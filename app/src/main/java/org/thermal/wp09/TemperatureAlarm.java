package org.thermal.wp09;

final class TemperatureAlarm {
    static final int LOW = 1, HIGH = 2;
    float low = Float.NaN, high = Float.NaN;
    int state;

    int update(ThermalFrame frame, Calibration calibration) {
        int next = 0;
        if (frame != null && frame.hasTemperature() &&
                (!frame.hasPixelTemperatures() || frame.thermalOutput || frame.firmware != null)) {
            float min = calibration.apply(frame.reported.minimum), max = calibration.apply(frame.reported.maximum);
            if (min <= low || ((state & LOW) != 0 && min < low + 0.5f)) next |= LOW;
            if (max >= high || ((state & HIGH) != 0 && max > high - 0.5f)) next |= HIGH;
        }
        int entered = next & ~state;
        state = next;
        return entered;
    }
}
