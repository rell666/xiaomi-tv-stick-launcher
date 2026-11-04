package home.cleanframe.tv;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static home.cleanframe.tv.UploadTests.check;
import static home.cleanframe.tv.UploadTests.eq;
import static home.cleanframe.tv.UploadTests.run;

/**
 * The plain-Java arithmetic behind AndroidPhotoProcessor (PhotoMath): cover crop, the user's crop
 * (parse, clamp, frame, place, its rect in the stored frame for every orientation, the place matrix,
 * tests/upload/data/crop-vectors.json, which web/photo-crop.js must also give), inSampleSize, the
 * eight EXIF orientations and the draw matrix, type sniffing, size limits, display names, meta.json
 * (with crop and edited); Jpeg.parse (frame size, metadata segments: a planted EXIF/GPS, XMP, IPTC or
 * comment is found) and Jpeg.strip; UploadServer.pickAddress and baseUrl.
 */
final class PhotoMathTest {
    static String s(int[] a) {
        return Arrays.toString(a);
    }

    /** Where m sends (x, y), rounded, as "x,y". */
    static String at(float[] m, float x, float y) {
        float[] p = PhotoMath.apply(m, x, y);
        return Math.round(p[0]) + "," + Math.round(p[1]);
    }

    static byte[] segment(int marker, String payload) {
        byte[] p = payload.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[4 + p.length];
        out[0] = (byte) 0xff;
        out[1] = (byte) marker;
        out[2] = (byte) ((p.length + 2) >> 8);
        out[3] = (byte) (p.length + 2);
        System.arraycopy(p, 0, out, 4, p.length);
        return out;
    }

