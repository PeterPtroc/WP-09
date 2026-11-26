package org.thermal.wp09;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class ThermalFrame {
    static final int WIDTH = 256;
    static final int HEIGHT = 192;
    static final int PIXELS = WIDTH * HEIGHT;
    static final int LEGACY_LEFT_MASK = 2;
    static final int CENTER = HEIGHT / 2 * WIDTH + WIDTH / 2;
    static final int BYTES = WIDTH * (HEIGHT + 4) * Short.BYTES;
    private static final int TELEMETRY = PIXELS * Short.BYTES;
    private static final int COEFFICIENTS = TELEMETRY + WIDTH * Short.BYTES;

    final long capturedNanos;
    final int firstColumn;
    final float[] readings;
    final byte[] preview;
    final int cold;
    final int hot;
    final float average;
    final float minimum;
    final float maximum;
    final float center;
    // A paired UVC image carries the camera's own statistics alongside the raw plane's statistics.
    final Markers firmware;
    final boolean thermalOutput;
    final Markers reported;
    final int[] repairedSites;
    final Display display;

    static final class Display {
        final int palette;
        final ColorScale scale;
        final float autoLow, autoHigh;
        final float imageLow, imageHigh;
        final float middleLow, middleHigh;
        final LiveImage.Filtered filtered;
        // 0 = legacy direct color, otherwise 1 + the profile's shadow lift in percent.
        final int tone;

        Display(int palette, ColorScale scale, float autoLow, float autoHigh, LiveImage.Filtered filtered, int tone,
                float imageLow, float imageHigh, float middleLow, float middleHigh) {
            this.palette = palette;
            this.scale = scale;
            this.autoLow = autoLow;
            this.autoHigh = autoHigh;
            this.filtered = filtered;
            this.tone = tone;
            this.imageLow = imageLow;
            this.imageHigh = imageHigh;
            this.middleLow = middleLow;
            this.middleHigh = middleHigh;
        }
    }

    static final class Markers {
        final int cold, hot;
        final float minimum, maximum, center;

        Markers(int cold, int hot, float minimum, float maximum, float center) {
            this.cold = cold >= 0 && cold < PIXELS ? cold : -1;
            this.hot = hot >= 0 && hot < PIXELS ? hot : -1;
            this.minimum = this.cold >= 0 ? minimum : Float.NaN;
            this.maximum = this.hot >= 0 ? maximum : Float.NaN;
            this.center = center;
        }
    }

    ThermalFrame(float[] readings, byte[] preview, int cold, int hot,
                 float minimum, float maximum, float center, float average) {
        this(readings, preview, cold, hot, minimum, maximum, center, average, System.nanoTime(), null);
    }

    ThermalFrame(float[] readings, byte[] preview, int cold, int hot, float minimum,
                 float maximum, float center, float average, long capturedNanos, Markers firmware) {
        this(readings, preview, cold, hot, minimum, maximum, center, average, capturedNanos, firmware, 0);
    }

    ThermalFrame(float[] readings, byte[] preview, int cold, int hot, float minimum,
                 float maximum, float center, float average, long capturedNanos, Markers firmware, int firstColumn) {
        this(readings, preview, cold, hot, minimum, maximum, center, average, capturedNanos, firmware, firstColumn, false);
    }

    ThermalFrame(float[] readings, byte[] preview, int cold, int hot, float minimum, float maximum,
                 float center, float average, long capturedNanos, Markers firmware, int firstColumn, boolean thermalOutput) {
        this(readings, preview, cold, hot, minimum, maximum, center, average, capturedNanos,
                firmware, firstColumn, thermalOutput, BlindPixels.NONE, null);
    }

    ThermalFrame(float[] readings, byte[] preview, int cold, int hot, float minimum, float maximum,
                 float center, float average, long capturedNanos, Markers firmware, int firstColumn,
                 boolean thermalOutput, int[] repairedSites, Display display) {
        this.repairedSites = repairedSites;
        this.display = display;
        this.capturedNanos = capturedNanos;
        this.firstColumn = firstColumn;
        this.firmware = firmware;
        this.thermalOutput = thermalOutput;
        this.readings = readings;
        this.preview = preview;
        this.cold = measurable(cold) ? cold : -1;
        this.hot = measurable(hot) ? hot : -1;
        this.minimum = this.cold >= 0 ? minimum : Float.NaN;
        this.maximum = this.hot >= 0 ? maximum : Float.NaN;
        this.center = center;
        this.average = average;
        reported = firmware != null && !thermalOutput ? firmware
                : new Markers(this.cold, this.hot, this.minimum, this.maximum, center);
    }

    boolean hasTemperature() {
        return Float.isFinite(center) || hasPixelTemperatures();
    }

    boolean hasPixelTemperatures() {
        return readings != null;
    }

    boolean hasFirmwareImage() {
        return !thermalOutput && preview != null && readings != null;
    }

    int displayScale() { return preview != null && preview.length == PIXELS * 4 ? 2 : 1; }

    boolean isEstimatedAt(int pixel) {
        if (!measurable(pixel)) return false;
        if (thermalOutput) return java.util.Arrays.binarySearch(repairedSites, pixel) >= 0;
        if (readings != null) return firmware == null || (pixel != firmware.hot && pixel != firmware.cold);
        return pixel != CENTER && pixel != cold && pixel != hot;
    }

    boolean agreesWithPreview(ThermalFrame previewFrame) {
        if (!thermalOutput || previewFrame == null || previewFrame.readings != null || !previewFrame.hasTemperature()
                || previewFrame.hot < 0 || previewFrame.cold < 0) return false;
        // Mode identification against a nearby preview. This tolerance
        // allows small scene changes; it does not modify any measurement.
        return Math.abs(maximum - previewFrame.maximum) <= 1f && Math.abs(minimum - previewFrame.minimum) <= 1f
                && Math.abs(center - previewFrame.center) <= 1f
                && Math.abs(readings[previewFrame.hot] - previewFrame.maximum) <= 1f
                && Math.abs(readings[previewFrame.cold] - previewFrame.minimum) <= 1f;
    }

    boolean measurable(int pixel) {
        return pixel >= 0 && pixel < PIXELS && pixel % WIDTH >= firstColumn;
    }

    float temperatureAt(int pixel) {
        if (!measurable(pixel)) return Float.NaN;
        if (readings != null) {
            if (firmware == null || thermalOutput) return readings[pixel];
            if (pixel == firmware.hot) return firmware.maximum;
            if (pixel == firmware.cold) return firmware.minimum;
            // A legacy upstream sample outside the observed firmware range cannot
            // represent this point. Its original value stays in the archived plane.
            float estimate = readings[pixel];
            return estimate >= firmware.minimum && estimate <= firmware.maximum ? estimate : Float.NaN;
        }
        if (!hasTemperature()) return Float.NaN;
        if (pixel == cold) return minimum;
        if (pixel == hot || minimum == maximum) return maximum;
        if (pixel == CENTER) return center;

        return temperatureForLevel(preview[pixel] & 0xff);
    }

    float temperatureForLevel(int level) {
        if (cold < 0 || hot < 0) {
            // Only interpolate between surviving measured anchors.
            int anchor = cold >= 0 ? cold : hot;
            if (anchor < 0) return Float.NaN;
            int centerLevel = preview[CENTER] & 255;
            int delta = (preview[anchor] & 255) - centerLevel;
            if (delta == 0) return Float.NaN;
            float fraction = (level - centerLevel) / (float) delta;
            return fraction >= 0 && fraction <= 1
                    ? center + fraction * ((cold >= 0 ? minimum : maximum) - center) : Float.NaN;
        }
        int coldLevel = preview[cold] & 0xff;
        int hotLevel = preview[hot] & 0xff;
        int range = hotLevel - coldLevel;
        if (minimum == maximum) return minimum;
        if (range == 0) return Float.NaN;
        float position = (level - coldLevel) / (float) range;
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
        // rtd2121w_get_ir_sns_attr: 262 x 203 source, crop (2,8), output 256 x 192.
        private static final int DUMP_IMAGE_OFFSET = 8 * DUMP_ROW_BYTES + 2 * Short.BYTES;
        private final float[] table = new float[16384];
        private final int[] calibrationWords = new int[14];
        private final float[] stats = new float[4];
        private boolean tableReady;
        private float lastEmissivity;

        ThermalFrame decode(byte[] bytes, float emissivity) {
            return decode(bytes, emissivity, System.nanoTime());
        }

        ThermalFrame decode(byte[] bytes, float emissivity, long timeNs) {
            if (bytes.length != BYTES) throw new IllegalArgumentException("Unexpected frame size");
            byte[] brightness = null;
            ByteBuffer frame = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (hasTelemetry(frame)) {
                int hot = pixelIndex(frame, 4);
                int cold = pixelIndex(frame, 10);
                int maximum = word(frame, TELEMETRY + 8);
                int minimum = word(frame, TELEMETRY + 14);
                boolean fullFrame = word(frame, 2 * hot) == maximum && word(frame, 2 * cold) == minimum;
                if (fullFrame && updateTable(bytes, frame, emissivity)) {
                    ThermalFrame temperature = convert(bytes, 0, WIDTH * Short.BYTES, true, timeNs);
                    if (temperature != null && matchesTelemetry(temperature, frame)) return temperature;
                } else {
                    brightness = new byte[PIXELS];
                    boolean monochrome = PixelOps.unpack(bytes, brightness);
                    if (!monochrome || !updateTable(bytes, frame, emissivity)) return imageOnly(brightness, timeNs);
                    // XWild's preview mode retains three radiometric counts in the trailer.
                    // The preceding YUYV pixels have already been mapped to display brightness.
                    Markers markers = markers(frame);
                    if (markers != null) {
                        return new ThermalFrame(null, brightness, markers.cold, markers.hot,
                                markers.minimum, markers.maximum, markers.center, Float.NaN, timeNs, null);
                    }
                }
            }
            if (brightness == null) {
                brightness = new byte[PIXELS];
                PixelOps.unpack(bytes, brightness);
            }
            return imageOnly(brightness, timeNs);
        }

        // In output mode 4 the UVC producer consumes the thermal channel itself.
        // The trailer's three positions may still describe the last mode-5 preview.
        ThermalFrame decodeThermalOutput(byte[] bytes, float emissivity, long timeNs, BlindPixels defects) {
            if (bytes.length != BYTES || (bytes[1] & 0xc0) != 0 && (defects == null || !defects.contains(0))) return null;
            ByteBuffer frame = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (!hasCalibration(frame) || !updateTable(bytes, frame, emissivity)) return null;
            if (defects != null) defects.correct(frame);
            ThermalFrame result = convert(bytes, 0, WIDTH * Short.BYTES, true, timeNs);
            if (result == null) return null;
            // Output-disabled UVC packets can carry an all-zero image and an old valid footer.
            if (result.cold == result.hot && word(frame, result.hot * 2) == 0) return null;
            // Without the device's defect list only accept a plane corroborated by
            // the complete firmware summary, including the two-center AD count.
            return defects != null || (hasTelemetry(frame) && matchesTelemetry(result, frame)) ? result : null;
        }

        static boolean matchesTelemetry(ThermalFrame result, ByteBuffer frame) {
            return result.hot == pixelIndex(frame, 4) && result.cold == pixelIndex(frame, 10)
                    && word(frame, result.hot * 2) == word(frame, TELEMETRY + 8)
                    && word(frame, result.cold * 2) == word(frame, TELEMETRY + 14)
                    && ((word(frame, CENTER * 2) + word(frame, (CENTER - 1) * 2)) >> 1) == word(frame, TELEMETRY + 24);
        }

        ThermalFrame repair(byte[] wire, ThermalFrame verified, BlindPixels defects) {
            defects.correct(ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN));
            return convert(wire, 0, WIDTH * Short.BYTES, true, verified.capturedNanos);
        }

        private Markers markers(ByteBuffer frame) {
            float low = table[word(frame, TELEMETRY + 14)], high = table[word(frame, TELEMETRY + 8)];
            float center = table[word(frame, TELEMETRY + 24)];
            if (!validTemperature(low) || !validTemperature(high) || !(low <= center && center <= high)) return null;
            return new Markers(pixelIndex(frame, 10), pixelIndex(frame, 4), low, high, center);
        }

        static ThermalFrame imageOnly(byte[] brightness, long timeNs) {
            return new ThermalFrame(null, brightness, -1, -1,
                    Float.NaN, Float.NaN, Float.NaN, Float.NaN, timeNs, null);
        }

        ThermalFrame decodeSnapshot(byte[] dump, int index, byte[] reference, float emissivity) {
            return decodeSnapshot(dump, index, reference, emissivity, 0);
        }

        ThermalFrame decodeSnapshot(byte[] dump, int index, byte[] reference, float emissivity, long previewTimeNs) {
            if (dump.length != DUMP_FRAMES * DUMP_FRAME_BYTES || index < 0 || index >= DUMP_FRAMES
                    || reference.length != BYTES) return null;
            ByteBuffer metadata = ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN);
            if (!hasTelemetry(metadata) || !updateTable(reference, metadata, emissivity)) return null;

            // The firmware dump includes eight reference rows before the active area and
            // padding after each row. Map each active sensor count through the vendor table.
            // Negative stamps identify the file announcement for an unpaired final map.
            long timeNs = previewTimeNs == 0 ? System.nanoTime() : Math.abs(previewTimeNs);
            ThermalFrame map = convert(dump, index * DUMP_FRAME_BYTES + DUMP_IMAGE_OFFSET, DUMP_ROW_BYTES, false, timeNs);
            if (map == null || previewTimeNs <= 0) return map;
            byte[] luma = new byte[PIXELS];
            if (!PixelOps.unpack(reference, luma)) return map;
            return new ThermalFrame(map.readings, luma, map.cold, map.hot, map.minimum, map.maximum,
                    map.center, map.average, previewTimeNs, markers(metadata));
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
            return hasCalibration(frame);
        }

        private static boolean hasCalibration(ByteBuffer frame) {
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

        private ThermalFrame convert(byte[] frame, int offset, int rowBytes, boolean thermalOutput, long timeNs) {
            float[] temperatures = new float[PIXELS];
            int valid = PixelOps.convert(frame, offset, rowBytes, table, temperatures, stats, thermalOutput);
            if (valid != PIXELS) return null;
            int cold = (int) stats[0], hot = (int) stats[1];
            return new ThermalFrame(temperatures, null, cold, hot, temperatures[cold],
                    temperatures[hot], thermalOutput ? stats[3] : temperatures[CENTER], stats[2],
                    timeNs, null, 0, thermalOutput);
        }

        private static boolean validTemperature(float value) {
            return Float.isFinite(value) && value >= -100f && value <= 1000f;
        }

        private static int word(ByteBuffer frame, int offset) {
            return Short.toUnsignedInt(frame.getShort(offset));
        }
    }
}
