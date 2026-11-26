package org.thermal.wp09;

/** One timeline per USB session. A saved frame owns its finished display plane. */
final class LiveImage {
    static final String[] SCENES = {"旷野", "林地", "观鸟"};
    static final class Settings {
        final int scene, palette;
        final boolean doubleSize;
        Settings(int scene, int palette, boolean doubleSize) {
            this.scene = Math.max(0, Math.min(SCENES.length - 1, scene));
            this.palette = palette;
            this.doubleSize = doubleSize;
        }
        boolean black() { return palette == 3 || palette == 5; }
    }

    /** Temperatures after display denoising. Keep the unclipped range for a fixed window. */
    static final class Filtered {
        final float[] values = new float[ThermalFrame.PIXELS];
        private final float[] previous;
        private final Settings settings;
        private float low, high;
        private byte[] luma;

        Filtered(Settings settings, float[] previous) {
            this.settings = settings;
            this.previous = previous;
        }

        synchronized byte[] window(float lower, float upper) {
            if (luma == null || lower != low || upper != high) {
                byte[] output = new byte[ThermalFrame.PIXELS * (settings.doubleSize ? 4 : 1)];
                PixelOps.windowLuma(values, previous, settings.scene, settings.black(), lower, upper, output);
                low = lower;
                high = upper;
                luma = output;
            }
            return luma;
        }
    }
    private final float[] history = new float[ThermalFrame.PIXELS];
    private final float[] autoWindow = new float[3];
    private float[] previousField;
    private float previousLow, previousHigh;
    private boolean previousAuto;
    private byte[] previous;
    private Settings last;
    private int[] lastRepairs;
    private float lastEmissivity;
    private long lastTime;

    void reset() { last = null; previous = null; previousField = null; }

    ThermalFrame process(ThermalFrame f, Settings settings, float emissivity, int[] repairs) {
        return process(f, settings, emissivity, repairs, ColorScale.AUTO);
    }

    ThermalFrame process(ThermalFrame f, Settings settings, float emissivity, int[] repairs, ColorScale scale) {
        return process(f, settings, emissivity, repairs, scale, Calibration.DEFAULT);
    }

    ThermalFrame process(ThermalFrame f, Settings settings, float emissivity, int[] repairs, ColorScale scale,
                         Calibration calibration) {
        boolean continuous = last == settings && lastRepairs == repairs && lastEmissivity == emissivity
                && f.capturedNanos > lastTime && f.capturedNanos - lastTime <= 120_000_000L;
        byte[] high = settings.doubleSize ? new byte[ThermalFrame.PIXELS * 4] : null;
        // In 2× mode only the enlarged raster is published. The native-size
        // grayscale reference can be reused after registration reads its old value.
        byte[] gray = high != null && previous != null ? previous : new byte[ThermalFrame.PIXELS];
        Filtered filtered = new Filtered(settings, high != null && continuous ? previousField : null);
        float fixedLow = (scale.low - calibration.offset) / calibration.scale;
        float fixedHigh = (scale.high - calibration.offset) / calibration.scale;
        boolean sameWindow = scale.locked() ? fixedLow == previousLow && fixedHigh == previousHigh : previousAuto;
        PixelOps.liveLuma(f.readings, history, settings.scene, settings.black(),
                continuous, sameWindow ? previous : null, gray, high, autoWindow, filtered.values, fixedLow, fixedHigh);
        previousAuto = !scale.locked();
        previousLow = previousAuto ? autoWindow[0] : fixedLow;
        previousHigh = previousAuto ? autoWindow[1] : fixedHigh;
        previousField = high != null ? filtered.values : null;
        previous = high != null ? gray : null;
        lastTime = f.capturedNanos;
        last = settings;
        lastRepairs = repairs;
        lastEmissivity = emissivity;
        return new ThermalFrame(f.readings, high == null ? gray : high, f.cold, f.hot, f.minimum, f.maximum,
                f.center, f.average, f.capturedNanos, f.firmware, f.firstColumn, true, repairs,
                new ThermalFrame.Display(settings.palette, scale, autoWindow[0], autoWindow[1],
                        filtered, 1 + Math.round(autoWindow[2] * 100), previousLow, previousHigh));
    }
}
