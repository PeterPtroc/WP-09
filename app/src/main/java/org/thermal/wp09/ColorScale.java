package org.thermal.wp09;

/** Display contrast and an optional window in calibrated Celsius. */
final class ColorScale {
    static final int MIN_CONTRAST = 50, NORMAL = 100, MAX_CONTRAST = 150;
    static final ColorScale AUTO = new ColorScale(NORMAL, Float.NaN, Float.NaN);
    final int contrast;
    final float low, high;

    ColorScale(int contrast, float low, float high) {
        this.contrast = Math.max(MIN_CONTRAST, Math.min(MAX_CONTRAST, contrast));
        boolean valid = Float.isFinite(low) && Float.isFinite(high) && low <= high;
        this.low = valid ? low : Float.NaN;
        this.high = valid ? high : Float.NaN;
    }

    boolean locked() { return Float.isFinite(low); }

    ColorScale withContrast(int value) { return new ColorScale(value, low, high); }

    ColorScale automatic() { return new ColorScale(contrast, Float.NaN, Float.NaN); }

    ColorScale lockAt(ThermalCapture capture) {
        if (capture == null || !capture.frame.hasPixelTemperatures()) return this;
        ThermalFrame frame = capture.frame;
        float lower = frame.minimum, upper = frame.maximum;
        if (frame.display != null && Float.isFinite(frame.display.autoLow) && Float.isFinite(frame.display.autoHigh)) {
            lower = frame.display.autoLow;
            upper = frame.display.autoHigh;
        }
        if (upper == lower) { lower -= .5f; upper += .5f; }
        return new ColorScale(contrast, capture.calibration.apply(lower), capture.calibration.apply(upper));
    }
}
