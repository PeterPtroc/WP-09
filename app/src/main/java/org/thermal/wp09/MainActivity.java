package org.thermal.wp09;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.DoubleConsumer;

public final class MainActivity extends AppCompatActivity {
    private static final String USB_PERMISSION = "org.thermal.wp09.USB_PERMISSION";
    private static final int CAMERA_PERMISSION = 1;
    private static final int FUSION_OPACITY = 55;
    private static final int FUSION_ZOOM = 100;
    private static final int MIN_ZOOM = 20;
    private static final int MAX_ZOOM = 1200;
    private static final int MAX_OFFSET = 400;
    private static final long ALIGNMENT_HOLD_NANOS = 550_000_000L;
    private static final int BG = 0xff151515;
    private static final int PANEL = 0xff222222;
    private static final int TEXT = 0xffeeeeee;
    private static final int MUTED = 0xffbbbbbb;

    private UsbManager usb;
    private SharedPreferences settings;
    private CameraController camera;
    private VisibleCamera visibleCamera;
    private ThermalMedia media;
    private UsbDevice selected;
    private boolean started;
    private boolean requested;
    private boolean streaming;
    private boolean measuring = true;
    private boolean laser;
    private boolean laserBusy;
    private boolean recording;
    private boolean recordingStarting;
    private boolean fusionEnabled;
    private boolean fusionReady;
    private boolean manualFusion;
    private int fusionRotation;
    private final ExecutorService registrationWorker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable alignmentTimeout = () -> finishAutomaticCalibration(null, "标定超时。请固定手机，照亮背景后重试");
    private FusionRegistration.Calibration fusionCalibration;
    private FusionRegistration.Transform savedAlignment, pendingAlignment, trackedAlignment;
    private FusionRegistration.Image visibleSample;
    private String alignmentKey, visibleCameraId;
    private int cameraWidth, cameraHeight, cameraOrientation;
    private volatile int registrationGeneration;
    private boolean learnedProfile, learning, waitingForPair;
    private final Runnable pairTimeout = () -> {
        if (waitingForPair) endLearnedAlignment(null, "暂未收到同步图对，请保持手机稳定后重试");
    };
    private boolean registrationBusy;
    private long visibleSampleAt, lastRegistrationAt, lastUsedVisibleAt, trackingUntil;
    private int recordingRequest;
    private int palette;
    private float emissivity;
    private long pluggedAt;
    private ThermalFrame frame;
    private UsbSample pendingSample;
    private final ActivityResultLauncher<String> sampleDestination =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"),
                    this::saveUsbSample);
    private final ActivityResultLauncher<String> fusionPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && started && streaming) startFusion();
                else if (!granted) message("请授予相机权限后开启热融合");
            });
    private Calibration calibration;
    private float lowCalibrationReading = Float.NaN;
    private float lowCalibrationReference;
    private ThermalView thermal;
    private LinearLayout root, header, metrics, body, toolsPanel;
    private LinearLayout capture, settingsRow, fusionRow, alignment;
    private SeekBar fusionOpacity, fusionZoom, fusionX, fusionY;
    private FrameLayout preview;
    private TextView status, empty, hot, cold, average, spot;
    private TextView fusionNotice;
    private MaterialButton laserButton, measurementButton, calibrationButton, paletteButton;
    private MaterialButton emissivityButton, photoButton, videoButton;
    private MaterialButton fusionButton, alignmentButton, angleButton, pairButton;

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
        visibleCamera = new VisibleCamera(this);
        manualFusion = settings.getBoolean("fusion_manual", settings.contains("fusion_zoom"));
        media = new ThermalMedia(this, this::message);
        buildUi();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        }
        setContentView(root);

        header = horizontal();
        header.setPadding(dp(10), 0, dp(10), 0);
        TextView title = text("WP-09 热成像", 17, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        status = text("等待 USB", 12, MUTED);
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        status.setMaxWidth(dp(105));
        status.setOnClickListener(view -> {
            if (!streaming && !requested) {
                selected = null;
                discover();
            }
        });
        status.setOnLongClickListener(view -> {
            showUsbDiagnostics();
            return true;
        });
        header.addView(status);
        root.addView(header);

        metrics = horizontal();
        metrics.setBackgroundColor(PANEL);
        metrics.setPadding(dp(8), 0, dp(8), 0);
        hot = metric(metrics, "最高", TEXT);
        cold = metric(metrics, "最低", TEXT);
        average = metric(metrics, "平均", TEXT);
        spot = metric(metrics, "选点", TEXT);
        root.addView(metrics);

        body = new LinearLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        preview = new FrameLayout(this);
        thermal = new ThermalView(this);
        thermal.setPalette(palette);
        thermal.onSpotChanged(this::render);
        preview.addView(thermal, new FrameLayout.LayoutParams(-1, -1));
        empty = text("等待 WP-09 接入", 14, MUTED);
        empty.setBackgroundColor(0xe3222222);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(14), dp(8), dp(14), dp(8));
        FrameLayout.LayoutParams emptyLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        preview.addView(empty, emptyLayout);
        body.addView(preview);
        toolsPanel = new LinearLayout(this);
        toolsPanel.setOrientation(LinearLayout.VERTICAL);
        toolsPanel.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(toolsPanel);

        capture = horizontal();
        capture.setGravity(Gravity.CENTER);
        capture.setPadding(dp(5), 0, dp(5), 0);
        toolsPanel.addView(capture);
        laserButton = control(capture, "激光", R.drawable.ic_zap, this::toggleLaser);
        photoButton = control(capture, "拍照", R.drawable.ic_camera, () -> media.photo(thermal));
        videoButton = control(capture, "录像", R.drawable.ic_video, this::toggleVideo);
        measurementButton = control(capture, "测温", R.drawable.ic_thermometer, () -> {
            measuring = !measuring;
            render();
        });

        settingsRow = horizontal();
        settingsRow.setPadding(dp(5), 0, dp(5), dp(2));
        toolsPanel.addView(settingsRow);
        paletteButton = control(settingsRow, "", R.drawable.ic_palette, this::choosePalette);
        emissivityButton = control(settingsRow, "", R.drawable.ic_sliders_horizontal, this::chooseEmissivity);
        calibrationButton = control(settingsRow, "校准", R.drawable.ic_crosshair, this::chooseCalibration);
        buildFusionControls();
        updateLayout(getResources().getConfiguration());
        render();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateLayout(configuration);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && root != null) updateSystemBars(getResources().getConfiguration());
    }

    private void updateLayout(Configuration configuration) {
        boolean wide = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                && configuration.screenWidthDp >= 520;
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        preview.setLayoutParams(wide ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, 0, 1));
        toolsPanel.setLayoutParams(wide ? new LinearLayout.LayoutParams(dp(228), -1)
                : new LinearLayout.LayoutParams(-1, -2));
        LinearLayout panel = wide ? toolsPanel : root;
        if (header.getParent() != panel) {
            LinearLayout previous = wide ? root : toolsPanel;
            previous.removeView(header);
            previous.removeView(metrics);
            panel.addView(header, 0);
            panel.addView(metrics, 1);
        }
        updateSystemBars(configuration);
    }

    private void updateSystemBars(Configuration configuration) {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), root);
        int bars = WindowInsetsCompat.Type.systemBars();
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(bars);
        } else {
            controller.show(bars);
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
        visibleCamera.destroy();
        registrationGeneration++;
        mainHandler.removeCallbacks(alignmentTimeout);
        mainHandler.removeCallbacks(pairTimeout);
        registrationWorker.shutdown();
        media.destroy();
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
        empty.setText("正在读取 WP-09");
        pluggedAt = SystemClock.elapsedRealtime();
        camera.start(selected, (next, receivedAt) -> {
            streaming = true;
            frame = next;
            thermal.setThermalTime(receivedAt);
            render();
            registerFrames(next, receivedAt);
            if (recording) media.frame(thermal, () -> {
                recording = false;
                recordingStarting = false;
                render();
            });
        }, message -> {
            disconnect();
            status.setText("连接失败");
            empty.setText(message);
        });
    }

    private void disconnect() {
        camera.stop();
        stopFusion();
        stopVideo();
        selected = null;
        frame = null;
        requested = false;
        streaming = false;
        laser = false;
        laserBusy = false;
        lowCalibrationReading = Float.NaN;
        render();
    }

    private void buildFusionControls() {
        alignment = new LinearLayout(this);
        alignment.setOrientation(LinearLayout.VERTICAL);
        alignment.setPadding(dp(5), 0, dp(5), 0);
        alignment.setVisibility(View.GONE);
        toolsPanel.addView(alignment);

        LinearLayout first = horizontal();
        alignment.addView(first);
        fusionOpacity = fusionSlider(first, "热像占比", "fusion_opacity", 0, 100, FUSION_OPACITY);
        fusionZoom = fusionSlider(first, "相机缩放", "fusion_zoom", MIN_ZOOM, MAX_ZOOM, FUSION_ZOOM);
        LinearLayout second = horizontal();
        alignment.addView(second);
        fusionX = fusionSlider(second, "水平偏移", "fusion_x", -MAX_OFFSET, MAX_OFFSET, 0);
        fusionY = fusionSlider(second, "垂直偏移", "fusion_y", -MAX_OFFSET, MAX_OFFSET, 0);

        fusionRotation = settings.getInt("fusion_rotation", 0);
        LinearLayout actions = horizontal();
        alignment.addView(actions);
        angleButton = control(actions, "", 0, () ->
                editNumber("旋转角度", "输入角度，负数为逆时针", fusionRotation, number -> {
                    fusionRotation = Math.floorMod((int) Math.round(number % 360), 360);
                    applyFusionAlignment();
                }));
        control(actions, "旋转 90°", 0, () -> {
            fusionRotation = (fusionRotation + 90) % 360;
            applyFusionAlignment();
        });
        control(actions, "重置", 0, () -> {
            fusionOpacity.setProgress(FUSION_OPACITY);
            fusionZoom.setProgress(FUSION_ZOOM - MIN_ZOOM);
            fusionX.setProgress(MAX_OFFSET);
            fusionY.setProgress(MAX_OFFSET);
            fusionRotation = 0;
            applyFusionAlignment();
        });
        applyFusionAlignment();

        fusionNotice = text("", 12, MUTED);
        fusionNotice.setGravity(Gravity.CENTER);
        fusionNotice.setPadding(dp(9), dp(4), dp(9), dp(4));
        fusionNotice.setMinHeight(dp(28));
        fusionNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        toolsPanel.addView(fusionNotice);

        fusionRow = horizontal();
        fusionRow.setPadding(dp(5), 0, dp(5), dp(2));
        toolsPanel.addView(fusionRow);
        fusionButton = control(fusionRow, "热融合", R.drawable.ic_camera, this::toggleFusion);
        alignmentButton = control(fusionRow, "自动对齐", R.drawable.ic_crosshair, this::chooseFusionAlignment);
        pairButton = control(fusionRow, "存图对", 0, () -> media.savePair(thermal));
    }

    private SeekBar fusionSlider(LinearLayout row, String name, String key, int min, int max, int initial) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(4), 0, dp(4), 0);
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
        TextView label = text(name, 11, TEXT);
        label.setGravity(Gravity.CENTER);
        column.addView(label);

        SeekBar slider = new SeekBar(this);
        slider.setContentDescription(name);
        slider.setMax(max - min);
        slider.setProgress(settings.getInt(key, initial) - min);
        label.setText(name + " " + (slider.getProgress() + min) + "%");
        label.setOnClickListener(ignored -> editNumber(name, min + "% 至 " + max + "%",
                slider.getProgress() + min, number -> {
                    if (number < min || number > max) throw new IllegalArgumentException("数值超出范围");
                    slider.setProgress((int) Math.round(number) - min);
                    applyFusionAlignment();
                }));
        column.addView(slider, new LinearLayout.LayoutParams(-1, dp(44)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                label.setText(name + " " + (progress + min) + "%");
                if (fromUser) applyFusionAlignment();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        return slider;
    }

    private void applyFusionAlignment() {
        thermal.setFusion(fusionOpacity.getProgress(), fusionZoom.getProgress() + MIN_ZOOM,
                fusionX.getProgress() - MAX_OFFSET, fusionY.getProgress() - MAX_OFFSET, fusionRotation);
        setText(angleButton, "角度 " + fusionRotation + "°");
    }

    private void showFusionAlignment(boolean show) {
        if (!show && alignment.getVisibility() == View.VISIBLE) {
            settings.edit().putInt("fusion_opacity", fusionOpacity.getProgress())
                    .putInt("fusion_zoom", fusionZoom.getProgress() + MIN_ZOOM)
                    .putInt("fusion_x", fusionX.getProgress() - MAX_OFFSET)
                    .putInt("fusion_y", fusionY.getProgress() - MAX_OFFSET)
                    .putInt("fusion_rotation", fusionRotation).apply();
        }
        capture.setVisibility(show ? View.GONE : View.VISIBLE);
        settingsRow.setVisibility(show ? View.GONE : View.VISIBLE);
        alignment.setVisibility(show ? View.VISIBLE : View.GONE);
        updateFusionButtons();
    }

    private void toggleFusion() {
        if (fusionEnabled) {
            stopFusion();
        } else if (streaming && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startFusion();
        } else if (streaming) {
            fusionPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startFusion() {
        fusionEnabled = true;
        thermal.setAutomaticFusion(!manualFusion, null, 0);
        visibleCamera.start(this::receiveVisible, reason -> {
            stopFusion();
            message(reason);
        });
        updateFusionButtons();
    }

    private void stopFusion() {
        if (!fusionEnabled) return;
        fusionEnabled = false;
        fusionReady = false;
        invalidateRegistration();
        fusionCalibration = null;
        savedAlignment = null;
        visibleSample = null;
        visibleSampleAt = 0;
        visibleCameraId = null;
        alignmentKey = null;
        visibleCamera.stop();
        thermal.clearVisible();
        showFusionAlignment(false);
    }

    private void updateFusionButtons() {
        boolean aligning = alignment.getVisibility() == View.VISIBLE;
        fusionRow.setVisibility(streaming ? View.VISIBLE : View.GONE);
        button(fusionButton, fusionEnabled && !fusionReady ? "开启中" : "热融合", streaming, fusionEnabled);
        String alignmentLabel = aligning ? "完成对齐" : learning ? "取消匹配" : fusionCalibration != null ? "取消标定" : "自动对齐";
        button(alignmentButton, alignmentLabel, fusionReady, aligning || learning || fusionCalibration != null);
        pairButton.setVisibility(fusionReady ? View.VISIBLE : View.GONE);
        if (learning) {
            fusionNotice.setVisibility(View.VISIBLE);
            setText(fusionNotice, waitingForPair ? "正在采集两路画面" : "正在用模型匹配两路画面…");
            return;
        }
        if (fusionCalibration != null) return;
        boolean showNotice = fusionReady && !manualFusion;
        fusionNotice.setVisibility(showNotice ? View.VISIBLE : View.GONE);
        if (showNotice) {
            String notice = "已自动对齐";
            if (savedAlignment == null) notice = "首次使用请点自动对齐";
            else if (learnedProfile) notice = "已应用 AI 标定";
            else if (trackedAlignment == null) notice = "已标定 · 暂用纯热像";
            setText(fusionNotice, notice);
        }
    }

    private void chooseFusionAlignment() {
        if (alignment.getVisibility() == View.VISIBLE) {
            showFusionAlignment(false);
        } else if (learning) {
            endLearnedAlignment(null, "已取消匹配");
        } else if (fusionCalibration != null) {
            finishAutomaticCalibration(null, null);
        } else {
            dialog().setTitle("自动对齐")
                    .setItems(new String[]{"AI 对齐当前画面", "用手掌自动对齐", "手动设置"}, (dialog, which) -> {
                        if (!fusionReady) return;
                        if (which == 0) {
                            invalidateRegistration();
                            learning = waitingForPair = true;
                            updateFusionButtons();
                            mainHandler.postDelayed(pairTimeout, 2500);
                            startLearnedAlignmentIfPaired();
                        } else if (which == 1) {
                            dialog().setTitle("用手掌自动对齐")
                                    .setMessage("把手机靠稳，对准空墙。圆圈出现后，将伸开的手掌沿墙面依次移到三个圆圈，各停一下。")
                                    .setPositiveButton("开始", (d, button) -> beginAutomaticCalibration())
                                    .setNegativeButton("取消", null).show();
                        } else {
                            if (!manualFusion && savedAlignment != null) copyAlignmentToSliders(savedAlignment);
                            manualFusion = true;
                            settings.edit().putBoolean("fusion_manual", true).apply();
                            invalidateRegistration();
                            thermal.setAutomaticFusion(false, null, 0);
                            showFusionAlignment(true);
                        }
                    }).setNegativeButton("取消", null).show();
        }
    }

    private void copyAlignmentToSliders(FusionRegistration.Transform transform) {
        int w = FusionRegistration.WIDTH;
        int h = FusionRegistration.HEIGHT;
        int sourceHeight = Math.round(w * cameraHeight / (float) cameraWidth);
        int angle = (int) Math.round(Math.toDegrees(Math.atan2(transform.b, transform.a)));
        fusionRotation = Math.floorMod(angle - cameraOrientation, 360);
        double radians = Math.toRadians(angle);
        double cos = Math.abs(Math.cos(radians)), sin = Math.abs(Math.sin(radians));
        double fill = Math.max((w * cos + h * sin) / w, (w * sin + h * cos) / sourceHeight);
        int zoom = (int) Math.round(100 * Math.hypot(transform.a, transform.b) / fill);
        float centerX = (transform.a * w - transform.b * sourceHeight) / 2 + transform.x;
        float centerY = (transform.b * w + transform.a * sourceHeight) / 2 + transform.y;
        fusionZoom.setProgress(zoom - MIN_ZOOM);
        fusionX.setProgress(MAX_OFFSET + Math.round(100 * (centerX / w - .5f)));
        fusionY.setProgress(MAX_OFFSET + Math.round(100 * (centerY / h - .5f)));
        applyFusionAlignment();
    }

    private void startLearnedAlignmentIfPaired() {
        if (!waitingForPair) return;
        ThermalView.Pair pair = thermal.currentPair();
        if (pair == null) return;
        waitingForPair = false;
        mainHandler.removeCallbacks(pairTimeout);
        updateFusionButtons();
        int generation = registrationGeneration;
        registrationWorker.execute(() -> {
            FusionRegistration.Transform result = null;
            String notice = "共同轮廓不足，请把管接头、转角等清晰结构纳入两路画面后重试";
            try {
                result = LearnedAlignment.estimate(getApplicationContext(), pair, () -> generation != registrationGeneration);
                if (result != null) notice = "AI 对齐已保存";
            } catch (Exception | LinkageError e) {
                android.util.Log.e("WP09", "Learned alignment failed", e);
                notice = "模型运行失败，可用手掌对齐或保存图对";
            } finally {
                pair.camera.recycle();
            }
            FusionRegistration.Transform match = result;
            String statusText = notice;
            mainHandler.post(() -> {
                if (generation == registrationGeneration) endLearnedAlignment(match, statusText);
            });
        });
    }

    private void endLearnedAlignment(FusionRegistration.Transform result, String notice) {
        invalidateRegistration();
        if (result != null) {
            savedAlignment = result;
            learnedProfile = true;
            manualFusion = false;
            settings.edit().putString(alignmentKey, result.encode()).putBoolean(alignmentKey + ".ai", true)
                    .putBoolean("fusion_manual", false).apply();
        }
        restoreAlignment();
        updateFusionButtons();
        message(notice);
    }

    private void restoreAlignment() {
        thermal.setAutomaticFusion(!manualFusion, learnedProfile ? savedAlignment : null, learnedProfile ? Long.MAX_VALUE : 0);
    }

    private void beginAutomaticCalibration() {
        if (!fusionReady) return;
        invalidateRegistration();
        fusionCalibration = new FusionRegistration.Calibration();
        thermal.setAutomaticFusion(true, null, 0);
        fusionNotice.setVisibility(View.VISIBLE);
        setText(fusionNotice, "保持手机不动，先空出画面");
        mainHandler.postDelayed(alignmentTimeout, 45_000);
        updateFusionButtons();
    }

    private void finishAutomaticCalibration(FusionRegistration.Transform result, String notice) {
        if (fusionCalibration == null) return;
        fusionCalibration = null;
        invalidateRegistration();
        if (result != null) {
            savedAlignment = result;
            learnedProfile = false;
            manualFusion = false;
            settings.edit().putString(alignmentKey, result.encode()).remove(alignmentKey + ".ai")
                    .putBoolean("fusion_manual", false).apply();
        }
        restoreAlignment();
        updateFusionButtons();
        if (notice != null) message(notice);
    }

    private void invalidateRegistration() {
        registrationGeneration++;
        learning = waitingForPair = false;
        mainHandler.removeCallbacks(pairTimeout);
        mainHandler.removeCallbacks(alignmentTimeout);
        lastRegistrationAt = lastUsedVisibleAt = trackingUntil = 0;
        pendingAlignment = trackedAlignment = null;
        thermal.setAlignmentGuide(-1);
    }

    private void receiveVisible(int[] pixels, int width, int height, int orientation, String id, long receivedAt) {
        if (!id.equals(visibleCameraId) || width != cameraWidth || height != cameraHeight
                || orientation != cameraOrientation) {
            if (fusionCalibration != null) finishAutomaticCalibration(null, "相机画面尺寸已变化，请重新标定");
            invalidateRegistration();
            visibleCameraId = id;
            cameraWidth = width;
            cameraHeight = height;
            cameraOrientation = orientation;
            alignmentKey = "fusion_auto." + usbAlignmentId() + "." + id + "." + width + "x" + height
                    + "." + orientation;
            savedAlignment = FusionRegistration.Transform.decode(settings.getString(alignmentKey, null));
            learnedProfile = savedAlignment != null && settings.getBoolean(alignmentKey + ".ai", false);
            visibleSampleAt = 0;
            restoreAlignment();
        }
        thermal.showVisible(pixels, width, height, orientation, receivedAt);
        startLearnedAlignmentIfPaired();
        boolean checking = fusionCalibration != null || !manualFusion && !learnedProfile && savedAlignment != null;
        if (checking && receivedAt - visibleSampleAt >= 48_000_000L) {
            visibleSample = FusionRegistration.Image.fromVisible(pixels, width, height);
            visibleSampleAt = receivedAt;
        }
        if (!fusionReady) {
            fusionReady = true;
            updateFusionButtons();
        }
    }

    private String usbAlignmentId() {
        if (selected == null) return "unknown";
        String model = selected.getVendorId() + "." + selected.getProductId();
        try {
            String serial = selected.getSerialNumber();
            return serial == null ? model : model + "." + serial;
        } catch (SecurityException denied) {
            return model;
        }
    }

    private void registerFrames(ThermalFrame next, long receivedAt) {
        startLearnedAlignmentIfPaired();
        if (learning || learnedProfile && fusionCalibration == null) return;
        FusionRegistration.Calibration setup = fusionCalibration;
        FusionRegistration.Transform base = savedAlignment;
        if (!fusionReady || setup == null && (manualFusion || base == null) || registrationBusy) return;
        if (setup == null && trackedAlignment != null && SystemClock.elapsedRealtimeNanos() > trackingUntil) {
            updateTracking(null);
        }
        long interval = setup == null ? 210_000_000L : 125_000_000L;
        if (receivedAt - lastRegistrationAt < interval || visibleSampleAt == lastUsedVisibleAt
                || !FusionRegistration.paired(receivedAt, visibleSampleAt)) return;

        FusionRegistration.Image light = visibleSample;
        int generation = registrationGeneration;
        lastRegistrationAt = receivedAt;
        lastUsedVisibleAt = visibleSampleAt;
        registrationBusy = true;
        registrationWorker.execute(() -> {
            FusionRegistration.Image heat = FusionRegistration.Image.fromThermal(next);
            int step = setup == null ? -1 : setup.observe(heat, light);
            FusionRegistration.Transform result = setup == null ? FusionRegistration.track(heat, light, base)
                    : step == FusionRegistration.TARGET_X.length ? setup.result() : null;
            mainHandler.post(() -> {
                registrationBusy = false;
                if (generation != registrationGeneration) return;
                if (setup == null) {
                    updateTracking(SystemClock.elapsedRealtimeNanos() - receivedAt <= ALIGNMENT_HOLD_NANOS
                            ? result : null);
                } else if (step == FusionRegistration.TARGET_X.length) {
                    finishAutomaticCalibration(result, result == null ? "三个位置没能对齐，请在同一距离重试"
                            : "自动对齐已保存");
                } else if (step >= 0) {
                    thermal.setAlignmentGuide(step);
                    setText(fusionNotice, (step + 1) + "/3 把手掌移到圆圈，停一下");
                }
            });
        });
    }

    private void updateTracking(FusionRegistration.Transform result) {
        if (result != null && pendingAlignment != null && Math.abs(result.x - pendingAlignment.x) <= 2
                && Math.abs(result.y - pendingAlignment.y) <= 2) {
            trackedAlignment = result;
            trackingUntil = SystemClock.elapsedRealtimeNanos() + ALIGNMENT_HOLD_NANOS;
        } else if (result == null || trackedAlignment == null || Math.abs(result.x - trackedAlignment.x) > 2
                || Math.abs(result.y - trackedAlignment.y) > 2) {
            trackedAlignment = null;
            trackingUntil = 0;
        }
        pendingAlignment = result;
        thermal.setAutomaticFusion(true, trackedAlignment, trackingUntil);
        updateFusionButtons();
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
        if (recording) {
            stopVideo();
        } else if (!recordingStarting && streaming) {
            recordingStarting = true;
            int request = ++recordingRequest;
            media.start(ok -> {
                if (request != recordingRequest) return;
                recordingStarting = false;
                recording = ok;
                if (ok) media.frame(thermal, () -> {
                    recording = false;
                    render();
                });
                render();
            });
        }
        render();
    }

    private void stopVideo() {
        recordingRequest++;
        if (recording || recordingStarting) media.stop();
        recording = false;
        recordingStarting = false;
    }

    private void choosePalette() {
        ArrayAdapter<String> options = new ArrayAdapter<String>(this,
                android.R.layout.select_dialog_singlechoice, ThermalView.PALETTE_NAMES) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView label = (TextView) super.getView(position, recycled, parent);
                GradientDrawable swatch = ThermalView.swatch(position);
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
        String location = frame.hasPixelTemperatures() ? "所选区域读数 " : "画面中心读数 ";
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
        settings.edit().putFloat("scale", value.scale).putFloat("offset", value.offset)
                .putBoolean("calibrated", value.applied).apply();
        render();
    }

    private void render() {
        thermal.show(frame, calibration, measuring);
        boolean valid = frame != null && frame.hasTemperature();
        boolean shown = measuring && valid;
        empty.setVisibility(frame == null ? View.VISIBLE : View.GONE);
        if (streaming) {
            String message;
            if (recording) message = "正在录像";
            else if (valid && !frame.hasPixelTemperatures()) message = calibration.applied
                    ? "预览估温 · 已校准" : "预览估温";
            else if (valid) message = calibration.applied ? "已连接 · 已校准" : "已连接 · 未现场校准";
            else message = SystemClock.elapsedRealtime() - pluggedAt < 1500
                    ? "读取标定数据" : "图像已连接 · 无温度数据";
            setText(status, message);
        }
        showValue(hot, shown ? frame.maximum : Float.NaN);
        showValue(cold, shown ? frame.minimum : Float.NaN);
        showValue(average, shown ? frame.average : Float.NaN);
        setText(spot, shown ? thermal.spotDisplay() : "—");
        button(laserButton, "激光", streaming && !laserBusy, laser);
        button(photoButton, "拍照", streaming, false);
        int videoIcon = recording ? R.drawable.ic_square : R.drawable.ic_video;
        if (!(videoButton.getTag() instanceof Integer) || (int) videoButton.getTag() != videoIcon) {
            videoButton.setIconResource(videoIcon);
            videoButton.setTag(videoIcon);
        }
        button(videoButton, recording ? "停止" : "录像", streaming && !recordingStarting, recording);
        button(measurementButton, "测温", true, measuring);
        button(paletteButton, ThermalView.PALETTE_NAMES[palette], true, false);
        button(emissivityButton, "ε " + emissivity, true, false);
        button(calibrationButton, Float.isFinite(lowCalibrationReading) ? "校准高点" : "校准",
                shown, calibration.applied);
        updateFusionButtons();
    }

    private void showValue(TextView view, float celsius) {
        setText(view, Float.isFinite(celsius)
                ? ThermalView.number(calibration.apply(celsius)) + " °C" : "—");
    }

    private static void setText(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private void button(MaterialButton view, String label, boolean enabled, boolean active) {
        setText(view, label);
        if (view.isEnabled() != enabled) view.setEnabled(enabled);
        if (view.isSelected() != active) view.setSelected(active);
    }

    private TextView metric(LinearLayout row, String label, int color) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        TextView name = text(label, 11, MUTED);
        name.setGravity(Gravity.CENTER);
        TextView value = text("—", 16, color);
        value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        value.setGravity(Gravity.CENTER);
        value.setSingleLine();
        value.setHorizontallyScrolling(false);
        if (Build.VERSION.SDK_INT >= 26) value.setAutoSizeTextTypeUniformWithConfiguration(
                9, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        column.addView(name, new LinearLayout.LayoutParams(-1, dp(16)));
        column.addView(value, new LinearLayout.LayoutParams(-1, dp(25)));
        row.addView(column, new LinearLayout.LayoutParams(0, dp(44), 1));
        return value;
    }

    private MaterialButton control(LinearLayout group, String label, int icon, Runnable action) {
        MaterialButton button = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setTextSize(11);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine();
        button.setAllCaps(false);
        button.setLetterSpacing(0);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(0);
        button.setStrokeWidth(0);
        if (icon != 0) button.setIconResource(icon);
        button.setIconSize(dp(16));
        button.setIconPadding(dp(4));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        if (Build.VERSION.SDK_INT >= 26) button.setTooltipText(label);
        ColorStateList foreground = new ColorStateList(new int[][]{
                {-android.R.attr.state_enabled}, {android.R.attr.state_selected}, {}
        }, new int[]{0xff828282, BG, TEXT});
        button.setTextColor(foreground);
        button.setIconTint(foreground);
        button.setBackgroundTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_selected}, {}}, new int[]{0xffdedede, PANEL}));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams width = new LinearLayout.LayoutParams(0, dp(48), 1);
        width.setMargins(dp(2), dp(2), dp(2), dp(2));
        group.addView(button, width);
        return button;
    }

    private TextView text(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
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
