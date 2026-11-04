package home.cleanframe.tv;

import java.util.ArrayDeque;

/**
 * The decisions behind MainActivity's failure fallback, in plain Java (no Android classes) so
 * tests/fallback.test.sh can drive them with a fake clock and store: when a dead or frozen page is
 * rebuilt, when it gives way to the native list, when Home retries it and what "Try again" clears.
 * MainActivity owns the views, the WebView and the timers; it reports each event here and does
 * what the answer says. One instance per activity, used on the main thread only.
 *
 * The fallback appears when the page has drawn no control after READY_TIMEOUT_MS on screen, when
 * the renderer crashes or hangs MAX_RENDER_CRASHES times in RENDER_DEATH_WINDOW_MS, when it is
 * killed for memory MAX_FRONT_MEMORY_KILLS times in that window while Home is in front, or at
 * start-up after MAX_FAILED_STARTS process starts in a row that died before the page had lasted
 * STABLE_MS on screen (the WebView failing to start is MainActivity's own). Memory kills while another app is in front are
 * rebuilt on resume without limit. "Try again" clears all three counts; Home retries a failure
 * fallback only RETRY_COOLDOWN_MS after it was shown and keeps the counts, so one more death
 * brings it straight back. A list opened by hand (holding Back) goes back to the page on Home or
 * Back at once; Back, like its "Back to CleanFrame" button, also clears the crash and memory-kill
 * counts. A failure fallback ignores Back.
 *
 * Hang: a renderer still unresponsive HANG_TIMEOUT_MS after its first report, while Home is in
 * front, is terminated; its death then counts as a crash. Still unresponsive HANG_TIMEOUT_MS after
 * that (or it could not be terminated): the fallback, "stopped responding".
 * WebView reports a hang only once input is sent to the renderer, and Home and Back reach the page
 * through evaluateJavascript, which is not input: so each Home/Back press also sends a round trip
 * (ping), and every PING_REPORT_MS it stays unanswered counts as one unresponsive report.
 * Home on Android 10+ is pause, onNewIntent, resume (measured on the stick, 2026-10-31: about 5 ms
 * from pause to resume, for staging's HOME intent and the live Home's real Home key alike), so the
 * press arrives while Home is not in front: pingNeeded() then remembers it and pingPending() after
 * resumed() sends it. A pause that ends within SHORT_PAUSE_MS (that Home round) keeps the hang clock
 * and any outstanding ping; a longer one (Home really hidden) restarts the clock and drops the ping.
 * So a page frozen when the user presses only Home or Back, even Home over and over, is found and
 * terminated after the same time as any other hang.
 */
final class FallbackPolicy {
    /** Milliseconds on a monotonic clock (SystemClock.elapsedRealtime on the device). */
    interface Clock {
        long now();
    }

    /** The start-guard count, kept across processes. save() must be on disk before it returns. */
    interface Store {
        int failedStarts();

        void saveFailedStarts(int n);
    }

