package home.cleanframe.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Settings > Screen and the Sleep menu on the stick; every decision is ScreenPolicy's (plain Java,
 * tested by tests/fallback.test.sh). Needs the WRITE_SETTINGS app-op granted once over adb (no device
 * admin: 2026-11-01) for the screen-off timeout. Without it it does what it can and says so in
 * screenPolicy()'s canWrite, and Sleep now is refused. Also needs global stay_on_while_plugged_in=0,
 * or Android never sleeps.
 *
 * Sleep now (2026-11-01: "put the stick to sleep. but keeps the original setting after")
 * pauses what plays and writes ScreenPolicy.SLEEP_NOW_MS, Android's shortest timeout, until Home is
 * resumed (the stick woke), the page reports a key (cancel) or RESTORE_AFTER_MS has passed; then
 * Sleep after's timeout again. The saved Sleep after is never changed by it.
 *
 * Alarms (this receiver, not exported): CHECK every PLAY_CHECK_MS while Sleep after is on (playback
 * holds a screen wake lock for PLAY_HOLD_MS); RESTORE writes the policy's timeout again. Neither wakes
 * the stick: delivered when it is next woken, which is when they matter. State lives in
 * SharedPreferences, so a killed process loses nothing.
 */
public final class SleepControl extends BroadcastReceiver {
    static final String PREFS = "screen";
    static final String ACTION_CHECK = "home.cleanframe.tv.SLEEP_CHECK", ACTION_RESTORE = "home.cleanframe.tv.SLEEP_RESTORE";
    private static final String K_DIM = "dim", K_SLEEP = "sleep", K_SLEEPING = "sleepingUntil";
    /** The page's defaults (web/home-extras.js), used until it has sent its own. */
    private static final int DEF_DIM = 5, DEF_SLEEP = 30;

    private static PowerManager.WakeLock playLock;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final String FAILED = "The TV could not change this.";
    static final String NO_WRITE = "The TV has not let CleanFrame change its sleep time.";

    static String fail(String reason) {
        try {
            return new JSONObject().put("ok", false).put("reason", reason).toString();
        } catch (JSONException e) {
            return "{\"ok\":false}";
        }
    }

    /** Bridge.screenPolicy: save the page's choices, apply them, watch playback. */
    static String policy(Context c, String dim, String sleep) {
        int d = ScreenPolicy.parse(dim, ScreenPolicy.DIM_CHOICES), s = ScreenPolicy.parse(sleep, ScreenPolicy.SLEEP_CHOICES);
        if (d < 0 || s < 0) return fail("Not a screen setting.");
        prefs(c).edit().putInt(K_DIM, d).putInt(K_SLEEP, s).apply();
        apply(c);
        check(c);
        try {
            return new JSONObject().put("ok", true).put("canWrite", Settings.System.canWrite(c)).toString();
        } catch (JSONException e) {
            return fail("No answer.");
        }
    }

