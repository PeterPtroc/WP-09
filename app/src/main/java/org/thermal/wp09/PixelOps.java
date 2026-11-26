package org.thermal.wp09;

import java.nio.ByteBuffer;

/** Bulk operations on native-sized 256 × 192 sensor planes. */
final class PixelOps {
    static { System.loadLibrary("wp09"); }

    static native int convert(byte[] source, int offset, int stride, float[] table, float[] dest, float[] stats,
                              boolean thermalOutput);
    static native int maskAndMeasure(float[] readings, float[] stats, int firstColumn);
    static native boolean unpack(byte[] wireFrame, byte[] luma);
    static native void renderTemperatures(float[] readings, int[] lut, int[] dest,
                                          float scale, float offset, float low, float high);
    static native void renderBrightness(byte[] luma, int[] lut, int[] dest, int firstColumn, int sourceShift);
    static native float[] displayBias(float[][] samples, byte[][] pairedLuma, int firstColumn);
    static native void renderMobile(float[] readings, int firstColumn, boolean thermalOutput, float[] bias,
                                   int[] lut, int[] dest);
    static native void liveLuma(float[] readings, float[] history, int scene, boolean black,
                                boolean continuous, byte[] previous, byte[] low, byte[] high, float[] autoWindow,
                                float[] filtered, float fixedLow, float fixedHigh);
    static native void windowLuma(float[] filtered, float[] previous, int scene, boolean black,
                                  float low, float high, byte[] output);
    static native boolean yuv(int[] pixels, ByteBuffer y, int yOffset, int yStride, int yStep,
                              ByteBuffer u, int uOffset, int uStride, int uStep,
                              ByteBuffer v, int vOffset, int vStride, int vStep);

    private PixelOps() {}
}
