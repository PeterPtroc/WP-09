package org.thermal.wp09;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.thermal.wp09.ThermalFrame.HEIGHT;
import static org.thermal.wp09.ThermalFrame.PIXELS;
import static org.thermal.wp09.ThermalFrame.WIDTH;

/** Shared v1 record for PNG and MP4. All numbers, including the float plane, are big-endian. */
final class ThermalPayload {
    private static final int MAGIC = 0x57505431; // WPT1
    private static final int HEADER = 52;
    private static final int MAX_RAW = HEADER + PIXELS * 8 + 2 + BlindPixels.MAX_USER * 2;
    static final int MAX_PACKED = MAX_RAW + 1024;
    static final int SOURCE_BYTES = 24;
    static final int IRON_BYTES = 1 + 2 * Float.BYTES;
    static final int LEGACY_DISPLAY = -1, AUTO = 0, LOCKED = 1;

    // Baked luma is viewable with the ordinary palette even by older readers.
    static void putIron(ByteBuffer b, ThermalCapture capture) {
        b.put((byte) (capture.ironEnhanced ? capture.scale.contrast : 0));
        b.putFloat(capture.middleLow).putFloat(capture.middleHigh);
    }

    // Optional container metadata. The primary WPT1 record remains readable by v0.9.2.
    static void putSource(ByteBuffer b, ThermalFrame frame) {
        b.putInt(frame.firstColumn);
        ThermalFrame.Markers m = frame.firmware;
        b.putInt(m == null ? -1 : m.cold).putInt(m == null ? -1 : m.hot);
        b.putFloat(m == null ? Float.NaN : m.minimum).putFloat(m == null ? Float.NaN : m.maximum)
                .putFloat(m == null ? Float.NaN : m.center);
    }

    private static ThermalFrame.Markers getMarkers(ByteBuffer b) throws IOException {
        int cold = b.getInt(), hot = b.getInt();
        float low = b.getFloat(), high = b.getFloat(), center = b.getFloat();
        if (cold == -1 && hot == -1 && Float.isNaN(low) && Float.isNaN(high) && Float.isNaN(center)) return null;
        if (!validAnchor(cold, low) || !validAnchor(hot, high) || !Float.isFinite(center)
                || low > center || high < center) throw invalid();
        return new ThermalFrame.Markers(cold, hot, low, high, center);
    }

    static final class Encoder implements AutoCloseable {
        private final Deflater zip = new Deflater(Deflater.BEST_SPEED);
        private final ByteBuffer buffer = ByteBuffer.allocate(MAX_RAW).order(ByteOrder.BIG_ENDIAN);
        private final byte[] packed = new byte[MAX_PACKED];

        // Writes a single zlib member into reusable storage, valid until the next call.
        int encode(ThermalCapture capture) throws IOException {
            ThermalFrame f = capture.frame;
            buffer.clear();
            int kind = f.readings != null && f.preview != null ? 3 : f.hasPixelTemperatures() ? 2 : f.hasTemperature() ? 1 : 0;
            if (f.displayScale() == 2 || f.repairedSites.length != 0) kind = 4;
            buffer.putInt(MAGIC).putShort((short) WIDTH).putShort((short) HEIGHT);
            buffer.put((byte) kind).put((byte) capture.palette)
                    .put((byte) (capture.calibration.applied ? 1 : 0)).put((byte) (kind == 4 ? f.displayScale() : 0));
            buffer.putFloat(capture.calibration.scale).putFloat(capture.calibration.offset);
            // v0.9.2/3 require finite limits on raw-only records. Keep their legacy
            // view usable; the optional container display flag records the actual lock.
            boolean rawAuto = kind == 2 && !capture.scale.locked();
            buffer.putFloat(rawAuto ? capture.calibration.apply(f.minimum) : capture.scale.low);
            buffer.putFloat(rawAuto ? capture.calibration.apply(f.maximum) : capture.scale.high);
            buffer.putInt(f.cold).putInt(f.hot).putFloat(f.minimum).putFloat(f.maximum);
            buffer.putFloat(f.center).putFloat(f.average);
            int size = HEADER;
            if (f.readings != null) {
                buffer.asFloatBuffer().put(f.readings);
                size += PIXELS * 4;
                buffer.position(size);
            }
            if (capture.brightness != null) {
                buffer.put(capture.brightness);
                size += capture.brightness.length;
            }
            if (kind == 4) {
                buffer.putShort((short) f.repairedSites.length);
                for (int p : f.repairedSites) buffer.putShort((short) p);
                size = buffer.position();
            }
            zip.reset();
            zip.setInput(buffer.array(), 0, size);
            zip.finish();
            int length = zip.deflate(packed);
            if (!zip.finished()) throw new IOException("Temperature record overflow");
            return length;
        }

