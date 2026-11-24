package org.thermal.wp09;

import java.util.Arrays;

final class FusionRegistration {
    static final int WIDTH = 96;
    static final int HEIGHT = WIDTH * ThermalFrame.HEIGHT / ThermalFrame.WIDTH;
    static final float[] TARGET_X = {0.27f, 0.73f, 0.50f};
    static final float[] TARGET_Y = {0.54f, 0.54f, 0.25f};
    private static final int TRACK_RADIUS = 5;

    static boolean paired(long thermalAt, long cameraAt) {
        return thermalAt > 0 && cameraAt > 0 && Math.abs(thermalAt - cameraAt) <= 95_000_000L;
    }

    static final class Image {
        final int height;
        final float[] values;
        final float changeThreshold;

        Image(int height, float changeThreshold) {
            this.height = height;
            this.changeThreshold = changeThreshold;
            values = new float[WIDTH * height];
        }

        static Image fromThermal(ThermalFrame frame) {
            boolean radiometric = frame.hasPixelTemperatures();
            Image result = new Image(HEIGHT, radiometric ? 0.9f : 12f);
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int startX = x * ThermalFrame.WIDTH / WIDTH;
                    int endX = (x + 1) * ThermalFrame.WIDTH / WIDTH;
                    int startY = y * ThermalFrame.HEIGHT / HEIGHT;
                    int endY = (y + 1) * ThermalFrame.HEIGHT / HEIGHT;
                    float sum = 0;
                    int count = 0;
                    for (int row = startY; row < endY; row++) {
                        for (int col = startX; col < endX; col++) {
                            int pixel = row * ThermalFrame.WIDTH + col;
                            float value = radiometric ? frame.readings[pixel] : (frame.preview[pixel] & 0xff);
                            if (Float.isFinite(value)) {
                                sum += value;
                                count++;
                            }
                        }
                    }
                    result.values[y * WIDTH + x] = count > 0 ? sum / count : Float.NaN;
                }
            }
            return result;
        }

        static Image fromVisible(int[] argb, int width, int height) {
            Image result = new Image(Math.max(1, Math.round(WIDTH * height / (float) width)), 13f);
            for (int y = 0; y < result.height; y++) {
                int row0 = Math.min(height - 1, (4 * y + 1) * height / (4 * result.height));
                int row1 = Math.min(height - 1, (4 * y + 3) * height / (4 * result.height));
                for (int x = 0; x < WIDTH; x++) {
                    int col0 = Math.min(width - 1, (4 * x + 1) * width / (4 * WIDTH));
                    int col1 = Math.min(width - 1, (4 * x + 3) * width / (4 * WIDTH));
                    result.values[y * WIDTH + x] = (luma(argb[row0 * width + col0])
                            + luma(argb[row0 * width + col1]) + luma(argb[row1 * width + col0])
                            + luma(argb[row1 * width + col1])) / 1024f;
                }
            }
            return result;
        }

        private static int luma(int argb) {
            return 77 * ((argb >>> 16) & 255) + 150 * ((argb >>> 8) & 255) + 29 * (argb & 255);
        }
    }

    // Maps camera sample coordinates to the thermal sample grid, with a common scale on both axes.
    static final class Transform {
        final float a, b, x, y;

        Transform(float a, float b, float x, float y) {
            this.a = a;
            this.b = b;
            this.x = x;
            this.y = y;
        }

        Transform translated(float dx, float dy) {
            return new Transform(a, b, x + dx, y + dy);
        }

        private boolean valid() {
            float scaleSquared = a * a + b * b;
            return scaleSquared >= 0.035f && scaleSquared <= 1024f
                    && Math.abs(x) < WIDTH * 32 && Math.abs(y) < HEIGHT * 32;
        }

        String encode() {
            return a + "," + b + "," + x + "," + y;
        }

        static Transform decode(String value) {
            if (value == null) return null;
            String[] parts = value.split(",", -1);
            if (parts.length != 4) return null;
            try {
                Transform transform = new Transform(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                        Float.parseFloat(parts[2]), Float.parseFloat(parts[3]));
                return transform.valid() ? transform : null;
            } catch (NumberFormatException error) {
                return null;
            }
        }
    }

    static final class Calibration {
        private Image thermalBackground;
        private Image visibleBackground;
        private Point previousThermal;
        private Point previousVisible;
        private int stableCount;
        private int index = -1;
        private final Point[] thermalPoints = new Point[3];
        private final Point[] visiblePoints = new Point[3];

        // Returns -1 while reading the empty background, 0–2 for the three circles, and 3 when done.
        int observe(Image thermal, Image visible) {
            if (index < 0) {
                boolean steady = thermalBackground != null && visibleBackground != null
                        && changeFraction(thermalBackground, thermal) <= 0.035f
                        && changeFraction(visibleBackground, visible) <= 0.035f;
                thermalBackground = thermal;
                visibleBackground = visible;
                stableCount = steady ? stableCount + 1 : 0;
                if (stableCount >= 3) {
                    index = 0;
                    stableCount = 0;
                }
                return index;
            }
            if (index >= thermalPoints.length) return index;
            Point heat = foreground(thermalBackground, thermal);
            Point light = foreground(visibleBackground, visible);
            if (heat == null || light == null || distance(heat.x - WIDTH * TARGET_X[index],
                    heat.y - HEIGHT * TARGET_Y[index]) > 9) {
                previousThermal = previousVisible = null;
                stableCount = 0;
                return index;
            }

            boolean steady = previousThermal != null && previousVisible != null
                    && distance(heat.x - previousThermal.x, heat.y - previousThermal.y) <= 2.2f
                    && distance(light.x - previousVisible.x, light.y - previousVisible.y) <= 2.2f;
            stableCount = steady ? stableCount + 1 : 1;
            previousThermal = heat;
            previousVisible = light;
            if (stableCount >= 3) {
                thermalPoints[index] = heat;
                visiblePoints[index] = light;
                index++;
                previousThermal = previousVisible = null;
                stableCount = 0;
            }
            return index;
        }

        Transform result() {
            if (index != thermalPoints.length) return null;
            Transform firstTwo = fit(visiblePoints, thermalPoints, 2);
            if (firstTwo == null || error(firstTwo, visiblePoints[2], thermalPoints[2]) > 4.5f) return null;
            Transform refined = fit(visiblePoints, thermalPoints, 3);
            if (refined == null || refined.a * refined.a + refined.b * refined.b < .16f
                    || refined.a * refined.a + refined.b * refined.b > 16) return null;
            for (int i = 0; i < thermalPoints.length; i++) {
                if (error(refined, visiblePoints[i], thermalPoints[i]) > 2.5f) return null;
            }
            return refined;
        }
    }

    static final class Point {
        final float x, y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    static Transform fit(Point[] camera, Point[] thermal, int count) {
        float cx = 0, cy = 0, tx = 0, ty = 0;
        for (int i = 0; i < count; i++) {
            cx += camera[i].x / count;
            cy += camera[i].y / count;
            tx += thermal[i].x / count;
            ty += thermal[i].y / count;
        }
        float dot = 0, cross = 0, sumSquared = 0;
        for (int i = 0; i < count; i++) {
            float vx = camera[i].x - cx;
            float vy = camera[i].y - cy;
            float ux = thermal[i].x - tx;
            float uy = thermal[i].y - ty;
            dot += vx * ux + vy * uy;
            cross += vx * uy - vy * ux;
            sumSquared += vx * vx + vy * vy;
        }
        if (sumSquared < 36) return null;
        float a = dot / sumSquared;
        float b = cross / sumSquared;
        Transform transform = new Transform(a, b, tx - a * cx + b * cy, ty - b * cx - a * cy);
        return transform.valid() ? transform : null;
    }

    static float error(Transform transform, Point camera, Point thermal) {
        return distance(transform.a * camera.x - transform.b * camera.y + transform.x - thermal.x,
                transform.b * camera.x + transform.a * camera.y + transform.y - thermal.y);
    }

    private static float distance(float x, float y) {
        return (float) Math.hypot(x, y);
    }

    private static float changeFraction(Image background, Image image) {
        int[] mask = changes(background, image);
        if (mask == null) return 1;
        int count = 0;
        for (int value : mask) if (value > 0) count++;
        return count / (float) mask.length;
    }

    private static int[] changes(Image background, Image image) {
        if (background.height != image.height || background.changeThreshold != image.changeThreshold) return null;
        int length = image.values.length;
        float[] delta = new float[length];
        for (int i = 0; i < length; i++) delta[i] = image.values[i] - background.values[i];
        float[] ordered = delta.clone();
        Arrays.sort(ordered);
        float median = ordered[length / 2];
        if (!Float.isFinite(median)) return null;
        for (int i = 0; i < length; i++) {
            delta[i] = Math.abs(delta[i] - median);
            ordered[i] = delta[i];
        }
        Arrays.sort(ordered);
        float noise = ordered[length * 3 / 4];
        if (!Float.isFinite(noise) || noise > image.changeThreshold * 1.6f) return null;
        float threshold = Math.max(image.changeThreshold, noise * 2.8f);
        int[] mask = new int[length];
        for (int y = 1; y < image.height - 1; y++) {
            for (int x = 1; x < WIDTH - 1; x++) {
                int i = y * WIDTH + x;
                if (delta[i] >= threshold) mask[i] = WIDTH + image.height;
            }
        }
        return mask;
    }

    private static Point foreground(Image background, Image image) {
        int[] mask = changes(background, image);
        if (mask == null) return null;
        int area = 0;
        for (int y = 1; y < image.height - 1; y++) {
            for (int x = 1; x < WIDTH - 1; x++) {
                int i = y * WIDTH + x;
                if (mask[i] == 0) continue;
                area++;
                mask[i] = 1 + Math.min(mask[i - 1], mask[i - WIDTH]);
            }
        }
        if (area < 30 || area > mask.length / 3) return null;
        // Give the broad part of the palm more weight than fingers, forearm and shadow edges.
        float sumX = 0, sumY = 0, weightSum = 0;
        for (int y = image.height - 2; y > 0; y--) {
            for (int x = WIDTH - 2; x > 0; x--) {
                int i = y * WIDTH + x;
                if (mask[i] == 0) continue;
                mask[i] = Math.min(mask[i], 1 + Math.min(mask[i + 1], mask[i + WIDTH]));
                int radius = mask[i] - 2;
                if (radius <= 0) continue;
                float weight = radius * radius * radius;
                sumX += (x + 0.5f) * weight;
                sumY += (y + 0.5f) * weight;
                weightSum += weight;
            }
        }
        return weightSum >= 12 ? new Point(sumX / weightSum, sumY / weightSum) : null;
    }

    static Transform track(Image thermal, Image visible, Transform base) {
        Edges heat = new Edges(thermal);
        Edges light = new Edges(visible);
        int[] samples = heat.sample();
        if (samples.length < 18 || light.threshold == Float.POSITIVE_INFINITY) return null;
        int side = TRACK_RADIUS * 2 + 1;
        float[] scores = new float[side * side];
        float best = 0;
        int bestX = 0, bestY = 0;
        for (int dy = -TRACK_RADIUS; dy <= TRACK_RADIUS; dy++) {
            for (int dx = -TRACK_RADIUS; dx <= TRACK_RADIUS; dx++) {
                float score = score(heat, light, samples, base, dx, dy);
                scores[(dy + TRACK_RADIUS) * side + dx + TRACK_RADIUS] = score;
                if (score > best) {
                    best = score;
                    bestX = dx;
                    bestY = dy;
                }
            }
        }
        if (best < 0.45f || Math.abs(bestX) == TRACK_RADIUS || Math.abs(bestY) == TRACK_RADIUS) return null;
        for (int dy = -TRACK_RADIUS; dy <= TRACK_RADIUS; dy++) {
            for (int dx = -TRACK_RADIUS; dx <= TRACK_RADIUS; dx++) {
                if (Math.max(Math.abs(dx - bestX), Math.abs(dy - bestY)) >= 3
                        && scores[(dy + TRACK_RADIUS) * side + dx + TRACK_RADIUS] > best - 0.08f) return null;
            }
        }
        return base.translated(bestX, bestY);
    }

    private static float score(Edges heat, Edges light, int[] samples, Transform base, int dx, int dy) {
        float inverse = 1 / (base.a * base.a + base.b * base.b);
        float rotationScale = (float) Math.sqrt(inverse);
        float total = 0;
        int horizontal = 0;
        int vertical = 0;
        int minX = WIDTH, maxX = 0, minY = HEIGHT, maxY = 0;
        for (int sample : samples) {
            int x = sample % WIDTH;
            int y = sample / WIDTH;
            float ux = x + 0.5f - base.x - dx;
            float uy = y + 0.5f - base.y - dy;
            int vx = (int) Math.floor((base.a * ux + base.b * uy) * inverse);
            int vy = (int) Math.floor((-base.b * ux + base.a * uy) * inverse);
            if (vx < 1 || vx >= WIDTH - 1 || vy < 1 || vy >= light.height - 1) continue;
            int other = vy * WIDTH + vx;
            if (light.magnitude[other] < light.threshold) continue;
            float projectedX = (base.a * light.x[other] - base.b * light.y[other]) * rotationScale;
            float projectedY = (base.b * light.x[other] + base.a * light.y[other]) * rotationScale;
            float similarity = Math.abs(heat.x[sample] * projectedX + heat.y[sample] * projectedY);
            if (similarity < 0.85f) continue;
            total += similarity;
            if (Math.abs(heat.x[sample]) >= 0.65f) horizontal++;
            if (Math.abs(heat.y[sample]) >= 0.65f) vertical++;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return horizontal >= 4 && vertical >= 4 && maxX - minX >= 17 && maxY - minY >= 12
                ? total / samples.length : 0;
    }

    private static final class Edges {
        final int height;
        final float[] x, y, magnitude;
        final float threshold;

        Edges(Image image) {
            height = image.height;
            x = new float[image.values.length];
            y = new float[x.length];
            magnitude = new float[x.length];
            for (int row = 1; row < height - 1; row++) {
                for (int col = 1; col < WIDTH - 1; col++) {
                    int i = row * WIDTH + col;
                    float gx = image.values[i + 1] - image.values[i - 1];
                    float gy = image.values[i + WIDTH] - image.values[i - WIDTH];
                    float length = distance(gx, gy);
                    if (!Float.isFinite(length) || length <= 0) continue;
                    x[i] = gx / length;
                    y[i] = gy / length;
                    magnitude[i] = length;
                }
            }
            float[] ordered = magnitude.clone();
            Arrays.sort(ordered);
            float level = ordered[ordered.length * 9 / 10];
            float floor = image.changeThreshold * 0.4f;
            threshold = ordered[ordered.length - 1] >= floor
                    ? Math.max(floor, level * 0.65f) : Float.POSITIVE_INFINITY;
        }

        int[] sample() {
            int[] samples = new int[magnitude.length];
            int count = 0;
            for (int row = 3; row < height - 3; row += 3) {
                for (int col = 3; col < WIDTH - 3; col += 3) {
                    int best = row * WIDTH + col;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int candidate = (row + dy) * WIDTH + col + dx;
                            if (magnitude[candidate] > magnitude[best]) best = candidate;
                        }
                    }
                    if (magnitude[best] >= threshold) samples[count++] = best;
                }
            }
            return Arrays.copyOf(samples, count);
        }
    }

    private FusionRegistration() {}
}
