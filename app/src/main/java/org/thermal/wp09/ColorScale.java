package org.thermal.wp09;

/** Display contrast and an optional window in calibrated Celsius. */
final class ColorScale {
    static final int MIN_CONTRAST = 50, NORMAL = 100, MAX_CONTRAST = 150;
    static final ColorScale AUTO = new ColorScale(NORMAL, Float.NaN, Float.NaN);
    final int contrast;
    final float low, high;
    // Positions of the locked middle temperatures within the calibrated outer window.
    final float middleLow, middleHigh;
    final float originalLow, originalHigh;

    ColorScale(int contrast, float low, float high) {
        this(contrast, low, high, .125f, .875f);
    }

    ColorScale(int contrast, float low, float high, float middleLow, float middleHigh) {
        this(contrast, low, high, middleLow, middleHigh, Float.NaN, Float.NaN);
    }

    ColorScale(int contrast, float low, float high, float middleLow, float middleHigh,
               float originalLow, float originalHigh) {
        this.contrast = Math.max(MIN_CONTRAST, Math.min(MAX_CONTRAST, contrast));
        boolean valid = Float.isFinite(low) && Float.isFinite(high) && low <= high;
        this.low = valid ? low : Float.NaN;
        this.high = valid ? high : Float.NaN;
        boolean knees = validMiddle(middleLow, middleHigh);
        this.middleLow = knees ? middleLow : .125f;
        this.middleHigh = knees ? middleHigh : .875f;
        this.originalLow = originalLow;
        this.originalHigh = originalHigh;
    }

    static boolean validMiddle(float a, float b) { return a >= 0 && a < b && b <= 1; }

    int ironPercent(int palette) { return palette == 0 && contrast > NORMAL ? contrast : 0; }

    boolean locked() { return Float.isFinite(low); }

    // A Celsius -> sensor round trip can shift the neutral iron bin by one level.
    // Reuse the exact acquisition endpoints while the calibration still matches.
    float sensorLow(Calibration cal) {
        return cal.apply(originalLow) == low ? originalLow : (low - cal.offset) / cal.scale;
    }

    float sensorHigh(Calibration cal) {
        return cal.apply(originalHigh) == high ? originalHigh : (high - cal.offset) / cal.scale;
    }

    ColorScale withContrast(int value) {
        return new ColorScale(value, low, high, middleLow, middleHigh, originalLow, originalHigh);
    }

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
        return new ColorScale(contrast, capture.calibration.apply(lower), capture.calibration.apply(upper),
                capture.middleLow, capture.middleHigh, lower, upper);
    }
}
