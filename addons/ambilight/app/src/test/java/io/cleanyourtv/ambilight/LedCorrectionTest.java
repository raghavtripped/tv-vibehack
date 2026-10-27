package io.cleanyourtv.ambilight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LedCorrectionTest {

    @Test
    public void fullColoursStayFullAndBlackStaysOff() {
        LedCorrection c = LedCorrection.parse(null);
        assertEquals(0xFF0000, c.apply(0xFF0000));
        assertEquals(0, c.apply(0));
        assertEquals(255, c.apply(0xFFFFFF) >> 8 & 0xFF);
    }

    @Test
    public void orangeLosesGreenSoItDoesntLookYellow() {
        int o = LedCorrection.parse(null).apply(0xFF8000);
        assertEquals(255, o >> 16 & 0xFF);
        assertTrue("half green is sent much lower", (o >> 8 & 0xFF) < 80);
    }

    @Test
    public void whitesGreysAndPastelsStayNeutralOrPastel() {
        LedCorrection c = LedCorrection.parse(null);
        for (int grey : new int[]{0xFFFFFF, 0xC0C0C0, 0x808080}) {
            float[] hsv = Director.hsv(c.apply(grey));
            assertTrue(String.format("#%06x stays neutral", grey), hsv[1] < 0.1);
        }
        // Pastel blue must stay blue, not turn green; pastel pink must stay pink, not turn orange.
        assertEquals(Director.hsv(0xAEC6CF)[0], Director.hsv(c.apply(0xAEC6CF))[0], 15);
        float pink = Director.hsv(c.apply(0xFFD1DC))[0];
        assertTrue("pink hue " + pink, pink > 320 || pink < 10);
    }

    @Test
    public void aDimColourIsScaledNotGammaDimmedAgain() {
        int c = LedCorrection.parse(null).apply(0x331A00); // a dark amber, 20% bright
        assertEquals(0x33, c >> 16 & 0xFF);
        assertTrue("same mix as full-brightness amber", Math.abs((c >> 8 & 0xFF) - 0x33 * 56 / 255) <= 2);
    }

    @Test
    public void aDimChannelIsNeverRoundedToOff() {
        assertEquals(1, LedCorrection.parse(null).apply(0x000001) & 0xFF);
    }

    @Test
    public void overridesParseAndBadOnesFallBack() {
        assertEquals(0x808080, LedCorrection.parse("1,1,1,1").apply(0x808080));
        assertEquals(LedCorrection.parse(null).apply(0xFF8000), LedCorrection.parse("nonsense").apply(0xFF8000));
        assertEquals(LedCorrection.parse(null).apply(0xFF8000), LedCorrection.parse("").apply(0xFF8000));
    }
}
