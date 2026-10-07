package ai.forgenerative.wgswitch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Talks to wgctl on the server. Call off the main thread. */
final class Api {
    // Only reachable through the tunnel, so the phone's WireGuard must be on.
    private static final String BASE = "http://10.100.0.1:8080";

    private Api() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("wgswitch", Context.MODE_PRIVATE);
    }

    static String token(Context c) {
        return prefs(c).getString("token", "");
    }

    static String call(Context ctx, String method, String path, JSONObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Authorization", "Bearer " + token(ctx));
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(data);
                }
            }
            int code = c.getResponseCode();
            String text = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400) {
                String msg = text;
                try {
                    msg = new JSONObject(text).optString("error", text);
                } catch (Exception ignored) {
                }
                throw new IOException("Server said: " + msg);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    static String call(Context ctx, String method, String path) throws IOException {
        return call(ctx, method, path, null);
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

    /** "1 h 52 min", "5 min", "2 d 3 h". */
    static String duration(long seconds) {
        long m = Math.max(1, (seconds + 59) / 60);
        if (m < 60) return m + " min";
        long h = m / 60;
        if (h < 24) return h + " h" + (m % 60 > 0 ? " " + m % 60 + " min" : "");
        return h / 24 + " d" + (h % 24 > 0 ? " " + h % 24 + " h" : "");
    }

    /** "99.48.87.243" while connected, "Last 50.247.9.177" + sep + "2 h ago" once offline, null if never seen. */
    static String publicIpText(JSONObject pub, String sep) {
        if (pub == null) return null;
        if (pub.optBoolean("current")) return pub.optString("ip");
        long ago = pub.optLong("ago");
        return "Last " + pub.optString("ip") + sep + (ago < 60 ? "just now" : duration(ago) + " ago");
    }

    /** Network speed from bytes/second, in bits like ISPs quote: "2.1 Mb/s", "80 kb/s". */
    static String rate(double bytesPerSec) {
        double bits = bytesPerSec * 8;
        if (bits >= 1e9) return String.format(java.util.Locale.ROOT, "%.1f Gb/s", bits / 1e9);
        if (bits >= 1e6) return String.format(java.util.Locale.ROOT, "%.1f Mb/s", bits / 1e6);
        if (bits >= 1e3) return String.format(java.util.Locale.ROOT, "%.0f kb/s", bits / 1e3);
        return String.format(java.util.Locale.ROOT, "%.0f b/s", bits);
    }

    /** Data amount: "340 MB", "1.2 GB". */
    static String bytes(double n) {
        if (n >= 1e12) return String.format(java.util.Locale.ROOT, "%.1f TB", n / 1e12);
        if (n >= 1e9) return String.format(java.util.Locale.ROOT, "%.1f GB", n / 1e9);
        if (n >= 1e6) return String.format(java.util.Locale.ROOT, "%.0f MB", n / 1e6);
        if (n >= 1e3) return String.format(java.util.Locale.ROOT, "%.0f KB", n / 1e3);
        return String.format(java.util.Locale.ROOT, "%.0f B", n);
    }

    /** Below this a tunnel is only exchanging keepalives, so live speed isn't worth showing. */
    static final double BUSY_BYTES_PER_SEC = 2_000;

    /** Phone's time zone, so "today" on the server means today here. */
    static String tzQuery() {
        return "tz=" + java.net.URLEncoder.encode(java.util.TimeZone.getDefault().getID(), StandardCharsets.UTF_8);
    }

    /** Short description of a pending timer, e.g. "Off in 1 h 52 min". */
    static String timerText(JSONObject timer) {
        return ("disable".equals(timer.optString("action")) ? "Off in " : "On in ")
                + duration(timer.optLong("in"));
    }
}
