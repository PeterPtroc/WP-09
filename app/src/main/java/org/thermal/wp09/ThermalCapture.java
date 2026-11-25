package org.thermal.wp09;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/** Immutable sensor frame and the display settings used for this particular exposure. */
final class ThermalCapture {
    static final String[] PALETTE_NAMES = {"铁红", "彩虹", "白热", "黑热"};
    static final int[][] STOPS = {
            {0xff130f21, 0xff4d1c59, 0xffa13b66, 0xffed6350, 0xffffb963, 0xfffff1bd},
            {0xff241057, 0xff294fc2, 0xff1dc8dc, 0xff80df62, 0xfff7dd40, 0xfff15a39},
            {Color.BLACK, Color.WHITE}, {Color.WHITE, Color.BLACK}
    };
    private static final int[][] COLORS = new int[STOPS.length][256];
    static {
        for (int p = 0; p < STOPS.length; p++) {
            int[] stops = STOPS[p];
            for (int i = 0; i < 256; i++) {
                float t = i * (stops.length - 1) / 255f;
                int k = Math.min((int) t, stops.length - 2);
                int a = stops[k], b = stops[k + 1];
                float f = t - k;
                COLORS[p][i] = Color.rgb(mix(Color.red(a), Color.red(b), f),
                        mix(Color.green(a), Color.green(b), f), mix(Color.blue(a), Color.blue(b), f));
            }
        }
    }

    final ThermalFrame frame;
    final Calibration calibration;
    final int palette;
    // A finite pair is an explicit, calibrated Celsius lock. NaN selects automatic display.
    final float low, high;
    final MobileImage movieImage;

    ThermalCapture(ThermalFrame frame, Calibration calibration, int palette, float low, float high) {
        this(frame, calibration, palette, low, high, null);
    }

    ThermalCapture(ThermalFrame frame, Calibration calibration, int palette, float low, float high,
                   MobileImage movieImage) {
        this.frame = frame;
        this.calibration = calibration;
        this.palette = palette;
        this.low = low;
        this.high = high;
        this.movieImage = movieImage;
    }

    ThermalCapture forMovie(MobileImage image) {
        return new ThermalCapture(frame, calibration, palette, low, high, image);
    }

    void render(int[] dest, int[] brightnessLut) {
        int[] colors = COLORS[palette];
        float scale = high > low ? 255f / (high - low) : 0;
        if (frame.readings != null && Float.isFinite(low)) {
            PixelOps.renderTemperatures(frame.readings, colors, dest, calibration.scale, calibration.offset, low, high);
        } else if (frame.readings != null && (movieImage != null || frame.preview == null)) {
            PixelOps.renderMobile(frame.readings, frame.firstColumn, frame.thermalOutput,
                    movieImage == null ? null : movieImage.bias, colors, dest);
        } else {
            int[] lut = colors;
            if (Float.isFinite(low) && frame.hasTemperature()) {
                for (int i = 0; i < brightnessLut.length; i++) {
                    brightnessLut[i] = color(frame.temperatureForLevel(i), colors, scale);
                }
                lut = brightnessLut;
            }
            // Old dual records put raw x=2 at stored x=2, while firmware luma x=0
            // already denotes the active origin. Only their 254-column overlap is visible.
            PixelOps.renderBrightness(frame.preview, lut, dest, frame.firstColumn,
                    frame.hasFirmwareImage() ? frame.firstColumn : 0);
            if (lut != colors) {
                if (frame.cold >= 0) dest[frame.cold] = color(frame.minimum, colors, scale);
                if (frame.hot >= 0) dest[frame.hot] = color(frame.maximum, colors, scale);
                dest[ThermalFrame.CENTER] = color(frame.center, colors, scale);
            }
        }
    }

    private int color(float raw, int[] colors, float scale) {
        if (!Float.isFinite(raw)) return Color.BLACK;
        int shade = scale == 0 ? 127 : Math.round((calibration.apply(raw) - low) * scale);
        return colors[Math.max(0, Math.min(255, shade))];
    }

    Bitmap bitmap() {
        int[] pixels = new int[ThermalFrame.PIXELS];
        render(pixels, new int[256]);
        return Bitmap.createBitmap(pixels, frame.firstColumn, ThermalFrame.WIDTH,
                ThermalFrame.WIDTH - frame.firstColumn, ThermalFrame.HEIGHT, Bitmap.Config.ARGB_8888);
    }

    static GradientDrawable swatch(int palette) {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, STOPS[palette]);
    }

    private static int mix(int a, int b, float f) { return Math.round(a + (b - a) * f); }
}
