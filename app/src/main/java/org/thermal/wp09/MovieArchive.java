package org.thermal.wp09;

import android.content.res.AssetFileDescriptor;
import android.media.MediaExtractor;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** ISO BMFF user-extension box. The fixed-width on-disk index supports seeking without loading the movie. */
final class MovieArchive {
    private static final int UUID = 0x75756964, ENTRY = 20;
    private static final long ID_HIGH = 0x29937dbe87254c0aL, ID_LOW = 0xa712f577c121b848L;
    private static final long EVENTS_HIGH = 0x3b9585a9c6e34f41L, EVENTS_LOW = 0xb252ede58e9a79c2L;
    private static final long DISPLAY_HIGH = 0x9ebdf3398d3a4524L, DISPLAY_LOW = 0xa156598f9e29ee65L;
    private static final long PLANE_HIGH = 0x5d099768a2184140L, PLANE_LOW = 0x93bedd598bf84407L;
    private static final long CONTRAST_HIGH = 0x8551eb625aa84ae8L, CONTRAST_LOW = 0xbc84881fea5819a7L;
    private static final long TONE_HIGH = 0x1eb06fb257db4dc6L, TONE_LOW = 0xb205f1bbd371e299L;
    private static final long IRON_HIGH = 0xb70c50db2ef34194L, IRON_LOW = 0x8b778266a314fa95L;
    private static final int EVENT = Long.BYTES + ThermalPayload.SOURCE_BYTES;
    private static final int VERSION = 1;

    static final class Writer implements Closeable {
        private final File samples, index;
        private final DataOutputStream data, entries;
        private final ThermalPayload.Encoder encoder = new ThermalPayload.Encoder();
        private final ByteBuffer event = ByteBuffer.allocate(EVENT + ThermalPayload.IRON_BYTES);
        boolean uniform;
        private boolean hasThermalOutput, hasContrast, hasLockedTone, hasIron;
        long durationUs;
        private long bytes;
        int count;

        Writer(File directory) throws IOException {
            samples = File.createTempFile("wp09-planes-", ".tmp", directory);
            try {
                index = File.createTempFile("wp09-index-", ".tmp", directory);
                try {
                    data = stream(samples);
                    try {
                        entries = stream(index);
                    } catch (IOException e) {
                        data.close();
                        throw e;
                    }
                } catch (IOException e) {
                    index.delete();
                    throw e;
                }
            } catch (IOException e) {
                samples.delete();
                encoder.close();
                throw e;
            }
        }

        void add(ThermalCapture capture, long captureUs) throws IOException {
            int size = encoder.encode(capture);
            data.write(encoder.bytes(), 0, size);
            entries.writeInt(size);
            event.clear();
            event.putLong(captureUs);
            ThermalPayload.putSource(event, capture.frame);
            ThermalPayload.putIron(event, capture);
            entries.write(event.array());
            int flags = capture.scale.locked() ? ThermalPayload.LOCKED : ThermalPayload.AUTO;
            if (capture.frame.thermalOutput) {
                flags |= 2;
                hasThermalOutput = true;
            }
            hasIron |= capture.ironEnhanced;
            hasContrast |= capture.paletteContrast() != ColorScale.NORMAL;
            hasLockedTone |= capture.lockedTone != 0;
            entries.writeInt(flags | (capture.paletteContrast() << 2) | (capture.lockedTone << 10));
            bytes += size;
            count++;
        }

