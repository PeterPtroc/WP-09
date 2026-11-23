package org.thermal.wp09;

final class Calibration {
    static final Calibration DEFAULT = new Calibration(1, 0, false);
    final float scale;
    final float offset;
    final boolean applied;

    Calibration(float scale, float offset, boolean applied) {
        this.scale = scale;
        this.offset = offset;
        this.applied = applied;
    }

    float apply(float reading) {
        return scale * reading + offset;
    }

    static Calibration onePoint(float reading, float reference) {
        checkReference(reading);
        checkReference(reference);
        return new Calibration(1f, reference - reading, true);
    }

    static Calibration twoPoint(float lowReading, float lowReference, float highReading,
                                float highReference) {
        checkReference(lowReference);
        checkReference(highReference);
        checkReference(lowReading);
        checkReference(highReading);
        float readingSpan = highReading - lowReading;
        float referenceSpan = highReference - lowReference;
        if (readingSpan < 5 || referenceSpan < 5) {
            throw new IllegalArgumentException("两组参考温度和设备读数分别至少相差 5 °C");
        }
        float scale = referenceSpan / readingSpan;
        return new Calibration(scale, lowReference - lowReading * scale, true);
    }

    private static void checkReference(float reference) {
        if (!Float.isFinite(reference) || reference < -100 || reference > 1000) {
            throw new IllegalArgumentException("请输入有效的参考摄氏温度");
        }
    }
}
