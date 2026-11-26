package org.thermal.wp09;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import static org.thermal.wp09.ThermalFrame.*;

final class ThermalView extends View {
    final Measurement measurement = new Measurement();
    private Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    private int[] rendered = new int[PIXELS];
    private final int[] lut = new int[256];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF image = new RectF(), destination = new RectF();
    private final Rect active = new Rect(0, 0, WIDTH, HEIGHT);
    private final RectF activeDestination = new RectF();
    private final RectF inset = new RectF(), downInset = new RectF();
    private final Rect insetSensor = new Rect(), insetSource = new Rect(), downSensor = new Rect();
    private boolean loupe, touchInLoupe;
    private static final int COLD_COLOR = 0xffcccccc, HOT_COLOR = Color.WHITE;
    private final ExtremumLabel coldLabel = new ExtremumLabel("低", COLD_COLOR);
    private final ExtremumLabel hotLabel = new ExtremumLabel("高", HOT_COLOR);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density, slop;
    private float zoom = 1, cx = WIDTH / 2f, cy = HEIGHT / 2f;
    private float downX, downY, lastX, lastY, span;
    private boolean multi, dragged;
    private int dragStart, dragEnd;
    private ThermalCapture capture;
    private ColorScale viewScale = ColorScale.AUTO;
    private boolean recordingView;
    private int palette;
    private boolean measuring = true;
    private Shader gradient;
    private int gradientContrast, gradientTone;
    private float gradientMiddleLow, gradientMiddleHigh;
    private float gradientTop, gradientBottom;
    private Runnable onSelection;
    private ThermalCapture lastDrawn;
    private long fpsStart;
    private int draws, fps;

