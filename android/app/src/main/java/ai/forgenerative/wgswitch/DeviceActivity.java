package ai.forgenerative.wgswitch;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One device: timers, daily schedules and its activity history.
 * Started without a "peer" extra it shows the history of all devices.
 */
public class DeviceActivity extends Activity {
    static final String EXTRA_PEER = "peer";
    private static final int[] TIMER_MINUTES = {30, 60, 120, 240};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String peer;  // null = all devices
    private LinearLayout content;
    private TextView title, subtitle;
    private ProgressBar progress;
    private SwipeRefreshLayout swipe;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_device);
        View root = findViewById(R.id.root);
        int side = root.getPaddingStart();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(side + bars.left, bars.top, side + bars.right, bars.bottom);
            return insets;
        });

        peer = getIntent().getStringExtra(EXTRA_PEER);
        content = findViewById(R.id.content);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        progress = findViewById(R.id.progress);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        swipe = findViewById(R.id.swipe);
        swipe.setColorSchemeColors(getColor(R.color.accent_start), getColor(R.color.accent_end));
        swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.card));
        swipe.setOnRefreshListener(this::load);
        title.setText(peer != null ? peer : "Activity");
        subtitle.setText(peer != null ? "" : "All devices · last 90 days");
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLock.require(this, this::load);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                JSONObject device = null, traffic = null;
                JSONArray schedules = new JSONArray();
                if (peer != null) {
                    traffic = new JSONObject(Api.call(this, "GET", "/peers/" + peer + "/traffic?" + Api.tzQuery()));
                    JSONArray peers = new JSONArray(Api.call(this, "GET", "/peers"));
                    for (int i = 0; i < peers.length(); i++) {
                        if (peer.equals(peers.getJSONObject(i).optString("name"))) device = peers.getJSONObject(i);
                    }
                    JSONArray all = new JSONArray(Api.call(this, "GET", "/schedules"));
                    for (int i = 0; i < all.length(); i++) {
                        if (peer.equals(all.getJSONObject(i).optString("peer"))) schedules.put(all.getJSONObject(i));
                    }
                }
                String q = "/events?limit=100" + (peer != null ? "&peer=" + peer : "");
                JSONArray events = new JSONObject(Api.call(this, "GET", q)).optJSONArray("events");
                JSONObject d = device, t = traffic;
                JSONArray s = schedules;
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    swipe.setRefreshing(false);
                    render(d, s, events, t);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    swipe.setRefreshing(false);
                    content.removeAllViews();
                    content.addView(note(e.getMessage() != null && e.getMessage().startsWith("Server said:")
                            ? e.getMessage() : "Can't reach the server. Is the WireGuard tunnel on?"));
                });
            }
        });
    }

    private void render(JSONObject device, JSONArray schedules, JSONArray events, JSONObject traffic) {
        content.removeAllViews();
        if (device != null) {
            boolean self = device.optBoolean("self");
            boolean enabled = device.optBoolean("enabled");
            String pub = Api.publicIpText(device.optJSONObject("public_ip"), "  ·  ");
            String domain = device.isNull("domain") ? "" : device.optString("domain");
            subtitle.setText((domain.isEmpty() ? "" : domain + "\n")
                    + device.optString("ip").replace("/32", "") + "  ·  " + (enabled ? "On" : "Off")
                    + (pub != null ? "\nPublic: " + pub : ""));

            JSONObject timer = device.optJSONObject("timer");
            if (timer != null) content.addView(timerBanner(timer, enabled));
            if (traffic != null) trafficSection(traffic);

            if (self) {
                content.addView(note("This is the phone you're using, so it can't be turned off from here."));
            } else {
                content.addView(section("Turn on for"));
                content.addView(chipRow(true));
                content.addView(section("Turn off for"));
                content.addView(chipRow(false));
            }

            JSONObject agent = device.optJSONObject("agent");
            if (agent != null) {
                content.addView(section("Remote Desktop access"));
                content.addView(rdpModeRow(agent));
                content.addView(note(rdpStatus(agent)));

                content.addView(section("SSH server"));
                content.addView(sshRow(agent));
                content.addView(section("Terminal access"));
                content.addView(shellRow(agent));
                content.addView(section("SSH keys"));
                JSONArray keys = agent.optJSONArray("keys");
                for (int i = 0; keys != null && i < keys.length(); i++) content.addView(keyRow(keys.optJSONObject(i)));
                if (keys == null || keys.length() == 0) content.addView(note("No keys yet. Nothing can use the tunnel until you add one."));
                if (agent.optBoolean("keys_pending")) content.addView(note("Applying key changes… the PC picks them up within about 10 seconds."));
                content.addView(outlineButton("+  Add key", v -> addKey()));
            }

            content.addView(section("Daily schedules"));
            for (int i = 0; i < schedules.length(); i++) content.addView(scheduleRow(schedules.optJSONObject(i)));
            if (schedules.length() == 0) content.addView(note("No schedules yet."));
            content.addView(outlineButton("+  Add schedule", v -> addSchedule(self)));
        }

        content.addView(section(device != null ? "Activity" : "Recent events"));
        for (int i = 0; i < events.length(); i++) content.addView(eventRow(events.optJSONObject(i)));
        if (events.length() == 0) {
            content.addView(note("No activity recorded yet. Connections, disconnections and changes show up here."));
        }
    }

    // ------------------------------------------------------------ traffic

    private void trafficSection(JSONObject t) {
        content.addView(section("Traffic"));

        // Live speed as two stat tiles (download is what most people care about, so it's first).
        LinearLayout tiles = new LinearLayout(this);
        tiles.addView(statTile("Download now", Api.rate(t.optDouble("down_rate"))), tileParams(0));
        tiles.addView(statTile("Upload now", Api.rate(t.optDouble("up_rate"))), tileParams(1));
        content.addView(tiles);

        JSONObject session = t.optJSONObject("session");
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setBackgroundResource(R.drawable.bg_card);
        table.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(10);
        table.setLayoutParams(tp);
        if (session != null) {
            table.addView(usageRow("This session (" + Api.duration(session.optLong("seconds")) + ")", session));
        }
        table.addView(usageRow("Today", t.optJSONObject("today")));
        table.addView(usageRow("Last 7 days", t.optJSONObject("week")));
        table.addView(usageRow("This month", t.optJSONObject("month")));
        content.addView(table);

        JSONArray days = t.optJSONArray("days");
        if (days != null && days.length() > 0) {
            content.addView(section("Data per day · last 14 days"));
            UsageChartView chart = new UsageChartView(this);
            TextView readout = note("");
            chart.setDays(days, day -> readout.setText(UsageChartView.shortDate(day.optString("date"))
                    + "   ↓ " + Api.bytes(day.optDouble("down")) + "   ↑ " + Api.bytes(day.optDouble("up"))
                    + "   total " + Api.bytes(day.optDouble("down") + day.optDouble("up"))));
            content.addView(chart, new LinearLayout.LayoutParams(-1, dp(150)));
            content.addView(readout);
        }
    }

    private LinearLayout.LayoutParams tileParams(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (index > 0) lp.leftMargin = dp(10);
        return lp;
    }

    /** Stat tile: label, then the value large. */
    private View statTile(String label, String value) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackgroundResource(R.drawable.bg_card);
        tile.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(getColor(R.color.text_muted));
        l.setTextSize(12);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(getColor(R.color.text));
        v.setTextSize(22);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        tile.addView(l);
        tile.addView(v);
        return tile;
    }

    private View usageRow(String label, JSONObject u) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(9), 0, dp(9));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(getColor(R.color.text_muted));
        l.setTextSize(14);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(this);
        double down = u == null ? 0 : u.optDouble("down"), up = u == null ? 0 : u.optDouble("up");
        v.setText("↓ " + Api.bytes(down) + "   ↑ " + Api.bytes(up));
        v.setTextColor(getColor(R.color.text));
        v.setTextSize(14);
        row.addView(v);
        return row;
    }

    // ------------------------------------------------------------ pieces

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text.toUpperCase(Locale.ROOT));
        t.setTextColor(getColor(R.color.text_muted));
        t.setTextSize(12);
        t.setLetterSpacing(0.15f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(dp(4), dp(24), 0, dp(10));
        return t;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.text_muted));
        t.setTextSize(14);
        t.setPadding(dp(4), dp(4), dp(4), dp(8));
        return t;
    }

    private View timerBanner(JSONObject timer, boolean enabled) {
        LinearLayout b = new LinearLayout(this);
        b.setBackgroundResource(R.drawable.bg_banner);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), dp(12), dp(8), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        b.setLayoutParams(lp);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.drawable.ic_timer);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent_start)));
        b.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView t = new TextView(this);
        t.setText(Api.timerText(timer));
        t.setTextColor(getColor(R.color.text));
        t.setTextSize(15);
        t.setPadding(dp(12), 0, 0, 0);
        b.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView cancel = new TextView(this);
        cancel.setText("Cancel");
        cancel.setTextColor(getColor(R.color.accent_start));
        cancel.setTypeface(cancel.getTypeface(), android.graphics.Typeface.BOLD);
        cancel.setPadding(dp(12), dp(8), dp(12), dp(8));
        cancel.setBackgroundResource(R.drawable.bg_glass_circle);
        // Re-sending the current state without minutes clears the timer on the server.
        cancel.setOnClickListener(v -> post("/peers/" + peer + (enabled ? "/enable" : "/disable"), null, "Timer cancelled"));
        b.addView(cancel);
        return b;
    }

    private View chipRow(boolean enable) {
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < TIMER_MINUTES.length; i++) {
            int minutes = TIMER_MINUTES[i];
            TextView chip = chip(minutes < 60 ? minutes + " min" : minutes / 60 + " h");
            chip.setOnClickListener(v -> {
                JSONObject body = new JSONObject();
                try {
                    body.put("minutes", minutes);
                } catch (Exception ignored) {
                }
                String label = (enable ? "On" : "Off") + " for " + Api.duration(minutes * 60L);
                post("/peers/" + peer + (enable ? "/enable" : "/disable"), body, label);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(chip, lp);
        }
        return row;
    }

    /** Direct / Tunnel only switch for PCs running the VPN Switch helper. */
    private View rdpModeRow(JSONObject agent) {
        String mode = agent.optString("rdp_mode", "direct");
        LinearLayout row = new LinearLayout(this);
        String[][] options = {{"direct", "Direct"}, {"tunnel", "Tunnel only"}};
        for (int i = 0; i < options.length; i++) {
            String value = options[i][0];
            TextView chip = chip(options[i][1]);
            chip.setSelected(value.equals(mode));
            chip.setOnClickListener(v -> {
                if (value.equals(mode)) return;
                if (value.equals("tunnel")) {
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Tunnel only?")
                            .setMessage("Direct Remote Desktop connections to " + peer + " will be blocked, "
                                    + "including any session connected directly right now.\n\n"
                                    + "Connect through the SSH tunnel (rdp-via-tunnel.bat) instead.")
                            .setPositiveButton("Switch", (dlg, w) -> setRdpMode(value))
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    setRdpMode(value);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(chip, lp);
        }
        return row;
    }

    private static String rdpStatus(JSONObject agent) {
        String wanted = agent.optString("rdp_mode");
        String applied = agent.optString("applied");
        long ago = agent.optLong("seen_ago");
        String status;
        if (!agent.isNull("error") && !agent.optString("error").isEmpty()) {
            status = "Problem: " + agent.optString("error");
        } else if (!wanted.equals(applied)) {
            status = "Applying… the PC picks up changes within about 10 seconds.";
        } else {
            status = "tunnel".equals(applied)
                    ? "Only connections through the SSH tunnel are accepted."
                    : "Remote Desktop accepts direct connections from your VPN devices.";
        }
        String seen = ago < 60 ? "just now" : Api.duration(ago) + " ago";
        status += "\nHelper last checked in " + seen + (agent.optBoolean("sshd") ? " · SSH running" : " · SSH not running");
        if (ago > 120) status += "\nThe PC may be off or offline; changes apply when it's back.";
        return status;
    }

    private View sshRow(JSONObject agent) {
        boolean on = agent.optBoolean("ssh_enabled", true);
        boolean tunnel = "tunnel".equals(agent.optString("rdp_mode"));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        String[][] options = {{"on", "On"}, {"off", "Off"}};
        for (int i = 0; i < options.length; i++) {
            boolean value = options[i][0].equals("on");
            TextView chip = chip(options[i][1]);
            chip.setSelected(value == on);
            chip.setOnClickListener(v -> {
                if (value == on) return;
                if (!value && tunnel) {
                    Toast.makeText(this, "Switch Remote Desktop to Direct first; tunnel only needs SSH", Toast.LENGTH_LONG).show();
                    return;
                }
                JSONObject body = new JSONObject();
                try {
                    body.put("enabled", value);
                } catch (Exception ignored) {
                }
                post("/peers/" + peer + "/ssh", body, value ? "Turning SSH on…" : "Turning SSH off…");
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(chip, lp);
        }
        box.addView(row);
        String state = agent.optBoolean("sshd") ? "Running" : "Stopped";
        box.addView(note(state + (tunnel ? " · needed for tunnel only, so it stays on" : on ? "" : " · nobody can use the tunnel while it's off")));
        return box;
    }

    /** SSH shell as your own account: needs a device key AND the Windows password. */
    private View shellRow(JSONObject agent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (agent.isNull("shell_enabled")) {
            box.addView(note("Not set up on this PC yet."));
            return box;
        }
        boolean on = agent.optBoolean("shell_enabled");
        LinearLayout row = new LinearLayout(this);
        String[][] options = {{"on", "On"}, {"off", "Off"}};
        for (int i = 0; i < options.length; i++) {
            boolean value = options[i][0].equals("on");
            TextView chip = chip(options[i][1]);
            chip.setSelected(value == on);
            chip.setOnClickListener(v -> {
                if (value == on) return;
                if (value) {
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Turn on terminal access?")
                            .setMessage("Anyone with one of your device keys AND your Windows password could open "
                                    + "an administrator command prompt on " + peer + " over the VPN.")
                            .setPositiveButton("Turn on", (dlg, w) -> setShell(true))
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    setShell(false);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(chip, lp);
        }
        box.addView(row);
        boolean applied = agent.optBoolean("shell_applied") == on && !agent.isNull("shell_applied");
        String text = on ? "Log in with your Windows username, a device key and your Windows password."
                : "Only Remote Desktop forwarding is allowed over SSH.";
        if (!applied) text = "Applying… the PC picks this up within about 10 seconds.";
        else if (on && !agent.optBoolean("sshd")) text += "\nThe SSH server is off, so turn it on to use this.";
        box.addView(note(text));
        return box;
    }

    private void setShell(boolean on) {
        JSONObject body = new JSONObject();
        try {
            body.put("enabled", on);
        } catch (Exception ignored) {
        }
        post("/peers/" + peer + "/shell", body, on ? "Turning terminal access on…" : "Turning terminal access off…");
    }

    private View keyRow(JSONObject k) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_schedule, content, false);
        ((android.widget.ImageView) row.findViewById(R.id.icon)).setImageResource(R.drawable.ic_key);
        String name = k.optString("name");
        ((TextView) row.findViewById(R.id.title)).setText(name);
        String fp = k.optString("fingerprint");
        ((TextView) row.findViewById(R.id.days)).setText(k.optString("type") + "  ·  "
                + (fp.length() > 22 ? fp.substring(0, 22) + "…" : fp) + (k.optBoolean("applied") ? "" : "  ·  applying"));
        row.findViewById(R.id.delete).setContentDescription("Remove key " + name);
        row.findViewById(R.id.delete).setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Remove key " + name + "?")
                .setMessage("That device will no longer be able to open the SSH tunnel to " + peer + ".")
                .setPositiveButton("Remove", (dlg, w) -> call("DELETE", "/peers/" + peer + "/keys/" + name, null, "Key removed"))
                .setNegativeButton("Cancel", null)
                .show());
        return row;
    }

    private void addKey() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        android.widget.EditText name = new android.widget.EditText(this);
        name.setHint("Device name, e.g. laptop");
        name.setSingleLine(true);
        android.widget.EditText key = new android.widget.EditText(this);
        key.setHint("ssh-ed25519 AAAA…");
        key.setMinLines(3);
        key.setTypeface(android.graphics.Typeface.MONOSPACE);
        key.setTextSize(13);
        box.addView(name);
        box.addView(key);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Add SSH key")
                .setMessage("Paste the device's PUBLIC key (the .pub file). Never the private key.")
                .setView(box)
                .setPositiveButton("Add", (dlg, w) -> {
                    JSONObject body = new JSONObject();
                    try {
                        body.put("name", name.getText().toString().trim());
                        body.put("key", key.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    post("/peers/" + peer + "/keys", body, "Key added");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setRdpMode(String mode) {
        JSONObject body = new JSONObject();
        try {
            body.put("mode", mode);
        } catch (Exception ignored) {
        }
        post("/peers/" + peer + "/rdp_mode", body, "tunnel".equals(mode) ? "Switching to tunnel only…" : "Switching to direct…");
    }

    private TextView chip(String text) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setGravity(Gravity.CENTER);
        c.setTextColor(getColor(R.color.text));
        c.setTextSize(14);
        c.setBackgroundResource(R.drawable.bg_chip);
        return c;
    }

    private View outlineButton(String text, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(getColor(R.color.accent_start));
        b.setTextSize(15);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setBackgroundResource(R.drawable.bg_button_outline);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private View scheduleRow(JSONObject s) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_schedule, content, false);
        boolean on = "enable".equals(s.optString("action"));
        ((TextView) row.findViewById(R.id.title)).setText((on ? "On at " : "Off at ") + s.optString("time"));
        ((TextView) row.findViewById(R.id.days)).setText(daysText(s.optString("days")));
        row.findViewById(R.id.delete).setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Delete schedule?")
                .setPositiveButton("Delete", (d, w) -> call("DELETE", "/schedules/" + s.optLong("id"), null, "Schedule deleted"))
                .setNegativeButton("Cancel", null)
                .show());
        return row;
    }

    private static String daysText(String days) {
        if (days.equals("0123456")) return "Every day";
        if (days.equals("01234")) return "Weekdays";
        if (days.equals("56")) return "Weekends";
        String[] names = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        StringBuilder b = new StringBuilder();
        for (char c : days.toCharArray()) b.append(b.length() > 0 ? ", " : "").append(names[c - '0']);
        return b.toString();
    }

    private View eventRow(JSONObject e) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_event, content, false);
        String kind = e.optString("kind");
        int color = getColor(switch (kind) {
            case "connected", "reconnected" -> R.color.online;
            case "new_ip", "ssh_key_added" -> R.color.danger_start;
            case "enabled", "rdp_mode" -> R.color.accent_start;
            default -> R.color.offline;
        });
        row.findViewById(R.id.dot).setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        ((TextView) row.findViewById(R.id.title)).setText(EventCheck.title(e));
        TextView detail = row.findViewById(R.id.detail);
        String d = e.optString("detail");
        if ("app".equals(e.optString("source"))) d = d.isEmpty() ? "from the app" : d;
        detail.setText(d);
        detail.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
        long ms = (long) (e.optDouble("ts") * 1000);
        ((TextView) row.findViewById(R.id.time)).setText(
                DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE));
        return row;
    }

    // ------------------------------------------------------------ actions

    private void addSchedule(boolean self) {
        // Step 1: on or off (off is impossible for this phone).
        String[] actions = self ? new String[]{"Turn on"} : new String[]{"Turn off", "Turn on"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Schedule for " + peer)
                .setItems(actions, (d, which) -> {
                    boolean on = actions[which].equals("Turn on");
                    // Step 2: time.
                    new TimePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert, (tp, h, m) -> {
                        // Step 3: days.
                        String[] labels = {"Every day", "Weekdays", "Weekends"};
                        String[] codes = {"0123456", "01234", "56"};
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle(actions[which] + " at " + String.format(Locale.ROOT, "%02d:%02d", h, m))
                                .setItems(labels, (d2, day) -> {
                                    JSONObject body = new JSONObject();
                                    try {
                                        body.put("peer", peer);
                                        body.put("action", on ? "enable" : "disable");
                                        body.put("time", String.format(Locale.ROOT, "%02d:%02d", h, m));
                                        body.put("days", codes[day]);
                                        body.put("tz", TimeZone.getDefault().getID());
                                    } catch (Exception ignored) {
                                    }
                                    post("/schedules", body, "Schedule added");
                                })
                                .show();
                    }, 1, 0, android.text.format.DateFormat.is24HourFormat(this)).show();
                })
                .show();
    }

    private void post(String path, JSONObject body, String done) {
        call("POST", path, body, done);
    }

    private void call(String method, String path, JSONObject body, String done) {
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            String msg;
            try {
                Api.call(this, method, path, body);
                msg = done;
            } catch (Exception e) {
                msg = e.getMessage() != null ? e.getMessage() : "Request failed";
            }
            String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
                load();
            });
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
