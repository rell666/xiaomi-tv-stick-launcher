package home.cleanframe.tv;

import java.util.ArrayList;
import java.util.List;

/**
 * ProfileProxies, the ownership of MainActivity's Bluetooth profile proxies (review 7c2b1e5): a proxy
 * delivered after onDestroy is closed at once; a disconnect hides a proxy but it is still closed at
 * destroy; a replaced proxy is closed; each is closed once. Plain Java, no JUnit: compiled and run by
 * tests/fallback.test.sh. Prints one line per case and a total; exits 1 on any failure.
 */
public final class ProfileProxiesTest {
    static void check(boolean cond, String msg) {
        if (!cond) throw new FallbackPolicyTest.Fail(msg);
    }

    static void eq(Object got, Object want, String what) {
        if (got == null ? want != null : !got.equals(want)) throw new FallbackPolicyTest.Fail(what + ": got " + got + ", want " + want);
    }

    static final int A2DP = 2, HEADSET = 1, HID = 4;

    /** A fake BluetoothAdapter.closeProfileProxy: records "profile:proxy". */
    static final class Rig {
        final List<String> closed = new ArrayList<>();
        final ProfileProxies<String> p = new ProfileProxies<>((profile, proxy) -> closed.add(profile + ":" + proxy));
    }

    static void lateCallbackClosed() {
        Rig r = new Rig();
        r.p.connected(A2DP, "a2dp#1");
        r.p.destroy();
        eq(r.closed.toString(), "[2:a2dp#1]", "destroy closes what was delivered");
        check(r.p.destroyed(), "marked destroyed");
        r.p.connected(HID, "hid#1"); // the service binds after onDestroy
        eq(r.closed.toString(), "[2:a2dp#1, 4:hid#1]", "a proxy delivered after destroy is closed at once");
        eq(r.p.get(HID), null, "and never stored");
        r.p.destroy();
        eq(r.closed.size(), 2, "a second destroy closes nothing again");
    }

    static void disconnectKeepsOwnership() {
        Rig r = new Rig();
        r.p.connected(HEADSET, "hs#1");
        eq(r.p.get(HEADSET), "hs#1", "available once connected");
        r.p.disconnected(HEADSET);
        eq(r.p.get(HEADSET), null, "not available while disconnected");
        r.p.destroy();
        eq(r.closed.toString(), "[1:hs#1]", "but still closed at destroy");
    }

    static void reconnect() {
        Rig r = new Rig();
        r.p.connected(A2DP, "a2dp#1");
        r.p.disconnected(A2DP);
        r.p.connected(A2DP, "a2dp#1");
        eq(r.closed.toString(), "[]", "the same proxy back: nothing closed");
        eq(r.p.get(A2DP), "a2dp#1", "available again");
        r.p.connected(A2DP, "a2dp#2");
        eq(r.closed.toString(), "[2:a2dp#1]", "a different proxy replaces it: the old one is closed");
        eq(r.p.get(A2DP), "a2dp#2", "the new one is answered");
        r.p.destroy();
        eq(r.closed.toString(), "[2:a2dp#1, 2:a2dp#2]", "each closed once");
    }

    static void closerThrows() {
        List<String> closed = new ArrayList<>();
        ProfileProxies<String> p = new ProfileProxies<>((profile, proxy) -> {
            closed.add(proxy);
            throw new IllegalStateException("service gone");
        });
        p.connected(A2DP, "x");
        p.connected(HID, "y");
        p.destroy(); // must not throw out of onDestroy
        eq(closed.size(), 2, "every proxy is still tried");
        p.connected(HEADSET, "z"); // must not throw out of the callback
        eq(closed.size(), 3, "the late one too");
        p.connected(HEADSET, null);
        eq(closed.size(), 3, "a null proxy is ignored");
    }

    interface Case {
        void run();
    }

    public static void main(String[] args) {
        Object[][] cases = {
            {"bluetooth proxies: a callback after onDestroy closes its proxy at once", (Case) ProfileProxiesTest::lateCallbackClosed},
            {"bluetooth proxies: a disconnect hides a proxy, destroy still closes it", (Case) ProfileProxiesTest::disconnectKeepsOwnership},
            {"bluetooth proxies: a reconnect with a new proxy closes the old one", (Case) ProfileProxiesTest::reconnect},
            {"bluetooth proxies: closing never throws", (Case) ProfileProxiesTest::closerThrows},
        };
        int failed = 0;
        for (Object[] c : cases) {
            try {
                ((Case) c[1]).run();
                System.out.println("ok   " + c[0]);
            } catch (Throwable x) {
                failed++;
                System.out.println("FAIL " + c[0] + ": " + (x instanceof FallbackPolicyTest.Fail ? x.getMessage() : x.toString()));
            }
        }
        System.out.println((cases.length - failed) + "/" + cases.length + " passed");
        if (failed > 0) System.exit(1);
    }
}
