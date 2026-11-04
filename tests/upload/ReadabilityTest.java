package home.cleanframe.tv;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static home.cleanframe.tv.UploadTests.check;
import static home.cleanframe.tv.UploadTests.eq;
import static home.cleanframe.tv.UploadTests.run;

/**
 * Readability (android/.../Readability.java) against v1's vectors (tools/photo-upload/readability.py,
 * removed in v2): derive() on the committed measurements of upstream's 15 hand-reviewed wallpapers
 * (tests/upload/data/upstream-readability.json) gives exactly v1's agreement counts and the same
 * misses; measure() of the synthetic stage (tests/upload/data/synthetic-stage.png, see synthetic.py)
 * equals the numpy original's numbers (synthetic-measure.json) to the last digit; the busy picture
 * keeps frostVeil at 0.44 (the app's range); exact summation and Python rounding behave.
 */
final class ReadabilityTest {
    static final String[] KEYS = {"tone", "ut.tr", "ut.br", "frostVeil", "listColumn", "dockQuiet", "clockMist"};

    static Object field(Readability.Fields f, String k) {
        switch (k) {
            case "tone": return f.tone;
            case "ut.tr": return f.tr;
            case "ut.br": return f.br;
            case "frostVeil": return f.frostVeil;
            case "listColumn": return (long) f.listColumn;
            case "dockQuiet": return f.dockQuiet;
            default: return f.clockMist;
        }
    }

    @SuppressWarnings("unchecked")
    static Object reviewed(Map<String, Object> r, String k) {
        Object v = k.startsWith("ut.") ? ((Map<String, Object>) r.get("ut")).get(k.substring(3)) : r.get(k);
        return v instanceof Number && k.equals("frostVeil") ? ((Number) v).doubleValue() : v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Readability.Stat> stats(Map<String, Object> m) {
        Map<String, Readability.Stat> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Map<String, Object> s = (Map<String, Object>) e.getValue();
            out.put(e.getKey(), new Readability.Stat(((Number) s.get("mean")).doubleValue(), ((Number) s.get("p95")).doubleValue(),
                    ((Number) s.get("sd")).doubleValue()));
        }
        return out;
    }