    /**
     * The page must draw its first control within this much time on screen, summed over every stretch
     * in front since the page was built (review 30e7356, G3: restarted on each resume, a page that
     * answers pings but never draws was kept off the fallback for ever by pressing Home, each press a
     * ~5 ms pause and resume). Time behind another app does not count; a new page starts from zero.
     */
    static final long READY_TIMEOUT_MS = 60_000;
    /** This many renderer crashes inside the window (in front or not) means rebuilding will not help. */
    static final int MAX_RENDER_CRASHES = 3;
    /**
     * This many memory kills of the renderer inside the window while Home is in front means the page
     * does not fit. Higher than the crash limit: a kill in front is usually the tail of pressure left
     * by the app just closed (one or two in a row), not a page defect, and 5 still reaches the
     * fallback within a few minutes of a page that can never fit.
     */
    static final int MAX_FRONT_MEMORY_KILLS = 5;
    static final long RENDER_DEATH_WINDOW_MS = 10 * 60_000;
    /** Home retries a failure fallback only once it has been up this long. */
    static final long RETRY_COOLDOWN_MS = 60_000;
    /** Process starts in a row that died before the page drew; at this many, start in the fallback. */
    static final int MAX_FAILED_STARTS = 3;
    /**
     * A start has worked once its page has been drawn and on screen this long (summed over stretches in
     * front), or Home was left for another app with the page drawn. Clearing the count at the first
     * draw left nothing to catch a process that dies right after each draw: the count is the only state
     * that survives its death. Leaving counts because the process is then fair game: on the 2 GB stick an
     * app in front often kills it, and three quick trips to an app must not end in the fallback.
     */
    static final long STABLE_MS = 30_000;
    /**
     * A renderer still unresponsive this long after the first report (WebView sends one after about
     * 5 s without handling input) is terminated: about 15 s frozen in all, three reports. A brief
     * stall on the 2 GB stick (GC, decoding a wallpaper, the first draw after boot) lasts a few
     * seconds and clears well before that; 15 s of a dead remote is not a stall worth waiting out
     * on the only launcher.
     */
    static final long HANG_TIMEOUT_MS = 10_000;
    /** A Home/Back ping unanswered this long is one unresponsive report (WebView's own interval). */
    static final long PING_REPORT_MS = 5_000;
    /**
     * A pause shorter than this is the pause around a Home press (onNewIntent), not Home being hidden:
     * it keeps the hang clock and the outstanding ping. Measured about 5 ms on the stick; 1 s leaves room
     * for a busy stick (a frozen renderer spinning a core), and is far shorter than leaving for another
     * app and coming back. Were every pause to reset the clock, Home pressed every few seconds would keep
     * a frozen page alive for ever.
     */
    static final long SHORT_PAUSE_MS = 1_000;

    /** What Home does. */
    enum Home { TO_DOCK, START_PAGE, STAY }

    /** What an unresponsive report does. */
    enum Hang { WAIT, TERMINATE, FALLBACK }

    /** What the page's renderer dying does. */
    enum Gone { REBUILD_NOW, REBUILD_ON_RESUME, FALLBACK }

    private final Clock clock;
    private final Store store;
    private int failedStarts;
    private boolean resumed, rebuildOnResume, fallbackByHand, pageReady, hangTerminated;
    /** Home was pressed while paused (Android 10+: pause, onNewIntent, resume): ping once resumed. */
    private boolean pingPending;
    /** Clock time of the last paused(); 0 while in front or before the first pause. */
    private long pausedAt;
    /** Clock time the failure fallback was shown; 0 when it is not showing. */
    private long failedAt;
    /** Clock time of the first unresponsive report for the current renderer; 0 while it responds. */
    private long unresponsiveSince;
    /** When the ready deadline falls; 0 while no ready check runs. */
    private long readyDeadlineAt;
    /** On-screen time this page has already spent undrawn, in earlier stretches in front. */
    private long readyUsed;
    /** When the current stretch of ready checks began; 0 while none runs. */
    private long readyRunSince;
    /** When the unanswered Home/Back ping was sent; 0 when none is outstanding. */
    private long pingSentAt;
    /** When the outstanding ping's next unresponsive report is due. */
    private long pingDueAt;
    /** The page is drawn and showing (not dropped, not behind the native list). */
    private boolean pageUp;
    /** onStop was the last of onStop and onResume: Home is behind another app. */
    private boolean hidden;
    /** On-screen time of the drawn page in earlier stretches, and when this stretch began (0: none runs). */
    private long stableUsed, stableSince;
    /** Recent renderer crashes (and hangs), and memory kills while Home was in front. */
    private final ArrayDeque<Long> renderCrashes = new ArrayDeque<>(), frontMemoryKills = new ArrayDeque<>();
    private String reason = "";

    FallbackPolicy(Clock clock, Store store) {
        this.clock = clock;
        this.store = store;
    }

    /** The fallback text for the last FALLBACK answer (or non-null String answer). */
    String reason() {
        return reason;
    }

    private String fallback(String why) {
        reason = why;
        return why;
    }

