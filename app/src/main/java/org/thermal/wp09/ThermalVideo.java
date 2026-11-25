package org.thermal.wp09;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.SystemClock;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.thermal.wp09.ThermalFrame.*;

/** The AVC sample and its measurement plane share an explicit presentation timestamp. Worker-thread only. */
final class ThermalVideo implements Closeable {
    private final File video;
    private File uniformVideo;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private MovieArchive.Writer archive;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private static final class PendingFrame {
        final ThermalCapture capture;
        final long timeNs;
        PendingFrame(ThermalCapture capture, long timeNs) { this.capture = capture; this.timeNs = timeNs; }
    }

    private final Map<Long, PendingFrame> pending = new LinkedHashMap<>();
    private final ArrayList<PendingFrame> warmup = new ArrayList<>(MobileImage.WINDOW);
    private MobileImage movieImage;
    private final int[] pixels = new int[PIXELS], lut = new int[256];
    private int track = -1;
    private long originNs = -1, lastInput = -1, firstOutput = -1, lastOutput = -1;
    private long firstEventNs, lastEventNs;
    private boolean eos;

    ThermalVideo(File directory, boolean fullMaps) throws IOException {
        video = File.createTempFile("wp09-video-", ".mp4", directory);
        try {
            archive = new MovieArchive.Writer(directory);
            muxer = new MediaMuxer(video.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            // Give full maps sufficient bits for fine temperature gradients.
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            int bitRate = codec.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .getVideoCapabilities().getBitrateRange().clamp(fullMaps ? 6_000_000 : 2_000_000);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 50);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            if (Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    void offer(ThermalCapture frame, long timeNs) throws IOException {
        offer(frame, timeNs, false);
    }

    void offer(ThermalCapture frame, long timeNs, boolean fromBurst) throws IOException {
        if (frame.frame.thermalOutput && !archive.uniform) {
            // Every direct exposure has its own UVC clock. Gaps hold the preceding
            // AVC picture and its measurement index for exactly the same interval.
            encode(frame, timeNs, false);
            return;
        }
        if (fromBurst) {
            archive.uniform = true;
            if (movieImage == null) {
                warmup.add(new PendingFrame(frame, timeNs));
                if (warmup.size() == MobileImage.WINDOW) encodeWarmup();
                return;
            }
            frame = frame.forMovie(movieImage);
        }
        encode(frame, timeNs, fromBurst);
    }

    private void encodeWarmup() throws IOException {
        movieImage = MobileImage.fromStart(warmup.size(), i -> warmup.get(i).capture.frame);
        for (int i = 0; i < warmup.size(); i++) {
            PendingFrame sample = warmup.set(i, null);
            encode(sample.capture.forMovie(movieImage), sample.timeNs, true);
        }
        warmup.clear();
    }

    private void encode(ThermalCapture frame, long timeNs, boolean fromBurst) throws IOException {
        drain(0);
        int slot = codec.dequeueInputBuffer(5000);
        if (slot < 0) {
            long deadline = SystemClock.elapsedRealtime() + 1000;
            do {
                drain(5000);
                slot = codec.dequeueInputBuffer(5000);
            } while (slot < 0 && SystemClock.elapsedRealtime() < deadline);
            if (slot < 0) throw new IOException("Encoder stalled");
        }
        if (originNs < 0) originNs = timeNs;
        // Several unpaired raw exposures can have the same known file event. A regular
        // temporary codec clock keeps them distinct until the final duration is available.
        long pts = fromBurst ? (lastInput < 0 ? 0 : lastInput + 20_000)
                : Math.max(lastInput + 1, (timeNs - originNs) / 1000);
        frame.render(pixels, lut);
        try (Image image = codec.getInputImage(slot)) {
            if (image == null) throw new IOException("Encoder has no YUV input");
            fillImage(image);
        }
        // Input frames held by an AVC encoder are bounded. Treat a stalled codec as a recording failure.
        if (pending.size() >= 64) throw new IOException("No video output");
        pending.put(pts, new PendingFrame(frame, timeNs));
        lastEventNs = timeNs;
        lastInput = pts;
        codec.queueInputBuffer(slot, 0, PIXELS * 3 / 2, pts, 0);
        drain(0);
    }

    private void fillImage(Image image) throws IOException {
        Image.Plane[] planes = image.getPlanes();
        Image.Plane y = planes[0], u = planes[1], v = planes[2];
        ByteBuffer yb = y.getBuffer(), ub = u.getBuffer(), vb = v.getBuffer();
        if (!PixelOps.yuv(pixels, yb, yb.position(), y.getRowStride(), y.getPixelStride(),
                ub, ub.position(), u.getRowStride(), u.getPixelStride(),
                vb, vb.position(), v.getRowStride(), v.getPixelStride())) {
            throw new IOException("Unsupported encoder plane layout");
        }
    }

    private void drain(long waitUs) throws IOException {
        for (;;) {
            int slot = codec.dequeueOutputBuffer(info, waitUs);
            if (slot == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
            } else if (slot >= 0) {
                try {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        long pts = info.presentationTimeUs;
                        PendingFrame frame = pending.remove(pts);
                        if (frame == null || pts <= lastOutput || track < 0) throw new IOException("Video timing mismatch");
                        if (firstOutput < 0) {
                            firstOutput = pts;
                            firstEventNs = frame.timeNs;
                        }
                        lastOutput = pts;
                        info.presentationTimeUs = pts - firstOutput;
                        muxer.writeSampleData(track, codec.getOutputBuffer(slot), info);
                        archive.add(frame.capture, (frame.timeNs - firstEventNs) / 1000);
                    }
                    eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                } finally {
                    codec.releaseOutputBuffer(slot, false);
                }
                if (eos) return;
            }
            waitUs = 0;
        }
    }

    boolean hasInput() { return lastInput >= 0 || !warmup.isEmpty(); }

    void finish(long stopNs) throws IOException {
        if (!warmup.isEmpty()) encodeWarmup();
        long deadline = SystemClock.elapsedRealtime() + 5000;
        boolean sent = false;
        long end = archive.uniform ? lastInput + 20_000 : Math.max(lastInput + 20_000, (stopNs - originNs) / 1000);
        while (!eos && SystemClock.elapsedRealtime() < deadline) {
            if (!sent) {
                int slot = codec.dequeueInputBuffer(10_000);
                if (slot >= 0) {
                    codec.queueInputBuffer(slot, 0, 0, end, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    sent = true;
                }
            }
            drain(10_000);
        }
        if (!eos || lastOutput < 0) throw new IOException("Incomplete recording");
        MediaCodec.BufferInfo last = new MediaCodec.BufferInfo();
        last.set(0, 0, end - firstOutput, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        muxer.writeSampleData(track, ByteBuffer.allocate(0), last);
        archive.durationUs = archive.uniform
                ? Math.max((stopNs - firstEventNs) / 1000, (lastEventNs - firstEventNs) / 1000 + 20_000)
                : last.presentationTimeUs;
        muxer.stop();
        muxer.release();
        muxer = null;
        if (archive.uniform) {
            uniformVideo = File.createTempFile("wp09-paced-", ".mp4", video.getParentFile());
            MovieArchive.remuxUniform(video, uniformVideo, archive.count, archive.durationUs);
        }
    }

    void writeTo(OutputStream out) throws IOException {
        File output = uniformVideo == null ? video : uniformVideo;
        MovieArchive.copy(output, out);
        archive.appendTo(out, output);
    }

    @Override public void close() {
        if (codec != null) {
            try { codec.release(); } catch (RuntimeException ignored) {}
        }
        if (muxer != null) {
            try { muxer.release(); } catch (RuntimeException ignored) {}
        }
        if (archive != null) archive.close();
        pending.clear();
        warmup.clear();
        video.delete();
        if (uniformVideo != null) uniformVideo.delete();
    }
}
