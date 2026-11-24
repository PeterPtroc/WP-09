package org.thermal.wp09;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.io.IOException;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

final class ThermalView extends View {
    static final String[] PALETTE_NAMES = {"铁红伪彩", "彩虹伪彩", "白热", "黑热"};
    private static final int[][] PALETTES = {
            {0xff130f21, 0xff4d1c59, 0xffa13b66, 0xffed6350, 0xffffb963, 0xfffff1bd},
            {0xff241057, 0xff294fc2, 0xff1dc8dc, 0xff80df62, 0xfff7dd40, 0xfff15a39},
            {0xff000000, 0xffffffff},
            {0xffffffff, 0xff000000}
    };
    private static final int[][] COLORS = new int[PALETTES.length][];
    private static final ThreadLocal<DecimalFormat> NUMBERS = new ThreadLocal<DecimalFormat>() {
        @Override protected DecimalFormat initialValue() {
            return new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        }
    };
    static {
        for (int i = 0; i < PALETTES.length; i++) COLORS[i] = palette(PALETTES[i]);
    }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF image = new RectF();
    private final RectF labelBox = new RectF();
    private final Matrix frameToView = new Matrix();
    private final Matrix viewToFrame = new Matrix();
    private final Matrix visibleToView = new Matrix();
    private final float[] visibleMatrix = new float[9];
    private final float[] mappedPoint = new float[2];
    private final Bitmap bitmap = Bitmap.createBitmap(ThermalFrame.WIDTH, ThermalFrame.HEIGHT,
            Bitmap.Config.ARGB_8888);
    private final int[] rendered = new int[ThermalFrame.PIXELS];
    private final float density;
    private ThermalFrame frame;
    private Bitmap visible;
    private long visibleAt;
    private long thermalTime, visibleTime;
    private boolean automaticFusion;
    private FusionRegistration.Transform automaticTransform;
    private long transformUntil;
    private int alignmentGuide = -1;
    private int sensorOrientation;
    private int fusionRotation;
    private int visibleAlpha;
    private float fusionZoom;
    private float fusionX;
    private float fusionY;
    private Calibration calibration = Calibration.DEFAULT;
    private boolean measuring = true;
    private int palette;
    private int spot = ThermalFrame.CENTER;
    private Runnable onSpotChanged;
    private Shader scaleGradient;
    private float gradientTop;
    private float gradientBottom;