        byte[] bytes() { return packed; }
        @Override public void close() { zip.end(); }
    }

    static ThermalCapture decode(byte[] packed, ByteBuffer source, int display, boolean thermalOutput, int contrast,
                                 int tone, ByteBuffer iron) throws IOException {
        try (Decoder decoder = new Decoder()) {
            return decoder.decode(packed, packed.length, source, display, thermalOutput, contrast, tone, iron);
        }
    }

    static final class Decoder implements AutoCloseable {
        private final byte[] raw = new byte[MAX_RAW];
        private final Inflater zip = new Inflater();

        ThermalCapture decode(byte[] packed, int length, ByteBuffer source, int display, boolean thermalOutput,
                              int contrast, int tone, ByteBuffer iron) throws IOException {
            if (length <= 0 || length > MAX_PACKED || length > packed.length) throw invalid();
            try {
                zip.reset();
                zip.setInput(packed, 0, length);
                int size = zip.inflate(raw);
                if (!zip.finished() || zip.getRemaining() != 0 || size < HEADER) throw invalid();
                return unpack(ByteBuffer.wrap(raw, 0, size).order(ByteOrder.BIG_ENDIAN), source, display, thermalOutput, contrast, tone, iron);
            } catch (DataFormatException e) {
                throw new IOException("Invalid temperature compression", e);
            }
        }

        @Override public void close() { zip.end(); }
    }

