package io.cleanyourtv.ambilight;

/**
 * Reads the TV's sound from FFT frames (android.media.audiofx.Visualizer on the output mix) and keeps three
 * numbers for the strip: how loud it is relative to recent peaks (0..1), whether a bass beat just hit, and
 * where the sound sits between bass-heavy (0) and bright (1).
 *
 * <p>FFT frames are Visualizer's packed bytes: [Re(0), Re(n/2), Re(1), Im(1), Re(2), Im(2), ...].
 */
final class Pulse {

    /** Below this summed magnitude the TV counts as silent. */
    static final double SILENCE = 40;
    private static final double PEAK_DECAY = 0.992, BASS_AVERAGE = 0.9, BEAT_RATIO = 1.45;
    private static final long BEAT_GAP_MS = 160;

    private double peak = SILENCE * 4, bassAverage;
    private double level, tone = 0.5;
    private boolean beat;
    private long lastBeat = Long.MIN_VALUE / 2, lastSound = Long.MIN_VALUE / 2;

    /** One FFT frame of {@code fft.length} bytes, captured at {@code samplingRateHz}, at time {@code now} (ms). */
    void update(byte[] fft, int samplingRateHz, long now) {
        int n = fft.length;
        double binHz = (double) samplingRateHz / n;
        double total = 0, bass = 0, weighted = 0;
        for (int k = 1; k < n / 2; k++) {
            double hz = k * binHz;
            if (hz < 30 || hz > 10_000) continue;
            double mag = Math.hypot(fft[2 * k], fft[2 * k + 1]);
            total += mag;
            if (hz <= 180) bass += mag;
            weighted += mag * Math.log(Math.max(hz, 100));
        }
        if (total < SILENCE) {
            level = 0;
            bassAverage *= BASS_AVERAGE;
            return;
        }
        lastSound = now;
        peak = Math.max(total, peak * PEAK_DECAY);
        level = Math.min(1, total / peak);
        // Spectral centroid on a log scale: about 0 for a bass line, 1 for cymbals and voices.
        double centroid = weighted / total;
        tone = clamp((centroid - Math.log(150)) / (Math.log(3000) - Math.log(150)));
        if (bass > bassAverage * BEAT_RATIO && bass > SILENCE / 4 && now - lastBeat > BEAT_GAP_MS) {
            beat = true;
            lastBeat = now;
        }
        bassAverage = bassAverage * BASS_AVERAGE + bass * (1 - BASS_AVERAGE);
    }

    double level() {
        return level;
    }

    double tone() {
        return tone;
    }

    /** True once for each beat: reading it clears it, so the strip's clock and the sound's clock can differ. */
    boolean beat() {
        boolean b = beat;
        beat = false;
        return b;
    }

    /** Something has been audible within {@code windowMs} of {@code now}. */
    boolean playing(long now, long windowMs) {
        return now - lastSound <= windowMs;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
