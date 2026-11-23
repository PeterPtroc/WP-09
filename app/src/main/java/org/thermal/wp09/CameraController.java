package org.thermal.wp09;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.function.Consumer;

final class CameraController {
    private final UsbManager usb;
    private final HandlerThread thread = new HandlerThread("WP09 USB");
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Session current;
    private volatile float emissivity = 0.98f;
    private UsbSample lastFailure;

    CameraController(UsbManager usb) {
        this.usb = usb;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void start(UsbDevice device, Consumer<ThermalFrame> onFrame, Consumer<String> onError) {
        stop();
        Session session = new Session(device, onFrame, onError);
        current = session;
        worker.post(() -> {
            lastFailure = null;
            session.open();
        });
    }

    void setEmissivity(float value) {
        emissivity = value;
    }

    void collectSample(Consumer<UsbSample> callback) {
        Session session = current;
        worker.post(() -> {
            UsbSample sample = session != null && current == session ? session.sample("streaming")
                    : lastFailure != null ? lastFailure : new UsbSample(null, null, 0, "no session");
            main.post(() -> callback.accept(sample));
        });
    }

    void setLaser(boolean enabled, Consumer<Boolean> callback) {
        Session session = current;
        if (session == null) {
            callback.accept(false);
            return;
        }
        worker.post(() -> {
            if (current != session) return;
            int result = session.handle != 0 ? Wp09Usb.laser(session.handle, enabled) : -1;
            main.post(() -> {
                if (current == session) callback.accept(result == 0);
            });
        });
    }

    void stop() {
        Session session = current;
        current = null;
        if (session != null) worker.post(session::close);
    }

    void destroy() {
        stop();
        thread.quitSafely();
    }

    private final class Session {
        private final UsbDevice device;
        private final Consumer<ThermalFrame> onFrame;
        private final Consumer<String> onError;
        private final byte[] bytes = new byte[ThermalFrame.BYTES];
        private final ThermalFrame.Decoder decoder = new ThermalFrame.Decoder();
        private UsbDeviceConnection connection;
        private long handle;
        private long lastReceived;
        private boolean delivering;
        private int unexpectedFrameBytes;

        Session(UsbDevice device, Consumer<ThermalFrame> onFrame, Consumer<String> onError) {
            this.device = device;
            this.onFrame = onFrame;
            this.onError = onError;
        }

        void open() {
            if (current != this) return;
            try {
                connection = usb.openDevice(device);
                if (connection == null) {
                    fail("无法打开 WP-09。请退出原应用并重新插入设备");
                    return;
                }
                String name = device.getDeviceName();
                int addressIndex = name.lastIndexOf('/');
                int busIndex = name.lastIndexOf('/', addressIndex - 1);
                long[] outputHandle = new long[1];
                int result = Wp09Usb.open(connection.getFileDescriptor(), device.getVendorId(),
                        device.getProductId(), Integer.parseInt(name.substring(addressIndex + 1)),
                        name.substring(0, busIndex), outputHandle);
                if (result != 0) {
                    fail("无法启动 WP-09 视频流（" + result + "）");
                    return;
                }
                handle = outputHandle[0];
                lastReceived = SystemClock.elapsedRealtime();
                worker.post(this::poll);
            } catch (RuntimeException | LinkageError error) {
                Log.e("WP09", "USB open", error);
                fail("无法启动 USB 热成像");
            }
        }

        void poll() {
            if (current != this) return;
            int count = Wp09Usb.read(handle, bytes);
            long now = SystemClock.elapsedRealtime();
            if (count < 0) unexpectedFrameBytes = -count;
            if (count == bytes.length) {
                lastReceived = now;
                synchronized (this) {
                    if (!delivering) {
                        ThermalFrame frame = decoder.decode(bytes, emissivity);
                        delivering = true;
                        main.post(() -> {
                            try {
                                if (current == this) onFrame.accept(frame);
                            } finally {
                                synchronized (this) { delivering = false; }
                            }
                        });
                    }
                }
            } else if (now - lastReceived > 4000) {
                fail(unexpectedFrameBytes == 0 ? "WP-09 未传输画面，请重新插入设备"
                        : "设备返回了未识别的帧格式（" + unexpectedFrameBytes + " 字节）");
                return;
            }
            worker.postDelayed(this::poll, 32);
        }

        void fail(String message) {
            lastFailure = sample(message);
            close();
            main.post(() -> {
                if (current == this) {
                    current = null;
                    onError.accept(message);
                }
            });
        }

        UsbSample sample(String status) {
            byte[] raw = handle == 0 ? null : Wp09Usb.sample(handle);
            return new UsbSample(device, raw, unexpectedFrameBytes, status);
        }

        void close() {
            if (handle != 0) {
                Wp09Usb.close(handle);
                handle = 0;
            }
            if (connection != null) {
                connection.close();
                connection = null;
            }
        }
    }
}