    /** Write the timeout ScreenPolicy wants now, if Android lets this app (and it differs). */
    static void apply(Context c) {
        SharedPreferences p = prefs(c);
        int want = ScreenPolicy.timeoutNow(p.getInt(K_DIM, DEF_DIM), p.getInt(K_SLEEP, DEF_SLEEP),
                p.getLong(K_SLEEPING, 0), System.currentTimeMillis());
        try {
            if (!Settings.System.canWrite(c)) return;
            if (Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, -1) == want) return;
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, want);
        } catch (RuntimeException e) {
            // SecurityException (permission revoked meanwhile): the next apply tries again
        }
    }

    /** Bridge.sleepNow: pause what plays and sleep within SLEEP_NOW_MS. {"ok":true} or {"ok":false,"reason"}. */
    static String now(Context c) {
        if (!Settings.System.canWrite(c)) return fail(NO_WRITE);
        prefs(c).edit().putLong(K_SLEEPING, ScreenPolicy.sleepingUntil(System.currentTimeMillis())).commit();
        releasePlayback();
        AudioManager audio = c.getSystemService(AudioManager.class);
        if (audio != null && audio.isMusicActive()) {
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
        }
        apply(c);
        exact(c.getSystemService(AlarmManager.class), AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + ScreenPolicy.RESTORE_AFTER_MS, alarm(c, ACTION_RESTORE));
        return ok();
    }

    /** Bridge.sleepCancel: a key before the stick slept. Sleep after's timeout again. {"ok":true}. */
    static String cancel(Context c) {
        restore(c);
        return ok();
    }

    /** Home resumed: if Sleep now was pending, the stick has slept and woken (or never left), so end it. */
    static void woke(Context c) {
        if (prefs(c).getLong(K_SLEEPING, 0) != 0) prefs(c).edit().putLong(K_SLEEPING, 0).apply();
        c.getSystemService(AlarmManager.class).cancel(alarm(c, ACTION_RESTORE));
        apply(c);
    }

    private static String ok() {
        try {
            return new JSONObject().put("ok", true).toString();
        } catch (JSONException e) {
            return fail("No answer.");
        }
    }

    /** Bridge.sleepStatus: {"playing":bool}. */
    static String status(Context c) {
        try {
            return new JSONObject().put("playing", playing(c)).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private static boolean playing(Context c) {
        AudioManager a = c.getSystemService(AudioManager.class);
        return a != null && a.isMusicActive();
    }

    /**
     * setExact, or an inexact set() when Android will not allow exact alarms: Android 12+ throws
     * SecurityException without SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM, and that exception from
     * check() in onResume crashed Home on every start (Hugsun HS89, Android 14, 2026-11-01).
     */
    private static void exact(AlarmManager am, int type, long at, PendingIntent pi) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            try {
                am.setExact(type, at, pi);
                return;
            } catch (SecurityException e) {
                // permission withdrawn between the check and the call: inexact below
            }
        }
        am.set(type, at, pi);
    }

    /** One alarm per action (FLAG_UPDATE_CURRENT: a new one replaces the old). */
    private static PendingIntent alarm(Context c, String action) {
        Intent i = new Intent(c, SleepControl.class).setAction(action);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Look at playback now and again in PLAY_CHECK_MS while Sleep after is on; else let go of the screen. */
    static void check(Context c) {
        SharedPreferences p = prefs(c);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (!ScreenPolicy.watchPlayback(p.getInt(K_SLEEP, DEF_SLEEP))) {
            am.cancel(alarm(c, ACTION_CHECK));
            releasePlayback();
            return;
        }
        if (ScreenPolicy.holdForPlayback(playing(c), p.getLong(K_SLEEPING, 0), System.currentTimeMillis())) holdPlayback(c);
        else releasePlayback();
        exact(am, AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + ScreenPolicy.PLAY_CHECK_MS, alarm(c, ACTION_CHECK));
    }

    /** SCREEN_BRIGHT is deprecated for windows that can use FLAG_KEEP_SCREEN_ON; the playing app's window is not ours. */
    @SuppressWarnings("deprecation")
    private static synchronized void holdPlayback(Context c) {
        if (playLock == null) {
            PowerManager pm = c.getSystemService(PowerManager.class);
            playLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "cleanframe:playback");
            playLock.setReferenceCounted(false);
        }
        playLock.acquire(ScreenPolicy.PLAY_HOLD_MS);
    }

    private static synchronized void releasePlayback() {
        if (playLock != null && playLock.isHeld()) playLock.release();
    }

    /**
     * A RESTORE, or a WARN / FIRE left by a build with the countdown timer (ignored). Never throws:
     * it runs in Home's process from the background, so a RuntimeException here (an AlarmManager or
     * PowerManager refusal, a settings write) would crash the launcher (review 7c2b1e5); it is logged
     * like onResume's.
     */
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            String a = i == null ? null : i.getAction();
            if (ACTION_CHECK.equals(a)) check(c);
            else if (ACTION_RESTORE.equals(a)) restore(c);
        } catch (RuntimeException e) {
            Log.w(MainActivity.TAG, "SleepControl alarm failed", e);
        }
    }

    private static void restore(Context c) {
        prefs(c).edit().putLong(K_SLEEPING, 0).apply();
        apply(c);
        check(c);
    }
}
