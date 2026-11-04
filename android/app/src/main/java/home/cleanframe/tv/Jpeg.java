package home.cleanframe.tv;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A JPEG's marker structure, read without decoding a pixel (plain Java, tests/upload/PhotoMathTest.java):
 * its frame size, and which metadata segments it carries, anywhere in the file. Used to check every JPEG the app writes:
 * the materials the page hands back (PendingPhotos.saveMaterial) and the wall and thumb the
 * processor encodes (no EXIF, XMP, IPTC or comment may reach R).
 */
final class Jpeg {
    /** The body of Adobe's APP14. */
    private static final int ADOBE_LEN = 12;

    private Jpeg() {
    }

    /** One parsed file: width and height from its SOF, and the metadata segments anywhere in it. */
    static final class Info {
        final int width, height;
        /**
         * "APP1", "APP13", "COM", ... : every APPn other than JFIF APP0, an ICC APP2 and Adobe's APP14, and every COM,
         * before the first scan or between scans (a progressive JPEG); "trailer" for bytes after EOI.
         */
        final List<String> metadata;

        Info(int width, int height, List<String> metadata) {
            this.width = width;
            this.height = height;
            this.metadata = metadata;
        }
    }

    /**
     * The parsed structure of b[0..n), or null unless it is a well-formed baseline/progressive JPEG:
     * SOI, length-framed segments, one SOF with a size, at least one SOS, each scan's entropy-coded
     * data (0xFF00 stuffing and RSTn inside it, fill bytes 0xFF before any marker, RSTn included), and an
 * EOI. Every marker is walked through every scan
     * up to EOI (review 30e7356, G7: until then the walk stopped at the first SOS, so an APP or COM
     * segment between the scans of a progressive JPEG went unseen and strip() kept it). Bytes after
     * EOI are listed as "trailer" (phones append a second image or vendor data there).
     */
    static Info parse(byte[] b, int n) {
        return walk(b, n, null);
    }

    /**
     * A segment walk() lists as metadata: an APPn other than JFIF/JFXX APP0, ICC APP2 and Adobe's APP14,
     * or a COM. Adobe's says how to read the colours (RGB, YCC, CMYK or YCCK): without it such a JPEG
     * decodes wrongly. It is "Adobe", version, two flags and the transform, always 12 bytes, so nothing
     * can be carried in one kept here.
     */
    private static boolean metadata(byte[] b, int m, int body, int bodyLen) {
        if (m == 0xe0) return !startsWith(b, body, bodyLen, "JFIF\0") && !startsWith(b, body, bodyLen, "JFXX\0");
        if (m == 0xe2) return !startsWith(b, body, bodyLen, "ICC_PROFILE\0");
        if (m == 0xee) return !(bodyLen == ADOBE_LEN && startsWith(b, body, bodyLen, "Adobe"));
        return (m >= 0xe1 && m <= 0xef) || m == 0xfe;
    }

    /**
     * b[0..n) without the segments parse() lists as metadata (EXIF, GPS, XMP, IPTC, comments, wherever
     * they are, and anything after EOI), every other byte as it was (no re-encoding); null unless
     * parse() accepts it. The phone's own copy of a photo is kept this way as its src.jpg.
     */
    static byte[] strip(byte[] b, int n) {
        if (b == null || n < 0 || n > b.length) return null;
        ByteArrayOutputStream o = new ByteArrayOutputStream(n);
        return walk(b, n, o) == null ? null : o.toByteArray();
    }

    /**
     * The one walk behind parse() and strip(): every marker from SOI to EOI. out != null: every byte
     * but the metadata segments (and the trailer) is copied to it.
     */
    private static Info walk(byte[] b, int n, ByteArrayOutputStream out) {
        if (b == null || n < 4 || n > b.length || u(b, 0) != 0xff || u(b, 1) != 0xd8) return null;
        if (out != null) out.write(b, 0, 2);
        int i = 2, w = -1, h = -1;
        boolean scanned = false;
        List<String> meta = new ArrayList<>();
        while (true) {
            if (i + 2 > n || u(b, i) != 0xff) return null;
            int start = i, m = u(b, i + 1);
            while (m == 0xff) { // fill bytes
                i++;
                if (i + 2 > n) return null;
                m = u(b, i + 1);
            }
            if (m == 0xd9) { // EOI: the image ends here
                if (!scanned || w <= 0) return null;
                if (out != null) out.write(b, start, i + 2 - start);
                if (i + 2 < n) meta.add("trailer");
                return new Info(w, h, meta);
            }
            if (m == 0x01 || (m >= 0xd0 && m <= 0xd7)) { // standalone markers
                if (out != null) out.write(b, start, i + 2 - start);
                i += 2;
                continue;
            }
            if (m == 0xd8 || m == 0x00) return null;
            if (i + 4 > n) return null;
            int len = (u(b, i + 2) << 8) | u(b, i + 3);
            if (len < 2 || i + 2 + len > n) return null;
            int body = i + 4, bodyLen = len - 2, next = i + 2 + len;
            boolean sof = m >= 0xc0 && m <= 0xcf && m != 0xc4 && m != 0xc8 && m != 0xcc;
            if (m == 0xda) { // SOS: its header, then entropy-coded data up to the next marker
                if (w <= 0) return null; // a scan before the frame
                int j = next;
                while (true) {
                    if (j >= n) return null; // no marker after the data: cut off
                    if (u(b, j) != 0xff) {
                        j++;
                        continue;
                    }
                    int k = j + 1;
                    while (k < n && u(b, k) == 0xff) k++; // fill bytes (T.81 B.1.1.2), before any marker
                    if (k >= n) return null;
                    int c = u(b, k);
                    if (c == 0x00 || (c >= 0xd0 && c <= 0xd7)) { // a stuffed 0xFF, or a restart marker
                        // (review 7c2b1e5: fill bytes before an RSTn were taken for the end of the scan,
                        // and the next entropy byte then refused a valid JPEG); the scan goes on after it
                        j = k + 1;
                        continue;
                    }
                    break; // a marker (j at its first fill byte, if any): the scan is over
                }
                if (out != null) out.write(b, start, j - start);
                scanned = true;
                i = j;
                continue;
            }
            if (sof) {
                if (w >= 0 || bodyLen < 6) return null; // one frame only
                h = (u(b, body + 1) << 8) | u(b, body + 2);
                w = (u(b, body + 3) << 8) | u(b, body + 4);
                if (w == 0 || h == 0) return null;
            }
            if (!sof && metadata(b, m, body, bodyLen)) {
                meta.add(m == 0xfe ? "COM" : "APP" + (m - 0xe0));
            } else if (out != null) {
                out.write(b, start, next - start);
            }
            i = next;
        }
    }

    private static int u(byte[] b, int i) {
        return b[i] & 0xff;
    }

    private static boolean startsWith(byte[] b, int at, int len, String s) {
        byte[] p = s.getBytes(StandardCharsets.ISO_8859_1);
        if (len < p.length) return false;
        for (int k = 0; k < p.length; k++) if (b[at + k] != p[k]) return false;
        return true;
    }
}
