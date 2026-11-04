package home.cleanframe.tv;

import java.util.ArrayList;
import java.util.List;

/**
 * Behavioural tests for FallbackPolicy (MainActivity's fallback/recovery decisions), with a fake
 * clock and start-count store. Plain Java (8+), no JUnit: compiled and run by tests/fallback.test.sh.
 * Prints one line per case and a total; exits 1 on any failure.
 */
public final class FallbackPolicyTest {
    static final class FakeClock implements FallbackPolicy.Clock {
        long t = 1_000_000; // elapsedRealtime is never 0 on a running device

        public long now() {
            return t;
        }

        void advance(long ms) {
            t += ms;
        }
    }

    static final class FakeStore implements FallbackPolicy.Store {
        int n;
        final List<Integer> saves = new ArrayList<>();

        FakeStore(int n) {
            this.n = n;
        }

        public int failedStarts() {
            return n;
        }

        public void saveFailedStarts(int v) {
            n = v;
            saves.add(v);
        }
    }

    static final class Fail extends RuntimeException {
        Fail(String m) {
            super(m);
        }
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new Fail(msg);
    }

    static void eq(Object got, Object want, String what) {
        if (got == null ? want != null : !got.equals(want)) throw new Fail(what + ": got " + got + ", want " + want);
    }

    FakeClock clock;
    FakeStore store;
    FallbackPolicy p;

    /** A policy in a fresh process (counted start), page started and Home in front. */
    void fresh() {
        clock = new FakeClock();
        store = new FakeStore(0);
        p = new FallbackPolicy(clock, store);
        eq(p.created(true), null, "fresh start");
        p.pageStarted();
        p.resumed();
        p.readyChecksStarted();
    }

    /** A page that drew. */
    void freshDrawn() {
        fresh();
        check(p.pageDrew(), "first pageDrew() is new");
    }

    // ---------- Crash window ----------

    void crashesInWindow() {
        freshDrawn();
        eq(p.renderGone(true), FallbackPolicy.Gone.REBUILD_NOW, "crash 1");
        p.pageStarted();
        clock.advance(60_000);
        eq(p.renderGone(true), FallbackPolicy.Gone.REBUILD_NOW, "crash 2");
        p.pageStarted();
        clock.advance(60_000);
        eq(p.renderGone(true), FallbackPolicy.Gone.FALLBACK, "crash 3 within 10 min");
        eq(p.reason(), "CleanFrame's page crashed 3 times in 10 minutes.", "crash 3 reason");
    }

    void crashWindowSlides() {
        freshDrawn();
        p.renderGone(true);
        p.pageStarted();
        clock.advance(5 * 60_000);
        p.renderGone(true);
        p.pageStarted();
        clock.advance(5 * 60_000 + 1); // the first crash is now 10 min + 1 ms old
        eq(p.renderGone(true), FallbackPolicy.Gone.REBUILD_NOW, "3rd crash with the 1st out of the window");
        p.pageStarted();
        clock.advance(1);
        eq(p.renderGone(true), FallbackPolicy.Gone.FALLBACK, "3 crashes in the window again");
    }

    void crashExactlyAtWindowEdgeStillCounts() {
        freshDrawn();
        p.renderGone(true);
        p.pageStarted();
        p.renderGone(true);
        p.pageStarted();
        clock.advance(FallbackPolicy.RENDER_DEATH_WINDOW_MS); // exactly 10 min: still inside
        eq(p.renderGone(true), FallbackPolicy.Gone.FALLBACK, "crash at exactly 10 min counts");
    }

    void crashInBackgroundCountsAndRebuildsOnResume() {
        freshDrawn();
        p.paused();
        eq(p.renderGone(true), FallbackPolicy.Gone.REBUILD_ON_RESUME, "crash behind another app");
        check(p.resumed(), "resume rebuilds after a background crash");
        p.pageStarted();
        p.renderGone(true);
        p.pageStarted();
        eq(p.renderGone(true), FallbackPolicy.Gone.FALLBACK, "background crash counted toward 3");
    }

    // ---------- Memory kills ----------

    void frontMemoryKillLimit() {
        freshDrawn();
        for (int i = 1; i < FallbackPolicy.MAX_FRONT_MEMORY_KILLS; i++) {
            eq(p.renderGone(false), FallbackPolicy.Gone.REBUILD_NOW, "front memory kill " + i);
            p.pageStarted();
            clock.advance(10_000);
        }
        eq(p.renderGone(false), FallbackPolicy.Gone.FALLBACK, "front memory kill 5");
        check(p.reason().startsWith("CleanFrame's page ran out of memory 5 times in 10 minutes"), "kill reason: " + p.reason());
    }

    void backgroundMemoryKillsUnlimited() {
        freshDrawn();
        for (int i = 1; i <= 50; i++) {
            p.paused();
            eq(p.renderGone(false), FallbackPolicy.Gone.REBUILD_ON_RESUME, "background kill " + i);
            check(p.resumed(), "rebuilt on resume after background kill " + i);
            p.pageStarted();
        }
        // ...and they did not count toward the front limit or the crash limit.
        for (int i = 1; i < FallbackPolicy.MAX_FRONT_MEMORY_KILLS; i++) {
            eq(p.renderGone(false), FallbackPolicy.Gone.REBUILD_NOW, "front kill " + i + " after background kills");
            p.pageStarted();
        }
    }

    void memoryKillsAndCrashesCountedApart() {
        freshDrawn();
        p.renderGone(true);
        p.pageStarted();
        p.renderGone(true);
        p.pageStarted();
        for (int i = 0; i < FallbackPolicy.MAX_FRONT_MEMORY_KILLS - 1; i++) {
            eq(p.renderGone(false), FallbackPolicy.Gone.REBUILD_NOW, "kill " + i + " after 2 crashes");
            p.pageStarted();
        }
    }

