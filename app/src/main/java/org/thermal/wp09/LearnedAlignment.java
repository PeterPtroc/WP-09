package org.thermal.wp09;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.Random;
import java.util.function.BooleanSupplier;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import org.thermal.wp09.FusionRegistration.Point;
import org.thermal.wp09.FusionRegistration.Transform;

final class LearnedAlignment {
    private static final int W = ThermalFrame.WIDTH, H = ThermalFrame.HEIGHT;
    private static final int STRIDE = 8;
    private static final float UNIT = FusionRegistration.WIDTH / (float) W;
    private static final RectF TARGET = new RectF(0, 0, W, H);
    private static final float TOLERANCE = 5 * UNIT;

    private static final class OrientationHint {
        int angle, support;
        OrientationHint(int initialAngle) { angle = initialAngle; }
    }

    static Transform estimate(Context context, ThermalView.Pair pair, BooleanSupplier cancelled)
            throws IOException, OrtException {
        int[] heat = pair.thermalPixels();
        float[] input = new float[2 * W * H];
        for (int i = 0; i < heat.length; i++) input[i + W * H] = (heat[i] & 255) / 255f;
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (AssetFileDescriptor asset = context.getAssets().openFd("minima-loftr-coarse.onnx");
             FileInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(
                     ParcelFileDescriptor.dup(asset.getFileDescriptor()));
             OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
            options.setInterOpNumThreads(1);
            try (OrtSession session = environment.createSession(stream.getChannel().map(FileChannel.MapMode.READ_ONLY,
                    asset.getStartOffset(), asset.getLength()), options)) {
                Bitmap canvasImage = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                try {
                    // Also search neutral poses. A previously adjusted slider can crop out the common region.
                    int primary = pair.angle + pair.rotation;
                    OrientationHint hint = new OrientationHint(primary);
                    if (pair.zoom != 1 || pair.x != 0 || pair.y != 0 || pair.rotation != 0) {
                        if (cancelled.getAsBoolean()) return null;
                        Transform fit = match(environment, session, pair.camera, canvasImage, input,
                                primary, pair.zoom, pair.x, pair.y, hint, false);
                        if (fit != null) return fit;
                    }
                    for (float zoom : new float[]{1f, 2.5f}) {
                        for (int delta : new int[]{0, 90, 270, 180}) {
                            if (cancelled.getAsBoolean()) return null;
                            Transform fit = match(environment, session, pair.camera, canvasImage, input,
                                    pair.angle + delta, zoom, 0, 0, hint, false);
                            if (fit != null) return fit;
                        }
                    }
                    // For a narrow IR lens the common area can lie away from the camera center.
                    int angle = hint.angle;
                    float[][] offsets = {{0, 0}, {0, .75f}, {0, -.75f}, {.75f, 0}, {-.75f, 0}};
                    for (float[] offset : offsets) {
                        if (cancelled.getAsBoolean()) return null;
                        Transform fit = match(environment, session, pair.camera, canvasImage, input,
                                angle, 4f, offset[0], offset[1], hint, true);
                        if (fit != null) return fit;
                    }
                } finally {
                    canvasImage.recycle();
                }
            }
        }
        return null;
    }

    private static Transform match(OrtEnvironment env, OrtSession session, Bitmap source, Bitmap warped,
                                   float[] input, int angle, float zoom, float dx, float dy,
                                   OrientationHint hint, boolean tiled) throws OrtException {
        Matrix matrix = new Matrix();
        ThermalView.mapCamera(matrix, source.getWidth(), source.getHeight(), TARGET, angle, zoom, dx, dy);
        warped.eraseColor(0xff000000);
        new Canvas(warped).drawBitmap(source, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] pixels = new int[W * H];
        warped.getPixels(pixels, 0, W, 0, 0, W, H);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            input[i] = (77 * ((p >>> 16) & 255) + 150 * ((p >>> 8) & 255) + 29 * (p & 255)) / 65280f;
        }
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[]{2, 1, H, W});
             OrtSession.Result output = session.run(Collections.singletonMap("images", tensor))) {
            Matrix inverse = new Matrix();
            if (!matrix.invert(inverse)) return null;
            return fit((float[][]) output.get(0).getValue(), inverse, source.getWidth(), source.getHeight(), angle, hint, tiled);
        }
    }

    private static Transform fit(float[][] matches, Matrix inverse, int width, int height, int angle,
                                 OrientationHint hint, boolean tiled) {
        Point[] source = new Point[matches.length], target = new Point[matches.length];
        int size = 0;
        int gridW = W / STRIDE;
        float[] xy = new float[2];
        for (int i = 0; i < matches.length; i++) {
            if (matches[i][1] < .25f) continue;
            int j = (int) matches[i][0];
            xy[0] = (i % gridW) * STRIDE + .5f;
            xy[1] = (i / gridW) * STRIDE + .5f;
            inverse.mapPoints(xy);
            if (xy[0] < 0 || xy[0] >= width || xy[1] < 0 || xy[1] >= height) continue;
            source[size] = new Point(xy[0] * FusionRegistration.WIDTH / width,
                    xy[1] * Math.round(FusionRegistration.WIDTH * height / (float) width) / height);
            target[size++] = new Point(((j % gridW) * STRIDE + .5f) * UNIT, ((j / gridW) * STRIDE + .5f) * UNIT);
        }
        if (size < 12) return null;
        Point[] left = new Point[size], right = new Point[size];
        Random random = new Random(9);
        Transform best = null;
        int bestCount = 0;
        for (int k = 0; k < 256; k++) {
            for (int n = 0; n < 2; n++) {
                int index = random.nextInt(size);
                left[n] = source[index];
                right[n] = target[index];
            }
            Transform trial = FusionRegistration.fit(left, right, 2);
            if (trial == null) continue;
            int count = 0;
            for (int i = 0; i < size; i++) {
                if (FusionRegistration.error(trial, source[i], target[i]) <= TOLERANCE) count++;
            }
            if (count > bestCount) {
                best = trial;
                bestCount = count;
            }
        }
        if (!tiled && bestCount > hint.support) {
            hint.angle = angle;
            hint.support = bestCount;
        }
        if (bestCount < Math.max(tiled ? 24 : 12, size * (tiled ? .55f : .5f))) return null;
        int count = 0;
        int occupied = 0;
        float sx = 0, sy = 0, xx = 0, yy = 0, xySum = 0;
        for (int i = 0; i < size; i++) {
            if (FusionRegistration.error(best, source[i], target[i]) > TOLERANCE) continue;
            Point p = target[i];
            left[count] = source[i];
            right[count++] = p;
            occupied |= 1 << ((int) (4 * p.x / FusionRegistration.WIDTH)
                    + 4 * (int) (4 * p.y / FusionRegistration.HEIGHT));
            sx += p.x;
            sy += p.y;
            xx += p.x * p.x;
            yy += p.y * p.y;
            xySum += p.x * p.y;
        }
        // Reject support concentrated on one object edge even if it spans the frame diagonally.
        xx = xx / count - sx / count * (sx / count);
        yy = yy / count - sy / count * (sy / count);
        xySum = xySum / count - sx / count * (sy / count);
        double minorVariance = .5 * (xx + yy - Math.hypot(xx - yy, 2 * xySum));
        if (Integer.bitCount(occupied) < 4 || minorVariance < 25) return null;
        return FusionRegistration.fit(left, right, count);
    }
}
