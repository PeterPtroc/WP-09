package org.thermal.wp09;


final class Measurement {
    enum Tool { POINT, LINE, AREA }

    Tool tool = Tool.POINT;
    int start = ThermalFrame.CENTER, end = start;
    float minimum, maximum, mean;
    int count;
    boolean includesRepair;
    final float[] profile = new float[ThermalFrame.WIDTH];
    int profileSize;

    void select(Tool next) {
        if (tool == next) return;
        tool = next;
        int x = start % ThermalFrame.WIDTH;
        int y = start / ThermalFrame.WIDTH;
        end = tool == Tool.POINT ? start : pixel(x > 200 ? x - 48 : x + 48,
                tool == Tool.LINE ? y : y > 150 ? y - 32 : y + 32);
    }

    static int pixel(int x, int y) {
        return Math.max(0, Math.min(ThermalFrame.HEIGHT - 1, y)) * ThermalFrame.WIDTH
                + Math.max(0, Math.min(ThermalFrame.WIDTH - 1, x));
    }

    void evaluate(ThermalFrame frame, Calibration calibration) {
        count = 0;
        includesRepair = false;
        profileSize = 0;
        minimum = Float.POSITIVE_INFINITY;
        maximum = Float.NEGATIVE_INFINITY;
        double sum = 0;
        if (frame != null && frame.hasTemperature()) {
            int ax = start % ThermalFrame.WIDTH, ay = start / ThermalFrame.WIDTH;
            int bx = end % ThermalFrame.WIDTH, by = end / ThermalFrame.WIDTH;
            if (tool == Tool.POINT) sum = add(calibration.apply(frame.temperatureAt(start)));
            else if (tool == Tool.LINE) {
                int steps = Math.max(Math.abs(bx - ax), Math.abs(by - ay));
                for (int i = 0; i <= steps; i++) {
                    float t = steps == 0 ? 0 : (float) i / steps;
                    int index = pixel(Math.round(ax + (bx - ax) * t), Math.round(ay + (by - ay) * t));
                    float value = calibration.apply(frame.temperatureAt(index));
                    includesRepair |= frame.repairedSites.length > 0 && frame.isEstimatedAt(index);
                    profile[profileSize++] = value;
                    sum += add(value);
                }
            } else {
                int left = Math.min(ax, bx), right = Math.max(ax, bx);
                int top = Math.min(ay, by), bottom = Math.max(ay, by);
                for (int p : frame.repairedSites)
                    includesRepair |= p % ThermalFrame.WIDTH >= left && p % ThermalFrame.WIDTH <= right
                            && p / ThermalFrame.WIDTH >= top && p / ThermalFrame.WIDTH <= bottom;
                for (int y = top; y <= bottom; y++) {
                    int row = y * ThermalFrame.WIDTH;
                    for (int x = left; x <= right; x++) sum += add(calibration.apply(frame.temperatureAt(row + x)));
                }
            }
        }
        mean = count > 0 ? (float) (sum / count) : Float.NaN;
        if (count == 0) minimum = maximum = Float.NaN;
    }

    private float add(float value) {
        if (!Float.isFinite(value)) return 0;
        minimum = Math.min(minimum, value);
        maximum = Math.max(maximum, value);
        count++;
        return value;
    }
}