        void appendTo(OutputStream target, File movie) throws IOException {
            entries.flush();
            data.flush();
            DataOutputStream out = new DataOutputStream(target);
            // An independent optional box preserves the original clock and device markers.
            // v0.9.2 readers skip it and can still open the standard v1 measurement index.
            out.writeInt(1);
            out.writeInt(UUID);
            out.writeLong(44L + (long) EVENT * count);
            out.writeLong(EVENTS_HIGH);
            out.writeLong(EVENTS_LOW);
            out.writeInt(VERSION);
            out.writeInt(count);
            out.writeInt(uniform ? 1 : 0);
            try (DataInputStream rows = readIndex()) {
                for (int i = 0; i < count; i++) {
                    rows.readInt();
                    rows.readFully(event.array());
                    rows.readInt();
                    out.write(event.array(), 0, EVENT);
                }
            }
            // One byte per sample makes an explicitly locked range unambiguous even
            // when it happens to equal that frame's automatic limits in a v1 record.
            writeFlags(out, DISPLAY_HIGH, DISPLAY_LOW, 0, 1);
            if (hasThermalOutput) writeFlags(out, PLANE_HIGH, PLANE_LOW, 1, 1);
            if (hasContrast) writeFlags(out, CONTRAST_HIGH, CONTRAST_LOW, 2, 255);
            if (hasLockedTone) writeFlags(out, TONE_HIGH, TONE_LOW, 10, 255);
            if (hasIron) {
                out.writeInt(1);
                out.writeInt(UUID);
                out.writeLong(36L + (long) count * ThermalPayload.IRON_BYTES);
                out.writeLong(IRON_HIGH);
                out.writeLong(IRON_LOW);
                out.writeInt(VERSION);
                try (DataInputStream rows = readIndex()) {
                    for (int i = 0; i < count; i++) {
                        rows.readInt();
                        rows.readFully(event.array());
                        rows.readInt();
                        out.write(event.array(), EVENT, ThermalPayload.IRON_BYTES);
                    }
                }
            }
            out.writeInt(1); // extended box size
            out.writeInt(UUID);
            out.writeLong(48L + (long) ENTRY * count + bytes);
            out.writeLong(ID_HIGH);
            out.writeLong(ID_LOW);
            out.writeInt(VERSION);
            out.writeInt(count);
            // MPEG4Writer can quantize or coalesce adjacent sample durations. Re-read the
            // finished AVC track so that even long recordings use the player's exact timestamps.
            MediaExtractor track = new MediaExtractor();
            try (DataInputStream sizes = readIndex()) {
                track.setDataSource(movie.getAbsolutePath());
                track.selectTrack(0);
                MediaFormat format = track.getTrackFormat(0);
                out.writeLong(format.containsKey(MediaFormat.KEY_DURATION)
                        ? format.getLong(MediaFormat.KEY_DURATION) : durationUs);
                long offset = 0, previous = -1;
                for (int i = 0; i < count; i++) {
                    long pts = track.getSampleTime();
                    if (pts <= previous) throw new IOException("Missing or unordered AVC sample");
                    int length = sizes.readInt();
                    sizes.readFully(event.array());
                    sizes.readInt();
                    out.writeLong(pts);
                    out.writeLong(offset);
                    out.writeInt(length);
                    offset += length;
                    previous = pts;
                    track.advance();
                }
                if (track.getSampleTime() >= 0) throw new IOException("Unpaired AVC sample");
            } finally {
                track.release();
            }
            copy(samples, out);
        }

        private DataInputStream readIndex() throws IOException {
            return new DataInputStream(new BufferedInputStream(new FileInputStream(index)));
        }

        private void writeFlags(DataOutputStream out, long high, long low, int shift, int mask) throws IOException {
            out.writeInt(1);
            out.writeInt(UUID);
            out.writeLong(36L + count);
            out.writeLong(high);
            out.writeLong(low);
            out.writeInt(VERSION);
            try (DataInputStream rows = readIndex()) {
                for (int i = 0; i < count; i++) {
                    rows.readInt();
                    rows.readFully(event.array());
                    out.writeByte((rows.readInt() >> shift) & mask);
                }
            }
        }

        @Override public void close() {
            encoder.close();
            try { data.close(); } catch (IOException ignored) {}
            try { entries.close(); } catch (IOException ignored) {}
            samples.delete();
            index.delete();
        }

