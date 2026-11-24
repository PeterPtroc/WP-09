package org.thermal.wp09;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.function.Consumer;

final class VisibleCamera {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final long FRAME_TIMEOUT_MS = 5000;
    private final Context context;
    private final CameraManager manager;
    private final HandlerThread thread = new HandlerThread("WP09 visible camera");
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Session requested;
    private Session active;
    private boolean destroyed;

    interface FrameListener {
        void onFrame(int[] argb, int width, int height, int sensorOrientation, String cameraId, long receivedAt);
    }

    VisibleCamera(Context context) {
        this.context = context.getApplicationContext();
        manager = context.getSystemService(CameraManager.class);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void start(FrameListener onFrame, Consumer<String> onError) {
        requested = new Session(onFrame, onError);
        worker.post(this::update);
    }

    void stop() {
        requested = null;
        worker.post(this::update);
    }

    void destroy() {
        requested = null;
        worker.post(() -> {
            destroyed = true;
            update();
        });
    }

    private void update() {
        Session next = requested;
        if (active != null && (active != next || active.failed)) {
            // Even a canceled open must receive its callback so it can close the device.
            if (active.opening) return;
            active.close();
            active = null;
        }
        if (destroyed) {
            thread.quitSafely();
        } else if (active == null && next != null && !next.failed) {
            active = next;
            next.open();
        }
    }

    private final class Session extends CameraDevice.StateCallback {
        private final FrameListener onFrame;
        private final Consumer<String> onError;
        private final byte[][] yuv = new byte[3][];
        private final Runnable timeout = this::checkFrameTimeout;
        private int[] argb;
        private ImageReader reader;
        private CameraDevice device;
        private CameraCaptureSession capture;
        private int orientation;
        private String cameraId;
        private long lastFrame;
        private boolean opening;
        private boolean failed;
        private volatile boolean delivering;

        Session(FrameListener onFrame, Consumer<String> onError) {
            this.onFrame = onFrame;
            this.onError = onError;
        }

        void open() {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                fail("请授予相机权限后开启热融合", null);
                return;
            }
            try {
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics properties = manager.getCameraCharacteristics(id);
                    Integer facing = properties.get(CameraCharacteristics.LENS_FACING);
                    if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                    StreamConfigurationMap formats = properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    Size size = formats == null ? null : previewSize(formats.getOutputSizes(ImageFormat.YUV_420_888));
                    if (size == null) continue;
                    Integer sensor = properties.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    orientation = sensor == null ? 0 : sensor;
                    cameraId = id;
                    reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
                    reader.setOnImageAvailableListener(this::read, worker);
                    opening = true;
                    manager.openCamera(id, this, worker);
                    lastFrame = SystemClock.uptimeMillis();
                    worker.postDelayed(timeout, FRAME_TIMEOUT_MS);
                    return;
                }
                fail("找不到可用的后置摄像头", null);
            } catch (CameraAccessException | RuntimeException error) {
                opening = false;
                fail("无法打开后置摄像头", error);
            }
        }