    // ---------- Start guard ----------

    void startGuardThreeFailedStarts() {
        clock = new FakeClock();
        store = new FakeStore(0);
        for (int start = 1; start <= FallbackPolicy.MAX_FAILED_STARTS; start++) {
            p = new FallbackPolicy(clock, store); // a new process each time
            eq(p.created(true), null, "start " + start + " runs the page");
            eq(store.n, start, "count after start " + start);
            p.pageStarted();
            p.resumed();
            // ...and dies before it drew
        }
        p = new FallbackPolicy(clock, store);
        eq(p.created(true), "CleanFrame stopped starting 3 times.", "4th start after 3 failed");
        p.fallbackShown(false);
        p.tryAgain(false);
        eq(store.n, 0, "Try again resets the start count");
        p.pageStarted();
        eq(p.home(false), FallbackPolicy.Home.STAY, "the restarted page is not a failure fallback");
    }

    void startGuardResetByDraw() {
        clock = new FakeClock();
        store = new FakeStore(2);
        p = new FallbackPolicy(clock, store);
        eq(p.created(true), null, "3rd start runs");
        eq(store.n, 3, "counted");
        p.pageStarted();
        p.resumed();
        check(p.pageDrew(), "drew");
        eq(store.n, 3, "drawing alone does not reset the count");
        check(!p.pageDrew(), "a second pageDrew() is not new");
        clock.advance(FallbackPolicy.STABLE_MS - 1);
        eq(p.stableLeft(), 1L, "1 ms short of lasting");
        p.stableDue();
        eq(store.n, 3, "not yet");
        clock.advance(1);
        p.stableDue();
        eq(store.n, 0, "a start that lasted resets the count");
        eq(p.stableLeft(), 0L, "and the timer stops");
        eq(store.saves.size(), 2, "one save per change (3, then 0)");
    }

    /** One process start that draws its page, then dies aliveMs later, on screen throughout. */
    String startDrawDie(FakeStore s, long aliveMs) {
        p = new FallbackPolicy(clock, s);
        String why = p.created(true);
        if (why != null) return why;
        p.pageStarted();
        p.resumed();
        p.pageDrew();
        clock.advance(aliveMs);
        p.stableDue(); // the timer, if it was due
        return null; // the process dies: nothing more reaches the policy
    }

    /** The bug: a process dying just after each draw cleared the count each time, and looped for ever. */
    void startGuardCatchesDeathRightAfterDraw() {
        clock = new FakeClock();
        FakeStore s = new FakeStore(0);
        for (int i = 1; i <= FallbackPolicy.MAX_FAILED_STARTS; i++) {
            eq(startDrawDie(s, 2_000), null, "start " + i);
            eq(s.n, i, "counted");
        }
        check(startDrawDie(s, 2_000) != null, "the next start is the fallback");
        check(p.reason().contains("stopped starting"), "says so: " + p.reason());
    }

    void startGuardClearedByADrawThatLasts() {
        clock = new FakeClock();
        FakeStore s = new FakeStore(0);
        eq(startDrawDie(s, 2_000), null, "early death");
        eq(startDrawDie(s, 2_000), null, "early death");
        eq(startDrawDie(s, FallbackPolicy.STABLE_MS), null, "this one lasts");
        eq(s.n, 0, "cleared");
        for (int i = 1; i <= FallbackPolicy.MAX_FAILED_STARTS; i++) eq(startDrawDie(s, 2_000), null, "counted from zero again");
    }

    /** Only time on screen counts, summed over stretches: a Home press's pause loses nothing. */
    void stableCountsOnlyOnScreen() {
        freshDrawn();
        clock.advance(10_000);
        p.paused();
        eq(p.stableLeft(), 0L, "no timer while paused");
        clock.advance(600_000);
        p.stableDue();
        eq(store.n, 1, "time behind another app does not count");
        p.resumed();
        eq(p.stableLeft(), FallbackPolicy.STABLE_MS - 10_000, "what is left");
        clock.advance(FallbackPolicy.STABLE_MS - 10_000);
        p.paused(); // the time ran out just as Home paused, before the timer: no resume needed to see it
        eq(store.n, 0, "cleared");
    }

    /** Leaving Home for an app with the page drawn: three quick trips, each killing it, are no failure. */
    void leavingWithPageDrawnIsAStartThatWorked() {
        clock = new FakeClock();
        FakeStore s = new FakeStore(0);
        for (int i = 0; i < 2 * FallbackPolicy.MAX_FAILED_STARTS; i++) {
            eq(startDrawDie(s, 5_000), null, "trip " + i);
            eq(s.n, 1, "counted");
            p.paused();
            p.stopped();
            eq(s.n, 0, "left with the page drawn: it worked");
        }
    }

    void leavingBeforeTheDrawIsNot() {
        fresh();
        p.paused();
        p.stopped();
        eq(store.n, 1, "no page, nothing shown to work");
        check(p.pageDrew(), "the last probe's answer comes in after Home left");
        eq(store.n, 0, "the page was drawn behind the app: it did work");
    }

    /** A page gone, or behind the native list, is not on screen. */
    void stableStopsWhenThePageGoes() {
        freshDrawn();
        clock.advance(5_000);
        eq(p.renderGone(true), FallbackPolicy.Gone.REBUILD_NOW, "crash");
        eq(p.stableLeft(), 0L, "stopped with the page");
        clock.advance(FallbackPolicy.STABLE_MS);
        p.stableDue();
        eq(store.n, 1, "no page, no credit");
        p.paused();
        p.stopped();
        eq(store.n, 1, "and leaving now says nothing");
        p.resumed();
        p.pageStarted();
        p.pageDrew();
        eq(p.stableLeft(), FallbackPolicy.STABLE_MS, "a new page starts from zero");
        p.fallbackShown(true);
        eq(p.stableLeft(), 0L, "not under the native list");
        p.paused();
        p.resumed();
        eq(p.stableLeft(), 0L, "nor after a pause there");
        clock.advance(FallbackPolicy.STABLE_MS);
        p.stableDue();
        eq(store.n, 1, "no credit there");
    }

