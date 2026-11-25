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
        float spread = spatialSpread(frame.readings);
        float range = frame.maximum - frame.minimum;
        boolean flattened = hasScene && spread < .22f &&
                ((sceneSpread - spread > .04f && spread < sceneSpread * .45f)
                || (range < .8f && sceneRange > Math.max(2f, 4 * range))
                || (Math.abs(frame.average - sceneMean) > .8f && spread < .13f));
        // The status flag lives inside the firmware. In mode 4, shutter-like
        // contrast loss plus a contradictory firmware summary calls for a fresh
        // mode-5 reference. A confirmed flat wall remains a valid exposure.
        if (flattened && !Decoder.matchesTelemetry(frame, words)) return true;
        remember(frame, spread);
        return false;
    }

    void verified(ThermalFrame frame) {
        holdSince = -1;
        remember(frame, spatialSpread(frame.readings));
    }

    private void remember(ThermalFrame frame, float spread) {
        hasScene = true;
        sceneSpread = spread;
        sceneMean = frame.average;
        sceneRange = frame.maximum - frame.minimum;
    }

    // Average 48 distributed patches before measuring spatial contrast so that
    // individual noisy pixels do not make a shutter look like a textured scene.
    private static float spatialSpread(float[] temperatures) {
        double mean = 0, squared = 0;
        int count = 0;
        for (int y = 8; y < HEIGHT; y += 32) for (int x = 8; x < WIDTH; x += 32) {
            float sum = 0;
            for (int dy = 0; dy < 16; dy += 4) for (int dx = 0; dx < 16; dx += 4)
                sum += temperatures[(y + dy) * WIDTH + x + dx];
            double value = sum / 16;
            double delta = value - mean;
            mean += delta / ++count;
            squared += delta * (value - mean);
        }
        return (float) Math.sqrt(squared / count);
    }
}
