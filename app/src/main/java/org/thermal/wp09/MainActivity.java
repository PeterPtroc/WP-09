package org.thermal.wp09;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.DoubleConsumer;

import static org.thermal.wp09.InstrumentUi.*;

public final class MainActivity extends AppCompatActivity {
    private static final String USB_PERMISSION = "org.thermal.wp09.USB_PERMISSION";
    private static final int CAMERA_PERMISSION = 1;
    private UsbManager usb;
    private SharedPreferences settings;
    private CameraController camera;
    private ThermalMedia media;
    private UsbDevice selected;
    private boolean started;
    private boolean requested;
    private boolean streaming;
    private boolean capturingFullFrame;
    private boolean viewingSnapshot;
    private boolean measuring = true;
    private boolean paused, pullingVideoMaps;
    private long lastPanelAt, mapRateStartNs, snapshotReadMs;
    private int mapsReceived, pendingVideoBatches;
    private String mapRate = "";
    private final TemperatureAlarm alarms = new TemperatureAlarm();
    private ToneGenerator tone;
    private boolean laser;
    private boolean laserBusy, shutterBusy;
    private boolean recording;
    private boolean recordingStarting;
    private int recordingRequest;
    private int palette;
    private float emissivity;
    private ThermalFrame frame;
    private ThermalFrame previousStill;
    private ThermalFrame liveFrame;
    private UsbSample pendingSample;
    private final ActivityResultLauncher<String> sampleDestination =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"),
                    this::saveUsbSample);
    private ThermalFrame pendingTemperatureFrame;
    private Calibration pendingTemperatureCalibration;
    private final ActivityResultLauncher<String> temperatureDestination =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                    this::saveTemperatureCsv);
    private Calibration calibration;
    private float lowCalibrationReading = Float.NaN;
    private float lowCalibrationReference;
    private ThermalView thermal;
    private LinearLayout root;
    private InspectionPanel inspection;
    private TextView status, empty, fps, alarmBanner;
    private MaterialButton photoButton, videoButton, galleryButton, pauseButton, shutterButton, fullFrameButton;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = usbExtra(intent);
            switch (intent.getAction()) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                    if (selected == null) discover();
                    break;
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                    if (device != null && device.equals(selected)) {
                        disconnect();
                        status.setText("WP-09 已断开");
                        discover();
                    }
                    break;
                case USB_PERMISSION:
                    if (started && device != null && device.equals(selected)) {
                        requested = false;
                        if (usb.hasPermission(device)) connect();
                        else denied("USB 授权未通过，点按重试");
                    }
                    break;
            }
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        usb = getSystemService(UsbManager.class);
        settings = getSharedPreferences("wp09", MODE_PRIVATE);
        calibration = new Calibration(settings.getFloat("scale", 1), settings.getFloat("offset", 0),
                settings.getBoolean("calibrated", false));
        palette = settings.getInt("palette", 0);
        emissivity = settings.getFloat("emissivity", 0.98f);
        camera = new CameraController(usb);
        camera.setEmissivity(emissivity);
        media = new ThermalMedia(this, this::message);
        alarms.low = settings.getFloat("alarmLow", Float.NaN);
        alarms.high = settings.getFloat("alarmHigh", Float.NaN);
        buildUi();
    }

    private void buildUi() {
        root = row(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        landscape(this, root);
        FrameLayout preview = new FrameLayout(this);
        root.addView(preview, new LinearLayout.LayoutParams(0, -1, 1));
        thermal = new ThermalView(this);
        thermal.setPalette(palette);
        preview.addView(thermal, new FrameLayout.LayoutParams(-1, -1));
        empty = text(this, "等待 WP-09 接入", 15, MUTED);
        empty.setGravity(Gravity.CENTER);
        preview.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        alarmBanner = text(this, "", 14, BG);
        alarmBanner.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        alarmBanner.setPadding(dp(12), dp(4), dp(12), dp(4));
        FrameLayout.LayoutParams banner = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        banner.topMargin = dp(6);
        preview.addView(alarmBanner, banner);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PANEL);
        root.addView(scroll, new LinearLayout.LayoutParams(dp(226), -1));
        LinearLayout panel = new LinearLayout(this);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(5), dp(8), dp(5));
        scroll.addView(panel);
        LinearLayout header = row(this);
        header.setPadding(dp(6), 0, dp(6), 0);
        panel.addView(header);
        TextView title = text(this, "WP-09", 18, TEXT);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        fps = text(this, "", 12, ACCENT);
        header.addView(fps);
        status = text(this, "等待 USB", 12, MUTED);
        status.setPadding(dp(6), 0, 0, dp(5));
        panel.addView(status);
        status.setOnClickListener(v -> { if (!streaming && !requested) { selected = null; discover(); } });
        View.OnLongClickListener diagnostic = v -> {
            if (frame != null && frame.hasPixelTemperatures() && (viewingSnapshot || paused)) {
                pendingTemperatureFrame = frame;
                pendingTemperatureCalibration = calibration;
                temperatureDestination.launch(frame.thermalOutput ? "WP09-全图温度-摄氏.csv" : "WP09-原始估温-摄氏.csv");
            } else showUsbDiagnostics();
            return true;
        };
        status.setOnLongClickListener(diagnostic);
        header.setOnLongClickListener(diagnostic);

        LinearLayout captureRow = row(this);
        panel.addView(captureRow);
        photoButton = action(captureRow, "拍照", R.drawable.ic_camera, this::takePhoto);
        videoButton = action(captureRow, "录像", R.drawable.ic_video, this::toggleVideo);
        galleryButton = action(captureRow, "相册", R.drawable.ic_gallery,
                () -> startActivity(new Intent(this, GalleryActivity.class)));

        inspection = new InspectionPanel(this, thermal);
        inspection.setPadding(0, dp(6), 0, dp(4));
        panel.addView(inspection);
        LinearLayout rangeRow = row(this);
        panel.addView(rangeRow);
        pauseButton = action(rangeRow, "定格", R.drawable.ic_pause, () -> {
            if (viewingSnapshot || paused) resumePreview();
            else if (thermal.drawnFrame() != null) {
                frame = thermal.drawnFrame();
                paused = true;
                inspection.reset();
                render();
            }
        });
        shutterButton = action(rangeRow, "快门校准", R.drawable.ic_crosshair, this::triggerShutter);
        LinearLayout options = row(this);
        panel.addView(options);
        fullFrameButton = action(options, "全图", R.drawable.ic_grid, this::toggleFullFrame);
        action(options, "设置", R.drawable.ic_sliders_horizontal, this::settingsMenu);
        render();
    }

    private void settingsMenu() {
        String[] entries = {"色彩　" + ThermalCapture.PALETTE_NAMES[palette], "高低温提醒",
                "发射率　" + emissivity, Float.isFinite(lowCalibrationReading) ? "校准高点" : "参考温度校准",
                laser ? "关闭激光" : "开启激光", measuring ? "隐藏测量标记" : "显示测量标记",
                "复位画面"};
        dialog().setTitle("设置").setItems(entries, (d, i) -> {
            switch (i) {
                case 0: choosePalette(); break;
                case 1: chooseAlarm(); break;
                case 2: chooseEmissivity(); break;
                case 3: if (frame != null && frame.hasTemperature()) chooseCalibration();
                        else message("等待测温数据"); break;
                case 4: if (streaming) toggleLaser(); else message("请连接 WP-09"); break;
                case 5: measuring = !measuring; render(); break;
                case 6: thermal.resetViewport(); break;
            }
        }).show();
    }

    private void triggerShutter() {
        if (!streaming || shutterBusy || !CameraController.supportsFullFrame(selected)) return;
        shutterBusy = true;
        render();
        camera.triggerShutter(ok -> {
            shutterBusy = false;
            message(ok ? "已触发快门校准" : "快门校准指令未确认");
            render();
        });
    }

    private void chooseAlarm() {
        TemperatureControls.show(this, alarms.low, alarms.high, (low, high) -> {
            alarms.low = low;
            alarms.high = high;
            alarms.state = 0;
            settings.edit().putFloat("alarmLow", low).putFloat("alarmHigh", high).apply();
            checkAlarm(liveFrame);
        });
    }

    private void checkAlarm(ThermalFrame current) {
        int before = alarms.state;
        int entered = alarms.update(current, calibration);
        if (before == alarms.state && alarmBanner.getVisibility() == (before == 0 ? View.GONE : View.VISIBLE)) return;
        alarmBanner.setVisibility(alarms.state == 0 ? View.GONE : View.VISIBLE);
        if (alarms.state == 0) return;
        boolean high = (alarms.state & TemperatureAlarm.HIGH) != 0;
        alarmBanner.setBackgroundColor(high ? 0xffeeeeee : 0xffbdbdbd);
        alarmBanner.setText(alarms.state == 3 ? "实时 · 高温 / 低温" : high ? "实时 · 高温" : "实时 · 低温");
        if (entered != 0) {
            try {
                if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180);
            } catch (RuntimeException ignored) { /* Visual alert remains available. */ }
        }
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        IntentFilter deviceActions = new IntentFilter();
        deviceActions.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        deviceActions.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        IntentFilter permissionAction = new IntentFilter(USB_PERMISSION);
        ContextCompat.registerReceiver(this, receiver, deviceActions, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, receiver, permissionAction, ContextCompat.RECEIVER_NOT_EXPORTED);
        discover();
    }

    @Override protected void onStop() {
        started = false;
        unregisterReceiver(receiver);
        disconnect();
        super.onStop();
    }

    @Override protected void onDestroy() {
        camera.destroy();
        media.destroy();
        if (tone != null) tone.release();
        super.onDestroy();
    }

    private void discover() {
        if (!started || selected != null || usb == null) return;
        for (UsbDevice device : usb.getDeviceList().values()) {
            String product = device.getProductName();
            if (product != null && product.toUpperCase(Locale.ROOT).replace("-", "").contains("WP09")) {
                selected = device;
                requestUsb();
                return;
            }
        }
        empty.setText("等待 WP-09 接入");
    }

    private void requestUsb() {
        if (selected == null || requested) return;
        if (usb.hasPermission(selected)) {
            connect();
        } else if (Build.VERSION.SDK_INT >= 28
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requested = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        } else {
            requested = true;
            status.setText("等待 USB 授权");
            Intent request = new Intent(USB_PERMISSION).setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            usb.requestPermission(selected, PendingIntent.getBroadcast(this, 0, request, flags));
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                      int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION) return;
        requested = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestUsb();
        else denied("需要相机权限才能访问 USB 热像仪");
    }

    private void denied(String reason) {
        selected = null;
        status.setText("等待授权");
        empty.setText(reason);
    }

    private void connect() {
        status.setText("正在连接");
        empty.setText("正在读取全图测温");
        camera.start(selected, this::receive, message -> {
            disconnect();
            status.setText("连接失败");
            empty.setText(message);
        });
    }

    void receive(ThermalFrame next) {
        boolean first = !streaming;
        streaming = true;
        liveFrame = next;
        if (!(recording && pullingVideoMaps)) checkAlarm(next);
        if (!viewingSnapshot && !paused) {
            frame = next;
            thermal.show(frame, calibration, measuring);
            long now = SystemClock.elapsedRealtime();
            if (first || now - lastPanelAt >= 120) render();
        }
        if (recording && !pullingVideoMaps && next.hasPixelTemperatures()) recordFrame(thermal.capture());
    }

    private void disconnect() {
        camera.stop();
        stopVideo();
        status.setText("等待 USB");
        selected = null;
        frame = null;
        liveFrame = null;
        previousStill = null;
        paused = false;
        thermal.resetViewport();
        inspection.reset();
        checkAlarm(null);
        capturingFullFrame = false;
        viewingSnapshot = false;
        requested = false;
        streaming = false;
        laser = false;
        laserBusy = shutterBusy = false;
        lowCalibrationReading = Float.NaN;
        render();
    }

    private void resumePreview() {
        previousStill = null;
        paused = viewingSnapshot = false;
        frame = liveFrame;
        inspection.reset();
        render();
    }

    private void toggleFullFrame() {
        if (viewingSnapshot) {
            if (previousStill == null) resumePreview();
            else {
                frame = previousStill;
                previousStill = null;
                paused = true;
                viewingSnapshot = false;
                inspection.reset();
                render();
            }
            return;
        }
        ThermalFrame visible = paused ? frame : thermal.drawnFrame();
        if (visible != null && visible.hasPixelTemperatures()) {
            frame = visible;
            snapshotReadMs = 0;
            paused = false;
            viewingSnapshot = true;
            render();
        } else captureMap(false);
    }

    private void takePhoto() {
        if (frame == null || capturingFullFrame) return;
        if (!paused && !frame.hasPixelTemperatures() && CameraController.supportsFullFrame(selected)) {
            captureMap(true);
        } else media.photo(thermal.capture());
    }

    private void captureMap(boolean save) {
        if (capturingFullFrame || !streaming || !CameraController.supportsFullFrame(selected)) return;
        capturingFullFrame = true;
        Calibration correction = calibration;
        long startedAt = SystemClock.elapsedRealtime();
        render();
        camera.captureFullFrame(snapshot -> {
            capturingFullFrame = false;
            if (snapshot == null) message("未取得全图，请重试");
            else if (save) media.photo(thermal.compose(snapshot, correction));
            else {
                previousStill = paused ? frame : null;
                frame = snapshot;
                snapshotReadMs = SystemClock.elapsedRealtime() - startedAt;
                paused = false;
                viewingSnapshot = true;
                measuring = true;
                inspection.reset();
            }
            render();
        });
    }

    private void saveTemperatureCsv(Uri uri) {
        ThermalFrame snapshot = pendingTemperatureFrame;
        Calibration correction = pendingTemperatureCalibration;
        pendingTemperatureFrame = null;
        pendingTemperatureCalibration = null;
        if (uri == null || snapshot == null || correction == null) return;
        new Thread(() -> {
            String result;
            try {
                OutputStream stream = getContentResolver().openOutputStream(uri, "w");
                if (stream == null) throw new IOException("Temperature CSV destination is unavailable");
                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                    for (int y = 0; y < ThermalFrame.HEIGHT; y++) {
                        for (int x = 0; x < ThermalFrame.WIDTH; x++) {
                            if (x != 0) writer.write(',');
                            float value = correction.apply(snapshot.temperatureAt(y * ThermalFrame.WIDTH + x));
                            writer.write(Float.isFinite(value) ? Float.toString(Math.round(value * 10f) / 10f) : "NaN");
                        }
                        writer.newLine();
                    }
                }
                result = "全图温度已导出";
            } catch (IOException | SecurityException error) {
                result = "全图温度导出失败";
            }
            String notice = result;
            runOnUiThread(() -> message(notice));
        }, "WP09 temperature export").start();
    }

    private void showUsbDiagnostics() {
        camera.collectSample(sample -> {
            if (!started || isFinishing()) return;
            if (usb != null) sample.setAttachedDevices(usb.getDeviceList().values());
            dialog().setTitle("USB 诊断")
                    .setMessage(sample.hasFrame() ? "USB 状态与一帧热像原始数据"
                            : "USB 状态 · 暂无原始帧")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("导出 ZIP", (dialog, which) -> {
                        pendingSample = sample;
                        sampleDestination.launch("WP09-USB.zip");
                    }).show();
        });
    }

    private void saveUsbSample(Uri uri) {
        UsbSample sample = pendingSample;
        pendingSample = null;
        if (uri == null || sample == null) return;
        new Thread(() -> {
            String result;
            try {
                OutputStream stream = getContentResolver().openOutputStream(uri, "w");
                if (stream == null) throw new IOException("Diagnostic destination is unavailable");
                sample.write(stream);
                result = "USB 诊断已保存";
            } catch (IOException | SecurityException error) {
                result = "USB 诊断保存失败";
            }
            String notice = result;
            runOnUiThread(() -> message(notice));
        }, "WP09 USB export").start();
    }

    private void toggleLaser() {
        if (!streaming || laserBusy) return;
        laserBusy = true;
        render();
        boolean enabled = !laser;
        camera.setLaser(enabled, ok -> {
            laserBusy = false;
            if (ok) laser = enabled;
            else message("激光指令未确认");
            render();
        });
    }

    private void toggleVideo() {
        if (recording) { stopVideo(); render(); return; }
        if (recordingStarting || !streaming || paused || viewingSnapshot || capturingFullFrame || frame == null) return;
        pullingVideoMaps = CameraController.supportsFullFrame(selected);
        if (!pullingVideoMaps && !frame.hasPixelTemperatures()) return;
        mapRateStartNs = System.nanoTime();
        mapsReceived = 0;
        pendingVideoBatches = 0;
        mapRate = "";
        recordingStarting = true;
        int request = ++recordingRequest;
        media.start(ok -> {
            if (request != recordingRequest) return;
            recordingStarting = false;
            recording = ok;
            if (ok) {
                if (pullingVideoMaps) nextVideoMap(request);
                else if (liveFrame != null && liveFrame.hasPixelTemperatures())
                    recordFrame(thermal.compose(liveFrame, calibration));
            }
            render();
        });
        render();
    }

    private void nextVideoMap(int request) {
        if (request != recordingRequest || !recording || capturingFullFrame || pendingVideoBatches >= 2) return;
        capturingFullFrame = true;
        Calibration correction = calibration;
        camera.captureMaps(true, frames -> {
            capturingFullFrame = false;
            if (request != recordingRequest || !recording) { render(); return; }
            if (frames.length == 0) {
                stopVideo();
                message("全图取样中断");
                render();
                return;
            }
            ThermalCapture[] captures = new ThermalCapture[frames.length];
            for (int i = 0; i < frames.length; i++) {
                captures[i] = thermal.compose(frames[i], correction);
                checkAlarm(frames[i]);
            }
            mapsReceived += frames.length;
            long now = System.nanoTime();
            if (now - mapRateStartNs >= 1_000_000_000L) {
                mapRate = ThermalView.number(mapsReceived * 1_000_000_000f / (now - mapRateStartNs)) + " 测温帧/秒";
                mapsReceived = 0;
                mapRateStartNs = now;
                render();
            }
            pendingVideoBatches++;
            media.burst(captures, ok -> {
                if (request != recordingRequest || !recording) return;
                pendingVideoBatches--;
                if (!ok) { stopVideo(); render(); return; }
                nextVideoMap(request);
            });
            // Overlap the next USB read with encoding. A slow encoder can hold two batches.
            nextVideoMap(request);
        });
    }

    private void recordFrame(ThermalCapture capture) {
        int request = recordingRequest;
        media.frame(capture, () -> {
            if (request == recordingRequest) { stopVideo(); render(); }
        });
    }

    private void stopVideo() {
        recordingRequest++;
        if (pullingVideoMaps) camera.endMapVideo();
        if (recording || recordingStarting) media.stop();
        if (recording && pullingVideoMaps) frame = liveFrame;
        recording = recordingStarting = pullingVideoMaps = false;
    }

    private void choosePalette() {
        ArrayAdapter<String> options = new ArrayAdapter<String>(this,
                android.R.layout.select_dialog_singlechoice, ThermalCapture.PALETTE_NAMES) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView label = (TextView) super.getView(position, recycled, parent);
                GradientDrawable swatch = ThermalCapture.swatch(position);
                swatch.setBounds(0, 0, dp(46), dp(14));
                label.setCompoundDrawablesRelative(swatch, null, null, null);
                label.setCompoundDrawablePadding(dp(14));
                return label;
            }
        };
        dialog().setTitle("色彩模式")
                .setSingleChoiceItems(options, palette, (dialog, which) -> {
                    palette = which;
                    thermal.setPalette(which);
                    settings.edit().putInt("palette", which).apply();
                    render();
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseEmissivity() {
        editNumber("发射率", "0.10 至 1.00", emissivity, entered -> {
            if (entered < 0.1 || entered > 1.0) {
                throw new IllegalArgumentException("发射率须在 0.10 至 1.00 之间");
            }
            float value = (float) entered;
            if (value == emissivity) return;
            emissivity = value;
            if (viewingSnapshot || paused) {
                previousStill = null;
                viewingSnapshot = paused = false;
                frame = liveFrame;
            }
            camera.setEmissivity(value);
            settings.edit().putFloat("emissivity", value).apply();
            if (calibration.applied) message("发射率已修改，原校准已重置");
            lowCalibrationReading = Float.NaN;
            saveCalibration(Calibration.DEFAULT);
        });
    }

    private void chooseCalibration() {
        if (Float.isFinite(lowCalibrationReading)) {
            captureCalibration(true, true);
            return;
        }
        dialog().setTitle("参考温度校准")
                .setItems(new String[]{"单点校准", "两点校准", "清除校准"}, (dialog, choice) -> {
                    if (choice == 2) saveCalibration(Calibration.DEFAULT);
                    else captureCalibration(choice == 1, false);
                }).setNegativeButton("取消", null).show();
    }

    private void captureCalibration(boolean twoPoint, boolean highPoint) {
        float reading = thermal.calibrationReading();
        if (!Float.isFinite(reading)) {
            message("所选位置暂无有效温度");
            return;
        }
        String label = !twoPoint ? "参考温度" : highPoint ? "高温参考点" : "低温参考点";
        String location = frame.hasPixelTemperatures() && frame.firmware == null ? "所选区域读数 " : "画面中心读数 ";
        editNumber(label, location + ThermalView.number(reading) + " °C",
                reading, entered -> {
                    float reference = (float) entered;
                    if (!twoPoint) saveCalibration(Calibration.onePoint(reading, reference));
                    else if (highPoint) {
                        saveCalibration(Calibration.twoPoint(lowCalibrationReading,
                                lowCalibrationReference, reading, reference));
                        lowCalibrationReading = Float.NaN;
                        render();
                    } else {
                        Calibration.onePoint(reading, reference);
                        lowCalibrationReading = reading;
                        lowCalibrationReference = reference;
                        render();
                    }
                });
    }

    private void editNumber(String title, String description, float initial, DoubleConsumer apply) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        field.setText(String.format(Locale.ROOT, "%.2f", initial));
        field.selectAll();
        FrameLayout container = new FrameLayout(this);
        container.setPadding(dp(22), 0, dp(22), 0);
        container.addView(field, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog dialog = dialog().setTitle(title).setMessage(description)
                .setView(container).setNegativeButton("取消", null).setPositiveButton("应用", null)
                .create();
        if (Float.isFinite(lowCalibrationReading)) dialog.setButton(AlertDialog.BUTTON_NEUTRAL,
                "放弃两点校准", (ignored, which) -> {
                    lowCalibrationReading = Float.NaN;
                    render();
                });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    try {
                        double value = Double.parseDouble(field.getText().toString().trim());
                        if (!Double.isFinite(value)) throw new NumberFormatException();
                        apply.accept(value);
                        dialog.dismiss();
                    } catch (NumberFormatException e) {
                        field.setError("请输入数值");
                    } catch (IllegalArgumentException e) {
                        field.setError(e.getMessage());
                    }
                }));
        dialog.show();
    }

    private MaterialAlertDialogBuilder dialog() {
        return new MaterialAlertDialogBuilder(this).setBackground(new ColorDrawable(PANEL));
    }

    private void saveCalibration(Calibration value) {
        calibration = value;
        inspection.reset();
        checkAlarm(liveFrame);
        settings.edit().putFloat("scale", value.scale).putFloat("offset", value.offset)
                .putBoolean("calibrated", value.applied).apply();
        render();
    }

    private void render() {
        thermal.show(frame, calibration, measuring);
        lastPanelAt = SystemClock.elapsedRealtime();
        empty.setVisibility(frame == null ? View.VISIBLE : View.GONE);
        inspection.setVisibility(measuring ? View.VISIBLE : View.GONE);
        if (measuring) inspection.refresh(frame == null ? 0 : frame.capturedNanos / 1_000_000, true);
        boolean valid = frame != null && frame.hasTemperature();
        if (streaming) {
            String state;
            if (recording) state = mapRate.isEmpty() ? "正在录像" : "录像 · " + mapRate;
            else if (capturingFullFrame) state = "正在读取全图…";
            else if (viewingSnapshot) state = thermal.capture().source()
                    + (snapshotReadMs > 0 ? " · " + snapshotReadMs + " ms" : "");
            else if (paused) state = "已定格 · " + thermal.capture().source();
            else state = thermal.capture() == null ? "读取画面" : thermal.capture().source();
            label(status, state);
        }
        label(fps, streaming && !viewingSnapshot && !paused && thermal.fps() > 0
                ? thermal.fps() + " fps" : "");
        boolean mapAvailable = CameraController.supportsFullFrame(selected);
        photoButton.setEnabled(frame != null && !capturingFullFrame && !recordingStarting
                && !(recording && mapAvailable && !frame.hasPixelTemperatures()));
        videoButton.setEnabled(recording || (streaming && !paused && !viewingSnapshot && !capturingFullFrame
                && !recordingStarting && (mapAvailable || (frame != null && frame.hasPixelTemperatures()))));
        if (videoButton.isSelected() != recording) {
            videoButton.setSelected(recording);
            videoButton.setIconResource(recording ? R.drawable.ic_square : R.drawable.ic_video);
        }
        label(videoButton, recording ? "停止" : "录像");
        galleryButton.setEnabled(!recording && !recordingStarting && !capturingFullFrame);
        pauseButton.setEnabled(frame != null && !recording && !recordingStarting && !capturingFullFrame);
        pauseButton.setSelected(paused || viewingSnapshot);
        label(pauseButton, paused || viewingSnapshot ? "实时" : "定格");
        shutterButton.setEnabled(streaming && mapAvailable && !shutterBusy);
        fullFrameButton.setEnabled(streaming && !capturingFullFrame && !recording && !recordingStarting
                && (mapAvailable || (valid && frame.hasPixelTemperatures())));
        fullFrameButton.setSelected(viewingSnapshot);
        label(fullFrameButton, viewingSnapshot ? (previousStill == null ? "返回实时" : "返回定格")
                : paused && mapAvailable && !frame.hasPixelTemperatures() ? "新拍全图" : "全图");

        if (alarms.state == 0 && alarmBanner.getVisibility() != View.GONE) alarmBanner.setVisibility(View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void message(String message) {
        if (started) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static UsbDevice usbExtra(Intent intent) {
        return Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }
}
