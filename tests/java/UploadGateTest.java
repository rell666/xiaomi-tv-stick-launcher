package home.cleanframe.tv;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Behavioural tests for UploadGate, which orders MainActivity's Bridge.uploadStart (JavaBridge thread)
 * against stopUpload (UI thread: onStop, every page drop). Review 999ba07 (L1): uploadStart held the
 * lock stopUpload takes while it wrote the token under the library's lock, so onStop on the UI thread
 * waited for the start-up clean-up or a scan of every photo (an ANR on the only launcher). Here a
 * "library" lock is held by the start's work (or by a clean-up the work waits for) and stop() must
 * still return at once; the start must then undo what it made. Plain Java, no JUnit: run by
 * tests/fallback.test.sh.
 */
public final class UploadGateTest {
    static void check(boolean cond, String msg) {
        if (!cond) throw new FallbackPolicyTest.Fail(msg);
    }

    static final String NOT_SHOWN = "{\"ok\":false,\"reason\":\"not shown\"}", OK = "{\"ok\":true}";

    /** Runs r on its own thread ("ui") and says whether it finished within ms. */
    static boolean finishesWithin(Runnable r, long ms) throws InterruptedException {
        Thread t = new Thread(r, "ui");
        t.setDaemon(true);
        t.start();
        t.join(ms);
        return !t.isAlive();
    }

    /** A start whose work holds the library's lock until released: stop() returns at once and the start undoes itself. */
    static void stopNeverWaitsForTheLibrary() throws Exception {
        UploadGate gate = new UploadGate();
        Object library = new Object();
        CountDownLatch inWork = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger undone = new AtomicInteger(), ended = new AtomicInteger();
        AtomicReference<String> answer = new AtomicReference<>();
        Thread bridge = new Thread(() -> answer.set(gate.start(() -> true, NOT_SHOWN, () -> {
            synchronized (library) { // UserLibrary.uploadStart(): photoRoom() scans every group under this lock
                inWork.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return OK;
            }
        }, OK::equals, undone::incrementAndGet)), "bridge");
        bridge.setDaemon(true);
        bridge.start();
        try {
            check(inWork.await(10, TimeUnit.SECONDS), "the start's work began");
            check(finishesWithin(() -> gate.stop(ended::incrementAndGet), 2000),
                    "stop() (the UI thread in onStop) waited for a start whose work holds the library's lock");
            check(ended.get() == 1, "stop() ran its end action");
        } finally {
            release.countDown();
        }
        bridge.join(10_000);
        check(!bridge.isAlive(), "the start finished");
        check(NOT_SHOWN.equals(answer.get()), "a start overtaken by stop() answers not shown, got " + answer.get());
        check(undone.get() == 1, "and undoes its token and server once, got " + undone.get());
    }

