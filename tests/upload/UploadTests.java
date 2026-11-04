package home.cleanframe.tv;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runner and shared helpers for the upload side's offline tests (docs/PHOTO-GROUPS.md, contract 4),
 * run by tests/upload.test.sh in ONE JVM (tests/tools.test.sh runs it again inside each
 * rollback clone): ReadabilityTest, PhotoMathTest, PendingPhotosTest, PhotoAttemptTest, UploadServerTest. Each case
 * prints "ok   <name>"; any failure prints "FAIL <name>: <why>" and the run exits 1.
 */
public final class UploadTests {
    static final class Fail extends RuntimeException {
        Fail(String m) {
            super(m);
        }
    }

    interface Case {
        void run() throws Exception;
    }

    static final List<String> NAMES = new ArrayList<>();
    static final List<Case> CASES = new ArrayList<>();

    static void check(boolean cond, String msg) {
        if (!cond) throw new Fail(msg);
    }

    static void eq(Object got, Object want, String what) {
        if (got == null ? want != null : !got.equals(want)) throw new Fail(what + ": got " + got + ", want " + want);
    }

    /** Registers a case; main() runs them all in parallel (each builds its own fixtures and server). */
    static void run(String name, Case c) {
        NAMES.add(name);
        CASES.add(c);
    }

    static String outcome(String name, Case c) {
        long t0 = System.nanoTime();
        try {
            c.run();
            return "ok   " + name + (System.getenv("UPLOAD_TEST_TIMES") != null ? String.format(" (%.2f s)", (System.nanoTime() - t0) / 1e9) : "");
        } catch (Throwable e) {
            java.io.StringWriter w = new java.io.StringWriter();
            if (!(e instanceof Fail)) e.printStackTrace(new java.io.PrintWriter(w));
            return "FAIL " + name + ": " + e + (w.getBuffer().length() > 0 ? "\n" + w : "");
        }
    }

    static final class Clock implements java.util.function.LongSupplier {
        volatile long t;

        Clock(long t) {
            this.t = t;
        }

        public long getAsLong() {
            return t;
        }
    }

    static Path tmp(String name) throws IOException {
        return Files.createTempDirectory(name);
    }

