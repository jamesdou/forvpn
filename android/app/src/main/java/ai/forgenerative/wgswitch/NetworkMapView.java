package ai.forgenerative.wgswitch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Live map: the VPN server in the middle, devices around it. A connected device's link
 * glows, and dots stream along it while data moves - toward the device for downloads,
 * toward the server for uploads. Device-to-device connections (e.g. Remote Desktop) are
 * drawn as curved arcs between the two devices, with dots running from the one that connected.
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
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private JSONArray devices = new JSONArray();
    private JSONArray links = new JSONArray();

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
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(2.5f * d);
        arc.setColor(accentEnd);
        arcLabel.setTextSize(10 * d);
        arcLabel.setTextAlign(Paint.Align.CENTER);
        arcLabel.setColor(textColor);
        pill.setStrokeWidth(1 * d);
        setMinimumHeight(Math.round(280 * d));
    }

    void setDevices(JSONArray devices, JSONArray links) {
        this.devices = devices;
        this.links = links == null ? new JSONArray() : links;
        StringBuilder desc = new StringBuilder("Network map. ");
        for (int i = 0; i < devices.length(); i++) {
            JSONObject dev = devices.optJSONObject(i);
            desc.append(dev.optString("name")).append(dev.optBoolean("online") ? " connected. " : " offline. ");
        }
        for (int i = 0; i < this.links.length(); i++) {
            JSONObject l = this.links.optJSONObject(i);
            desc.append(l.optString("from")).append(" to ").append(l.optString("to")).append(' ')
                    .append(l.optString("service")).append(". ");
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

        // Device-to-device connections: one arc per pair of devices (several connections between the
        // same two would otherwise draw on top of each other), bowed away from the hub so the
        // spokes stay visible. Labels are collected and drawn last, on a pill, above everything.
        java.util.LinkedHashMap<String, java.util.List<JSONObject>> pairs = new java.util.LinkedHashMap<>();
        for (int i = 0; i < links.length(); i++) {
            JSONObject l = links.optJSONObject(i);
            String a = l.optString("from"), b = l.optString("to");
            String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
            pairs.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(l);
        }
        java.util.List<Object[]> pendingLabels = new java.util.ArrayList<>();
        for (java.util.List<JSONObject> group : pairs.values()) {
            // Lead with a named service (Remote Desktop, SSH...) over a bare port number.
            JSONObject lead = group.get(0);
            for (JSONObject l : group) if (!l.optString("service").startsWith("port ")) { lead = l; break; }
            int a = indexOf(lead.optString("from")), b = indexOf(lead.optString("to"));
            if (a < 0 || b < 0) continue;
            float[] pa = position(a, n, cx, cy, radius), pb = position(b, n, cx, cy, radius);
            float mx = (pa[0] + pb[0]) / 2, my = (pa[1] + pb[1]) / 2;
            float dx = mx - cx, dy = my - cy, len = (float) Math.max(1, Math.hypot(dx, dy));
            float bend = radius * 0.55f;
            float qx = mx + dx / len * bend, qy = my + dy / len * bend;
            if (len < 2) {  // devices opposite each other: bow sideways instead
                qx = mx + (pb[1] - pa[1]) * 0.4f;
                qy = my - (pb[0] - pa[0]) * 0.4f;
            }
            Path p = new Path();
            p.moveTo(pa[0], pa[1]);
            p.quadTo(qx, qy, pb[0], pb[1]);
            c.drawPath(p, arc);
            PathMeasure pm = new PathMeasure(p, false);
            float[] pos = new float[2];
            dot.setColor(accentEnd);
            for (int k = 0; k < 3; k++) {  // dots run from the device that started the connection
                float t = ((now / 1400f) + k / 3f) % 1f;
                pm.getPosTan(pm.getLength() * (0.15f + t * 0.7f), pos, null);
                c.drawCircle(pos[0], pos[1], 3 * d, dot);
            }
            animating = true;
            pm.getPosTan(pm.getLength() / 2, pos, null);
            String text = lead.optString("service") + (group.size() > 1 ? "  +" + (group.size() - 1) : "");
            pendingLabels.add(new Object[]{text, pos[0], pos[1]});
        }

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

        for (Object[] lbl : pendingLabels) {
            String text = (String) lbl[0];
            float x = (float) lbl[1], y = (float) lbl[2];
            float w = arcLabel.measureText(text) + 14 * d, h = 18 * d;
            x = Math.max(w / 2, Math.min(getWidth() - w / 2, x));  // keep the pill on screen
            android.graphics.RectF r = new android.graphics.RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
            pill.setColor(card);
            pill.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r, h / 2, h / 2, pill);
            pill.setColor(accentEnd);
            pill.setStyle(Paint.Style.STROKE);
            c.drawRoundRect(r, h / 2, h / 2, pill);
            c.drawText(text, x, y + 3.5f * d, arcLabel);
        }

        if (animating) postInvalidateOnAnimation();
    }

    private int indexOf(String name) {
        for (int i = 0; i < devices.length(); i++) if (name.equals(devices.optJSONObject(i).optString("name"))) return i;
        return -1;
    }

    private static float[] position(int i, int n, float cx, float cy, float radius) {
        double angle = -Math.PI / 2 + 2 * Math.PI * i / Math.max(1, n);
        return new float[]{cx + (float) (Math.cos(angle) * radius), cy + (float) (Math.sin(angle) * radius)};
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
