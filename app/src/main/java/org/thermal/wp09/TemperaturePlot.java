package org.thermal.wp09;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A rolling 30-second history, or the selected line's current spatial profile. */
final class TemperaturePlot extends View {
    private final float[] values = new float[320];
    private final long[] times = new long[values.length];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Measurement measurement;
    private int head, size;
    private boolean profile;
    private final float d;

    TemperaturePlot(Context c, Measurement measurement) {
        super(c);
        this.measurement = measurement;
        d = c.getResources().getDisplayMetrics().density;
        setContentDescription("温度曲线。线测量时点按切换时间曲线和线剖面");
        setOnClickListener(v -> {
            if (measurement.tool == Measurement.Tool.LINE) {
                profile = !profile;
                invalidate();
            }
        });
    }

    void reset(boolean lineProfile) {
        size = head = 0;
        profile = lineProfile;
        invalidate();
    }

    void add(long timeMs, float value) {
        if (size > 0) {
            long previous = times[(head + values.length - 1) % values.length];
            if (timeMs < previous) size = head = 0;
            else if (timeMs - previous < 100) { invalidate(); return; }
        }
        while (size > 0 && timeMs - times[(head + values.length - size) % values.length] > 30000) size--;
        values[head] = value;
        times[head] = timeMs;
        head = (head + 1) % values.length;
        size = Math.min(values.length, size + 1);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        boolean line = profile && measurement.tool == Measurement.Tool.LINE;
        int n = line ? measurement.profileSize : size;
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        int first = (head + values.length - size) % values.length;
        for (int i = 0; i < n; i++) {
            float value = line ? measurement.profile[i] : values[(first + i) % values.length];
            if (Float.isFinite(value)) { low = Math.min(low, value); high = Math.max(high, value); }
        }
        float left = 6*d, right = getWidth() - 42*d, top = 12*d, bottom = getHeight() - 20*d;
        paint.setTextSize(10*d);
        paint.setColor(InstrumentUi.MUTED);
        c.drawText(line ? "线剖面 · 像素" : "近 30 秒", left, getHeight() - 4*d, paint);
        if (!Float.isFinite(low)) return;
        if (high - low < 0.2f) { high += 0.1f; low -= 0.1f; }
        c.drawText(ThermalView.number(high) + "°", right + 3*d, top + 3*d, paint);
        c.drawText(ThermalView.number(low) + "°", right + 3*d, bottom + 3*d, paint);
        paint.setColor(InstrumentUi.TILE);
        c.drawLine(left, top, right, top, paint);
        c.drawLine(left, bottom, right, bottom, paint);
        path.reset();
        boolean move = true;
        long latest = times[(head + values.length - 1) % values.length];
        float lastX = left, lastY = bottom;
        boolean any = false;
        for (int i = 0; i < n; i++) {
            int k = (first + i) % values.length;
            float value = line ? measurement.profile[i] : values[k];
            if (!Float.isFinite(value)) { move = true; continue; }
            float fraction = line ? (float) i / Math.max(1, n - 1) : 1 - (latest - times[k]) / 30000f;
            lastX = left + (right - left) * fraction;
            lastY = bottom - (bottom - top) * (value - low) / (high - low);
            if (move) path.moveTo(lastX, lastY);
            else path.lineTo(lastX, lastY);
            move = false;
            any = true;
        }
        paint.setColor(InstrumentUi.ACCENT);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f*d);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        if (any) c.drawCircle(lastX, lastY, 2*d, paint);
    }
}
