package home.cleanframe.tv;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewRenderProcess;
import android.webkit.WebViewRenderProcessClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Home screen: CleanFrame's web UI (web/, packaged as assets) in a full-screen WebView.
 * The page is served from a private https origin so localStorage persists and nothing
 * needs file:// access; any other scheme or host (http://cleanframe.local included) is refused, so
 * the page never touches the network.
 * window.AndroidHome is the bridge web/device.js uses in place of Tizen's application API.
 *
 * This is the stick's only launcher, so when the page cannot run Home falls back to a plain
 * native list (retry, TV settings, every launchable app). That failure fallback appears when the
 * WebView cannot be created, or when FallbackPolicy (plain Java, tested offline by
 * tests/fallback.test.sh) says so: the page has drawn no control after READY_TIMEOUT_MS on screen,
 * the renderer crashes or hangs MAX_RENDER_CRASHES times in RENDER_DEATH_WINDOW_MS, it is killed
 * for memory MAX_FRONT_MEMORY_KILLS times in that window while Home is in front, or at start-up
 * after MAX_FAILED_STARTS process starts in a row that died before the page had lasted (STABLE_MS). The fallback
 * text says which. This class owns the views, WebView and timers and does what the policy answers.
 *
 * Hang (API 29+, so on the stick's Android 11): a page that drew and then froze still has its
 * control, so the ready check cannot see it. WebView reports a renderer that has not handled input
 * for about 5 s, and again about every 5 s while it stays hung. Once it has stayed unresponsive for
 * HANG_TIMEOUT_MS after the first report, and only while Home is in front, the renderer is
 * terminated; onRenderProcessGone rebuilds the page and counts the hang as a crash. A renderer that
 * cannot be terminated (or survives it) leaves the page for the fallback, "stopped responding".
 * From it, "Try again" retries at once (and clears all three counts); Home retries only
 * RETRY_COOLDOWN_MS after it was shown and keeps the counts, so a page that keeps failing is not
 * rebuilt on every press and one more death brings the fallback straight back. Holding Back opens
 * the same list by hand (the page sees nothing of that press); Back or Home there returns to CleanFrame,
 * Back (like the list's button) with the counts cleared (policy.back()). Back does nothing on a failure fallback.
 *
 * Memory: a renderer the system kills for memory while another app is in front is freed and rebuilt
 * without limit when Home is next shown. Killed while Home is in front, it is rebuilt at once but
 * counted, because a page too heavy for the stick would otherwise be rebuilt, killed and rebuilt
 * forever (a flickering black screen whose 60 s ready deadline restarts on every rebuild). Under
 * memory pressure in the background (onTrimMemory) the WebView is dropped and rebuilt on resume,
 * uncounted. Timers pause while Home is hidden.
 *
 * Photo groups (docs/PHOTO-GROUPS.md, contract 2): UserLibrary (plain Java, tested offline by
 * tests/fallback.test.sh) owns the library under libraryRoot(); this class only wires it to the bridge
 * and to the /home/user/<gid>/<pid>/<file>.jpg route. The upload token never outlives the screen:
 * stopUpload() runs when the activity stops and whenever the page is dropped or rebuilt, ordered against
 * uploadStart() by UploadGate (plain Java, tests/fallback.test.sh) so that it never waits for the
 * library's lock on the UI thread.
 *
 * Uploads (contract 4, v2: straight to the stick): uploadStart() starts UploadServer (plain Java,
 * tests/upload.test.sh) on the stick's LAN IPv4 address, port 8097, and stopUpload() stops it, so it
 * listens only while the Add photo screen is open. Each photo is prepared by AndroidPhotoProcessor on
 * one background thread into R/groups/<gid>/photos/.<pid>.tmp/; the page draws its panel and
 * materials from /home/user-tmp/<gid>/<pid>/wall.jpg and hands them back through saveMaterial()
 * (PendingPhotos), which moves the photo into place. Unfinished photos are offered again whenever
 * the page asks (materialJobs(), on every load); ones older than an hour are removed at start-up.
 * The app holds INTERNET only for that listening socket: it opens no outgoing connection
 * (tests/android.test.py fails on any outgoing-connection API) and the WebView loads nothing from
 * the network (setBlockNetworkLoads, the origin lock).
 *
 * Bluetooth (Settings > Bluetooth on the page): bluetooth() lists the bonded devices, read from the
 * adapter and from profile proxies (A2DP, headset, HID host) opened in onCreate and closed in
 * onDestroy, never waited for (BluetoothReport, plain Java, tests/fallback.test.sh, builds the
 * answer); bluetoothPair() opens TV Settings' own pairing page (CONNECT_INPUT, the one Bluetooth
 * page both sticks resolve). The app never scans, pairs, connects or disconnects anything itself
 * (BluetoothA2dp.connect is a system API) and opens no Bluetooth socket. BLUETOOTH_CONNECT (Android
 * 12+) is granted over adb with pm grant; Home never asks for it with a dialog. bluetoothUnpair() is
 * the one change it makes: it unpairs headphones and speakers, and nothing else (BluetoothReport.unpairable:
 * unpairing the remote would strand the stick), through BluetoothDevice.removeBond, which is not
 * public SDK and is looked up by reflection in one place (removeBondMethod). The page names a device by
 * an opaque token (BluetoothBonds), never its address.
 */
public class MainActivity extends Activity {
    static final String HOST = "cleanframe.local";
    static final String START = "https://" + HOST + "/home/index.html";
    static final long READY_POLL_MS = 2_000;
    static final String READY_JS = "document.querySelector('[data-action]')!==null";
    static final String PREFS = "launcher", KEY_FAILED_STARTS = "failedStarts";
    /** About 40-100 icons at 192 px; the All apps grid on the stick shows a few dozen. */
    static final long ICON_CACHE_BYTES = 2 * 1024 * 1024;
    /** Uploaded photos: /home/user/<gid>/<pid>/<file>.jpg, i.e. the page's relative user/... paths. */
    static final String USER_ROUTE = "/home/user/";
    /** Unfinished photos' wall.jpg for the page's material step: /home/user-tmp/<gid>/<pid>/wall.jpg. */
    static final String USER_TMP_ROUTE = "/home/user-tmp/";
    /** After a photo is queued: the page draws its materials now instead of at its next poll. */
    static final String MATERIALS_WAKE_JS = "window.FrameDevice&&FrameDevice.materialsWake&&FrameDevice.materialsWake()";

    static final String TAG = "CleanFrame";
    /** One increment per process, however many times the activity is created in it. */
    private static boolean startCounted;
    /** Volatile: Bridge.uploadStart() reads it on the JavaBridge thread (in uploadGate's checks). */
    private volatile WebView web;
    private boolean probing;
    /** Between onStart and onStop: an upload token is only issued while Home is on screen. */
    private volatile boolean started;
    /** Orders issuing a token (Bridge.uploadStart) against removing it (stopUpload, UI thread: never waits on the library). */
    private final UploadGate uploadGate = new UploadGate();
    private UserLibrary userLibrary;
    private PendingPhotos pendingPhotos;
    /** lib()'s start-up clean-up, on its own thread; uploadStart() waits for it (guarded by this). */
    private FutureTask<Void> cleanup;
    /** Made on the first uploadStart() (one start at a time: UploadGate); stopped by stopUpload(). */
    private volatile UploadServer uploadServer;
    /** The one thread that prepares uploaded photos (decoding two at once could exhaust memory). */
    private final ExecutorService processing = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cleanframe-photo");
        t.setDaemon(true);
        return t;
    });
    /** Back went down on the page and has not become a long press: Escape is sent when it is released. */
    private boolean backPending;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Every fallback decision: counts, windows, deadlines (elapsedRealtime; start count in prefs). */
    private final FallbackPolicy policy = new FallbackPolicy(SystemClock::elapsedRealtime, new FallbackPolicy.Store() {
        @Override
        public int failedStarts() {
            return prefs().getInt(KEY_FAILED_STARTS, 0);
        }

        /** commit(), not apply(): the start count must be on disk before a native crash can follow. */
        @Override
        public void saveFailedStarts(int n) {
            prefs().edit().putInt(KEY_FAILED_STARTS, n).commit();
        }
    });
    /** Rendered icons, at most ICON_CACHE_BYTES, re-rendered after an app update (tests/fallback.test.sh). */
    private final IconCache iconCache = new IconCache(ICON_CACHE_BYTES);
    /** TV Settings' pairing page for remotes, keyboards and audio (com.android.tv.settings AddAccessoryActivity on both sticks). */
    static final String CONNECT_INPUT = "com.google.android.intent.action.CONNECT_INPUT";
    /** BluetoothProfile.HID_HOST: a public BluetoothProfile id since API 15, hidden as a constant until 33. */
    static final int PROFILE_HID_HOST = 4;
    private volatile BluetoothAdapter btAdapter;
    /**
     * The profile proxies bluetooth() answers from (get(): null until each connects, and while its
     * service is disconnected), each closed in onDestroy, or at once if it arrives after that
     * (ProfileProxies, plain Java, tests/java/ProfileProxiesTest.java).
     */
    private final ProfileProxies<BluetoothProfile> btProxies = new ProfileProxies<>(this::closeProxy);
    /** The page's tokens for the bonded devices, rebuilt by each bluetooth() call. */
    private final BluetoothBonds<BluetoothDevice> btBonds = new BluetoothBonds<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        openBluetoothProxies();
        boolean first = !startCounted;
        startCounted = true;
        String why = policy.created(first); // the start count is reset to 0 once the page has lasted (armStable)
        if (why != null) {
            showFallback(why, false);
        } else {
            startPage();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /**
     * R of docs/PHOTO-GROUPS.md contract 1, named here and nowhere else: the app's external files dir
     * + /cleanframe (no permission needed; only this app reads or writes it). null while shared storage
     * is unavailable. On the stick (Android 11 user build) adb shell cannot read or write it either
     * (DEVICE-CHECK 1, 2026-10-31), so tools/stick-test.sh goes through the upload server and the page.
     */
    private File libraryRoot() {
        File ext = getExternalFilesDir(null);
        return ext == null ? null : new File(ext, "cleanframe");
    }

    /**
     * The photo library, made once R is available (wall clock: tokens and dates are epoch ms). Start-up
     * clean-up is started here, once, on its own thread (never the UI thread: it walks every group,
     * reads JSON and deletes trees): unfinished photos older than PendingPhotos.STALE_MS, and upload
     * temp files a stopped process left in the cache. removeStale() holds the library's lock, so a
     * bridge call that changes the library waits for it; uploadStart() waits for all of it before the
     * server can write a new temp file (awaitCleanup).
     */
    private synchronized UserLibrary lib() {
        if (userLibrary == null) {
            File root = libraryRoot();
            if (root != null) {
                UserLibrary l = new UserLibrary(root, System::currentTimeMillis, new SecureRandom());
                PendingPhotos p = new PendingPhotos(l, System::currentTimeMillis, new SecureRandom());
                File tmp = uploadTmp();
                FutureTask<Void> task = new FutureTask<>(() -> {
                    p.removeStale();
                    File[] parts = tmp.listFiles();
                    if (parts != null) for (File f : parts) if (f.isFile() && !f.delete()) f.deleteOnExit();
                    return null;
                });
                Thread t = new Thread(task, "cleanframe-cleanup");
                t.setDaemon(true);
                t.start();
                userLibrary = l;
                pendingPhotos = p;
                cleanup = task;
            }
        }
        return userLibrary;
    }

    /**
     * How long Bridge.uploadStart waits for lib()'s start-up clean-up: it is a synchronous bridge call,
     * so a long wait there freezes the page's renderer (review 7c2b1e5: it was 30 s, past WebView's
     * unresponsive-renderer report and HangWatch).
     */
    static final long CLEANUP_WAIT_MS = 2_000;

    /** Whether lib()'s start-up clean-up is over, waiting at most CLEANUP_WAIT_MS (UploadGate.awaitDone). */
    private boolean awaitCleanup() {
        FutureTask<Void> task;
        synchronized (this) {
            task = cleanup;
        }
        boolean done = UploadGate.awaitDone(task, CLEANUP_WAIT_MS);
        if (!done) Log.w(TAG, "start-up clean-up still running: Add photo answers busy");
        return done;
    }

    /** Unfinished photos (made with lib()); null while R is unavailable. */
    private synchronized PendingPhotos pending() {
        return lib() == null ? null : pendingPhotos;
    }

    /** Where an upload's body is streamed before it is prepared. */
    private File uploadTmp() {
        return new File(getCacheDir(), "upload");
    }

    /** The upload page's server, made once (inside uploadGate.start()): R, the processor, the phone page from assets/upload/. */
    private UploadServer server(UserLibrary l) {
        if (uploadServer == null) {
            uploadServer = new UploadServer(l, pending(), new AndroidPhotoProcessor(processing),
                    name -> readAll(getAssets().open("upload/" + name)), uploadTmp(), System::currentTimeMillis,
                    this::wakeMaterials, null);
        }
        return uploadServer;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            for (int n; (n = i.read(b)) > 0; ) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    /** A photo was queued for its materials: wake the page's material step (on the UI thread, if a page is up). */
    private void wakeMaterials() {
        ui.post(() -> {
            WebView w = web;
            if (w != null) w.evaluateJavascript(MATERIALS_WAKE_JS, null);
        });
    }

    /**
     * The stick's LAN IPv4 address (UploadServer.pickAddress over every interface that can be read), or
     * null. Read from NetworkInterface, which needs no permission beyond INTERNET.
     */
    private static InetAddress lanAddress() {
        try {
            List<UploadServer.Iface> list = new ArrayList<>();
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    list.add(new UploadServer.Iface(n.getName(), n.isUp(), n.isLoopback(), Collections.list(n.getInetAddresses())));
                } catch (IOException | RuntimeException e) {
                    // review 30e7356 (A5): one interface that cannot be read (a tunnel going away) is skipped, not "No network connection"
                }
            }
            return UploadServer.pickAddress(list);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Ends the Add photo screen: removes the upload code (R/upload-token.json) and stops the upload
     * page's server, so nothing listens once the screen is gone; never throws.
     */
    private void stopUpload() {
        try {
            uploadGate.stop(() -> {
                // The file itself, never lib() (this is the UI thread: onCreate, onStop) nor the
                // library's lock (start-up clean-up or a start's scan may hold it for a while): only the
                // gate's lock, which no start holds across its slow part (UploadGate). A start in
                // progress sees this stop and undoes its own token and server.
                File root = libraryRoot();
                try {
                    if (root != null) UserLibrary.removeToken(root.toPath());
                } catch (IOException | RuntimeException e) {
                    // Storage gone: there is no token file to remove (the server is still stopped).
                }
                UploadServer srv = uploadServer;
                if (srv != null) srv.stop();
            });
        } catch (Throwable e) {
            // never throws to onStop()/dropPage()
        }
    }

    /** Build a fresh WebView and load CleanFrame, or show the fallback if WebView is unavailable. */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void startPage() {
        dropPage();
        policy.pageStarted();
        probing = false;
        try {
            // DevTools over `adb forward` only; the socket refuses any uid but shell/root.
            WebView.setWebContentsDebuggingEnabled(true);
            web = new WebView(this);
        } catch (Throwable e) {
            // e.g. the WebView provider is updating, missing or disabled (can be an Error, such as
            // UnsatisfiedLinkError, not only a RuntimeException).
            web = null;
            showFallback("The web view could not start (" + e.getClass().getSimpleName() + ").", false);
            return;
        }
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        // The app holds INTERNET for the upload page's listening socket only (contract 4). Every page
        // load is served by shouldInterceptRequest (the origin lock), so no network load is needed:
        // block them all. DEVICE-CHECK 7: the page must still load; if it does not, drop this line and
        // rely on the origin lock and its tests.
        s.setBlockNetworkLoads(true);
        web.addJavascriptInterface(new Bridge(web), "AndroidHome");
        web.setWebViewClient(new Client());
        if (Build.VERSION.SDK_INT >= 29) {
            web.setWebViewRenderProcessClient(getMainExecutor(), new HangWatch());
        }
        web.setFocusable(true);
        setContentView(web);
        web.requestFocus();
        web.loadUrl(START);
        if (policy.inFront()) {
            web.onResume();
            web.resumeTimers();
            startReadyChecks();
        }
    }

    /**
     * A page that loaded but never drew a control (script error, missing asset, hung renderer) is a
     * black screen. Polled while Home is on screen; the deadline counts only on-screen time, so a slow
     * first start after boot, or a page left loading behind another app, is not mistaken for broken.
     */
    private final Runnable readyPoll = () -> {
        if (web == null || policy.pageReady()) return;
        probeReady();
        ui.postDelayed(this.readyPoll, READY_POLL_MS);
    };

    private final Runnable readyDeadline = () -> {
        if (web == null) return;
        String why = policy.readyDeadline();
        if (why != null) {
            showFallback(why, false);
            return;
        }
        long left = policy.readyLeft(); // the timer ran early on the policy's clock: wait the rest
        if (left > 0) ui.postDelayed(this.readyDeadline, left);
    };

    private void startReadyChecks() {
        stopReadyChecks();
        if (web == null || !policy.readyChecksStarted()) return;
        ui.postDelayed(readyPoll, READY_POLL_MS);
        ui.postDelayed(readyDeadline, policy.readyLeft()); // what is left of the page's on-screen allowance
    }

    private void stopReadyChecks() {
        ui.removeCallbacks(readyPoll);
        ui.removeCallbacks(readyDeadline);
        policy.readyChecksStopped();
    }

    /** One readiness query at a time; a reply from an older WebView is ignored. */
    private void probeReady() {
        WebView w = web;
        if (w == null || policy.pageReady() || probing) return;
        probing = true;
        w.evaluateJavascript(READY_JS, r -> {
            if (w != web) return;
            probing = false;
            if ("true".equals(r)) markReady();
        });
    }

    /** The page has drawn its first control: no more ready checks. This start worked once it lasts: armStable(). */
    private void markReady() {
        if (policy.pageDrew()) stopReadyChecks();
        armStable();
    }

    private final Runnable stableCheck = () -> {
        policy.stableDue();
        armStable();
    };

    /** The timer behind FallbackPolicy.stableLeft(), after any event that can change what is left. */
    private void armStable() {
        ui.removeCallbacks(stableCheck);
        long left = policy.stableLeft();
        if (left > 0) ui.postDelayed(stableCheck, left);
    }

    /** Detach and destroy the WebView, freeing its memory. */
    private void dropPage() {
        stopReadyChecks();
        ui.removeCallbacks(pingCheck);
        WebView w = web;
        web = null;
        stopUpload(); // after web = null: Bridge.uploadStart() of the dropped page is refused from here on
        if (w == null) return;
        ViewGroup parent = (ViewGroup) w.getParent();
        if (parent != null) parent.removeView(w);
        w.destroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
    }

    /** Home is off screen: the Add photo screen's code ends with it (the page asks again when shown). */
    @Override
    protected void onStop() {
        started = false; // before stopUpload(): a start in progress then fails its second check and undoes itself
        stopUpload();
        policy.stopped();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (policy.resumed()) {
            startPage();
        } else if (web != null) {
            web.onResume();
            web.resumeTimers();
            startReadyChecks(); // the rest of the page's on-screen allowance; a no-op once it was seen ready
            resumePing();
        }
        armStable();
        // Each guarded on its own: a throw here crashed Home on every start once (SleepControl.exact(),
        // Hugsun HS89, 2026-11-01); the screen policy must never take Home down with it.
        try {
            SleepControl.woke(this); // Home is back on screen: Sleep after's timeout, even after Sleep now
        } catch (RuntimeException e) {
            Log.w(TAG, "SleepControl.woke failed on resume", e);
        }
        try {
            SleepControl.check(this); // and the playback watch, after a reboot or a killed process
        } catch (RuntimeException e) {
            Log.w(TAG, "SleepControl.check failed on resume", e);
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Home pressed (from another app, or on Home itself): go back to the dock, or retry the page. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!intent.hasCategory(Intent.CATEGORY_HOME)) return;
        switch (policy.home(web != null)) {
            case TO_DOCK:
                // A frozen page never answers: found as a hang even if Home is all that is pressed. On
                // Android 10+ this runs between onPause and onResume, so the policy only remembers the
                // request here and onResume's resumePing() sends it.
                pingPage();
                web.evaluateJavascript("window.FrameDevice&&FrameDevice.toHome&&FrameDevice.toHome();", null);
                break;
            case START_PAGE: // by hand at once; a failure only after RETRY_COOLDOWN_MS, counts kept
                startPage();
                break;
            case STAY:
                break;
        }
    }

    @Override
    protected void onPause() {
        policy.paused(); // a short pause (around a Home press) keeps the hang clock; resumed() decides
        armStable();
        ui.removeCallbacks(pingCheck);
        if (web != null) {
            probeReady(); // a page that drew just before Home was left still counts as started
            stopReadyChecks();
            web.onPause();
            web.pauseTimers(); // process-wide; there is only one WebView
        }
        super.onPause();
    }

    /**
     * Under memory pressure in the background, free the page before the system kills its renderer
     * (or this whole process). TRIM_MEMORY_BACKGROUND, not COMPLETE: it is only sent once memory is
     * already low, and at COMPLETE this process is next in line to be killed, too late to help.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_BACKGROUND) return;
        iconCache.clear();
        if (policy.trimMemory(web != null)) dropPage(); // rebuilt, uncounted, on resume
    }

    @Override
    protected void onDestroy() {
        closeBluetoothProxies(); // first: marks the proxies' owner destroyed, so a late one is closed at once
        dropPage();
        processing.shutdownNow();
        super.onDestroy();
    }

    /**
     * The A2DP, headset and HID host proxies bluetooth() reads, asked for once here: each arrives on
     * the main thread when the Bluetooth service binds it, possibly after onDestroy (then btProxies
     * closes it at once). Nothing waits for them. Never throws: no adapter, no permission or a refused
     * profile only leaves that proxy null.
     */
    private void openBluetoothProxies() {
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            btAdapter = bm == null ? null : bm.getAdapter();
            if (btAdapter == null) return;
            BluetoothProfile.ServiceListener l = new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    btProxies.connected(profile, proxy);
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    btProxies.disconnected(profile); // still ours to close in onDestroy
                }
            };
            for (int p : new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET, PROFILE_HID_HOST}) {
                try {
                    btAdapter.getProfileProxy(getApplicationContext(), l, p);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Bluetooth profile " + p + " unavailable: " + e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Bluetooth unavailable: " + e.getClass().getSimpleName());
        }
    }

    /** onDestroy: every proxy delivered so far is closed, and any delivered later (ProfileProxies). */
    private void closeBluetoothProxies() {
        btProxies.destroy();
    }

    /** btProxies' closer (it swallows a RuntimeException: the service is going anyway). */
    private void closeProxy(int profile, BluetoothProfile proxy) {
        BluetoothAdapter a = btAdapter;
        if (a != null) a.closeProfileProxy(profile, proxy);
    }

    /**
     * Connected devices on one proxy; null = cannot tell: not connected yet, reading it fails, or it
     * answers null. An empty list only says nothing is connected.
     */
    private static List<BluetoothDevice> links(BluetoothProfile proxy) {
        if (proxy == null) return null;
        try {
            return proxy.getConnectedDevices();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** links(), with "cannot tell" read as no links. */
    private static List<BluetoothDevice> linked(BluetoothProfile proxy) {
        List<BluetoothDevice> l = links(proxy);
        return l == null ? Collections.<BluetoothDevice>emptyList() : l;
    }

    /** One bonded device's state now, on the cached proxies. No address leaves here. */
    private BluetoothReport.Entry btEntry(BluetoothDevice d, String id) {
        String name = Build.VERSION.SDK_INT >= 30 ? d.getAlias() : null;
        if (name == null) name = d.getName();
        BluetoothClass c = d.getBluetoothClass();
        boolean audio = linked(btProxies.get(BluetoothProfile.A2DP)).contains(d)
                || linked(btProxies.get(BluetoothProfile.HEADSET)).contains(d);
        List<BluetoothDevice> input = links(btProxies.get(PROFILE_HID_HOST));
        return new BluetoothReport.Entry(id, name, c == null ? -1 : c.getMajorDeviceClass(), c == null ? -1 : c.getDeviceClass(),
                d.getType(), btUuids(d), audio, input != null && input.contains(d), input != null);
    }

    /** The services the device last told Android it offers, or null. Never asks it (no fetchUuidsWithSdp). */
    private static String[] btUuids(BluetoothDevice d) {
        ParcelUuid[] uuids = d.getUuids();
        if (uuids == null) return null;
        String[] out = new String[uuids.length];
        for (int i = 0; i < uuids.length; i++) out[i] = uuids[i] == null ? null : uuids[i].toString();
        return out;
    }

    /**
     * BluetoothDevice.removeBond(), not public SDK: the one reflective lookup in the app
     * (tests/android.test.py). null when this TV does not let the app resolve it (canUnpair false).
     */
    private static Method removeBondMethod() {
        try {
            return BluetoothDevice.class.getMethod("removeBond");
        } catch (Throwable e) {
            return null;
        }
    }

    /** The bridge's bluetoothUnpair(): BluetoothBonds decides, on the device's state now; "" or a reason. */
    private String bluetoothUnpairNow(String id) {
        final Method removeBond = removeBondMethod();
        boolean permitted = checkSelfPermission(Build.VERSION.SDK_INT < 31
                ? "android.permission.BLUETOOTH_ADMIN" : "android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
        return btBonds.unpair(id, btAdapter != null, permitted, removeBond != null, new BluetoothBonds.Ops<BluetoothDevice>() {
            @Override
            public BluetoothReport.Entry read(BluetoothDevice device, String token) {
                return btEntry(device, token);
            }

            @Override
            public boolean remove(BluetoothDevice device) throws Exception {
                return Boolean.TRUE.equals(removeBond.invoke(device));
            }
        });
    }

    /**
     * The bridge's bluetooth(): the bonded devices from the adapter, each with its major class and
     * its links on the cached proxies (BluetoothReport builds the JSON; no address leaves here).
     */
    private String bluetoothReport() {
        BluetoothAdapter a = btAdapter;
        boolean permitted = Build.VERSION.SDK_INT < 31
                || checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
        String refused = BluetoothReport.reason(a != null, permitted);
        if (refused != null) return refused;
        try {
            java.util.Set<BluetoothDevice> bonded = a.getBondedDevices();
            List<BluetoothDevice> devices = bonded == null ? new ArrayList<BluetoothDevice>() : new ArrayList<>(bonded);
            List<String> ids = btBonds.rebuild(devices);
            List<BluetoothReport.Entry> out = new ArrayList<>();
            for (int i = 0; i < devices.size(); i++) out.add(btEntry(devices.get(i), ids.get(i)));
            return BluetoothReport.ok(a.isEnabled(), removeBondMethod() != null, out);
        } catch (SecurityException e) {
            return BluetoothReport.fail(BluetoothReport.NO_PERMISSION);
        } catch (RuntimeException e) {
            return BluetoothReport.fail(BluetoothReport.FAILED);
        }
    }

    /**
     * The remote's Back never reaches the page as a key, so send it as Escape (CleanFrame's "back").
     * A press is only known to be short when it is released, so the page gets Escape keydown + keyup
     * then, once per press however long it was held (no repeats, so no Escape storm). A long press
     * opens the native fallback instead and the page gets nothing: no panel closes as a side effect,
     * and no keydown is left without its keyup (main.js ignores Escape until the held one is
     * released). In the list opened by hand, Back returns to CleanFrame with the counts cleared, as
     * its button does (policy.back()); a failure fallback ignores it. That press is not passed to the page.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getKeyCode() != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(ev);
        int action = ev.getAction();
        if (action == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
            backPending = web != null;
            if (web != null) pingPage(); // as for Home: a frozen page is found even if only Back is pressed
            if (web == null && policy.back()) startPage(); // by hand: counts cleared, as the button
        } else if (action == KeyEvent.ACTION_DOWN && ev.isLongPress()) {
            if (backPending) {
                backPending = false;
                showFallback("Opened by holding Back. Press Back to return to CleanFrame.", true);
            }
        } else if (action == KeyEvent.ACTION_UP) {
            boolean shortPress = backPending && !ev.isCanceled();
            backPending = false;
            if (shortPress) {
                sendKey("keydown", 27);
                sendKey("keyup", 27);
            }
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        // A launcher never finishes on Back.
    }

    private void sendKey(String type, int code) {
        if (web == null) return;
        web.evaluateJavascript("(function(){var e=new KeyboardEvent('" + type + "',{bubbles:true,cancelable:true,key:'Escape'});"
                + "Object.defineProperty(e,'keyCode',{get:function(){return " + code + ";}});"
                + "Object.defineProperty(e,'which',{get:function(){return " + code + ";}});"
                + "(document.activeElement||document.body).dispatchEvent(e);})();", null);
    }

    /**
     * Native Home with no web code: a way back to CleanFrame, TV settings, and every launchable app.
     * byHand: opened by holding Back (so Back and Home return to CleanFrame), not by a failure.
     */
    private void showFallback(String why, boolean byHand) {
        dropPage();
        policy.fallbackShown(byHand);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(96, 48, 96, 48);
        TextView title = new TextView(this);
        title.setText("CleanFrame\n" + why);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setPadding(0, 0, 0, 24);
        list.addView(title);
        Button retry = fallbackButton(list, byHand ? "Back to CleanFrame" : "Try CleanFrame again", v -> {
            policy.tryAgain(byHand);
            startPage();
        });
        fallbackButton(list, "TV settings", v -> report("TV settings", start(new Intent(Settings.ACTION_SETTINGS))));
        for (Map.Entry<String, String> app : launchableApps().entrySet()) {
            String pkg = app.getKey(), label = app.getValue();
            fallbackButton(list, label, v -> report(label, launch(pkg)));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(list);
        setContentView(scroll);
        retry.requestFocus();
    }

    /** A fallback button's start()/launch() answer: "" is success; anything else is shown, not dropped. */
    private void report(String what, String error) {
        if (error == null || error.isEmpty()) return;
        Toast.makeText(this, "Could not open " + what + ": " + error, Toast.LENGTH_LONG).show();
    }

    private Button fallbackButton(LinearLayout list, String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        list.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private class Client extends WebViewClient {
        /**
         * The page's process is often killed for memory while an app plays: free the dead WebView and
         * rebuild it when Home is next shown (not in the background, where it would compete with the
         * app in front), without limit. If Home is in front it is rebuilt at once, but counted: crashes
         * (anywhere) toward MAX_RENDER_CRASHES, memory kills in front toward MAX_FRONT_MEMORY_KILLS,
         * each in RENDER_DEATH_WINDOW_MS. Without the second count a page too heavy for the stick is
         * rebuilt and killed forever in front, and never reaches the fallback.
         */
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view != web) return true; // an old WebView, already dropped
            dropPage();
            switch (policy.renderGone(detail.didCrash())) { // our terminate() counts as a crash
                case FALLBACK:
                    showFallback(policy.reason(), false);
                    break;
                case REBUILD_NOW:
                    startPage();
                    break;
                case REBUILD_ON_RESUME:
                    break;
            }
            return true;
        }

        /** Navigation anywhere but our own origin is cancelled. */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return !ours(req.getUrl());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String path = u.getPath();
            if (!ours(u) || path == null || path.contains("..")) return status(403, "Forbidden");
            if (path.startsWith(USER_TMP_ROUTE)) {
                // An unfinished photo's wall.jpg, for the page's material step: PendingPhotos.tmpWall()
                // alone decides (wall.jpg only, valid ids, canonical path under R); anything else is 404.
                try {
                    PendingPhotos p = pending();
                    File f = p == null ? null : p.tmpWall(path.substring(USER_TMP_ROUTE.length()));
                    if (f != null) return new WebResourceResponse("image/jpeg", null, new FileInputStream(f));
                } catch (Throwable e) {
                    // IO thread: a vanished file or broken storage just loses the image.
                }
                return status(404, "Not Found");
            }
            if (path.startsWith(USER_ROUTE)) {
                // An uploaded photo: UserLibrary.photoFile() alone decides (seven names, valid ids,
                // canonical path under R); anything else is 404, never an asset.
                try {
                    UserLibrary l = lib();
                    File f = l == null ? null : l.photoFile(path.substring(USER_ROUTE.length()));
                    if (f != null) return new WebResourceResponse("image/jpeg", null, new FileInputStream(f));
                } catch (Throwable e) {
                    // IO thread: a vanished file or broken storage just loses the image.
                }
                return status(404, "Not Found");
            }
            if (path.startsWith("/home/")) {
                try {
                    InputStream in = getAssets().open(path.substring("/home/".length()));
                    String type = mime(path);
                    return new WebResourceResponse(type, charset(type), in);
                } catch (IOException e) {
                    // Missing asset (e.g. the private fonts CleanFrame does not ship): fall through to 404.
                }
            } else if (path.startsWith("/icons/") && path.endsWith(".png")) {
                try {
                    byte[] png = icon(path.substring("/icons/".length(), path.length() - ".png".length()));
                    if (png != null) return new WebResourceResponse("image/png", null, new ByteArrayInputStream(png));
                } catch (Throwable e) {
                    // This runs on WebView's IO thread, where anything uncaught kills the whole process:
                    // a broken icon drawable (RuntimeException) or OutOfMemoryError just loses the icon.
                }
            }
            return status(404, "Not Found");
        }
    }

    /**
     * Hang watch (API 29+; installed only behind the SDK check). Reports arrive on the main thread.
     * Not counted while Home is hidden: after more than FallbackPolicy.SHORT_PAUSE_MS away the clock
     * restarts when Home is next in front and the renderer is still unresponsive (WebView only reports
     * a hang once input is sent to it). The brief pause around a Home press keeps it.
     */
    @TargetApi(29)
    private class HangWatch extends WebViewRenderProcessClient {
        @Override
        public void onRenderProcessUnresponsive(WebView view, WebViewRenderProcess renderer) {
            if (view != web) return;
            hang(() -> renderer != null && renderer.terminate());
        }

        @Override
        public void onRenderProcessResponsive(WebView view, WebViewRenderProcess renderer) {
            if (view == web) policy.responsive();
        }
    }

    /**
     * Home and Back reach the page through evaluateJavascript, which is not input, so WebView never
     * reports a page that froze while the user pressed only those. Each press therefore sends a
     * round trip; while it stays unanswered, every PING_REPORT_MS is one unresponsive report to
     * hang(), exactly like WebView's own (about 15 s frozen, then terminate). Home arrives while
     * paused on Android 10+ (pause, onNewIntent, resume): its ping is sent by resumePing().
     */
    private void pingPage() {
        WebView w = web;
        if (w == null || !policy.pingNeeded()) return;
        w.evaluateJavascript("0", r -> {
            if (w != web) return; // an old WebView's late answer
            policy.pong();
            ui.removeCallbacks(pingCheck);
        });
        ui.removeCallbacks(pingCheck);
        ui.postDelayed(pingCheck, FallbackPolicy.PING_REPORT_MS);
    }

    private final Runnable pingCheck = () -> {
        if (web == null || !policy.pingOutstanding()) return;
        if (policy.pingOverdue()) hang(this::terminateRenderer);
        if (web != null && policy.pingOutstanding()) ui.postDelayed(this.pingCheck, policy.pingCheckIn());
    };

    /**
     * After onResume: send the ping a Home press asked for while paused, or re-arm the check of one
     * still outstanding after the short pause around a Home press (onPause removed it), at its own due
     * time, so pressing Home every few seconds does not push the reports back.
     */
    private void resumePing() {
        if (policy.pingPending()) {
            pingPage();
        } else if (policy.pingOutstanding()) {
            ui.removeCallbacks(pingCheck);
            ui.postDelayed(pingCheck, policy.pingCheckIn());
        }
    }

    /** Terminate the current page's renderer: false where that is impossible (API < 29, no renderer). */
    private boolean terminateRenderer() {
        if (web == null) return false;
        if (Build.VERSION.SDK_INT >= 29) {
            return terminateRenderer29(web);
        }
        return false;
    }

    @TargetApi(29)
    private static boolean terminateRenderer29(WebView w) {
        WebViewRenderProcess r = w.getWebViewRenderProcess();
        return r != null && r.terminate();
    }

    /**
     * One unresponsive report for the current page. After HANG_TIMEOUT_MS of reports in front the
     * renderer is terminated (onRenderProcessGone rebuilds it and counts a crash); if it cannot be,
     * or is still hung HANG_TIMEOUT_MS later, the fallback.
     */
    private void hang(BooleanSupplier terminate) {
        switch (policy.unresponsive()) {
            case WAIT:
                return;
            case TERMINATE:
                if (policy.terminated(terminate.getAsBoolean())) return;
                showFallback(policy.reason(), false);
                return;
            case FALLBACK:
                showFallback(policy.reason(), false);
                return;
        }
    }

    /** Our origin only: https and HOST, so http://cleanframe.local (or any other scheme) is refused. */
    private static boolean ours(Uri u) {
        return "https".equals(u.getScheme()) && HOST.equals(u.getHost());
    }

    private static WebResourceResponse status(int code, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", code, reason, null, new ByteArrayInputStream(new byte[0]));
    }

    /**
     * Content type by extension. tests/android.test.py fails unless every extension under web/ has its
     * own line here, so the fallback below is never used for a file we ship.
     */
    private static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".xml")) return "application/xml";
        if (p.endsWith(".txt")) return "text/plain";
        if (p.endsWith(".md")) return "text/markdown";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".otf")) return "font/otf";
        return "application/octet-stream";
    }

    /** "utf-8" for text types; null for binary ones (fonts, images), which have no character encoding. */
    private static String charset(String mime) {
        boolean text = mime.startsWith("text/") || mime.equals("application/javascript")
                || mime.equals("application/json") || mime.equals("application/xml") || mime.equals("image/svg+xml");
        return text ? "utf-8" : null;
    }

    /**
     * An installed app's icon as PNG, rendered once per app version and cached (bounded). A missing
     * app is not cached. Runs on WebView's IO threads; no lock is held while drawing (IconCache locks
     * only its own map), so one slow icon does not stall the others or onTrimMemory.
     */
    private byte[] icon(String pkg) {
        PackageManager pm = getPackageManager();
        long updated;
        try {
            updated = pm.getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            iconCache.remove(pkg);
            return null; // Uninstalled since the list was read.
        }
        byte[] cached = iconCache.get(pkg, updated);
        if (cached != null) return cached;
        try {
            Drawable d = pm.getApplicationIcon(pkg);
            int size = 192;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            byte[] png = out.toByteArray();
            iconCache.put(pkg, updated, png);
            return png;
        } catch (PackageManager.NameNotFoundException e) {
            return null; // Uninstalled since the list was read.
        }
    }

    /** Package → label for every launchable app but this one, TV apps first. */
    private Map<String, String> launchableApps() {
        PackageManager pm = getPackageManager();
        Map<String, String> found = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> list = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
            for (ResolveInfo r : list) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || found.containsKey(pkg)) continue;
                found.put(pkg, String.valueOf(r.loadLabel(pm)));
            }
        }
        return found;
    }

    /** "" on success or a message for the user. */
    private String launch(String pkg) {
        // getLaunchIntentForPackage(null) matches any package, so take only a real package name.
        if (pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) return "This app is not installed.";
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return "This app is not installed.";
        return start(i);
    }

    private String start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "";
        } catch (ActivityNotFoundException | SecurityException e) {
            return e.getClass().getSimpleName();
        }
    }

    /**
     * A Settings page opened fresh. TV Settings keeps its task in the background when Home is pressed
     * on one of its pages, and NEW_TASK alone brings that task back with its old top page: App info
     * for Emby then showed YouTube's page, left there by an earlier App info (seen on the Xiaomi,
     * 2026-11-02: one task holding three stacked AppManagementActivity pages). CLEAR_TASK starts the
     * page in a new, empty task.
     */
    private String startFresh(Intent i) {
        return start(i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    /** "" if pkg may be uninstalled, else why not: UninstallPolicy, on what Android says about it now. */
    private String uninstallRefusal(String pkg) {
        if (!UninstallPolicy.validName(pkg)) return UninstallPolicy.NOT_INSTALLED;
        PackageManager pm = getPackageManager();
        ApplicationInfo a;
        try {
            a = pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            a = null;
        }
        int flags = a == null ? 0 : a.flags;
        Set<String> homes = new HashSet<>();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            homes.add(r.activityInfo.packageName);
        }
        return UninstallPolicy.refusal(pkg, getPackageName(), a != null, (flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0, homes);
    }

    /** One photo-library bridge call: its JSON answer, or {"ok":false,...}; never throws to the page. Bridge.withLib() adds the page check. */
    private String withLib(Function<UserLibrary, String> call) {
        try {
            UserLibrary l = lib();
            return l == null ? UserLibrary.fail(NO_STORAGE) : call.apply(l);
        } catch (Throwable e) {
            return UserLibrary.fail(UserLibrary.FAILED);
        }
    }

    static final String NO_STORAGE = "The TV's storage is not available.";
    static final String NOT_SHOWN = "The home screen is not on screen.";
    static final String NO_NETWORK = "No network connection";
    static final String NO_SERVER = "Could not open the upload page on the TV. Try again.";
    /** uploadStart while the start-up clean-up still runs (CLEANUP_WAIT_MS); the page shows it with Try again. */
    static final String BUSY = "The TV is still tidying up after starting. Try again in a moment.";

    /** A bridge answer that says "ok": true. */
    private static boolean isOk(String answer) {
        Object o = UserLibrary.Json.parse(answer);
        return o instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) o).get("ok"));
    }

    /**
     * Called from the page (on WebView's JavaBridge thread, not the main thread). The app methods
     * return "" on success or a message for the page to show; the photo-library methods
     * (docs/PHOTO-GROUPS.md contract 2) return JSON strings and never throw.
     */
    private class Bridge {
        /** The WebView this bridge was added to: a call from an older, dropped page is ignored. */
        private final WebView owner;

        Bridge(WebView owner) {
            this.owner = owner;
        }

        /** MainActivity.withLib() for the current page only: a dropped page's call is refused. */
        private String withLib(Function<UserLibrary, String> call) {
            if (owner != web) return UserLibrary.fail(NOT_SHOWN);
            return MainActivity.this.withLib(call);
        }

        /**
         * web/device.js: the page has drawn its first control (the same test as READY_JS), sent as
         * soon as it happens so a start killed before the next 2 s probe is not counted as failed.
         */
        @JavascriptInterface
        public String ready() {
            ui.post(() -> {
                if (owner == web) markReady();
            });
            return "";
        }

        @JavascriptInterface
        public String apps() {
            JSONArray out = new JSONArray();
            try {
                for (Map.Entry<String, String> e : launchableApps().entrySet()) {
                    out.put(new JSONObject().put("appId", e.getKey()).put("name", e.getValue()));
                }
            } catch (org.json.JSONException e) {
                return "[]";
            }
            return out.toString();
        }

        @JavascriptInterface
        public String launch(String pkg) {
            if (owner != web) return NOT_SHOWN; // a dropped page's call
            return MainActivity.this.launch(pkg);
        }

        @JavascriptInterface
        public String settings() {
            if (owner != web) return NOT_SHOWN;
            return startFresh(new Intent(Settings.ACTION_SETTINGS));
        }

        /**
         * Settings > Bluetooth: {"ok":true,"on":bool,"canUnpair":bool,"devices":[{"name","kind","connected",
         * "id","unpair"}]} or {"ok":false,"reason"} (BluetoothReport). Answers from the cached proxies at once.
         */
        @JavascriptInterface
        public String bluetooth() {
            try {
                return bluetoothReport();
            } catch (Throwable e) {
                return BluetoothReport.fail(BluetoothReport.FAILED);
            }
        }

        /** Pair a device: TV Settings' own pairing page, opened fresh like settings(). "" or a reason. */
        @JavascriptInterface
        public String bluetoothPair() {
            if (owner != web) return NOT_SHOWN;
            return startFresh(new Intent(CONNECT_INPUT));
        }

        /**
         * Unpair one device, named by its token from bluetooth(): headphones and speakers only, decided
         * here again (BluetoothBonds). "" or a reason sentence; never throws.
         */
        @JavascriptInterface
        public String bluetoothUnpair(String id) {
            if (owner != web) return NOT_SHOWN;
            try {
                return bluetoothUnpairNow(id);
            } catch (Throwable e) {
                return BluetoothReport.UNPAIR_FAILED;
            }
        }

        /**
         * The long-press menu's App info: Android's page for an installed app (force stop, storage,
         * uninstall). Only a package this app lists as launchable; anything else is refused.
         */
        @JavascriptInterface
        public String appInfo(String pkg) {
            if (owner != web) return NOT_SHOWN;
            if (pkg == null || !launchableApps().containsKey(pkg)) return "This app is not installed.";
            return startFresh(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)));
        }

        /** Whether the long-press menu offers Uninstall: "true" or "false" (UninstallPolicy), never throws. */
        @JavascriptInterface
        public String canUninstall(String pkg) {
            if (owner != web) return "false";
            try {
                return uninstallRefusal(pkg).isEmpty() ? "true" : "false";
            } catch (Throwable e) {
                return "false";
            }
        }

        /**
         * The long-press menu's Uninstall: opens Android's own confirm dialog, fresh like appInfo(), so it
         * returns to Home. Decided here again; a refused package never opens it. "" (the dialog opened, not
         * that the app has gone) or a reason sentence; never throws. Nothing here removes a package itself.
         */
        @JavascriptInterface
        public String uninstall(String pkg) {
            if (owner != web) return NOT_SHOWN;
            try {
                String why = uninstallRefusal(pkg);
                if (!why.isEmpty()) return why;
                return startFresh(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))).isEmpty() ? "" : UninstallPolicy.FAILED;
            } catch (Throwable e) {
                return UninstallPolicy.FAILED;
            }
        }

        /**
         * Settings > Screen (web/home-extras.js): minutes before Home dims and before the stick then
         * sleeps, as the page offers them. {"ok":true,"canWrite":bool} or {"ok":false,"reason"}.
         */
        @JavascriptInterface
        public String screenPolicy(String dimMin, String sleepMin) {
            try {
                return SleepControl.policy(MainActivity.this, dimMin, sleepMin);
            } catch (Throwable e) {
                return SleepControl.fail(SleepControl.FAILED);
            }
        }

        /** The Sleep menu's Sleep now: the stick sleeps within moments, Sleep after is kept. {"ok":true} or {"ok":false,"reason"}. */
        @JavascriptInterface
        public String sleepNow() {
            try {
                return SleepControl.now(MainActivity.this);
            } catch (Throwable e) {
                return SleepControl.fail(SleepControl.FAILED);
            }
        }

        /** A button pressed after Sleep now, before the stick slept: Sleep after's timeout again. {"ok":true}. */
        @JavascriptInterface
        public String sleepCancel() {
            try {
                return SleepControl.cancel(MainActivity.this);
            } catch (Throwable e) {
                return SleepControl.fail(SleepControl.FAILED);
            }
        }

        /** {"playing":bool}: whether anything plays (no dim then). */
        @JavascriptInterface
        public String sleepStatus() {
            try {
                return SleepControl.status(MainActivity.this);
            } catch (Throwable e) {
                return "{}";
            }
        }

        /** {"v":1,"groups":[...],"photos":[...]}; the built-ins only if storage cannot be read. */
        @JavascriptInterface
        public String library() {
            try {
                UserLibrary l = lib();
                return l == null ? UserLibrary.emptyLibrary() : l.library();
            } catch (Throwable e) {
                return UserLibrary.emptyLibrary();
            }
        }

        @JavascriptInterface
        public String addGroup(String name) {
            return withLib(l -> l.addGroup(name));
        }

        @JavascriptInterface
        public String renameGroup(String gid, String name) {
            return withLib(l -> l.renameGroup(gid, name));
        }

        @JavascriptInterface
        public String deleteGroup(String gid) {
            return withLib(l -> l.deleteGroup(gid, MainActivity.this::stopUpload)); // its code dropped: the server stops too
        }

        @JavascriptInterface
        public String deletePhoto(String gid, String pid) {
            return withLib(l -> l.deletePhoto(gid, pid));
        }

        /**
         * A fresh (or renewed) upload token for gid, and the upload page listening on the stick's LAN
         * address: {"ok":true,"url":"http://<ip>:8097/u/<token>","base","code","expires"}, or
         * {"ok":false,"reason":"No network connection"}. Refused for a page that is no longer the
         * current one, or while Home is off screen, so a token and server started here are always
         * ended: by the next stopUpload(), or, when one ran while this was starting, by the undo here
         * (UploadGate). The token and the bind run outside the gate's lock: they take the library's
         * lock, which stopUpload() on the UI thread must never wait for.
         */
        @JavascriptInterface
        public String uploadStart(String gid) {
            return withLib(l -> {
                // its sweep of the upload temp dir is over before the server can write there; not yet: try again
                if (!awaitCleanup()) return UserLibrary.fail(BUSY);
                return uploadGate.start(() -> owner == web && started, UserLibrary.fail(NOT_SHOWN), () -> {
                    InetAddress ip = lanAddress();
                    if (ip == null) return UserLibrary.fail(NO_NETWORK);
                    String base = UploadServer.baseUrl(ip);
                    String answer = l.uploadStart(gid, base);
                    if (!isOk(answer)) return answer;
                    try {
                        server(l).start(ip, UploadServer.PORT);
                    } catch (IOException | RuntimeException e) {
                        l.uploadStop();
                        return UserLibrary.fail(NO_SERVER);
                    }
                    return answer;
                }, MainActivity::isOk, () -> { // a stopUpload() ran meanwhile: end what this start made
                    UploadServer srv = uploadServer;
                    if (srv != null) srv.stop();
                    l.uploadStop();
                });
            });
        }

        @JavascriptInterface
        public String uploadStop() {
            return withLib(l -> {
                if (owner == web) stopUpload(); // an older page cannot end the current page's code
                return UserLibrary.ok();
            });
        }

        /** Unfinished photos still missing materials: {"v":1,"jobs":[{"gid","pid","wall","tone","need"}]}. */
        @JavascriptInterface
        public String materialJobs() {
            return withLib(l -> pending().materialJobs());
        }

        /** One material the page drew (a JPEG data URL): {"ok":true,"done":<bool>} or {"ok":false,"reason"}. */
        @JavascriptInterface
        public String saveMaterial(String gid, String pid, String kind, String dataUrl) {
            return withLib(l -> pending().saveMaterial(gid, pid, kind, dataUrl));
        }

        /**
         * Adjust: re-make an uploaded photo from its src.jpg with crop "x,y,z" (PendingPhotos.recropBegin):
         * answers at once; the work runs on its own thread (it waits for the processing thread, which
         * uploads share), then wakes the materials queue. recropState() follows it.
         */
        @JavascriptInterface
        public String recrop(String gid, String pid, String crop) {
            return withLib(l -> {
                PendingPhotos p = pending();
                String answer = p.recropBegin(gid, pid, crop);
                if (!isOk(answer)) return answer;
                Thread t = new Thread(() -> {
                    p.recropRun(gid, pid, crop, new AndroidPhotoProcessor(processing));
                    wakeMaterials();
                }, "cleanframe-recrop");
                t.setDaemon(true);
                t.start();
                return answer;
            });
        }

        /** {"ok":true,"state":"working"|"saving"|"done"|"failed","reason"?}. */
        @JavascriptInterface
        public String recropState(String gid, String pid) {
            return withLib(l -> pending().recropState(gid, pid));
        }
    }
}
