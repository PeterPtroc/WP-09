package org.thermal.wp09;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import android.graphics.drawable.GradientDrawable;

/** Immutable sensor frame and the display settings used for this particular exposure. */
final class ThermalCapture {
    static final String[] PALETTE_NAMES = {"铁红", "彩虹", "白热", "黑热",
            "原厂白热", "原厂黑热", "蓝紫", "铁弓", "全彩", "冷暖", "黄紫", "红热", "青金", "绿红", "多彩"};
    private static final int[][] CLASSIC = {
            {0xff130f21, 0xff4d1c59, 0xffa13b66, 0xffed6350, 0xffffb963, 0xfffff1bd},
            {0xff241057, 0xff294fc2, 0xff1dc8dc, 0xff80df62, 0xfff7dd40, 0xfff15a39},
            {Color.BLACK, Color.WHITE}, {Color.WHITE, Color.BLACK}
    };
    static final int[][] STOPS = new int[PALETTE_NAMES.length][];
    private static final int[][] COLORS = new int[PALETTE_NAMES.length][256];
    private static final int[][][] CONTRAST_COLORS =
            new int[PALETTE_NAMES.length][ColorScale.MAX_CONTRAST - ColorScale.MIN_CONTRAST + 1][];
    static {
        for (int p = 0; p < CLASSIC.length; p++) {
            int[] stops = STOPS[p] = CLASSIC[p];
            for (int i = 0; i < 256; i++) {
                float t = i * (stops.length - 1) / 255f;
                int k = Math.min((int) t, stops.length - 2);
                int a = stops[k], b = stops[k + 1];
                float f = t - k;
                COLORS[p][i] = Color.rgb(mix(Color.red(a), Color.red(b), f),
                        mix(Color.green(a), Color.green(b), f), mix(Color.blue(a), Color.blue(b), f));
            }
        }
        InputStream stream = ThermalCapture.class.getResourceAsStream("/wp09_outdoor.rgb");
        if (stream == null) throw new IllegalStateException("Missing WP-09 color tables");
        try (DataInputStream in = new DataInputStream(stream)) {
            for (int p = CLASSIC.length; p < COLORS.length; p++) {
                for (int i = 0; i < 256; i++)
                    COLORS[p][i] = Color.rgb(in.readUnsignedByte(), in.readUnsignedByte(), in.readUnsignedByte());
                STOPS[p] = COLORS[p];
            }
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    final ThermalFrame frame;
    final Calibration calibration;
    final int palette;
    final ColorScale scale;
    final MobileImage movieImage;
    final int lockedTone;
    final byte[] brightness;

    ThermalCapture(ThermalFrame frame, Calibration calibration, int palette, float low, float high) {
        this(frame, calibration, palette, new ColorScale(ColorScale.NORMAL, low, high), null);
    }

    ThermalCapture(ThermalFrame frame, Calibration calibration, int palette, ColorScale scale, MobileImage movieImage) {
        this.frame = frame;
        this.calibration = calibration;
        this.palette = palette;
        this.scale = scale;
        this.movieImage = movieImage;
        ThermalFrame.Display display = frame.display;
        lockedTone = scale.locked() && display != null ? display.tone : 0;
        byte[] image = frame.preview;
        if (display != null && display.filtered != null) {
            float low = scale.locked() ? (scale.low - calibration.offset) / calibration.scale : display.autoLow;
            float high = scale.locked() ? (scale.high - calibration.offset) / calibration.scale : display.autoHigh;
            // A drawn auto exposure can be locked exactly, including its calibrated window.
            boolean same = low == display.imageLow && high == display.imageHigh ||
                    (scale.locked() && scale.low == calibration.apply(display.imageLow)
                            && scale.high == calibration.apply(display.imageHigh));
            if (!same) image = display.filtered.window(low, high);
        }
        brightness = image;
    }

    ThermalCapture forMovie(MobileImage image) {
        return new ThermalCapture(frame, calibration, palette, scale, image);
    }

    void render(int[] dest, int[] brightnessLut) {
        if (dest.length != rasterPixels()) throw new IllegalArgumentException("Display plane size");
        int[] colors = colors();
        float low = this.scale.low, high = this.scale.high;
        float scale = high > low ? 255f / (high - low) : 0;
        if (lockedTone != 0 || brightness != frame.preview) {
            PixelOps.renderBrightness(brightness, colors, dest, 0, 0);
        } else if (frame.readings != null && Float.isFinite(low)) {
            PixelOps.renderTemperatures(frame.readings, colors, dest, calibration.scale, calibration.offset, low, high);
        } else if (frame.readings != null && (frame.preview == null || movieImage != null && frame.displayScale() == 1)) {
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
        int shade = scale == 0 ? 127 : Math.round((calibration.apply(raw) - this.scale.low) * scale);
        return colors[Math.max(0, Math.min(255, shade))];
    }

    int[] colors() {
        if (scale.contrast == ColorScale.NORMAL) return COLORS[palette];
        int index = scale.contrast - ColorScale.MIN_CONTRAST;
        synchronized (CONTRAST_COLORS) {
            int[] colors = CONTRAST_COLORS[palette][index];
            if (colors == null) {
                colors = new int[256];
                float gain = scale.contrast / 100f;
                for (int i = 0; i < colors.length; i++) {
                    float t = 2f * i / 255f - 1f;
                    // Monotone S-curve with fixed endpoints and midpoint slope equal to gain.
                    float mapped = gain * t / (1f + (gain - 1f) * Math.abs(t));
                    int level = Math.round((1f + mapped) * 127.5f);
                    colors[i] = COLORS[palette][Math.max(0, Math.min(255, level))];
                }
                CONTRAST_COLORS[palette][index] = colors;
            }
            return colors;
        }
    }

    int[] legendColors() {
        int[] colors = colors();
        if (lockedTone <= 1) return colors;
        int[] legend = new int[256];
        float lift = (lockedTone - 1) / 100f;
        for (int i = 0; i < legend.length; ++i) {
            float t = i / 255f;
            legend[i] = colors[Math.round(255f * (t + lift * t * (1f - t)))];
        }
        return legend;
    }

    Bitmap bitmap() {
        int scale = frame.displayScale();
        int[] pixels = new int[rasterPixels()];
        render(pixels, new int[256]);
        return Bitmap.createBitmap(pixels, frame.firstColumn * scale, ThermalFrame.WIDTH * scale,
                (ThermalFrame.WIDTH - frame.firstColumn) * scale, ThermalFrame.HEIGHT * scale, Bitmap.Config.ARGB_8888);
    }

    int rasterPixels() { int scale = frame.displayScale(); return ThermalFrame.PIXELS * scale * scale; }

    static GradientDrawable swatch(int palette) {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, STOPS[palette]);
    }

    private static int mix(int a, int b, float f) { return Math.round(a + (b - a) * f); }
}