    static int[] pixels(String path, int[] size) throws Exception {
        BufferedImage im = javax.imageio.ImageIO.read(new File(path));
        size[0] = im.getWidth();
        size[1] = im.getHeight();
        return im.getRGB(0, 0, size[0], size[1], null, 0, size[0]);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] a) {
        run("readability agreement with upstream's 15 = v1's counts and misses", () -> {
            Map<String, Object> data = UploadTests.obj(UploadTests.read("tests/upload/data/upstream-readability.json"));
            List<Object> ws = (List<Object>) data.get("wallpapers");
            eq(ws.size(), 15, "wallpapers");
            Map<String, Integer> hits = new LinkedHashMap<>();
            Map<String, List<String>> miss = new TreeMap<>();
            for (String k : KEYS) {
                hits.put(k, 0);
                miss.put(k, new ArrayList<>());
            }
            List<Object> sorted = new ArrayList<>(ws);
            sorted.sort((x, y) -> ((String) ((Map<String, Object>) x).get("id")).compareTo((String) ((Map<String, Object>) y).get("id")));
            for (Object o : sorted) {
                Map<String, Object> w = (Map<String, Object>) o;
                Readability.Fields f = Readability.derive(stats((Map<String, Object>) w.get("m")));
                for (String k : KEYS) {
                    if (field(f, k).equals(reviewed((Map<String, Object>) w.get("reviewed"), k))) hits.merge(k, 1, Integer::sum);
                    else miss.get(k).add((String) w.get("id"));
                }
            }
            Map<String, Integer> want = new LinkedHashMap<>();
            int[] counts = {11, 15, 14, 14, 11, 14, 14}; // v1: tone 11, ut.tr 15, ut.br 14, frostVeil 14, listColumn 11, dockQuiet 14, clockMist 14
            for (int i = 0; i < KEYS.length; i++) want.put(KEYS[i], counts[i]);
            eq(hits, want, "agreement");
            eq(miss.get("tone"), Arrays.asList("bridge", "cove", "night", "peaks"), "tone misses");
            eq(miss.get("listColumn"), Arrays.asList("alpine", "amber", "goldcoast", "valley"), "listColumn misses");
            eq(miss.get("frostVeil"), Arrays.asList("golden"), "frostVeil misses");
        });
        run("readability measure(synthetic stage) = the numpy original exactly", () -> {
            Map<String, Object> ref = (Map<String, Object>) UploadTests.obj(UploadTests.read("tests/upload/data/synthetic-measure.json")).get("m");
            int[] size = new int[2];
            int[] px = pixels("tests/upload/data/synthetic-stage.png", size);
            eq(size[0] + "x" + size[1], "1920x1080", "stage size");
            Map<String, Readability.Stat> got = Readability.measure(px, size[0], size[1]);
            Map<String, Readability.Stat> want = stats(ref);
            eq(new ArrayList<>(got.keySet()), new ArrayList<>(want.keySet()), "regions");
            for (String r : want.keySet()) {
                Readability.Stat g = got.get(r), w = want.get(r);
                check(g.mean == w.mean && g.p95 == w.p95 && g.sd == w.sd, r + ": got " + g + ", want " + w);
            }
        });
        run("readability: a 1920x1280 wall is measured on its centred 1920x1080", () -> {
            int[] size = new int[2];
            int[] stage = pixels("tests/upload/data/synthetic-stage.png", size);
            int[] wall = new int[1920 * 1280];
            Arrays.fill(wall, 0xffffffff); // the 100 rows above and below must not count
            System.arraycopy(stage, 0, wall, 100 * 1920, stage.length);
            Map<String, Readability.Stat> a1 = Readability.measure(stage, 1920, 1080), a2 = Readability.measure(wall, 1920, 1280);
            for (String r : a1.keySet()) eq(a2.get(r).toString(), a1.get(r).toString(), r);
            for (int[] bad : new int[][]{{1919, 1280}, {1920, 1079}, {2000, 1200}, {1000, 1080}}) {
                boolean refused = false;
                try {
                    Readability.measure(new int[bad[0] * bad[1]], bad[0], bad[1]);
                } catch (IllegalArgumentException e) {
                    refused = true;
                }
                check(refused, "measure accepted " + bad[0] + "x" + bad[1] + " (needs a resize first)");
            }
        });
        run("readability: busy picture keeps frostVeil 0.44 in the app's range", () -> {
            int[] busy = new int[1920 * 1080];
            for (int y = 0; y < 1080; y++) Arrays.fill(busy, y * 1920, y * 1920 + 960, 0xffffffff);
            Readability.Fields d = Readability.derive(Readability.measure(busy, 1920, 1080));
            eq(d.frostVeil, 0.44, "derive frostVeil");
            eq(Readability.metaFields(busy, 1920, 1080).frostVeil, 0.44, "meta frostVeil");
            eq(Readability.FROST_VEIL_MAX, UserLibrary.FROST_VEIL_MAX, "the app's range");
            int[] black = new int[1920 * 1080];
            Readability.Fields f = Readability.metaFields(black, 1920, 1080);
            eq(f.tone + "|" + f.tr + "|" + f.br + "|" + f.frostVeil + "|" + f.listColumn + "|" + f.dockQuiet + "|" + f.clockMist,
                    "dark|light|light|0.2|0|false|false", "black");
        });
        run("readability: p95 is numpy's linear percentile at 0.95 * (n - 1)", () -> {
            // tl is 560x210 = 117600 pixels: 0.95 * 117599 = 111719.05, so with the first 111720 black and
            // the rest white the 95th percentile is 5% of the way from black to white
            int[] px = new int[1920 * 1080];
            java.util.Arrays.fill(px, 0xffffffff);
            for (int i = 0; i < 111720; i++) px[(i / 560) * 1920 + i % 560] = 0xff000000;
            Readability.Stat tl = Readability.measure(px, 1920, 1080).get("tl");
            eq(tl.p95, 0.05, "p95 between the two sorted values");
            eq(tl.mean, 0.05, "mean: 5% white");
        });
        run("readability: derive's thresholds sit where v1's do", () -> {
            Map<String, Readability.Stat> m = new LinkedHashMap<>();
            Readability.Stat mid = new Readability.Stat(.5, .5, .1);
            for (String r : Readability.REGION_NAMES) m.put(r, mid);
            m.put("all", new Readability.Stat(.25, .5, .1));
            eq(Readability.derive(m).tone, "light", "all.mean .25 is light");
            m.put("all", new Readability.Stat(.2499, .5, .1));
            eq(Readability.derive(m).tone, "dark", ".2499 is dark");
            eq(Readability.derive(m).frostVeil, .20, "dark, sd .1, mean .2499: .20");
            m.put("all", new Readability.Stat(.1999, .5, .1));
            eq(Readability.derive(m).frostVeil, .12, "mean < .20 and sd >= .10: .12");
            m.put("all", new Readability.Stat(.35, .5, .1));
            eq(Readability.derive(m).frostVeil, .36, "mean .35: .36");
            m.put("all", new Readability.Stat(.1, .5, .285));
            eq(Readability.derive(m).frostVeil, .44, "sd .285: .44");
            m.put("tr", new Readability.Stat(.84, .9, .1));
            eq(Readability.derive(m).tr, "dark", "corner mean .84 is dark");
            m.put("tr", new Readability.Stat(.0999, .2999, .1));
            eq(Readability.derive(m).tr, "light", "corner .0999/.2999 is light");
            m.put("tr", new Readability.Stat(.0999, .30, .1));
            eq(Readability.derive(m).tr, "mist", "p95 .30 is mist");
            m.put("col", new Readability.Stat(.20, .5, .1));
            eq(Readability.derive(m).listColumn, 2, "col .20: 2");
            m.put("col", new Readability.Stat(.30, .5, .1));
            eq(Readability.derive(m).listColumn, 3, "col .30: 3");
            m.put("all", new Readability.Stat(.1, .5, .1));
            m.put("dock", new Readability.Stat(.25, .5, .1));
            eq(Readability.derive(m).dockQuiet, true, "dark and dock .25: quiet");
            m.put("tl", new Readability.Stat(.1, .50, .1));
            m.put("tr", new Readability.Stat(.5, .5, .1));
            eq(Readability.derive(m).clockMist, true, "tl.p95 .50 and tr not dark: clockMist");
            m.put("tl", new Readability.Stat(.1, .4999, .1));
            eq(Readability.derive(m).clockMist, false, "tl.p95 .4999: none");
        });
        run("readability: fsum is exact and round4 is Python's round(x, 4)", () -> {
            Readability.FSum s = new Readability.FSum();
            for (double x : new double[]{1e16, 1.0, -1e16}) s.add(x);
            eq(s.sum(), 1.0, "1e16 + 1 - 1e16");
            Readability.FSum t = new Readability.FSum();
            for (int i = 0; i < 10; i++) t.add(0.1);
            eq(t.sum(), 1.0, "ten 0.1"); // a naive sum gives 0.9999999999999999
            // CPython's test_math fsum cases for the half-even correction across partials
            double[][][] cases = {
                    {{Math.pow(2, 53), -0.5, -Math.pow(2, -54)}, {Math.pow(2, 53) - 1.0}},
                    {{Math.pow(2, 53), 1.0, Math.pow(2, -100)}, {Math.pow(2, 53) + 2.0}},
                    {{Math.pow(2, 53) + 10.0, 1.0, Math.pow(2, -100)}, {Math.pow(2, 53) + 12.0}},
                    {{Math.pow(2, 53) - 4.0, 0.5, Math.pow(2, -54)}, {Math.pow(2, 53) - 3.0}},
            };
            for (double[][] c : cases) {
                Readability.FSum f = new Readability.FSum();
                for (double x : c[0]) f.add(x);
                eq(f.sum(), c[1][0], "fsum " + java.util.Arrays.toString(c[0]));
            }
            eq(Readability.round4(0.12345), 0.1235, "0.12345 is above the half (binary)");
            eq(Readability.round4(0.00005), 0.0001, "0.00005");
            eq(Readability.round4(2.675), 2.675, "already 4 digits");
            eq(Readability.round4(0.30000000000000004), 0.3, "0.30000000000000004");
            eq(Readability.round4(1.00005), 1.0001, "1.00005 is above the half (binary)");
            eq(Readability.round4(0.00015), 0.0001, "0.00015 is below the half (binary)");
        });
    }
}
