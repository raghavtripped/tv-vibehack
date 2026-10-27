package io.cleanyourtv.ambilight;

/**
 * Makes the strip show the colour the screen shows. Cheap RGB LEDs don't respond like a panel: their brightness
 * isn't linear in the value sent, and their green and blue are much stronger than their red, so a straight copy
 * of orange looks yellow and cyan looks blue. Each channel gets a gain and the value a gamma:
 * {@code out = 255 * gain * (in / 255) ^ gamma}. The defaults were measured by photographing an LEDDMX strip next
 * to the screen showing the same colours; {@code --es led r,g,b,gamma} overrides them.
 */
final class LedCorrection {

    /**
     * Gamma 2.2 is the screen-to-light conversion (pixel values are gamma-encoded, an LED's light is linear in what
     * it's sent): without it the strip's mid values are far too strong, so orange (FF8000) came out yellow. The
     * channels stay balanced so white, greys and pastels stay neutral; blue is trimmed only slightly, because this
     * strip's blue is a little strong in mixed colours. (A first calibration cut blue to 0.6 from camera photos;
     * the camera's auto white balance hid that this turned whites yellow and pastel blues green.)
     */
    static final float[] DEFAULT = {1.0f, 1.0f, 0.92f, 2.2f};

    private final float gainR, gainG, gainB, gamma;

    LedCorrection(float[] p) {
        gainR = p[0];
        gainG = p[1];
        gainB = p[2];
        gamma = p[3];
    }

    static LedCorrection parse(String spec) {
        if (spec == null) return new LedCorrection(DEFAULT);
        String[] parts = spec.split(",");
        if (parts.length != 4) return new LedCorrection(DEFAULT);
        float[] p = new float[4];
        try {
            for (int i = 0; i < 4; i++) p[i] = Float.parseFloat(parts[i].trim());
        } catch (NumberFormatException e) {
            return new LedCorrection(DEFAULT);
        }
        for (int i = 0; i < 3; i++) p[i] = Math.max(0, Math.min(1.5f, p[i]));
        p[3] = Math.max(0.5f, Math.min(3f, p[3]));
        return new LedCorrection(p);
    }

    /**
     * The gamma corrects the colour's mix, not its brightness: the colour is corrected at full brightness and then
     * scaled back linearly. Otherwise a dark scene, already dimmed on purpose, would be dimmed a second time by the
     * gamma (20% becoming 3%) and the strip would all but go out.
     */
    int apply(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF, max = Math.max(r, Math.max(g, b));
        if (max == 0) return 0;
        double level = max / 255.0, up = 255.0 / max;
        return channel(r * up, gainR, level) << 16 | channel(g * up, gainG, level) << 8 | channel(b * up, gainB, level);
    }

    private int channel(double v, float gain, double level) {
        if (v <= 0) return 0;
        double out = 255 * gain * Math.pow(Math.min(255, v) / 255.0, gamma) * level;
        // Never round a lit channel down to off: a dim channel still tints the colour.
        return (int) Math.max(1, Math.min(255, Math.round(out)));
    }
}
