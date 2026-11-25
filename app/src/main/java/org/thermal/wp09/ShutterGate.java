package org.thermal.wp09;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.thermal.wp09.ThermalFrame.*;

/** Observations shared by the preview and recording readers of one XWild stream. */
final class ShutterGate {
    private static final long SETTLE_NS = 100_000_000L;
    private long holdSince = -1, stableSince, lastPreview;
    private boolean manual, previewGap, hasScene;
    private float sceneSpread, sceneMean, sceneRange;
    private static final int COLS = 8, ROWS = 6;
    private float[] prior = new float[COLS * ROWS], tiles = new float[COLS * ROWS];

    void start(long stamp, boolean commanded) {
        holdSince = lastPreview = stamp;
        stableSince = -1;
        manual = commanded;
        previewGap = false;
    }

    boolean holding() { return holdSince >= 0; }

    // Called only for verified mode-5 YUYV exposures. V12's processing callback
    // withholds these during calibration. Allow buffered images to drain and
    // allow the firmware's 100 ms post-opening settling interval.
    boolean referenceReady(long stamp) {
        if (!holding()) return true;
        if (stamp - lastPreview >= 140_000_000L) {
            previewGap = true;
            stableSince = -1;
        }
        lastPreview = stamp;
        if (stableSince < 0) stableSince = stamp;
        long elapsed = stamp - holdSince;
        return stamp - stableSince >= SETTLE_NS && (!manual ||
                elapsed >= (previewGap ? 400_000_000L : 1_500_000_000L));
    }

    boolean suspect(ThermalFrame frame, byte[] wire) {
        ByteBuffer words = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
        float spread = sampleTiles(frame.readings);
        float range = frame.maximum - frame.minimum;
        boolean flattened = hasScene && spread < .22f &&
                ((sceneSpread - spread > .04f && spread < sceneSpread * .45f)
                || (range < .8f && sceneRange > Math.max(2f, 4 * range))
                || (Math.abs(frame.average - sceneMean) > .8f && spread < .13f));
        // The status flag lives inside the firmware. In mode 4, shutter-like
        // contrast loss plus a contradictory firmware summary calls for a fresh
        // mode-5 reference. A confirmed flat wall remains a valid exposure.
        if ((flattened || hasScene && partialClosure()) && !Decoder.matchesTelemetry(frame, words)) return true;
        remember(frame, spread);
        return false;
    }

    void verified(ThermalFrame frame) {
        holdSince = -1;
        remember(frame, sampleTiles(frame.readings));
    }

    private void remember(ThermalFrame frame, float spread) {
        hasScene = true;
        sceneSpread = spread;
        sceneMean = frame.average;
        sceneRange = frame.maximum - frame.minimum;
        float[] tmp = prior;
        prior = tiles;
        tiles = tmp;
    }

    private boolean partialClosure() {
        return coveredEdge(ROWS, COLS, COLS, 1) || coveredEdge(COLS, ROWS, 1, COLS);
    }

    // On a partial closure adjacent strips at one edge become the same flat
    // surface while part of the old scene is still in view.
    private boolean coveredEdge(int stripes, int length, int across, int along) {
        int mask = 0, unchanged = 0;
        float first = Float.NaN;
        for (int s = 0; s < stripes; s++) {
            float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
            float oldLo = lo, oldHi = hi, difference = 0;
            for (int j = 0; j < length; j++) {
                int i = s * across + j * along;
                lo = Math.min(lo, tiles[i]); hi = Math.max(hi, tiles[i]);
                oldLo = Math.min(oldLo, prior[i]); oldHi = Math.max(oldHi, prior[i]);
                difference += Math.abs(tiles[i] - prior[i]);
            }
            float oldContrast = oldHi - oldLo;
            if (oldContrast > .40f && hi - lo < Math.min(.24f, oldContrast * .25f)
                    && difference > length * Math.max(.07f, oldContrast * .10f)) {
                if (!Float.isFinite(first)) first = (hi + lo) * .5f;
                if (Math.abs((hi + lo) * .5f - first) < .20f) mask |= 1 << s;
            } else if (difference < length * .25f && hi - lo > .45f) unchanged++;
        }
        int end = 3 << (stripes - 2);
        return unchanged > 0 && ((mask & 3) == 3 || (mask & end) == end);
    }

    // Average 48 distributed patches before measuring spatial contrast so that
    // individual noisy pixels do not make a shutter look like a textured scene.
    private float sampleTiles(float[] temperatures) {
        double mean = 0, squared = 0;
        int count = 0;
        for (int y = 8; y < HEIGHT; y += 32) for (int x = 8; x < WIDTH; x += 32) {
            float sum = 0;
            for (int dy = 0; dy < 16; dy += 4) for (int dx = 0; dx < 16; dx += 4)
                sum += temperatures[(y + dy) * WIDTH + x + dx];
            double value = sum / 16;
            tiles[count] = (float) value;
            double delta = value - mean;
            mean += delta / ++count;
            squared += delta * (value - mean);
        }
        return (float) Math.sqrt(squared / count);
    }
}