    private static ThermalCapture unpack(ByteBuffer b, ByteBuffer source, int display, boolean thermalOutput,
                                         int contrast, int tone, ByteBuffer iron) throws IOException {
        if (contrast < ColorScale.MIN_CONTRAST || contrast > ColorScale.MAX_CONTRAST) throw invalid();
        if (b.getInt() != MAGIC || b.getShort() != WIDTH || b.getShort() != HEIGHT) throw invalid();
        int kind = b.get(), palette = b.get(), applied = b.get();
        float middleLow = Float.NaN, middleHigh = Float.NaN;
        if (iron != null) {
            int percent = iron.get() & 255;
            if (percent != 0) {
                middleLow = iron.getFloat();
                middleHigh = iron.getFloat();
                if (percent <= ColorScale.NORMAL || percent > ColorScale.MAX_CONTRAST || contrast != ColorScale.NORMAL
                        || palette != 0 || !thermalOutput || kind < 3 || !ColorScale.validMiddle(middleLow, middleHigh))
                    throw invalid();
                contrast = percent;
            }
        }
        if (tone < 0 || tone > 101 || (tone != 0 && (!thermalOutput || display != LOCKED || kind < 3)))
            throw invalid();
        int firstColumn = source == null ? ThermalFrame.LEGACY_LEFT_MASK : source.getInt();
        if (firstColumn != 0 && firstColumn != ThermalFrame.LEGACY_LEFT_MASK) throw invalid();
        ThermalFrame.Markers markers = source == null ? null : getMarkers(source);
        if (markers != null && (kind < 2 || firstColumn != 0)) throw invalid();
        if (thermalOutput && (kind < 2 || firstColumn != 0)) throw invalid();
        int raster = b.get();
        if ((kind == 4 ? !thermalOutput || (raster != 1 && raster != 2) : raster != 0)
                || kind < 0 || kind > 4 || palette < 0
                || palette >= ThermalCapture.STOPS.length || applied < 0 || applied > 1) throw invalid();
        float scale = b.getFloat(), offset = b.getFloat(), low = b.getFloat(), high = b.getFloat();
        int cold = b.getInt(), hot = b.getInt();
        float min = b.getFloat(), max = b.getFloat(), center = b.getFloat(), average = b.getFloat();
        boolean mapped = Float.isFinite(low) && Float.isFinite(high) && low <= high;
        if (!Float.isFinite(scale) || scale <= 0 || !Float.isFinite(offset)
                || (!mapped && !(Float.isNaN(low) && Float.isNaN(high))) || (kind == 2 && !mapped)
                || (kind >= 2 && (cold < 0 || hot < 0 || cold >= PIXELS || hot >= PIXELS
                || !Float.isFinite(min) || !Float.isFinite(max) || min > max))
                || display < LEGACY_DISPLAY || display > LOCKED || (display == LOCKED && !mapped)
                || (kind == 1 && (!validAnchor(cold, min) || !validAnchor(hot, max)
                || !Float.isFinite(center) || center < min || center > max))
                || (kind == 0 && (cold != -1 || hot != -1 || !Float.isNaN(center)))
                || (kind != 4 && b.remaining() != PIXELS * (kind == 3 ? 5 : kind == 2 ? 4 : 1))
                || (kind == 4 && b.remaining() < PIXELS * (4 + raster * raster) + 2)) throw invalid();
        float[] readings = null;
        byte[] brightness = null;
        if (kind >= 2) {
            readings = new float[PIXELS];
            b.asFloatBuffer().get(readings);
            b.position(b.position() + PIXELS * 4);
            boolean autoRaw = kind == 2 && low == scale * min + offset && high == scale * max + offset;
            float[] stats = new float[3];
            int valid = PixelOps.maskAndMeasure(readings, stats, firstColumn);
            if (valid == 0) throw invalid();
            if (thermalOutput) {
                // Two different AD codes can round to the same Celsius float. Retain the
                // recorded AD extrema and center-pair measurement across save and reopen.
                if (valid != PIXELS || readings[cold] != min || readings[hot] != max ||
                        min != readings[(int) stats[0]] || max != readings[(int) stats[1]] ||
                        !Float.isFinite(center) || center < min || center > max) throw invalid();
            } else {
                cold = (int) stats[0];
                hot = (int) stats[1];
                min = readings[cold];
                max = readings[hot];
                center = readings[ThermalFrame.CENTER];
            }
            average = stats[2];
            if (display == AUTO || (display == LEGACY_DISPLAY && autoRaw)) low = high = Float.NaN;
        }
        if (kind != 2) {
            brightness = new byte[kind == 4 ? PIXELS * raster * raster : PIXELS];
            b.get(brightness);
        }
        int[] sites = BlindPixels.NONE;
        if (kind == 4) {
            int n = Short.toUnsignedInt(b.getShort());
            if (n > BlindPixels.MAX_USER || b.remaining() != n * 2) throw invalid();
            sites = new int[n];
            for (int i = 0; i < n; i++) {
                sites[i] = Short.toUnsignedInt(b.getShort());
                if (sites[i] >= PIXELS || (i > 0 && sites[i] <= sites[i-1])) throw invalid();
            }
        }
        ColorScale colorScale = new ColorScale(contrast, low, high, middleLow, middleHigh);
        ThermalFrame frame = new ThermalFrame(readings, brightness, cold, hot, min, max, center, average,
                System.nanoTime(), markers, firstColumn, thermalOutput, sites,
                new ThermalFrame.Display(palette, colorScale, Float.NaN, Float.NaN, null, tone, Float.NaN, Float.NaN,
                        middleLow, middleHigh));
        return new ThermalCapture(frame, new Calibration(scale, offset, applied == 1), palette, colorScale, null);
    }

    private static boolean validAnchor(int index, float value) {
        return (index >= 0 && index < PIXELS && Float.isFinite(value)) || (index == -1 && Float.isNaN(value));
    }

    private static IOException invalid() { return new IOException("Invalid temperature record"); }
}
