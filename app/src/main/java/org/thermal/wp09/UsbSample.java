package org.thermal.wp09;

import android.hardware.usb.UsbDevice;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class UsbSample {
    private final byte[] frame;
    private final Properties info = new Properties();

    UsbSample(UsbDevice device, byte[] frame, int observedLength, String status) {
        this.frame = frame;
        if (device != null) info.setProperty("device", describe(device));
        info.setProperty("status", status);
        info.setProperty("uvc.mode", "YUYV 256x196, 1-50 fps");
        info.setProperty("uvc.expected_bytes", Integer.toString(ThermalFrame.BYTES));
        info.setProperty("uvc.received_bytes",
                Integer.toString(frame != null ? frame.length : observedLength));
    }

    void setAttachedDevices(Iterable<UsbDevice> devices) {
        int index = 0;
        for (UsbDevice device : devices) {
            info.setProperty("usb.attached." + index++, describe(device));
        }
    }

    boolean hasFrame() {
        return frame != null;
    }

    void setCaptureReader(boolean queued) {
        info.setProperty("capture.hid_reader", queued ? "queued" : "sequential");
    }

    void write(OutputStream stream) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(stream)) {
            zip.putNextEntry(new ZipEntry("usb.properties"));
            info.store(zip, "WP-09 USB diagnostic");
            zip.closeEntry();
            if (hasFrame()) {
                zip.putNextEntry(new ZipEntry("frame.bin"));
                zip.write(frame);
                zip.closeEntry();
            }
        }
    }

    private static String describe(UsbDevice device) {
        return String.format(Locale.ROOT, "%04x:%04x %s",
                device.getVendorId(), device.getProductId(), device.getProductName());
    }
}