    void startGuardCountsOncePerProcess() {
        clock = new FakeClock();
        store = new FakeStore(0);
        p = new FallbackPolicy(clock, store);
        p.created(true);
        for (int i = 0; i < 5; i++) {
            p = new FallbackPolicy(clock, store); // activity re-created in the same process
            eq(p.created(false), null, "re-created activity " + i);
        }
        eq(store.n, 1, "one count per process");
    }

    // ---------- Ready deadline ----------

    void readyDeadline60s() {
        fresh();
        clock.advance(FallbackPolicy.READY_TIMEOUT_MS - 1);
        eq(p.readyDeadline(), null, "59.999 s on screen");
        eq(p.readyLeft(), 1L, "1 ms left");
        clock.advance(1);
        eq(p.readyDeadline(), "CleanFrame's page did not start.", "60 s on screen, never drew");
    }

    void readyDeadlineCountsOnlyOnScreen() {
        fresh();
        clock.advance(50_000);
        p.paused();
        p.readyChecksStopped();
        clock.advance(10 * 60_000); // behind another app
        eq(p.readyDeadline(), null, "no deadline while hidden");
        p.resumed();
        check(p.readyChecksStarted(), "checks restart on resume");
        eq(p.readyLeft(), 10_000L, "the 50 s already on screen are kept, the 10 min hidden are not counted");
        clock.advance(9_999);
        eq(p.readyDeadline(), null, "59.999 s on screen in all");
        clock.advance(1);
        check(p.readyDeadline() != null, "60 s on screen in all, across the pause");
    }

    /** Review 30e7356 (G3): each Home press (pause, resume ~5 ms later) restarted the full 60 s. */
    void readyDeadlineNotPostponedByHome() {
        fresh();
        for (int i = 0; i < 100; i++) { // Home every 5 s for 500 s on a page that never draws
            clock.advance(5_000);
            p.paused();
            p.readyChecksStopped();
            clock.advance(5);
            p.resumed();
            p.readyChecksStarted();
            if (p.readyDeadline() != null) {
                check(i >= 11, "fell back after only " + i + " presses");
                return;
            }
        }
        throw new Fail("pressing Home every 5 s kept a page that never draws off the fallback for 500 s");
    }

    void readyDeadlineNewPageStartsFresh() {
        fresh();
        clock.advance(40_000);
        p.readyChecksStopped();
        p.pageStarted(); // rebuilt (a crash, Try again)
        check(p.readyChecksStarted(), "checks start for the new page");
        eq(p.readyLeft(), FallbackPolicy.READY_TIMEOUT_MS, "a new page gets the full 60 s");
    }

    void readyDeadlineNotAfterDraw() {
        fresh();
        clock.advance(30_000);
        p.pageDrew();
        clock.advance(60_000);
        eq(p.readyDeadline(), null, "drawn page: no deadline");
        check(!p.readyChecksStarted(), "no checks once drawn");
    }

    // ---------- Hang ----------