    /**
     * The activity is created. firstInProcess: this process has not been counted yet (one count per
     * process, however many times the activity is created in it). The count is reset to 0 once the
     * page has lasted (STABLE_MS). Returns the fallback text, or null to start the page.
     */
    String created(boolean firstInProcess) {
        failedStarts = store.failedStarts();
        if (firstInProcess) saveFailedStarts(failedStarts + 1);
        if (failedStarts > MAX_FAILED_STARTS) { // the count includes this start
            return fallback("CleanFrame stopped starting " + MAX_FAILED_STARTS + " times.");
        }
        return null;
    }

    private void saveFailedStarts(int n) {
        if (n == failedStarts) return;
        failedStarts = n;
        store.saveFailedStarts(n);
    }

    int failedStarts() {
        return failedStarts;
    }

    /** A fresh page is being built (also when the WebView then fails to start). */
    void pageStarted() {
        fallbackByHand = false;
        rebuildOnResume = false;
        failedAt = 0;
        pageReady = false;
        unresponsiveSince = 0;
        hangTerminated = false;
        readyDeadlineAt = 0;
        readyUsed = 0;
        readyRunSince = 0;
        pingSentAt = 0;
        pingPending = false;
        pageUp = false;
        stableUsed = 0;
        stableSince = 0;
    }

    /** The native list is shown. byHand: opened by holding Back, not by a failure. */
    void fallbackShown(boolean byHand) {
        pageGone();
        fallbackByHand = byHand;
        rebuildOnResume = false;
        failedAt = byHand ? 0 : Math.max(1, clock.now());
        readyDeadlineAt = 0;
        readyRunSince = 0;
        pingSentAt = 0;
        pingPending = false;
    }

    boolean byHand() {
        return fallbackByHand;
    }

    /** The list's first button ("Try CleanFrame again" / "Back to CleanFrame"): all counts cleared. */
    void tryAgain(boolean byHand) {
        renderCrashes.clear();
        frontMemoryKills.clear();
        if (!byHand) saveFailedStarts(0);
    }

    /**
     * The remote's Back pressed while the native list shows (no page). true: leave it for the page
     * (the caller starts one). The list opened by hand leaves exactly as its "Back to CleanFrame"
     * button does, crash and memory-kill counts cleared (2026-10-31; before, Back kept them,
     * so a page that had crashed twice was one crash from the failure list after a visit by hand).
     * A failure fallback ("crashed", "stopped responding", "stopped starting", ...) ignores Back:
     * only its button, or Home after RETRY_COOLDOWN_MS, leaves it.
     */
    boolean back() {
        if (!fallbackByHand) return false;
        tryAgain(true);
        return true;
    }

    // ---------- Ready deadline ----------

    /**
     * Ready checks start (page alive, Home on screen): false if the page already drew. The deadline is
     * what is left of READY_TIMEOUT_MS after this page's earlier on-screen time (readyLeft()).
     */
    boolean readyChecksStarted() {
        readyChecksStopped(); // a stretch still running is counted first
        if (pageReady) return false;
        long now = clock.now();
        readyRunSince = Math.max(1, now);
        readyDeadlineAt = Math.max(1, now + Math.max(0, READY_TIMEOUT_MS - readyUsed));
        return true;
    }

    /** Home is hidden (or the page dropped): the stretch on screen is added to the page's used time. */
    void readyChecksStopped() {
        if (readyRunSince != 0) readyUsed += Math.max(0, clock.now() - readyRunSince);
        readyRunSince = 0;
        readyDeadlineAt = 0;
    }

    /** The deadline timer fired: the fallback text once READY_TIMEOUT_MS on screen passed undrawn, else null. */
    String readyDeadline() {
        if (pageReady || readyDeadlineAt == 0 || clock.now() < readyDeadlineAt) return null;
        return fallback("CleanFrame's page did not start.");
    }

    /** Milliseconds until the deadline (0 if none is running). */
    long readyLeft() {
        return pageReady || readyDeadlineAt == 0 ? 0 : Math.max(0, readyDeadlineAt - clock.now());
    }

