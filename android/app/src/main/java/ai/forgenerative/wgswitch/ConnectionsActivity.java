package ai.forgenerative.wgswitch;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Who's connected right now: a live map plus a card per device. Refreshes every 5 seconds. */
public class ConnectionsActivity extends Activity {
    private static final long REFRESH_MS = 5_000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::load;
    private LinearLayout content;
    private ProgressBar progress;
    private SwipeRefreshLayout swipe;
    private NetworkMapView map;
    private boolean visible;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_device);  // same header + pull-to-refresh list as a device screen
        View root = findViewById(R.id.root);
        int side = root.getPaddingStart();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(side + bars.left, bars.top, side + bars.right, bars.bottom);
            return insets;
        });
        content = findViewById(R.id.content);
        progress = findViewById(R.id.progress);
        ((TextView) findViewById(R.id.title)).setText("Connections");
        ((TextView) findViewById(R.id.subtitle)).setText("Live · updates every 5 seconds");
        findViewById(R.id.back).setOnClickListener(v -> finish());
        swipe = findViewById(R.id.swipe);
        swipe.setColorSchemeColors(getColor(R.color.accent_start), getColor(R.color.accent_end));
        swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.card));
        swipe.setOnRefreshListener(this::load);
        map = new NetworkMapView(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        AppLock.require(this, this::load);
    }

    @Override
    protected void onPause() {
        visible = false;
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        handler.removeCallbacks(tick);
        if (content.getChildCount() == 0) progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                JSONObject res = new JSONObject(Api.call(this, "GET", "/connections"));
                JSONArray devices = res.optJSONArray("devices"), links = res.optJSONArray("links");
                runOnUiThread(() -> render(devices, links == null ? new JSONArray() : links));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    content.removeAllViews();
                    content.addView(note("Can't reach the server. Is the WireGuard tunnel on?"));
                });
            }
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                swipe.setRefreshing(false);
                if (visible) handler.postDelayed(tick, REFRESH_MS);
            });
        });
    }

    private void render(JSONArray devices, JSONArray links) {
        content.removeAllViews();
        map.setDevices(devices, links);
        if (map.getParent() == null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(280));
            lp.topMargin = dp(8);
            map.setLayoutParams(lp);
        }
        content.addView(map);

        content.addView(section(links.length() == 0 ? "No connections between devices" : "Active connections · " + links.length()));
        for (int i = 0; i < links.length(); i++) content.addView(linkCard(links.optJSONObject(i)));

        int online = 0;
        for (int i = 0; i < devices.length(); i++) if (devices.optJSONObject(i).optBoolean("online")) online++;
        content.addView(section(online == 0 ? "Nobody connected" : "Connected now · " + online));
        for (int i = 0; i < devices.length(); i++) {
            JSONObject dev = devices.optJSONObject(i);
            if (dev.optBoolean("online")) content.addView(card(dev));
        }
        if (online < devices.length()) {
            content.addView(section("Offline"));
            for (int i = 0; i < devices.length(); i++) {
                JSONObject dev = devices.optJSONObject(i);
                if (!dev.optBoolean("online")) content.addView(card(dev));
            }
        }
        content.addView(note("Every device connects through your VPN server: spokes are each device's tunnel, "
                + "arcs are connections between two of your devices."));
    }

    /** "work → homepc", Remote Desktop, how long, and data each way. */
    private View linkCard(JSONObject l) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_schedule, content, false);
        ImageView icon = row.findViewById(R.id.icon);
        int port = l.optInt("port");
        icon.setImageResource(port == 3389 ? R.drawable.ic_desktop : port == 22 ? R.drawable.ic_key : R.drawable.ic_hub);
        ((TextView) row.findViewById(R.id.title)).setText(l.optString("from") + "  →  " + l.optString("to"));
        double out = l.optDouble("sent_rate"), back = l.optDouble("received_rate");
        String detail = l.optString("service") + (port == 22 ? " (terminal or Remote Desktop tunnel)" : "")
                + "  ·  " + Api.duration(l.optLong("seconds"))
                + "\n→ " + Api.bytes(l.optDouble("sent")) + "   ← " + Api.bytes(l.optDouble("received"));
        if (out + back >= Api.BUSY_BYTES_PER_SEC) detail += "  ·  now → " + Api.rate(out) + "  ← " + Api.rate(back);
        ((TextView) row.findViewById(R.id.days)).setText(detail);
        row.findViewById(R.id.delete).setVisibility(View.GONE);  // nothing to delete; this is a live view
        return row;
    }

    private View card(JSONObject dev) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_device, content, false);
        String name = dev.optString("name");
        boolean on = dev.optBoolean("online");
        ImageView icon = row.findViewById(R.id.icon);
        icon.setImageResource(name.contains("phone") ? R.drawable.ic_phone
                : name.contains("laptop") ? R.drawable.ic_laptop : R.drawable.ic_desktop);
        int iconColor = getColor(on ? R.color.accent_start : R.color.offline);
        icon.setImageTintList(ColorStateList.valueOf(iconColor));
        row.findViewById(R.id.iconBg).setBackgroundTintList(ColorStateList.valueOf((iconColor & 0x00FFFFFF) | 0x26000000));
        ((TextView) row.findViewById(R.id.name)).setText(dev.optBoolean("self") ? name + "  ·  this phone" : name);
        if (!dev.isNull("domain")) {
            TextView d = row.findViewById(R.id.domain);
            d.setText(dev.optString("domain"));
            d.setVisibility(View.VISIBLE);
        }
        row.findViewById(R.id.dot).setBackgroundTintList(ColorStateList.valueOf(getColor(on ? R.color.online : R.color.offline)));
        String status;
        if (on) {
            status = "Connected " + Api.duration(dev.optLong("since_seconds"));
            double down = dev.optDouble("down_rate"), up = dev.optDouble("up_rate");
            status += down + up >= Api.BUSY_BYTES_PER_SEC
                    ? "\n↓ " + Api.rate(down) + "   ↑ " + Api.rate(up) : "\nIdle";
            status += String.format(Locale.ROOT, "  ·  this session ↓ %s ↑ %s",
                    Api.bytes(dev.optDouble("session_down")), Api.bytes(dev.optDouble("session_up")));
        } else {
            status = "Offline";
        }
        ((TextView) row.findViewById(R.id.status)).setText(status);
        // No on/off switch here; this page is for watching. Tap opens the device.
        Switch toggle = row.findViewById(R.id.toggle);
        toggle.setVisibility(View.GONE);
        row.setOnClickListener(v -> startActivity(new Intent(this, DeviceActivity.class).putExtra(DeviceActivity.EXTRA_PEER, name)));
        return row;
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text.toUpperCase(Locale.ROOT));
        t.setTextColor(getColor(R.color.text_muted));
        t.setTextSize(12);
        t.setLetterSpacing(0.15f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(dp(4), dp(20), 0, dp(10));
        return t;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.text_muted));
        t.setTextSize(13);
        t.setPadding(dp(4), dp(4), dp(4), dp(12));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
