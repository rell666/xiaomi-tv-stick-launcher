package home.cleanframe.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;

/**
 * The stick's PhotoProcessor (docs/PHOTO-GROUPS.md, contract 4). Each photo is prepared on the one
 * background thread MainActivity hands in, so two uploads never decode at once; the HTTP thread waits
 * for the answer (so the phone hears "too many megapixels" and the like).
 *
 * Steps, with the memory each needs on the 2 GB stick (the arithmetic is PhotoMath's, tested offline):
 *  1. type from the first bytes, then size from the header only (inJustDecodeBounds): nothing decoded;
 *  2. EXIF orientation (android.media.ExifInterface);
 *  3. the part of the photo inside the crop's frame (PhotoMath.place: the user's position and zoom,
 *     else the centred 16:9), mapped to the stored frame, decoded alone (BitmapRegionDecoder) with the
 *     largest power-of-two inSampleSize that keeps it at least the size it is drawn at: under
 *     3840x2160 (33 MB); sRGB requested, so an embedded colour profile is converted, not copied;
 *  4. drawn upright and scaled into its place on a black 1920x1080 bitmap (8 MB; alpha flattened on
 *     black; zoomed out past the photo, the rest stays black), the region freed; readability from it;
 *  5. wall.jpg q87, thumb.jpg 512x288 q80 (via 1024x576). A Bitmap carries no EXIF, XMP, GPS or
 *     comment, and Jpeg.parse() checks each encoded file has none before it is written;
 *  6. src.jpg for Adjust, once the wall is freed: the phone's upright copy as it came with its
 *     metadata segments cut out (Jpeg.strip, no re-encoding), else a copy made here of at most
 *     PhotoMath.SRC_MADE_PIXELS (the decoded picture and its upright copy, 16 MB each); none when the
 *     stick has less than PhotoMath.SRC_MIN_FREE free (the photo is still added, without Adjust);
 *  7. wall.jpg, thumb.jpg, src.jpg, then meta.json last, into the photo's .&lt;pid&gt;.tmp directory, only
 *     if the HTTP thread has not given up on it by then (PhotoProcessor.Attempt: a decode cannot be
 *     interrupted).
 * Peak about 41 MB (the region under 33 MB + the 8 MB wall) for any photo up to 60 megapixels. A
 * picture the region decoder cannot read is decoded whole and its crop copied out, both held at once:
 * PhotoMath.wholeSample() keeps the whole bitmap to 8 MP (32 MB), so that path peaks under 64 MB.
 */
final class AndroidPhotoProcessor implements PhotoProcessor {
    /** One photo's preparing, from when it starts on the processing thread (PhotoProcessor.Attempt). */
    static final long TIMEOUT_S = 120;
    /**
     * How long a photo may wait for the processing thread before it starts: two photos' worth (the
     * other upload slot's photo, and an Adjust, ahead of it). A phone waits at most QUEUE_S + TIMEOUT_S.
     */
    static final long QUEUE_S = 2 * TIMEOUT_S;

    private final ExecutorService worker;

    AndroidPhotoProcessor(ExecutorService worker) {
        this.worker = worker;
    }

    @Override
    public void process(File upload, File dir, String pid, String name, long added, PhotoMath.Crop crop) throws Refused, IOException {
        Attempt.run(worker, TIMEOUT_S * 1000, QUEUE_S * 1000, a -> prepare(upload, dir, pid, name, added, crop, 0, a));
    }

    @Override
    public void recrop(File src, File dir, String pid, String name, long added, PhotoMath.Crop crop, long edited) throws Refused, IOException {
        Attempt.run(worker, TIMEOUT_S * 1000, QUEUE_S * 1000, a -> prepare(src, dir, pid, name, added, crop, edited, a));
    }