    static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.copy(f, to.resolve(from.relativize(f).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** A real JPEG of w x h (ImageIO; JFIF APP0 only), filled with a gradient. */
    static byte[] jpeg(int w, int h) throws IOException {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y += 8) for (int x = 0; x < w; x += 8) {
            int c = ((x * 255 / Math.max(1, w)) << 16) | ((y * 255 / Math.max(1, h)) << 8) | 0x40;
            for (int yy = y; yy < Math.min(h, y + 8); yy++) for (int xx = x; xx < Math.min(w, x + 8); xx++) im.setRGB(xx, yy, c);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(im, "jpg", out)) throw new IOException("no JPEG writer");
        return out.toByteArray();
    }

    /** A real progressive JPEG of w x h (ImageIO, several scans; JFIF APP0 only), filled like jpeg(). */
    static byte[] progressiveJpeg(int w, int h) throws IOException {
        BufferedImage im = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg(w, h)));
        javax.imageio.ImageWriter wr = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam p = wr.getDefaultWriteParam();
        p.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream o = javax.imageio.ImageIO.createImageOutputStream(out)) {
            wr.setOutput(o);
            wr.write(null, new javax.imageio.IIOImage(im, null, null), p);
        } finally {
            wr.dispose();
        }
        return out.toByteArray();
    }

    /**
     * A real JPEG of w x h with a restart interval (ImageIO, DRI of 2 MCUs: RSTn markers inside every
     * scan), baseline or progressive, filled with a pattern so the entropy data is not trivial.
     */
    static byte[] restartJpeg(int w, int h, boolean progressive) throws IOException {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) im.setRGB(x, y, (x * 255 / w) << 16 | (y * 255 / h) << 8 | ((x ^ y) & 0xff));
        javax.imageio.ImageWriter wr = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            javax.imageio.ImageWriteParam p = wr.getDefaultWriteParam();
            if (progressive) p.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
            javax.imageio.metadata.IIOMetadata md = wr.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(im), p);
            String fmt = "javax_imageio_jpeg_image_1.0";
            org.w3c.dom.Element root = (org.w3c.dom.Element) md.getAsTree(fmt);
            org.w3c.dom.Node seq = root.getElementsByTagName("markerSequence").item(0);
            javax.imageio.metadata.IIOMetadataNode dri = new javax.imageio.metadata.IIOMetadataNode("dri");
            dri.setAttribute("interval", "2");
            seq.insertBefore(dri, seq.getFirstChild());
            md.setFromTree(fmt, root);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (javax.imageio.stream.ImageOutputStream o = javax.imageio.ImageIO.createImageOutputStream(out)) {
                wr.setOutput(o);
                wr.write(null, new javax.imageio.IIOImage(im, null, md), p);
            }
            return out.toByteArray();
        } finally {
            wr.dispose();
        }
    }

    /** jpg with `fill` extra 0xFF fill bytes before every RSTn after the first SOS (T.81 B.1.1.2 allows them). */
    static byte[] padRestarts(byte[] jpg, int fill) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(jpg.length + 1024);
        boolean scan = false;
        for (int i = 0; i < jpg.length; i++) {
            int c = jpg[i] & 0xff, d = i + 1 < jpg.length ? jpg[i + 1] & 0xff : -1;
            if (c == 0xff && d == 0xda) scan = true;
            if (scan && c == 0xff && d >= 0xd0 && d <= 0xd7) for (int k = 0; k < fill; k++) o.write(0xff);
            o.write(c);
        }
        return o.toByteArray();
    }

    /** How many RSTn markers jpg holds. */
    static int restarts(byte[] jpg) {
        int n = 0;
        for (int i = 0; i + 1 < jpg.length; i++) {
            int d = jpg[i + 1] & 0xff;
            if ((jpg[i] & 0xff) == 0xff && d >= 0xd0 && d <= 0xd7) n++;
        }
        return n;
    }

    static String dataUrl(byte[] jpg) {
        return PendingPhotos.DATA_URL_PREFIX + java.util.Base64.getEncoder().encodeToString(jpg);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(String json) {
        return (Map<String, Object>) UserLibrary.Json.parse(json);
    }

    static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    public static void main(String[] a) throws Exception {
        check(Files.isDirectory(Paths.get("tests/upload/data")), "run from the repo root");
        long t0 = System.nanoTime();
        ReadabilityTest.main(a);
        PhotoMathTest.main(a);
        PendingPhotosTest.main(a);
        PhotoAttemptTest.main(a);
        UploadServerTest.main(a);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(4, Math.min(CASES.size(), 2 * Runtime.getRuntime().availableProcessors())));
        List<java.util.concurrent.Future<String>> results = new ArrayList<>();
        for (int i = 0; i < CASES.size(); i++) {
            String n = NAMES.get(i);
            Case c = CASES.get(i);
            results.add(pool.submit(() -> outcome(n, c)));
        }
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            String line;
            try {
                line = results.get(i).get(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                line = "FAIL " + NAMES.get(i) + ": did not finish (" + e + ")";
            }
            System.out.println(line);
            if (line.startsWith("FAIL")) failed.add(NAMES.get(i));
        }
        pool.shutdownNow();
        System.out.printf("%d/%d passed in %.2f s%n", CASES.size() - failed.size(), CASES.size(), (System.nanoTime() - t0) / 1e9);
        List<String> FAILED = failed;
        if (!FAILED.isEmpty()) {
            System.out.println("UPLOAD_TESTS_FAIL " + String.join(", ", FAILED));
            System.exit(1);
        }
        System.out.println("UPLOAD_TESTS_OK");
        System.exit(0);
    }
}
