package org.thermal.wp09;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.thermal.wp09.ThermalFrame.*;

/** V12 marks bad sites with the sign bit in the first factory K/B plane. */
final class BlindPixels {
    static final int[] NONE = {};
    static final int MAX_USER = 128;
    private final int[] sites;
    private final BlindPixels factory;
    private final int[] neighbors = new int[8];

    private BlindPixels(int[] sites) { this(sites, null); }

    BlindPixels(int[] sites, BlindPixels factory) {
        this.sites = sites;
        this.factory = factory;
    }

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
                    if (available(other)) neighbors[count++] = Short.toUnsignedInt(words.getShort(2 * other));
                }
            }
            Arrays.sort(neighbors, 0, count);
            int value = 0;
            if (count >= 4) {
                for (int i = 1; i < count - 1; i++) value += neighbors[i];
                value /= count - 2;
            } else if (count > 0) value = neighbors[count / 2];
            else {
                // A contiguous marked cluster may have no healthy immediate neighbor.
                // Expand to the nearest ring with original observations.
                for (int r = 2; count == 0 && r < WIDTH; r++) {
                    for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) != r || x + dx < 0 || x + dx >= WIDTH
                                || y + dy < 0 || y + dy >= HEIGHT) continue;
                        int other = (y + dy) * WIDTH + x + dx;
                        if (available(other)) {
                            value += Short.toUnsignedInt(words.getShort(2 * other));
                            count++;
                        }
                    }
                }
                if (count > 0) value /= count;
            }
            words.putShort(p * 2, (short) value);
        }
    }

    private boolean available(int p) { return !contains(p) && (factory == null || !factory.contains(p)); }
}
