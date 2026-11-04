package home.cleanframe.tv;

import java.nio.charset.StandardCharsets;

/**
 * The pixel arithmetic and pure rules of preparing an uploaded photo (docs/PHOTO-GROUPS.md,
 * contract 4), in plain Java so tests/java/PhotoMathTest.java checks them offline; the Android
 * processor (AndroidPhotoProcessor) only feeds BitmapFactory, BitmapRegionDecoder and Matrix from
 * these answers.
 *
 * Memory plan: the type and pixel count come from the header alone; the frame (the crop the user
 * chose, else the centred 16:9) is computed in the photo's displayed (EXIF-oriented) frame and mapped
 * back to the stored frame, and only the part of the photo inside it is decoded, with the largest
 * power-of-two inSampleSize that keeps it at least the size it is drawn at. So the decoded bitmap is
 * under twice the wall on each side (33 MB ARGB) whatever the photo's size, shape or crop.
 *
 * The crop (2026-11-02: "chose position and zoom in and out"): {x, y, z} in the displayed
 * picture. z = 1 is the largest 16:9 frame inside the picture (the old centre crop), z &gt; 1 zooms in
 * (up to Z_MAX), z &lt; 1 zooms out down to the whole picture (fitZoom), the rest of the wall black;
 * x, y are the frame's centre as fractions of the picture, kept so the frame stays on it (centred on a
 * side where the frame is larger). The phone page and the TV's Adjust screen do the same arithmetic in
 * JavaScript (web/photo-crop.js, served to the phone as /static/crop.js); tests/upload/data/
 * crop-vectors.json holds the answers all three must give.
 */
final class PhotoMath {
    /** The wall is the screen's 16:9 (until 2026-11-02 1920x1280, of which the screen showed the middle 1080). */
    static final int WALL_W = 1920, WALL_H = 1080, THUMB_W = 512, THUMB_H = 288;
    /**
     * src.jpg, the upright copy kept beside a photo for Adjust: the phone's copy as sent (metadata
     * removed) when it is a JPEG of at most SRC_MAX_SIDE and SRC_MAX_PIXELS shown as stored, else one
     * made here of at most SRC_MADE_PIXELS (memory: it and its upright copy are held at once). None is
     * kept when the stick has less than SRC_MIN_FREE bytes free.
     */
    static final int SRC_MAX_SIDE = 4096, SRC_QUALITY = 90;
    static final long SRC_MAX_PIXELS = 4096L * 3072, SRC_MAX_BYTES = 16L * 1024 * 1024, SRC_MADE_PIXELS = 4_000_000L,
            SRC_MIN_FREE = 500L * 1024 * 1024;
    static final double Z_MAX = 4;
    static final int WALL_QUALITY = 87, THUMB_QUALITY = 80;
    static final long MAX_PIXELS = 60_000_000L;
    static final int MIN_SIDE = 64;

    private PhotoMath() {
    }

    /** "jpeg", "png", "webp", "heic" from the first bytes (at least 12), or null for anything else. */
    static String sniff(byte[] h, int n) {
        if (n >= 3 && (h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xd8 && (h[2] & 0xff) == 0xff) return "jpeg";
        if (n >= 8 && (h[0] & 0xff) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G' && h[4] == 0x0d && h[5] == 0x0a
                && h[6] == 0x1a && h[7] == 0x0a) return "png";
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B'
                && h[11] == 'P') return "webp";
        if (n >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            String brand = new String(h, 8, 4, StandardCharsets.ISO_8859_1);
            switch (brand) {
                case "heic": case "heix": case "hevc": case "hevx": case "heim": case "heis": case "mif1": case "msf1":
                    return "heic";
                default:
                    return null;
            }
        }
        return null;
    }

    /**
     * The refusal for a photo of this type (from sniff) that the platform could not decode: a HEIC gets
     * the HEIC message (code "type", so 415 on the phone), anything else is "broken".
     */
    static PhotoProcessor.Refused unreadable(String type) {
        return "heic".equals(type) ? new PhotoProcessor.Refused("type", PhotoProcessor.HEIC)
                : new PhotoProcessor.Refused("broken", PhotoProcessor.BROKEN);
    }

    /** EXIF orientations 5-8 swap width and height. */
    static boolean transposed(int orientation) {
        return orientation >= 5 && orientation <= 8;
    }