    /** edited &gt; 0: Adjust, upload is the photo's own src.jpg (kept as it is). */
    static void prepare(File upload, File dir, String pid, String name, long added, PhotoMath.Crop crop, long edited, Attempt attempt)
            throws Refused, IOException {
        String path = upload.getAbsolutePath();
        byte[] head = new byte[32];
        int n;
        try (InputStream in = new FileInputStream(upload)) {
            n = in.read(head);
        }
        String type = PhotoMath.sniff(head, Math.max(n, 0));
        if (type == null) throw new Refused("type", TYPE);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int w = bounds.outWidth, h = bounds.outHeight;
        if (w <= 0 || h <= 0) throw PhotoMath.unreadable(type);
        String problem = PhotoMath.sizeProblem(w, h);
        if ("pixels".equals(problem)) throw new Refused("pixels", PIXELS);
        if ("small".equals(problem)) throw new Refused("small", SMALL);
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            // no readable EXIF: shown as stored
        }
        boolean t = PhotoMath.transposed(orientation);
        int shownW = t ? h : w, shownH = t ? w : h;
        PhotoMath.Crop k = PhotoMath.clamp(shownW, shownH, crop);
        double[] place = PhotoMath.place(shownW, shownH, k);
        int[] cut = PhotoMath.toStored((int) place[0], (int) place[1], (int) place[2], (int) place[3], w, h, orientation);
        int drawW = Math.max(1, (int) Math.ceil(t ? place[7] : place[6])), drawH = Math.max(1, (int) Math.ceil(t ? place[6] : place[7]));
        int sample = PhotoMath.sampleSize(cut[2], cut[3], drawW, drawH);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap region = null, wall = null;
        try {
            region = decodeRegion(path, cut, o);
            if (region == null) region = decodeWhole(path, w, h, cut, o);
            if (region == null) throw PhotoMath.unreadable(type); // a HEIC whose size reads but whose pixels do not
            wall = Bitmap.createBitmap(PhotoMath.WALL_W, PhotoMath.WALL_H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(wall);
            c.drawColor(Color.BLACK);
            Matrix m = new Matrix();
            m.setValues(PhotoMath.placeMatrix(orientation, region.getWidth(), region.getHeight(), place));
            c.drawBitmap(region, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG));
            region.recycle();
            region = null;
            int[] px = new int[Readability.STAGE_W * Readability.STAGE_H];
            wall.getPixels(px, 0, Readability.STAGE_W, 0, 0, Readability.STAGE_W, Readability.STAGE_H);
            Readability.Fields fields = Readability.metaFields(px, Readability.STAGE_W, Readability.STAGE_H);
            px = null;
            byte[] wallJpg = encode(wall, PhotoMath.WALL_QUALITY, PhotoMath.WALL_W, PhotoMath.WALL_H);
            Bitmap half = Bitmap.createScaledBitmap(wall, PhotoMath.THUMB_W * 2, PhotoMath.THUMB_H * 2, true);
            wall.recycle();
            wall = null;
            Bitmap thumb = Bitmap.createScaledBitmap(half, PhotoMath.THUMB_W, PhotoMath.THUMB_H, true);
            if (thumb != half) half.recycle();
            byte[] thumbJpg = encode(thumb, PhotoMath.THUMB_QUALITY, PhotoMath.THUMB_W, PhotoMath.THUMB_H);
            thumb.recycle();
            byte[] srcJpg = edited > 0 ? Files.readAllBytes(upload.toPath())
                    : dir.getUsableSpace() >= PhotoMath.SRC_MIN_FREE ? src(upload, type, w, h, orientation) : null;
            attempt.commit(dir); // given up on (the phone was told it failed): dir removed, nothing written
            Files.write(new File(dir, "wall.jpg").toPath(), wallJpg);
            Files.write(new File(dir, "thumb.jpg").toPath(), thumbJpg);
            if (srcJpg != null) Files.write(new File(dir, "src.jpg").toPath(), srcJpg);
            Files.write(new File(dir, "meta.json").toPath(), PhotoMath.metaJson(pid, name, added, fields, k, edited)); // last
        } catch (OutOfMemoryError e) {
            throw new IOException("out of memory");
        } finally {
            if (region != null) region.recycle();
            if (wall != null) wall.recycle();
        }
    }

    /**
     * src.jpg's bytes: the upload itself, metadata cut out, when it is a JPEG shown as stored and small
     * enough (the phone page's upright copy); else an upright copy of at most SRC_MADE_PIXELS; null if
     * neither can be made (the photo is kept without Adjust).
     */
    private static byte[] src(File upload, String type, int w, int h, int orientation) {
        try {
            if ("jpeg".equals(type) && (orientation < 2 || orientation > 8) && PhotoMath.srcAsSent(w, h)
                    && upload.length() <= PhotoMath.SRC_MAX_BYTES) {
                byte[] all = Files.readAllBytes(upload.toPath());
                byte[] clean = Jpeg.strip(all, all.length);
                Jpeg.Info info = clean == null ? null : Jpeg.parse(clean, clean.length);
                if (info != null && info.metadata.isEmpty() && info.width == w && info.height == h) return clean;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = PhotoMath.sampleWithin(w, h, PhotoMath.SRC_MADE_PIXELS);
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            Bitmap all = BitmapFactory.decodeFile(upload.getAbsolutePath(), o);
            if (all == null) return null;
            boolean t = PhotoMath.transposed(orientation);
            int aw = all.getWidth(), ah = all.getHeight(), ow = t ? ah : aw, oh = t ? aw : ah;
            Bitmap up = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            try {
                Canvas c = new Canvas(up);
                c.drawColor(Color.BLACK);
                Matrix m = new Matrix();
                m.setValues(PhotoMath.drawMatrix(orientation, aw, ah, ow, oh));
                c.drawBitmap(all, m, new Paint(Paint.FILTER_BITMAP_FLAG));
                all.recycle();
                return encode(up, PhotoMath.SRC_QUALITY, ow, oh);
            } finally {
                if (!all.isRecycled()) all.recycle();
                up.recycle();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap decodeRegion(String path, int[] crop, BitmapFactory.Options o) {
        try {
            @SuppressWarnings("deprecation") BitmapRegionDecoder d = BitmapRegionDecoder.newInstance(path, false);
            if (d == null) return null;
            try {
                return d.decodeRegion(new Rect(crop[0], crop[1], crop[0] + crop[2], crop[1] + crop[3]), o);
            } finally {
                d.recycle();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * A picture with no region decoder: the whole picture, sampled to at most PhotoMath.WHOLE_MAX_PIXELS
     * (its crop may then be scaled up a little), then the crop cut from it.
     */
    private static Bitmap decodeWhole(String path, int w, int h, int[] crop, BitmapFactory.Options o) {
        o.inSampleSize = PhotoMath.wholeSample(w, h, o.inSampleSize);
        Bitmap all = BitmapFactory.decodeFile(path, o);
        if (all == null) return null;
        int[] r = PhotoMath.wholeCut(all.getWidth(), all.getHeight(), w, h, crop); // always inside all
        Bitmap cut = Bitmap.createBitmap(all, r[0], r[1], r[2], r[3]);
        if (cut != all) all.recycle();
        return cut;
    }

    /** JPEG bytes of b, refused unless they are a whole JPEG of that size with no metadata segment. */
    static byte[] encode(Bitmap b, int quality, int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        if (!b.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw new IOException("JPEG encoding failed");
        byte[] jpg = out.toByteArray();
        Jpeg.Info info = Jpeg.parse(jpg, jpg.length);
        if (info == null || info.width != w || info.height != h || !info.metadata.isEmpty()) {
            throw new IOException("the encoded JPEG is not clean: " + (info == null ? "unparsable" : info.metadata));
        }
        return jpg;
    }
}
