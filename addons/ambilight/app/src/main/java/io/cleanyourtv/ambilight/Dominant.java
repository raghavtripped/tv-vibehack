package io.cleanyourtv.ambilight;

/**
 * The dominant colour of a screen measured as a few areas: areas of similar colour are grouped, each group is
 * weighted by how many areas it covers (vivid areas count a bit more, so an orange costume against a white wall
 * still shows), and the heaviest group's average wins. Black areas (letterbox bars, a dark corner) don't vote.
 */
final class Dominant {

    /** Areas whose colours are closer than this (RGB distance, 0..441) belong to the same group. */
    static final double SAME = 70;
    /** Below this (max channel, 0..255) an area counts as black. */
    static final int BLACK = 14;
    /** A small nudge only: a mostly white or pale scene must not be overruled by one vivid corner. */
    static final double VIVID_BONUS = 0.2;

    private Dominant() {
    }

    /** @param cells 0xRRGGBB per area, 0..255 per channel; @return the dominant colour, or 0 if all are black */
    static int pick(int[] cells) {
        int n = cells.length;
        boolean[] lit = new boolean[n];
        double[] weight = new double[n];
        int litCount = 0;
        for (int i = 0; i < n; i++) {
            int c = cells[i];
            int max = Math.max(c >> 16 & 0xFF, Math.max(c >> 8 & 0xFF, c & 0xFF));
            lit[i] = max >= BLACK;
            if (!lit[i]) continue;
            litCount++;
            weight[i] = 1 + VIVID_BONUS * Director.hsv(c)[1];
        }
        if (litCount == 0) return 0;
        int best = -1;
        double bestWeight = -1;
        for (int i = 0; i < n; i++) {
            if (!lit[i]) continue;
            double w = 0;
            for (int j = 0; j < n; j++) if (lit[j] && distance(cells[i], cells[j]) < SAME) w += weight[j];
            if (w > bestWeight) {
                bestWeight = w;
                best = i;
            }
        }
        double r = 0, g = 0, b = 0, total = 0;
        for (int j = 0; j < n; j++) {
            if (!lit[j] || distance(cells[best], cells[j]) >= SAME) continue;
            r += (cells[j] >> 16 & 0xFF) * weight[j];
            g += (cells[j] >> 8 & 0xFF) * weight[j];
            b += (cells[j] & 0xFF) * weight[j];
            total += weight[j];
        }
        return (int) Math.round(r / total) << 16 | (int) Math.round(g / total) << 8 | (int) Math.round(b / total);
    }

    static double distance(int a, int b) {
        int dr = (a >> 16 & 0xFF) - (b >> 16 & 0xFF), dg = (a >> 8 & 0xFF) - (b >> 8 & 0xFF), db = (a & 0xFF) - (b & 0xFF);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
