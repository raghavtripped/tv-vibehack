package io.cleanyourtv.ambilight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AmbiColorTest {

    private static final int W = 16, H = 9;

    private static byte[] fill(int rgb) {
        byte[] p = new byte[W * H * 4];
        for (int i = 0; i < W * H; i++) set(p, i % W, i / W, rgb);
        return p;
    }

    private static void set(byte[] p, int x, int y, int rgb) {
        int i = (y * W + x) * 4;
        p[i] = (byte) (rgb >> 16);
        p[i + 1] = (byte) (rgb >> 8);
        p[i + 2] = (byte) rgb;
        p[i + 3] = (byte) 0xFF;
    }

    private static int red(int c) {
        return c >> 16 & 0xFF;
    }

    private static int green(int c) {
        return c >> 8 & 0xFF;
    }

    private static int blue(int c) {
        return c & 0xFF;
    }

    @Test
    public void blackFrameTurnsTheStripOff() {
        assertEquals(0, AmbiColor.frameColor(fill(0x000000), W, H, W * 4));
        assertEquals(0, AmbiColor.frameColor(fill(0x0A0A0A), W, H, W * 4));
    }

    @Test
    public void solidColourKeepsItsHue() {
        int c = AmbiColor.frameColor(fill(0x2060C0), W, H, W * 4);
        assertTrue(blue(c) > green(c) && green(c) > red(c));
    }

    @Test
    public void letterboxBarsAreIgnored() {
        byte[] p = fill(0x000000);
        for (int y = 2; y < H - 2; y++) for (int x = 0; x < W; x++) set(p, x, y, 0xC02020);
        int c = AmbiColor.frameColor(p, W, H, W * 4);
        assertTrue("bars must not darken the colour", red(c) > 150);
        assertTrue(red(c) > green(c) * 3);
    }

    @Test
    public void edgesOutweighTheMiddle() {
        byte[] p = fill(0x0000C0);
        for (int y = 3; y < 6; y++) for (int x = 4; x < 12; x++) set(p, x, y, 0xC00000);
        int c = AmbiColor.frameColor(p, W, H, W * 4);
        assertTrue("the blue edges win", blue(c) > red(c));
    }

    @Test
    public void rowStridePaddingIsSkipped() {
        int stride = W * 4 + 8;
        byte[] p = new byte[stride * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * stride + x * 4;
                p[i + 1] = (byte) 0xB0; // green
            }
            for (int pad = W * 4; pad < stride; pad++) p[y * stride + pad] = (byte) 0xFF;
        }
        int c = AmbiColor.frameColor(p, W, H, stride);
        assertEquals(0, red(c));
        assertEquals(0, blue(c));
    }

    @Test
    public void greyStaysGrey() {
        int c = AmbiColor.grade(128, 128, 128);
        assertEquals(red(c), green(c));
        assertEquals(green(c), blue(c));
    }

    @Test
    public void gradingBoostsSaturationWhenAsked() {
        AmbiColor.saturation = 1.35;
        try {
            int c = AmbiColor.grade(200, 150, 150);
            assertEquals(200 * 1.0, red(c), 30);
            assertTrue("the weaker channels move further away", green(c) < 150 * red(c) / 200);
        } finally {
            AmbiColor.saturation = AmbiColor.SATURATION;
        }
    }

    @Test
    public void byDefaultAPastelStaysPastel() {
        int c = AmbiColor.grade(255, 209, 220); // pastel pink
        float[] in = Director.hsv(0xFFD1DC), out = Director.hsv(c);
        assertEquals(in[1], out[1], 0.01);
        assertEquals(in[0], out[0], 1);
    }

    @Test
    public void smoothingStartsAtTheFirstColourThenEases() {
        AmbiColor s = AmbiColor.smoother();
        assertEquals(0xFF0000, s.smooth(0xFF0000, 0.5));
        int next = s.smooth(0x0000FF, 0.5);
        assertTrue(red(next) > 100 && red(next) < 160);
        assertTrue(blue(next) > 100 && blue(next) < 160);
    }

    @Test
    public void dimScalesEveryChannel() {
        assertEquals(0x7F4020, AmbiColor.dim(0xFF8040, 50));
        assertEquals(0, AmbiColor.dim(0xFFFFFF, 0));
        assertEquals(0xFFFFFF, AmbiColor.dim(0xFFFFFF, 150));
    }

    @Test
    public void differsUsesAThreshold() {
        assertFalse(AmbiColor.differs(0x101010, 0x121212, 2));
        assertTrue(AmbiColor.differs(0x101010, 0x101014, 2));
    }

    @Test
    public void leddmxFrames() {
        assertArrayEquals(new byte[]{0x7B, (byte) 0xFF, 0x07, (byte) 0xFF, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xBF},
                Leddmx.color(255, 0, 0));
        assertArrayEquals(new byte[]{0x7B, 0x00, 0x04, 0x01, 0x00, 0x00, (byte) 0xFF, 0x00, (byte) 0xBF}, Leddmx.power(true));
        assertEquals(0x00, Leddmx.power(false)[3]);
    }

    @Test
    public void averagedColoursUseTheLowerBlackLevel() {
        assertEquals(0, AmbiColor.averageColor(0x050403, AmbiColor.BLACK_AVERAGE));
        int dim = AmbiColor.averageColor(0x110C09, AmbiColor.BLACK_AVERAGE);
        assertTrue("a dark film scene still has a colour", dim != 0);
        assertTrue("and keeps its warm hue", red(dim) > blue(dim));
        assertEquals(0, AmbiColor.averageColor(0x110C09, AmbiColor.BLACK));
    }
}
