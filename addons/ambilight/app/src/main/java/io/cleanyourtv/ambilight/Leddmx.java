package io.cleanyourtv.ambilight;

/**
 * Frames for LEDDMX Bluetooth LED controllers (the "LED LAMP" app family; advertised as LEDDMX-00/01/03-xxxx).
 * They take 9-byte frames on characteristic FFE1 of service FFE0, written without response, framed 7B .. BF.
 * Worked out by the projects that reverse-engineered the vendor app (wall-led, LED_UNION, LEDDMX-00) and checked
 * on an LEDDMX-03 strip.
 */
final class Leddmx {

    static final String NAME_PREFIX = "LEDDMX";
    static final java.util.UUID SERVICE = java.util.UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    static final java.util.UUID CHARACTERISTIC = java.util.UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    private Leddmx() {
    }

    static byte[] color(int r, int g, int b) {
        return new byte[]{0x7B, (byte) 0xFF, 0x07, (byte) r, (byte) g, (byte) b, 0x00, (byte) 0xFF, (byte) 0xBF};
    }

    /** The controller's own brightness, 0..100; colours are scaled by it on top of their own values. */
    static byte[] brightness(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        return new byte[]{0x7B, (byte) 0xFF, 0x01, (byte) (p * 32 / 100), (byte) p, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xBF};
    }

    static byte[] power(boolean on) {
        return new byte[]{0x7B, 0x00, 0x04, (byte) (on ? 1 : 0), 0x00, 0x00, (byte) 0xFF, 0x00, (byte) 0xBF};
    }
}
