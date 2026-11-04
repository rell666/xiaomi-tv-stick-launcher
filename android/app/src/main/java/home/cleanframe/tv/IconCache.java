package home.cleanframe.tv;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rendered app icons (PNG bytes) by package, in plain Java (tested by tests/fallback.test.sh).
 * Bounded by total bytes, least recently used out first, and keyed by the package's lastUpdateTime
 * as well as its name, so an app update (which can change its icon) is rendered afresh instead of
 * serving the old icon until the process dies. Thread-safe: WebView asks for icons on its IO
 * threads. Every method holds the lock only for the map operation; callers render outside it.
 */
final class IconCache {
    private static final class Entry {
        final long updated;
        final byte[] png;

        Entry(long updated, byte[] png) {
            this.updated = updated;
            this.png = png;
        }
    }

    private final long maxBytes;
    private long bytes;
    private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>(16, 0.75f, true); // access order

    IconCache(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** The icon cached for pkg at this lastUpdateTime, or null. An entry from another version is dropped. */
    synchronized byte[] get(String pkg, long updated) {
        Entry e = map.get(pkg);
        if (e == null) return null;
        if (e.updated != updated) {
            remove(pkg);
            return null;
        }
        return e.png;
    }

    /** Cache pkg's icon (replacing any older one), then evict least recently used icons over the budget. */
    synchronized void put(String pkg, long updated, byte[] png) {
        remove(pkg);
        if (png == null || png.length > maxBytes) return; // larger than the whole budget: never cached
        map.put(pkg, new Entry(updated, png));
        bytes += png.length;
        Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
        while (bytes > maxBytes && it.hasNext()) {
            Entry old = it.next().getValue();
            it.remove();
            bytes -= old.png.length;
        }
    }

    synchronized void remove(String pkg) {
        Entry e = map.remove(pkg);
        if (e != null) bytes -= e.png.length;
    }

    synchronized void clear() {
        map.clear();
        bytes = 0;
    }

    synchronized long bytes() {
        return bytes;
    }

    synchronized int size() {
        return map.size();
    }
}
