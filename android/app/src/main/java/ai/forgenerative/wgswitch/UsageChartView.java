package ai.forgenerative.wgswitch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Daily data used (download + upload), one column per day. Single series, so no legend:
 * the section title names it. Columns follow the chart spec: <= 24dp wide, 4dp rounded tip,
 * square at the baseline, 2dp gap, hairline baseline, label only on the busiest day.
 * Tapping a column reports that day's split to {@code onSelect} (the readout under the chart).
 */
class UsageChartView extends View {
    private final float d = getResources().getDisplayMetrics().density;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private JSONArray days = new JSONArray();
    private int selected = -1;
    private Consumer<JSONObject> onSelect = e -> { };

    UsageChartView(Context c) {
        super(c);
        bar.setColor(c.getColor(R.color.accent_start));  // validated vs the card surface (dataviz validator)
        axis.setColor(c.getColor(R.color.card_stroke));
        axis.setStrokeWidth(1);
        text.setColor(c.getColor(R.color.text_muted));  // text uses text tokens, never the series colour
        text.setTextSize(11 * d);
        setMinimumHeight(Math.round(150 * d));
    }

    void setDays(JSONArray days, Consumer<JSONObject> onSelect) {
        this.days = days;
        this.onSelect = onSelect;
        this.selected = days.length() - 1;  // start on today
        onSelect.accept(days.optJSONObject(selected));
        StringBuilder desc = new StringBuilder("Data used per day: ");
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            desc.append(day.optString("date")).append(' ').append(Api.bytes(total(day))).append(", ");
        }
        setContentDescription(desc);
        invalidate();
    }

    private static double total(JSONObject day) {
        return day.optDouble("down") + day.optDouble("up");
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = days.length();
        if (n == 0) return;
        float top = 18 * d, labels = 18 * d;
        float base = getHeight() - labels, plot = base - top;
        double max = 0;
        int peak = 0;
        for (int i = 0; i < n; i++) {
            double t = total(days.optJSONObject(i));
            if (t > max) {
                max = t;
                peak = i;
            }
        }
        float slot = getWidth() / (float) n;
        float w = Math.min(24 * d, slot - 2 * d);  // cap thickness; the leftover slot is air
        float r = 4 * d;
        for (int i = 0; i < n; i++) {
            double t = total(days.optJSONObject(i));
            float h = max > 0 ? (float) (t / max * plot) : 0;
            if (t > 0) h = Math.max(h, 2 * d);  // a tiny day still shows
            float x = i * slot + (slot - w) / 2;
            bar.setAlpha(selected < 0 || i == selected ? 255 : 110);
            if (h > 0) {
                float rr = Math.min(r, h);
                path.reset();
                path.addRoundRect(new RectF(x, base - h, x + w, base),
                        new float[]{rr, rr, rr, rr, 0, 0, 0, 0}, Path.Direction.CW);
                c.drawPath(path, bar);
            }
        }
        c.drawLine(0, base + 0.5f, getWidth(), base + 0.5f, axis);

        // Selective labels: the busiest day's value, the first date and "Today".
        if (max > 0) {
            String v = Api.bytes(max);
            float px = peak * slot + slot / 2 - text.measureText(v) / 2;
            px = Math.max(0, Math.min(px, getWidth() - text.measureText(v)));
            c.drawText(v, px, base - (float) (total(days.optJSONObject(peak)) / max * plot) - 5 * d, text);
        }
        float ty = base + 14 * d;
        c.drawText(shortDate(days.optJSONObject(0).optString("date")), 0, ty, text);
        String today = "Today";
        c.drawText(today, getWidth() - text.measureText(today), ty, text);
    }

    static String shortDate(String iso) {
        try {
            return new SimpleDateFormat("MMM d", Locale.getDefault())
                    .format(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso));
        } catch (Exception e) {
            return iso;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (days.length() == 0) return false;
        if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
            // The whole column slot is the hit target, not just the (thin) bar.
            int i = Math.max(0, Math.min(days.length() - 1, (int) (e.getX() / (getWidth() / (float) days.length()))));
            if (i != selected) {
                selected = i;
                onSelect.accept(days.optJSONObject(i));
                invalidate();
            }
            getParent().requestDisallowInterceptTouchEvent(true);  // let a sideways scrub beat the scroll view
            return true;
        }
        return super.onTouchEvent(e);
    }
}
