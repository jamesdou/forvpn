package ai.forgenerative.wgswitch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Live map: the VPN server in the middle, devices around it. A connected device's link
 * glows, and dots stream along it while data moves - toward the device for downloads,
 * toward the server for uploads. Every device's traffic goes through the server.
 */
class NetworkMapView extends View {
    private final float d = getResources().getDisplayMetrics().density;
    private final Paint link = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint node = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int online, offline, accent, accentEnd, card, textColor, muted;
    private JSONArray devices = new JSONArray();

    NetworkMapView(Context c) {
        super(c);
        online = c.getColor(R.color.online);
        offline = c.getColor(R.color.offline);
        accent = c.getColor(R.color.accent_start);
        accentEnd = c.getColor(R.color.accent_end);
        card = c.getColor(R.color.card);
        textColor = c.getColor(R.color.text);
        muted = c.getColor(R.color.text_muted);
        link.setStrokeCap(Paint.Cap.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * d);
        label.setTextSize(12 * d);
        label.setTextAlign(Paint.Align.CENTER);
        setMinimumHeight(Math.round(280 * d));
    }

    void setDevices(JSONArray devices) {
        this.devices = devices;
        StringBuilder desc = new StringBuilder("Network map. ");
        for (int i = 0; i < devices.length(); i++) {
            JSONObject dev = devices.optJSONObject(i);
            desc.append(dev.optString("name")).append(dev.optBoolean("online") ? " connected. " : " offline. ");
        }
        setContentDescription(desc);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - 40 * d;
        int n = devices.length();
        long now = SystemClock.uptimeMillis();
        boolean animating = false;

        for (int i = 0; i < n; i++) {
            JSONObject dev = devices.optJSONObject(i);
            double angle = -Math.PI / 2 + 2 * Math.PI * i / Math.max(1, n);
            float x = cx + (float) (Math.cos(angle) * radius), y = cy + (float) (Math.sin(angle) * radius);
            boolean on = dev.optBoolean("online");
            double down = dev.optDouble("down_rate"), up = dev.optDouble("up_rate");
            boolean busy = on && down + up >= Api.BUSY_BYTES_PER_SEC;

            // Link: thicker the busier it is.
            float width = !on ? 1.5f * d : busy ? (float) (3 + Math.min(4, Math.log10(Math.max(1, down + up)) - 3)) * d : 2.5f * d;
            link.setStrokeWidth(width);
            link.setShader(on ? new LinearGradient(cx, cy, x, y, accent, online, Shader.TileMode.CLAMP) : null);
            link.setColor(on ? accent : offline);
            link.setAlpha(on ? 255 : 90);
            c.drawLine(cx, cy, x, y, link);

            // Flow: dots travel server -> device for download, device -> server for upload.
            if (busy) {
                animating = true;
                streamDots(c, cx, cy, x, y, now, down, false);
                streamDots(c, cx, cy, x, y, now, up, true);
            }

            // Device node.
            float nr = 20 * d;
            node.setColor(card);
            c.drawCircle(x, y, nr, node);
            ring.setColor(on ? online : offline);
            c.drawCircle(x, y, nr, ring);
            node.setColor(on ? online : offline);
            c.drawCircle(x, y, 5 * d, node);
            label.setColor(on ? textColor : muted);
            float ly = y + nr + 16 * d;
            if (ly > getHeight() - 4 * d) ly = y - nr - 8 * d;
            c.drawText(dev.optString("name"), x, ly, label);
        }

        // The VPN server hub.
        float hr = 30 * d;
        hub.setShader(new LinearGradient(cx - hr, cy - hr, cx + hr, cy + hr, accent, accentEnd, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, hr, hub);
        label.setColor(0xFFFFFFFF);
        label.setTextSize(11 * d);
        c.drawText("VPN", cx, cy + 4 * d, label);
        label.setTextSize(12 * d);

        if (animating) postInvalidateOnAnimation();
    }

    private void streamDots(Canvas c, float cx, float cy, float x, float y, long now, double rate, boolean toServer) {
        if (rate < Api.BUSY_BYTES_PER_SEC / 2) return;
        int count = rate > 1e6 ? 4 : rate > 1e5 ? 3 : 2;
        float speed = rate > 1e6 ? 900 : rate > 1e5 ? 1300 : 1800;  // ms per trip
        dot.setColor(toServer ? accentEnd : 0xFFFFFFFF);
        for (int k = 0; k < count; k++) {
            float t = ((now / speed) + k / (float) count) % 1f;
            if (toServer) t = 1 - t;
            // Keep dots off the node circles at either end.
            float tt = 0.18f + t * 0.64f;
            c.drawCircle(cx + (x - cx) * tt, cy + (y - cy) * tt, (toServer ? 2.5f : 3f) * d, dot);
        }
    }
}
