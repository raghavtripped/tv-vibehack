package io.cleanyourtv.ambilight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;

public class CameraSourceTest {

    @Test
    public void cropParsesOrFallsBack() {
        assertArrayEquals(new float[]{0.2f, 0.25f, 0.8f, 0.75f}, CameraSource.crop("0.2, 0.25,0.8,0.75"), 0);
        float[] d = {0.1f, 0.1f, 0.9f, 0.9f};
        assertArrayEquals(d, CameraSource.crop(null), 0);
        assertArrayEquals(d, CameraSource.crop("nonsense"), 0);
        assertArrayEquals(d, CameraSource.crop("0.5,0.5,0.5,0.5"), 0);
        assertArrayEquals(new float[]{0f, 0f, 1f, 1f}, CameraSource.crop("-1,-1,2,2"), 0);
    }

    /** A w x h YUV_420_888 frame, planar, one colour. */
    private static byte[] convert(int w, int h, int yv, int uv, int vv) {
        ByteBuffer y = ByteBuffer.allocate(w * h), u = ByteBuffer.allocate(w * h / 4), v = ByteBuffer.allocate(w * h / 4);
        for (int i = 0; i < w * h; i++) y.put(i, (byte) yv);
        for (int i = 0; i < w * h / 4; i++) {
            u.put(i, (byte) uv);
            v.put(i, (byte) vv);
        }
        byte[] out = new byte[CameraSource.WIDTH * CameraSource.HEIGHT * 4];
        CameraSource.toRgba(w, h, y, w, 1, u, v, w / 2, 1, CameraSource.crop(null), out);
        return out;
    }

    @Test
    public void greyStaysGrey() {
        byte[] out = convert(160, 120, 128, 128, 128);
        assertEquals(128, out[0] & 0xFF);
        assertEquals(128, out[1] & 0xFF);
        assertEquals(128, out[2] & 0xFF);
        assertEquals(0xFF, out[3] & 0xFF);
    }

    @Test
    public void redComesOutRed() {
        // Pure red in BT.601 full range: Y 76, U 85, V 255.
        byte[] out = convert(160, 120, 76, 85, 255);
        int r = out[0] & 0xFF, g = out[1] & 0xFF, b = out[2] & 0xFF;
        assertTrue(r > 240 && g < 15 && b < 15);
        int c = AmbiColor.frameColor(out, CameraSource.WIDTH, CameraSource.HEIGHT, CameraSource.WIDTH * 4);
        assertTrue((c >> 16 & 0xFF) > 200);
    }
}
