package org.thermal.wp09;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.function.Consumer;

final class CameraController {
    private final UsbManager usb;
    private final android.content.SharedPreferences prefs;
    private volatile LiveImage.Settings quality = new LiveImage.Settings(0, 0, false);
    private volatile ColorScale colorScale = ColorScale.AUTO;
    private volatile Calibration displayCalibration = Calibration.DEFAULT;
    private final HandlerThread thread = new HandlerThread("WP09 USB");
    private final Handler worker;
    private final HandlerThread previewThread = new HandlerThread("WP09 preview");
    private final Handler previewWorker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Session current;
    private volatile float emissivity = 0.98f;
    private UsbSample lastFailure;

    CameraController(UsbManager usb) {
        this(usb, null);
    }

    CameraController(UsbManager usb, android.content.SharedPreferences prefs) {
        this.usb = usb;
        this.prefs = prefs;
        thread.start();
        worker = new Handler(thread.getLooper());
        previewThread.start();
        previewWorker = new Handler(previewThread.getLooper());
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

    void setQuality(int scene, int palette, boolean doubleSize) {
        if (scene != quality.scene || palette != quality.palette || doubleSize != quality.doubleSize)
            quality = new LiveImage.Settings(scene, palette, doubleSize);
    }

    void setColorScale(ColorScale value) { colorScale = value; }
    void setDisplayCalibration(Calibration value) { displayCalibration = value; }

    CameraRepairs repairs() {
        Session session = current;
        return session == null ? null : session.repairs;
    }

    void collectSample(Consumer<UsbSample> callback) {
        Session session = current;
        worker.post(() -> {
            UsbSample sample = session != null && current == session ? session.sample("streaming")
                    : lastFailure != null ? lastFailure : new UsbSample(null, null, 0, "no session");
            main.post(() -> callback.accept(sample));
        });
    }

    static boolean supportsFullFrame(UsbDevice device) {
        if (device == null || device.getVendorId() != 0x3474 || device.getProductId() != 0x6015) {
            return false;
        }
        return "XWild_WP09_C".equals(device.getProductName());
    }

    void captureVideoBatch(Consumer<ThermalFrame[]> callback) {
        Session session = current;
        if (session == null || !supportsFullFrame(session.device)) {
            callback.accept(new ThermalFrame[0]);
            return;
        }
        int generation = session.movieGeneration;
        float captureEmissivity = emissivity;
        worker.post(() -> {
            if (current != session || session.handle == 0) return;
            ArrayList<ThermalFrame> frames = new ArrayList<>(8);
            session.capturing = true;
            try {
                synchronized (session.readLock) {
                    session.readingThermal = session.identifiedThermalOutput && generation == session.movieGeneration;
                }
                if (session.readingThermal) session.readThermal(frames, captureEmissivity, generation);
            } finally {
                if (frames.isEmpty() || generation != session.movieGeneration) session.readingThermal = false;
                session.capturing = false;
            }
            main.post(() -> {
                if (current == session) callback.accept(frames.toArray(new ThermalFrame[0]));
            });
        });
    }

    void endMapVideo() {
        Session session = current;
        if (session == null) return;
        session.movieGeneration++;
        worker.post(() -> session.readingThermal = false);
    }

    void triggerShutter(Consumer<Boolean> callback) {
        Session session = current;
        if (session == null || !supportsFullFrame(session.device)) {
            callback.accept(false);
            return;
        }
        worker.post(() -> {
            if (current != session || session.handle == 0) return;
            synchronized (session.readLock) {
                if (!session.shutter.holding() && Wp09Usb.triggerShutter(session.handle) == 0) {
                    session.shutterSettled = callback;
                    session.beginShutter(System.nanoTime(), true);
                    return;
                }
            }
            main.post(() -> { if (current == session) callback.accept(false); });
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
        previewThread.quitSafely();
        thread.quitSafely();
    }

    private final class Session {
        private final UsbDevice device;
        private final Consumer<ThermalFrame> onFrame;
        private final Consumer<String> onError;
        private final byte[] bytes = new byte[ThermalFrame.BYTES];
        private final byte[] thermalBytes = new byte[ThermalFrame.BYTES];
        private final ThermalFrame.Decoder decoder = new ThermalFrame.Decoder();
        private final ThermalFrame.Decoder snapshotDecoder = new ThermalFrame.Decoder();
        private final ShutterGate shutter = new ShutterGate();
        private Consumer<Boolean> shutterSettled;
        private final Object readLock = new Object();
        private volatile boolean capturing;
        private volatile boolean readingThermal;
        private volatile int movieGeneration;
        private final boolean xwild;
        private BlindPixels defects;
        private volatile CameraRepairs repairs;
        private final LiveImage image = new LiveImage();
        private boolean identifiedThermalOutput;
        private ThermalFrame recentPreview;
        private long retryPreviewAt;
        private UsbDeviceConnection connection;
        private long handle;
        private volatile long lastReceived;
        private ThermalFrame pending;
        private final Runnable deliver = this::deliverLatest;
        private volatile int unexpectedFrameBytes;

        Session(UsbDevice device, Consumer<ThermalFrame> onFrame, Consumer<String> onError) {
            this.device = device;
            xwild = supportsFullFrame(device);
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
                // A previous client may have left the device in thermal or raw output mode.
                if (xwild) {
                    if (!Wp09Usb.setThermalOutput(handle, false)) {
                        fail("无法读取相机测温数据");
                        return;
                    }
                    // Each open has its own USB request queue. UVC owns the first connection.
                    UsbDeviceConnection capture = null;
                    try {
                        capture = usb.openDevice(device);
                        if (capture != null) Wp09Usb.attachCaptureFd(handle, capture.getFileDescriptor());
                    } catch (RuntimeException error) {
                        Log.i("WP09", "Using the existing HID connection", error);
                    } finally {
                        if (capture != null) capture.close();
                    }
                    byte[] kb = Wp09Usb.factoryKb(handle);
                    defects = BlindPixels.fromFactory(kb);
                    String serial = null;
                    try { serial = device.getSerialNumber(); } catch (SecurityException ignored) {}
                    repairs = new CameraRepairs(prefs, serial, kb, defects);
                }
                lastReceived = SystemClock.elapsedRealtime();
                previewWorker.post(this::poll);
            } catch (RuntimeException | LinkageError error) {
                Log.e("WP09", "USB open", error);
                fail("无法启动 USB 热成像");
            }
        }

        void readThermal(ArrayList<ThermalFrame> frames, float captureEmissivity, int generation) {
            // A shutter correction can briefly pause exposures. Keep the recording open through that gap.
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (current == this && generation == movieGeneration && SystemClock.elapsedRealtime() < deadline) {
                long stamp = Wp09Usb.read(handle, thermalBytes);
                if (stamp <= 0) continue;
                ThermalFrame frame = thermalFrame(snapshotDecoder, thermalBytes, captureEmissivity, stamp);
                if (shutter.holding() && !frames.isEmpty()) break;
                if (frame == null) continue;
                lastReceived = SystemClock.elapsedRealtime();
                if (frames.isEmpty()) deadline = lastReceived + 1000;
                frames.add(frame);
                if (generation == movieGeneration) present(frame);
                if (frames.size() == 8) break;
            }
        }

        private void beginShutter(long stamp, boolean commanded) {
            image.reset();
            shutter.start(stamp, commanded);
            selectPreview(stamp);
        }

        private void selectPreview(long stamp) {
            recentPreview = null;
            retryPreviewAt = Wp09Usb.setThermalOutput(handle, false) ? 0 : stamp + 150_000_000L;
        }

        // On connection and after shutter closure, use the firmware's processed
        // channel as a reference. Only verified full temperature planes leave here.
        private ThermalFrame thermalFrame(ThermalFrame.Decoder reader, byte[] wire, float emiss, long stamp) {
            if (identifiedThermalOutput && !shutter.holding()) {
                ThermalFrame frame = reader.decodeThermalOutput(wire, emiss, stamp, defects);
                if (frame == null) return null;
                if (!shutter.suspect(frame, wire)) return finishImage(reader, wire, frame, emiss);
                beginShutter(stamp, false);
                return null;
            }
            if (retryPreviewAt != 0) {
                if (stamp >= retryPreviewAt) selectPreview(stamp);
                return null;
            }
            if (recentPreview == null) {
                ThermalFrame ref = reader.decode(wire, emiss, stamp);
                if (!ref.hasPixelTemperatures() && ref.hasTemperature() && shutter.referenceReady(stamp)
                        && Wp09Usb.setThermalOutput(handle, true))
                    recentPreview = ref;
                return null;
            }
            if (stamp - recentPreview.capturedNanos > 900_000_000L) {
                selectPreview(stamp);
                return null;
            }
            ThermalFrame first = reader.decodeThermalOutput(wire, emiss, stamp, defects);
            if (first == null || !first.agreesWithPreview(recentPreview)) return null;
            shutter.verified(first);
            identifiedThermalOutput = true;
            recentPreview = null;
            if (shutterSettled != null) {
                Consumer<Boolean> done = shutterSettled;
                shutterSettled = null;
                main.post(() -> { if (current == this) done.accept(true); });
            }
            return finishImage(reader, wire, first, emiss);
        }

        private ThermalFrame finishImage(ThermalFrame.Decoder reader, byte[] wire, ThermalFrame verified, float emiss) {
            CameraRepairs.Map map = repairs.current;
            ThermalFrame measured = map.correction == null ? verified : reader.repair(wire, verified, map.correction);
            return measured == null ? null : image.process(measured, quality, emiss, map.sites, colorScale, displayCalibration);
        }

        private ThermalFrame liveFrame(long stamp) {
            if (xwild) return thermalFrame(decoder, bytes, emissivity, stamp);
            ThermalFrame frame = decoder.decode(bytes, emissivity, stamp);
            return frame.hasPixelTemperatures() ? frame : ThermalFrame.Decoder.imageOnly(frame.preview, stamp);
        }

        void poll() {
            ThermalFrame decoded = null;
            synchronized (readLock) {
                if (current != this || handle == 0) return;
                if (readingThermal) {
                    previewWorker.postDelayed(this::poll, 12);
                    return;
                }
                long stamp = Wp09Usb.read(handle, bytes);
                if (stamp < 0) unexpectedFrameBytes = (int) -stamp;
                if (stamp > 0) decoded = liveFrame(stamp);
            }
            long now = SystemClock.elapsedRealtime();
            if (decoded != null) {
                lastReceived = now;
                present(decoded);
            } else if (!capturing && now - lastReceived > 5000) {
                worker.post(() -> {
                    if (current == this) fail(xwild ? "未收到有效测温数据，点连接状态重试"
                            : unexpectedFrameBytes == 0 ? "WP-09 未传输画面，请重新插入设备"
                            : "设备返回了未识别的帧格式（" + unexpectedFrameBytes + " 字节）");
                });
                return;
            }
            previewWorker.post(this::poll);
        }

        private synchronized void present(ThermalFrame decoded) {
            if (pending != null && pending.capturedNanos >= decoded.capturedNanos) return;
            boolean schedule = pending == null;
            pending = decoded;
            if (schedule) main.post(deliver);
        }

        private void deliverLatest() {
            ThermalFrame next;
            synchronized (this) {
                next = pending;
                pending = null;
            }
            if (current == this) onFrame.accept(next);
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
            UsbSample sample = new UsbSample(device, raw, unexpectedFrameBytes, status);
            if (handle != 0 && supportsFullFrame(device)) sample.setCaptureReader(Wp09Usb.queuedCapture(handle));
            return sample;
        }

        void close() {
            synchronized (readLock) {
                if (handle != 0) {
                    Wp09Usb.close(handle);
                    handle = 0;
                }
            }
            if (connection != null) {
                connection.close();
                connection = null;
            }
        }
    }
}
