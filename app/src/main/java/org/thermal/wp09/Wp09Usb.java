package org.thermal.wp09;

final class Wp09Usb {
    static {
        System.loadLibrary("wp09");
    }

    static native int open(int fd, int vendor, int product, int address, String usbfs,
                           long[] outputHandle);
    // Positive monotonic UVC callback time, zero for a timeout, negative for a bad length.
    static native long read(long handle, byte[] frame);
    static native byte[] sample(long handle);
    static native void prepareCapture(long handle);
    static native void attachCaptureFd(long handle, int descriptor);
    static native boolean queuedCapture(long handle);
    static native byte[] factoryKb(long handle);
    static native boolean setThermalOutput(long handle, boolean enabled);
    static native byte[] captureRawDump(long handle, byte[][] references, long[] previewTimesNs, boolean burst);
    static native int laser(long handle, boolean enabled);
    static native int triggerShutter(long handle);
    static native void close(long handle);
    static native boolean temperatureTable(byte[] frame, float[] table, float emissivity);

    private Wp09Usb() {}
}
