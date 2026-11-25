package org.thermal.wp09;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.net.Uri;
import android.view.ViewTreeObserver;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

public final class ThermalSmokeTest extends Instrumentation {
    private boolean skipMedia, exportFieldFrames, exportMobileMovie, directReplay;
    private int seconds = 4;
    private byte[] rawDump, reference;
    private ThermalFrame[] recorded;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle options) {
        super.onCreate(options);
        skipMedia = options != null && "true".equals(options.getString("skipMedia"));
        exportFieldFrames = options != null && "true".equals(options.getString("exportFieldFrames"));
        exportMobileMovie = options != null && "true".equals(options.getString("exportMobileMovie"));
        directReplay = options != null && "true".equals(options.getString("directReplay"));
        if (options != null && options.containsKey("replaySeconds")) {
            seconds = Math.max(2, Math.min(120, Integer.parseInt(options.getString("replaySeconds"))));
        }
        start();
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            checkThermometryAndCapture();
            results.putString("stream", "\nWP-09 checks passed. " + report + "\n");
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
        checkThermalOutput(decoder, wire, preview, summary);
        checkShutterGate(decoder, wire);
        Calibration calibration = Calibration.twoPoint(20, 24, 50, 57);
        check(Math.abs(calibration.apply(35) - 40.5f) < 0.01f, "Two-point calibration");

        Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) startActivitySync(intent);
        waitForIdleSync();
        ThermalView view = field(activity, "thermal", ThermalView.class);
        MaterialButton laser = field(activity, "laserButton", MaterialButton.class);
        boolean noDevice = field(activity, "selected", android.hardware.usb.UsbDevice.class) == null;
        if (noDevice) runOnMainSync(() -> check(!laser.isEnabled() && "开启激光".contentEquals(laser.getText()),
                "Disconnected laser shortcut"));
        runOnMainSync(() -> activity.receive(summary));
        waitForIdleSync();
        runOnMainSync(() -> {
            check(view.spotReading() == summary.center, "Preview center reading");
            tapImage(view, 70, 96);
            check(view.spotDisplay().startsWith("≈"), "Estimated spot is identified");
            check(view.calibrationReading() == summary.center, "Preview calibration uses center");
            Bitmap canvas = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(canvas));
            canvas.recycle();
            activity.receive(frame);
            View freeze = field(activity, "pauseButton", View.class);
            freeze.performClick();
            check(view.capture().frame == summary, "Freeze retains the UVC frame actually drawn");
            if (noDevice) {
                check(laser.isEnabled(), "Laser shortcut is available while frozen");
                laser.performClick();
                check(laser.isEnabled() && !laser.isSelected() && "开启激光".contentEquals(laser.getText()),
                        "Unconfirmed laser command retains last state and allows retry");
            }
            activity.receive(frame);
            check(view.capture().frame == summary, "Later delivery cannot replace a frozen preview");
            freeze.performClick();
            check(view.capture().frame == frame, "Resume returns to the newest arrival");
        });
        waitForIdleSync();
        runOnMainSync(() -> {
            tapImage(view, 65, 91);
            check(view.measurement.start == 91 * ThermalFrame.WIDTH + 65, "Sensor coordinates");
            check(view.spotReading() == frame.readings[view.measurement.start], "Pixel reading");
            check(view.spotDisplay().equals(ThermalView.number(view.spotReading()) + " °C"), "Exact spot");
            checkZoom(view);
            checkLabels(view, frame, calibration);
        });
        checkLandscape(activity, view);
        checkMeasurement(frame, calibration);
        checkPayload(summary, calibration);
        ThermalFrame oneAnchor = new ThermalFrame(null, summary.preview, 1, summary.hot,
                -70, summary.maximum, summary.center, Float.NaN, System.nanoTime(), null, ThermalFrame.LEGACY_LEFT_MASK);
        check(oneAnchor.cold < 0 && Float.isNaN(oneAnchor.minimum) && Float.isNaN(oneAnchor.temperatureAt(1)),
                "Masked preview anchor cannot trigger an alarm or supply a reading");
        checkPayload(oneAnchor, calibration);
        checkPayload(frame, calibration);
        checkPayload(decoder.decode(new byte[ThermalFrame.BYTES], 0.98f), calibration);
        checkScaleAndAlarms(frame);
        ThermalFrame both = new ThermalFrame(frame.readings, summary.preview, frame.cold, frame.hot,
                frame.minimum, frame.maximum, frame.center, frame.average);
        checkFirmwareImage(both, summary, calibration);
        checkPayload(both, calibration);
        checkMobileDisplay(frame, summary, calibration);
        checkMixedFieldFiles(view);
        checkNewFieldFiles();
        checkLegacyFiles();
        ThermalFrame real = realSnapshot(decoder);
        if (real != null) {
            checkPayload(real, Calibration.DEFAULT);
            report.append("All 393,216 real raw-to-Celsius samples verified at firmware crop (2,8). ");
        }
        if (!skipMedia) {
            checkVariableMovie(summary, frame);
            checkBurstMovie(decoder, wire);
            checkRecordingButton(activity, decoder, wire, summary);
        }
        if (!skipMedia) checkMedia(activity, view, decoder, wire, real == null ? frame : real);
        runOnMainSync(activity::finish);
    }

    private ThermalFrame realSnapshot(ThermalFrame.Decoder decoder) throws Exception {
        File folder = getTargetContext().getCacheDir();
        File source = new File(folder, "wp09-dump.bin");
        if (!source.isFile()) return null;
        rawDump = readFile(source);
        recorded = new ThermalFrame[8];
        reference = readFile(new File(folder, "wp09-reference.bin"));
        float[] table = new float[16384];
        check(Wp09Usb.temperatureTable(reference, table, 0.98f), "Factory thermometry table");
        ByteBuffer words = ByteBuffer.wrap(rawDump).order(ByteOrder.LITTLE_ENDIAN);
        ThermalFrame frame = null;
        for (int i = 0; i < 8; i++) {
            frame = decoder.decodeSnapshot(rawDump, i, reference, 0.98f);
            recorded[i] = frame;
            check(frame != null && frame.hasPixelTemperatures(), "Real dump frame " + i);
            for (int p = 0; p < frame.readings.length; p++) {
                int x = p % 256, y = p / 256;
                int value = Short.toUnsignedInt(words.getShort(((i * 203 + 8 + y) * 384 + 2 + x) * 2));
                check(Float.isFinite(frame.readings[p]) && frame.readings[p] == table[value],
                        "Every measurement follows the driver's active rectangle");
            }
        }
        File paired = new File(folder, "wp09-paired-reference.bin");
        if (paired.isFile()) {
            byte[] image = readFile(paired);
            ThermalFrame raw = decoder.decodeSnapshot(rawDump, 7, image, 0.98f);
            long stamp = System.nanoTime();
            frame = decoder.decodeSnapshot(rawDump, 7, image, 0.98f, stamp);
            check(frame != null && frame.hasFirmwareImage() && frame.capturedNanos == stamp
                    && Arrays.equals(frame.readings, raw.readings), "Paired exposure keeps every sensor reading");
            for (int pixel = 0; pixel < ThermalFrame.PIXELS; pixel++) {
                check(frame.preview[pixel] == image[2 * pixel], "Original WP-09 display luminance");
            }
            checkFirmwareImage(frame, decoder.decode(image, 0.98f), Calibration.DEFAULT);
            sameMarkers(frame.firmware, decoder.decode(image, 0.98f, stamp));
            report.append(String.format(java.util.Locale.ROOT,
                    "Real trailer cold/hot (%d,%d)=%.2f/(%d,%d)=%.2f, raw (%d,%d)=%.2f/(%d,%d)=%.2f. ",
                    frame.firmware.cold % 256, frame.firmware.cold / 256, frame.firmware.minimum,
                    frame.firmware.hot % 256, frame.firmware.hot / 256, frame.firmware.maximum,
                    frame.cold % 256, frame.cold / 256, frame.minimum, frame.hot % 256, frame.hot / 256, frame.maximum));
            report.append("Real firmware image matched pixel by pixel. ");
        }
        return frame;
    }

    private void checkThermalOutput(ThermalFrame.Decoder decoder, byte[] wire, byte[] preview,
                                    ThermalFrame referenceFrame) throws Exception {
        byte[] kb = new byte[ThermalFrame.PIXELS * 4];
        int defect = Measurement.pixel(20, 20), firstHot = Measurement.pixel(70, 60);
        kb[2 * defect + 1] = (byte) 0x80;
        BlindPixels defects = BlindPixels.fromFactory(kb);
        check(defects != null && defects.contains(defect) && !defects.contains(firstHot), "Device-specific bad sites");
        check(decoder.decodeThermalOutput(preview, .98f, 0, defects) == null, "A queued YUYV preview is never radiometry");
        ThermalFrame matched = decoder.decodeThermalOutput(wire.clone(), .98f, 1, null);
        check(matched != null && matched.agreesWithPreview(referenceFrame), "Independent preview identification");
        byte[] live = wire.clone();
        ByteBuffer ad = ByteBuffer.wrap(live).order(ByteOrder.LITTLE_ENDIAN);
        int[] neighborhood = {5000, 6100, 6400, 6500, 6700, 6900, 7200, 10000};
        int n = 0;
        for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
            if (x != 0 || y != 0) ad.putShort(2 * (defect + y * 256 + x), (short) neighborhood[n++]);
        }
        ad.putShort(2 * defect, (short) 16000);
        ad.putShort(2 * firstHot, (short) 11000);
        ad.putShort(2 * (firstHot + 4), (short) 11000);
        ad.putShort(2 * (ThermalFrame.CENTER - 1), (short) 6500);
        ad.putShort(2 * ThermalFrame.CENTER, (short) 7601);
        check(decoder.decodeThermalOutput(live.clone(), .98f, 5, null) == null,
                "Stale trailer alone cannot qualify an unknown defect map");
        ThermalFrame frame = decoder.decodeThermalOutput(live, .98f, 6, defects);
        float[] table = new float[16384];
        check(Wp09Usb.temperatureTable(live, table, .98f), "Fixture LUT");
        check(frame != null && frame.thermalOutput, "Qualified thermal stream");
        check(frame.hot == firstHot && frame.maximum == table[11000], "Physical hot code and first tie: " + frame.hot);
        check(frame.center == table[7050] && frame.temperatureAt(ThermalFrame.CENTER) == table[7601],
                "Center AD pair and right pixel: " + frame.center + ", " + table[7050]);
        check(frame.temperatureAt(defect) == table[(6100 + 6400 + 6500 + 6700 + 6900 + 7200) / 6],
                "Trimmed replacement: " + ad.getShort(2 * defect));
        check(frame.firmware == null, "Stale firmware coordinates never attached");
        check(!frame.agreesWithPreview(new ThermalFrame(null, referenceFrame.preview, referenceFrame.cold,
                referenceFrame.hot, referenceFrame.minimum - 5, referenceFrame.maximum + 5,
                referenceFrame.center, Float.NaN)), "Contradictory stream fails identification");
        for (int p = 0; p < ThermalFrame.PIXELS; p++) {
            check(frame.temperatureAt(p) == table[Short.toUnsignedInt(ad.getShort(p * 2))]
                    && !frame.isEstimatedAt(p), "Every thermal output pixel uses its own code");
        }
        checkPayload(frame, Calibration.DEFAULT);
        ad.putShort(2 * firstHot, (short) 0x8060);
        check(decoder.decodeThermalOutput(live, .98f, 7, defects) == null, "Reject partial mixed mode data");
        report.append("49,152 synthetic thermal-output sites, bad-site repair and stale telemetry checked. ");
    }

    private void checkShutterGate(ThermalFrame.Decoder decoder, byte[] wire) throws Exception {
        ShutterGate gate = new ShutterGate();
        BlindPixels defects = BlindPixels.fromFactory(new byte[ThermalFrame.PIXELS * 4]);
        ThermalFrame scene = decoder.decodeThermalOutput(wire, .98f, 0, defects);
        gate.verified(scene);
        byte[] closed = wire.clone();
        ByteBuffer words = ByteBuffer.wrap(closed).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < ThermalFrame.PIXELS; i++) words.putShort(i * 2, (short) 7100);
        ThermalFrame flat = decoder.decodeThermalOutput(closed, .98f, 20_000_000L, defects);
        check(gate.suspect(flat, closed), "A shutter plane with stale scene telemetry needs a firmware reference");
        int trailer = ThermalFrame.PIXELS * 2;
        for (int pos : new int[]{4, 6, 10, 12}) words.putShort(trailer + pos, (short) 0);
        for (int pos : new int[]{8, 14, 24}) words.putShort(trailer + pos, (short) 7100);
        check(!gate.suspect(flat, closed), "A confirmed flat wall is a legitimate live image");
        Arrays.fill(closed, 0, trailer, (byte) 0);
        check(decoder.decodeThermalOutput(closed, .98f, 1, defects) == null, "Reject blank disabled output");
        gate.start(0, true);
        for (long t = 0; t <= 120_000_000L; t += 20_000_000L)
            check(!gate.referenceReady(t), "Queued exposures cannot complete a manual shutter command");
        check(!gate.referenceReady(600_000_000L), "Allow the reopened shutter to settle");
        for (long t = 620_000_000L; t < 700_000_000L; t += 20_000_000L)
            check(!gate.referenceReady(t), "Withhold settling frames");
        check(gate.referenceReady(700_000_000L) && gate.holding(), "Require a matching thermal frame after reference");
        gate.verified(scene);
        check(!gate.holding(), "Reopen the verified stream");

        File movie = new File(getTargetContext().getCacheDir(), "wp09-shutter-gap.mp4");
        long[] times = {0, 20_000, 40_000, 780_000, 800_000};
        try {
            try (ThermalVideo video = new ThermalVideo(movie.getParentFile(), true)) {
                for (long t : times)
                    video.offer(new ThermalCapture(scene, Calibration.DEFAULT, 0, Float.NaN, Float.NaN), t * 1000, true);
                video.finish(1_300_000_000L);
                try (FileOutputStream out = new FileOutputStream(movie)) { video.writeTo(out); }
            }
            try (MovieArchive.Reader video = new MovieArchive.Reader(getTargetContext().getContentResolver()
                    .openAssetFileDescriptor(Uri.fromFile(movie), "r"))) {
                check(video.size() == 5 && video.atPlayTime(770_000) == 2 && video.atPlayTime(790_000) == 3,
                        "AVC and album hold the last real measurement for the shutter gap");
                check(Math.abs(video.durationUs - 1_300_000) < 1000 && video.atPlayTime(1_250_000) == 4,
                        "Stopping during calibration retains the trailing hold");
                for (int i = 0; i < times.length; i++)
                    check(Math.abs(video.timeUs(i) - times[i]) < 1000 && video.captureUs(i) == times[i],
                            "Direct stream preserves independent exposure timestamps");
            }
        } finally { movie.delete(); }
        report.append("Shutter screening, real flat fields, settling and AVC gap holds checked. ");
    }

    private void checkNewFieldFiles() throws Exception {
        File dir = getTargetContext().getCacheDir();
        File still = new File(dir, "field-hand.png"), movie = new File(dir, "field-hand.mp4");
        if (!still.isFile() || !movie.isFile()) return;
        try (InputStream in = new FileInputStream(still)) {
            ThermalCapture c = RadiometricPng.read(in);
            check(c != null && !c.frame.thermalOutput && c.frame.firmware != null, "Legacy hand source");
            checkFieldBounds(c);
            ThermalFrame f = c.frame;
            check(f.reported.hot == Measurement.pixel(172, 153) && Math.abs(f.reported.maximum - 36.392292f) < .0001f
                    && Math.abs(f.maximum - 39.634571f) < .0001f && Float.isNaN(f.temperatureAt(f.hot))
                    && f.isEstimatedAt(f.hot) && !f.isEstimatedAt(f.reported.hot), "Hand: measured hot vs upstream estimate");
        }
        try (MovieArchive.Reader r = new MovieArchive.Reader(getTargetContext().getContentResolver()
                .openAssetFileDescriptor(Uri.fromFile(movie), "r"))) {
            check(r.size() == 56, "Actual hand movie sample count");
            for (int i = 0; i < r.size(); i++) {
                ThermalCapture c = r.frame(i);
                check(c.frame.firmware != null && !c.frame.thermalOutput, "Real movie source");
                checkFieldBounds(c);
            }
            report.append("Hand PNG shows 36.39 C and all 56 archived movie frames checked. ");
        }
    }

    private static byte[] readFile(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) { in.readFully(bytes); }
        return bytes;
    }

    private static void checkPayload(ThermalFrame source, Calibration calibration) throws Exception {
        float lo = source.hasPixelTemperatures() && !source.hasFirmwareImage() ? 10 : Float.NaN;
        float hi = Float.isFinite(lo) ? 80 : Float.NaN;
        ThermalCapture capture = new ThermalCapture(source, calibration, 1, lo, hi);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RadiometricPng.write(out, capture);
        byte[] png = out.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        check(bitmap != null && bitmap.getWidth() == 256 - source.firstColumn && bitmap.getHeight() == 192,
                "Stock PNG decoder sees only the active field");
        ThermalCapture saved = RadiometricPng.read(new ByteArrayInputStream(png));
        check(saved != null && saved.palette == 1 && saved.calibration.scale == calibration.scale
                && saved.calibration.offset == calibration.offset, "PNG display settings and calibration");
        check(Arrays.equals(source.readings, saved.frame.readings) && Arrays.equals(source.preview, saved.frame.preview),
                "Sensor plane lossless");
        check((source.firmware == null) == (saved.frame.firmware == null)
                && source.thermalOutput == saved.frame.thermalOutput, "PNG measurement provenance");
        if (source.firmware != null) sameMarkers(saved.frame.firmware, source.firmware);
        for (int i = 0; i < ThermalFrame.PIXELS; i++) {
            check(Float.compare(saved.frame.temperatureAt(i), source.temperatureAt(i)) == 0
                    && saved.frame.isEstimatedAt(i) == source.isEstimatedAt(i), "PNG arbitrary point");
        }
        Bitmap restored = saved.bitmap();
        check(restored.sameAs(bitmap), "Stock preview matches saved thermal mapping");
        ByteArrayOutputStream legacy = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, legacy);
        check(RadiometricPng.read(new ByteArrayInputStream(legacy.toByteArray())) == null, "Legacy PNG");
        bitmap.recycle();
        restored.recycle();
        png[png.length - 17] ^= 1;
        try {
            RadiometricPng.read(new ByteArrayInputStream(png));
            throw new AssertionError("Damaged thermal PNG accepted");
        } catch (IOException expected) { /* ancillary checksum */ }
    }

    private static void checkLabels(ThermalView view, ThermalFrame base, Calibration calibration) {
        ThermalCapture saved = view.capture();
        Bitmap target = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            view.resetViewport();
            float[] flat = new float[ThermalFrame.PIXELS];
            Arrays.fill(flat, 20);
            for (int corner : new int[]{2, ThermalFrame.WIDTH - 1, ThermalFrame.PIXELS - ThermalFrame.WIDTH + 2, ThermalFrame.PIXELS - 1}) {
                ThermalFrame sameSpot = new ThermalFrame(flat, null, corner, corner,
                        20, 20, 20, 20, 0, null, 0, true);
                view.display(new ThermalCapture(sameSpot, calibration, 0, 0, 50));
                view.draw(new android.graphics.Canvas(target));
                Object hot = field(view, "hotLabel", Object.class), cold = field(view, "coldLabel", Object.class);
                RectF a = field(hot, "box", RectF.class), b = field(cold, "box", RectF.class);
                RectF bounds = field(view, "image", RectF.class);
                check(bounds.contains(a) && bounds.contains(b) && !RectF.intersects(a, b), "Corner labels separated");
                String value = ThermalView.number(calibration.apply(20)) + " °C";
                check(("高 " + value).equals(field(hot, "text", String.class)) &&
                        ("低 " + value).equals(field(cold, "text", String.class)), "Both calibrated labels");
            }
            ThermalFrame.Markers firmware = new ThermalFrame.Markers(70 * 256, 99 * 256 + 1, 19, 41, 25);
            ThermalFrame dual = new ThermalFrame(base.readings, new byte[ThermalFrame.PIXELS], base.cold,
                    base.hot, base.minimum, base.maximum, base.center, base.average, 123, firmware);
            for (float low : new float[]{Float.NaN, 10}) {
                view.display(new ThermalCapture(dual, calibration, 0, low, Float.isNaN(low) ? low : 80));
                view.draw(new android.graphics.Canvas(target));
                RectF dest = field(view, "destination", RectF.class);
                for (boolean high : new boolean[]{false, true}) {
                    Object label = field(view, high ? "hotLabel" : "coldLabel", Object.class);
                    int p = high ? firmware.hot : firmware.cold;
                    String text = (high ? "高 " : "低 ") + ThermalView.number(calibration.apply(
                            high ? firmware.maximum : firmware.minimum)) + " °C";
                    check(field(label, "visible", Boolean.class) && text.equals(field(label, "text", String.class))
                            && Math.abs(field(label, "x", Float.class) - dest.left - (p % 256 + .5f) * dest.width() / 256) < .01f
                            && Math.abs(field(label, "y", Float.class) - dest.top - (p / 256 + .5f) * dest.height() / 192) < .01f,
                            "Markers use the calibrated, measurable extrema in automatic and locked views");
                    tapImage(view, p % 256, p / 256);
                    check(text.endsWith(view.spotDisplay()) && view.measurement.start == p,
                            "Tapping the displayed marker gives the same number");
                }
            }
        } finally {
            view.display(saved);
            target.recycle();
        }
    }

    private static void checkFirmwareImage(ThermalFrame dual, ThermalFrame preview, Calibration cal) {
        ThermalCapture capture = new ThermalCapture(dual, cal, 1, Float.NaN, Float.NaN);
        int[] processed = new int[ThermalFrame.PIXELS], actual = processed.clone();
        new ThermalCapture(preview, cal, 1, Float.NaN, Float.NaN).render(processed, new int[256]);
        capture.render(actual, new int[256]);
        check(Arrays.equals(actual, processed), "Automatic image uses firmware luminance exactly");
        new ThermalCapture(dual, cal, 1, 10, 80).render(actual, new int[256]);
        check(!Arrays.equals(actual, processed), "Locked scale uses radiometry");
    }

    private static void checkMobileDisplay(ThermalFrame f, ThermalFrame preview, Calibration cal) throws Exception {
        MobileImage stationary = MobileImage.fromStart(32, i -> f);
        check(stationary.bias == null, "A stationary scene cannot become a noise reference");
        ThermalFrame paired = new ThermalFrame(f.readings, preview.preview, f.cold, f.hot, f.minimum, f.maximum,
                f.center, f.average, f.capturedNanos, null, f.firstColumn, f.thermalOutput);
        int[] a = new int[ThermalFrame.PIXELS], b = a.clone(), lut = new int[256];
        for (boolean locked : new boolean[]{false, true}) {
            float low = locked ? cal.apply(f.minimum) : Float.NaN, high = locked ? cal.apply(f.maximum) : Float.NaN;
            ThermalCapture raw = new ThermalCapture(f, cal, 2, low, high);
            raw.forMovie(stationary).render(a, lut);
            new ThermalCapture(paired, cal, 2, low, high).forMovie(stationary).render(b, lut);
            check(Arrays.equals(a, b), "A firmware image cannot change full-map movie colors");
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            RadiometricPng.write(png, raw);
            ThermalCapture decoded = RadiometricPng.read(new ByteArrayInputStream(png.toByteArray()));
            check(Float.isFinite(decoded.low) == locked, "PNG preserves lock equal to frame extrema");
            decoded.render(b, lut);
            check(Arrays.equals(a, b), "Raw still uses the same phone display and lock as the movie");
        }
        float[] flat = new float[ThermalFrame.PIXELS];
        Arrays.fill(flat, 20);
        flat[211] = Float.NaN;
        ThermalFrame constant = new ThermalFrame(flat, null, 2, 2, 20, 20, 20, 20, 0, null, 2);
        new ThermalCapture(constant, cal, 2, Float.NaN, Float.NaN).render(a, lut);
        for (int i = 0; i < a.length; i++)
            check(a[i] == (i % 256 < 2 || i == 211 ? 0xff000000 : 0xff808080),
                    "Flat or invalid pixels and legacy crop are deterministic");
    }

    private static void checkFieldBounds(ThermalCapture c) {
        ThermalFrame f = c.frame;
        ThermalFrame.Markers m = f.reported;
        check(f.firstColumn == 0 && f.hasPixelTemperatures() && f.temperatureAt(m.hot) == m.maximum
                && f.temperatureAt(m.cold) == m.minimum, "Field markers agree with a tap at each extremum");
        for (int p = 0; p < ThermalFrame.PIXELS; p++) {
            float value = f.temperatureAt(p);
            check(Float.isNaN(value) ? f.firmware != null && (f.readings[p] < m.minimum || f.readings[p] > m.maximum)
                    : value >= m.minimum && value <= m.maximum, "Only compatible archived estimates are displayed");
        }
    }

    private void checkMixedFieldFiles(ThermalView view) throws Exception {
        File dir = getTargetContext().getCacheDir();
        File photo = new File(dir, "mobile-photo.png"), movie = new File(dir, "mobile-source.mp4");
        if (!photo.isFile() || !movie.isFile()) return;
        try (InputStream in = new FileInputStream(photo)) {
            ThermalCapture c = RadiometricPng.read(in);
            checkFieldBounds(c);
            check(c.frame.hot == 178 * 256 + 57 && c.frame.cold == 9 * 256 + 189
                    && c.frame.maximum > c.frame.firmware.maximum + 4, "Reported field photo reproduced");
            runOnMainSync(() -> {
                ThermalCapture previous = view.capture();
                Bitmap canvas = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    view.resetViewport();
                    view.display(c);
                    view.draw(new android.graphics.Canvas(canvas));
                    for (int p : new int[]{c.frame.reported.hot, c.frame.reported.cold}) {
                        tapImage(view, p % 256, p / 256);
                        String label = p == c.frame.reported.hot ? "hotLabel" : "coldLabel";
                        Object mark = field(view, label, Object.class);
                        check(field(mark, "text", String.class).endsWith(view.spotDisplay()),
                                "Field photo canvas and point measurement agree");
                    }
                } finally { view.display(previous); canvas.recycle(); }
            });
        }
        try (MovieArchive.Reader r = new MovieArchive.Reader(getTargetContext().getContentResolver()
                .openAssetFileDescriptor(Uri.fromFile(movie), "r"));
             ThermalVideo export = exportMobileMovie ? new ThermalVideo(dir, true) : null;
             FileOutputStream display = exportMobileMovie ? new FileOutputStream(new File(dir, "mobile-render.argb")) : null) {
            int n = r.size(), paired = 0;
            check(n == 136 && r.evenPlayback(), "Reported mixed-source recording");
            int[] pixels = new int[ThermalFrame.PIXELS], other = pixels.clone(), lut = new int[256];
            ByteBuffer argb = ByteBuffer.allocate(pixels.length * 4);
            long[] cost = new long[n];
            for (int i = 0; i < n; i++) {
                ThermalCapture c = r.frame(i);
                ThermalFrame f = c.frame;
                if (f.hasFirmwareImage()) paired++;
                check(c.movieImage != null && c.movieImage.bias != null && !Float.isFinite(c.low),
                        "One phone display profile across matched and unmatched field frames");
                checkFieldBounds(c);
                long start = System.nanoTime();
                c.render(pixels, lut);
                cost[i] = System.nanoTime() - start;
                ThermalFrame opposite = new ThermalFrame(f.readings, f.preview == null ? new byte[pixels.length] : null,
                        f.cold, f.hot, f.minimum, f.maximum, f.center, f.average, 0, null, f.firstColumn);
                new ThermalCapture(opposite, c.calibration, c.palette, c.low, c.high, c.movieImage).render(other, lut);
                check(Arrays.equals(pixels, other), "Rendering is independent of UVC availability on every exposure");
                if (export != null) {
                    export.offer(c, r.captureUs(i) * 1000, true);
                    argb.asIntBuffer().put(pixels);
                    display.write(argb.array());
                }
            }
            r.frame(0).render(other, lut);
            r.frame(n - 1).render(other, lut);
            check(Arrays.equals(pixels, other) && paired == 48, "Random seeks reproduce the same processed pixels");
            Arrays.sort(cost);
            report.append(String.format(java.util.Locale.ROOT,
                    "Mobile field: %d/%d planes, %d paired, render median %.2f ms, p95 %.2f ms. ",
                    n, n, paired, cost[n / 2] / 1e6, cost[(int) Math.ceil(.95 * n) - 1] / 1e6));
            if (export != null) {
                export.finish(r.durationUs * 1000);
                try (FileOutputStream out = new FileOutputStream(new File(dir, "mobile-result.mp4"))) { export.writeTo(out); }
            }
        }
    }

    private static void sameMarkers(ThermalFrame.Markers saved, ThermalFrame preview) {
        sameMarkers(saved, new ThermalFrame.Markers(preview.cold, preview.hot,
                preview.minimum, preview.maximum, preview.center));
    }

    private static void sameMarkers(ThermalFrame.Markers saved, ThermalFrame.Markers expected) {
        check(saved != null && saved.cold == expected.cold && saved.hot == expected.hot
                && Float.compare(saved.minimum, expected.minimum) == 0
                && Float.compare(saved.maximum, expected.maximum) == 0 && saved.center == expected.center,
                "Firmware coordinates, both extrema and center match the original UVC trailer");
    }

    private void checkLegacyFiles() throws Exception {
        File folder = getTargetContext().getCacheDir();
        File photo = new File(folder, "field.png"), movie = new File(folder, "field.mp4");
        if (!photo.exists() || !movie.exists()) return;
        try (InputStream in = new FileInputStream(photo)) {
            ThermalCapture c = RadiometricPng.read(in);
            check(c != null && c.frame.hasPixelTemperatures() && !c.frame.hasFirmwareImage(), "Field PNG v1");
        }
        File referenceFile = new File(folder, "field-expected.bin");
        try (AssetFileDescriptor fd = getTargetContext().getContentResolver().openAssetFileDescriptor(Uri.fromFile(movie), "r");
             MovieArchive.Reader reader = new MovieArchive.Reader(fd);
             DataInputStream expected = referenceFile.exists() ? new DataInputStream(new FileInputStream(referenceFile)) : null) {
            int n = reader.size();
            ByteBuffer plane = ByteBuffer.allocate(ThermalFrame.PIXELS * 4);
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                ThermalCapture c = reader.frame(i);
                check(c.frame.hasPixelTemperatures() && reader.atTime(reader.timeUs(i)) == i, "Legacy video seek and data");
                if (expected != null) {
                    check(reader.timeUs(i) == expected.readLong() && c.frame.cold == expected.readInt()
                            && c.frame.hot == expected.readInt() && c.frame.average == expected.readFloat(),
                            "Field extrema, mean and original time");
                    plane.asFloatBuffer().put(c.frame.readings);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(plane.array());
                    check(crc.getValue() == Integer.toUnsignedLong(expected.readInt()), "Every field temperature and mask");
                    int[] colors = new int[ThermalFrame.PIXELS];
                    c.render(colors, new int[256]);
                    for (int y = 0; y < ThermalFrame.HEIGHT; y++) {
                        check(colors[y * 256] == android.graphics.Color.BLACK
                                && colors[y * 256 + 1] == android.graphics.Color.BLACK, "Masked movie display");
                    }
                }
            }
            if (expected != null) check(expected.read() == -1, "All reference frames consumed");
            report.append("Field video: ").append(n).append(" temperature planes checked in ")
                    .append((System.nanoTime() - start) / 1_000_000).append(" ms. ");
            File delivery = new File(folder, "field-delivery.mp4");
            if (delivery.isFile()) {
                MediaExtractor avc = new MediaExtractor();
                try (MovieArchive.Reader paced = new MovieArchive.Reader(getTargetContext().getContentResolver()
                        .openAssetFileDescriptor(Uri.fromFile(delivery), "r"))) {
                    avc.setDataSource(delivery.getAbsolutePath());
                    avc.selectTrack(0);
                    check(paced.size() == n && paced.evenPlayback(), "Delivered movie has both clocks");
                    for (int i = 0; i < n; i++) {
                        ThermalFrame f = paced.frame(i).frame;
                        check(f.firstColumn == 2 && f.firmware == null && paced.captureUs(i) == reader.timeUs(i)
                                && Math.abs(paced.timeUs(i) - avc.getSampleTime()) <= 1
                                && Arrays.equals(f.readings, reader.frame(i).frame.readings),
                                "Delivered AVC, original event and temperature plane align");
                        avc.advance();
                    }
                } finally { avc.release(); }
            }
            if (exportFieldFrames) {
                try (FileOutputStream display = new FileOutputStream(new File(folder, "field-active.argb"))) {
                    int[] colors = new int[ThermalFrame.PIXELS];
                    ByteBuffer pixels = ByteBuffer.allocate(ThermalFrame.PIXELS * 4);
                    for (int i = 0; i < n; i++) {
                        Bitmap visible = reader.frame(i).bitmap();
                        int w = visible.getWidth(), h = visible.getHeight();
                        check(w == 254 && h == 192, "Both ends of the old overlap are cropped in the output");
                        visible.getPixels(colors, 0, w, 0, 0, w, h);
                        visible.recycle();
                        pixels.asIntBuffer().put(colors, 0, w * h);
                        display.write(pixels.array(), 0, w * h * 4);
                    }
                }
            }
            if (reader.evenPlayback()) {
                File track = new File(folder, "field-regular-track.mp4");
                File result = new File(folder, "field-regular.mp4");
                MovieArchive.remuxUniform(movie, track, n, reader.durationUs);
                try (MovieArchive.Writer writer = new MovieArchive.Writer(folder);
                     FileOutputStream out = new FileOutputStream(result)) {
                    writer.uniform = true;
                    writer.durationUs = reader.durationUs;
                    for (int i = 0; i < n; i++) {
                        check(reader.atPlayTime(reader.playUs(i)) == i
                                && reader.playUs(i) == MovieArchive.evenTime(i, reader.durationUs, n),
                                "Old album movie uses a uniformly spaced playback clock");
                        writer.add(reader.frame(i), reader.captureUs(i));
                    }
                    MovieArchive.copy(track, out);
                    writer.appendTo(out, track);
                } finally { track.delete(); }
                try (MovieArchive.Reader converted = new MovieArchive.Reader(getTargetContext().getContentResolver()
                        .openAssetFileDescriptor(Uri.fromFile(result), "r"))) {
                    check(converted.evenPlayback() && converted.size() == n, "Paced field movie and event count");
                    for (int i = 0; i < n; i++) {
                        check(converted.captureUs(i) == reader.timeUs(i) &&
                                Math.abs(converted.timeUs(i) - reader.playUs(i)) < 1500 &&
                                Arrays.equals(converted.frame(i).frame.readings, reader.frame(i).frame.readings),
                                "Field remux preserves original event times and planes");
                    }
                }
                if (!skipMedia && reader.durationUs < 12_000_000) checkFieldCadence(movie, n, reader.durationUs);
            }
        }
    }

    private void checkFieldCadence(File file, int count, long duration) throws Exception {
        Intent intent = new Intent(getTargetContext(), ReviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("uri", Uri.fromFile(file).toString()).putExtra("movie", true);
        ReviewActivity activity = (ReviewActivity) startActivitySync(intent);
        ThermalView view = field(activity, "thermal", ThermalView.class);
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (view.drawnFrame() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20);
        long[] painted = new long[count];
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        runOnMainSync(() -> {
            check(field(activity, "evenPlayback", Boolean.class) && view.drawnFrame() != null, "Legacy movie opened paced");
            RectF activeImage = field(view, "image", RectF.class);
            check(Math.abs(activeImage.width() / activeImage.height() - 254f / 192) < .0001f,
                    "Old field of view contains only 254 visible columns");
            tapPreview(view, (activeImage.left + activeImage.width() / 508) / view.getWidth(),
                    activeImage.centerY() / view.getHeight());
            check(view.measurement.start % 256 == 2, "First visible column maps to the first valid archived reading");
            tapPreview(view, (activeImage.right - activeImage.width() / 508) / view.getWidth(),
                    activeImage.centerY() / view.getHeight());
            check(view.measurement.start % 256 == 255, "Last visible column remains tappable");
            view.getViewTreeObserver().addOnDrawListener(() -> {
                int i = field(activity, "index", Integer.class);
                if (!field(activity, "playing", Boolean.class) || painted[i] != 0) return;
                painted[i] = System.nanoTime();
                if (i == count - 1) done.countDown();
            });
            field(activity, "play", View.class).performClick();
        });
        try {
            check(done.await(duration / 1000 + 4000, TimeUnit.MILLISECONDS), "Field playback finished");
            long previous = 0, min = Long.MAX_VALUE, max = 0;
            int shown = 0;
            for (long draw : painted) if (draw > 0) {
                if (previous != 0) {
                    min = Math.min(min, draw - previous);
                    max = Math.max(max, draw - previous);
                }
                previous = draw;
                shown++;
            }
            check(shown == count, "All measured field frames appeared on the display");
            report.append("Album draws ").append(shown).append("/").append(count).append(", min/max ")
                    .append(min / 1_000_000).append("/").append(max / 1_000_000).append(" ms. ");
        } finally { runOnMainSync(activity::finish); }
    }

    private static void checkMeasurement(ThermalFrame f, Calibration cal) {
        Measurement m = new Measurement();
        m.select(Measurement.Tool.LINE);
        m.start = Measurement.pixel(5, 10);
        m.end = Measurement.pixel(1, 10);
        m.evaluate(f, cal);
        check(m.count == 5 && m.profileSize == 5, "Line includes valid edge pixels");
        check(m.profile[0] == cal.apply(f.readings[m.start]) && m.profile[4] == cal.apply(f.readings[m.end]), "Line direction");
        m.select(Measurement.Tool.AREA);
        m.end = Measurement.pixel(1, 8);
        m.evaluate(f, cal);
        double sum = 0;
        for (int y = 8; y <= 10; y++) for (int x = 1; x <= 5; x++) sum += cal.apply(f.readings[Measurement.pixel(x, y)]);
        check(m.count == 15 && Math.abs(m.mean - sum / 15) < 0.001, "Inclusive rectangle includes active edge");
        m.start = m.end;
        m.evaluate(f, cal);
        check(m.count == 1 && m.mean == cal.apply(f.readings[m.end]), "Single-pixel edge region");
    }

    private void checkScaleAndAlarms(ThermalFrame f) {
        ThermalFrame wider = new ThermalFrame(f.readings, null, f.cold, f.hot, f.minimum - 10,
                f.maximum + 50, f.center, f.average);
        ThermalCapture before = new ThermalCapture(f, Calibration.DEFAULT, 0, f.minimum, f.maximum);
        ThermalCapture locked = new ThermalCapture(wider, Calibration.DEFAULT, 0, f.minimum, f.maximum);
        ThermalCapture auto = new ThermalCapture(wider, Calibration.DEFAULT, 0, wider.minimum, wider.maximum);
        int[] first = new int[ThermalFrame.PIXELS], second = first.clone();
        before.render(first, new int[256]);
        locked.render(second, new int[256]);
        check(Arrays.equals(first, second), "Locked color across different extrema");
        auto.render(second, new int[256]);
        check(!Arrays.equals(first, second), "Automatic scale follows frame");
        TemperatureAlarm alarm = new TemperatureAlarm();
        alarm.low = f.minimum; alarm.high = f.maximum;
        check(alarm.update(f, Calibration.DEFAULT) == 3, "Both thresholds crossed");
        check(alarm.update(f, Calibration.DEFAULT) == 0 && alarm.state == 3, "No repeated alert");
        alarm.update(null, Calibration.DEFAULT);
        check(alarm.state == 0, "Disconnect clears alert");
    }

    private void checkVariableMovie(ThermalFrame preview, ThermalFrame full) throws Exception {
        File cache = getTargetContext().getCacheDir();
        File demo = new File(cache, "wp09-sample.mp4");
        File mixed = new File(cache, "wp09-variable.mp4");
        ThermalFrame onlyImage = new ThermalFrame(null, preview.preview, -1, -1, Float.NaN,
                Float.NaN, Float.NaN, Float.NaN);
        long[] times = {0, 22_000, 800_000, 2_200_000};
        ThermalFrame paired = new ThermalFrame(full.readings, preview.preview, full.cold, full.hot,
                full.minimum, full.maximum, full.center, full.average);
        ThermalFrame[] frames = {preview, full, onlyImage, paired};
        try (ThermalVideo video = new ThermalVideo(cache, false)) {
            for (int i = 0; i < times.length; i++) {
                ThermalFrame f = frames[i];
                video.offer(new ThermalCapture(f, Calibration.DEFAULT, 0, f == full ? f.minimum : Float.NaN,
                        f == full ? f.maximum : Float.NaN), times[i] * 1000);
            }
            video.finish(3_000_000_000L);
            try (FileOutputStream out = new FileOutputStream(mixed)) { video.writeTo(out); }
        }
        try (AssetFileDescriptor fd = getTargetContext().getContentResolver().openAssetFileDescriptor(Uri.fromFile(mixed), "r");
             MovieArchive.Reader r = new MovieArchive.Reader(fd)) {
            check(r.size() == 4 && r.durationUs >= 2_200_000, "Variable-rate video duration");
            for (int i = 0; i < 4; i++) {
                ThermalFrame f = r.frame(i).frame;
                check(r.atTime(times[i] + 1000) == i, "Irregular frame time seek");
                check(Arrays.equals(f.readings, frames[i].readings) && Arrays.equals(f.preview, frames[i].preview)
                        && f.thermalOutput == frames[i].thermalOutput && (!f.thermalOutput ||
                            Float.compare(f.center, frames[i].center) == 0), "Mixed frame source preserved");
            }
            check(r.atTime(790_000) == 1 && r.atTime(2_190_000) == 2, "Hold correct frame over gaps");
        } finally { mixed.delete(); }
        if (recorded != null) {
            // Slow, portable sample of all eight original planes for manual frame stepping.
            try (ThermalVideo video = new ThermalVideo(cache, false)) {
                for (int i = 0; i < recorded.length; i++) {
                    ThermalFrame f = recorded[i];
                    video.offer(new ThermalCapture(f, Calibration.DEFAULT, 0, 17, 35), i * 500_000_000L);
                }
                video.finish(recorded.length * 500_000_000L);
                try (FileOutputStream out = new FileOutputStream(demo)) { video.writeTo(out); }
            }
            checkMovie(Uri.fromFile(demo), Arrays.asList(recorded[0].readings, recorded[1].readings,
                    recorded[2].readings, recorded[3].readings, recorded[4].readings,
                    recorded[5].readings, recorded[6].readings, recorded[7].readings));
        }
    }

    private void checkBurstMovie(ThermalFrame.Decoder decoder, byte[] wire) throws Exception {
        // Two synthetic camera bursts with measured-style 20 ms arrivals and an inter-burst gap.
        final int stride = 768, frameBytes = 203 * stride;
        byte[] dump = new byte[8 * frameBytes];
        byte[][] previews = new byte[8][];
        for (int i = 0; i < 8; i++) {
            previews[i] = wire.clone();
            for (int y = 0; y < ThermalFrame.HEIGHT; y++) for (int x = 0; x < ThermalFrame.WIDTH; x++) {
                int pixel = y * ThermalFrame.WIDTH + x;
                int sensor = 5000 + 25 * ((x + 3 * y + 7 * i) % 200);
                int position = i * frameBytes + (y + 8) * stride + (x + 2) * 2;
                dump[position] = (byte) sensor;
                dump[position + 1] = (byte) (sensor >> 8);
                previews[i][2 * pixel] = (byte) ((sensor - 5000) / 25);
                previews[i][2 * pixel + 1] = (byte) 128;
            }
            ByteBuffer trailer = ByteBuffer.wrap(previews[i]).order(ByteOrder.LITTLE_ENDIAN);
            trailer.putShort(ThermalFrame.PIXELS * 2 + 4, (short) (90 + i));
            trailer.putShort(ThermalFrame.PIXELS * 2 + 10, (short) (20 + i));
        }
        LinkedBlockingQueue<String> result = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<Boolean> accepted = new LinkedBlockingQueue<>();
        ThermalMedia media = new ThermalMedia(getTargetContext(), result::offer);
        ThermalFrame[] expected = new ThermalFrame[24];
        long origin = System.nanoTime();
        try {
            media.start(accepted::offer);
            check(Boolean.TRUE.equals(accepted.poll(10, TimeUnit.SECONDS)), "Burst encoder ready");
            for (int batch = 0; batch < 3; batch++) {
                ThermalCapture[] captures = new ThermalCapture[8];
                for (int i = 0; i < 8; i++) {
                    boolean paired = batch < 2;
                    long time = origin + batch * 420_000_000L + (paired ? i * 20_000_000L : 0);
                    ThermalFrame f = decoder.decodeSnapshot(dump, i, previews[i], 0.98f, paired ? time : -time);
                    check(f != null && f.hasFirmwareImage() == paired && f.capturedNanos == time, "Burst source");
                    if (paired) {
                        sameMarkers(f.firmware, decoder.decode(previews[i], 0.98f, time));
                        check(f.firmware.hot != f.hot && f.firmware.cold != f.cold
                                && f.temperatureAt(f.firmware.hot) == f.firmware.maximum, "Firmware markers override raw estimates");
                    }
                    if (i == 0 && batch == 0) checkPayload(f, Calibration.DEFAULT);
                    // The final raw frame deliberately locks exactly to its own extrema.
                    boolean lock = batch == 2 && i == 7;
                    captures[i] = new ThermalCapture(f, Calibration.DEFAULT, 0, lock ? f.minimum : Float.NaN,
                            lock ? f.maximum : Float.NaN);
                    expected[batch * 8 + i] = f;
                }
                media.burst(captures, accepted::offer);
            }
            media.stop();
            for (int batch = 0; batch < 3; batch++) check(Boolean.TRUE.equals(accepted.poll(10, TimeUnit.SECONDS)),
                    "Every camera burst accepted");
            check("录像已保存".equals(result.poll(10, TimeUnit.SECONDS)), "Queued bursts saved before stop");
            Uri movie = CaptureLibrary.list(getTargetContext()).stream().filter(e -> e.movie).findFirst().get().uri;
            try (AssetFileDescriptor fd = getTargetContext().getContentResolver().openAssetFileDescriptor(movie, "r");
                 MovieArchive.Reader reader = new MovieArchive.Reader(fd)) {
                check(reader.size() == expected.length, "Every burst exposure retained");
                check(reader.evenPlayback(), "Full-map movie pacing");
                for (int i = 0; i < reader.size(); i++) {
                    ThermalCapture stored = reader.frame(i);
                    ThermalFrame saved = stored.frame;
                    check(stored.movieImage != null && Float.isFinite(stored.low) == (i == 23),
                            "Short recording flushes all pending frames and preserves exact manual locks");
                    check(Arrays.equals(saved.readings, expected[i].readings)
                            && Arrays.equals(saved.preview, expected[i].preview), "Burst pairing preserved");
                    if (expected[i].firmware == null) check(saved.firmware == null, "Raw-only file event identified");
                    else sameMarkers(saved.firmware, expected[i].firmware);
                    check(reader.captureUs(i) == (expected[i].capturedNanos - origin) / 1000,
                            "Original burst timing preserved across USB gaps");
                    check(Math.abs(reader.timeUs(i) - MovieArchive.evenTime(i, reader.durationUs, reader.size())) < 1000,
                            "Uniform AVC sample times");
                }
            }
            report.append("24/24 burst frames saved, including 8 raw frames at one file event. ");
        } finally { media.destroy(); }
    }

    private void checkMedia(MainActivity activity, ThermalView view, ThermalFrame.Decoder decoder,
                            byte[] wire, ThermalFrame photoFrame) throws Exception {
        LinkedBlockingQueue<String> result = new LinkedBlockingQueue<>();
        ThermalMedia media = new ThermalMedia(getTargetContext(), result::offer);
        ThermalCapture photo = view.compose(photoFrame, Calibration.DEFAULT);
        List<float[]> expected = new ArrayList<>();
        try {
            media.photo(photo);
            check("照片已保存".equals(result.poll(10, TimeUnit.SECONDS)), "PNG published");
            Uri image = latestPhoto();
            LinkedBlockingQueue<Boolean> ready = new LinkedBlockingQueue<>();
            media.start(ready::offer);
            check(Boolean.TRUE.equals(ready.poll(10, TimeUnit.SECONDS)), "Encoder start");
            AtomicInteger draws = new AtomicInteger();
            ThermalFrame[] previous = {null};
            ViewTreeObserver.OnDrawListener listener = () -> {
                ThermalCapture c = view.capture();
                if (c != null && c.frame != previous[0]) { draws.incrementAndGet(); previous[0] = c.frame; }
            };
            runOnMainSync(() -> view.getViewTreeObserver().addOnDrawListener(listener));
            long start = SystemClock.elapsedRealtime();
            int n = seconds * 50;
            BlindPixels noDefects = BlindPixels.fromFactory(new byte[ThermalFrame.PIXELS * 4]);
            float[][] referencePlanes = new float[8][];
            List<ThermalCapture> batch = new ArrayList<>(8);
            java.util.concurrent.Semaphore batches = new java.util.concurrent.Semaphore(2);
            long firstNs = 0, lastNs = 0;
            for (int i = 0; i < n; i++) {
                long left = start + i * 20L - SystemClock.elapsedRealtime();
                if (left > 0) SystemClock.sleep(left);
                int j = i % 8;
                int raw = 6000 + j * 150;
                wire[80] = (byte) raw; wire[81] = (byte) (raw >> 8);
                ThermalFrame f = directReplay ? decoder.decodeThermalOutput(wire, .98f, System.nanoTime(), noDefects)
                        : recorded == null ? decoder.decode(wire, 0.98f)
                        : decoder.decodeSnapshot(rawDump, j, reference, 0.98f);
                if (referencePlanes[j] == null) referencePlanes[j] = f.readings;
                expected.add(referencePlanes[j]);
                if (i == 0) firstNs = f.capturedNanos;
                lastNs = f.capturedNanos;
                boolean submit = directReplay && (i % 8 == 7 || i + 1 == n);
                if (submit) check(batches.tryAcquire(3, TimeUnit.SECONDS), "Map encoder keeps up with capture");
                boolean concurrentPhoto = directReplay && i % 350 == 60;
                runOnMainSync(() -> {
                    activity.receive(f);
                    if (directReplay) {
                        if (concurrentPhoto) media.photo(view.drawnCapture(), false);
                        batch.add(view.capture());
                        if (submit) {
                            media.burst(batch.toArray(new ThermalCapture[0]), ok -> {
                                if (!ok) result.offer("encoding error");
                                batches.release();
                            });
                            batch.clear();
                        }
                    } else media.frame(view.capture(), () -> result.offer("encoding error"));
                });
            }
            waitForIdleSync();
            long span = lastNs - firstNs;
            runOnMainSync(() -> view.getViewTreeObserver().removeOnDrawListener(listener));
            media.stop();
            String notice = result.poll(12, TimeUnit.SECONDS);
            check("录像已保存".equals(notice), "MP4 published: " + notice);
            List<CaptureLibrary> files = CaptureLibrary.list(getTargetContext());
            Uri movie = files.stream().filter(e -> e.movie).findFirst().get().uri;
            try (InputStream in = getTargetContext().getContentResolver().openInputStream(image)) {
                ThermalCapture actual = RadiometricPng.read(in);
                check(actual != null && Arrays.equals(actual.frame.readings, photo.frame.readings)
                        && Arrays.equals(actual.frame.preview, photo.frame.preview), "Published PNG planes");
            }
            int encoded = checkMovie(movie, expected);
            if (directReplay) check(encoded == n, "Every WP-09 recording frame has a matching AVC sample");
            report.append(String.format(java.util.Locale.ROOT, "50 Hz replay: %.1f input fps, %d/%d distinct view draws, %d AVC samples. ",
                    (n - 1) * 1e9 / span, draws.get(), n, encoded));
            checkReview(image, movie, photoFrame);
        } finally { media.destroy(); }
    }

    private void checkRecordingButton(MainActivity activity, ThermalFrame.Decoder decoder,
                                      byte[] wire, ThermalFrame preview) throws Exception {
        if (field(activity, "selected", android.hardware.usb.UsbDevice.class) != null) return;
        Uri before = latestMovie();
        Uri beforePhoto = latestPhoto();
        View button = field(activity, "videoButton", View.class);
        SystemClock.sleep(140);
        runOnMainSync(() -> {
            activity.receive(preview);
            check(!button.isEnabled(), "A three-point-only stream cannot start a recording");
        });
        SystemClock.sleep(140);
        runOnMainSync(() -> {
            activity.receive(decoder.decode(wire, .98f));
            check(button.isEnabled(), "A stream with a full temperature plane can record");
            button.performClick();
        });
        long deadline = SystemClock.uptimeMillis() + 4000;
        while (!field(activity, "recording", Boolean.class) && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(20);
        check(field(activity, "recording", Boolean.class), "One tap starts the recorder");
        ThermalCapture[] drawn = new ThermalCapture[1];
        for (int i = 0; i < 3; i++) {
            SystemClock.sleep(50);
            boolean tap = i == 1;
            runOnMainSync(() -> {
                activity.receive(preview);
                if (tap) {
                    ThermalView view = field(activity, "thermal", ThermalView.class);
                    drawn[0] = view.drawnCapture();
                    View photo = field(activity, "photoButton", View.class);
                    check(photo.isEnabled() && drawn[0].frame.hasPixelTemperatures(), "Still available during recording");
                    photo.performClick();
                }
                activity.receive(decoder.decode(wire, .98f));
            });
        }
        runOnMainSync(button::performClick);
        deadline = SystemClock.uptimeMillis() + 5000;
        Uri saved = before;
        while (java.util.Objects.equals(before, saved) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(30);
            saved = latestMovie();
        }
        check(saved != null && !saved.equals(before), "Stop publishes the one-tap recording");
        // The photo and video are published independently on different workers.
        deadline = SystemClock.uptimeMillis() + 5000;
        Uri still = latestPhoto();
        while (java.util.Objects.equals(beforePhoto, still) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(30);
            still = latestPhoto();
        }
        check(still != null && !still.equals(beforePhoto), "Recording snapshot saved");
        try (InputStream in = getTargetContext().getContentResolver().openInputStream(still)) {
            ThermalCapture restored = RadiometricPng.read(in);
            check(Arrays.equals(restored.frame.readings, drawn[0].frame.readings) && restored.palette == drawn[0].palette,
                    "Saved the drawn exposure even with a newer preview waiting for vsync");
        }
        try (MovieArchive.Reader r = new MovieArchive.Reader(getTargetContext().getContentResolver()
                .openAssetFileDescriptor(saved, "r"))) {
            check(r.size() == 4, "Only the four full-plane arrivals were recorded");
            for (int i = 0; i < r.size(); i++) check(r.frame(i).frame.readings.length == ThermalFrame.PIXELS,
                    "Every recorded sample carries its full temperature plane");
        }
        report.append("One-tap recording saved 4 complete maps from 4 full and 3 preview arrivals. ");
    }

    private Uri latestMovie() {
        return CaptureLibrary.list(getTargetContext()).stream().filter(item -> item.movie)
                .map(item -> item.uri).findFirst().orElse(null);
    }

    private Uri latestPhoto() {
        return CaptureLibrary.list(getTargetContext()).stream().filter(item -> !item.movie)
                .map(item -> item.uri).findFirst().orElse(null);
    }

    private int checkMovie(Uri uri, List<float[]> inputs) throws Exception {
        MediaExtractor mp4 = new MediaExtractor();
        mp4.setDataSource(getTargetContext(), uri, null);
        mp4.selectTrack(0);
        try (AssetFileDescriptor fd = getTargetContext().getContentResolver().openAssetFileDescriptor(uri, "r");
             MovieArchive.Reader reader = new MovieArchive.Reader(fd)) {
            int count = reader.size();
            check(count >= 2, "Video contains multiple measurements");
            int inputIndex = 0;
            for (int i = 0; i < count; i++) {
                long pts = reader.timeUs(i);
                check(mp4.getSampleTime() >= 0 && Math.abs(pts - mp4.getSampleTime()) <= 20,
                        "Metadata synchronized at frame " + i + " (" + pts + ", " + mp4.getSampleTime() + ")");
                check(reader.atTime(pts) == i, "Seek finds exact frame");
                ThermalFrame restored = reader.frame(i).frame;
                while (inputIndex < inputs.size() && !Arrays.equals(restored.readings, inputs.get(inputIndex))) inputIndex++;
                check(inputIndex++ < inputs.size(), "Every decoded video plane matches a submitted frame");
                mp4.advance();
            }
            check(mp4.getSampleTime() == -1, "All video samples have a measurement record");
            check(reader.atTime(reader.durationUs) == count - 1, "End seek");
            return count;
        } finally { mp4.release(); }
    }

    private void checkReview(Uri photo, Uri movie, ThermalFrame expected) throws Exception {
        Rect initialBar = new Rect();
        for (Uri uri : new Uri[]{photo, movie}) {
            Intent intent = new Intent(getTargetContext(), ReviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("uri", uri.toString()).putExtra("movie", uri.equals(movie));
            ReviewActivity review = (ReviewActivity) startActivitySync(intent);
            ThermalView view = field(review, "thermal", ThermalView.class);
            long deadline = SystemClock.uptimeMillis() + 6000;
            while (SystemClock.uptimeMillis() < deadline) {
                boolean[] loaded = {false};
                runOnMainSync(() -> loaded[0] = view.capture() != null);
                if (loaded[0]) break;
                SystemClock.sleep(30);
            }
            waitForIdleSync();
            runOnMainSync(() -> {
                check(view.capture() != null, "Normal album viewer loaded thermal data");
                check(field(review, "status", View.class).getVisibility() == View.GONE, "No source or speed row");
                InspectionPanel panel = field(review, "inspection", InspectionPanel.class);
                MaterialButton hot = field(panel, "hotMarker", MaterialButton.class);
                MaterialButton cold = field(panel, "coldMarker", MaterialButton.class);
                if (uri.equals(photo)) {
                    Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                    for (MaterialButton button : new MaterialButton[]{hot, cold, hot}) {
                        button.performClick();
                        view.draw(new android.graphics.Canvas(bitmap));
                        check(field(field(view, "hotLabel", Object.class), "visible", Boolean.class) == hot.isChecked()
                                        && field(field(view, "coldLabel", Object.class), "visible", Boolean.class) == cold.isChecked(),
                                "Independent buttons control both canvas labels");
                    }
                    bitmap.recycle();
                } else {
                    check(hot.isChecked() && !cold.isChecked(), "Marker choices persist across media");
                    cold.performClick();
                    field(review, "scrub", View.class).getGlobalVisibleRect(initialBar);
                    field(panel, "chart", View.class).performClick();
                }
                tapImage(view, 13, 77);
                if (uri.equals(photo)) check(view.measurement.start == Measurement.pixel(13, 77)
                        && Float.compare(view.spotReading(), expected.temperatureAt(Measurement.pixel(13, 77))) == 0,
                        "Album tap preserves the source and excludes out-of-range legacy estimates");
                else field(review, "next", View.class).performClick();
            });
            if (uri.equals(movie)) {
                long wait = SystemClock.uptimeMillis() + 4000;
                while (field(review, "index", Integer.class) == 0 && SystemClock.uptimeMillis() < wait) SystemClock.sleep(20);
                check(field(review, "index", Integer.class) == 1, "Album next frame");
                runOnMainSync(() -> field(review, "play", View.class).performClick());
                long playDeadline = SystemClock.uptimeMillis() + 2000;
                while (field(review, "index", Integer.class) <= 1 && SystemClock.uptimeMillis() < playDeadline) SystemClock.sleep(20);
                runOnMainSync(() -> {
                    check(field(review, "index", Integer.class) > 1, "Album playback advances");
                    Rect bar = new Rect();
                    field(review, "scrub", View.class).getGlobalVisibleRect(bar);
                    check(!bar.isEmpty() && bar.equals(initialBar), "Timeline stays fixed with chart and playback");
                    check(field(review, "position", TextView.class).getText().toString().matches("[0-9]{2,}:[0-9]{2}")
                                    && field(review, "durationLabel", TextView.class).getLineCount() == 1,
                            "Playback only shows fixed single-line clocks");
                    field(review, "play", View.class).performClick();
                    check(!field(review, "playing", Boolean.class), "Album pause");
                });
            } else saveScreenshot("smoke-album.png");
            runOnMainSync(review::finish);
        }
    }

    private static void tapImage(ThermalView view, int x, int y) {
        RectF image = field(view, "destination", RectF.class);
        tapPreview(view, (image.left + (x + 0.5f) * image.width() / 256) / view.getWidth(),
                (image.top + (y + 0.5f) * image.height() / 192) / view.getHeight());
    }

    private void checkZoom(ThermalView view) {
        RectF image = field(view, "image", RectF.class);
        float x = image.centerX(), y = image.centerY();
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] ids = {new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()};
        ids[0].id = 0; ids[1].id = 1;
        MotionEvent.PointerCoords[] xy = {new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()};
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN | 256,
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP | 256, MotionEvent.ACTION_UP}) {
            boolean spread = action == MotionEvent.ACTION_MOVE;
            xy[0].x = x - (spread ? 100 : 60);
            xy[1].x = x + (spread ? 140 : 60);
            xy[0].y = xy[1].y = y;
            int n = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP ? 1 : 2;
            MotionEvent event = MotionEvent.obtain(now, now, action, n, ids, xy, 0, 0, 1, 1, 0, 0, 0, 0);
            view.dispatchTouchEvent(event);
            event.recycle();
        }
        check(field(view, "zoom", Float.class) > 1.9f, "Pinch zoom");
        check(field(view, "cx", Float.class) < 128, "Two-finger pan");
        tapImage(view, 135, 110);
        check(view.measurement.start == Measurement.pixel(135, 110), "Tap follows zoomed, panned image");
        view.resetViewport();
    }

    private void checkLandscape(MainActivity activity, ThermalView view) throws Exception {
        UiAutomation ui = getUiAutomation();
        try {
            checkRotation(activity, view, Surface.ROTATION_0);
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
        SystemClock.sleep(200);
        waitForIdleSync();
        // Draw once at the new size before using local view coordinates for the tap.
        Bitmap screen = getUiAutomation().takeScreenshot();
        if (screen != null) screen.recycle();
        runOnMainSync(() -> {
            check(activity.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                    "Camera stays landscape with system rotation " + rotation);
            check(activity.getRequestedOrientation() == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                    "Landscape locked");
            RectF bounds = field(view, "image", RectF.class);
            check(Math.abs(bounds.width() / bounds.height() - 4f / 3) < 0.01f, "Sensor aspect ratio");
            int nativeX = 91, nativeY = 42;
            float sx = (nativeX + 0.5f) / ThermalFrame.WIDTH;
            float sy = (nativeY + 0.5f) / ThermalFrame.HEIGHT;
            float x = bounds.left + bounds.width() * sx;
            float y = bounds.top + bounds.height() * sy;
            tapPreview(view, x / view.getWidth(), y / view.getHeight());
            check(view.measurement.start == nativeY * ThermalFrame.WIDTH + nativeX,
                    "Tap follows camera coordinates in landscape");
        });
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
        bytes.putShort(meta + 24, (short) ((bytes.getShort(2 * ThermalFrame.CENTER)
                + bytes.getShort(2 * (ThermalFrame.CENTER - 1))) >> 1));
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
