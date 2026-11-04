package home.cleanframe.tv;

/**
 * Behavioural tests for ScreenPolicy (Settings > Screen and the Sleep menu's decisions; SleepControl
 * carries them out on the stick). Plain Java (8+), no JUnit: run by tests/fallback.test.sh.
 */
public final class ScreenPolicyTest {
    static void check(boolean cond, String msg) {
        if (!cond) throw new FallbackPolicyTest.Fail(msg);
    }

    static final long M = ScreenPolicy.MINUTE_MS, T0 = 1_761_998_400_000L;

    static void onlyOfferedChoices() {
        check(ScreenPolicy.parse("5", ScreenPolicy.DIM_CHOICES) == 5, "5 is a Dim after choice");
        check(ScreenPolicy.parse(" 0 ", ScreenPolicy.DIM_CHOICES) == 0, "0 (Off) is a choice");
        check(ScreenPolicy.parse("120", ScreenPolicy.SLEEP_CHOICES) == 120, "2 hours is a Sleep after choice");
        check(ScreenPolicy.parse("90", ScreenPolicy.SLEEP_CHOICES) == -1, "90 min is not a Sleep after choice (the timer is gone)");
        for (String bad : new String[] {"7", "-5", "", null, "5.0", "1e3", "2147483647", "abc", "90"}) {
            check(ScreenPolicy.parse(bad, ScreenPolicy.DIM_CHOICES) == -1, "Dim after refuses " + bad);
        }
    }

    static void timeoutIsDimPlusSleep() {
        check(ScreenPolicy.screenOffTimeout(5, 30) == 35 * M, "defaults: 5 + 30 = 35 min");
        check(ScreenPolicy.screenOffTimeout(0, 10) == 10 * M, "Dim Off: sleep counted from the last press");
        check(ScreenPolicy.screenOffTimeout(15, 120) == 135 * M, "the longest: 2 h 15 min, no int overflow");
        check(ScreenPolicy.screenOffTimeout(5, 0) == ScreenPolicy.NEVER, "Sleep Off: never");
        check(ScreenPolicy.screenOffTimeout(0, 0) == ScreenPolicy.NEVER, "both Off: never");
    }

    static void sleepNowThenRestores() {
        long until = ScreenPolicy.sleepingUntil(T0);
        check(until == T0 + ScreenPolicy.RESTORE_AFTER_MS, "Sleep now holds the short timeout for RESTORE_AFTER_MS");
        check(ScreenPolicy.timeoutNow(5, 30, until, T0) == ScreenPolicy.SLEEP_NOW_MS, "just pressed: the short timeout");
        check(ScreenPolicy.timeoutNow(5, 0, until, T0) == ScreenPolicy.SLEEP_NOW_MS, "even with Sleep after Off");
        check(ScreenPolicy.timeoutNow(5, 30, until, until) == 35 * M, "restored when the window ends: Sleep after kept");
        check(ScreenPolicy.timeoutNow(15, 120, until, until) == 135 * M, "any Sleep after comes back as it was");
        check(ScreenPolicy.timeoutNow(5, 0, until, until) == ScreenPolicy.NEVER, "Sleep after Off comes back as never");
        check(ScreenPolicy.timeoutNow(5, 30, 0, T0) == 35 * M, "no Sleep now: the policy");
        check(ScreenPolicy.timeoutNow(5, 30, T0 + 10 * M, T0) == 35 * M,
              "a window longer than RESTORE_AFTER_MS (clock moved back) is not believed");
        check(ScreenPolicy.SLEEP_NOW_MS >= 10_000, "at least the stick's 10 s minimum");
    }

    static void playbackKeepsTheScreenOn() {
        check(ScreenPolicy.watchPlayback(30) && !ScreenPolicy.watchPlayback(0), "watched only while Android could sleep");
        check(ScreenPolicy.holdForPlayback(true, 0, T0), "playing: hold");
        check(!ScreenPolicy.holdForPlayback(false, 0, T0), "not playing: no hold");
        check(!ScreenPolicy.holdForPlayback(true, T0 + 30_000, T0), "Sleep now wins over playback");
        check(ScreenPolicy.PLAY_HOLD_MS > ScreenPolicy.PLAY_CHECK_MS, "the hold outlasts the gap between checks");
    }

    public static void main(String[] args) {
        Object[][] cases = {
            {"screen: only the choices the page offers", (FallbackPolicyTest.Case) t -> onlyOfferedChoices()},
            {"screen: Android's timeout is dim + sleep, never when Off", (FallbackPolicyTest.Case) t -> timeoutIsDimPlusSleep()},
            {"screen: Sleep now writes the short timeout, then Sleep after's again", (FallbackPolicyTest.Case) t -> sleepNowThenRestores()},
            {"screen: playback holds the screen on unless Sleep now is pending", (FallbackPolicyTest.Case) t -> playbackKeepsTheScreenOn()},
        };
        FallbackPolicyTest.run(cases);
    }
}
