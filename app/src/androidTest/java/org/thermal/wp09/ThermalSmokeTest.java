package org.thermal.wp09;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

public final class ThermalSmokeTest extends Instrumentation {
    @Override public void onCreate(Bundle options) {
        super.onCreate(options);
        start();
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            checkThermometryAndCapture();
            results.putString("stream", "\nWP-09 thermometry, spot estimation, rotation, calibration, diagnostic ZIP and PNG/MP4 checks passed.\n");
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            results.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, results);
        }
    }

    private void checkThermometryAndCapture() throws Exception {
        ThermalFrame.Decoder decoder = new ThermalFrame.Decoder();
        byte[] wire = fixture();
        checkUsbSample(wire);
        ThermalFrame frame = decoder.decode(wire, 0.98f);
        if (!frame.hasTemperature()) {
            float[] table = new float[16384];
            check(Wp09Usb.temperatureTable(wire, table, 0.98f), "Temperature library did not load");
            throw new AssertionError("Synthetic temperature table sample " + table[4800]
                    + ", " + table[7000] + ", " + table[10300]);
        }
        check(frame.readings[frame.cold] < frame.average && frame.average < frame.readings[frame.hot],
                "Extrema and mean");
        byte[] preview = wire.clone();
        ByteBuffer samples = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < ThermalFrame.PIXELS * 2; i += 2) {
            int raw = Short.toUnsignedInt(samples.getShort(i));
            preview[i] = (byte) ((raw - 4800) * 255 / 5500);
            preview[i + 1] = (byte) 128;
        }
        int probe = ThermalFrame.CENTER + 40;
        preview[2 * frame.cold] = 10;
        preview[2 * ThermalFrame.CENTER] = 50;
        preview[2 * frame.hot] = (byte) 210;
        preview[2 * probe] = (byte) 130;
        ThermalFrame summary = decoder.decode(preview, 0.98f);
        check(summary.hasTemperature() && !summary.hasPixelTemperatures(), "Preview telemetry");
        check(summary.minimum <= summary.center && summary.center <= summary.maximum,
                "Preview extrema and center");
        check(summary.cold == frame.cold && summary.hot == frame.hot
                && !Float.isFinite(summary.average), "Preview positions and unavailable average");
        check(summary.temperatureAt(summary.cold) == summary.minimum
                && summary.temperatureAt(summary.hot) == summary.maximum, "Preview anchor readings");
        check(summary.isEstimatedAt(probe)
                && Math.abs(summary.temperatureAt(probe) - (summary.center + summary.maximum) / 2)
                < 0.001f, "Preview interpolation above center");
        preview[2 * probe] = 30;
        ThermalFrame lower = decoder.decode(preview, 0.98f);
        check(Math.abs(lower.temperatureAt(probe) - (lower.minimum + lower.center) / 2)
                < 0.001f, "Preview interpolation below center");
        preview[2 * probe] = 0;
        check(decoder.decode(preview, 0.98f).temperatureAt(probe) == summary.minimum,
                "Out-of-range brightness is clamped");
        float changed = decoder.decode(wire, 0.70f).average;
        check(Float.isFinite(changed) && Math.abs(changed - frame.average) > 0.01f, "Emissivity");
        check(!decoder.decode(new byte[ThermalFrame.BYTES], 0.98f).hasTemperature(), "Image-only stream");
        Calibration calibration = Calibration.twoPoint(20, 24, 50, 57);
        check(Math.abs(calibration.apply(35) - 40.5f) < 0.01f, "Two-point calibration");

        Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) startActivitySync(intent);
        waitForIdleSync();
        ThermalView view = field(activity, "thermal", ThermalView.class);
        Method render = MainActivity.class.getDeclaredMethod("render");
        render.setAccessible(true);
        Field frameField = MainActivity.class.getDeclaredField("frame");
        frameField.setAccessible(true);
        runOnMainSync(() -> showFrame(activity, render, frameField, summary));
        waitForIdleSync();
        runOnMainSync(() -> {
            check(view.spotReading() == summary.center, "Preview center reading");
            RectF bounds = field(view, "image", RectF.class);
            float density = view.getResources().getDisplayMetrics().density;
            if (view.getHeight() - 8 * density >= view.getWidth() * 3f / 4) {
                check(bounds.width() >= view.getWidth() - 5 * density,
                        "Brightness preview uses the full width");
            }
            check("—".contentEquals(field(activity, "average", TextView.class).getText()),
                    "Preview average is unavailable");
            tapPreview(view, 0.32f, 0.50f);
            check(field(activity, "spot", TextView.class).getText().toString().startsWith("约 "),
                    "Touch to estimate and label");
            check(view.calibrationReading() == summary.center, "Preview calibration uses center");
        });
        saveScreenshot("smoke-preview.png");
        runOnMainSync(() -> showFrame(activity, render, frameField, frame));
        waitForIdleSync();
        runOnMainSync(() -> {
            check(field(activity, "hot", TextView.class).getText().toString().contains("°C"), "Hot label");
            float previous = view.spotReading();
            tapPreview(view, 0.58f, 0.50f);
            check(view.spotReading() != previous && !view.spotDisplay().startsWith("约 "),
                    "Touch to measure");
        });
        checkLandscape(activity, view);

        LinkedBlockingQueue<String> mediaResult = new LinkedBlockingQueue<>();
        ThermalMedia media = new ThermalMedia(getTargetContext(), mediaResult::offer);
        try {
            runOnMainSync(() -> media.photo(view));
            check("照片已保存".equals(mediaResult.poll(8, TimeUnit.SECONDS)), "PNG save");

            LinkedBlockingQueue<Boolean> started = new LinkedBlockingQueue<>();
            media.start(started::offer);
            check(Boolean.TRUE.equals(started.poll(8, TimeUnit.SECONDS)), "Video start");
            for (int i = 0; i < 22; i++) {
                runOnMainSync(() -> media.frame(view, () -> {}));
                SystemClock.sleep(55);
            }
            media.stop();
            check("录像已保存".equals(mediaResult.poll(12, TimeUnit.SECONDS)), "MP4 save");
        } finally {
            media.destroy();
            runOnMainSync(activity::finish);
        }
    }

    private void checkLandscape(MainActivity activity, ThermalView view) throws Exception {
        UiAutomation ui = getUiAutomation();
        try {
            checkRotation(activity, view, Surface.ROTATION_90);
            saveScreenshot("smoke-landscape.png");
            checkRotation(activity, view, Surface.ROTATION_270);
        } finally {
            ui.setRotation(UiAutomation.ROTATION_UNFREEZE);
        }
    }

    private void saveScreenshot(String name) throws Exception {
        waitForIdleSync();
        Bitmap screen = getUiAutomation().takeScreenshot();
        try (FileOutputStream file = new FileOutputStream(new File(getTargetContext().getCacheDir(), name))) {
            screen.compress(Bitmap.CompressFormat.PNG, 100, file);
        } finally {
            screen.recycle();
        }
    }

    private void checkRotation(MainActivity activity, ThermalView view, int rotation) {
        check(getUiAutomation().setRotation(rotation), "Request landscape rotation");
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (view.getDisplay().getRotation() != rotation && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(40);
        }
        waitForIdleSync();
        // Draw once at the new size before using local view coordinates for the tap.
        Bitmap screen = getUiAutomation().takeScreenshot();
        if (screen != null) screen.recycle();
        runOnMainSync(() -> {
            check(view.getDisplay().getRotation() == rotation, "Display rotation");
            View header = field(activity, "header", View.class);
            View panel = field(activity, "toolsPanel", View.class);
            check(header.getParent() == panel, "Landscape sidebar");
            RectF bounds = field(view, "image", RectF.class);
            float aspectRatio = (float) ThermalFrame.WIDTH / ThermalFrame.HEIGHT;
            check(Math.abs(bounds.width() / bounds.height() - aspectRatio) < 0.01f,
                    "Landscape frame keeps native aspect ratio");
            float density = view.getResources().getDisplayMetrics().density;
            check(panel.getWidth() <= 230 * density, "Compact landscape sidebar");
            TextView hot = field(activity, "hot", TextView.class);
            check(hot.getPaint().measureText(hot.getText().toString()) <= hot.getWidth(),
                    "Full temperature value fits the sidebar");
            if ((view.getHeight() - 24 * density) * aspectRatio <= view.getWidth() - 61 * density) {
                check(bounds.height() >= view.getHeight() - 25 * density,
                        "Landscape preview fills the height");
            }
            int nativeX = 91, nativeY = 42;
            float sx = (nativeX + 0.5f) / ThermalFrame.WIDTH;
            float sy = (nativeY + 0.5f) / ThermalFrame.HEIGHT;
            float x = bounds.left + bounds.width() * sx;
            float y = bounds.top + bounds.height() * sy;
            tapPreview(view, x / view.getWidth(), y / view.getHeight());
            check(field(view, "spot", Integer.class) == nativeY * ThermalFrame.WIDTH + nativeX,
                    "Tap follows camera coordinates in landscape");
        });
    }

    private static void showFrame(MainActivity activity, Method render, Field frameField,
                                  ThermalFrame frame) {
        try {
            frameField.set(activity, frame);
            render.invoke(activity);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void tapPreview(ThermalView view, float xf, float yf) {
        float x = view.getWidth() * xf;
        float y = view.getHeight() * yf;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0);
        view.dispatchTouchEvent(down);
        view.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private static void checkUsbSample(byte[] frame) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new UsbSample(null, frame, 0, "test").write(output);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            check("usb.properties".equals(zip.getNextEntry().getName()), "Diagnostic USB info");
            Properties info = new Properties();
            info.load(zip);
            check(Integer.toString(frame.length).equals(info.getProperty("uvc.received_bytes")),
                    "Diagnostic byte count");
            check("frame.bin".equals(zip.getNextEntry().getName()), "Diagnostic frame");
            ByteArrayOutputStream restored = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = zip.read(buffer)) != -1;) restored.write(buffer, 0, count);
            check(Arrays.equals(frame, restored.toByteArray()), "Diagnostic frame bytes");
            check(zip.getNextEntry() == null, "Diagnostic archive entries");
        }
    }

    private static byte[] fixture() {
        ByteBuffer bytes = ByteBuffer.allocate(ThermalFrame.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int hot = 75 * ThermalFrame.WIDTH + 186;
        int cold = 31 * ThermalFrame.WIDTH + 42;
        for (int i = 0; i < ThermalFrame.PIXELS; i++) {
            int x = i % ThermalFrame.WIDTH;
            int y = i / ThermalFrame.WIDTH;
            double warm = Math.exp(-((x - 186) * (x - 186) + (y - 75) * (y - 75)) / 1400.0);
            double cool = Math.exp(-((x - 42) * (x - 42) + (y - 31) * (y - 31)) / 750.0);
            int count = 7000 + (int) (3300 * warm - 2200 * cool);
            bytes.putShort(2 * i, (short) count);
        }
        int meta = ThermalFrame.PIXELS * 2;
        bytes.putShort(meta + 2, (short) 5540);
        bytes.putShort(meta + 4, (short) (hot % ThermalFrame.WIDTH));
        bytes.putShort(meta + 6, (short) (hot / ThermalFrame.WIDTH));
        bytes.putShort(meta + 8, bytes.getShort(hot * 2));
        bytes.putShort(meta + 10, (short) (cold % ThermalFrame.WIDTH));
        bytes.putShort(meta + 12, (short) (cold / ThermalFrame.WIDTH));
        bytes.putShort(meta + 14, bytes.getShort(cold * 2));
        bytes.putShort(meta + 24, (short) 7000);
        int calibration = meta + ThermalFrame.WIDTH * 2;
        bytes.putShort(calibration, (short) 4000);
        bytes.putShort(calibration + 2, (short) 2981);
        bytes.putFloat(calibration + 6, 1);
        bytes.putFloat(calibration + 10, 1);
        bytes.putFloat(calibration + 14, 0);
        bytes.putFloat(calibration + 18, 0);
        bytes.putFloat(calibration + 22, 0.02f);
        return bytes.array();
    }

    private static <T> T field(Object object, String name, Class<T> type) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(object));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
