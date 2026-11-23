package org.thermal.wp09;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ThermalMedia {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 400;
    private final Context context;
    private final Consumer<String> notify;
    private final HandlerThread thread = new HandlerThread("WP09 media");
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean frameInFlight = new AtomicBoolean();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap videoFrame;
    private long queuedAt;
    private MediaRecorder recorder;
    private Surface surface;
    private File video;
    private long startedAt;

    ThermalMedia(Context context, Consumer<String> notify) {
        this.context = context.getApplicationContext();
        this.notify = notify;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void photo(ThermalView view) {
        Bitmap photo = Bitmap.createBitmap(WIDTH * 2, HEIGHT * 2, Bitmap.Config.ARGB_8888);
        view.drawCapture(photo);
        worker.post(() -> {
            try {
                writeMedia(false, stream -> {
                    if (!photo.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw new IOException("PNG encoding failed");
                    }
                });
                message("照片已保存");
            } catch (IOException | RuntimeException e) {
                message("照片保存失败");
            } finally {
                photo.recycle();
            }
        });
    }

    void start(Consumer<Boolean> ready) {
        worker.post(() -> {
            try {
                video = File.createTempFile("wp09-", ".mp4", context.getCacheDir());
                recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                recorder.setVideoSize(WIDTH, HEIGHT);
                recorder.setVideoFrameRate(20);
                recorder.setVideoEncodingBitRate(2_000_000);
                recorder.setOutputFile(video.getAbsolutePath());
                recorder.prepare();
                surface = recorder.getSurface();
                recorder.start();
                startedAt = SystemClock.elapsedRealtime();
                main.post(() -> ready.accept(true));
            } catch (IOException | RuntimeException e) {
                release();
                discard();
                main.post(() -> ready.accept(false));
                message("无法启动录像");
            }
        });
    }

    void frame(ThermalView view, Runnable onFailure) {
        long now = SystemClock.elapsedRealtime();
        if (now - queuedAt < 48 || !frameInFlight.compareAndSet(false, true)) return;
        queuedAt = now;
        if (videoFrame == null) {
            videoFrame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        }
        view.drawCapture(videoFrame);
        Bitmap frame = videoFrame;
        worker.post(() -> {
            try {
                if (surface != null) {
                    Canvas canvas = surface.lockHardwareCanvas();
                    try {
                        canvas.drawBitmap(frame, 0, 0, paint);
                    } finally {
                        surface.unlockCanvasAndPost(canvas);
                    }
                }
            } catch (RuntimeException e) {
                release();
                discard();
                main.post(onFailure);
                message("录像编码失败");
            } finally {
                frameInFlight.set(false);
            }
        });
    }

    void stop() {
        worker.post(() -> {
            if (recorder == null) return;
            try {
                boolean tooShort = SystemClock.elapsedRealtime() - startedAt < 500;
                recorder.stop();
                release();
                if (tooShort) {
                    message("录像时间太短");
                    return;
                }
                writeMedia(true, stream -> {
                    try (FileInputStream input = new FileInputStream(video)) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int n; (n = input.read(buffer)) != -1;) stream.write(buffer, 0, n);
                    }
                });
                message("录像已保存");
            } catch (IOException | RuntimeException e) {
                message("录像保存失败");
            } finally {
                release();
                discard();
            }
        });
    }

    void destroy() {
        stop();
        worker.post(() -> {
            if (videoFrame != null) videoFrame.recycle();
        });
        thread.quitSafely();
    }

    private void release() {
        if (recorder != null) recorder.release();
        recorder = null;
        if (surface != null) surface.release();
        surface = null;
    }

    private void discard() {
        if (video != null) video.delete();
        video = null;
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
            boolean committed = false;
            try (OutputStream stream = new FileOutputStream(output)) {
                writer.write(stream);
                committed = true;
            } finally {
                if (!committed) output.delete();
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
