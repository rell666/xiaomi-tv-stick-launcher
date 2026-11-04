package home.cleanframe.tv;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns one uploaded file into a prepared photo's first files (docs/PHOTO-GROUPS.md, contract 4).
 * UploadServer calls it; AndroidPhotoProcessor is the real one (BitmapFactory, ExifInterface), the
 * offline tests use a stub that writes files. Plain Java.
 */
interface PhotoProcessor {
    /**
     * Read upload and write wall.jpg (1920x1080, the part crop frames: null = the centred 16:9),
     * thumb.jpg (512x288), src.jpg (the upright copy Adjust starts from, when there is room) and, LAST,
     * meta.json (with PhotoMath.metaJson(pid, name, added, ..., crop, 0)) into dir, the photo's
     * .&lt;pid&gt;.tmp directory. Refused: the file is not a photo the TV takes (its message is shown on
     * the phone). Anything else thrown is an internal failure ("could not be prepared").
     */
    void process(File upload, File dir, String pid, String name, long added, PhotoMath.Crop crop) throws Refused, IOException;

    /**
     * Adjust: the same from a photo's src.jpg with a new crop, into its .&lt;pid&gt;.tmp; src.jpg is copied
     * as it is and meta.json carries edited (PendingPhotos swaps the result in over the photo).
     */
    void recrop(File src, File dir, String pid, String name, long added, PhotoMath.Crop crop, long edited) throws Refused, IOException;

    /** A photo the TV does not take. code: "type", "pixels", "small" or "broken". */
    final class Refused extends Exception {
        final String code;

        Refused(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    String TYPE = "That file is not a JPEG, PNG, WebP or HEIC photo.";
    String PIXELS = "That photo has more than " + PhotoMath.MAX_PIXELS / 1_000_000 + " megapixels.";
    String SMALL = "That photo is too small (under " + PhotoMath.MIN_SIDE + " pixels on a side).";
    String BROKEN = "That photo could not be read (damaged or unsupported).";
    /**
     * A HEIC the stick's own decoder cannot open: on the Xiaomi stick (Android 11) every HEIC tried on
     * 2026-10-31 failed in the platform decoder ("all codecs failed to extract frame"), so the phone is
     * told what to do, not that the file is damaged (DEVICE-CHECK 11).
     */
    String HEIC = "This TV cannot open this HEIC photo. Please send it as a JPEG.";

    /**
     * One photo prepared on the processing thread, with a deadline the HTTP thread waits for (plain
     * Java, so tests/upload/PhotoAttemptTest.java runs it). Past the deadline the HTTP thread gives up
     * and the phone is told the photo failed, but the worker cannot be stopped: Future.cancel(true) only
     * interrupts, and BitmapFactory ignores that. Left alone it would go on to write meta.json into the
     * photo's .&lt;pid&gt;.tmp, which then becomes a job the page finishes, a photo the phone was told had
     * failed (review 999ba07, L3). So the work calls commit(dir) before its first write: after a give-up
     * that removes dir and throws, and nothing is written; once committed, the deadline no longer gives
     * up on it and the caller waits for its (short) writes instead.
     *
     * The deadline counts from when the work starts on the worker, not from when it was queued (review
     * 30e7356, A2: a photo queued behind a slow one was given up on before it had started). Waiting in
     * the queue has its own cap, queueMs: a photo not started by then is given up on unstarted (it
     * never runs). So the caller waits at most queueMs + timeoutMs, plus a committed photo's writes.
     */
    final class Attempt {
        interface Work {
            void run(Attempt a) throws Refused, IOException;
        }

        private boolean gaveUp, committed, started;

        /** On the worker, before the work: false (do not run it) once the caller gave up while it was queued. */
        private synchronized boolean start() {
            if (gaveUp) return false;
            started = true;
            notifyAll();
            return true;
        }

        /** Waits at most ms for start(); true once started. */
        private synchronized boolean awaitStart(long ms) throws InterruptedException {
            long end = System.nanoTime() + ms * 1_000_000L;
            while (!started) {
                long left = end - System.nanoTime();
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return true;
        }

        /** Before the first write into dir: go ahead, or (the caller gave up) dir is removed and this throws. */
        void commit(File dir) throws IOException {
            synchronized (this) {
                if (!gaveUp) {
                    committed = true;
                    return;
                }
            }
            try {
                UserLibrary.deleteTree(dir.toPath());
            } catch (IOException | RuntimeException e) {
                // left for PendingPhotos.removeStale(): it never gets a meta.json, so it is never a job
            }
            throw new IOException("given up: preparing took too long");
        }

        /** false: the work has committed (its writes are under way), so it is not given up. */
        private synchronized boolean giveUp() {
            if (committed) return false;
            gaveUp = true;
            return true;
        }

        /**
         * Runs work on worker and waits for it: at most queueMs for it to start, then at most timeoutMs
         * from its start unless it has committed by then.
         */
        static void run(ExecutorService worker, long timeoutMs, long queueMs, Work work) throws Refused, IOException {
            Attempt a = new Attempt();
            Future<Void> f = worker.submit(() -> {
                if (a.start()) work.run(a);
                return null;
            });
            try {
                try {
                    if (!a.awaitStart(queueMs) && a.giveUp()) {
                        f.cancel(true); // still queued: start() refuses it, it never runs
                        throw new IOException("preparing timed out");
                    }
                    f.get(timeoutMs, TimeUnit.MILLISECONDS); // from its start (or the start just raced the queue cap)
                } catch (TimeoutException e) {
                    if (a.giveUp()) {
                        f.cancel(true); // not started yet: never runs; running: commit() stops it before any write
                        throw new IOException("preparing timed out");
                    }
                    f.get(); // committed just before the deadline: its writes are under way
                }
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof Refused) throw (Refused) c;
                if (c instanceof IOException) throw (IOException) c;
                throw new IOException("preparing failed: " + (c == null ? "?" : c.getClass().getSimpleName()));
            } catch (InterruptedException e) {
                a.giveUp();
                f.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
    }
}
