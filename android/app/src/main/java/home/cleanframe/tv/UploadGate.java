package home.cleanframe.tv;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Orders the Add photo screen's start (Bridge.uploadStart, on WebView's JavaBridge thread) against its
 * stop (MainActivity.stopUpload, on the UI thread from onStop and from every page drop). Plain Java,
 * tested by tests/java/UploadGateTest.java (tests/fallback.test.sh).
 *
 * The stop must never wait for the start's slow part. That part writes the token under the library's
 * lock, which the start-up clean-up (removeStale, on its own thread) or a scan of every group and photo
 * (photoRoom) can hold for seconds; the UI thread waiting there in onStop is an ANR on the stick's only
 * launcher (review 999ba07, L1, where uploadStart held the lock stopUpload takes while it did all that).
 * So:
 *  - stop() holds only this gate's own lock, never held across anything slower than a flag check
 *    or stop()'s own end action (deleting the token file, closing the server socket);
 *  - start() checks shown() under that lock and notes how many stops there have been, then runs its
 *    work with none of this gate's locks held that stop() needs (starts are serialised among
 *    themselves by a second lock the UI thread never takes), then, under the lock again, keeps the
 *    work only if no stop() ran in the meantime and the screen is still shown. Otherwise it undoes
 *    the work (outside the lock: the undo takes the library's lock) and answers notShown. A token
 *    and server the work made are therefore always ended, by the next stop() or by the undo.
 */
final class UploadGate {
    /** Held by stop() and by start()'s two short checks only. */
    private final Object lock = new Object();
    /** One start at a time, so an undo can never end another start's work. The UI thread never takes it. */
    private final Object starts = new Object();
    /** How many times stop() has run (guarded by lock). */
    private long stops;

    /**
     * Whether task (the start-up clean-up; null: none) is over within ms. A clean-up that failed counts
     * as over (it is left behind); one still running after ms, or an interrupt, is not. Bridge.uploadStart
     * runs inside a synchronous @JavascriptInterface call, so it may wait only briefly (review 7c2b1e5:
     * 30 s there let WebView report the renderer unresponsive and HangWatch kill it); when this says
     * no it answers MainActivity.BUSY and the page offers Try again.
     */
    static boolean awaitDone(Future<?> task, long ms) {
        if (task == null) return true;
        try {
            task.get(ms, TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException e) {
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) { // CancellationException: never started, nothing more will run
            return true;
        }
    }

    /** Ends the screen: end runs under the gate's lock (it must be quick), and any start in progress is undone. */
    void stop(Runnable end) {
        synchronized (lock) {
            stops++;
            end.run();
        }
    }

    /**
     * notShown unless shown() (else work never runs); then work's answer. An answer ok() accepts is kept
     * only if no stop() ran while work did and shown() still holds; otherwise undo runs and the answer is
     * notShown. An answer ok() refuses is returned as it is (work has already cleaned up after itself).
     */
    String start(BooleanSupplier shown, String notShown, Supplier<String> work, Predicate<String> ok, Runnable undo) {
        synchronized (starts) {
            long seen;
            synchronized (lock) {
                if (!shown.getAsBoolean()) return notShown;
                seen = stops;
            }
            String answer = work.get(); // no lock stop() takes is held here
            if (!ok.test(answer)) return answer;
            synchronized (lock) {
                if (stops == seen && shown.getAsBoolean()) return answer;
            }
            undo.run();
            return notShown;
        }
    }
}
