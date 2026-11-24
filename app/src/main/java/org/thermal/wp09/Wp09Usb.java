package org.thermal.wp09;

final class Wp09Usb {
    static {
        System.loadLibrary("wp09");
    }

    static native int open(int fd, int vendor, int product, int address, String usbfs,
                           long[] outputHandle);
    static native int read(long handle, byte[] frame, long[] receivedAt);
    static native byte[] sample(long handle);
    static native int laser(long handle, boolean enabled);
    static native void close(long handle);
    static native boolean temperatureTable(byte[] frame, float[] table, float emissivity);

    private Wp09Usb() {}
}
