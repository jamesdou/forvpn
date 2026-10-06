package ai.forgenerative.wgswitch;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lists the WireGuard peers on the server, with an on/off switch for each one. */
public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout list, hero;
    private TextView heroTitle, heroSub;
    private ImageView heroIcon;
    private ProgressBar progress;
    private SwipeRefreshLayout swipe;
    private boolean animateNext = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.root);
        int side = root.getPaddingStart();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(side + bars.left, bars.top, side + bars.right, bars.bottom);
            return insets;
        });

        list = findViewById(R.id.list);
        hero = findViewById(R.id.hero);
        heroTitle = findViewById(R.id.heroTitle);
        heroSub = findViewById(R.id.heroSub);
        heroIcon = findViewById(R.id.heroIcon);
        progress = findViewById(R.id.progress);
        swipe = findViewById(R.id.swipe);
        swipe.setColorSchemeColors(getColor(R.color.accent_start), getColor(R.color.accent_end));
        swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.card));
        swipe.setOnRefreshListener(this::refresh);
        findViewById(R.id.token).setOnClickListener(v -> askToken());
        findViewById(R.id.history).setOnClickListener(v -> startActivity(new Intent(this, DeviceActivity.class)));

        EventCheck.createChannels(this);
        try {
            EventCheck.schedule(this);
        } catch (RuntimeException e) {
            // Background alerts are a bonus; never let them stop the app from opening.
            Toast.makeText(this, "Background alerts unavailable: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

    }

    @Override
    protected void onResume() {
        super.onResume();
        // Nothing is shown or fetched until the biometric check passes.
        AppLock.require(this, () -> {
            if (token().isEmpty()) askToken();
            else refresh();
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private String token() {
        return Api.token(this);
    }

    private void askToken() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(token());
        FrameLayout box = new FrameLayout(this);
        int pad = dp(24);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Server token")
                .setMessage("Printed by install.sh on the server (/etc/wgctl/token).")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Api.prefs(this).edit().putString("token", input.getText().toString().trim()).apply();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (!loading) swipe.setRefreshing(false);
    }

    private void refresh() {
        setLoading(true);
        io.execute(() -> {
            try {
                JSONArray peers = new JSONArray(Api.call(this, "GET", "/peers"));
                runOnUiThread(() -> {
                    setLoading(false);
                    show(peers);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showError(e);
                });
                return;
            }
            try {
                // Catch up on alerts now rather than waiting for the background job.
                EventCheck.check(this);
            } catch (Exception ignored) {
                // The list loaded; a failed alert check just waits for the next one.
            }
        });
    }

    private void showError(Exception e) {
        hero.setBackgroundResource(R.drawable.bg_hero_error);
        heroIcon.setImageResource(R.drawable.ic_warning);
        boolean fromServer = e.getMessage() != null && e.getMessage().startsWith("Server said:");
        heroTitle.setText(fromServer ? "Server error" : "Can't reach server");
        heroSub.setText(fromServer ? e.getMessage().substring(13) : "Is the WireGuard tunnel on?");
    }

    private void show(JSONArray peers) {
        int online = 0, total = peers.length();
        list.removeAllViews();
        for (int i = 0; i < total; i++) {
            JSONObject p = peers.optJSONObject(i);
            if (state(p) == State.ONLINE) online++;
            if (p.optBoolean("self")) Api.prefs(this).edit().putString("self_name", p.optString("name")).apply();
            View row = row(p);
            list.addView(row);
            if (animateNext) {
                row.setAlpha(0f);
                row.setTranslationY(dp(24));
                row.animate().alpha(1f).translationY(0f).setStartDelay(80L * i)
                        .setDuration(350).setInterpolator(new DecelerateInterpolator()).start();
            }
        }
        // Only animate the first load; later refreshes would make the list jump.
        animateNext = false;

        hero.setBackgroundResource(R.drawable.bg_hero);
        heroIcon.setImageResource(R.drawable.ic_shield);
        heroTitle.setText(online + " of " + total + " online");
        heroSub.setText(online == total ? "Every device is connected" : "Tunnel to the server is up");
    }

    private enum State { ONLINE, IDLE, NEVER, OFF }

    private static State state(JSONObject p) {
        if (!p.optBoolean("enabled")) return State.OFF;
        if (p.isNull("handshake_ago")) return State.NEVER;
        // WireGuard re-handshakes every ~2 min while traffic flows; keepalive keeps it fresh.
        return p.optLong("handshake_ago") < 180 ? State.ONLINE : State.IDLE;
    }

    private View row(JSONObject p) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_device, list, false);
        String name = p.optString("name");
        boolean self = p.optBoolean("self");
        State st = state(p);

        int color = getColor(switch (st) {
            case ONLINE -> R.color.online;
            case IDLE -> R.color.idle;
            default -> R.color.offline;
        });

        ImageView icon = row.findViewById(R.id.icon);
        icon.setImageResource(name.contains("phone") ? R.drawable.ic_phone
                : name.contains("laptop") ? R.drawable.ic_laptop : R.drawable.ic_desktop);
        int iconColor = st == State.OFF ? getColor(R.color.offline) : getColor(R.color.accent_start);
        icon.setImageTintList(ColorStateList.valueOf(iconColor));
        row.findViewById(R.id.iconBg).setBackgroundTintList(
                ColorStateList.valueOf((iconColor & 0x00FFFFFF) | 0x26000000));

        ((TextView) row.findViewById(R.id.name)).setText(self ? name + "  ·  this phone" : name);
        JSONObject timer = p.optJSONObject("timer");
        ((TextView) row.findViewById(R.id.status)).setText(p.optString("ip").replace("/32", "") + "  ·  " + describe(st, p)
                + (timer != null ? "  ·  " + Api.timerText(timer) : "")
                + (p.optJSONObject("agent") != null && "tunnel".equals(p.optJSONObject("agent").optString("applied"))
                        ? "  ·  Tunnel only" : ""));

        String domain = p.optString("domain", "");
        if (!domain.isEmpty() && !p.isNull("domain")) {
            TextView d = row.findViewById(R.id.domain);
            d.setText(domain);
            d.setVisibility(View.VISIBLE);
            d.setOnLongClickListener(v -> {
                getSystemService(android.content.ClipboardManager.class)
                        .setPrimaryClip(android.content.ClipData.newPlainText("domain", domain));
                Toast.makeText(this, "Copied " + domain, Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        JSONObject pub = p.optJSONObject("public_ip");
        String pubText = Api.publicIpText(pub, "\n");
        if (pubText != null) {
            row.findViewById(R.id.publicRow).setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.publicIp)).setText(pubText);
            // Green globe = live address; grey = last one seen.
            ((ImageView) row.findViewById(R.id.publicIcon)).setImageTintList(ColorStateList.valueOf(
                    getColor(pub.optBoolean("current") ? R.color.online : R.color.offline)));
        }

        View dot = row.findViewById(R.id.dot);
        dot.setBackgroundTintList(ColorStateList.valueOf(color));
        if (st == State.ONLINE) {
            ObjectAnimator pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f);
            pulse.setDuration(1100);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.start();
        }

        Switch toggle = row.findViewById(R.id.toggle);
        toggle.setChecked(p.optBoolean("enabled"));
        // The server refuses this anyway; disabling it here makes the lockout guard visible.
        toggle.setEnabled(!self);
        toggle.setOnCheckedChangeListener((b, on) -> setPeer(name, on, toggle));
        row.setOnClickListener(v -> startActivity(new Intent(this, DeviceActivity.class).putExtra(DeviceActivity.EXTRA_PEER, name)));
        return row;
    }

    private static String describe(State st, JSONObject p) {
        long s = p.optLong("handshake_ago");
        return switch (st) {
            case OFF -> "Off";
            case NEVER -> "Never connected";
            case ONLINE -> "Online";
            case IDLE -> s < 3600 ? "Seen " + s / 60 + " min ago"
                    : s < 86400 ? "Seen " + s / 3600 + " h ago" : "Seen " + s / 86400 + " d ago";
        };
    }

    private void setPeer(String name, boolean on, Switch toggle) {
        toggle.setEnabled(false);
        setLoading(true);
        io.execute(() -> {
            try {
                Api.call(this, "POST", "/peers/" + name + (on ? "/enable" : "/disable"));
                runOnUiThread(this::refresh);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    refresh();  // redraw switches from the server's real state
                });
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