    void hangTerminateThenCountsAsCrash() {
        freshDrawn();
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "report 1");
        clock.advance(5_000);
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "report 2 at 5 s");
        clock.advance(4_999);
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "9.999 s after the first");
        clock.advance(1);
        eq(p.unresponsive(), FallbackPolicy.Hang.TERMINATE, "10 s after the first report");
        check(p.terminated(true), "terminate succeeded");
        eq(p.renderGone(false), FallbackPolicy.Gone.REBUILD_NOW, "hung renderer gone (didCrash false) -> rebuild");
        p.pageStarted();
        p.renderGone(true);
        p.pageStarted();
        // a third death: a hang
        p.unresponsive();
        clock.advance(10_000);
        eq(p.unresponsive(), FallbackPolicy.Hang.TERMINATE, "second hang");
        p.terminated(true);
        eq(p.renderGone(false), FallbackPolicy.Gone.FALLBACK, "hang counted as crash 3");
        eq(p.reason(), "CleanFrame's page stopped responding (3 crashes or hangs in 10 minutes).", "hang reason");
    }

    void hangCannotTerminate() {
        freshDrawn();
        p.unresponsive();
        clock.advance(10_000);
        eq(p.unresponsive(), FallbackPolicy.Hang.TERMINATE, "terminate asked");
        check(!p.terminated(false), "terminate failed");
        eq(p.reason(), "CleanFrame's page stopped responding.", "reason");
    }

    void hangSurvivesTerminate() {
        freshDrawn();
        p.unresponsive();
        clock.advance(10_000);
        p.unresponsive();
        p.terminated(true);
        clock.advance(5_000);
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "5 s after terminate");
        clock.advance(5_000);
        eq(p.unresponsive(), FallbackPolicy.Hang.FALLBACK, "still hung 10 s after terminate");
    }

    void hangClockOnlyInFront() {
        freshDrawn();
        p.unresponsive();
        clock.advance(8_000);
        p.paused();
        clock.advance(60_000);
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "hidden: no action");
        p.resumed();
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "first report after resume restarts the clock");
        clock.advance(9_999);
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "9.999 s after resume");
        clock.advance(1);
        eq(p.unresponsive(), FallbackPolicy.Hang.TERMINATE, "10 s in front");
    }

    void pauseResetsHangClock() {
        freshDrawn();
        p.unresponsive();
        clock.advance(8_000);
        p.paused(); // no report while hidden
        clock.advance(FallbackPolicy.SHORT_PAUSE_MS + 1); // hidden, not the pause around a Home press
        p.resumed();
        clock.advance(2_000);
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "first report after a pause is a new first report");
        clock.advance(9_999);
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "9.999 s after it");
    }

    void responsiveResetsHang() {
        freshDrawn();
        p.unresponsive();
        clock.advance(9_000);
        p.responsive();
        clock.advance(5_000);
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "a new first report");
        clock.advance(9_999);
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "not 10 s since the new first report");
    }

    void newPageClearsHangState() {
        freshDrawn();
        p.unresponsive();
        clock.advance(10_000);
        p.unresponsive();
        p.terminated(true);
        p.renderGone(false);
        p.pageStarted();
        p.renderGone(false); // a memory kill of the NEW page is not a hang
        eq(p.reason(), "", "no fallback");
        p.pageStarted();
        p.unresponsive();
        clock.advance(10_000);
        eq(p.unresponsive(), FallbackPolicy.Hang.TERMINATE, "a new page's hang terminates first");
    }

    // ---------- Home/Back ping (a frozen page found when only Home or Back is pressed) ----------

    /** One Home/Back press on a frozen page, then the activity's pingCheck every PING_REPORT_MS. */
    FallbackPolicy.Hang pingTick() {
        clock.advance(FallbackPolicy.PING_REPORT_MS);
        check(p.pingOverdue(), "ping overdue after " + FallbackPolicy.PING_REPORT_MS + " ms");
        return p.unresponsive();
    }

    void pingOnFrozenPageTerminates() {
        freshDrawn();
        check(p.pingNeeded(), "Home press sends a ping");
        check(!p.pingNeeded(), "a second press while one is outstanding sends none");
        clock.advance(FallbackPolicy.PING_REPORT_MS - 1);
        check(!p.pingOverdue(), "not overdue at 4.999 s");
        clock.advance(-(FallbackPolicy.PING_REPORT_MS - 1));
        eq(pingTick(), FallbackPolicy.Hang.WAIT, "5 s: first report");
        eq(pingTick(), FallbackPolicy.Hang.WAIT, "10 s");
        eq(pingTick(), FallbackPolicy.Hang.TERMINATE, "15 s unanswered: terminate");
        check(p.terminated(true), "terminated");
        eq(p.renderGone(false), FallbackPolicy.Gone.REBUILD_NOW, "counted as a crash, rebuilt");
        p.pageStarted();
        check(!p.pingOutstanding(), "a new page has no ping outstanding");
    }

    void pingThirdFrozenPageFallsBack() {
        freshDrawn();
        for (int i = 1; i <= 3; i++) {
            check(p.pingNeeded(), "press " + i);
            pingTick();
            pingTick();
            eq(pingTick(), FallbackPolicy.Hang.TERMINATE, "hang " + i + " terminated");
            p.terminated(true);
            FallbackPolicy.Gone g = p.renderGone(false);
            if (i < 3) {
                eq(g, FallbackPolicy.Gone.REBUILD_NOW, "hang " + i + " rebuilt");
                p.pageStarted();
            } else {
                eq(g, FallbackPolicy.Gone.FALLBACK, "3rd hang -> fallback");
            }
        }
        eq(p.reason(), "CleanFrame's page stopped responding (3 crashes or hangs in 10 minutes).", "reason");
    }

    void pongClearsPing() {
        freshDrawn();
        check(p.pingNeeded(), "press");
        clock.advance(FallbackPolicy.PING_REPORT_MS);
        p.unresponsive();
        clock.advance(1_000);
        p.pong();
        check(!p.pingOverdue() && !p.pingOutstanding(), "answered");
        clock.advance(60_000);
        check(!p.pingOverdue(), "no report after the answer");
        p.unresponsive();
        clock.advance(9_999);
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "the answer reset the hang clock");
        check(p.pingNeeded(), "the next press sends a new ping");
    }

    void noPingBehindAnotherApp() {
        freshDrawn();
        p.paused();
        check(!p.pingNeeded(), "no ping while Home is hidden");
        p.resumed();
        check(p.pingNeeded(), "ping in front");
        p.paused();
        clock.advance(FallbackPolicy.SHORT_PAUSE_MS + 1);
        p.resumed();
        check(!p.pingOutstanding(), "leaving Home (longer than SHORT_PAUSE_MS) drops the ping");
    }

    // ---------- Home on Android 10+: pause, onNewIntent, resume (measured on the stick) ----------

    /** The pause around a Home press, as measured on the stick (onPause -> onResume about 5 ms). */
    static final long HOME_PAUSE_MS = 5;

    /** MainActivity's pingCheck: when it next runs (absolute clock time), or -1 when not armed. */
    long checkAt = -1;
    /** Clock time of the TERMINATE answer, or -1. */
    long terminatedAt = -1;

    /** A Home press as Android 10+ delivers it: onPause, onNewIntent (pingPage), onResume (resumePing). */
    void homePress() {
        p.paused();
        checkAt = -1; // onPause: ui.removeCallbacks(pingCheck)
        clock.advance(HOME_PAUSE_MS);
        eq(p.home(true), FallbackPolicy.Home.TO_DOCK, "Home on a live page");
        if (p.pingNeeded()) checkAt = clock.now() + FallbackPolicy.PING_REPORT_MS; // onNewIntent: pingPage()
        check(!p.resumed(), "no rebuild after a Home press"); // onResume
        if (p.pingPending()) { // resumePing()
            check(p.pingNeeded(), "the pending Home ping is sent once resumed");
            checkAt = clock.now() + FallbackPolicy.PING_REPORT_MS;
        } else if (p.pingOutstanding()) {
            checkAt = clock.now() + p.pingCheckIn();
        }
    }

    /** A Back press (in front: no pause). */
    void backPress() {
        if (p.pingNeeded()) checkAt = clock.now() + FallbackPolicy.PING_REPORT_MS;
    }

    /** One pingCheck run, as MainActivity's runnable: report an overdue ping, re-arm while outstanding. */
    void runPingCheck() {
        checkAt = -1;
        if (!p.pingOutstanding()) return;
        if (p.pingOverdue() && hangReport() == FallbackPolicy.Hang.TERMINATE) return;
        if (p.pingOutstanding()) checkAt = clock.now() + p.pingCheckIn();
    }

    FallbackPolicy.Hang hangReport() {
        FallbackPolicy.Hang h = p.unresponsive();
        if (h == FallbackPolicy.Hang.TERMINATE && terminatedAt < 0) terminatedAt = clock.now();
        return h;
    }

    /**
     * Run the frozen page's clock from now until until (absolute): pingCheck when armed, a press every
     * pressEvery ms (0: none; the first one pressEvery after now), and WebView's own report every
     * webEvery ms (0: none). Stops at the first TERMINATE.
     */
    void runFrozen(long until, long pressEvery, Runnable press, long webEvery) {
        long nextPress = pressEvery > 0 ? clock.now() + pressEvery : Long.MAX_VALUE;
        long nextWeb = webEvery > 0 ? clock.now() + webEvery : Long.MAX_VALUE;
        while (terminatedAt < 0) {
            long next = Math.min(Math.min(nextPress, nextWeb), checkAt < 0 ? Long.MAX_VALUE : checkAt);
            if (next > until) return;
            clock.advance(next - clock.now());
            if (next == checkAt) {
                runPingCheck();
            } else if (next == nextWeb) {
                hangReport();
                nextWeb += webEvery;
            } else {
                press.run();
                nextPress += pressEvery;
            }
        }
    }

    /** Milliseconds from one press on a frozen page to TERMINATE (no further presses). */
    long timeToTerminate(Runnable press) {
        freshDrawn();
        terminatedAt = -1;
        checkAt = -1;
        long t0 = clock.now();
        press.run();
        runFrozen(t0 + 120_000, 0, null, 0);
        check(terminatedAt >= 0, "frozen page never terminated in 120 s");
        return terminatedAt - t0;
    }

    void homeOnlyPingSentAfterResume() {
        freshDrawn();
        p.paused();
        clock.advance(HOME_PAUSE_MS);
        check(!p.pingNeeded(), "Home between pause and resume: no ping yet (not in front)");
        check(!p.pingPending(), "nothing sent while still paused");
        check(!p.resumed(), "resume");
        check(p.pingPending(), "resumed: the Home press's ping is due");
        check(!p.pingPending(), "only once");
        check(p.pingNeeded(), "the ping is sent");
        check(p.pingOutstanding(), "and outstanding");
    }

    void homeOnlyFrozenPageTerminatedLikeBack() {
        long back = timeToTerminate(this::backPress);
        eq(back, 3 * FallbackPolicy.PING_REPORT_MS, "Back: ms to terminate");
        long home = timeToTerminate(this::homePress);
        eq(home, back + HOME_PAUSE_MS, "Home only: ms to terminate (Back's, after the 5 ms pause)");
        check(p.terminated(true), "terminated");
        eq(p.renderGone(false), FallbackPolicy.Gone.REBUILD_NOW, "counted as a crash, rebuilt");
    }

    void repeatedHomeDoesNotPostponeTermination() {
        for (long every : new long[] {1_000, 3_000, 4_999}) {
            freshDrawn();
            terminatedAt = -1;
            checkAt = -1;
            long t0 = clock.now();
            homePress();
            runFrozen(t0 + 120_000, every, this::homePress, 0);
            check(terminatedAt >= 0, "Home every " + every + " ms: frozen page never terminated");
            long took = terminatedAt - t0;
            // A single press terminates after 3 reports + its pause; a check that falls due during a
            // later press's pause runs when it ends, so each of the 3 reports may slip by one pause.
            long bound = 3 * FallbackPolicy.PING_REPORT_MS + HOME_PAUSE_MS + 3 * HOME_PAUSE_MS;
            check(took <= bound, "Home every " + every + " ms: terminated after " + took + " ms, want <= " + bound);
        }
    }

    void homePressesKeepWebViewHangClock() {
        // A D-pad press on a frozen page: WebView's own reports every 5 s; Home pressed every 4 s.
        freshDrawn();
        long t0 = clock.now();
        hangReport(); // WebView's first report
        runFrozen(t0 + 120_000, 4_000, this::homePress, FallbackPolicy.PING_REPORT_MS);
        check(terminatedAt >= 0, "never terminated while Home was pressed every 4 s");
        check(terminatedAt - t0 <= FallbackPolicy.HANG_TIMEOUT_MS + HOME_PAUSE_MS,
                "terminated " + (terminatedAt - t0) + " ms after the first report, not HANG_TIMEOUT_MS");
    }

    void shortPauseBoundary() {
        freshDrawn();
        p.unresponsive();
        p.paused();
        clock.advance(FallbackPolicy.SHORT_PAUSE_MS);
        p.resumed();
        clock.advance(FallbackPolicy.HANG_TIMEOUT_MS - FallbackPolicy.SHORT_PAUSE_MS);
        eq(p.unresponsive(), FallbackPolicy.Hang.TERMINATE, "a pause of exactly SHORT_PAUSE_MS keeps the clock");
        freshDrawn();
        p.unresponsive();
        check(p.pingNeeded(), "ping");
        p.paused();
        clock.advance(FallbackPolicy.SHORT_PAUSE_MS + 1);
        p.resumed();
        check(!p.pingOutstanding(), "a longer pause drops the ping");
        clock.advance(FallbackPolicy.HANG_TIMEOUT_MS);
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "a longer pause restarts the clock");
    }

    void reportWhilePausedIgnored() {
        freshDrawn();
        p.unresponsive();
        clock.advance(4_000);
        p.paused();
        eq(p.unresponsive(), FallbackPolicy.Hang.WAIT, "paused: ignored");
        p.resumed();
        clock.advance(6_000);
        eq(p.unresponsive(), FallbackPolicy.Hang.TERMINATE, "a report in the short pause did not restart the clock");
    }

    void newPageClearsPendingPing() {
        freshDrawn();
        p.paused();
        check(!p.pingNeeded(), "Home while paused");
        p.pageStarted();
        p.resumed();
        check(!p.pingPending(), "a new page drops the old page's pending ping");
        freshDrawn();
        p.paused();
        check(!p.pingNeeded(), "Home while paused");
        p.fallbackShown(false);
        p.resumed();
        check(!p.pingPending(), "the fallback drops the pending ping");
    }

    // ---------- Home retry, by hand, Try again, trim ----------

    void homeRetryAfter60s() {
        freshDrawn();
        p.renderGone(true);
        p.pageStarted();
        p.renderGone(true);
        p.pageStarted();
        eq(p.renderGone(true), FallbackPolicy.Gone.FALLBACK, "3 crashes");
        p.fallbackShown(false);
        eq(p.home(false), FallbackPolicy.Home.STAY, "Home at once keeps the fallback");
        clock.advance(FallbackPolicy.RETRY_COOLDOWN_MS - 1);
        eq(p.home(false), FallbackPolicy.Home.STAY, "Home at 59.999 s keeps the fallback");
        clock.advance(1);
        eq(p.home(false), FallbackPolicy.Home.START_PAGE, "Home at 60 s retries");
        p.pageStarted();
        eq(p.renderGone(true), FallbackPolicy.Gone.FALLBACK, "counts kept: one more crash brings it straight back");
        eq(p.reason(), "CleanFrame's page crashed 4 times in 10 minutes.", "4 crashes");
    }

    void homeOnLivePageGoesToDock() {
        freshDrawn();
        eq(p.home(true), FallbackPolicy.Home.TO_DOCK, "live page");
    }

    void byHandReturnsAtOnce() {
        freshDrawn();
        p.fallbackShown(true);
        check(p.byHand(), "by hand");
        eq(p.home(false), FallbackPolicy.Home.START_PAGE, "Home from the hand-opened list returns at once");
        p.tryAgain(true);
        eq(store.n, 1, "by hand: start count untouched");
        p.pageStarted();
        check(!p.byHand(), "cleared by a new page");
    }

    void byHandDoesNotResetStartCount() {
        clock = new FakeClock();
        store = new FakeStore(1);
        p = new FallbackPolicy(clock, store);
        p.created(true); // 2
        p.pageStarted();
        p.resumed();
        p.fallbackShown(true);
        p.tryAgain(true);
        eq(store.n, 2, "Back to CleanFrame keeps the start count (only a draw resets it)");
    }

    /** Two crashes and MAX_FRONT_MEMORY_KILLS - 1 front kills: one more of either is the failure list. */
    void oneDeathFromFallback() {
        freshDrawn();
        p.renderGone(true);
        p.pageStarted();
        p.renderGone(true);
        p.pageStarted();
        for (int i = 0; i < FallbackPolicy.MAX_FRONT_MEMORY_KILLS - 1; i++) {
            p.renderGone(false);
            p.pageStarted();
        }
    }

    void byHandBackClearsCounts() {
        oneDeathFromFallback();
        p.fallbackShown(true);
        check(p.back(), "Back leaves the hand-opened list for the page");
        p.pageStarted();
        eq(p.renderGone(true), FallbackPolicy.Gone.REBUILD_NOW, "after Back from the hand-opened list one crash is rebuilt");
        p.pageStarted();
        eq(p.renderGone(true), FallbackPolicy.Gone.REBUILD_NOW, "and a second (the crash count was cleared)");
        p.pageStarted();
        eq(p.renderGone(false), FallbackPolicy.Gone.REBUILD_NOW, "after Back one front kill is rebuilt (kill count cleared)");
        p.pageStarted();
        check(!p.byHand(), "the new page is not the hand-opened list");
        check(!p.back(), "Back with no list showing does not restart the page");
    }

    void byHandBackSameAsButton() {
        // The button's outcome...
        oneDeathFromFallback();
        p.fallbackShown(true);
        p.tryAgain(true);
        p.pageStarted();
        FallbackPolicy.Gone button = p.renderGone(true);
        // ...and Back's, from the same state.
        oneDeathFromFallback();
        p.fallbackShown(true);
        check(p.back(), "Back leaves the list");
        p.pageStarted();
        eq(p.renderGone(true), button, "Back from the hand-opened list ends as its 'Back to CleanFrame' button does");
        // The start count: kept by both (only a draw resets it).
        clock = new FakeClock();
        store = new FakeStore(1);
        p = new FallbackPolicy(clock, store);
        p.created(true); // 2
        p.pageStarted();
        p.resumed();
        p.fallbackShown(true);
        check(p.back(), "Back leaves the list");
        eq(store.n, 2, "Back keeps the start count, as the button does");
    }

    void byHandHomeKeepsCounts() {
        // Unchanged: only Back was made to clear the counts; Home from the hand-opened list keeps them.
        oneDeathFromFallback();
        p.fallbackShown(true);
        eq(p.home(false), FallbackPolicy.Home.START_PAGE, "Home returns at once");
        p.pageStarted();
        eq(p.renderGone(true), FallbackPolicy.Gone.FALLBACK, "Home kept the counts: one more crash is the 3rd");
    }

    void failureFallbacksIgnoreBack() {
        // "crashed 3 times"
        oneDeathFromFallback();
        eq(p.renderGone(true), FallbackPolicy.Gone.FALLBACK, "3rd crash");
        p.fallbackShown(false);
        check(!p.back(), "Back on 'crashed 3 times' does nothing");
        eq(p.home(false), FallbackPolicy.Home.STAY, "still the failure list after Back");
        clock.advance(FallbackPolicy.RETRY_COOLDOWN_MS);
        eq(p.home(false), FallbackPolicy.Home.START_PAGE, "Home retries after 60 s");
        p.pageStarted();
        eq(p.renderGone(true), FallbackPolicy.Gone.FALLBACK, "Back cleared nothing: one more crash brings it back");
        eq(p.reason(), "CleanFrame's page crashed 4 times in 10 minutes.", "4 crashes");
        // "stopped responding (3 crashes or hangs)"
        oneDeathFromFallback();
        p.unresponsive();
        clock.advance(FallbackPolicy.HANG_TIMEOUT_MS);
        eq(p.unresponsive(), FallbackPolicy.Hang.TERMINATE, "hang");
        p.terminated(true);
        eq(p.renderGone(false), FallbackPolicy.Gone.FALLBACK, "hang is the 3rd death");
        p.fallbackShown(false);
        check(!p.back(), "Back on 'stopped responding (3 crashes or hangs)' does nothing");
        clock.advance(FallbackPolicy.RETRY_COOLDOWN_MS);
        eq(p.home(false), FallbackPolicy.Home.START_PAGE, "Home retries after 60 s");
        p.pageStarted();
        eq(p.renderGone(true), FallbackPolicy.Gone.FALLBACK, "counts kept after Back");
        // "stopped responding" (terminate failed)
        freshDrawn();
        p.unresponsive();
        clock.advance(FallbackPolicy.HANG_TIMEOUT_MS);
        p.unresponsive();
        check(!p.terminated(false), "terminate failed");
        p.fallbackShown(false);
        check(!p.back(), "Back on 'stopped responding' does nothing");
        eq(p.home(false), FallbackPolicy.Home.STAY, "still the failure list after Back");
        // "stopped starting 3 times"
        clock = new FakeClock();
        store = new FakeStore(FallbackPolicy.MAX_FAILED_STARTS);
        p = new FallbackPolicy(clock, store);
        eq(p.created(true), "CleanFrame stopped starting 3 times.", "4th start");
        p.fallbackShown(false);
        check(!p.back(), "Back on 'stopped starting' does nothing");
        eq(store.n, FallbackPolicy.MAX_FAILED_STARTS + 1, "Back keeps the start count");
        eq(p.home(false), FallbackPolicy.Home.STAY, "still the failure list after Back");
    }

    void tryAgainClearsCounts() {
        freshDrawn();
        p.renderGone(true);
        p.pageStarted();
        p.renderGone(true);
        p.pageStarted();
        for (int i = 0; i < FallbackPolicy.MAX_FRONT_MEMORY_KILLS - 1; i++) {
            p.renderGone(false);
            p.pageStarted();
        }
        eq(p.renderGone(true), FallbackPolicy.Gone.FALLBACK, "3rd crash");
        p.fallbackShown(false);
        p.tryAgain(false);
        p.pageStarted();
        eq(p.renderGone(true), FallbackPolicy.Gone.REBUILD_NOW, "after Try again one crash is rebuilt");
        p.pageStarted();
        eq(p.renderGone(false), FallbackPolicy.Gone.REBUILD_NOW, "after Try again one front kill is rebuilt");
        p.pageStarted();
        eq(p.home(false), FallbackPolicy.Home.STAY, "a new page is not a failure fallback");
    }

    void trimMemoryOnlyInBackground() {
        freshDrawn();
        check(!p.trimMemory(true), "no drop while in front");
        p.paused();
        check(!p.trimMemory(false), "nothing to drop");
        check(p.trimMemory(true), "dropped behind another app");
        check(p.resumed(), "rebuilt on resume");
        p.pageStarted();
        p.paused();
        check(!p.resumed(), "no rebuild once a new page was started");
    }

    void fallbackClearsRebuild() {
        freshDrawn();
        p.paused();
        p.renderGone(false); // rebuild on resume
        p.fallbackShown(true);
        check(!p.resumed(), "a shown fallback is not replaced by a rebuild on resume");
    }

    // ---------- Runner ----------

    interface Case {
        void run(FallbackPolicyTest t);
    }

    public static void main(String[] args) {
        Object[][] cases = {
            {"crashes: 3 in 10 min -> fallback", (Case) FallbackPolicyTest::crashesInWindow},
            {"crashes: the 10 min window slides", (Case) FallbackPolicyTest::crashWindowSlides},
            {"crashes: one exactly 10 min old still counts", (Case) FallbackPolicyTest::crashExactlyAtWindowEdgeStillCounts},
            {"crashes: in the background count, rebuilt on resume", (Case) FallbackPolicyTest::crashInBackgroundCountsAndRebuildsOnResume},
            {"memory: 5 kills in front -> fallback", (Case) FallbackPolicyTest::frontMemoryKillLimit},
            {"memory: kills in the background are unlimited and uncounted", (Case) FallbackPolicyTest::backgroundMemoryKillsUnlimited},
            {"memory: kills and crashes are counted apart", (Case) FallbackPolicyTest::memoryKillsAndCrashesCountedApart},
            {"start guard: 3 failed starts -> 4th starts in the fallback; Try again resets", (Case) FallbackPolicyTest::startGuardThreeFailedStarts},
            {"start guard: a start that drew resets the count", (Case) FallbackPolicyTest::startGuardResetByDraw},
            {"start guard: one count per process", (Case) FallbackPolicyTest::startGuardCountsOncePerProcess},
            {"start guard: a process dying right after each draw reaches the fallback", (Case) FallbackPolicyTest::startGuardCatchesDeathRightAfterDraw},
            {"start guard: a draw that lasts STABLE_MS clears the count", (Case) FallbackPolicyTest::startGuardClearedByADrawThatLasts},
            {"start guard: only time on screen counts towards lasting", (Case) FallbackPolicyTest::stableCountsOnlyOnScreen},
            {"start guard: leaving for an app with the page drawn is a start that worked", (Case) FallbackPolicyTest::leavingWithPageDrawnIsAStartThatWorked},
            {"start guard: leaving before the draw is not, until the draw arrives", (Case) FallbackPolicyTest::leavingBeforeTheDrawIsNot},
            {"start guard: a page gone or under the list earns nothing", (Case) FallbackPolicyTest::stableStopsWhenThePageGoes},
            {"ready: never drew in 60 s on screen -> fallback", (Case) FallbackPolicyTest::readyDeadline60s},
            {"ready: the deadline counts only on-screen time", (Case) FallbackPolicyTest::readyDeadlineCountsOnlyOnScreen},
            {"ready: no deadline once drawn", (Case) FallbackPolicyTest::readyDeadlineNotAfterDraw},
            {"ready: pressing Home again and again does not postpone the deadline (G3)", (Case) FallbackPolicyTest::readyDeadlineNotPostponedByHome},
            {"ready: a new page starts with the full 60 s", (Case) FallbackPolicyTest::readyDeadlineNewPageStartsFresh},
            {"hang: 10 s after the first report -> terminate; counted as a crash", (Case) FallbackPolicyTest::hangTerminateThenCountsAsCrash},
            {"hang: terminate() fails -> fallback", (Case) FallbackPolicyTest::hangCannotTerminate},
            {"hang: still hung 10 s after terminate -> fallback", (Case) FallbackPolicyTest::hangSurvivesTerminate},
            {"hang: the clock runs only in front", (Case) FallbackPolicyTest::hangClockOnlyInFront},
            {"hang: leaving Home resets the clock (no report needed while hidden)", (Case) FallbackPolicyTest::pauseResetsHangClock},
            {"hang: responsive resets the clock", (Case) FallbackPolicyTest::responsiveResetsHang},
            {"hang: a new page starts with no hang state", (Case) FallbackPolicyTest::newPageClearsHangState},
            {"ping: a frozen page after Home/Back is terminated after 15 s, counted", (Case) FallbackPolicyTest::pingOnFrozenPageTerminates},
            {"ping: the 3rd frozen page in 10 min -> fallback", (Case) FallbackPolicyTest::pingThirdFrozenPageFallsBack},
            {"ping: an answer clears the ping and the hang clock", (Case) FallbackPolicyTest::pongClearsPing},
            {"ping: none while Home is hidden; leaving drops it", (Case) FallbackPolicyTest::noPingBehindAnotherApp},
            {"home only: pause, Home, resume sends the ping once resumed", (Case) FallbackPolicyTest::homeOnlyPingSentAfterResume},
            {"home only: a frozen page is terminated after Back's timeout", (Case) FallbackPolicyTest::homeOnlyFrozenPageTerminatedLikeBack},
            {"home only: Home pressed again and again does not postpone termination", (Case) FallbackPolicyTest::repeatedHomeDoesNotPostponeTermination},
            {"home only: Home presses keep WebView's hang clock (D-pad hang)", (Case) FallbackPolicyTest::homePressesKeepWebViewHangClock},
            {"home only: SHORT_PAUSE_MS keeps the clock, longer restarts it", (Case) FallbackPolicyTest::shortPauseBoundary},
            {"home only: a report during the short pause is ignored, clock kept", (Case) FallbackPolicyTest::reportWhilePausedIgnored},
            {"home only: a new page or the fallback drops a pending ping", (Case) FallbackPolicyTest::newPageClearsPendingPing},
            {"home: retries a failure fallback only after 60 s, counts kept", (Case) FallbackPolicyTest::homeRetryAfter60s},
            {"home: live page -> dock", (Case) FallbackPolicyTest::homeOnLivePageGoesToDock},
            {"by hand: Home returns at once", (Case) FallbackPolicyTest::byHandReturnsAtOnce},
            {"by hand: Back to CleanFrame keeps the start count", (Case) FallbackPolicyTest::byHandDoesNotResetStartCount},
            {"by hand: the Back key clears the crash and memory counts", (Case) FallbackPolicyTest::byHandBackClearsCounts},
            {"by hand: the Back key ends as the 'Back to CleanFrame' button does", (Case) FallbackPolicyTest::byHandBackSameAsButton},
            {"by hand: Home returns but keeps the counts (unchanged)", (Case) FallbackPolicyTest::byHandHomeKeepsCounts},
            {"failure lists: Back does nothing and clears nothing", (Case) FallbackPolicyTest::failureFallbacksIgnoreBack},
            {"try again: clears crash and memory counts", (Case) FallbackPolicyTest::tryAgainClearsCounts},
            {"trim: dropped only in the background, rebuilt on resume", (Case) FallbackPolicyTest::trimMemoryOnlyInBackground},
            {"fallback: a shown list cancels a pending rebuild", (Case) FallbackPolicyTest::fallbackClearsRebuild},
        };
        run(cases);
    }

    /** Runs {name, Case} pairs, each on a fresh instance; prints a total and exits 1 on any failure. */
    static void run(Object[][] cases) {
        int failed = 0;
        long t0 = System.nanoTime();
        for (Object[] c : cases) {
            try {
                ((Case) c[1]).run(new FallbackPolicyTest());
                System.out.println("ok   " + c[0]);
            } catch (Throwable e) { // a broken test is a failure, never a pass
                failed++;
                System.out.println("FAIL " + c[0] + ": " + (e instanceof Fail ? e.getMessage() : e.toString()));
            }
        }
        System.out.printf("%d/%d passed in %.2f s%n", cases.length - failed, cases.length, (System.nanoTime() - t0) / 1e9);
        System.exit(failed == 0 ? 0 : 1);
    }
}
