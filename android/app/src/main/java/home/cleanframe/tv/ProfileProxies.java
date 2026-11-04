package home.cleanframe.tv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Bluetooth profile proxies MainActivity asks for in onCreate, in plain Java (no Android classes;
 * P is BluetoothProfile there) so tests/fallback.test.sh runs it offline (tests/java/ProfileProxiesTest.java).
 *
 * Ownership is kept apart from availability (review 7c2b1e5): a proxy delivered by
 * onServiceConnected is ours to close (closeProfileProxy) until destroy(), whether or not its
 * service is connected at the moment; onServiceDisconnected only makes it unavailable to get(), and a
 * reconnect that delivers a different proxy closes the one it replaces. destroy() (onDestroy) marks
 * the owner destroyed first, then closes every proxy it owns; a proxy delivered after that (the
 * service binding late, after the activity is gone) is closed at once instead of being stored on a
 * destroyed activity and leaked. Thread-safe: callbacks come on the main thread, get() on the
 * bridge's; closing runs outside the lock and its RuntimeException is swallowed (the service is
 * going anyway).
 */
final class ProfileProxies<P> {
    /** Closes one proxy (BluetoothAdapter.closeProfileProxy). */
    interface Closer<P> {
        void close(int profile, P proxy);
    }

    private final Closer<P> closer;
    /** Delivered and not yet closed: closed by destroy() (or when replaced). */
    private final Map<Integer, P> owned = new HashMap<>();
    /** Owned and its service connected now: what get() answers. */
    private final Map<Integer, P> available = new HashMap<>();
    private boolean destroyed;

    ProfileProxies(Closer<P> closer) {
        this.closer = closer;
    }

    /** onServiceConnected: store it, or close it at once if the owner is already destroyed. */
    void connected(int profile, P proxy) {
        if (proxy == null) return;
        P replaced = null;
        boolean late;
        synchronized (this) {
            late = destroyed;
            if (!late) {
                replaced = owned.put(profile, proxy);
                available.put(profile, proxy);
            }
        }
        if (late) close(profile, proxy);
        else if (replaced != null && replaced != proxy) close(profile, replaced);
    }

    /** onServiceDisconnected: not available until it connects again, still ours to close. */
    synchronized void disconnected(int profile) {
        available.remove(profile);
    }

    /** The proxy for profile while its service is connected, else null. */
    synchronized P get(int profile) {
        return available.get(profile);
    }

    /** onDestroy: destroyed from now on, then every owned proxy closed (once). */
    void destroy() {
        List<Map.Entry<Integer, P>> toClose;
        synchronized (this) {
            destroyed = true;
            toClose = new ArrayList<>(owned.entrySet());
            owned.clear();
            available.clear();
        }
        for (Map.Entry<Integer, P> e : toClose) close(e.getKey(), e.getValue());
    }

    synchronized boolean destroyed() {
        return destroyed;
    }

    private void close(int profile, P proxy) {
        try {
            closer.close(profile, proxy);
        } catch (RuntimeException e) {
            // the service is going anyway
        }
    }
}
