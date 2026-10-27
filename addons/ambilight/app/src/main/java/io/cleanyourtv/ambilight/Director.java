package io.cleanyourtv.ambilight;

/**
 * Picks what the strip shows on each tick. The picture comes first and the music rides on top of it:
 * <ul>
 *   <li>while there is a picture, the strip shows its colour at full brightness, and each bass beat in what the
 *       TV is playing adds a short flash towards white;</li>
 *   <li>through a short black (a scene cut, a fade) it keeps the last colour, so the strip doesn't flicker;</li>
 *   <li>when the picture stays black or frozen but sound is playing, it follows the music: the hue starts from
 *       the last picture, drifts faster the louder it gets, jumps on each beat and leans warm or cool with how
 *       bass-heavy the sound is; brightness stays high and beats flash;</li>
 *   <li>black, and the TV plays sound the add-on cannot hear (Dolby sent straight to the hardware decoder): the
 *       last colour breathes slowly, so the strip neither freezes nor goes dark;</li>
 *   <li>black and silent: it holds the last colour, then fades down to a dim glow of it.</li>
 * </ul>
 * The caller passes a frozen picture as black: on TVs that play video on a hardware plane, screen capture sees
 * only the app's controls, which stop changing while the film plays.
 */
final class Director {

    static final long HOLD_MS = 1200, FADE_MS = 4000, SOUND_WINDOW_MS = 1500, BREATH_MS = 6000;
    static final double GLOW = 0.12;
    static final double SCREEN_ALPHA = 0.45, MUSIC_ALPHA = 0.6;
    /** How much white a beat mixes into the picture's colour, and into the music colour. */
    static final double SCREEN_ACCENT = 0.35, MUSIC_ACCENT = 0.45;

    enum Mode { SCREEN, HOLD, MUSIC, BREATHE, FADE }

    private int lastLit = 0xFF8040; // warm amber until the first picture
    private long blackSince = -1;
    private Mode mode = Mode.SCREEN;
    private double flash, drift;

    /**
     * @param screen the colour of the latest frame, 0 when it is black, frozen or capture is blocked
     * @param pulse what the add-on hears of the TV's sound, or null when it can't listen
     * @param soundOn the TV says audio is playing, whether or not {@code pulse} can hear it
     * @return the colour to move towards; {@link #alpha()} says how fast
     */
    int next(long now, int screen, Pulse pulse, boolean soundOn) {
        return next(now, screen, pulse, soundOn, false);
    }

    /**
     * @param real the picture comes from the TV's own output (colour taking, a webcam), not screen capture: then a
     *             black picture really is dark, so the strip fades instead of breathing an old colour (music still
     *             plays on top when it can be heard)
     */
    int next(long now, int screen, Pulse pulse, boolean soundOn, boolean real) {
        boolean hearing = pulse != null && pulse.playing(now, SOUND_WINDOW_MS);
        flash = hearing && pulse.beat() ? 1 : flash * 0.72;
        if (screen != 0) {
            lastLit = AmbiColor.lift(screen);
            blackSince = -1;
            drift = 0;
            mode = Mode.SCREEN;
            return AmbiColor.towardWhite(lastLit, SCREEN_ACCENT * flash);
        }
        if (blackSince < 0) blackSince = now;
        long black = now - blackSince;
        if (black < HOLD_MS) {
            mode = Mode.HOLD;
            return lastLit;
        }
        if (hearing) {
            mode = Mode.MUSIC;
            return music(pulse);
        }
        if (soundOn && !real) {
            mode = Mode.BREATHE;
            double phase = (now % BREATH_MS) / (double) BREATH_MS;
            return scale(lastLit, 0.65 + 0.35 * Math.cos(phase * 2 * Math.PI));
        }
        mode = Mode.FADE;
        double t = Math.min(1, (double) (black - HOLD_MS) / FADE_MS);
        // A real black picture goes dark; with screen capture black may just mean "can't see", so keep a glow.
        return scale(lastLit, 1 - t * (1 - (real ? 0 : GLOW)));
    }

    private int music(Pulse pulse) {
        // Drift a few degrees per tick, faster when loud; jump on a beat.
        drift = (drift + 1.5 + 6 * pulse.level() + (flash == 1 ? 45 : 0)) % 360;
        float[] hsv = hsv(lastLit);
        // Bass-heavy sound pulls the hue one way, bright sound the other, up to 60 degrees.
        double hue = ((hsv[0] + drift + (pulse.tone() - 0.5) * 120) % 360 + 360) % 360;
        double value = Math.min(1, 0.7 + 0.3 * pulse.level());
        return AmbiColor.towardWhite(fromHsv(hue, 1, value), MUSIC_ACCENT * flash);
    }

    Mode mode() {
        return mode;
    }

    double alpha() {
        return mode == Mode.MUSIC ? MUSIC_ALPHA : SCREEN_ALPHA;
    }

    static int scale(int rgb, double f) {
        return (int) Math.round((rgb >> 16 & 0xFF) * f) << 16 | (int) Math.round((rgb >> 8 & 0xFF) * f) << 8
                | (int) Math.round((rgb & 0xFF) * f);
    }

    /** Hue in degrees, saturation and value 0..1. */
    static float[] hsv(int rgb) {
        double r = (rgb >> 16 & 0xFF) / 255.0, g = (rgb >> 8 & 0xFF) / 255.0, b = (rgb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        double h;
        if (d == 0) h = 0;
        else if (max == r) h = 60 * (((g - b) / d) % 6);
        else if (max == g) h = 60 * ((b - r) / d + 2);
        else h = 60 * ((r - g) / d + 4);
        if (h < 0) h += 360;
        return new float[]{(float) h, (float) (max == 0 ? 0 : d / max), (float) max};
    }

    static int fromHsv(double h, double s, double v) {
        double c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
        double r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return (int) Math.round((r + m) * 255) << 16 | (int) Math.round((g + m) * 255) << 8 | (int) Math.round((b + m) * 255);
    }
}
