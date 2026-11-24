package org.thermal.wp09;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipInputStream;

public final class ThermalSmokeTest extends Instrumentation {
    @Override public void onCreate(Bundle options) {
        super.onCreate(options);
        start();
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            checkAutomaticRegistration();
            checkLearnedRegistration();
            checkThermometryAndCapture();
            results.putString("stream", "\nWP-09 thermometry, automatic fusion, rotation, calibration, diagnostic ZIP and PNG/MP4 checks passed.\n");
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            results.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, results);
        }
    }

    private void checkAutomaticRegistration() {
        FusionRegistration.Transform transform = calibrate(false);
        check(transform != null && Math.abs(transform.a - 0.15f) < 0.025f
                && Math.abs(transform.b - 1.15f) < 0.025f && Math.abs(transform.x - 82) < 1.5f
                && Math.abs(transform.y + 20) < 1.5f, "Three stationary palm positions recover rotation, scale, and translation");
        check(calibrate(true) == null, "Reject an inconsistent third position");
        check(FusionRegistration.Transform.decode(transform.encode()) != null
                && FusionRegistration.Transform.decode("NaN,1,0,0") == null, "Saved transform round trip and validation");

        FusionRegistration.Transform base = new FusionRegistration.Transform(1, 0, 0, 0);
        FusionRegistration.Image heat = structure(0, 0, true);
        FusionRegistration.Transform tracked = FusionRegistration.track(heat, structure(3, -2, false), base);
        check(tracked != null && tracked.x == -3 && tracked.y == 2, "Match opposite-polarity common edges after small motion");
        check(FusionRegistration.track(heat, structure(15, 13, false), base) == null,
                "Do not apply an offset outside the tracking range");
        check(FusionRegistration.track(heat, new FusionRegistration.Image(FusionRegistration.HEIGHT, 13), base)
                == null, "Fall back when the visible scene has no texture");
        check(FusionRegistration.paired(1_100_000_000L, 1_040_000_000L)
                && !FusionRegistration.paired(1_100_000_000L, 1_000_000_000L), "Frame arrival separation limit");
    }

    private void checkLearnedRegistration() throws Exception {
        int w = ThermalFrame.WIDTH, h = ThermalFrame.HEIGHT;
        Bitmap camera = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            android.graphics.Canvas canvas = new android.graphics.Canvas(camera);
            java.util.Random random = new java.util.Random(2001);
            android.graphics.Paint paint = new android.graphics.Paint();
            for (int i = 0; i < 140; i++) {
                int color = 30 + random.nextInt(220);
                paint.setColor(Color.rgb(color, color, color));
                float x = random.nextInt(w), y = random.nextInt(h);
                canvas.drawRect(x, y, x + 3 + random.nextInt(30), y + 3 + random.nextInt(20), paint);
            }
            int[] pixels = new int[w * h];
            camera.getPixels(pixels, 0, w, 0, 0, w, h);
            byte[] bytes = new byte[ThermalFrame.BYTES];
            for (int y = 0; y < h - 8; y++) {
                for (int x = 16; x < w; x++) bytes[2 * (y * w + x)] = (byte) pixels[(y + 8) * w + x - 16];
            }
            ThermalFrame heat = new ThermalFrame.Decoder().decode(bytes, .98f);
            ThermalView.Pair pair = new ThermalView.Pair(heat, camera, 0, 0, 1, 0, 0);
            FusionRegistration.Transform result = LearnedAlignment.estimate(getTargetContext(), pair, () -> false);
            check(result != null && Math.abs(result.x - 6) < 1.2f && Math.abs(result.y + 3) < 1.2f
                    && Math.abs(result.a - 1) < .04 && Math.abs(result.b) < .04,
                    "Bundled MINIMA model recovers a known two-axis translation on Android");
        } finally {
            camera.recycle();
        }
    }

    private FusionRegistration.Transform calibrate(boolean inconsistent) {
        FusionRegistration.Calibration setup = new FusionRegistration.Calibration();
        for (int n = 0; n < 4; n++) {
            check(setup.observe(palm(-1, true, false), palm(-1, false, false)) == (n == 3 ? 0 : -1),
                    "Wait for a stable empty background");
        }
        for (int index = 0; index < 3; index++) {
            FusionRegistration.Image heat = palm(index, true, false);
            FusionRegistration.Image light = palm(index, false, inconsistent && index == 2);
            for (int n = 0; n < 3; n++) {
                check(setup.observe(heat, light) == index + (n == 2 ? 1 : 0), "Automatically accept a held palm");
            }
        }
        return setup.result();
    }

    private FusionRegistration.Image palm(int target, boolean thermal, boolean inconsistent) {
        int width = FusionRegistration.WIDTH;
        int height = FusionRegistration.HEIGHT;
        FusionRegistration.Image image = new FusionRegistration.Image(thermal ? height : 88, thermal ? .9f : 13f);
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < width; x++) {
                float hx = x + .5f;
                float hy = y + .5f;
                if (!thermal) {
                    hx = .15f * (x + .5f) - 1.15f * (y + .5f) + 82;
                    hy = 1.15f * (x + .5f) + .15f * (y + .5f) - 20;
                }
                float level = thermal ? 22 + x * .007f : 140 + x * .2f + (target >= 0 ? 6 : 0);
                if (target >= 0) {
                    float dx = (hx - width * FusionRegistration.TARGET_X[target] - (inconsistent ? 12 : 0)) / 6f;
                    float dy = (hy - height * FusionRegistration.TARGET_Y[target]) / 8f;
                    if (dx * dx + dy * dy < 1) level += thermal ? 6 : -55;
                }
                image.values[y * width + x] = level;
            }
        }
        return image;
    }

    private FusionRegistration.Image structure(int dx, int dy, boolean thermal) {
        int width = FusionRegistration.WIDTH;
        FusionRegistration.Image image = new FusionRegistration.Image(FusionRegistration.HEIGHT, thermal ? .9f : 13);
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < width; x++) {
                int u = x - dx;
                int v = y - dy;
                boolean inside = (u > 13 && u < 38 && v > 15 && v < 33)
                        || (u > 54 && u < 77 && v > 31 && v < 53)
                        || (u > 31 && u < 44 && v > 45 && v < 62);
                image.values[y * width + x] = thermal ? (inside ? 35 : 22) : (inside ? 49 : 131);
            }
        }
        return image;
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
        FusionRegistration.Image alignmentImage = FusionRegistration.Image.fromThermal(summary);
        float topLeft = 0;
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) topLeft += (preview[2 * (y * ThermalFrame.WIDTH + x)] & 255) / 4f;
        }
        check(Math.abs(topLeft - alignmentImage.values[0]) < 0.001f, "Use 8-bit brightness for alignment in preview mode");
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
        runOnMainSync(() -> checkFusionCapture(frame));
        checkVisibleCamera(activity, render);

        LinkedBlockingQueue<String> mediaResult = new LinkedBlockingQueue<>();
        ThermalMedia media = new ThermalMedia(getTargetContext(), mediaResult::offer);
        int[] visiblePixels = {Color.RED, Color.GREEN, Color.RED, Color.GREEN};
        FusionRegistration.Transform identity = new FusionRegistration.Transform(1, 0, 0, 0);
        try {
            runOnMainSync(() -> {
                view.setFusion(50, 100, 0, 0, 0);
                showAutomaticCapture(view, visiblePixels, identity);
                media.savePair(view);
            });
            String pairResult = mediaResult.poll(8, TimeUnit.SECONDS);
            check(pairResult != null && pairResult.startsWith("图对已存入"), "Unblended visible and thermal PNG pair saved");
            runOnMainSync(() -> {
                showAutomaticCapture(view, visiblePixels, identity);
                media.photo(view);
            });
            check("照片已保存".equals(mediaResult.poll(8, TimeUnit.SECONDS)), "PNG save");

            LinkedBlockingQueue<Boolean> started = new LinkedBlockingQueue<>();
            media.start(started::offer);
            check(Boolean.TRUE.equals(started.poll(8, TimeUnit.SECONDS)), "Video start");
            for (int i = 0; i < 22; i++) {
                runOnMainSync(() -> {
                    showAutomaticCapture(view, visiblePixels, identity);
                    media.frame(view, () -> {});
                });
                SystemClock.sleep(55);
            }
            media.stop();
            check("录像已保存".equals(mediaResult.poll(12, TimeUnit.SECONDS)), "MP4 save");
        } finally {
            media.destroy();
            runOnMainSync(activity::finish);
        }
    }

    private void showAutomaticCapture(ThermalView view, int[] pixels, FusionRegistration.Transform transform) {
        long now = SystemClock.elapsedRealtimeNanos();
        view.setThermalTime(now);
        view.showVisible(pixels, 2, 2, 0, now);
        view.setAutomaticFusion(true, transform, now + 550_000_000L);
    }

    private void checkFusionCapture(ThermalFrame frame) {
        ThermalView view = new ThermalView(getTargetContext());
        view.show(frame, Calibration.DEFAULT, false);
        Bitmap output = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
        int[] pixels = {Color.RED, Color.GREEN, Color.RED, Color.GREEN};
        int x = 128;
        int y = 240;
        try {
            view.drawCapture(output);
            int thermal = output.getPixel(x, y);
            view.showVisible(pixels, 2, 2, 0);
            view.setFusion(0, 100, 0, 0, 0);
            view.drawCapture(output);
            check(output.getPixel(x, y) == Color.RED, "Camera image in capture");
            view.setFusion(50, 100, 0, 0, 0);
            view.drawCapture(output);
            int fused = output.getPixel(x, y);
            check(fused != thermal && fused != Color.RED, "Thermal and visible layers both contribute");
            view.setFusion(0, 100, 0, 0, 180);
            view.drawCapture(output);
            check(output.getPixel(x, y) == Color.GREEN, "Visible image rotates in thermal coordinates");
            view.setFusion(0, 50, 75, 0, 0);
            view.drawCapture(output);
            check(output.getPixel(x, y) == thermal, "Thermal image fills outside shifted camera bounds");
            view.clearVisible();
            view.drawCapture(output);
            check(output.getPixel(x, y) == thermal, "Pure thermal fallback");

            view.setFusion(0, 50, 75, 0, 180);
            showAutomaticCapture(view, pixels, new FusionRegistration.Transform(1, 0, 0, 0));
            view.drawCapture(output);
            check(output.getPixel(x, y) == Color.RED, "Capture uses the automatic transform when available");
            long cameraAt = SystemClock.elapsedRealtimeNanos();
            view.showVisible(pixels, 2, 2, 0, cameraAt);
            view.setThermalTime(cameraAt - 140_000_000L);
            view.drawCapture(output);
            check(output.getPixel(x, y) == thermal, "A distant frame pair returns to thermal capture");
        } finally {
            output.recycle();
        }
    }

    private boolean awaitLabel(String label, boolean click) {
        long deadline = SystemClock.uptimeMillis() + 6000;
        do {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                    if (!label.equals(String.valueOf(node.getText()))) continue;
                    if (!click) return true;
                    while (node != null && !node.isClickable()) node = node.getParent();
                    if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                }
            }
            SystemClock.sleep(80);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private void checkVisibleCamera(MainActivity activity, Method render) throws Exception {
        if (Build.VERSION.SDK_INT < 28 || !activity.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CAMERA)) return;
        getUiAutomation().grantRuntimePermission(getTargetContext().getPackageName(), Manifest.permission.CAMERA);
        View fusion = field(activity, "fusionButton", View.class);
        View alignment = field(activity, "alignmentButton", View.class);
        Field streaming = MainActivity.class.getDeclaredField("streaming");
        streaming.setAccessible(true);
        runOnMainSync(() -> {
            try {
                streaming.setBoolean(activity, true);
                render.invoke(activity);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
            check(fusion.performClick(), "Open visible camera from thermal preview");
        });
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 6500;
        do {
            runOnMainSync(() -> ready.set(alignment.isEnabled()));
            if (ready.get()) break;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        runOnMainSync(() -> {
            check(alignment.isEnabled(), "Rear camera delivered a visible frame");
            check(alignment.performClick(), "Open guided alignment from the normal controls");
        });
        waitForIdleSync();
        check(awaitLabel("AI 对齐当前画面", false),
                "Offer learned alignment from the normal controls");
        check(awaitLabel("用手掌自动对齐", true), "Open palm calibration");
        waitForIdleSync();
        check(awaitLabel("开始", true), "Start automatic background capture");
        waitForIdleSync();
        runOnMainSync(() -> {
            check("取消标定".contentEquals(((TextView) alignment).getText()), "Calibration became active");
            check(field(activity, "fusionNotice", View.class).isShown(), "Calibration instructions are visible");
        });
        saveScreenshot("smoke-fusion-calibration.png");
        runOnMainSync(() -> {
            check(alignment.performClick(), "Cancel calibration");
            check("自动对齐".contentEquals(((TextView) alignment).getText()), "Calibration cancellation restores controls");
        });

        SharedPreferences preferences = field(activity, "settings", SharedPreferences.class);
        String key = field(activity, "alignmentKey", String.class);
        String previousCalibration = preferences.getString(key, null);
        boolean hadAiFlag = preferences.contains(key + ".ai");
        boolean previousAi = preferences.getBoolean(key + ".ai", false);
        boolean hadMode = preferences.contains("fusion_manual");
        boolean previousMode = preferences.getBoolean("fusion_manual", false);
        FusionRegistration.Transform result = calibrate(false);
        Method begin = MainActivity.class.getDeclaredMethod("beginAutomaticCalibration");
        Method finish = MainActivity.class.getDeclaredMethod("finishAutomaticCalibration",
                FusionRegistration.Transform.class, String.class);
        Method finishLearned = MainActivity.class.getDeclaredMethod("endLearnedAlignment",
                FusionRegistration.Transform.class, String.class);
        finishLearned.setAccessible(true);
        begin.setAccessible(true);
        finish.setAccessible(true);
        try {
            runOnMainSync(() -> {
                try {
                    finishLearned.invoke(activity, result, "测试 AI 标定");
                    finishLearned.invoke(activity, null, "测试拒绝不一致匹配");
                    check(result.encode().equals(preferences.getString(key, null))
                            && preferences.getBoolean(key + ".ai", false), "Keep the learned profile after a failed retry");
                    begin.invoke(activity);
                    finish.invoke(activity, result, null);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
                check(result.encode().equals(preferences.getString(key, null)), "Save the measured alignment profile");
                check(fusion.performClick(), "Close visible camera");
                check(!alignment.isEnabled() && !fusion.isSelected(), "Return to thermal preview");
                check(fusion.performClick(), "Reopen visible camera");
            });
            long reopenDeadline = SystemClock.uptimeMillis() + 6500;
            do {
                runOnMainSync(() -> ready.set(alignment.isEnabled()));
                if (ready.get()) break;
                SystemClock.sleep(50);
            } while (SystemClock.uptimeMillis() < reopenDeadline);
            runOnMainSync(() -> {
                FusionRegistration.Transform restored = field(activity, "savedAlignment", FusionRegistration.Transform.class);
                check(alignment.isEnabled() && restored != null && result.encode().equals(restored.encode()),
                        "Reuse calibration automatically after reopening the camera");
                check(fusion.performClick(), "Stop fused preview");
            });
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            if (previousCalibration == null) restore.remove(key);
            else restore.putString(key, previousCalibration);
            if (hadAiFlag) restore.putBoolean(key + ".ai", previousAi);
            else restore.remove(key + ".ai");
            if (hadMode) restore.putBoolean("fusion_manual", previousMode);
            else restore.remove("fusion_manual");
            restore.apply();
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
