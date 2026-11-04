package home.cleanframe.tv;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Readability fields for an uploaded wallpaper (docs/PHOTO-GROUPS.md, contract 4): the luminance
 * rules the Space gallery used, ported from v1's tools/photo-upload/readability.py (removed in v2)
 * with the same arithmetic, so the same picture gives the same numbers and the same fields.
 *
 * measure(): linear-light luminance (sRGB EOTF, Rec.709 weights) of the on-screen 1920x1080
 * centred crop, per region: mean, 95th percentile (numpy's linear method) and population sd, each
 * rounded to 4 decimals. Like v1 (Pillow mode "F"), each channel's contribution is a float32 table
 * entry and the three are added in float32, (r + g) + b; the sums are exactly rounded (a port of
 * Python's math.fsum), so the result does not depend on summation order.
 * derive(): the thresholds, the same values as v1 (fitted to upstream's 15 hand-reviewed catalog
 * entries; agreement: tone 11/15, ut.tr 15/15, ut.br 14/15, frostVeil 14/15, listColumn 11/15,
 * dockQuiet 14/15, clockMist 14/15 on tests/java/data/upstream-readability.json, harness
 * tests/java/ReadabilityTest.java).
 *
 * Plain Java: the caller hands in ARGB pixels (MainActivity's processor reads them off the prepared
 * 1920x1280 wall, whose centred 1920x1080 crop is the stage, so no resampling is needed).
 */
final class Readability {
    static final int STAGE_W = 1920, STAGE_H = 1080;
    static final String[] REGION_NAMES = {"tl", "tr", "br", "col", "dock", "all"};
    /** x0, y0, x1, y1 on the 1920x1080 stage, in REGION_NAMES order. */
    static final int[][] REGIONS = {
            {0, 0, 560, 210},          // clock (main.js .shade ellipse at 188,104)
            {1300, 0, 1920, 210},      // top-right utilities (ellipse at 1620,104)
            {1300, 870, 1920, 1080},   // bottom-right utilities
            {0, 0, 600, 1080},         // List layout column (.ph-col 0..600px)
            {96, 676, 1824, 908},      // dock band, as upstream build-materials.mjs samples it
            {0, 0, 1920, 1080},
    };
    static final double[] WEIGHTS = {.2126, .7152, .0722};
    static final double FROST_VEIL_MAX = UserLibrary.FROST_VEIL_MAX;
    /** Each channel's float32 contribution, as v1's band.point(..., "F") tables. */
    private static final float[][] LUT = new float[3][256];

    static {
        for (int c = 0; c < 3; c++) {
            for (int v = 0; v < 256; v++) LUT[c][v] = (float) (linear(v) * WEIGHTS[c]);
        }
    }

    private Readability() {
    }

    static double linear(int v8) {
        double v = v8 / 255.0;
        return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
    }

    static final class Stat {
        final double mean, p95, sd;

        Stat(double mean, double p95, double sd) {
            this.mean = mean;
            this.p95 = p95;
            this.sd = sd;
        }

        @Override
        public String toString() {
            return "{mean=" + mean + ", p95=" + p95 + ", sd=" + sd + "}";
        }
    }

    /** The meta.json readability fields (contract 1), with upstream catalog value sets. */
    static final class Fields {
        final String tone, tr, br;
        final double frostVeil;
        final int listColumn;
        final boolean dockQuiet, clockMist;

        Fields(String tone, String tr, String br, double frostVeil, int listColumn, boolean dockQuiet, boolean clockMist) {
            this.tone = tone;
            this.tr = tr;
            this.br = br;
            this.frostVeil = frostVeil;
            this.listColumn = listColumn;
            this.dockQuiet = dockQuiet;
            this.clockMist = clockMist;
        }
    }

    /**
     * The stage of a picture that already covers 1920x1080 at scale 1 (one side exactly 1920 wide or
     * 1080 high, the other at least that): its centred crop, as v1's stage() without a resize.
     * argb: w*h pixels, row-major (alpha ignored). Any other size is refused.
     */
    static Map<String, Stat> measure(int[] argb, int w, int h) {
        boolean cover = (w == STAGE_W && h >= STAGE_H) || (h == STAGE_H && w >= STAGE_W);
        if (!cover || argb.length < (long) w * h) throw new IllegalArgumentException("not a 1920x1080 cover picture: " + w + "x" + h);
        int x0 = (w - STAGE_W) / 2, y0 = (h - STAGE_H) / 2;
        float[] lum = new float[STAGE_W * STAGE_H];
        for (int y = 0; y < STAGE_H; y++) {
            int row = (y0 + y) * w + x0, out = y * STAGE_W;
            for (int x = 0; x < STAGE_W; x++) {
                int p = argb[row + x];
                float r = LUT[0][(p >> 16) & 0xff], g = LUT[1][(p >> 8) & 0xff], b = LUT[2][p & 0xff];
                lum[out + x] = (r + g) + b; // float32 adds, in v1's order
            }
        }
        Map<String, Stat> m = new LinkedHashMap<>();
        for (int i = 0; i < REGIONS.length; i++) m.put(REGION_NAMES[i], stats(lum, REGIONS[i]));
        return m;
    }

    private static Stat stats(float[] lum, int[] box) {
        int bw = box[2] - box[0], bh = box[3] - box[1], n = bw * bh;
        float[] v = new float[n];
        for (int y = 0; y < bh; y++) System.arraycopy(lum, (box[1] + y) * STAGE_W + box[0], v, y * bw, bw);
        FSum s = new FSum();
        for (float x : v) s.add(x);
        double mean = s.sum() / n;
        FSum q = new FSum();
        for (float x : v) {
            double d = x - mean;
            q.add(d * d);
        }
        double sd = Math.sqrt(q.sum() / n);
        Arrays.sort(v);
        double k = .95 * (n - 1);
        int lo = (int) Math.floor(k);
        double p95 = v[lo] + ((double) v[Math.min(lo + 1, n - 1)] - v[lo]) * (k - lo);
        return new Stat(round4(mean), round4(p95), round4(sd));
    }

    /** Python's round(x, 4): half-even on the exact binary value, then the nearest double. */
    static double round4(double x) {
        return Double.parseDouble(new BigDecimal(x).setScale(4, RoundingMode.HALF_EVEN).toPlainString());
    }

    static String corner(Stat r) {
        if (r.mean >= .84) return "dark";
        if (r.mean < .10 && r.p95 < .30) return "light";
        return "mist";
    }

    static Fields derive(Map<String, Stat> m) {
        Stat a = m.get("all"), tl = m.get("tl"), col = m.get("col"), dock = m.get("dock");
        String tone = a.mean >= .25 ? "light" : "dark";
        double fv = a.sd >= .285 ? .44 : a.mean >= .35 ? .36 : (a.mean < .20 && a.sd >= .10) ? .12 : .20;
        int lc = col.mean < .20 ? 0 : col.mean < .30 ? 2 : 3;
        String tr = corner(m.get("tr"));
        return new Fields(tone, tr, corner(m.get("br")), fv, lc, tone.equals("dark") && dock.mean >= .25,
                tl.p95 >= .50 && !tr.equals("dark"));
    }

    /** The readability part of meta.json for a prepared wallpaper (frostVeil kept in the app's range). */
    static Fields metaFields(int[] argb, int w, int h) {
        Fields f = derive(measure(argb, w, h));
        return new Fields(f.tone, f.tr, f.br, Math.min(f.frostVeil, FROST_VEIL_MAX), f.listColumn, f.dockQuiet, f.clockMist);
    }

    /**
     * Exactly rounded sum of doubles: Shewchuk's algorithm as CPython's math.fsum implements it
     * (finite inputs only), including its half-even correction across partials.
     */
    static final class FSum {
        private double[] p = new double[32];
        private int n;

        void add(double x) {
            int i = 0;
            for (int j = 0; j < n; j++) {
                double y = p[j];
                if (Math.abs(x) < Math.abs(y)) {
                    double t = x;
                    x = y;
                    y = t;
                }
                double hi = x + y;
                double lo = y - (hi - x);
                if (lo != 0.0) p[i++] = lo;
                x = hi;
            }
            n = i;
            if (x != 0.0) {
                if (n == p.length) p = Arrays.copyOf(p, n * 2);
                p[n++] = x;
            }
        }

        double sum() {
            double hi = 0.0, lo = 0.0;
            int k = n;
            if (k > 0) {
                hi = p[--k];
                while (k > 0) {
                    double x = hi, y = p[--k];
                    hi = x + y;
                    double yr = hi - x;
                    lo = y - yr;
                    if (lo != 0.0) break;
                }
                if (k > 0 && ((lo < 0.0 && p[k - 1] < 0.0) || (lo > 0.0 && p[k - 1] > 0.0))) {
                    double y = lo * 2.0, x = hi + y, yr = x - hi;
                    if (y == yr) hi = x;
                }
            }
            return hi;
        }
    }
}
