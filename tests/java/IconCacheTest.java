package home.cleanframe.tv;

/**
 * Behavioural tests for IconCache (MainActivity's rendered-icon cache): byte budget, LRU order,
 * invalidation by lastUpdateTime. Plain Java (8+), no JUnit: run by tests/fallback.test.sh.
 */
public final class IconCacheTest {
    static void check(boolean cond, String msg) {
        if (!cond) throw new FallbackPolicyTest.Fail(msg);
    }

    static byte[] png(int n, int fill) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    static void hitSameVersion() {
        IconCache c = new IconCache(1000);
        byte[] a = png(100, 1);
        c.put("com.a", 5, a);
        check(c.get("com.a", 5) == a, "same lastUpdateTime: cached icon");
        check(c.get("com.b", 5) == null, "other package: miss");
    }

    static void staleAfterUpdate() {
        IconCache c = new IconCache(1000);
        c.put("com.a", 5, png(100, 1));
        check(c.get("com.a", 6) == null, "app updated (new lastUpdateTime): old icon not served");
        check(c.size() == 0 && c.bytes() == 0, "stale entry dropped, its bytes freed");
        c.put("com.a", 6, png(80, 2));
        check(c.get("com.a", 6)[0] == 2 && c.bytes() == 80, "re-rendered icon cached; replaced, not added");
    }

    static void boundedLru() {
        IconCache c = new IconCache(300);
        c.put("a", 1, png(100, 1));
        c.put("b", 1, png(100, 2));
        c.put("c", 1, png(100, 3));
        check(c.get("a", 1) != null, "a used: now most recent");
        c.put("d", 1, png(100, 4)); // over budget: b (least recently used) goes
        check(c.get("b", 1) == null, "least recently used evicted");
        check(c.get("a", 1) != null && c.get("c", 1) != null && c.get("d", 1) != null, "the rest kept");
        check(c.bytes() <= 300, "within the byte budget: " + c.bytes());
        for (int i = 0; i < 1000; i++) c.put("p" + i, 1, png(50, i));
        check(c.bytes() <= 300 && c.size() == 6, "1000 icons later still bounded: " + c.bytes() + " bytes, " + c.size());
    }

    static void tooBigNotCached() {
        IconCache c = new IconCache(300);
        c.put("a", 1, png(100, 1));
        c.put("huge", 1, png(301, 9));
        check(c.get("huge", 1) == null, "an icon larger than the budget is not cached");
        check(c.get("a", 1) != null, "and evicts nothing");
    }

    static void clearFrees() {
        IconCache c = new IconCache(300);
        c.put("a", 1, png(100, 1));
        c.clear();
        check(c.get("a", 1) == null && c.bytes() == 0, "clear() (onTrimMemory) empties it");
    }

    public static void main(String[] args) {
        Object[][] cases = {
            {"icons: same package and version -> cached", (FallbackPolicyTest.Case) t -> hitSameVersion()},
            {"icons: an app update invalidates its icon", (FallbackPolicyTest.Case) t -> staleAfterUpdate()},
            {"icons: bounded by bytes, least recently used out", (FallbackPolicyTest.Case) t -> boundedLru()},
            {"icons: one over the whole budget is not cached", (FallbackPolicyTest.Case) t -> tooBigNotCached()},
            {"icons: clear() frees everything", (FallbackPolicyTest.Case) t -> clearFrees()},
        };
        FallbackPolicyTest.run(cases);
    }
}
