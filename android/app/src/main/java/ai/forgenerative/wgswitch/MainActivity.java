package ai.forgenerative.wgswitch;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lists the WireGuard peers on the server, with an on/off switch for each one. */
public class MainActivity extends Activity {
    // Only reachable through the tunnel, so the phone's WireGuard must be on.
    private static final String API = "http://10.100.0.1:8080";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout list, hero;
    private TextView heroTitle, heroSub;
    private ImageView heroIcon, refreshIcon;
    private ProgressBar progress;
    private ObjectAnimator spin;
    private boolean animateNext = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("wgswitch", MODE_PRIVATE);

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
        refreshIcon = findViewById(R.id.refreshIcon);
        findViewById(R.id.refresh).setOnClickListener(v -> refresh());
        findViewById(R.id.token).setOnClickListener(v -> askToken());

        spin = ObjectAnimator.ofFloat(refreshIcon, View.ROTATION, 0f, 360f);
        spin.setDuration(800);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (token().isEmpty()) askToken();
        else refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private String token() {
        return prefs.getString("token", "");
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
                    prefs.edit().putString("token", input.getText().toString().trim()).apply();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            spin.start();
        } else {
            spin.cancel();
            refreshIcon.animate().rotation(0f).setDuration(200).start();
        }
    }

    private void refresh() {
        setLoading(true);
        io.execute(() -> {
            try {
                JSONArray peers = new JSONArray(call("GET", "/peers"));
                runOnUiThread(() -> {
                    setLoading(false);
                    show(peers);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showError(e);
                });
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
        ((TextView) row.findViewById(R.id.status)).setText(p.optString("ip").replace("/32", "") + "  ·  " + describe(st, p));

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
        row.setOnClickListener(v -> { if (toggle.isEnabled()) toggle.toggle(); });
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
                call("POST", "/peers/" + name + (on ? "/enable" : "/disable"));
                runOnUiThread(this::refresh);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    refresh();  // redraw switches from the server's real state
                });
            }
        });
    }

    private String call(String method, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Authorization", "Bearer " + token());
            int code = c.getResponseCode();
            String body = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400) {
                String msg = body;
                try {
                    msg = new JSONObject(body).optString("error", body);
                } catch (Exception ignored) {
                }
                throw new IOException("Server said: " + msg);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