    ThermalView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        labelPaint.setTextSize(d(12));
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setContentDescription("热像。点按测温，拖动绘制线或区域，双指缩放和平移");
        setBackgroundColor(InstrumentUi.BG);
    }

    void onSpotChanged(Runnable listener) { onSelection = listener; }
    ThermalCapture capture() { return capture; }
    ThermalFrame drawnFrame() { return lastDrawn == null ? null : lastDrawn.frame; }
    ThermalCapture drawnCapture() { return lastDrawn; }
    int fps() { return fps; }

    void setLoupe(boolean enabled) {
        loupe = enabled;
        if (!enabled) inset.setEmpty();
        invalidate();
    }

    void setExtremaVisible(boolean hot, boolean cold) {
        if (hotLabel.enabled == hot && coldLabel.enabled == cold) return;
        hotLabel.enabled = hot;
        coldLabel.enabled = cold;
        invalidate();
    }

    ThermalCapture compose(ThermalFrame frame, Calibration calibration) {
        if (frame == null) return null;
        // Batches may reach the recorder after the user changed the screen controls.
        return compose(frame, calibration, frame.display == null ? viewScale : frame.display.scale);
    }

    private ThermalCapture compose(ThermalFrame frame, Calibration calibration, ColorScale scale) {
        int color = frame.display != null ? frame.display.palette : palette;
        if (scale.locked() && !frame.hasPixelTemperatures()) scale = scale.automatic();
        return new ThermalCapture(frame, calibration, color, scale,
                capture != null && capture.frame == frame ? capture.movieImage : null);
    }

    void setColorScale(ColorScale scale) {
        if (viewScale == scale) return;
        viewScale = scale;
        if (capture != null) display(forScreen(capture.frame, capture.calibration));
    }

    private ThermalCapture forScreen(ThermalFrame frame, Calibration calibration) {
        return recordingView ? compose(frame, calibration) : compose(frame, calibration, viewScale);
    }

    void show(ThermalFrame frame, Calibration calibration, boolean enabled, boolean recording) {
        boolean changed = measuring != enabled;
        measuring = enabled;
        boolean modeChanged = recording != recordingView;
        recordingView = recording;
        if (frame == null && capture == null && !changed) return;
        if (!modeChanged && capture != null && capture.frame == frame && capture.calibration == calibration) {
            if (changed) invalidate();
            return;
        }
        display(frame == null ? null : forScreen(frame, calibration));
    }

    /** Album playback uses the palette and temperature window saved for each frame. */
    void display(ThermalCapture next) {
        capture = next;
        active.left = next == null ? 0 : next.frame.firstColumn;
        if (next != null) {
            measurement.start = visiblePixel(measurement.start);
            measurement.end = visiblePixel(measurement.end);
            float a = next.ironEnhanced ? next.middleLow : -1, b = next.ironEnhanced ? next.middleHigh : -1;
            if (next.palette != palette || gradientContrast != next.scale.contrast || gradientTone != next.lockedTone
                    || a != gradientMiddleLow || b != gradientMiddleHigh) gradient = null;
            gradientMiddleLow = a;
            gradientMiddleHigh = b;
            palette = next.palette;
            gradientContrast = next.scale.contrast;
            gradientTone = next.lockedTone;
            int scale = next.frame.displayScale();
            if (rendered.length != next.rasterPixels()) rendered = new int[next.rasterPixels()];
            next.render(rendered, lut);
            if (bitmap.getWidth() != active.width() * scale || bitmap.getHeight() != HEIGHT * scale)
                bitmap = Bitmap.createBitmap(active.width() * scale, HEIGHT * scale, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(rendered, active.left * scale, WIDTH * scale, 0, 0, bitmap.getWidth(), bitmap.getHeight());
        } else {
            fpsStart = 0;
            fps = draws = 0;
            lastDrawn = null;
        }
        invalidate();
    }

    void setPalette(int value) {
        palette = value;
        gradient = null;
        if (capture != null) display(forScreen(capture.frame, capture.calibration));
        else invalidate();
    }

    void resetViewport() {
        cx = WIDTH / 2f;
        cy = HEIGHT / 2f;
        zoom = 1;
        transform();
        invalidate();
    }

    void select(Measurement.Tool tool) {
        measurement.select(tool);
        selectionChanged();
    }

    float spotReading() {
        return capture == null ? Float.NaN : capture.frame.temperatureAt(measurement.start);
    }

    String spotDisplay() {
        if (capture == null) return "—";
        float value = capture.calibration.apply(spotReading());
        if (!Float.isFinite(value)) return "—";
        return (capture.frame.isEstimatedAt(measurement.start) ? "≈" : "") + number(value) + " °C";
    }

    float calibrationReading() {
        if (capture == null || !capture.frame.measurable(measurement.start)) return Float.NaN;
        ThermalFrame frame = capture.frame;
        if (!frame.hasPixelTemperatures() || frame.firmware != null) return frame.reported.center;
        int x = measurement.start % WIDTH, y = measurement.start / WIDTH;
        float sum = 0;
        int count = 0;
        for (int row = Math.max(0, y - 2); row <= Math.min(HEIGHT - 1, y + 2); row++) {
            for (int col = Math.max(0, x - 2); col <= Math.min(WIDTH - 1, x + 2); col++) {
                float value = frame.readings[row * WIDTH + col];
                if (Float.isFinite(value)) { sum += value; count++; }
            }
        }
        return count > 0 ? sum / count : Float.NaN;
    }

    @Override protected void onDraw(Canvas canvas) {
        boolean degrees = measuring && capture != null && capture.scale.locked();
        float gutter = degrees ? d(49) : 0;
        float available = Math.max(1, getWidth() - gutter - d(4));
        float scale = Math.min(available / active.width(), Math.max(1, getHeight() - d(degrees ? 22 : 8)) / HEIGHT);
        float w = active.width() * scale, h = HEIGHT * scale;
        float x = (getWidth() - w - gutter) / 2, y = (getHeight() - h) / 2;
        image.set(x, y, x + w, y + h);
        transform();
        paint.setColor(0xff0b0b0b);
        canvas.drawRect(image, paint);
        if (capture == null) return;
        canvas.save();
        canvas.clipRect(image);
        paint.setColor(Color.WHITE);
        paint.setFilterBitmap(!capture.scale.locked() || capture.lockedTone != 0);
        canvas.drawBitmap(bitmap, null, activeDestination, paint);
        if (measuring && capture.frame.hasTemperature()) {
            drawSelection(canvas);
            drawExtrema(canvas);
        }
        if (loupe) drawLoupe(canvas);
        canvas.restore();
        if (degrees) drawScale(canvas);
        if (capture.frame != drawnFrame()) {
            long now = SystemClock.elapsedRealtime();
            if (fpsStart == 0) fpsStart = now;
            draws++;
            if (now - fpsStart >= 1000) {
                fps = Math.round(draws * 1000f / (now - fpsStart));
                draws = 0;
                fpsStart = now;
            }
        }
        lastDrawn = capture;
    }

    private void drawLoupe(Canvas canvas) {
        int p = measurement.tool == Measurement.Tool.POINT ? measurement.start : measurement.end;
        float w = Math.min(d(148), image.width() * .31f);
        float margin = d(5);
        float top = image.top + margin;
        if (sx(p) > image.right - w - margin && sy(p) < top + .75f * w + margin)
            top = image.bottom - margin - .75f * w;
        inset.set(image.right - w - margin, top, image.right - margin, top + .75f * w);
        int sw = Math.max(4, Math.round(w * WIDTH / destination.width() / 2.5f));
        int sh = Math.max(3, Math.round(sw * .75f));
        int x = Math.max(active.left, Math.min(WIDTH - sw, p % WIDTH - sw / 2));
        int y = Math.max(0, Math.min(HEIGHT - sh, p / WIDTH - sh / 2));
        insetSensor.set(x, y, x + sw, y + sh);
        if (touchInLoupe && !multi) {
            inset.set(downInset);
            insetSensor.set(downSensor);
            x = insetSensor.left;
            y = insetSensor.top;
            sw = insetSensor.width();
            sh = insetSensor.height();
        }
        int s = capture.frame.displayScale();
        insetSource.set((x - active.left) * s, y * s, (x - active.left + sw) * s, (y + sh) * s);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(bitmap, insetSource, inset, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(d(2));
        paint.setColor(Color.BLACK);
        canvas.drawRect(inset, paint);
        paint.setStrokeWidth(d(1));
        paint.setColor(Color.WHITE);
        canvas.drawRect(inset, paint);
        if (measuring) {
            float mx = inset.left + (p % WIDTH + .5f - x) * inset.width() / sw;
            float my = inset.top + (p / WIDTH + .5f - y) * inset.height() / sh;
            paint.setColor(Color.BLACK);
            paint.setStrokeWidth(d(3));
            canvas.drawRect(mx-d(3), my-d(3), mx+d(3), my+d(3), paint);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(d(1));
            canvas.drawRect(mx-d(3), my-d(3), mx+d(3), my+d(3), paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawExtrema(Canvas canvas) {
        ThermalFrame f = capture.frame;
        int hot = f.reported.hot, cold = f.reported.cold;
        hotLabel.place(hot, capture.calibration.apply(f.reported.maximum), true, f.isEstimatedAt(hot));
        coldLabel.place(cold, capture.calibration.apply(f.reported.minimum), false, f.isEstimatedAt(cold));
        // At a corner both labels may flip to the same side. Stack them inside the viewport.
        if (hotLabel.visible && coldLabel.visible && RectF.intersects(hotLabel.box, coldLabel.box)) {
            float gap = d(2), h = coldLabel.box.height();
            float top = Math.max(image.top, Math.min(hotLabel.box.top, image.bottom - 2 * h - gap));
            hotLabel.box.offsetTo(hotLabel.box.left, top);
            coldLabel.box.offsetTo(coldLabel.box.left, top + h + gap);
        }
        hotLabel.draw(canvas);
        coldLabel.draw(canvas);
        if (hotLabel.visible) marker(canvas, hot, HOT_COLOR, false, d(4));
        if (coldLabel.visible) marker(canvas, cold, COLD_COLOR, false, d(hotLabel.visible && hot == cold ? 2 : 4));
    }

    private final class ExtremumLabel {
        final String prefix;
        final int color;
        final RectF box = new RectF();
        String text;
        long tenths;
        float x, y, width;
        boolean enabled = true, visible;
        boolean estimated;

        ExtremumLabel(String prefix, int color) { this.prefix = prefix; this.color = color; }

        void place(int pixel, float celsius, boolean above, boolean estimate) {
            x = sx(pixel);
            y = sy(pixel);
            visible = enabled && pixel >= 0 && pixel < PIXELS && Float.isFinite(celsius) && image.contains(x, y);
            if (!visible) return;
            long rounded = Math.round((double) celsius * 10);
            if (text == null || tenths != rounded || estimated != estimate) {
                tenths = rounded;
                estimated = estimate;
                text = prefix + (estimate ? " ≈" : " ") + number(celsius) + " °C";
                width = labelPaint.measureText(text) + d(10);
            }
            float gap = d(7), height = d(19);
            float left = x + gap;
            if (left + width > image.right) left = x - gap - width;
            left = Math.max(image.left, Math.min(left, image.right - width));
            float top = above ? y - gap - height : y + gap;
            if (top < image.top) top = y + gap;
            if (top + height > image.bottom) top = y - gap - height;
            top = Math.max(image.top, Math.min(top, image.bottom - height));
            box.set(left, top, left + width, top + height);
        }

        void draw(Canvas canvas) {
            if (!visible) return;
            float endX = Math.max(box.left, Math.min(x, box.right));
            float endY = Math.max(box.top, Math.min(y, box.bottom));
            labelPaint.setStrokeWidth(d(1.2f));
            labelPaint.setColor(color);
            canvas.drawLine(x, y, endX, endY, labelPaint);
            labelPaint.setColor(0xe5151515);
            canvas.drawRect(box, labelPaint);
            labelPaint.setColor(color);
            canvas.drawText(text, box.left + d(5), box.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2, labelPaint);
        }
    }

    private void transform() {
        float halfW = active.width() / (2 * zoom), halfH = HEIGHT / (2 * zoom);
        cx = Math.max(active.left + halfW, Math.min(WIDTH - halfW, cx));
        cy = Math.max(halfH, Math.min(HEIGHT - halfH, cy));
        float scale = image.width() / active.width() * zoom;
        float x = image.centerX() - cx * scale, y = image.centerY() - cy * scale;
        destination.set(x, y, x + WIDTH * scale, y + HEIGHT * scale);
        activeDestination.set(x + active.left * scale, y, destination.right, destination.bottom);
    }

    private void drawSelection(Canvas canvas) {
        int a = measurement.start, b = measurement.end;
        if (measurement.tool == Measurement.Tool.POINT) {
            marker(canvas, a, Color.WHITE, true, d(9));
            return;
        }
        float ax = sx(a), ay = sy(a), bx = sx(b), by = sy(b);
        for (int layer = 0; layer < 2; layer++) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(d(layer == 0 ? 3 : 1.4f));
            paint.setColor(layer == 0 ? 0xc0000000 : InstrumentUi.ACCENT);
            if (measurement.tool == Measurement.Tool.AREA) {
                canvas.drawRect(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by), paint);
            } else canvas.drawLine(ax, ay, bx, by, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(ax, ay, d(3), paint);
        canvas.drawCircle(bx, by, d(3), paint);
    }

    private float sx(int p) { return destination.left + (p % WIDTH + 0.5f) * destination.width() / WIDTH; }
    private float sy(int p) { return destination.top + (p / WIDTH + 0.5f) * destination.height() / HEIGHT; }

    private void marker(Canvas canvas, int p, int color, boolean point, float r) {
        float x = sx(p), y = sy(p);
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 2; i++) {
            paint.setStrokeWidth(d(i == 0 ? 3 : 1.4f));
            paint.setColor(i == 0 ? 0xd0000000 : color);
            if (point) {
                canvas.drawLine(x - r, y, x - r / 3, y, paint);
                canvas.drawLine(x + r / 3, y, x + r, y, paint);
                canvas.drawLine(x, y - r, x, y - r / 3, paint);
                canvas.drawLine(x, y + r / 3, x, y + r, paint);
            } else canvas.drawCircle(x, y, r, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawScale(Canvas canvas) {
        float x = image.right + d(5);
        if (gradient == null || image.top != gradientTop || image.bottom != gradientBottom) {
            gradientTop = image.top;
            gradientBottom = image.bottom;
            gradient = new LinearGradient(0, image.bottom, 0, image.top, capture.legendColors(), null, Shader.TileMode.CLAMP);
        }
        paint.setColor(Color.WHITE);
        paint.setShader(gradient);
        canvas.drawRect(x, image.top, x + d(6), image.bottom, paint);
        paint.setShader(null);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(d(10));
        paint.setColor(0xffdddddd);
        for (int i = 0; i < 5; i++) {
            float f = i / 4f;
            float y = image.top + f * image.height();
            canvas.drawLine(x + d(6), y, x + d(9), y, paint);
            canvas.drawText(number(capture.scale.high + (capture.scale.low - capture.scale.high) * f), x + d(11), y + d(3), paint);
        }
        canvas.drawText("°C", x + d(11), image.top - d(1), paint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (capture == null) return false;
                if (!image.contains(x, y)) return false;
                downX = lastX = x;
                downY = lastY = y;
                touchInLoupe = loupe && inset.contains(x, y);
                downInset.set(inset);
                downSensor.set(insetSensor);
                multi = dragged = false;
                dragStart = measurement.start;
                dragEnd = measurement.end;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    multi = true;
                    span = span(e);
                    lastX = (x + e.getX(1)) / 2;
                    lastY = (y + e.getY(1)) / 2;
                    // Cancel any tentative one-finger selection when the second finger arrives.
                    if (measurement.start != dragStart || measurement.end != dragEnd) {
                        measurement.start = dragStart;
                        measurement.end = dragEnd;
                        selectionChanged();
                    }
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (multi && e.getPointerCount() == 2) {
                    float midX = (x + e.getX(1)) / 2, midY = (y + e.getY(1)) / 2;
                    float distance = span(e);
                    float oldScale = destination.width() / WIDTH;
                    float anchorX = cx + (lastX - image.centerX()) / oldScale;
                    float anchorY = cy + (lastY - image.centerY()) / oldScale;
                    if (span > 1) zoom = Math.max(1, Math.min(12, zoom * distance / span));
                    float newScale = image.width() * zoom / active.width();
                    cx = anchorX - (midX - image.centerX()) / newScale;
                    cy = anchorY - (midY - image.centerY()) / newScale;
                    span = distance;
                    lastX = midX;
                    lastY = midY;
                    transform();
                    invalidate();
                } else if (!multi) {
                    dragged |= Math.hypot(x - downX, y - downY) > slop;
                    if (dragged && measurement.tool == Measurement.Tool.POINT && zoom > 1 && !touchInLoupe) {
                        float scale = destination.width() / WIDTH;
                        cx -= (x - lastX) / scale;
                        cy -= (y - lastY) / scale;
                        transform();
                        invalidate();
                    } else if (dragged && measurement.tool != Measurement.Tool.POINT && measuring) {
                        setEndpoints(x, y);
                    }
                    lastX = x;
                    lastY = y;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!multi && measuring && capture != null && capture.frame.hasTemperature()) {
                    if (measurement.tool != Measurement.Tool.POINT) setEndpoints(x, y);
                    else if ((!dragged || touchInLoupe) && image.contains(x, y)) {
                        measurement.start = measurement.end = toPixel(x, y);
                        selectionChanged();
                    }
                }
                touchInLoupe = false;
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                touchInLoupe = false;
                multi = false;
                return true;
            default:
                return true;
        }
    }

    private static float span(MotionEvent e) {
        return (float) Math.hypot(e.getX(1) - e.getX(), e.getY(1) - e.getY());
    }

    private void setEndpoints(float x, float y) {
        measurement.start = toPixel(downX, downY);
        measurement.end = toPixel(x, y);
        selectionChanged();
    }

    private int toPixel(float x, float y) {
        if (touchInLoupe) {
            int col = downSensor.left + (int)((x - downInset.left) * downSensor.width() / downInset.width());
            int row = downSensor.top + (int)((y - downInset.top) * downSensor.height() / downInset.height());
            return Measurement.pixel(Math.max(downSensor.left, Math.min(downSensor.right - 1, col)),
                    Math.max(downSensor.top, Math.min(downSensor.bottom - 1, row)));
        }
        return visiblePixel(Measurement.pixel((int) ((x - destination.left) * WIDTH / destination.width()),
                (int) ((y - destination.top) * HEIGHT / destination.height())));
    }

    private int visiblePixel(int index) {
        return index + Math.max(0, active.left - index % WIDTH);
    }

    private void selectionChanged() {
        invalidate();
        if (onSelection != null) onSelection.run();
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    static String number(float value) {
        if (!Float.isFinite(value)) return "—";
        long tenths = Math.round((double) value * 10);
        long abs = Math.abs(tenths);
        return (tenths < 0 ? "−" : "") + abs / 10 + "." + abs % 10;
    }
    private float d(float value) { return density * value; }
}