    /** Start-up clean-up holds the library's lock: a start waits for it, stop() does not. */
    static void stopNeverWaitsForTheCleanUp() throws Exception {
        UploadGate gate = new UploadGate();
        Object library = new Object();
        CountDownLatch cleaning = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        Thread cleanup = new Thread(() -> {
            synchronized (library) { // PendingPhotos.removeStale()
                cleaning.countDown();
                try {
                    cleaned.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "cleanframe-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
        check(cleaning.await(10, TimeUnit.SECONDS), "the clean-up holds the library");
        AtomicInteger undone = new AtomicInteger();
        AtomicReference<String> answer = new AtomicReference<>();
        Thread bridge = new Thread(() -> answer.set(gate.start(() -> true, NOT_SHOWN, () -> {
            synchronized (library) {
                return OK;
            }
        }, OK::equals, undone::incrementAndGet)), "bridge");
        bridge.setDaemon(true);
        try {
            bridge.start();
            long end = System.nanoTime() + 10_000_000_000L;
            while (bridge.getState() != Thread.State.BLOCKED && bridge.isAlive() && System.nanoTime() < end) Thread.sleep(1);
            check(bridge.getState() == Thread.State.BLOCKED, "the start waits for the clean-up");
            check(finishesWithin(() -> gate.stop(() -> { }), 2000), "stop() waited for the clean-up behind a start");
        } finally {
            cleaned.countDown();
        }
        bridge.join(10_000);
        check(NOT_SHOWN.equals(answer.get()) && undone.get() == 1, "the overtaken start undid itself: " + answer.get() + ", " + undone.get());
    }

    static void keptWhenNothingStopped() {
        UploadGate gate = new UploadGate();
        AtomicInteger undone = new AtomicInteger();
        check(OK.equals(gate.start(() -> true, NOT_SHOWN, () -> OK, OK::equals, undone::incrementAndGet)), "kept");
        check(undone.get() == 0, "not undone");
        // a stop before the start does not count against it
        gate.stop(() -> { });
        check(OK.equals(gate.start(() -> true, NOT_SHOWN, () -> OK, OK::equals, undone::incrementAndGet)), "kept after an earlier stop");
        check(undone.get() == 0, "still not undone");
    }

    static void refusedWhenNotShown() {
        UploadGate gate = new UploadGate();
        AtomicBoolean ran = new AtomicBoolean();
        String a = gate.start(() -> false, NOT_SHOWN, () -> {
            ran.set(true);
            return OK;
        }, OK::equals, () -> ran.set(true));
        check(NOT_SHOWN.equals(a) && !ran.get(), "off screen or a dropped page: refused before any work");
    }

    static void hiddenDuringWorkIsUndone() {
        UploadGate gate = new UploadGate();
        AtomicBoolean shown = new AtomicBoolean(true);
        AtomicInteger undone = new AtomicInteger();
        String a = gate.start(shown::get, NOT_SHOWN, () -> {
            shown.set(false); // the page was dropped (web changed) while the token was written
            return OK;
        }, OK::equals, undone::incrementAndGet);
        check(NOT_SHOWN.equals(a) && undone.get() == 1, "not shown any more at the end: undone");
    }

    static void failedWorkIsReturnedAsItIs() {
        UploadGate gate = new UploadGate();
        AtomicInteger undone = new AtomicInteger();
        String full = "{\"ok\":false,\"reason\":\"full\"}";
        String a = gate.start(() -> true, NOT_SHOWN, () -> {
            gate.stop(() -> { });
            return full;
        }, OK::equals, undone::incrementAndGet);
        check(full.equals(a) && undone.get() == 0, "a refused start made nothing: its own answer, no undo");
    }

    /** Starts are serialised, so one start's undo can never end another start's token or server. */
    static void startsOneAtATime() throws Exception {
        UploadGate gate = new UploadGate();
        AtomicInteger inside = new AtomicInteger(), most = new AtomicInteger();
        Thread[] ts = new Thread[4];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> gate.start(() -> true, NOT_SHOWN, () -> {
                most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inside.decrementAndGet();
                return OK;
            }, OK::equals, () -> { }));
            ts[i].start();
        }
        for (Thread t : ts) t.join(10_000);
        check(most.get() == 1, "two starts worked at once: " + most.get());
    }

    /**
     * Review 7c2b1e5: uploadStart waited up to 30 s for the start-up clean-up inside a synchronous bridge
     * call. awaitDone waits only as long as it is told, then says "not yet" (the page shows busy).
     */
    static void cleanupWaitIsShort() throws Exception {
        check(UploadGate.awaitDone(null, 10), "no clean-up: done");
        java.util.concurrent.FutureTask<Void> done = new java.util.concurrent.FutureTask<>(() -> null);
        done.run();
        check(UploadGate.awaitDone(done, 10), "a finished clean-up: done");
        java.util.concurrent.FutureTask<Void> failed = new java.util.concurrent.FutureTask<>(() -> {
            throw new java.io.IOException("disk");
        });
        failed.run();
        check(UploadGate.awaitDone(failed, 10), "a failed clean-up is left behind: done");
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.FutureTask<Void> slow = new java.util.concurrent.FutureTask<>(() -> {
            release.await(30, TimeUnit.SECONDS);
            return null;
        });
        Thread t = new Thread(slow, "cleanframe-cleanup");
        t.setDaemon(true);
        t.start();
        long t0 = System.nanoTime();
        boolean got = UploadGate.awaitDone(slow, 200);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(!got, "a clean-up still running: not done");
        check(ms >= 150 && ms < 5_000, "waited about as long as asked: " + ms + " ms");
        release.countDown();
        t.join(10_000);
        check(UploadGate.awaitDone(slow, 10), "done once it ends");
    }

    public static void main(String[] args) throws Exception {
        String[] names = {"stopNeverWaitsForTheLibrary", "stopNeverWaitsForTheCleanUp", "keptWhenNothingStopped", "refusedWhenNotShown",
                "hiddenDuringWorkIsUndone", "failedWorkIsReturnedAsItIs", "startsOneAtATime", "cleanupWaitIsShort"};
        int failed = 0;
        for (String n : names) {
            try {
                UploadGateTest.class.getDeclaredMethod(n).invoke(null);
                System.out.println("ok   " + n);
            } catch (java.lang.reflect.InvocationTargetException e) {
                failed++;
                Throwable c = e.getCause();
                System.out.println("FAIL " + n + ": " + (c instanceof FallbackPolicyTest.Fail ? c.getMessage() : c));
            }
        }
        System.out.println((names.length - failed) + "/" + names.length + " passed");
        if (failed > 0) System.exit(1);
    }
}