    ThermalView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setContentDescription("红外热成像。点按画面选择测温位置");
        setBackgroundColor(0xff111111);
    }

    void onSpotChanged(Runnable action) {
        onSpotChanged = action;
    }

    void show(ThermalFrame next, Calibration correction, boolean temperatureOn) {
        boolean bitmapChanged = frame != next;
        frame = next;
        calibration = correction;
        measuring = temperatureOn;
        if (bitmapChanged) updateBitmap();
        invalidate();
    }

    void showVisible(int[] argb, int width, int height, int orientation) {
        showVisible(argb, width, height, orientation, SystemClock.elapsedRealtimeNanos());
    }

    void showVisible(int[] argb, int width, int height, int orientation, long receivedAt) {
        if (visible == null || visible.getWidth() != width || visible.getHeight() != height) {
            visible = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        visible.setPixels(argb, 0, width, 0, 0, width, height);
        sensorOrientation = orientation;
        visibleAt = SystemClock.elapsedRealtime();
        visibleTime = receivedAt;
        invalidate();
    }

    void clearVisible() {
        visible = null;
        visibleTime = 0;
        invalidate();
    }

    static final class Pair {
        final ThermalFrame heat;
        final Bitmap camera;
        final int angle, rotation;
        final float zoom, x, y;

        Pair(ThermalFrame heat, Bitmap camera, int angle, int rotation, float zoom, float x, float y) {
            this.heat = heat;
            this.camera = camera;
            this.angle = angle;
            this.rotation = rotation;
            this.zoom = zoom;
            this.x = x;
            this.y = y;
        }

        int[] thermalPixels() {
            if (!heat.hasPixelTemperatures()) return heat.preview;
            int[] pixels = new int[ThermalFrame.PIXELS];
            float range = heat.maximum - heat.minimum;
            for (int i = 0; i < pixels.length; i++) {
                int shade = range > 0 ? Math.round(255 * (heat.readings[i] - heat.minimum) / range) : 127;
                int gray = Math.max(0, Math.min(255, shade));
                pixels[i] = Color.rgb(gray, gray, gray);
            }
            return pixels;
        }
    }

    Pair currentPair() {
        long now = SystemClock.elapsedRealtimeNanos();
        if (frame == null || visible == null || !FusionRegistration.paired(thermalTime, visibleTime)
                || now - Math.min(thermalTime, visibleTime) > 500_000_000L) return null;
        return new Pair(frame, visible.copy(Bitmap.Config.ARGB_8888, false), sensorOrientation,
                fusionRotation, fusionZoom, fusionX, fusionY);
    }

    ThermalMedia.Writer captureFusionPair() {
        Pair pair = currentPair();
        if (pair == null) return null;
        Properties info = new Properties();
        info.setProperty("sensor_orientation", Integer.toString(pair.angle));
        info.setProperty("arrival_delta_ms", Long.toString((visibleTime - thermalTime) / 1_000_000L));
        info.setProperty("thermal_mode", pair.heat.hasPixelTemperatures() ? "radiometric_preview" : "grayscale_preview");
        info.setProperty("manual_zoom", Integer.toString(Math.round(pair.zoom * 100)));
        info.setProperty("manual_x", Integer.toString(Math.round(pair.x * 100)));
        info.setProperty("manual_y", Integer.toString(Math.round(pair.y * 100)));
        info.setProperty("manual_rotation", Integer.toString(pair.rotation));
        return stream -> {
            try (ZipOutputStream zip = new ZipOutputStream(stream)) {
                Bitmap gray = Bitmap.createBitmap(pair.thermalPixels(), ThermalFrame.WIDTH, ThermalFrame.HEIGHT,
                        Bitmap.Config.ARGB_8888);
                try {
                    writeImage(zip, "thermal.png", gray);
                    writeImage(zip, "visible.png", pair.camera);
                    zip.putNextEntry(new ZipEntry("pair.properties"));
                    info.store(zip, "WP-09 fusion pair");
                    zip.closeEntry();
                } finally {
                    gray.recycle();
                }
            } finally {
                pair.camera.recycle();
            }
        };
    }

    private static void writeImage(ZipOutputStream zip, String name, Bitmap image) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        if (!image.compress(Bitmap.CompressFormat.PNG, 100, zip)) throw new IOException("PNG encoding failed");
        zip.closeEntry();
    }

    void setThermalTime(long receivedAt) {
        thermalTime = receivedAt;
    }

    void setAutomaticFusion(boolean automatic, FusionRegistration.Transform transform, long until) {
        automaticFusion = automatic;
        automaticTransform = transform;
        transformUntil = until;
        invalidate();
    }

    void setAlignmentGuide(int guide) {
        if (alignmentGuide == guide) return;
        alignmentGuide = guide;
        invalidate();
    }

    void setFusion(int opacity, int zoom, int x, int y, int rotation) {
        visibleAlpha = Math.round(255 * (100 - opacity) / 100f);
        fusionZoom = zoom / 100f;
        fusionX = x / 100f;
        fusionY = y / 100f;
        fusionRotation = rotation;
        invalidate();
    }

    void setPalette(int selected) {
        palette = selected;
        scaleGradient = null;
        updateBitmap();
        invalidate();
    }

    private void updateBitmap() {
        if (frame != null) {
            int[] colors = COLORS[palette];
            if (frame.hasPixelTemperatures()) {
                float min = frame.minimum;
                float range = frame.maximum - min;
                for (int i = 0; i < rendered.length; i++) {
                    float value = frame.readings[i];
                    int shade = range > 0 ? Math.round(255 * (value - min) / range) : 127;
                    rendered[i] = Float.isFinite(value)
                            ? colors[Math.max(0, Math.min(shade, 255))] : Color.BLACK;
                }
            } else {
                for (int i = 0; i < rendered.length; i++) {
                    rendered[i] = colors[frame.preview[i] & 0xff];
                }
            }
            bitmap.setPixels(rendered, 0, ThermalFrame.WIDTH, 0, 0,
                    ThermalFrame.WIDTH, ThermalFrame.HEIGHT);
        }
    }

    float spotReading() {
        return frame == null ? Float.NaN : frame.temperatureAt(spot);
    }

    String spotDisplay() {
        float value = calibration.apply(spotReading());
        if (!Float.isFinite(value)) return "—";
        return frame.isEstimatedAt(spot) ? "约 " + Math.round(value) + " °C"
                : number(value) + " °C";
    }

    float calibrationReading() {
        if (frame == null) return Float.NaN;
        if (!frame.hasPixelTemperatures()) return frame.center;
        int x = spot % ThermalFrame.WIDTH;
        int y = spot / ThermalFrame.WIDTH;
        float sum = 0f;
        int count = 0;
        for (int row = Math.max(0, y - 2); row <= Math.min(ThermalFrame.HEIGHT - 1, y + 2); row++) {
            for (int col = Math.max(0, x - 2); col <= Math.min(ThermalFrame.WIDTH - 1, x + 2); col++) {
                float reading = frame.readings[row * ThermalFrame.WIDTH + col];
                if (Float.isFinite(reading)) {
                    sum += reading;
                    count++;
                }
            }
        }
        return count > 0 ? sum / count : Float.NaN;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawScene(canvas, getWidth(), getHeight(), true);
    }

    void drawCapture(Bitmap target) {
        RectF oldImage = new RectF(image);
        Matrix oldTransform = new Matrix(frameToView);
        Canvas canvas = new Canvas(target);
        canvas.drawColor(0xff111111);
        float widthDp = 530;
        float scale = target.getWidth() / d(widthDp);
        canvas.scale(scale, scale);
        float height = target.getHeight() / scale;
        boolean showReadings = measuring && frame != null && frame.hasTemperature();
        drawScene(canvas, target.getWidth() / scale, height - (showReadings ? d(17) : 0), false);
        if (showReadings) {
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setColor(0xffe7e7e7);
            paint.setTextSize(d(11));
            String label = frame.hasPixelTemperatures()
                    ? "平均 " + number(calibration.apply(frame.average)) + " °C     选点 " : "选点 ";
            canvas.drawText(label + spotDisplay(), image.left, height - d(3), paint);
        }
        image.set(oldImage);
        frameToView.set(oldTransform);
    }

    private void drawScene(Canvas canvas, float width, float height, boolean live) {
        boolean showTemperatureScale = measuring && (frame == null || frame.hasPixelTemperatures());
        float scaleWidth = measuring ? d(showTemperatureScale ? 57 : 16) : 0;
        boolean compactBrightness = live && measuring && !showTemperatureScale
                && width >= d(64) && height >= d(48);
        float fullWidth = Math.max(1, width - d(4));
        int verticalGutter = !measuring ? 4 : compactBrightness ? 8 : 24;
        float heightLimited = Math.max(1, height - d(verticalGutter)) / ThermalFrame.HEIGHT;
        float fullFrameWidth = Math.min(fullWidth, heightLimited * ThermalFrame.WIDTH);
        boolean overlay = compactBrightness && fullFrameWidth + scaleWidth > fullWidth;
        float outsideScaleWidth = overlay ? 0 : scaleWidth;
        float available = Math.max(1, fullWidth - outsideScaleWidth);
        float frameScale = Math.min(available / ThermalFrame.WIDTH, heightLimited);
        float imageWidth = ThermalFrame.WIDTH * frameScale;
        float imageHeight = ThermalFrame.HEIGHT * frameScale;
        float left = (width - imageWidth - outsideScaleWidth) / 2;
        float top = (height - imageHeight) / 2;
        image.set(left, top, left + imageWidth, top + imageHeight);
        // The external WP-09 supplies frames in camera orientation.
        frameToView.setScale(frameScale, frameScale);
        frameToView.postTranslate(left, top);
        paint.setShader(null);
        paint.setColor(0xff080808);
        canvas.drawRect(image, paint);
        if (frame != null) {
            canvas.drawBitmap(bitmap, frameToView, paint);
            if (visible != null && visibleAlpha > 0 && SystemClock.elapsedRealtime() - visibleAt <= 500
                    && (!automaticFusion || automaticTransform != null &&
                    SystemClock.elapsedRealtimeNanos() <= transformUntil &&
                    FusionRegistration.paired(thermalTime, visibleTime))) {
                drawVisible(canvas);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(d(1));
        paint.setColor(0xff3b3b3b);
        canvas.drawRect(image, paint);
        paint.setStyle(Paint.Style.FILL);

        if (live && alignmentGuide >= 0) drawAlignmentGuide(canvas);
        if (!measuring) return;
        boolean valid = frame != null && frame.hasTemperature();
        drawScale(canvas, valid && frame.hasPixelTemperatures(), overlay);
        if (valid) {
            mark(canvas, frame.cold, Color.WHITE, d(6), false,
                    "低 " + number(calibration.apply(frame.minimum)) + "°", 0);
            mark(canvas, frame.hot, Color.WHITE, d(6), false,
                    "高 " + number(calibration.apply(frame.maximum)) + "°", 1);
            mark(canvas, spot, Color.WHITE, d(9), true, null, 0);
        }
    }

    private void drawVisible(Canvas canvas) {
        if (automaticFusion) {
            float unit = image.width() / FusionRegistration.WIDTH;
            float sx = unit * FusionRegistration.WIDTH / visible.getWidth();
            float sy = unit * Math.round(FusionRegistration.WIDTH * visible.getHeight()
                    / (float) visible.getWidth()) / visible.getHeight();
            FusionRegistration.Transform transform = automaticTransform;
            visibleMatrix[0] = transform.a * sx;
            visibleMatrix[1] = -transform.b * sy;
            visibleMatrix[2] = image.left + transform.x * unit;
            visibleMatrix[3] = transform.b * sx;
            visibleMatrix[4] = transform.a * sy;
            visibleMatrix[5] = image.top + transform.y * unit;
            visibleMatrix[8] = 1;
            visibleToView.setValues(visibleMatrix);
        } else {
            mapCamera(visibleToView, visible.getWidth(), visible.getHeight(), image, sensorOrientation + fusionRotation,
                    fusionZoom, fusionX, fusionY);
        }
        canvas.save();
        canvas.clipRect(image);
        paint.setAlpha(visibleAlpha);
        canvas.drawBitmap(visible, visibleToView, paint);
        paint.setAlpha(255);
        canvas.restore();
    }

    static void mapCamera(Matrix matrix, int width, int height, RectF image, int angle,
                          float zoom, float x, float y) {
        double radians = Math.toRadians(angle);
        float cos = (float) Math.abs(Math.cos(radians));
        float sin = (float) Math.abs(Math.sin(radians));
        float scale = zoom * Math.max((image.width() * cos + image.height() * sin) / width,
                (image.width() * sin + image.height() * cos) / height);
        matrix.setTranslate(-width / 2f, -height / 2f);
        matrix.postRotate(angle);
        matrix.postScale(scale, scale);
        matrix.postTranslate(image.centerX() + x * image.width(), image.centerY() + y * image.height());
    }

    private void drawAlignmentGuide(Canvas canvas) {
        float x = image.left + image.width() * FusionRegistration.TARGET_X[alignmentGuide];
        float y = image.top + image.height() * FusionRegistration.TARGET_Y[alignmentGuide];
        float radius = Math.min(d(22), image.width() * 0.085f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(d(4));
        paint.setColor(Color.BLACK);
        glyph(canvas, x, y, radius, false);
        paint.setStrokeWidth(d(2));
        paint.setColor(Color.WHITE);
        glyph(canvas, x, y, radius, false);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawScale(Canvas canvas, boolean valid, boolean overlay) {
        float x = overlay ? image.right - d(11) : image.right + d(4);
        float top = image.top;
        float width = d(7);
        if (overlay) {
            paint.setColor(0xb3111111);
            canvas.drawRect(x - d(3), top, image.right, image.bottom, paint);
        }
        if (frame != null) {
            if (scaleGradient == null || gradientTop != top || gradientBottom != image.bottom) {
                gradientTop = top;
                gradientBottom = image.bottom;
                scaleGradient = new LinearGradient(0, image.bottom, 0, top, PALETTES[palette],
                        null, Shader.TileMode.CLAMP);
            }
            paint.setShader(scaleGradient);
        } else {
            paint.setColor(0xff2c2c2c);
        }
        canvas.drawRect(x, top, x + width, image.bottom, paint);
        paint.setShader(null);
        if (!valid && frame != null) return;
        paint.setTextSize(d(11));
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(0xffbbbbbb);
        float low = valid ? frame.minimum : Float.NaN;
        float high = valid ? frame.maximum : Float.NaN;
        int ticks = image.height() < d(112) ? 2 : 5;
        for (int i = 0; i < ticks; i++) {
            float fraction = (float) i / (ticks - 1);
            float y = image.top + fraction * image.height();
            canvas.drawLine(x + width, y, x + width + d(4), y, paint);
            float reading = calibration.apply(high + (low - high) * fraction);
            canvas.drawText(valid ? number(reading) : "–", x + width + d(5),
                    y + d(4), paint);
        }
        canvas.drawText("°C", x + width + d(5), image.top - d(4), paint);
    }

    private final RectF coldLabel = new RectF();

    private void mark(Canvas canvas, int index, int color, float radius, boolean crosshair,
                      String label, int labelIndex) {
        mappedPoint[0] = index % ThermalFrame.WIDTH + 0.5f;
        mappedPoint[1] = index / ThermalFrame.WIDTH + 0.5f;
        frameToView.mapPoints(mappedPoint);
        float x = mappedPoint[0];
        float y = mappedPoint[1];
        canvas.save();
        canvas.clipRect(image);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(d(3.5f));
        paint.setColor(Color.BLACK);
        glyph(canvas, x, y, radius, crosshair);
        paint.setStrokeWidth(d(1.5f));
        paint.setColor(color);
        glyph(canvas, x, y, radius, crosshair);
        paint.setStyle(Paint.Style.FILL);
        if (label != null) drawLabel(canvas, x, y, radius, color, label, labelIndex);
        canvas.restore();
    }

    private void drawLabel(Canvas canvas, float x, float y, float radius, int color,
                           String label, int index) {
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(d(11));
        float labelWidth = paint.measureText(label) + d(10);
        float labelHeight = d(19);
        float left = Math.max(image.left + d(2),
                Math.min(x + radius + d(4), image.right - labelWidth - d(2)));
        float top = y - radius - labelHeight - d(3);
        if (top < image.top + d(2)) top = y + radius + d(3);
        top = Math.min(top, image.bottom - labelHeight - d(2));
        labelBox.set(left, top, left + labelWidth, top + labelHeight);
        if (index == 0) coldLabel.set(labelBox);
        else if (RectF.intersects(labelBox, coldLabel)) {
            float below = coldLabel.bottom + d(2);
            labelBox.offsetTo(left, below + labelHeight < image.bottom
                    ? below : coldLabel.top - labelHeight - d(2));
        }
        paint.setColor(0xe6212121);
        canvas.drawRect(labelBox, paint);
        paint.setColor(color);
        canvas.drawText(label, labelBox.left + d(5), labelBox.bottom - d(5), paint);
    }

    private void glyph(Canvas canvas, float x, float y, float r, boolean crosshair) {
        if (crosshair) {
            float gap = r / 3;
            canvas.drawLine(x - r, y, x - gap, y, paint);
            canvas.drawLine(x + gap, y, x + r, y, paint);
            canvas.drawLine(x, y - r, x, y - gap, paint);
            canvas.drawLine(x, y + gap, x, y + r, paint);
        } else {
            canvas.drawCircle(x, y, r, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN)
            return measuring && frame != null && frame.hasTemperature()
                    && image.contains(event.getX(), event.getY());
        if (event.getAction() == MotionEvent.ACTION_UP && image.contains(event.getX(), event.getY())
                && frameToView.invert(viewToFrame)) {
            mappedPoint[0] = event.getX();
            mappedPoint[1] = event.getY();
            viewToFrame.mapPoints(mappedPoint);
            int x = Math.max(0, Math.min((int) mappedPoint[0], ThermalFrame.WIDTH - 1));
            int y = Math.max(0, Math.min((int) mappedPoint[1], ThermalFrame.HEIGHT - 1));
            spot = y * ThermalFrame.WIDTH + x;
            performClick();
            invalidate();
            if (onSpotChanged != null) onSpotChanged.run();
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    static String number(float value) {
        return Float.isFinite(value) ? NUMBERS.get().format(value) : "—";
    }

    static GradientDrawable swatch(int palette) {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                PALETTES[palette]);
    }

    private float d(float dp) { return dp * density; }

    private static int[] palette(int[] stops) {
        int[] colors = new int[256];
        for (int i = 0; i < colors.length; i++) {
            float point = i * (stops.length - 1f) / (colors.length - 1);
            int segment = Math.min((int) point, stops.length - 2);
            float f = point - segment;
            int a = stops[segment], b = stops[segment + 1];
            colors[i] = Color.rgb(mix(Color.red(a), Color.red(b), f),
                    mix(Color.green(a), Color.green(b), f),
                    mix(Color.blue(a), Color.blue(b), f));
        }
        return colors;
    }

    private static int mix(int a, int b, float f) {
        return Math.round(a + (b - a) * f);
    }
}