    /**
     * The centred crop of a w x h picture with the aspect tw:th, as large as fits: {x, y, cw, ch}.
     * (Pillow's ImageOps.fit with centering 0.5, to the nearest pixel.)
     */
    static int[] coverCrop(int w, int h, int tw, int th) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("empty picture");
        long cw, ch;
        if ((long) w * th >= (long) h * tw) { // wider than tw:th (or equal): full height
            ch = h;
            cw = Math.min(w, Math.max(1, Math.round((double) h * tw / th)));
        } else {
            cw = w;
            ch = Math.min(h, Math.max(1, Math.round((double) w * th / tw)));
        }
        return new int[]{(int) ((w - cw) / 2), (int) ((h - ch) / 2), (int) cw, (int) ch};
    }

    /** A crop: the frame's centre (x, y, fractions of the displayed picture) and its zoom z. */
    static final class Crop {
        final double x, y, z;

        Crop(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static final Crop CENTRE = new Crop(.5, .5, 1);
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("[0-9]{1,6}(\\.[0-9]{1,12})?");

    /** The crop field "x,y,z" (three plain decimals, as the phone page and the TV page send it), or null. */
    static Crop parseCrop(String s) {
        if (s == null || s.length() > 64) return null;
        String[] p = s.trim().split(",", -1);
        if (p.length != 3) return null;
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) {
            if (!NUMBER.matcher(p[i]).matches()) return null;
            v[i] = Double.parseDouble(p[i]);
        }
        return v[2] > 0 ? new Crop(v[0], v[1], v[2]) : null;
    }

    /** The 16:9 frame at zoom 1 of a W x H picture, {w, h}: the largest inside it (exact, not rounded). */
    static double[] cover169(int w, int h) {
        return (long) w * WALL_H >= (long) h * WALL_W ? new double[]{(double) h * WALL_W / WALL_H, h}
                : new double[]{w, (double) w * WALL_H / WALL_W};
    }

    /** The zoom at which the frame holds the whole picture (1 for an exactly 16:9 one). */
    static double fitZoom(int w, int h) {
        double[] c = cover169(w, h);
        return Math.min(c[0] / w, c[1] / h);
    }

    /** c (null: the centre) made valid for a W x H picture: z within [fitZoom, Z_MAX], the frame on the picture. */
    static Crop clamp(int w, int h, Crop c) {
        if (c == null) c = CENTRE;
        double[] c0 = cover169(w, h);
        double z = Double.isNaN(c.z) ? 1 : Math.max(fitZoom(w, h), Math.min(Z_MAX, c.z));
        return new Crop(axis(c.x, c0[0] / z / w), axis(c.y, c0[1] / z / h), z);
    }

    /** A centre along one side, the frame being span of it: kept so the frame stays on the picture. */
    private static double axis(double v, double span) {
        if (span >= 1 || Double.isNaN(v)) return .5;
        return Math.max(span / 2, Math.min(1 - span / 2, v));
    }

    /** {fx, fy, fw, fh}: the frame of c (clamped) in the displayed picture's pixels. */
    static double[] frame(int w, int h, Crop c) {
        Crop k = clamp(w, h, c);
        double[] c0 = cover169(w, h);
        double fw = c0[0] / k.z, fh = c0[1] / k.z;
        return new double[]{k.x * w - fw / 2, k.y * h - fh / 2, fw, fh};
    }

    /**
     * Where a W x H (displayed) picture goes on the WALL_W x WALL_H wall for crop c: {sx, sy, sw, sh},
     * the whole pixels of the picture inside the frame, then {dx, dy, dw, dh}, where they are drawn. The
     * rest of the wall (zoomed out past the picture) stays black.
     */
    static double[] place(int w, int h, Crop c) {
        double[] f = frame(w, h, c);
        double k = WALL_W / f[2];
        int sx = (int) Math.max(0, Math.floor(f[0] + 1e-6)), sy = (int) Math.max(0, Math.floor(f[1] + 1e-6));
        int ex = (int) Math.min(w, Math.ceil(f[0] + f[2] - 1e-6)), ey = (int) Math.min(h, Math.ceil(f[1] + f[3] - 1e-6));
        int sw = Math.max(1, ex - sx), sh = Math.max(1, ey - sy);
        return new double[]{sx, sy, sw, sh, (sx - f[0]) * k, (sy - f[1]) * k, sw * k, sh * k};
    }

    /**
     * The rect {x, y, w, h} of the displayed picture, in the STORED frame of a rw x rh photo with this
     * EXIF orientation (orient()'s map is a signed permutation, so its inverse is its transpose).
     */
    static int[] toStored(int x, int y, int w, int h, int rw, int rh, int orientation) {
        float[] m = orient(orientation, rw, rh);
        double ax = x - m[2], ay = y - m[5], bx = x + w - m[2], by = y + h - m[5];
        double sx0 = m[0] * ax + m[3] * ay, sy0 = m[1] * ax + m[4] * ay, sx1 = m[0] * bx + m[3] * by, sy1 = m[1] * bx + m[4] * by;
        int x0 = (int) Math.round(Math.min(sx0, sx1)), y0 = (int) Math.round(Math.min(sy0, sy1));
        int x1 = (int) Math.round(Math.max(sx0, sx1)), y1 = (int) Math.round(Math.max(sy0, sy1));
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        return new int[]{x0, y0, Math.max(1, Math.min(rw, x1) - x0), Math.max(1, Math.min(rh, y1) - y0)};
    }

    /** A whole-picture decode is held to this many pixels (32 MB ARGB): see wholeSample(). */
    static final long WHOLE_MAX_PIXELS = 8_000_000L;

    /**
     * inSampleSize for a picture with no region decoder, decoded whole (w x h stored) before its crop
     * is copied out, so both bitmaps are in memory at once: sample (sampleSize()'s, for the crop),
     * doubled until the whole sampled picture is at most WHOLE_MAX_PIXELS. The cut is part of it, so
     * the two together stay under 64 MB (the old 16 MP limit allowed 64 MB + a 39 MB cut, ~103 MB, on
     * a 2 GB stick). A crop that ends up under 1920x1280 is scaled up: a little softer, never refused.
     */
    static int wholeSample(int w, int h, int sample) {
        int s = Math.max(1, sample);
        while ((long) ((w + s - 1) / s) * ((h + s - 1) / s) > WHOLE_MAX_PIXELS) s *= 2;
        return s;
    }

    /**
     * The crop {x, y, w, h} of a w x h picture, scaled to the aw x ah bitmap it was decoded to whole,
     * always inside it (x < aw, y < ah, at least 1x1): Bitmap.createBitmap throws otherwise (review
     * 7c2b1e5: a crop at the right or bottom edge could round x to aw).
     */
    static int[] wholeCut(int aw, int ah, int w, int h, int[] crop) {
        float fx = (float) aw / w, fy = (float) ah / h;
        int x = Math.max(0, Math.min(aw - 1, Math.round(crop[0] * fx)));
        int y = Math.max(0, Math.min(ah - 1, Math.round(crop[1] * fy)));
        int cw = Math.max(1, Math.min(aw - x, Math.round(crop[2] * fx)));
        int ch = Math.max(1, Math.min(ah - y, Math.round(crop[3] * fy)));
        return new int[]{x, y, cw, ch};
    }

    /** The largest power of two s (at most 64) with cw/s >= tw and ch/s >= th; 1 if none. */
    static int sampleSize(int cw, int ch, int tw, int th) {
        int s = 1;
        while (s < 64 && cw / (s * 2) >= tw && ch / (s * 2) >= th) s *= 2;
        return s;
    }

    /**
     * The affine map {a, b, c, d, e, f} (x' = a x + b y + c, y' = d x + e y + f) taking a w x h
     * picture stored with this EXIF orientation to its displayed frame. 1 (or anything unknown) is
     * the identity; 2 mirror, 3 rotate 180, 4 flip, 5 transpose, 6 rotate 90 clockwise, 7 transverse,
     * 8 rotate 90 anticlockwise (Pillow's ImageOps.exif_transpose).
     */
    static float[] orient(int orientation, int w, int h) {
        switch (orientation) {
            case 2: return new float[]{-1, 0, w, 0, 1, 0};
            case 3: return new float[]{-1, 0, w, 0, -1, h};
            case 4: return new float[]{1, 0, 0, 0, -1, h};
            case 5: return new float[]{0, 1, 0, 1, 0, 0};
            case 6: return new float[]{0, -1, h, 1, 0, 0};
            case 7: return new float[]{0, -1, h, -1, 0, w};
            case 8: return new float[]{0, 1, 0, -1, 0, w};
            default: return new float[]{1, 0, 0, 0, 1, 0};
        }
    }

    /**
     * The 3x3 matrix (android.graphics.Matrix.setValues order) that draws a decoded w x h region,
     * stored with this orientation, upright onto an outW x outH bitmap, filling it.
     */
    static float[] drawMatrix(int orientation, int w, int h, int outW, int outH) {
        float[] m = orient(orientation, w, h);
        boolean t = transposed(orientation);
        float sx = (float) outW / (t ? h : w), sy = (float) outH / (t ? w : h);
        return new float[]{m[0] * sx, m[1] * sx, m[2] * sx, m[3] * sy, m[4] * sy, m[5] * sy, 0, 0, 1};
    }

    /**
     * The 3x3 matrix that draws a decoded w x h region (stored with this orientation) upright into the
     * rect {dx, dy, dw, dh} = p[4..7] of place().
     */
    static float[] placeMatrix(int orientation, int w, int h, double[] p) {
        float[] m = orient(orientation, w, h);
        boolean t = transposed(orientation);
        float sx = (float) (p[6] / (t ? h : w)), sy = (float) (p[7] / (t ? w : h));
        return new float[]{m[0] * sx, m[1] * sx, (float) (m[2] * sx + p[4]), m[3] * sy, m[4] * sy, (float) (m[5] * sy + p[5]), 0, 0, 1};
    }

    /** The smallest power of two s with (w / s) x (h / s) at most max pixels. */
    static int sampleWithin(int w, int h, long max) {
        int s = 1;
        while (s < 64 && (long) ((w + s - 1) / s) * ((h + s - 1) / s) > max) s *= 2;
        return s;
    }

    /** Whether a W x H picture is kept as src.jpg as it came (else a smaller copy is made). */
    static boolean srcAsSent(int w, int h) {
        return w <= SRC_MAX_SIDE && h <= SRC_MAX_SIDE && (long) w * h <= SRC_MAX_PIXELS;
    }

    /** Where the affine/3x3 matrix m sends the point (x, y). */
    static float[] apply(float[] m, float x, float y) {
        return new float[]{m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5]};
    }

    /** null if the size is acceptable, else ImageRefused's code: "pixels" or "small". */
    static String sizeProblem(long w, long h) {
        if (w * h > MAX_PIXELS) return "pixels";
        if (Math.min(w, h) < MIN_SIDE) return "small";
        return null;
    }

    /**
     * The display name of an uploaded file (v1's photo_name): its base name without the extension,
     * control and format characters removed, white space collapsed, at most PHOTO_NAME_MAX UTF-16
     * units (never ending in half a surrogate pair); "Photo" if nothing is left.
     */
    static String photoName(String filename) {
        if (filename == null) return "Photo";
        String base = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        int dot = base.lastIndexOf('.');
        String stem = dot >= 0 ? base.substring(0, dot) : base;
        StringBuilder b = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < stem.length(); ) {
            int c = stem.codePointAt(i);
            i += Character.charCount(c);
            int type = Character.getType(c);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = true;
                continue;
            }
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.UNASSIGNED || type == Character.PRIVATE_USE) continue;
            if (space && b.length() > 0) b.append(' ');
            space = false;
            b.appendCodePoint(c);
        }
        String s = b.toString();
        if (s.length() > UserLibrary.PHOTO_NAME_MAX) {
            int end = UserLibrary.PHOTO_NAME_MAX;
            if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
            s = s.substring(0, end).trim();
        }
        return s.isEmpty() ? "Photo" : s;
    }

    /** meta.json for a prepared photo (contract 1), in the form UserLibrary.validMeta() accepts. */
    static byte[] metaJson(String pid, String name, long added, Readability.Fields f) {
        return metaJson(pid, name, added, f, null, 0);
    }

    /**
     * meta.json with the crop the wall was made with (clamped; omitted when null) and, for a photo
     * re-cropped with Adjust, when (edited &gt; 0: PendingPhotos swaps it in over the photo it replaces).
     */
    static byte[] metaJson(String pid, String name, long added, Readability.Fields f, Crop crop, long edited) {
        UserLibrary.Json.W w = new UserLibrary.Json.W().beginObject().key("v").value(1).key("id").value(pid).key("name").value(name)
                .key("added").value(added).key("tone").value(f.tone)
                .key("ut").beginObject().key("tr").value(f.tr).key("br").value(f.br).endObject()
                .key("frostVeil").value((Number) f.frostVeil).key("listColumn").value(f.listColumn)
                .key("dockQuiet").value(f.dockQuiet).key("clockMist").value(f.clockMist);
        if (crop != null) {
            w.key("crop").beginObject().key("x").value((Number) round6(crop.x)).key("y").value((Number) round6(crop.y))
                    .key("z").value((Number) round6(crop.z)).endObject();
        }
        if (edited > 0) w.key("edited").value(edited);
        return w.endObject().toString().getBytes(StandardCharsets.UTF_8);
    }

    static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    /** A meta.json's crop, or null when it has none (or a malformed one). */
    static Crop metaCrop(java.util.Map<String, Object> m) {
        Object c = m == null ? null : m.get("crop");
        if (!(c instanceof java.util.Map)) return null;
        java.util.Map<?, ?> k = (java.util.Map<?, ?>) c;
        Object x = k.get("x"), y = k.get("y"), z = k.get("z");
        if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) return null;
        double zz = ((Number) z).doubleValue();
        return zz > 0 && !Double.isInfinite(zz) ? new Crop(((Number) x).doubleValue(), ((Number) y).doubleValue(), zz) : null;
    }
}
