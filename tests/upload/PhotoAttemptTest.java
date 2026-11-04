package home.cleanframe.tv;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static home.cleanframe.tv.UploadTests.check;
import static home.cleanframe.tv.UploadTests.eq;
import static home.cleanframe.tv.UploadTests.run;

/**
 * PhotoProcessor.Attempt, the deadline AndroidPhotoProcessor runs each photo under (review 999ba07,
 * L3): a decode past its deadline cannot be interrupted, so once the HTTP thread has given up (and the
 * phone has been told the photo failed) the late worker must write nothing, and must remove its
 * directory, instead of later writing meta.json into a .&lt;pid&gt;.tmp the page would finish. The work
 * here waits like BitmapFactory does: interrupts ignored.
 */
final class PhotoAttemptTest {
    /** Waits for l, ignoring interrupts (BitmapFactory.decode* cannot be interrupted either). */
    static void uninterruptibly(CountDownLatch l) {
        boolean interrupted = false;
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end) {
            try {
                if (l.await(end - System.nanoTime(), TimeUnit.NANOSECONDS)) break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    static void writeAll(File dir) throws IOException {
        for (String f : new String[]{"wall.jpg", "thumb.jpg", "meta.json"}) Files.write(new File(dir, f).toPath(), f.getBytes(StandardCharsets.UTF_8));
    }

    /** Lets everything already queued on worker finish. */
    static void drain(ExecutorService worker) throws Exception {
        worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    public static void main(String[] args) {
        run("attempt: a photo given up on at its deadline writes nothing later, and removes its tmp dir", () -> {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Path dir = UploadTests.tmp("cf-attempt").resolve(".pslow000001.tmp");
                Files.createDirectories(dir);
                CountDownLatch decoded = new CountDownLatch(1);
                long t0 = System.nanoTime();
                boolean timedOut = false;
                try {
                    PhotoProcessor.Attempt.run(worker, 200, 5000, a -> {
                        uninterruptibly(decoded); // a long decode
                        a.commit(dir.toFile());
                        writeAll(dir.toFile());
                    });
                } catch (IOException e) {
                    timedOut = "preparing timed out".equals(e.getMessage());
                }
                check(timedOut, "the deadline gives up on it");
                check(System.nanoTime() - t0 < 5_000_000_000L, "at the deadline, not when the decode ends");
                decoded.countDown(); // the decode finishes after the phone was told it failed
                drain(worker);
                check(!Files.exists(dir.resolve("meta.json")), "the late worker wrote meta.json: the page would add a photo the phone was told failed");
                check(!Files.exists(dir), "the late worker removes its tmp dir");
            } finally {
                worker.shutdownNow();
            }
        });
        run("attempt: a photo that committed before its deadline is waited for, not given up", () -> {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Path dir = UploadTests.tmp("cf-attempt").resolve(".pslow000002.tmp");
                Files.createDirectories(dir);
                PhotoProcessor.Attempt.run(worker, 200, 5000, a -> {
                    a.commit(dir.toFile());
                    try {
                        Thread.sleep(600); // slow storage: the writes outlast the deadline
                    } catch (InterruptedException e) {
                        throw new IOException("interrupted");
                    }
                    writeAll(dir.toFile());
                });
                check(Files.isRegularFile(dir.resolve("meta.json")), "its files are all there when run() returns");
            } finally {
                worker.shutdownNow();
            }
        });
        run("attempt: the deadline counts from the start, not the queue: a photo behind a slow one is prepared (A2)", () -> {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                CountDownLatch slow = new CountDownLatch(1);
                worker.submit(() -> uninterruptibly(slow)); // an earlier photo takes longer than this one's deadline
                Path dir = UploadTests.tmp("cf-attempt").resolve(".pbehind0004.tmp");
                Files.createDirectories(dir);
                new Thread(() -> {
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException e) {
                        // released early
                    }
                    slow.countDown();
                }).start();
                PhotoProcessor.Attempt.run(worker, 300, 5000, a -> {
                    a.commit(dir.toFile());
                    writeAll(dir.toFile());
                });
                check(Files.isRegularFile(dir.resolve("meta.json")), "prepared after 600 ms queued, with a 300 ms deadline of its own");
            } finally {
                worker.shutdownNow();
            }
        });
        run("attempt: a queued photo given up on never runs; refusals and failures pass through", () -> {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                CountDownLatch busy = new CountDownLatch(1);
                worker.submit(() -> uninterruptibly(busy)); // the one thread is stuck on an earlier photo
                Path dir = UploadTests.tmp("cf-attempt").resolve(".pqueued0003.tmp");
                Files.createDirectories(dir);
                boolean[] ran = {false};
                boolean timedOut = false;
                try {
                    PhotoProcessor.Attempt.run(worker, 5000, 100, a -> ran[0] = true); // queued past its queue cap
                } catch (IOException e) {
                    timedOut = true;
                }
                busy.countDown();
                drain(worker);
                check(timedOut && !ran[0], "given up while queued: never started");
                String code = null;
                try {
                    PhotoProcessor.Attempt.run(worker, 5000, 5000, a -> {
                        throw new PhotoProcessor.Refused("pixels", PhotoProcessor.PIXELS);
                    });
                } catch (PhotoProcessor.Refused e) {
                    code = e.code;
                }
                eq(code, "pixels", "a refusal reaches the phone as it is");
                String msg = null;
                try {
                    PhotoProcessor.Attempt.run(worker, 5000, 5000, a -> {
                        throw new IllegalStateException("boom");
                    });
                } catch (IOException e) {
                    msg = e.getMessage();
                }
                eq(msg, "preparing failed: IllegalStateException", "anything else is an internal failure");
            } finally {
                worker.shutdownNow();
            }
        });
    }
}
