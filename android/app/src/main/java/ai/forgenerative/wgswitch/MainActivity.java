package ai.forgenerative.wgswitch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

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
    private LinearLayout list;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("wgswitch", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("WireGuard devices");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, dp(4), 0, dp(12));
        root.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> refresh());
        Button token = new Button(this);
        token.setText("Set token");
        token.setOnClickListener(v -> askToken());
        buttons.addView(refresh);
        buttons.addView(token);
        root.addView(buttons);

        setContentView(root);
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
        new AlertDialog.Builder(this)
                .setTitle("Server token")
                .setMessage("Printed by install.sh on the server (/etc/wgctl/token).")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit().putString("token", input.getText().toString().trim()).apply();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refresh() {
        status.setText("Loading…");
        io.execute(() -> {
            try {
                JSONArray peers = new JSONArray(call("GET", "/peers"));
                runOnUiThread(() -> show(peers));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText(explain(e)));
            }
        });
    }

    private void show(JSONArray peers) {
        list.removeAllViews();
        for (int i = 0; i < peers.length(); i++) {
            list.addView(row(peers.optJSONObject(i)));
        }
        status.setText("Turning a device off only removes that device's key on the server.");
    }

    private LinearLayout row(JSONObject p) {
        String name = p.optString("name");
        boolean self = p.optBoolean("self");

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(self ? name + " (this phone)" : name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        TextView sub = new TextView(this);
        sub.setText(p.optString("ip") + " · " + lastSeen(p));
        text.addView(label);
        text.addView(sub);
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(p.optBoolean("enabled"));
        // The server refuses this anyway; disabling it here makes the lockout guard visible.
        toggle.setEnabled(!self);
        toggle.setOnCheckedChangeListener((b, on) -> setPeer(name, on, toggle));
        row.addView(toggle);
        return row;
    }

    private static String lastSeen(JSONObject p) {
        if (!p.optBoolean("enabled")) return "off";
        if (p.isNull("handshake_ago")) return "never connected";
        long s = p.optLong("handshake_ago");
        // WireGuard re-handshakes every ~2 min while traffic flows; keepalive keeps it fresh.
        if (s < 180) return "online";
        if (s < 3600) return "seen " + s / 60 + " min ago";
        if (s < 86400) return "seen " + s / 3600 + " h ago";
        return "seen " + s / 86400 + " d ago";
    }

    private void setPeer(String name, boolean on, Switch toggle) {
        toggle.setEnabled(false);
        status.setText((on ? "Enabling " : "Disabling ") + name + "…");
        io.execute(() -> {
            try {
                call("POST", "/peers/" + name + (on ? "/enable" : "/disable"));
                runOnUiThread(this::refresh);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText(explain(e));
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

    private static String explain(Exception e) {
        if (e.getMessage() != null && e.getMessage().startsWith("Server said:")) return e.getMessage();
        return "Can't reach 10.100.0.1 — is the WireGuard tunnel on? (" + e.getClass().getSimpleName() + ")";
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
