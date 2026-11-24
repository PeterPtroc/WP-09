package org.thermal.wp09;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class ThermalFrame {
    static final int WIDTH = 256;
    static final int HEIGHT = 192;
    static final int PIXELS = WIDTH * HEIGHT;
    static final int CENTER = HEIGHT / 2 * WIDTH + WIDTH / 2;
    static final int BYTES = WIDTH * (HEIGHT + 4) * Short.BYTES;
    private static final int TELEMETRY = PIXELS * Short.BYTES;
    private static final int COEFFICIENTS = TELEMETRY + WIDTH * Short.BYTES;

    final float[] readings;
    final int[] preview;
    final int cold;
    final int hot;
    final float average;
    final float minimum;
    final float maximum;
    final float center;

    private ThermalFrame(float[] readings, int[] preview, int cold, int hot,
                         float minimum, float maximum, float center, float average) {
        this.readings = readings;
        this.preview = preview;
        this.cold = cold;
        this.hot = hot;
        this.minimum = minimum;
        this.maximum = maximum;
        this.center = center;
        this.average = average;
    }

    boolean hasTemperature() {
        return Float.isFinite(center) || hasPixelTemperatures();
    }

    boolean hasPixelTemperatures() {
        return readings != null;
    }

    boolean isInterpolatedAt(int pixel) {
        return readings == null && pixel != CENTER && pixel != cold && pixel != hot;
    }

    float temperatureAt(int pixel) {
        if (readings != null) return readings[pixel];
        if (!hasTemperature()) return Float.NaN;
        if (pixel == CENTER) return center;
        if (pixel == cold) return minimum;
        if (pixel == hot || minimum == maximum) return maximum;

        int coldLevel = preview[cold] & 0xff;
        int hotLevel = preview[hot] & 0xff;
        int range = hotLevel - coldLevel;
        if (range == 0) return Float.NaN;
        float position = ((preview[pixel] & 0xff) - coldLevel) / (float) range;
        if (position <= 0) return minimum;
        if (position >= 1) return maximum;

        // Display gain may change with the scene. Use anchors from this frame and
        // avoid extrapolating into brightness levels outside their range.
        float midpoint = ((preview[CENTER] & 0xff) - coldLevel) / (float) range;
        if (midpoint <= 0 || midpoint >= 1) {
            return minimum + position * (maximum - minimum);
        }
        if (position <= midpoint) {
            return minimum + position / midpoint * (center - minimum);
        }
        return center + (position - midpoint) / (1 - midpoint) * (maximum - center);
    }

    static final class Decoder {
        private static final int DUMP_ROW_BYTES = 768;
        private static final int DUMP_FRAME_BYTES = 203 * DUMP_ROW_BYTES;
        private static final int DUMP_FRAMES = 8;
        private static final int DUMP_IMAGE_OFFSET = 8 * DUMP_ROW_BYTES;
        private final float[] table = new float[16384];
        private final int[] calibrationWords = new int[14];
        private boolean tableReady;
        private float lastEmissivity;

        ThermalFrame decode(byte[] bytes, float emissivity) {
            ByteBuffer frame = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (hasTelemetry(frame)) {
                int hot = pixelIndex(frame, 4);
                int cold = pixelIndex(frame, 10);
                int maximum = word(frame, TELEMETRY + 8);
                int minimum = word(frame, TELEMETRY + 14);
                boolean fullFrame = matchesPixel(frame, hot, maximum)
                        && matchesPixel(frame, cold, minimum);
                if (fullFrame && updateTable(bytes, frame, emissivity)) {
                    ThermalFrame temperature = convert(frame, 0, WIDTH * Short.BYTES, false);
                    if (temperature != null) return temperature;
                } else if (isMonochromeYuyv(bytes) && updateTable(bytes, frame, emissivity)) {
                    // XWild's preview mode retains three radiometric counts in the trailer.
                    // The preceding YUYV pixels have already been mapped to display brightness.
                    float low = table[minimum];
                    float high = table[maximum];
                    float center = table[word(frame, TELEMETRY + 24)];
                    if (validTemperature(low) && validTemperature(high)
                            && low <= center && center <= high) {
                        return new ThermalFrame(null, preview(bytes), cold, hot,
                                low, high, center, Float.NaN);
                    }
                }
            }
            return new ThermalFrame(null, preview(bytes), -1, -1,
                    Float.NaN, Float.NaN, Float.NaN, Float.NaN);
        }

        ThermalFrame decodeSnapshot(byte[] dump, int index, byte[] reference, float emissivity) {
            if (dump.length != DUMP_FRAMES * DUMP_FRAME_BYTES || index < 0 || index >= DUMP_FRAMES
                    || reference.length != BYTES) return null;
            ByteBuffer metadata = ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN);
            if (!hasTelemetry(metadata) || !updateTable(reference, metadata, emissivity)) return null;

            // The firmware dump includes eight reference rows before the active area and
            // padding after each row. Map each active sensor count through the vendor table.
            ByteBuffer sensor = ByteBuffer.wrap(dump).order(ByteOrder.LITTLE_ENDIAN);
            return convert(sensor, index * DUMP_FRAME_BYTES + DUMP_IMAGE_OFFSET,
                    DUMP_ROW_BYTES, true);
        }

        private boolean hasTelemetry(ByteBuffer frame) {
            int maximum = word(frame, TELEMETRY + 8);
            int minimum = word(frame, TELEMETRY + 14);
            int center = word(frame, TELEMETRY + 24);
            if (minimum <= 0 || maximum >= table.length || center < minimum || center > maximum) {
                return false;
            }
            if (pixelIndex(frame, 4) < 0 || pixelIndex(frame, 10) < 0) {
                return false;
            }
            for (int i = 0; i < 5; i++) {
                float coefficient = frame.getFloat(COEFFICIENTS + 6 + i * 4);
                if (!Float.isFinite(coefficient) || Math.abs(coefficient) > 1e12f
                        || (i < 2 && Math.abs(coefficient) < 1e-12f)) return false;
            }
            return true;
        }

        private static int pixelIndex(ByteBuffer frame, int coordinates) {
            int x = word(frame, TELEMETRY + coordinates);
            int y = word(frame, TELEMETRY + coordinates + 2);
            return x < WIDTH && y < HEIGHT ? y * WIDTH + x : -1;
        }

        private static boolean matchesPixel(ByteBuffer frame, int index, int expected) {
            return Math.abs(word(frame, 2 * index) - expected) <= 32;
        }

        private static boolean isMonochromeYuyv(byte[] bytes) {
            for (int i = 1; i < TELEMETRY; i += 2) {
                if (bytes[i] != (byte) 128) return false;
            }
            return true;
        }

        private boolean updateTable(byte[] bytes, ByteBuffer frame, float emissivity) {
            boolean changed = !tableReady || emissivity != lastEmissivity;
            for (int i = 0; i < calibrationWords.length; i++) {
                int current = i == 0 ? word(frame, TELEMETRY + 2)
                        : word(frame, COEFFICIENTS + (i - 1) * Short.BYTES);
                changed |= calibrationWords[i] != current;
                calibrationWords[i] = current;
            }
            if (changed) {
                tableReady = Wp09Usb.temperatureTable(bytes, table, emissivity);
                lastEmissivity = emissivity;
            }
            return tableReady;
        }

        private ThermalFrame convert(ByteBuffer frame, int offset, int rowBytes, boolean requireAllPixels) {
            float[] temperatures = new float[PIXELS];
            int cold = -1;
            int hot = -1;
            double sum = 0;
            int valid = 0;
            for (int i = 0; i < PIXELS; i++) {
                int raw = word(frame, offset + i / WIDTH * rowBytes + (i % WIDTH) * Short.BYTES);
                float value = raw > 0 && raw < table.length ? table[raw] : Float.NaN;
                if (!validTemperature(value)) {
                    temperatures[i] = Float.NaN;
                    continue;
                }
                temperatures[i] = value;
                if (cold < 0 || value < temperatures[cold]) cold = i;
                if (hot < 0 || value > temperatures[hot]) hot = i;
                sum += value;
                valid++;
            }
            if (valid < PIXELS * 0.98 || (requireAllPixels && valid != PIXELS)) return null;
            return new ThermalFrame(temperatures, null, cold, hot, temperatures[cold],
                    temperatures[hot], temperatures[CENTER], (float) (sum / valid));
        }

        private static boolean validTemperature(float value) {
            return Float.isFinite(value) && value >= -100f && value <= 1000f;
        }

        private static int[] preview(byte[] bytes) {
            int[] colors = new int[PIXELS];
            for (int i = 0; i < PIXELS; i++) {
                int luma = bytes[2 * i] & 0xff;
                colors[i] = 0xff000000 | (luma * 0x010101);
            }
            return colors;
        }

        private static int word(ByteBuffer frame, int offset) {
            return Short.toUnsignedInt(frame.getShort(offset));
        }
    }
}