    /** jpg with seg inserted right after SOI. */
    static byte[] plant(byte[] jpg, byte[] seg) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(jpg, 0, 2);
        o.write(seg, 0, seg.length);
        o.write(jpg, 2, jpg.length - 2);
        return o.toByteArray();
    }

    static double[] concat(double[]... parts) {
        int n = 0;
        for (double[] p : parts) n += p.length;
        double[] out = new double[n];
        int i = 0;
        for (double[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }

    /** The stored rect AndroidPhotoProcessor decodes for a w x h stored photo with this orientation and crop. */
    static int[] cut(int w, int h, int o, PhotoMath.Crop c) {
        boolean t = PhotoMath.transposed(o);
        double[] p = PhotoMath.place(t ? h : w, t ? w : h, c);
        return PhotoMath.toStored((int) p[0], (int) p[1], (int) p[2], (int) p[3], w, h, o);
    }

    /** Its inSampleSize, as AndroidPhotoProcessor works it out. */
    static int sampleFor(int w, int h, int o, PhotoMath.Crop c) {
        boolean t = PhotoMath.transposed(o);
        double[] p = PhotoMath.place(t ? h : w, t ? w : h, c);
        int[] cr = cut(w, h, o, c);
        return PhotoMath.sampleSize(cr[2], cr[3], Math.max(1, (int) Math.ceil(t ? p[7] : p[6])), Math.max(1, (int) Math.ceil(t ? p[6] : p[7])));
    }

    public static void main(String[] a) {
        run("photomath cover crop is the centred 3:2", () -> {
            eq(s(PhotoMath.coverCrop(4000, 3000, 1920, 1280)), "[0, 166, 4000, 2667]", "4:3 landscape");
            eq(s(PhotoMath.coverCrop(3000, 4000, 1920, 1280)), "[0, 1000, 3000, 2000]", "3:4 portrait");
            eq(s(PhotoMath.coverCrop(1920, 1280, 1920, 1280)), "[0, 0, 1920, 1280]", "exact");
            eq(s(PhotoMath.coverCrop(30000, 2000, 1920, 1280)), "[13500, 0, 3000, 2000]", "panorama");
            eq(s(PhotoMath.coverCrop(64, 64, 1920, 1280)), "[0, 10, 64, 43]", "tiny square");
            eq(s(PhotoMath.coverCrop(1920, 1080, 512, 341)), "[149, 0, 1622, 1080]", "thumb aspect");
        });
        run("photomath crop: parse the field, clamp to the picture, frame and place", () -> {
            check(PhotoMath.parseCrop(null) == null && PhotoMath.parseCrop("") == null && PhotoMath.parseCrop("0.5,0.5") == null, "missing parts");
            check(PhotoMath.parseCrop("0.5,0.5,0") == null && PhotoMath.parseCrop("-1,0.5,1") == null, "zero zoom, negative");
            check(PhotoMath.parseCrop("NaN,0.5,1") == null && PhotoMath.parseCrop("1e3,0.5,1") == null && PhotoMath.parseCrop("0.5,0.5,1,") == null, "not plain decimals");
            check(PhotoMath.parseCrop("0." + "1".repeat(70) + ",0.5,1") == null, "over 64 characters");
            PhotoMath.Crop c = PhotoMath.parseCrop(" 0.25,0.75,2 ");
            eq(c.x + "," + c.y + "," + c.z, "0.25,0.75,2.0", "parsed");
            // 4000x3000: the 16:9 at zoom 1 is 4000x2250, centred: the old centre crop
            eq(Arrays.toString(PhotoMath.frame(4000, 3000, null)), "[0.0, 375.0, 4000.0, 2250.0]", "no crop: the centred 16:9");
            eq(Arrays.toString(PhotoMath.frame(4000, 3000, new PhotoMath.Crop(0, 0, 2))), "[0.0, 0.0, 2000.0, 1125.0]", "zoom 2 pushed into the top-left corner");
            eq(Arrays.toString(PhotoMath.frame(4000, 3000, new PhotoMath.Crop(1, 1, 2))), "[2000.0, 1875.0, 2000.0, 1125.0]", "and the bottom-right");
            eq(PhotoMath.fitZoom(4000, 3000), 0.75, "4:3: the whole picture at zoom 0.75");
            eq(Arrays.toString(PhotoMath.frame(4000, 3000, new PhotoMath.Crop(0, 0, 0.01))), "[-666.6666666666665, 0.0, 5333.333333333333, 3000.0]",
                    "zoomed out past the picture: clamped to the whole of it, centred across");
            PhotoMath.Crop k = PhotoMath.clamp(4000, 3000, new PhotoMath.Crop(0.1, 0.9, 99));
            eq(k.z, PhotoMath.Z_MAX, "zoom capped");
            eq(k.x + "," + k.y, "0.125,0.9", "x kept on the picture, y already on it");
            eq(PhotoMath.clamp(4000, 3000, new PhotoMath.Crop(0.1, 0.99, 4)).y, 0.90625, "y kept on the picture");
            eq(PhotoMath.clamp(3000, 4000, new PhotoMath.Crop(0.9, 0.2, 1)).x, 0.5, "portrait at zoom 1: the frame is the full width, centred");
            eq(PhotoMath.fitZoom(1920, 1080), 1.0, "exactly 16:9");
            // placing: zoomed out on a portrait photo, it is drawn centred with black either side
            double[] pl = PhotoMath.place(3000, 4000, new PhotoMath.Crop(.5, .5, PhotoMath.fitZoom(3000, 4000)));
            eq(Arrays.toString(Arrays.copyOf(pl, 4)), "[0.0, 0.0, 3000.0, 4000.0]", "the whole portrait");
            eq(Math.round(pl[4]) + "," + Math.round(pl[5]) + "," + Math.round(pl[6]) + "," + Math.round(pl[7]), "555,0,810,1080", "centred, full height");
            double[] full = PhotoMath.place(4000, 3000, null);
            eq(Arrays.toString(full), "[0.0, 375.0, 4000.0, 2250.0, 0.0, 0.0, 1920.0, 1080.0]", "zoom 1 fills the wall");
            double[] odd = PhotoMath.place(4000, 3000, new PhotoMath.Crop(0.3333, 0.4444, 1.7));
            check(odd[4] <= 0 && odd[5] <= 0 && odd[4] + odd[6] >= 1920 - 1e-6 && odd[5] + odd[7] >= 1080 - 1e-6,
                    "whole pixels around the frame still cover the wall: " + Arrays.toString(odd));
        });
        run("photomath crop vectors: tests/upload/data/crop-vectors.json (web/photo-crop.js gives the same)", () -> {
            Object parsed = UserLibrary.Json.parse(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("tests/upload/data/crop-vectors.json")), StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked") List<Object> cases = (List<Object>) ((Map<String, Object>) parsed).get("cases");
            check(cases.size() >= 40, "cases: " + cases.size());
            for (Object o : cases) {
                @SuppressWarnings("unchecked") Map<String, Object> v = (Map<String, Object>) o;
                @SuppressWarnings("unchecked") List<Object> in = (List<Object>) v.get("in");
                int w = ((Number) in.get(0)).intValue(), h = ((Number) in.get(1)).intValue();
                PhotoMath.Crop c = in.get(2) == UserLibrary.Json.NULL ? null
                        : new PhotoMath.Crop(((Number) in.get(2)).doubleValue(), ((Number) in.get(3)).doubleValue(), ((Number) in.get(4)).doubleValue());
                PhotoMath.Crop k = PhotoMath.clamp(w, h, c);
                double[] got = concat(new double[]{k.x, k.y, k.z}, PhotoMath.frame(w, h, c), PhotoMath.place(w, h, c));
                @SuppressWarnings("unchecked") List<Object> want = (List<Object>) v.get("out");
                eq(want.size(), got.length, "length");
                for (int i = 0; i < got.length; i++) {
                    double d = ((Number) want.get(i)).doubleValue();
                    check(Math.abs(d - got[i]) < 1e-6, in + " [" + i + "]: " + got[i] + " != " + d);
                }
            }
        });
        run("photomath toStored: a displayed rect, back in the stored frame, for all eight orientations", () -> {
            int rw = 400, rh = 300;
            for (int o = 1; o <= 8; o++) {
                boolean t = PhotoMath.transposed(o);
                int dw = t ? rh : rw, dh = t ? rw : rh;
                for (int[] r : new int[][]{{0, 0, dw, dh}, {10, 20, 30, 40}, {dw - 7, dh - 9, 7, 9}, {50, 0, 1, dh}}) {
                    int[] s = PhotoMath.toStored(r[0], r[1], r[2], r[3], rw, rh, o);
                    float[] m = PhotoMath.orient(o, rw, rh);
                    // the stored rect's corners, sent forward, are the displayed rect's corners
                    float[] p = PhotoMath.apply(m, s[0], s[1]), q = PhotoMath.apply(m, s[0] + s[2], s[1] + s[3]);
                    String back = Math.round(Math.min(p[0], q[0])) + "," + Math.round(Math.min(p[1], q[1])) + ","
                            + Math.round(Math.abs(q[0] - p[0])) + "," + Math.round(Math.abs(q[1] - p[1]));
                    eq(back, r[0] + "," + r[1] + "," + r[2] + "," + r[3], "orientation " + o + " " + Arrays.toString(r));
                    check(s[0] >= 0 && s[1] >= 0 && s[0] + s[2] <= rw && s[1] + s[3] <= rh, "inside the stored picture");
                }
            }
            eq(s(PhotoMath.toStored(0, 375, 4000, 2250, 4000, 3000, 1)), "[0, 375, 4000, 2250]", "normal");
            eq(s(PhotoMath.toStored(0, 375, 4000, 2250, 3000, 4000, 6)), "[375, 0, 2250, 4000]", "shown rotated 90: stored portrait");
        });
        run("photomath place matrix: the decoded region lands on its place upright, every orientation", () -> {
            double[] pl = {0, 0, 0, 0, 100, 50, 800, 450};
            for (int o = 1; o <= 8; o++) {
                boolean t = o >= 5;
                int w = t ? 300 : 400, h = t ? 400 : 300; // decoded region, stored frame (displayed 400x300... any)
                float[] m = PhotoMath.placeMatrix(o, w, h, new double[]{0, 0, 0, 0, 100, 50, t ? 800 : 800, 450});
                int minX = 99999, minY = 99999, maxX = -99999, maxY = -99999;
                for (float[] p : new float[][]{{0, 0}, {w, 0}, {0, h}, {w, h}}) {
                    float[] q = PhotoMath.apply(m, p[0], p[1]);
                    minX = Math.min(minX, Math.round(q[0]));
                    maxX = Math.max(maxX, Math.round(q[0]));
                    minY = Math.min(minY, Math.round(q[1]));
                    maxY = Math.max(maxY, Math.round(q[1]));
                }
                eq(minX + "," + minY + "," + maxX + "," + maxY, "100,50,900,500", "orientation " + o);
                float[] pm = PhotoMath.placeMatrix(o, w, h, new double[]{0, 0, 0, 0, 0, 0, 1920, 1080}), dm = PhotoMath.drawMatrix(o, w, h, 1920, 1080);
                for (int i = 0; i < 9; i++) check(Math.abs(pm[i] - dm[i]) < 1e-3, "at 0,0 it is drawMatrix " + o + " [" + i + "]");
            }
            eq(at(PhotoMath.placeMatrix(6, 300, 400, pl), 0, 0), "900,50", "6: stored TL -> TR of the place");
        });
        run("photomath whole-picture decode: the whole bitmap and its cut stay under 64 MB together", () -> {
            // review 687edfd: decodeWhole allowed a 16 MP whole bitmap (64 MB) plus the cut (up to 39 MB):
            // ~103 MB on a 2 GB, 32-bit stick, while AndroidPhotoProcessor's doc said "about 60 MB".
            double[][] aspects = {{1, 1}, {4, 3}, {3, 2}, {16, 9}, {21, 9}, {3, 1}, {9, 16}, {3, 4}, {1, 3}};
            double[] megapixels = {0.01, 2.5, 5, 8, 9.6, 12, 16, 24, 33, 48, 60};
            int cases = 0;
            for (double[] ar : aspects) {
                for (double mp : megapixels) {
                    int w = (int) Math.sqrt(mp * 1e6 * ar[0] / ar[1]), h = (int) (w * ar[1] / ar[0]);
                    if ((long) w * h > PhotoMath.MAX_PIXELS || w < PhotoMath.MIN_SIDE || h < PhotoMath.MIN_SIDE) continue;
                    for (int orientation : new int[]{1, 6}) {
                        boolean t = PhotoMath.transposed(orientation);
                        int[] c = cut(w, h, orientation, null);
                        int sample = sampleFor(w, h, orientation, null);
                        int s = PhotoMath.wholeSample(w, h, sample);
                        String what = w + "x" + h + " orientation " + orientation;
                        check(s >= sample && Integer.bitCount(s) == 1, what + ": sample " + s + " from " + sample);
                        long all = (long) ((w + s - 1) / s) * ((h + s - 1) / s) * 4, cut = (long) ((c[2] + s - 1) / s) * ((c[3] + s - 1) / s) * 4;
                        check(all + cut <= 64_000_000L, what + ": whole " + all / 1_000_000 + " MB + cut " + cut / 1_000_000 + " MB");
                        if ((long) ((w + sample - 1) / sample) * ((h + sample - 1) / sample) <= PhotoMath.WHOLE_MAX_PIXELS) {
                            eq(s, sample, what + ": not sampled further when it already fits");
                        }
                        cases++;
                    }
                }
            }
            check(cases > 150, "cases: " + cases);
            eq(PhotoMath.wholeSample(4000, 3000, 2), 2, "12 MP 4:3: 2000x1500 already fits");
            eq(PhotoMath.wholeSample(7600, 5067, 2), 4, "38 MP 3:2: 3800x2534 (9.6 MP) is sampled once more");
            eq(PhotoMath.wholeSample(4308, 7660, 2), 4, "33 MP 9:16 portrait: 2154x3830 (8.2 MP) is sampled once more");
        });
        run("photomath wholeCut: the crop scaled to the whole decode stays inside it (createBitmap cannot throw)", () -> {
            // review 7c2b1e5: x rounded to the bitmap's width at the right edge (decodeWhole threw)
            eq(Arrays.toString(PhotoMath.wholeCut(1000, 750, 4000, 3000, new int[]{1000, 600, 2000, 1500})), "[250, 150, 500, 375]", "scaled");
            eq(Arrays.toString(PhotoMath.wholeCut(3, 3, 7, 7, new int[]{6, 6, 1, 1})), "[2, 2, 1, 1]", "x and y would round to 3 = the width");
            eq(Arrays.toString(PhotoMath.wholeCut(2, 2, 3, 3, new int[]{2, 2, 1, 1})), "[1, 1, 1, 1]", "last pixel, rounding up");
            java.util.Random rnd = new java.util.Random(11);
            for (int k = 0; k < 20000; k++) {
                int w = 1 + rnd.nextInt(9000), h = 1 + rnd.nextInt(9000), s = 1 << rnd.nextInt(7);
                int aw = Math.max(1, (w + s - 1) / s), ah = Math.max(1, (h + s - 1) / s);
                int x = rnd.nextInt(w), y = rnd.nextInt(h), cw = 1 + rnd.nextInt(w - x), ch = 1 + rnd.nextInt(h - y);
                int[] r = PhotoMath.wholeCut(aw, ah, w, h, new int[]{x, y, cw, ch});
                check(r[0] >= 0 && r[1] >= 0 && r[2] >= 1 && r[3] >= 1 && r[0] + r[2] <= aw && r[1] + r[3] <= ah,
                        w + "x" + h + " /" + s + " crop " + x + "," + y + " " + cw + "x" + ch + " -> " + Arrays.toString(r) + " in " + aw + "x" + ah);
            }
        });
        run("photomath sample size: largest power of two still covering what is drawn", () -> {
            eq(PhotoMath.sampleSize(4000, 2667, 1920, 1280), 2, "12 MP 4:3");
            eq(PhotoMath.sampleSize(3840, 2560, 1920, 1280), 2, "exactly 2x");
            eq(PhotoMath.sampleSize(3839, 2560, 1920, 1280), 1, "just under 2x");
            eq(PhotoMath.sampleSize(9486, 6324, 1920, 1280), 4, "60 MP 3:2");
            eq(PhotoMath.sampleSize(800, 533, 1920, 1280), 1, "small: upscaled, never 0");
            eq(PhotoMath.sampleSize(1 << 20, 1 << 20, 1, 1), 64, "capped at 64");
            eq(PhotoMath.sampleSize(2000, 3000, 1280, 1920), 1, "transposed target");
            eq(PhotoMath.sampleSize(4000, 1500, 1920, 1280), 1, "the height alone stops it");
            eq(PhotoMath.sampleSize(1500, 4000, 1280, 1920), 1, "the width alone stops it");
            // the decoded region never exceeds 2x the wall on either side, whatever the photo and crop
            PhotoMath.Crop[] crops = {null, new PhotoMath.Crop(.2, .8, 1.5), new PhotoMath.Crop(1, 0, 4), new PhotoMath.Crop(.5, .5, .01)};
            for (int w = 64; w < 40000; w = w * 3 / 2 + 7) {
                for (int h = 64; h < 40000; h = h * 5 / 3 + 11) {
                    if ((long) w * h > PhotoMath.MAX_PIXELS) continue;
                    for (PhotoMath.Crop c : crops) {
                        for (int o : new int[]{1, 6}) {
                            int[] cr = cut(w, h, o, c);
                            int sm = sampleFor(w, h, o, c);
                            check((long) ((cr[2] + sm - 1) / sm) * ((cr[3] + sm - 1) / sm) <= 3840L * 2160 + 4 * 3840,
                                    w + "x" + h + " decodes " + cr[2] / sm + "x" + cr[3] / sm);
                        }
                    }
                }
            }
        });
        run("photomath orientation matrices map corners like exif_transpose", () -> {
            int w = 40, h = 30; // stored; corners TL TR BL BR -> displayed
            String[] want = {
                    null,
                    "0,0 40,0 0,30 40,30",   // 1 normal
                    "40,0 0,0 40,30 0,30",   // 2 mirror
                    "40,30 0,30 40,0 0,0",   // 3 rotate 180
                    "0,30 40,30 0,0 40,0",   // 4 flip
                    "0,0 0,40 30,0 30,40",   // 5 transpose
                    "30,0 30,40 0,0 0,40",   // 6 rotate 90 clockwise
                    "30,40 30,0 0,40 0,0",   // 7 transverse
                    "0,40 0,0 30,40 30,0",   // 8 rotate 90 anticlockwise
            };
            for (int o = 1; o <= 8; o++) {
                float[] m = PhotoMath.orient(o, w, h);
                eq(at(m, 0, 0) + " " + at(m, w, 0) + " " + at(m, 0, h) + " " + at(m, w, h), want[o], "orientation " + o);
                eq(PhotoMath.transposed(o), o >= 5, "transposed " + o);
            }
            eq(Arrays.toString(PhotoMath.orient(0, w, h)), Arrays.toString(PhotoMath.orient(1, w, h)), "unknown = normal");
            eq(Arrays.toString(PhotoMath.orient(9, w, h)), Arrays.toString(PhotoMath.orient(1, w, h)), "9 = normal");
        });
        run("photomath draw matrix fills the wall upright", () -> {
            for (int o = 1; o <= 8; o++) {
                boolean t = o >= 5;
                int w = t ? 1334 : 2000, h = t ? 2000 : 1334; // a decoded region, stored frame
                float[] m = PhotoMath.drawMatrix(o, w, h, 1920, 1280);
                eq(m.length, 9, "3x3");
                eq(m[6] + "," + m[7] + "," + m[8], "0.0,0.0,1.0", "affine");
                String box = "";
                int minX = 99999, minY = 99999, maxX = -1, maxY = -1;
                for (float[] p : new float[][]{{0, 0}, {w, 0}, {0, h}, {w, h}}) {
                    float[] q = PhotoMath.apply(m, p[0], p[1]);
                    minX = Math.min(minX, Math.round(q[0]));
                    maxX = Math.max(maxX, Math.round(q[0]));
                    minY = Math.min(minY, Math.round(q[1]));
                    maxY = Math.max(maxY, Math.round(q[1]));
                }
                box = minX + "," + minY + "," + maxX + "," + maxY;
                eq(box, "0,0,1920,1280", "orientation " + o + " covers the wall");
            }
            // the stored top-left pixel of a photo shown rotated 90 clockwise lands top-right
            eq(at(PhotoMath.drawMatrix(6, 1334, 2000, 1920, 1280), 0, 0), "1920,0", "6: TL -> TR");
            eq(at(PhotoMath.drawMatrix(8, 1334, 2000, 1920, 1280), 0, 0), "0,1280", "8: TL -> BL");
        });
        run("photomath meta.json carries the crop (rounded) and edited; metaCrop reads it back", () -> {
            Readability.Fields f = new Readability.Fields("dark", "light", "mist", .12, 0, false, false);
            String plain = new String(PhotoMath.metaJson("pabcdefghij", "A", 1, f), StandardCharsets.UTF_8);
            check(!plain.contains("crop") && !plain.contains("edited"), "none by default: " + plain);
            byte[] m = PhotoMath.metaJson("pabcdefghij", "A", 1, f, new PhotoMath.Crop(1.0 / 3, .5, 1.23456789), 1761900900123L);
            @SuppressWarnings("unchecked") Map<String, Object> o = (Map<String, Object>) UserLibrary.Json.parse(new String(m, StandardCharsets.UTF_8));
            check(UserLibrary.validMeta(o, "pabcdefghij"), "still valid meta.json");
            eq(o.get("edited"), 1761900900123L, "edited");
            PhotoMath.Crop c = PhotoMath.metaCrop(o);
            eq(c.x + "," + c.y + "," + c.z, "0.333333,0.5,1.234568", "rounded to 6 places");
            check(PhotoMath.metaCrop(null) == null, "no meta");
            o.put("crop", "0.5,0.5,1");
            check(PhotoMath.metaCrop(o) == null, "malformed crop: none");
        });
        run("photomath Jpeg.strip cuts EXIF/GPS, XMP, IPTC and comments, every other byte kept", () -> {
            byte[] jpg = UploadTests.jpeg(64, 48);
            byte[] dirty = plant(plant(plant(jpg, segment(0xe1, "Exif\0\0GPS 51.5N")), segment(0xfe, "a comment")), segment(0xed, "Photoshop 3.0\0IPTC"));
            check(!Jpeg.parse(dirty, dirty.length).metadata.isEmpty(), "planted");
            byte[] clean = Jpeg.strip(dirty, dirty.length);
            eq(Jpeg.parse(clean, clean.length).metadata.toString(), "[]", "nothing left");
            eq(Arrays.equals(clean, jpg), true, "the original bytes exactly");
            eq(Arrays.equals(Jpeg.strip(jpg, jpg.length), jpg), true, "a clean JPEG is unchanged");
            check(!new String(clean, StandardCharsets.ISO_8859_1).contains("GPS"), "no GPS string");
            check(Jpeg.strip("not a jpeg".getBytes(StandardCharsets.UTF_8), 10) == null, "not a JPEG: null");
        });
        run("photomath sniff: JPEG, PNG, WebP, HEIC only", () -> {
            byte[] jpg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0, 0, 0, 0, 0, 0, 0};
            byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};
            byte[] webp = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'};
            byte[] heic = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
            byte[] mif1 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'i', 'f', '1'};
            byte[] avif = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f'};
            byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0};
            byte[] riffWav = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'};
            eq(PhotoMath.sniff(jpg, 12), "jpeg", "jpeg");
            eq(PhotoMath.sniff(png, 12), "png", "png");
            eq(PhotoMath.sniff(webp, 12), "webp", "webp");
            eq(PhotoMath.sniff(heic, 12), "heic", "heic");
            eq(PhotoMath.sniff(mif1, 12), "heic", "mif1");
            eq(PhotoMath.sniff(avif, 12), null, "avif");
            eq(PhotoMath.sniff(gif, 12), null, "gif");
            eq(PhotoMath.sniff(riffWav, 12), null, "wav");
            eq(PhotoMath.sniff(jpg, 2), null, "too short");
            eq(PhotoMath.sniff(png, 7), null, "png too short");
        });
        run("photomath unreadable: a HEIC the stick cannot decode says HEIC (415), anything else is broken", () -> {
            PhotoProcessor.Refused h = PhotoMath.unreadable("heic");
            eq(h.code, "type", "heic code");
            eq(h.getMessage(), PhotoProcessor.HEIC, "heic message");
            check(h.getMessage().contains("HEIC") && h.getMessage().contains("JPEG"), "names HEIC and what to send instead");
            for (String t : new String[]{"jpeg", "png", "webp", null}) {
                PhotoProcessor.Refused r = PhotoMath.unreadable(t);
                eq(r.code, "broken", t + " code");
                eq(r.getMessage(), PhotoProcessor.BROKEN, t + " message");
            }
        });
        run("photomath size limits: 60 MP, 64 px", () -> {
            eq(PhotoMath.sizeProblem(9486, 6324), null, "59.99 MP");
            eq(PhotoMath.sizeProblem(10000, 6001), "pixels", "60.01 MP");
            eq(PhotoMath.sizeProblem(60000, 1000), null, "60 MP exactly");
            eq(PhotoMath.sizeProblem(64, 64), null, "64 px");
            eq(PhotoMath.sizeProblem(63, 4000), "small", "63 px");
            eq(PhotoMath.sizeProblem(1L << 31, 1L << 31), "pixels", "no overflow");
        });
        run("photomath display names (v1's photo_name)", () -> {
            eq(PhotoMath.photoName("IMG_0042.JPG"), "IMG_0042", "extension");
            eq(PhotoMath.photoName("a b.jpg"), "a b", "space");
            eq(PhotoMath.photoName("  beach \t at\n\nnoon .jpeg"), "beach at noon", "white space collapsed");
            eq(PhotoMath.photoName("C:\\Users\\m\\holiday.png"), "holiday", "windows path");
            eq(PhotoMath.photoName("../../etc/passwd"), "passwd", "unix path");
            eq(PhotoMath.photoName(".jpg"), "Photo", "only an extension");
            eq(PhotoMath.photoName(""), "Photo", "empty");
            eq(PhotoMath.photoName(null), "Photo", "none");
            eq(PhotoMath.photoName("x\u0000y\u0007z\u202e.jpg"), "xyz", "control and format characters");
            eq(PhotoMath.photoName("a\u2028b.jpg"), "a b", "line separator is a space");
            eq(PhotoMath.photoName("café ☕.jpg"), "café ☕", "unicode kept");
            String longName = new String(new char[199]).replace('\0', 'a') + "\ud83d\ude00\ud83d\ude00.jpg";
            String n = PhotoMath.photoName(longName);
            eq(n.length(), 199, "cut before half an emoji (200 units)");
            check(!Character.isHighSurrogate(n.charAt(n.length() - 1)), "no lone surrogate");
            String meta = new String(PhotoMath.metaJson("pabcdefghij", n, 1, new Readability.Fields("dark", "light", "mist", .12, 0, false, false)),
                    StandardCharsets.UTF_8);
            check(UserLibrary.validMeta(UploadTests.obj(meta), "pabcdefghij"), "a cut name is still a valid meta.json name");
            eq(PhotoMath.photoName(new String(new char[300]).replace('\0', 'b')).length(), 200, "200 units");
        });
        run("photomath meta.json is what UserLibrary accepts", () -> {
            byte[] m = PhotoMath.metaJson("pabcdefghij", "Beach \"1\"", 1761900900000L,
                    new Readability.Fields("light", "mist", "dark", .44, 3, true, true));
            String s = new String(m, StandardCharsets.UTF_8);
            eq(s, "{\"v\":1,\"id\":\"pabcdefghij\",\"name\":\"Beach \\\"1\\\"\",\"added\":1761900900000,\"tone\":\"light\","
                    + "\"ut\":{\"tr\":\"mist\",\"br\":\"dark\"},\"frostVeil\":0.44,\"listColumn\":3,\"dockQuiet\":true,\"clockMist\":true}", "json");
            Map<String, Object> o = UploadTests.obj(s);
            check(UserLibrary.validMeta(o, "pabcdefghij"), "validMeta");
            check(!UserLibrary.validMeta(o, "potherphoto"), "wrong id refused");
        });
        run("jpeg parse: size and the metadata a phone photo carries", () -> {
            byte[] j = UploadTests.jpeg(320, 180);
            Jpeg.Info i = Jpeg.parse(j, j.length);
            check(i != null, "ImageIO JPEG parses");
            eq(i.width + "x" + i.height, "320x180", "size");
            eq(i.metadata, Collections.emptyList(), "JFIF only");
            String[][] planted = {
                    {"e1", "Exif\0\0MM\0*PLANTED-GPS-9c1e", "APP1"},
                    {"e1", "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>PLANTED-XMP</x:xmpmeta>", "APP1"},
                    {"ed", "Photoshop 3.0\08BIM PLANTED-IPTC", "APP13"},
                    {"fe", "PLANTED-COM-e2b4", "COM"},
                    {"e0", "NOTJFIF\0", "APP0"},
                    {"e2", "MPF\0\0\0", "APP2"},
                    {"eb", "JP\0\0", "APP11"},
            };
            for (String[] p : planted) {
                byte[] b = plant(j, segment(Integer.parseInt(p[0], 16), p[1]));
                Jpeg.Info x = Jpeg.parse(b, b.length);
                check(x != null, "parses with " + p[2]);
                eq(x.metadata, Collections.singletonList(p[2]), "found " + p[1].substring(0, 4));
            }
            byte[] icc = plant(j, segment(0xe2, "ICC_PROFILE\0\1\1sRGB"));
            eq(Jpeg.parse(icc, icc.length).metadata, Collections.emptyList(), "an ICC profile is colour, not metadata");
            byte[] fill = plant(j, new byte[]{(byte) 0xff, (byte) 0xff});
            check(Jpeg.parse(fill, fill.length) != null && Jpeg.parse(fill, fill.length).width == 320, "fill bytes before a marker");
        });
        run("jpeg: Adobe's APP14 says how to read the colours: kept by strip, not metadata; anything else in APP14 is", () -> {
            byte[] j = UploadTests.jpeg(64, 48);
            String transform = "Adobe\0\144\0\0\0\0\2"; // the 12 bytes: name, version, two flags, YCCK
            byte[] seg = segment(0xee, transform);
            byte[] adobe = plant(j, seg);
            eq(Jpeg.parse(adobe, adobe.length).metadata, Collections.emptyList(), "not metadata");
            byte[] exif = plant(adobe, segment(0xe1, "Exif\0\0GPS 51.5N"));
            eq(Jpeg.parse(exif, exif.length).metadata, Collections.singletonList("APP1"), "the rest still is");
            byte[] kept = Jpeg.strip(exif, exif.length);
            check(Arrays.equals(kept, adobe), "strip takes the EXIF and keeps every byte of the APP14");
            for (String bad : new String[]{"Adobe\0\144\0\0\0\0\2HIDDEN-GPS-9c1e", "Adobe", "NotAdobe\0\0\0\0", "Picasa"}) {
                byte[] b = plant(j, segment(0xee, bad));
                eq(Jpeg.parse(b, b.length).metadata, Collections.singletonList("APP14"), "listed: " + bad);
                check(Arrays.equals(Jpeg.strip(b, b.length), j), "stripped: " + bad);
            }
        });
        run("jpeg: metadata between the scans of a progressive JPEG is found and stripped (G7)", () -> {
            byte[] j = UploadTests.progressiveJpeg(320, 180);
            int sos2 = -1, seen = 0;
            for (int k = 0; k + 1 < j.length; k++) {
                if ((j[k] & 0xff) == 0xff && (j[k + 1] & 0xff) == 0xda && ++seen == 2) {
                    sos2 = k;
                    break;
                }
            }
            check(sos2 > 0, "ImageIO wrote a progressive JPEG (a second SOS)");
            Jpeg.Info plain = Jpeg.parse(j, j.length);
            check(plain != null && plain.width == 320 && plain.metadata.isEmpty(), "the progressive JPEG parses, clean: " + (plain == null ? null : plain.metadata));
            eq(Arrays.equals(Jpeg.strip(j, j.length), j), true, "a clean progressive JPEG is unchanged by strip");
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(j, 0, sos2);
            byte[] exif = segment(0xe1, "Exif\0\0MM\0*LATE-GPS-51.5N"), com = segment(0xfe, "LATE-COMMENT");
            o.write(exif, 0, exif.length);
            o.write(com, 0, com.length);
            o.write(j, sos2, j.length - sos2);
            byte[] dirty = o.toByteArray();
            Jpeg.Info d = Jpeg.parse(dirty, dirty.length);
            check(d != null, "still a JPEG");
            eq(d.metadata, Arrays.asList("APP1", "COM"), "the segments after the first scan are found");
            byte[] clean = Jpeg.strip(dirty, dirty.length);
            check(clean != null, "strip accepts it");
            eq(Arrays.equals(clean, j), true, "stripped back to the original bytes exactly");
            String text = new String(clean, StandardCharsets.ISO_8859_1);
            check(!text.contains("LATE-GPS") && !text.contains("LATE-COMMENT"), "no late metadata survives strip");
            byte[] tail = Arrays.copyOf(j, j.length + 9);
            System.arraycopy("SEFT-TAIL".getBytes(StandardCharsets.ISO_8859_1), 0, tail, j.length, 9);
            eq(Jpeg.parse(tail, tail.length).metadata, Collections.singletonList("trailer"), "bytes after EOI are listed");
            eq(Arrays.equals(Jpeg.strip(tail, tail.length), j), true, "and cut by strip");
        });
        run("jpeg: entropy data with 0xFF00 stuffing and RSTn, then a scan's COM, walked to EOI (G7)", () -> {
            // SOI, SOF0 8x8 one component, SOS, data with FF00 and RST0, COM, a 2nd SOS, data, EOI
            byte[] head = {(byte) 0xff, (byte) 0xd8,
                    (byte) 0xff, (byte) 0xc0, 0, 11, 8, 0, 8, 0, 8, 1, 1, 0x11, 0,
                    (byte) 0xff, (byte) 0xda, 0, 8, 1, 1, 0, 0, 0x3f, 0,
                    0x12, (byte) 0xff, 0, 0x34, (byte) 0xff, (byte) 0xd0, 0x56};
            byte[] com = segment(0xfe, "IN-SCAN-COMMENT");
            byte[] rest = {(byte) 0xff, (byte) 0xda, 0, 8, 1, 1, 0, 0, 0x3f, 0, 0x78, (byte) 0xff, 0, (byte) 0xff, (byte) 0xd9};
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(head, 0, head.length);
            o.write(com, 0, com.length);
            o.write(rest, 0, rest.length);
            byte[] b = o.toByteArray();
            Jpeg.Info i = Jpeg.parse(b, b.length);
            check(i != null && i.width == 8 && i.height == 8, "parses");
            eq(i.metadata, Collections.singletonList("COM"), "the COM after the first scan");
            ByteArrayOutputStream want = new ByteArrayOutputStream();
            want.write(head, 0, head.length);
            want.write(rest, 0, rest.length);
            eq(Arrays.equals(Jpeg.strip(b, b.length), want.toByteArray()), true, "strip drops it, keeps the stuffed and restart bytes");
            byte[] cut = Arrays.copyOf(b, b.length - 2);
            check(Jpeg.parse(cut, cut.length) == null && Jpeg.strip(cut, cut.length) == null, "no EOI after the last scan: refused");
        });
        run("jpeg: fill bytes before RSTn inside a scan are legal (T.81 B.1.1.2), baseline and progressive", () -> {
            // review 7c2b1e5: 0xFF fill bytes before a restart marker were taken for the end of the scan,
            // then the next entropy byte refused a valid upload
            for (boolean progressive : new boolean[]{false, true}) {
                String kind = progressive ? "progressive" : "baseline";
                byte[] j = UploadTests.restartJpeg(160, 96, progressive);
                check(UploadTests.restarts(j) > 4, kind + ": ImageIO wrote restart markers");
                for (int fill : new int[]{1, 3}) {
                    byte[] padded = UploadTests.padRestarts(j, fill);
                    check(padded.length > j.length, kind + ": padded");
                    java.awt.image.BufferedImage plainImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(j)),
                            paddedImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(padded));
                    check(paddedImg != null && plainImg.getRGB(37, 51) == paddedImg.getRGB(37, 51) && plainImg.getRGB(159, 95) == paddedImg.getRGB(159, 95),
                            kind + ": a real decoder reads the padded file as the same image");
                    Jpeg.Info i = Jpeg.parse(padded, padded.length);
                    check(i != null, kind + " with " + fill + " fill byte(s) before every RSTn parses");
                    eq(i.width + "x" + i.height + " " + i.metadata, "160x96 []", kind + " size, clean");
                    eq(Arrays.equals(Jpeg.strip(padded, padded.length), padded), true, kind + ": strip keeps every byte");
                }
            }
            // by hand: data, fill, RST0, data again (the byte the old walk refused), EOI
            byte[] b = {(byte) 0xff, (byte) 0xd8,
                    (byte) 0xff, (byte) 0xc0, 0, 11, 8, 0, 8, 0, 8, 1, 1, 0x11, 0,
                    (byte) 0xff, (byte) 0xda, 0, 8, 1, 1, 0, 0, 0x3f, 0,
                    0x12, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xd0, 0x56, (byte) 0xff, (byte) 0xff, (byte) 0xd9};
            Jpeg.Info i = Jpeg.parse(b, b.length);
            check(i != null && i.width == 8, "fill bytes, RST0, more data, fill bytes, EOI");
            byte[] noEnd = Arrays.copyOf(b, b.length - 3); // ... RST0, data, then nothing
            check(Jpeg.parse(noEnd, noEnd.length) == null, "still refused without an EOI");
            byte[] fillsOnly = Arrays.copyOf(b, 29); // ... data, fill bytes to the end
            check(Jpeg.parse(fillsOnly, fillsOnly.length) == null, "fill bytes to the end: refused");
        });
        run("jpeg: every real JPEG in web/assets (camera and editor output) parses and strips", () -> {
            List<Path> all = new ArrayList<>();
            try (java.util.stream.Stream<Path> s = Files.walk(Paths.get("web/assets"))) {
                s.filter(p -> p.toString().endsWith(".jpg")).sorted().forEach(all::add);
            }
            check(all.size() > 100, "the wallpapers and materials: " + all.size());
            int exif = 0;
            for (Path p : all) {
                byte[] b = Files.readAllBytes(p);
                Jpeg.Info i = Jpeg.parse(b, b.length);
                check(i != null && i.width > 0 && i.height > 0, p + " parses");
                if (i.metadata.contains("APP1")) exif++;
                byte[] clean = Jpeg.strip(b, b.length);
                check(clean != null && Jpeg.parse(clean, clean.length).metadata.isEmpty(), p + " strips clean");
            }
            check(exif > 0, "some carry a camera's EXIF: " + exif);
        });
        run("jpeg parse refuses what is not a whole JPEG", () -> {
            byte[] j = UploadTests.jpeg(64, 48);
            check(Jpeg.parse(Arrays.copyOf(j, j.length - 1), j.length - 1) == null, "no EOI (truncated)");
            check(Jpeg.parse(j, 3) == null, "3 bytes");
            check(Jpeg.parse(null, 0) == null, "null");
            check(Jpeg.parse(j, j.length + 1) == null, "n past the array");
            byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, (byte) 0xff, (byte) 0xd9};
            check(Jpeg.parse(png, png.length) == null, "a PNG");
            byte[] noSof = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xda, 0, 2, (byte) 0xff, (byte) 0xd9};
            check(Jpeg.parse(noSof, noSof.length) == null, "SOS without a frame");
            byte[] badLen = j.clone();
            badLen[4] = (byte) 0xff; // APP0 length past the end
            check(Jpeg.parse(badLen, badLen.length) == null, "segment length past the end");
        });
        run("pickAddress: the LAN IPv4 only", () -> {
            InetAddress wifi = InetAddress.getByName("192.168.0.55"), eth = InetAddress.getByName("10.0.0.7"),
                    pub = InetAddress.getByName("81.2.69.160"), ll = InetAddress.getByName("169.254.3.4"),
                    lo = InetAddress.getByName("127.0.0.1"), v6 = InetAddress.getByName("fe80::1"),
                    v6g = InetAddress.getByName("2001:db8::5"), p2p = InetAddress.getByName("192.168.49.1"),
                    vpn = InetAddress.getByName("10.8.0.2"), any = InetAddress.getByName("0.0.0.0");
            List<UploadServer.Iface> l = Arrays.asList(
                    new UploadServer.Iface("lo", true, true, Arrays.asList(lo)),
                    new UploadServer.Iface("tun0", true, false, Arrays.asList(vpn)),
                    new UploadServer.Iface("p2p0", true, false, Arrays.asList(p2p)),
                    new UploadServer.Iface("wlan0", true, false, Arrays.asList(v6, v6g, ll, wifi)));
            eq(UploadServer.pickAddress(l), wifi, "wlan0's private IPv4");
            eq(UploadServer.pickAddress(Arrays.asList(new UploadServer.Iface("wlan0", false, false, Arrays.asList(wifi)))), null, "down");
            eq(UploadServer.pickAddress(Arrays.asList(new UploadServer.Iface("wlan0", true, false, Arrays.asList(v6, v6g, ll, any)))), null, "no usable IPv4");
            eq(UploadServer.pickAddress(Arrays.asList(new UploadServer.Iface("eth0", true, false, Arrays.asList(pub)),
                    new UploadServer.Iface("wlan0", true, false, Arrays.asList(wifi)))), wifi, "private beats public");
            eq(UploadServer.pickAddress(Arrays.asList(new UploadServer.Iface("usb0", true, false, Arrays.asList(eth)),
                    new UploadServer.Iface("eth0", true, false, Arrays.asList(wifi)))), wifi, "eth beats other names");
            eq(UploadServer.pickAddress(Arrays.asList(new UploadServer.Iface("wlan0", true, true, Arrays.asList(wifi)))), null, "loopback iface");
            eq(UploadServer.pickAddress(Collections.emptyList()), null, "none");
            eq(UploadServer.baseUrl(wifi), "http://192.168.0.55:8097", "base");
            eq(UserLibrary.validUploadBase(UploadServer.baseUrl(wifi)), "http://192.168.0.55:8097", "UserLibrary accepts it");
            eq(UploadServer.PORT, 8097, "port");
        });
    }
}
