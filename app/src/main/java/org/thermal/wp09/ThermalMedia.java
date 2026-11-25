package org.thermal.wp09;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Semaphore;
import android.util.Log;
import java.util.Locale;
import java.util.function.Consumer;

final class ThermalMedia {
    static final String SAVED = "org.thermal.wp09.MEDIA_SAVED";
    private final Context context;
    private final Consumer<String> notify;
    private final HandlerThread thread = new HandlerThread("WP09 media");
    private final Handler worker;
    private final HandlerThread photoThread = new HandlerThread("WP09 photos");
    private final Handler photoWorker;
    private final Semaphore photoSlots = new Semaphore(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Semaphore available = new Semaphore(24);
    private ThermalVideo movie;

    ThermalMedia(Context context, Consumer<String> notify) {
        this.context = context.getApplicationContext();
        this.notify = notify;
        thread.start();
        worker = new Handler(thread.getLooper());
        photoThread.start();
        photoWorker = new Handler(photoThread.getLooper());
    }

    void photo(ThermalCapture capture) {
        photo(capture, true);
    }

    void photo(ThermalCapture capture, boolean showSaved) {
        if (capture == null) return;
        if (!photoSlots.tryAcquire()) {
            message("照片正在保存，请稍后再拍");
            return;
        }
        photoWorker.post(() -> {
            try {
                writeMedia(false, stream -> RadiometricPng.write(stream, capture));
                saved(showSaved ? "照片已保存" : null);
            } catch (IOException | RuntimeException e) {
                Log.w("WP09", "Photo", e);
                message("照片保存失败");
            } finally {
                photoSlots.release();
            }
        });
    }

    void start(Consumer<Boolean> ready) {
        start(1, ready);
    }

    void start(int scale, Consumer<Boolean> ready) {
        worker.post(() -> {
            try {
                movie = new ThermalVideo(context.getCacheDir(), true, scale);
                main.post(() -> ready.accept(true));
            } catch (IOException | RuntimeException e) {
                Log.w("WP09", "Video start", e);
                main.post(() -> ready.accept(false));
                message("无法启动录像");
            }
        });
    }

    void frame(ThermalCapture capture, Runnable onFailure) {
        if (capture == null || !capture.frame.hasPixelTemperatures()) return;
        if (!available.tryAcquire()) {
            main.post(onFailure);
            message("编码积压，正在保存已录画面");
            return;
        }
        long stamp = capture.frame.capturedNanos;
        worker.post(() -> {
            try {
                if (movie != null) movie.offer(capture, stamp);
            } catch (IOException | RuntimeException e) {
                Log.w("WP09", "Video frame", e);
                release();
                main.post(onFailure);
                message("录像编码失败");
            } finally {
                available.release();
            }
        });
    }

    void burst(ThermalCapture[] frames, Consumer<Boolean> finished) {
        worker.post(() -> {
            try {
                if (movie == null) { main.post(() -> finished.accept(false)); return; }
                for (ThermalCapture frame : frames) {
                    if (!frame.frame.hasPixelTemperatures()) throw new IOException("Missing temperature plane");
                    movie.offer(frame, frame.frame.capturedNanos, true);
                }
                main.post(() -> finished.accept(true));
            } catch (IOException | RuntimeException e) {
                Log.w("WP09", "Video burst", e);
                release();
                main.post(() -> finished.accept(false));
                message("录像编码失败");
            }
        });
    }

    void stop() {
        long end = System.nanoTime();
        worker.post(() -> {
            if (movie == null) return;
            try {
                if (!movie.hasInput()) {
                    message("尚未取得录像帧");
                    return;
                }
                movie.finish(end);
                writeMedia(true, movie::writeTo);
                saved("录像已保存");
            } catch (IOException | RuntimeException e) {
                Log.w("WP09", "Video save", e);
                message("录像保存失败");
            } finally {
                release();
            }
        });
    }

    void destroy() {
        stop();
        thread.quitSafely();
        photoThread.quitSafely();
    }

    private void release() {
        if (movie != null) movie.close();
        movie = null;
    }

    private void saved(String text) {
        context.sendBroadcast(new android.content.Intent(SAVED).setPackage(context.getPackageName()));
        if (text != null) message(text);
    }

    private void message(String text) {
        main.post(() -> notify.accept(text));
    }

    private void writeMedia(boolean movie, Writer writer) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        String name = "WP09_" + stamp + (movie ? ".mp4" : ".png");
        String directory = movie ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
        if (Build.VERSION.SDK_INT < 29) {
            File folder = context.getExternalFilesDir(directory);
            if (folder == null) throw new IOException("External storage unavailable");
            File output = new File(folder, name);
            File temp = File.createTempFile(".wp09-", ".tmp", folder);
            try {
                try (OutputStream stream = new FileOutputStream(temp)) { writer.write(stream); }
                if (!temp.renameTo(output)) throw new IOException("Media commit failed");
            } finally {
                temp.delete();
            }
            return;
        }

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, movie ? "video/mp4" : "image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory + "/WP09");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(movie ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert failed");
        boolean committed = false;
        try {
            try (OutputStream stream = resolver.openOutputStream(uri)) {
                if (stream == null) throw new IOException("MediaStore output unavailable");
                writer.write(stream);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            committed = resolver.update(uri, values, null, null) == 1;
            if (!committed) throw new IOException("MediaStore update failed");
        } finally {
            if (!committed) resolver.delete(uri, null, null);
        }
    }

    private interface Writer {
        void write(OutputStream stream) throws IOException;
    }
}
