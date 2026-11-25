package org.thermal.wp09;

import java.io.IOException;

/** One display-only noise estimate for a recording, reproducible on any seek. */
final class MobileImage {
    static final int WINDOW = 32;
    private static final int STRIDE = 4;
    final float[] bias;

    private MobileImage(float[] bias) { this.bias = bias; }

    interface Source { ThermalFrame frame(int index) throws IOException; }

    static MobileImage fromStart(int count, Source source) throws IOException {
        int samples = (Math.min(count, WINDOW) + STRIDE - 1) / STRIDE;
        if (samples < 3) return new MobileImage(null);
        float[][] readings = new float[samples][];
        byte[][] references = new byte[samples][];
        int firstColumn = -1;
        for (int i = 0; i < samples; i++) {
            ThermalFrame frame = source.frame(i * STRIDE);
            if (frame.readings == null || (i > 0 && frame.firstColumn != firstColumn)) return new MobileImage(null);
            readings[i] = frame.readings;
            references[i] = frame.preview;
            firstColumn = frame.firstColumn;
        }
        return new MobileImage(PixelOps.displayBias(readings, references, firstColumn));
    }
}
