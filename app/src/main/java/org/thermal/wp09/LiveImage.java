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
    }
    private final float[] history = new float[ThermalFrame.PIXELS];
    private byte[] previous;
    private Settings last;
    private int[] lastRepairs;
    private float lastEmissivity;
    private long lastTime;

    void reset() { last = null; previous = null; }

    ThermalFrame process(ThermalFrame f, Settings settings, float emissivity, int[] repairs) {
        boolean continuous = last == settings && lastRepairs == repairs && lastEmissivity == emissivity
                && f.capturedNanos > lastTime && f.capturedNanos - lastTime <= 120_000_000L;
        byte[] high = settings.doubleSize ? new byte[ThermalFrame.PIXELS * 4] : null;
        // In 2× mode only the enlarged raster is published. The native-size
        // grayscale reference can be reused after registration reads its old value.
        byte[] gray = high != null && previous != null ? previous : new byte[ThermalFrame.PIXELS];
        PixelOps.liveLuma(f.readings, history, settings.scene, settings.palette == 3 || settings.palette == 5,
                continuous, previous, gray, high);
        previous = high != null ? gray : null;
        lastTime = f.capturedNanos;
        last = settings;
        lastRepairs = repairs;
        lastEmissivity = emissivity;
        return new ThermalFrame(f.readings, high == null ? gray : high, f.cold, f.hot, f.minimum, f.maximum,
                f.center, f.average, f.capturedNanos, f.firmware, f.firstColumn, true, repairs, settings.palette);
    }
}
