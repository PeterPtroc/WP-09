package org.thermal.wp09;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.thermal.wp09.ThermalFrame.*;

/** V12 marks bad sites with the sign bit in the first factory K/B plane. */
final class BlindPixels {
    private final int[] sites;
    private final int[] neighbors = new int[8];

    private BlindPixels(int[] sites) { this.sites = sites; }

    static BlindPixels fromFactory(byte[] kb) {
        if (kb == null || kb.length != PIXELS * 4) return null;
        int[] flagged = new int[128];
        int count = 0;
        for (int p = 0; p < PIXELS; p++) {
            if (kb[2 * p + 1] >= 0) continue;
            if (count == flagged.length) return null;
            flagged[count++] = p;
        }
        return new BlindPixels(Arrays.copyOf(flagged, count));
    }

    boolean contains(int pixel) { return Arrays.binarySearch(sites, pixel) >= 0; }

    void correct(ByteBuffer words) {
        for (int p : sites) {
            int count = 0, x = p % WIDTH, y = p / WIDTH;
            for (int row = Math.max(0, y - 1); row <= Math.min(HEIGHT - 1, y + 1); row++) {
                for (int col = Math.max(0, x - 1); col <= Math.min(WIDTH - 1, x + 1); col++) {
                    int other = row * WIDTH + col;
                    if (!contains(other)) neighbors[count++] = Short.toUnsignedInt(words.getShort(2 * other));
                }
            }
            Arrays.sort(neighbors, 0, count);
            int value = 0;
            if (count >= 4) {
                for (int i = 1; i < count - 1; i++) value += neighbors[i];
                value /= count - 2;
            } else if (count > 0) value = neighbors[count / 2];
            words.putShort(p * 2, (short) value);
        }
    }
}
