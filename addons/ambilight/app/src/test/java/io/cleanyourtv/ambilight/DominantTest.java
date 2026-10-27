package io.cleanyourtv.ambilight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DominantTest {

    @Test
    public void allBlackIsBlack() {
        assertEquals(0, Dominant.pick(new int[]{0, 0x050505, 0x0A0A0A}));
    }

    @Test
    public void theMostCommonColourWins() {
        int orange = 0xFF8000, white = 0xF0F0F0;
        int c = Dominant.pick(new int[]{orange, orange, orange, white, white, 0});
        assertEquals(orange, c);
    }

    @Test
    public void vividAreasOutweighAnEqualNumberOfGreyOnes() {
        int c = Dominant.pick(new int[]{0xE0E0E0, 0xE0E0E0, 0xE0E0E0, 0xFF5010, 0xFF5010, 0xFF5010});
        assertEquals(0xFF5010, c);
    }

    @Test
    public void similarColoursAreAveragedTogether() {
        int c = Dominant.pick(new int[]{0xFF8000, 0xF07010, 0x0000FF});
        assertTrue((c >> 16 & 0xFF) > 240 && (c & 0xFF) < 20);
    }

    @Test
    public void blackBarsDontVote() {
        int c = Dominant.pick(new int[]{0, 0, 0, 0, 0x2060FF, 0x2060FF});
        assertEquals(0x2060FF, c);
    }

    @Test
    public void gridCoversThePanelWithoutOverlap() {
        int[][] g = ColorTakerSource.grid(3840, 2160, 3, 2);
        assertEquals(6, g.length);
        assertArrayEquals(new int[]{0, 1279, 0, 1079}, g[0]);
        assertArrayEquals(new int[]{2560, 3839, 1080, 2159}, g[5]);
        // Each area stays under the size where the blue sum overflowed (about 4 million pixels).
        for (int[] a : g) assertTrue((long) (a[1] - a[0] + 1) * (a[3] - a[2] + 1) < 4_000_000L);
    }

    @Test
    public void reportsAreSixBit() {
        assertEquals(0xFF8200, ColorTakerSource.toRgb(new int[]{0, 63, 32, 0}));
        assertEquals(0xFFFFFF, ColorTakerSource.toRgb(new int[]{0, 63, 63, 63}));
        assertEquals(0x828282, ColorTakerSource.toRgb(new int[]{0, 32, 32, 32}));
    }
}