    /**
     * The page has drawn its first control. true the first time (the caller stops its checks). The
     * start count is not cleared yet: stableLeft().
     */
    boolean pageDrew() {
        if (pageReady) return false;
        pageReady = true;
        readyDeadlineAt = 0;
        readyRunSince = 0;
        pageUp = true;
        if (hidden) saveFailedStarts(0); // the reply to the last probe, after Home was left
        stableGo();
        return true;
    }

    boolean pageReady() {
        return pageReady;
    }

    // ---------- The start has worked ----------

    private void stableGo() {
        if (pageUp && resumed && stableSince == 0 && failedStarts > 0) stableSince = Math.max(1, clock.now());
    }

    private void stableStop() {
        if (stableSince != 0) stableUsed += Math.max(0, clock.now() - stableSince);
        stableSince = 0;
        if (stableUsed >= STABLE_MS) saveFailedStarts(0);
    }

    /** The drawn page's renderer is gone, or the native list covers it: its time on screen stops. */
    private void pageGone() {
        stableStop();
        pageUp = false;
    }

    /** Milliseconds until the timer should look again (stableDue()); 0: no timer, nothing is counting. */
    long stableLeft() {
        if (stableSince == 0 || failedStarts == 0) return 0;
        return Math.max(1, STABLE_MS - stableUsed - (clock.now() - stableSince));
    }

    /** The timer fired: the start count is cleared once the page has lasted STABLE_MS. */
    void stableDue() {
        if (stableSince == 0 || stableUsed + clock.now() - stableSince < STABLE_MS) return;
        stableSince = 0;
        saveFailedStarts(0);
    }

    /** Home left the screen for another app (onStop). With the page drawn, this start worked. */
    void stopped() {
        hidden = true;
        if (pageUp) saveFailedStarts(0);
    }

    // ---------- Lifecycle ----------

    /**
     * Home is in front. true: the page was dropped in the background and must be rebuilt now. After a
     * pause longer than SHORT_PAUSE_MS (Home was hidden) the hang clock restarts and the ping is
     * dropped; a shorter one (the pause around a Home press) keeps both. Then pingPending().
     */
    boolean resumed() {
        resumed = true;
        hidden = false;
        stableGo();
        if (pausedAt != 0 && clock.now() - pausedAt > SHORT_PAUSE_MS) {
            unresponsiveSince = 0;
            pingSentAt = 0;
        }
        pausedAt = 0;
        return rebuildOnResume;
    }

    /** Home is in front (between resumed() and paused()). */
    boolean inFront() {
        return resumed;
    }

    /**
     * Home is paused: hidden, or only the brief pause around a Home press (Android 10+). Which one is
     * known at resumed(), which restarts the hang clock only after a pause longer than SHORT_PAUSE_MS.
     */
    void paused() {
        stableStop();
        resumed = false;
        pausedAt = Math.max(1, clock.now());
    }

    /** Memory pressure in the background: true to drop the page now (rebuilt, uncounted, on resume). */
    boolean trimMemory(boolean pageAlive) {
        if (resumed || !pageAlive) return false;
        pageGone();
        rebuildOnResume = true;
        return true;
    }

    /** Home pressed (from another app, or on Home itself). */
    Home home(boolean pageAlive) {
        if (pageAlive) return Home.TO_DOCK;
        if (fallbackByHand) return Home.START_PAGE;
        // A failure fallback: retried after the cooldown; the death counts are kept, so one more in
        // the window brings the fallback straight back.
        if (failedAt != 0 && clock.now() - failedAt >= RETRY_COOLDOWN_MS) return Home.START_PAGE;
        return Home.STAY;
    }

    // ---------- Renderer death and hang ----------

