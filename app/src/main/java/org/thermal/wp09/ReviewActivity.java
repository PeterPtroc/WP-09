package org.thermal.wp09;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.thermal.wp09.InstrumentUi.*;

public final class ReviewActivity extends AppCompatActivity {
    private final HandlerThread thread = new HandlerThread("WP09 album reader");
    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler io;
    private MovieArchive.Reader reader; // accessed on io
    private final AtomicInteger revision = new AtomicInteger();
    private Uri uri;
    private ThermalView thermal;
    private InspectionPanel inspection;
    private TextView status, position, durationLabel;
    private FrameLayout stage;
    private LinearLayout transport;
    private SeekBar scrub;
    private MaterialButton play, prev, next;
    private VideoView ordinaryVideo;
    private int count, index;
    private long durationUs, ptsUs, captureUs, originMs, lastPanelMs;
    private long displayedSecond = -1;
    private boolean playing, seeking, evenPlayback;
    private final Runnable endPlayback = this::pause;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        thread.start();
        io = new Handler(thread.getLooper());
        boolean movie = getIntent().getBooleanExtra("movie", false);
        String path = getIntent().getStringExtra("uri");
        if (path == null) { finish(); return; }
        uri = Uri.parse(path);
        LinearLayout root = row(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        landscape(this, root);
        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(0, -1, 1));
        thermal = new ThermalView(this);
        stage.addView(thermal);

        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(PANEL);
        root.addView(sidebar, new LinearLayout.LayoutParams(dp(this, 226), -1));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        sidebar.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(this, 8), dp(this, 5), dp(this, 8), dp(this, 5));
        scroll.addView(panel);
        LinearLayout nav = row(this);
        panel.addView(nav);
        action(nav, "相册", R.drawable.ic_back, this::finish);
        TextView type = text(this, movie ? "录像" : "照片", 17, TEXT);
        type.setGravity(Gravity.CENTER);
        nav.addView(type, new LinearLayout.LayoutParams(0, -2, 1));
        status = text(this, "读取中…", 12, MUTED);
        status.setPadding(dp(this, 6), dp(this, 6), dp(this, 6), dp(this, 4));
        panel.addView(status);
        inspection = new InspectionPanel(this, thermal);
        inspection.setVisibility(View.GONE);
        panel.addView(inspection);

        transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.VERTICAL);
        transport.setPadding(dp(this, 8), 0, dp(this, 8), dp(this, 5));
        transport.setVisibility(View.GONE);
        sidebar.addView(transport);
        LinearLayout times = row(this);
        times.setPadding(dp(this, 6), 0, dp(this, 6), 0);
        transport.addView(times);
        position = clockLabel(times, Gravity.START);
        durationLabel = clockLabel(times, Gravity.END);
        scrub = new SeekBar(this);
        scrub.setProgressDrawable(getDrawable(R.drawable.seek_track));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setColor(ACCENT);
        thumb.setSize(dp(this, 10), dp(this, 14));
        scrub.setThumb(thumb);
        scrub.setSplitTrack(false);
        scrub.setMax(10000);
        scrub.setContentDescription("录像时间");
        transport.addView(scrub, new LinearLayout.LayoutParams(-1, dp(this, 36)));
        LinearLayout buttons = row(this);
        transport.addView(buttons);
        prev = action(buttons, "", R.drawable.ic_previous, () -> step(-1));
        play = action(buttons, "", R.drawable.ic_play, this::togglePlay);
        next = action(buttons, "", R.drawable.ic_next, () -> step(1));
        prev.setContentDescription("上一帧");
        play.setContentDescription("播放");
        next.setContentDescription("下一帧");
        scrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { pause(); seeking = true; inspection.reset(); }
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user && count > 0) request(-1, (long) (durationUs * (progress / 10000.0)));
            }
            public void onStopTrackingTouch(SeekBar bar) { seeking = false; }
        });
        io.post(() -> load(movie));
    }

    private TextView clockLabel(LinearLayout times, int gravity) {
        TextView value = text(this, "00:00", 12, MUTED);
        value.setTypeface(Typeface.MONOSPACE);
        value.setSingleLine();
        value.setGravity(gravity | Gravity.CENTER_VERTICAL);
        times.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        return value;
    }

    private void load(boolean movie) {
        try {
            if (movie) {
                AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(uri, "r");
                if (fd == null) throw new IOException("Missing file");
                try { reader = new MovieArchive.Reader(fd); }
                catch (IOException | RuntimeException e) { fd.close(); throw e; }
                int frames = reader.size();
                long duration = reader.durationUs;
                boolean even = reader.evenPlayback();
                if (frames > 0) {
                    main.post(() -> {
                        if (isDestroyed()) return;
                        count = frames;
                        durationUs = duration;
                        durationLabel.setText(clock((duration + 999_999) / 1_000_000));
                        evenPlayback = even;
                        transport.setVisibility(View.VISIBLE);
                        request(0, -1);
                    });
                } else main.post(() -> plainVideo("普通录像 · 无温度数据"));
            } else {
                ThermalCapture capture;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Missing image");
                    capture = RadiometricPng.read(in);
                }
                if (capture == null) plainPhoto("普通照片 · 无温度数据");
                else main.post(() -> { if (!isDestroyed()) display(capture, 0); });
            }
        } catch (IOException | RuntimeException e) {
            if (movie) main.post(() -> plainVideo("温度数据不可读 · 普通播放"));
            else plainPhoto("温度数据不可读 · 普通预览");
        }
    }

    private void plainPhoto(String reason) {
        try {
            Bitmap image = CaptureLibrary.image(this, uri, 2048);
            main.post(() -> {
                if (isDestroyed()) return;
                status.setText(reason);
                ImageView picture = new ImageView(this);
                picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
                picture.setImageBitmap(image);
                stage.addView(picture);
            });
        } catch (IOException | RuntimeException e) {
            main.post(() -> status.setText("照片读取失败"));
        }
    }

    private void plainVideo(String reason) {
        if (isDestroyed()) return;
        status.setVisibility(View.VISIBLE);
        status.setText(reason);
        transport.setVisibility(View.GONE);
        inspection.setVisibility(View.GONE);
        ordinaryVideo = new VideoView(this);
        stage.addView(ordinaryVideo, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        MediaController controls = new MediaController(this);
        ordinaryVideo.setMediaController(controls);
        controls.setAnchorView(ordinaryVideo);
        ordinaryVideo.setOnPreparedListener(mp -> { ordinaryVideo.seekTo(1); controls.show(); });
        ordinaryVideo.setOnErrorListener((mp, what, extra) -> { status.setText("录像读取失败"); return true; });
        ordinaryVideo.setVideoURI(uri);
    }

    private void step(int delta) {
        pause();
        inspection.reset();
        request(Math.max(0, Math.min(count - 1, index + delta)), -1);
    }

    private void togglePlay() {
        if (playing) { pause(); return; }
        long start = index == count - 1 ? 0 : ptsUs;
        if (start == 0) inspection.reset();
        originMs = SystemClock.uptimeMillis() - start / 1000;
        playing = true;
        play.setContentDescription("暂停");
        play.setIconResource(R.drawable.ic_pause);
        request(start == 0 ? 0 : index, -1);
    }

    private void pause() {
        if (playing && thermal.capture() != null) inspection.refresh(captureUs / 1000, true);
        playing = false;
        revision.incrementAndGet();
        main.removeCallbacks(endPlayback);
        if (play != null) {
            play.setContentDescription("播放");
            play.setIconResource(R.drawable.ic_play);
        }
    }

    private void request(int desired, long timeUs) {
        int token = revision.incrementAndGet();
        boolean playback = playing;
        long startMs = originMs;
        io.post(() -> {
            if (token != revision.get()) return;
            try {
                int target = desired >= 0 ? desired : reader.atPlayTime(timeUs);
                int i = playback ? Math.max(target, reader.atPlayTime((SystemClock.uptimeMillis() - startMs) * 1000)) : target;
                long time = reader.playUs(i);
                long sampled = reader.captureUs(i);
                ThermalCapture c = reader.frame(i);
                Runnable present = () -> {
                    if (token != revision.get() || isDestroyed()) return;
                    index = i;
                    ptsUs = time;
                    captureUs = sampled;
                    display(c, sampled);
                    prev.setEnabled(i > 0);
                    next.setEnabled(i + 1 < count);
                    if (!seeking) scrub.setProgress(i == count - 1 ? 10000 : (int) (time * 10000.0 / durationUs));
                    long second = i == count - 1 ? (durationUs + 999_999) / 1_000_000 : time / 1_000_000;
                    if (second != displayedSecond) {
                        position.setText(clock(second));
                        displayedSecond = second;
                    }
                    if (playing && i + 1 < count) request(i + 1, -1);
                    else if (playing) main.postAtTime(endPlayback, startMs + (durationUs + 999) / 1000);
                };
                // Decode one frame ahead and present it on an absolute monotonic deadline.
                if (playback) main.postAtTime(present, startMs + (time + 999) / 1000);
                else main.post(present);
            } catch (IOException | RuntimeException e) {
                main.post(() -> {
                    if (token != revision.get() || isDestroyed()) return;
                    pause();
                    thermal.display(null);
                    plainVideo("温度数据不可读 · 普通播放");
                });
            }
        });
    }

    private static String clock(long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void display(ThermalCapture c, long time) {
        thermal.display(c);
        boolean measured = c.frame.hasTemperature();
        status.setVisibility(measured ? View.GONE : View.VISIBLE);
        if (!measured) label(status, "无温度数据");
        inspection.setVisibility(measured ? View.VISIBLE : View.GONE);
        long now = SystemClock.uptimeMillis();
        if (!playing || evenPlayback || now - lastPanelMs >= 120) {
            inspection.refresh(time / 1000, true);
            lastPanelMs = now;
        }
    }

    @Override protected void onStop() {
        if (playing) pause();
        if (ordinaryVideo != null) ordinaryVideo.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        pause();
        if (ordinaryVideo != null) ordinaryVideo.stopPlayback();
        io.post(() -> {
            if (reader != null) try { reader.close(); } catch (IOException ignored) {}
        });
        thread.quitSafely();
        super.onDestroy();
    }
}
