package io.cleanyourtv.ambilight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DirectorTest {

    private static final int RATE = 44_100, N = 1024;

    /** An FFT frame with {@code magnitude} in every bin from {@code fromHz} to {@code toHz}. */
    private static byte[] fft(double fromHz, double toHz, int magnitude) {
        byte[] f = new byte[N];
        double bin = (double) RATE / N;
        for (int k = 1; k < N / 2; k++) {
            double hz = k * bin;
            if (hz >= fromHz && hz <= toHz) f[2 * k] = (byte) magnitude;
        }
        return f;
    }

    private static int value(int rgb) {
        return Math.max(rgb >> 16 & 0xFF, Math.max(rgb >> 8 & 0xFF, rgb & 0xFF));
    }

    @Test
    public void followsTheScreen() {
        Director d = new Director();
        assertEquals(AmbiColor.lift(0x123456), d.next(0, 0x123456, new Pulse(), false));
        assertEquals(Director.Mode.SCREEN, d.mode());
    }

    @Test
    public void holdsThroughAShortBlack() {
        Director d = new Director();
        d.next(0, 0x2080FF, null, false);
        assertEquals(0x2080FF, d.next(100, 0, null, false));
        assertEquals(0x2080FF, d.next(Director.HOLD_MS - 1, 0, null, false));
        assertEquals(Director.Mode.HOLD, d.mode());
    }

    @Test
    public void silentBlackFadesToAGlow() {
        Director d = new Director();
        d.next(0, 0xFF0000, null, false);
        d.next(10, 0, null, false);
        int mid = d.next(10 + Director.HOLD_MS + Director.FADE_MS / 2, 0, null, false);
        assertEquals(Director.Mode.FADE, d.mode());
        assertTrue(value(mid) < 255 && value(mid) > 60);
        int end = d.next(10 + Director.HOLD_MS + Director.FADE_MS * 2, 0, null, false);
        assertEquals((int) Math.round(255 * Director.GLOW), end >> 16 & 0xFF);
    }

    @Test
    public void blackWithSoundFollowsTheMusic() {
        Director d = new Director();
        Pulse p = new Pulse();
        d.next(0, 0x00FF00, p, false);
        d.next(10, 0, p, false);
        long t = 10 + Director.HOLD_MS + 100;
        p.update(fft(40, 4000, 60), RATE, t);
        d.next(t, 0, p, false);
        assertEquals(Director.Mode.MUSIC, d.mode());
        assertEquals(Director.MUSIC_ALPHA, d.alpha(), 0);
    }

    @Test
    public void musicStopsWhenTheSoundDoes() {
        Director d = new Director();
        Pulse p = new Pulse();
        d.next(0, 0x00FF00, p, false);
        d.next(10, 0, p, false);
        p.update(fft(40, 4000, 60), RATE, 20);
        d.next(10 + Director.HOLD_MS + Director.SOUND_WINDOW_MS + 500, 0, p, false);
        assertEquals(Director.Mode.FADE, d.mode());
    }

    @Test
    public void pictureWinsOverMusic() {
        Director d = new Director();
        Pulse p = new Pulse();
        p.update(fft(40, 4000, 60), RATE, 0);
        d.next(0, 0x101080, p, false);
        assertEquals(Director.Mode.SCREEN, d.mode());
    }

    @Test
    public void pulseHearsSilenceLoudnessBeatsAndTone() {
        Pulse p = new Pulse();
        p.update(new byte[N], RATE, 0);
        assertEquals(0, p.level(), 0);
        assertFalse(p.playing(0, 1000));

        for (int i = 0; i < 20; i++) p.update(fft(1000, 4000, 10), RATE, 100 + i * 50L);
        assertTrue(p.playing(1100, 1000));
        assertTrue("bright sound sits high", p.tone() > 0.6);
        assertTrue(p.level() > 0.5);

        p.update(fft(40, 160, 120), RATE, 2000);
        assertTrue("a sudden bass hit is a beat", p.beat());
        assertTrue("bass-heavy sound sits low", p.tone() < 0.3);
        p.update(fft(40, 160, 120), RATE, 2050);
        assertFalse("no second beat within the gap", p.beat());
    }

    @Test
    public void hsvRoundTrips() {
        for (int rgb : new int[]{0xFF0000, 0x00FF00, 0x0000FF, 0x804020, 0x10A0C0, 0xFFFFFF}) {
            float[] h = Director.hsv(rgb);
            int back = Director.fromHsv(h[0], h[1], h[2]);
            assertFalse(String.format("#%06x -> #%06x", rgb, back), AmbiColor.differs(rgb, back, 1));
        }
    }

    @Test
    public void unheardSoundBreathesTheLastColour() {
        Director d = new Director();
        d.next(0, 0x0000FF, null, true);
        d.next(10, 0, null, true);
        long t = Director.BREATH_MS * 2; // the top of a breath
        int a = d.next(t, 0, null, true);
        assertEquals(Director.Mode.BREATHE, d.mode());
        int b = d.next(t + Director.BREATH_MS / 2, 0, null, true);
        assertTrue("it rises and falls", Math.abs((a & 0xFF) - (b & 0xFF)) > 100);
        assertTrue("never goes dark", (b & 0xFF) > 60 && (a & 0xFF) > 60);
    }

    @Test
    public void frameSignatureSeesChanges() {
        byte[] f = new byte[16 * 9 * 4];
        long blank = AmbiColor.signature(f, 16, 9, 64);
        f[3] = 9; // alpha only: same picture
        assertEquals(blank, AmbiColor.signature(f, 16, 9, 64));
        f[100] = 1;
        assertFalse(blank == AmbiColor.signature(f, 16, 9, 64));
    }

    @Test
    public void pictureIsShownAtFullBrightness() {
        Director d = new Director();
        int c = d.next(0, 0x804020, null, false);
        assertEquals(255, c >> 16 & 0xFF);
        assertEquals("same hue", Director.hsv(0x804020)[0], Director.hsv(c)[0], 2);
        assertEquals("same saturation", Director.hsv(0x804020)[1], Director.hsv(c)[1], 0.02);
    }

    @Test
    public void aBeatFlashesThePictureTowardWhite() {
        Director d = new Director();
        Pulse p = new Pulse();
        for (int i = 0; i < 10; i++) p.update(fft(1000, 4000, 10), RATE, i * 50L);
        int calm = d.next(500, 0xFF0000, p, true);
        p.update(fft(40, 160, 120), RATE, 600);
        int hit = d.next(600, 0xFF0000, p, true);
        assertEquals(0xFF0000, calm);
        assertTrue("green rises on the flash", (hit >> 8 & 0xFF) > 40);
        int after = d.next(650, 0xFF0000, p, true);
        assertTrue("and decays", (after >> 8 & 0xFF) < (hit >> 8 & 0xFF));
    }

    @Test
    public void musicModeStaysBright() {
        Director d = new Director();
        Pulse p = new Pulse();
        d.next(0, 0x00FF00, p, true);
        d.next(10, 0, p, true);
        for (int i = 0; i < 30; i++) {
            long t = Director.HOLD_MS + 100 + i * 50L;
            p.update(fft(40, 4000, 20 + i), RATE, t);
            int c = d.next(t, 0, p, true);
            assertEquals(Director.Mode.MUSIC, d.mode());
            assertTrue(value(c) >= 175);
        }
    }

    @Test
    public void aDarkSceneStaysDimInItsColour() {
        int navy = new Director().next(0, 0x080818, null, false);
        assertTrue("blue", (navy & 0xFF) > (navy >> 16 & 0xFF) * 2);
        assertTrue("but dim", value(navy) < 160 && value(navy) > 60);
        assertEquals(255, value(new Director().next(0, 0x506070, null, false)));
    }

    @Test
    public void aRealBlackPictureFadesInsteadOfBreathing() {
        Director d = new Director();
        d.next(0, 0xFFFF80, null, true, true);
        d.next(10, 0, null, true, true);
        d.next(10 + Director.HOLD_MS + Director.FADE_MS * 2, 0, null, true, true);
        assertEquals(Director.Mode.FADE, d.mode());
        Director capture = new Director();
        capture.next(0, 0xFFFF80, null, true, false);
        capture.next(10, 0, null, true, false);
        capture.next(10 + Director.HOLD_MS + 100, 0, null, true, false);
        assertEquals("screen capture still breathes", Director.Mode.BREATHE, capture.mode());
    }

    @Test
    public void aRealBlackPictureEndsDark() {
        Director d = new Director();
        d.next(0, 0xFFFFFF, null, false, true);
        d.next(10, 0, null, false, true);
        assertEquals(0, d.next(10 + Director.HOLD_MS + Director.FADE_MS * 2, 0, null, false, true));
    }
}
