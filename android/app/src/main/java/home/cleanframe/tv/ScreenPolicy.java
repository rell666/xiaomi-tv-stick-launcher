package home.cleanframe.tv;

/**
 * Settings > Screen and the Sleep menu's decisions, in plain Java (tested by tests/fallback.test.sh;
 * SleepControl does what they answer). 2026-11-01: "it is not a tv it is just a stick… just
 * sleep till i press a button"; "playback is activity".
 *
 * Home dims after dimMin minutes without a button press (the page's own layer, web/home-extras.js)
 * and the stick sleeps sleepMin minutes after that: Android's screen-off timeout is their sum, and
 * never when Sleep after is Off. Only the choices the page offers are accepted. While something
 * plays, SleepControl holds a screen wake lock (re-checked every PLAY_CHECK_MS) so neither happens.
 *
 * Sleep now (the Sleep menu; 2026-11-01: "keeps the original setting after"): SleepControl
 * pauses what plays and writes SLEEP_NOW_MS as the timeout until RESTORE_AFTER_MS later (or until
 * Home is resumed or a key is pressed, whichever is first), then the policy's timeout again. The
 * page's countdown timer (Off / 15–120 min) was removed the same day: the Sleep button and Settings >
 * Screen > Sleep after are one setting.
 */
final class ScreenPolicy {
    static final long MINUTE_MS = 60_000;
    /** Android's "never" for screen_off_timeout. */
    static final int NEVER = Integer.MAX_VALUE;
    static final int[] DIM_CHOICES = {0, 2, 5, 10, 15};
    static final int[] SLEEP_CHOICES = {0, 10, 30, 60, 120};
    /** Android's minimum on this stick is 10 s (dumpsys power mMinimumScreenOffTimeoutConfig). */
    static final int SLEEP_NOW_MS = 15_000;
    static final long RESTORE_AFTER_MS = 90_000;
    static final long PLAY_CHECK_MS = 60_000;
    /** Longer than PLAY_CHECK_MS, so the lock never lapses between two checks while something plays. */
    static final long PLAY_HOLD_MS = 90_000;

    private ScreenPolicy() {
    }

    /** The minutes in s if they are one of choices, else -1. */
    static int parse(String s, int[] choices) {
        int v;
        try {
            v = Integer.parseInt(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        for (int c : choices) {
            if (c == v) return v;
        }
        return -1;
    }

    /** Android's screen-off timeout for this policy (ms): dim + sleep minutes; NEVER when sleepMin is 0. */
    static int screenOffTimeout(int dimMin, int sleepMin) {
        if (sleepMin <= 0) return NEVER;
        return (int) ((Math.max(0, dimMin) + sleepMin) * MINUTE_MS);
    }

    /** The timeout to write now: SLEEP_NOW_MS while Sleep now is putting the stick to sleep. */
    static int timeoutNow(int dimMin, int sleepMin, long sleepingUntil, long now) {
        return sleeping(sleepingUntil, now) ? SLEEP_NOW_MS : screenOffTimeout(dimMin, sleepMin);
    }

    static boolean sleeping(long sleepingUntil, long now) {
        return sleepingUntil > now && sleepingUntil - now <= RESTORE_AFTER_MS;
    }

    /** Sleep now pressed at now: the short timeout holds until this (epoch ms). */
    static long sleepingUntil(long now) {
        return now + RESTORE_AFTER_MS;
    }

    /** Playback is watched (and keeps the screen on) only while Android's timeout could sleep the stick. */
    static boolean watchPlayback(int sleepMin) {
        return sleepMin > 0;
    }

    /** Hold the screen on now: something plays and Sleep now is not putting the stick to sleep. */
    static boolean holdForPlayback(boolean playing, long sleepingUntil, long now) {
        return playing && !sleeping(sleepingUntil, now);
    }
}