        private static DataOutputStream stream(File file) throws IOException {
            return new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file), 65536));
        }
    }

    static final class Reader implements Closeable {
        private final AssetFileDescriptor descriptor;
        private final FileInputStream input;
        private final FileChannel channel;
        private final ByteBuffer scratch = ByteBuffer.allocate(EVENT);
        private final ByteBuffer ironEntry = ByteBuffer.allocate(ThermalPayload.IRON_BYTES);
        private static final int PAGE_ENTRIES = 256;
        private final ByteBuffer indexPage = ByteBuffer.allocate(ENTRY * PAGE_ENTRIES);
        private final ByteBuffer packed = ByteBuffer.allocate(ThermalPayload.MAX_PACKED);
        private final ThermalPayload.Decoder decoder = new ThermalPayload.Decoder();
        private int pageStart = -1, pageEnd;
        private long index, data, end;
        private long events, displayFlags, displayCount, planeFlags, planeCount;
        private long contrastFlags, contrastCount, toneFlags, toneCount;
        private long ironEntries, ironBytes;
        private int eventCount;
        private boolean uniform, legacyBursts;
        private MobileImage movieImage;
        long durationUs;
        private int count;

        Reader(AssetFileDescriptor descriptor) throws IOException {
            this.descriptor = descriptor;
            input = new FileInputStream(descriptor.getFileDescriptor());
            channel = input.getChannel();
            try {
                long base = descriptor.getStartOffset(), length = descriptor.getLength();
                long limit = length < 0 ? channel.size() : Math.min(channel.size(), base + length);
                scan(base, limit);
                if (events != 0 && eventCount != count) throw new IOException("Mismatched MP4 event count");
                if (displayFlags != 0 && displayCount != count) throw new IOException("Mismatched MP4 display count");
                if (planeFlags != 0 && planeCount != count) throw new IOException("Mismatched MP4 plane count");
                if (contrastFlags != 0 && contrastCount != count) throw new IOException("Mismatched MP4 contrast count");
                if (toneFlags != 0 && toneCount != count) throw new IOException("Mismatched MP4 tone count");
                if (ironEntries != 0 && ironBytes != (long) count * ThermalPayload.IRON_BYTES)
                    throw new IOException("Mismatched MP4 iron curves");
                legacyBursts = events == 0 && looksLikeLegacyBurst();
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }

        private void scan(long pos, long limit) throws IOException {
            while (limit - pos >= 8) {
                ByteBuffer head = read(pos, 8);
                long size = Integer.toUnsignedLong(head.getInt());
                int type = head.getInt(), header = 8;
                if (size == 1) {
                    size = read(pos + 8, 8).getLong();
                    header = 16;
                } else if (size == 0) size = limit - pos;
                if (size < header || size > limit - pos) throw new IOException("Invalid MP4 size");
                if (type == UUID && size >= header + 32) {
                    ByteBuffer id = read(pos + header, 16);
                    long high = id.getLong(), low = id.getLong();
                    if (high == ID_HIGH && low == ID_LOW) {
                        ByteBuffer info = read(pos + header + 16, 16);
                        if (info.getInt() == VERSION) {
                            count = info.getInt();
                            durationUs = info.getLong();
                            index = pos + header + 32;
                            end = pos + size;
                            if (durationUs <= 0 || count <= 0 || count > (end - index) / ENTRY) throw new IOException("Invalid MP4 index");
                            data = index + (long) count * ENTRY;
                        }
                    }
                }
                if (type == UUID && size >= header + 28) {
                    ByteBuffer id = read(pos + header, 16);
                    if (id.getLong() == EVENTS_HIGH && id.getLong() == EVENTS_LOW) {
                        ByteBuffer info = read(pos + header + 16, 12);
                        if (info.getInt() == VERSION) {
                            eventCount = info.getInt();
                            uniform = (info.getInt() & 1) != 0;
                            if (eventCount <= 0 || size != header + 28L + (long) EVENT * eventCount)
                                throw new IOException("Invalid MP4 events");
                            events = pos + header + 28;
                        }
                    }
                }
                if (type == UUID && size >= header + 20) {
                    ByteBuffer id = read(pos + header, 16);
                    long high = id.getLong(), low = id.getLong();
                    boolean plane = high == PLANE_HIGH && low == PLANE_LOW;
                    boolean contrast = high == CONTRAST_HIGH && low == CONTRAST_LOW;
                    boolean tone = high == TONE_HIGH && low == TONE_LOW;
                    boolean iron = high == IRON_HIGH && low == IRON_LOW;
                    if ((plane || contrast || tone || iron || (high == DISPLAY_HIGH && low == DISPLAY_LOW))
                            && read(pos + header + 16, 4).getInt() == VERSION) {
                        if (plane) {
                            planeFlags = pos + header + 20;
                            planeCount = size - header - 20;
                        } else if (contrast) {
                            contrastFlags = pos + header + 20;
                            contrastCount = size - header - 20;
                        } else if (iron) {
                            ironEntries = pos + header + 20;
                            ironBytes = size - header - 20;
                        } else if (tone) {
                            toneFlags = pos + header + 20;
                            toneCount = size - header - 20;
                        } else {
                            displayFlags = pos + header + 20;
                            displayCount = size - header - 20;
                        }
                    }
                }
                pos += size;
            }
        }

        int size() { return count; }

        long timeUs(int i) throws IOException { return entry(i).getLong(); }

        long captureUs(int i) throws IOException {
            return events == 0 ? timeUs(i) : read(events + (long) i * EVENT, Long.BYTES).getLong();
        }

        boolean evenPlayback() { return uniform || legacyBursts; }

        long playUs(int i) throws IOException {
            return legacyBursts ? evenTime(i, durationUs, count) : timeUs(i);
        }

        int atPlayTime(long ptsUs) throws IOException {
            // The AVC track in an old file still carries its original, irregular clock.
            if (!legacyBursts) return atTime(ptsUs);
            int lo = 0, hi = count - 1;
            while (lo < hi) {
                int mid = lo + (hi - lo + 1) / 2;
                if (playUs(mid) <= ptsUs) lo = mid;
                else hi = mid - 1;
            }
            return lo;
        }

        private boolean looksLikeLegacyBurst() throws IOException {
            if (count < 8) return false;
            // Recognize the old recorder's eight-frame 50 Hz groups, separated by a file read.
            int n = Math.min(32, count);
            long previous = timeUs(0);
            boolean gap = false;
            for (int i = 1; i < n; i++) {
                long current = timeUs(i), delta = current - previous;
                if (i % 8 == 0 && delta >= 100_000) gap = true;
                else if (delta < 8_000 || delta > 40_000) return false;
                previous = current;
            }
            if (!gap && !(count == 8 && durationUs - previous >= 100_000)) return false;
            return readCapture(0).frame.hasFirmwareImage() && readCapture(n - 1).frame.hasFirmwareImage();
        }

        int atTime(long ptsUs) throws IOException {
            int lo = 0, hi = count - 1;
            if (pageStart >= 0 && ptsUs >= indexPage.getLong(0)
                    && (pageEnd == count || ptsUs <= indexPage.getLong((pageEnd - pageStart - 1) * ENTRY))) {
                lo = pageStart;
                hi = pageEnd - 1;
            }
            while (lo < hi) {
                int mid = lo + (hi - lo + 1) / 2;
                if (timeUs(mid) <= ptsUs) lo = mid;
                else hi = mid - 1;
            }
            return lo;
        }

        ThermalCapture frame(int i) throws IOException {
            ThermalCapture capture = readCapture(i);
            if (!evenPlayback() || !capture.frame.hasPixelTemperatures() || capture.frame.thermalOutput) return capture;
            if (movieImage == null) movieImage = MobileImage.fromStart(count, index -> readCapture(index).frame);
            return capture.forMovie(movieImage);
        }

        private ThermalCapture readCapture(int i) throws IOException {
            ByteBuffer item = entry(i);
            item.getLong();
            long offset = item.getLong();
            int size = item.getInt();
            if (size <= 0 || size > ThermalPayload.MAX_PACKED || offset < 0 || offset > end - data - size) {
                throw new IOException("Invalid MP4 frame");
            }
            packed.clear();
            packed.limit(size);
            readFully(packed, data + offset);
            int display = displayFlags == 0 ? ThermalPayload.LEGACY_DISPLAY : read(displayFlags + i, 1).get() & 255;
            int plane = planeFlags == 0 ? 0 : read(planeFlags + i, 1).get() & 255;
            if (plane > 1) throw new IOException("Unknown temperature plane");
            int contrast = contrastFlags == 0 ? ColorScale.NORMAL : read(contrastFlags + i, 1).get() & 255;
            int tone = toneFlags == 0 ? 0 : read(toneFlags + i, 1).get() & 255;
            if (ironEntries != 0) {
                ironEntry.clear();
                readFully(ironEntry, ironEntries + (long) i * ThermalPayload.IRON_BYTES);
                ironEntry.flip();
            }
            // Event metadata borrows scratch until the decoder consumes it.
            ByteBuffer source = events == 0 ? null : read(events + (long) i * EVENT + 8, ThermalPayload.SOURCE_BYTES);
            return decoder.decode(packed.array(), size, source, display, plane == 1, contrast, tone,
                    ironEntries == 0 ? null : ironEntry);
        }

        private ByteBuffer entry(int i) throws IOException {
            if (i < 0 || i >= count) throw new IOException("Missing MP4 frame");
            if (i < pageStart || i >= pageEnd) {
                int start = i / PAGE_ENTRIES * PAGE_ENTRIES;
                int length = Math.min(PAGE_ENTRIES, count - start);
                indexPage.clear();
                indexPage.limit(length * ENTRY);
                readFully(indexPage, index + (long) start * ENTRY);
                indexPage.flip();
                pageStart = start;
                pageEnd = start + length;
            }
            indexPage.position((i - pageStart) * ENTRY);
            return indexPage;
        }

        private ByteBuffer read(long pos, int n) throws IOException {
            scratch.clear();
            scratch.limit(n);
            readFully(scratch, pos);
            scratch.flip();
            return scratch;
        }

        private void readFully(ByteBuffer dest, long pos) throws IOException {
            while (dest.hasRemaining()) {
                int n = channel.read(dest, pos);
                if (n <= 0) throw new EOFException();
                pos += n;
            }
        }

        @Override public void close() throws IOException {
            decoder.close();
            try { input.close(); } finally { descriptor.close(); }
        }
    }

    static long evenTime(int i, long durationUs, int count) {
        return (durationUs / count) * i + ((durationUs % count) * i) / count;
    }

    /** Copy AVC access units in presentation order. The final hold occupies exactly one interval. */
    static void remuxUniform(File source, File destination, int count, long durationUs) throws IOException {
        MediaExtractor input = new MediaExtractor();
        try {
            input.setDataSource(source.getAbsolutePath());
            input.selectTrack(0);
            MediaFormat format = input.getTrackFormat(0);
            if (!MediaFormat.MIMETYPE_VIDEO_AVC.equals(format.getString(MediaFormat.KEY_MIME)))
                throw new IOException("Expected a WP-09 AVC track");
            MediaMuxer output = new MediaMuxer(destination.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            try {
                int track = output.addTrack(format);
                output.start();
                int size = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
                ByteBuffer packet = ByteBuffer.allocateDirect(Math.max(size, ThermalFrame.PIXELS * 4));
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long previous = -1;
                for (int i = 0; i < count; i++) {
                    packet.clear();
                    int length = input.readSampleData(packet, 0);
                    if (length <= 0 || length > packet.capacity() || input.getSampleTime() <= previous)
                        throw new IOException("Missing or unordered AVC packet");
                    previous = input.getSampleTime();
                    int flags = (input.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    info.set(0, length, evenTime(i, durationUs, count), flags);
                    output.writeSampleData(track, packet, info);
                    input.advance();
                }
                if (input.getSampleTime() >= 0) throw new IOException("Unpaired AVC packet");
                info.set(0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                output.writeSampleData(track, packet, info);
                output.stop();
            } finally { output.release(); }
        } finally { input.release(); }
    }

    static void copy(File file, OutputStream out) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
        }
    }
}
