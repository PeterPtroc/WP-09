package org.thermal.wp09;

import android.graphics.Bitmap;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

final class RadiometricPng {
    private static final long SIGNATURE = 0x89504e470d0a1a0aL;
    private static final int IHDR = 0x49484452, IEND = 0x49454e44;
    private static final int TEMPERATURE = 0x77705448; // wpTH: ancillary, private, unsafe to copy
    private static final int SOURCE = 0x7770464d; // wpFM: active image window and paired firmware extrema
    private static final int DISPLAY = 0x77704453; // wpDS: explicit temperature-window lock
    private static final int PLANE = 0x7770504c; // wpPL: 1 = firmware thermal output

    static void write(OutputStream destination, ThermalCapture capture) throws IOException {
        Bitmap bitmap = capture.bitmap();
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, image)) throw new IOException("PNG encode");
        } finally {
            bitmap.recycle();
        }
        byte[] png = image.toByteArray();
        int end = png.length - 12; // Android's PNG encoder finishes with an empty IEND.
        DataOutputStream out = new DataOutputStream(destination);
        out.write(png, 0, end);
        ByteBuffer metadata = ByteBuffer.allocate(ThermalPayload.SOURCE_BYTES);
        ThermalPayload.putSource(metadata, capture.frame);
        writeChunk(out, SOURCE, metadata.array(), metadata.capacity());
        writeChunk(out, DISPLAY, new byte[]{(byte) (Float.isFinite(capture.low) ? 1 : 0)}, 1);
        if (capture.frame.thermalOutput) writeChunk(out, PLANE, new byte[]{1}, 1);
        try (ThermalPayload.Encoder encoder = new ThermalPayload.Encoder()) {
            int size = encoder.encode(capture);
            writeChunk(out, TEMPERATURE, encoder.bytes(), size);
        }
        out.write(png, end, 12);
    }

    static ThermalCapture read(InputStream source) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(source));
        if (in.readLong() != SIGNATURE) return null;
        boolean nativeSize = false;
        ByteBuffer metadata = null;
        int display = ThermalPayload.LEGACY_DISPLAY;
        boolean thermalOutput = false;
        for (;;) {
            int length = in.readInt(), type = in.readInt();
            if (length < 0) throw new IOException("Invalid PNG chunk");
            if (type == IHDR && length == 13) {
                int width = in.readInt(), height = in.readInt();
                nativeSize = (width == ThermalFrame.WIDTH || width == ThermalFrame.WIDTH - ThermalFrame.LEGACY_LEFT_MASK)
                        && height == ThermalFrame.HEIGHT;
                length -= 8;
            }
            if (type == IEND) return null;
            if ((type == TEMPERATURE || type == SOURCE || type == DISPLAY || type == PLANE) && nativeSize) {
                if (length > ThermalPayload.MAX_PACKED || (type == SOURCE && length != ThermalPayload.SOURCE_BYTES)
                        || ((type == DISPLAY || type == PLANE) && length != 1))
                    throw new IOException("Invalid PNG record length");
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                if (in.readInt() != checksum(type, bytes, length)) throw new IOException("PNG checksum");
                if (type == TEMPERATURE) return ThermalPayload.decode(bytes, metadata, display, thermalOutput);
                if (type == PLANE) {
                    if (bytes[0] != 1) throw new IOException("Unknown temperature plane");
                    thermalOutput = true;
                } else if (type == DISPLAY) display = bytes[0] & 255;
                else metadata = ByteBuffer.wrap(bytes);
                continue;
            }
            for (long left = (long) length + 4; left > 0;) {
                long n = in.skip(left);
                if (n == 0) {
                    if (in.read() == -1) throw new EOFException();
                    n = 1;
                }
                left -= n;
            }
        }
    }

    private static void writeChunk(DataOutputStream out, int type, byte[] payload, int size) throws IOException {
        out.writeInt(size);
        out.writeInt(type);
        out.write(payload, 0, size);
        out.writeInt(checksum(type, payload, size));
    }

    private static int checksum(int type, byte[] payload, int size) {
        CRC32 crc = new CRC32();
        crc.update(type >>> 24);
        crc.update(type >>> 16);
        crc.update(type >>> 8);
        crc.update(type);
        crc.update(payload, 0, size);
        return (int) crc.getValue();
    }
}
