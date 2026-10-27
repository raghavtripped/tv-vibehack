package io.cleanyourtv.ambilight;

/**
 * Turns a small copy of the screen into one colour for a single-zone strip behind the TV, and smooths it over time.
 * The edges of the picture count more than the middle, since that is what the strip sits next to. Near-black
 * pixels (letterbox bars, black borders) are skipped, so a film in 21:9 still lights the room from its picture;
 * a frame that is almost all black, or a protected app the TV refuses to capture, turns the strip off.
 */
final class AmbiColor {

    /** Pixels darker than this (max of r, g, b) are treated as black bars and ignored. */
    static final int BLACK = 18;
    /** Below this share of lit pixels the frame counts as black. */
    static final double MIN_LIT = 0.02;
    static final double EDGE_WEIGHT = 3;
    /** No boost by default: whites, greys and pastels stay as soft as the picture (raise with the extra). */
    static final double SATURATION = 1.0;
    /** The boost in use: {@link #SATURATION} unless the owner set another with the {@code saturation} extra. */
    static volatile double saturation = SATURATION;

    private double r, g, b;
    private boolean primed;

    private AmbiColor() {
    }

    static AmbiColor smoother() {
        return new AmbiColor();
    }

    /**
     * The colour of one frame as 0xRRGGBB. {@code pixels} are packed RGBA bytes (ImageReader RGBA_8888), with
     * {@code rowStride} bytes per row.
     */
    static int frameColor(byte[] pixels, int width, int height, int rowStride) {
        int bandX = Math.max(1, width / 5), bandY = Math.max(1, height / 5);
        double sr = 0, sg = 0, sb = 0, weights = 0;
        int lit = 0;
        for (int y = 0; y < height; y++) {
            int row = y * rowStride;
            boolean edgeRow = y < bandY || y >= height - bandY;
            for (int x = 0; x < width; x++) {
                int i = row + x * 4;
                int pr = pixels[i] & 0xFF, pg = pixels[i + 1] & 0xFF, pb = pixels[i + 2] & 0xFF;
                if (Math.max(pr, Math.max(pg, pb)) < BLACK) continue;
                lit++;
                double w = edgeRow || x < bandX || x >= width - bandX ? EDGE_WEIGHT : 1;
                sr += pr * w;
                sg += pg * w;
                sb += pb * w;
                weights += w;
            }
        }
        if (lit < MIN_LIT * width * height || weights == 0) return 0;
        return grade(sr / weights, sg / weights, sb / weights);
    }

    /**
     * Below this a whole-screen average counts as black. Lower than {@link #BLACK}: the average takes in letterbox
     * bars and dark surroundings, so a dim film scene can average well under what a single lit pixel would be.
     */
    static final int BLACK_AVERAGE = 8;

    /** One already-averaged colour (the TV's own colour taking), graded like a frame; near-black gives 0. */
    static int averageColor(int rgb, int black) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        if (Math.max(r, Math.max(g, b)) < black) return 0;
        return grade(r, g, b);
    }

    /** A cheap fingerprint of a frame: two frames with the same one show the same picture. */
    static long signature(byte[] pixels, int width, int height, int rowStride) {
        long h = 1125899906842597L;
        for (int y = 0; y < height; y++) {
            int row = y * rowStride;
            for (int i = row; i < row + width * 4; i++) {
                if ((i & 3) != 3) h = 31 * h + pixels[i];
            }
        }
        return h;
    }

    /** LEDs look washed out next to a screen: push the saturation up and deepen dark tones a little. */
    static int grade(double r, double g, double b) {
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max <= 0) return 0;
        double sat = (max - min) / max;
        double boosted = Math.min(1, sat * saturation);
        double scale = sat == 0 ? 0 : boosted / sat;
        // Scene-adaptive curve: a dark scene (a dim frame) is pushed down further, so the strip doesn't light the
        // room more than the picture does; bright frames keep a gentle 1.3.
        double exponent = max >= 96 ? 1.3 : 1.3 + 0.5 * (1 - max / 96);
        double v = Math.pow(max / 255, exponent) * 255;
        int[] out = new int[3];
        double[] in = {r, g, b};
        for (int c = 0; c < 3; c++) {
            // Keep the hue: move each channel away from the maximum by the boosted saturation.
            double channel = sat == 0 ? max : max - (max - in[c]) * scale;
            out[c] = clamp((int) Math.round(channel / max * v));
        }
        return out[0] << 16 | out[1] << 8 | out[2];
    }

    /** One step of smoothing towards {@code target} (0xRRGGBB); {@code alpha} is how far to move, 0..1. */
    int smooth(int target, double alpha) {
        double tr = target >> 16 & 0xFF, tg = target >> 8 & 0xFF, tb = target & 0xFF;
        if (!primed) {
            r = tr;
            g = tg;
            b = tb;
            primed = true;
        } else {
            r += (tr - r) * alpha;
            g += (tg - g) * alpha;
            b += (tb - b) * alpha;
        }
        return clamp((int) Math.round(r)) << 16 | clamp((int) Math.round(g)) << 8 | clamp((int) Math.round(b));
    }

    /**
     * The same hue and saturation at full brightness: the brightest channel goes to 255. Behind a TV the strip has
     * to compete with the screen, so a colour is shown as bright as the strip can make it; black stays black.
     */
    static int maxOut(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF, max = Math.max(r, Math.max(g, b));
        if (max == 0) return 0;
        return clamp(Math.round(r * 255f / max)) << 16 | clamp(Math.round(g * 255f / max)) << 8 | clamp(Math.round(b * 255f / max));
    }

    /** Below this brightest channel a picture counts as a dark scene and the strip dims with it. */
    static final int DARK_KNEE = 112;

    /**
     * Full brightness for a normal or bright picture; a dark scene keeps its colour but stays dim (a quarter of the
     * strip's brightness near black, rising to full at {@link #DARK_KNEE}), so a navy night sky doesn't
     * light the room bright blue.
     */
    static int lift(int rgb) {
        int max = Math.max(rgb >> 16 & 0xFF, Math.max(rgb >> 8 & 0xFF, rgb & 0xFF));
        if (max == 0) return 0;
        int full = maxOut(rgb);
        if (max >= DARK_KNEE) return full;
        double value = 0.08 + 0.92 * max / (double) DARK_KNEE;
        return Director.scale(full, value);
    }

    /** Mixes {@code amount} (0..1) of white into a colour. */
    static int towardWhite(int rgb, double amount) {
        double a = Math.max(0, Math.min(1, amount));
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        return (int) Math.round(r + (255 - r) * a) << 16 | (int) Math.round(g + (255 - g) * a) << 8 | (int) Math.round(b + (255 - b) * a);
    }

    /** Scales a colour by a brightness percentage, 0..100. */
    static int dim(int rgb, int percent) {
        int p = Math.max(0, Math.min(100, percent));
        return (rgb >> 16 & 0xFF) * p / 100 << 16 | (rgb >> 8 & 0xFF) * p / 100 << 8 | (rgb & 0xFF) * p / 100;
    }

    /** True when two colours differ enough for the strip to show it. */
    static boolean differs(int a, int b, int threshold) {
        return Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)) > threshold
                || Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF)) > threshold
                || Math.abs((a & 0xFF) - (b & 0xFF)) > threshold;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