        @Override public void onOpened(CameraDevice camera) {
            opening = false;
            device = camera;
            if (requested != this || failed) {
                update();
                return;
            }
            try {
                Surface surface = reader.getSurface();
                CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                request.addTarget(surface);
                camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession session) {
                        if (requested != Session.this || active != Session.this || failed) {
                            session.close();
                            return;
                        }
                        capture = session;
                        try {
                            session.setRepeatingRequest(request.build(), null, worker);
                        } catch (CameraAccessException | RuntimeException error) {
                            fail("无法启动后置摄像头预览", error);
                        }
                    }

                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                        session.close();
                        fail("无法启动后置摄像头预览", null);
                    }
                }, worker);
            } catch (CameraAccessException | RuntimeException error) {
                fail("无法启动后置摄像头预览", error);
            }
        }

        @Override public void onDisconnected(CameraDevice camera) {
            camera.close();
            opening = false;
            fail("后置摄像头已断开", null);
        }

        @Override public void onError(CameraDevice camera, int error) {
            camera.close();
            opening = false;
            fail("后置摄像头不可用，可关闭占用相机的应用后重试", null);
        }

        private void read(ImageReader source) {
            if (source != reader || requested != this || failed) return;
            try (Image image = source.acquireLatestImage()) {
                if (image == null) return;
                long arrival = SystemClock.elapsedRealtimeNanos();
                lastFrame = SystemClock.uptimeMillis();
                if (delivering) return;
                Rect crop = image.getCropRect();
                int step = Math.max((crop.width() + WIDTH - 1) / WIDTH, (crop.height() + HEIGHT - 1) / HEIGHT);
                int width = (crop.width() + step - 1) / step;
                int height = (crop.height() + step - 1) / step;
                if (argb == null || argb.length != width * height) argb = new int[width * height];
                convert(image.getPlanes(), crop, step);
                delivering = true;
                main.post(() -> {
                    try {
                        if (requested == this) onFrame.onFrame(argb, width, height, orientation, cameraId, arrival);
                    } finally {
                        delivering = false;
                    }
                });
            } catch (RuntimeException error) {
                fail("无法读取后置摄像头画面", error);
            }
        }

        private void checkFrameTimeout() {
            long remaining = lastFrame + FRAME_TIMEOUT_MS - SystemClock.uptimeMillis();
            if (remaining > 0) worker.postDelayed(timeout, remaining);
            else fail("后置摄像头未传输画面", null);
        }

        private void convert(Image.Plane[] planes, Rect crop, int step) {
            for (int i = 0; i < planes.length; i++) {
                ByteBuffer buffer = planes[i].getBuffer();
                int length = buffer.remaining();
                if (yuv[i] == null || yuv[i].length < length) yuv[i] = new byte[length];
                buffer.get(yuv[i], 0, length);
            }
            int yRowStride = planes[0].getRowStride();
            int uRowStride = planes[1].getRowStride();
            int vRowStride = planes[2].getRowStride();
            int uPixelStride = planes[1].getPixelStride();
            int vPixelStride = planes[2].getPixelStride();
            int index = 0;
            for (int row = crop.top; row < crop.bottom; row += step) {
                int yRow = row * yRowStride;
                int uRow = (row / 2) * uRowStride;
                int vRow = (row / 2) * vRowStride;
                for (int col = crop.left; col < crop.right; col += step) {
                    int y = Math.max(0, (yuv[0][yRow + col] & 0xff) - 16) * 298;
                    int u = (yuv[1][uRow + (col / 2) * uPixelStride] & 0xff) - 128;
                    int v = (yuv[2][vRow + (col / 2) * vPixelStride] & 0xff) - 128;
                    int red = channel(y + 409 * v);
                    int green = channel(y - 100 * u - 208 * v);
                    int blue = channel(y + 516 * u);
                    argb[index++] = 0xff000000 | (red << 16) | (green << 8) | blue;
                }
            }
        }

        private void fail(String message, Exception error) {
            if (active != this) return;
            if (error != null) Log.w("WP09", "Visible camera", error);
            boolean alreadyFailed = failed;
            failed = true;
            update();
            if (!alreadyFailed) main.post(() -> {
                if (requested == this) onError.accept(message);
            });
        }

        private void close() {
            worker.removeCallbacks(timeout);
            if (capture != null) capture.close();
            if (device != null) device.close();
            if (reader != null) {
                reader.close();
                reader = null;
            }
        }
    }

    private static int channel(int value) {
        return Math.max(0, Math.min(255, (value + 128) >> 8));
    }

    private static Size previewSize(Size[] sizes) {
        if (sizes == null) return null;
        Size best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Size size : sizes) {
            long dx = size.getWidth() - WIDTH;
            long dy = size.getHeight() - HEIGHT;
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                best = size;
                bestDistance = distance;
            }
        }
        return best;
    }
}