    /**
     * The current page's renderer died. didCrash: WebView's RenderProcessGoneDetail.didCrash();
     * false is a kill by the system (for memory). Our own terminate() of a hung renderer counts as a
     * crash whatever didCrash says.
     */
    Gone renderGone(boolean didCrash) {
        pageGone();
        boolean hung = hangTerminated;
        long now = clock.now();
        if (hung || didCrash) {
            int n = recordDeath(renderCrashes, now);
            if (n >= MAX_RENDER_CRASHES) {
                fallback(hung ? "CleanFrame's page stopped responding (" + n + " crashes or hangs in 10 minutes)."
                        : "CleanFrame's page crashed " + n + " times in 10 minutes.");
                return Gone.FALLBACK;
            }
        } else if (resumed) {
            int n = recordDeath(frontMemoryKills, now);
            if (n >= MAX_FRONT_MEMORY_KILLS) {
                fallback("CleanFrame's page ran out of memory " + n
                        + " times in 10 minutes while on screen; it may be too heavy for this device.");
                return Gone.FALLBACK;
            }
        }
        if (resumed) return Gone.REBUILD_NOW;
        rebuildOnResume = true;
        return Gone.REBUILD_ON_RESUME;
    }

    /**
     * An unresponsive report for the current renderer (WebView's, about every 5 s while it is hung).
     * Ignored while Home is paused: after a real absence resumed() restarts the clock.
     */
    Hang unresponsive() {
        long now = clock.now();
        if (!resumed) return Hang.WAIT;
        if (unresponsiveSince == 0) {
            unresponsiveSince = now;
            return Hang.WAIT;
        }
        if (now - unresponsiveSince < HANG_TIMEOUT_MS) return Hang.WAIT;
        if (!hangTerminated) return Hang.TERMINATE;
        // Still hung HANG_TIMEOUT_MS after terminate().
        fallback("CleanFrame's page stopped responding.");
        return Hang.FALLBACK;
    }

    /** After a TERMINATE answer: did terminate() succeed? false means the fallback (reason() says why). */
    boolean terminated(boolean ok) {
        if (!ok) {
            fallback("CleanFrame's page stopped responding.");
            return false;
        }
        hangTerminated = true; // renderGone takes it from here
        unresponsiveSince = clock.now();
        return true;
    }

    /** The renderer handled input again. */
    void responsive() {
        unresponsiveSince = 0;
        pingSentAt = 0;
    }

    /**
     * Home or Back pressed with the page alive. true: send a ping now (Home is in front and none is
     * outstanding); the caller then checks pingOverdue() every pingCheckIn() until pong(). Pressed
     * while paused (Home on Android 10+ arrives between pause and resume): false, but remembered,
     * and pingPending() says to send it once resumed.
     */
    boolean pingNeeded() {
        if (pingSentAt != 0) return false;
        if (!resumed) {
            pingPending = true;
            return false;
        }
        pingPending = false;
        pingSentAt = Math.max(1, clock.now());
        pingDueAt = pingSentAt + PING_REPORT_MS;
        return true;
    }

    /** After resumed(): true once if a press while paused asked for a ping (the caller sends it now). */
    boolean pingPending() {
        boolean p = pingPending && resumed;
        if (resumed) pingPending = false;
        return p;
    }

    /** The page answered the ping: its renderer is running script, so it is not hung. */
    void pong() {
        pingSentAt = 0;
        unresponsiveSince = 0;
    }

    /**
     * The ping's next report is due (PING_REPORT_MS after it was sent, then every PING_REPORT_MS while
     * unanswered): true once per report, which the caller passes to unresponsive().
     */
    boolean pingOverdue() {
        long now = clock.now();
        if (pingSentAt == 0 || now < pingDueAt) return false;
        pingDueAt = now + PING_REPORT_MS;
        return true;
    }

    /**
     * Milliseconds until the outstanding ping's next report is due (0: now). After a short pause the
     * caller re-arms its check with this, so the pause does not push the report back.
     */
    long pingCheckIn() {
        return pingSentAt == 0 ? 0 : Math.max(0, pingDueAt - clock.now());
    }

    boolean pingOutstanding() {
        return pingSentAt != 0;
    }

    /** Add a death at now, forget those older than RENDER_DEATH_WINDOW_MS, and return how many remain. */
    private static int recordDeath(ArrayDeque<Long> deaths, long now) {
        deaths.addLast(now);
        while (now - deaths.peekFirst() > RENDER_DEATH_WINDOW_MS) deaths.removeFirst();
        return deaths.size();
    }
}
