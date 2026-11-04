package home.cleanframe.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BluetoothBonds: the page's device tokens and the unpair decision (taken on the device's state now),
 * offline. Plain Java, no JUnit: compiled and run by tests/fallback.test.sh. Prints one line per case
 * and a total; exits 1 on any failure.
 */
public final class BluetoothBondsTest {
    static void check(boolean cond, String msg) {
        if (!cond) throw new FallbackPolicyTest.Fail(msg);
    }

    static void eq(Object got, Object want, String what) {
        if (got == null ? want != null : !got.equals(want)) throw new FallbackPolicyTest.Fail(what + ": got " + got + ", want " + want);
    }

    /** A device as a MAC-looking string: the fake BluetoothDevice whose address must never come out. */
    static final String HEADPHONES = "AA:BB:CC:DD:EE:01", REMOTE = "AA:BB:CC:DD:EE:02", SPEAKER = "AA:BB:CC:DD:EE:03";

    static final String[] AUDIO = {BluetoothReport.UUID_A2DP_SINK};

    static BluetoothReport.Entry audio(String id, String name, int cls, String[] uuids, boolean input, boolean hidKnown) {
        return new BluetoothReport.Entry(id, name, BluetoothReport.MAJOR_AUDIO_VIDEO, cls, BluetoothReport.TYPE_CLASSIC, uuids, false, input, hidKnown);
    }

    /** Fake reads and the fake removeBond: every removal attempted is recorded. */
    static final class Fake implements BluetoothBonds.Ops<String> {
        final Map<String, BluetoothReport.Entry> state = new HashMap<>();
        final List<String> removed = new ArrayList<>();
        boolean answer = true;
        Exception thrown;
        RuntimeException readThrown;

        Fake() {
            state.put(HEADPHONES, audio("?", "Headphones", BluetoothReport.CLASS_HEADPHONES, AUDIO, false, true));
            state.put(SPEAKER, audio("?", "Speaker", BluetoothReport.CLASS_LOUDSPEAKER, AUDIO, false, true));
            state.put(REMOTE, new BluetoothReport.Entry("?", "Xiaomi RC", -1, -1, BluetoothReport.TYPE_LE, null, false, true, true));
        }

        @Override
        public BluetoothReport.Entry read(String device, String id) {
            if (readThrown != null) throw readThrown;
            return state.get(device);
        }

        @Override
        public boolean remove(String device) throws Exception {
            removed.add(device);
            if (thrown != null) throw thrown;
            return answer;
        }
    }

    static BluetoothBonds<String> bonds(String... devices) {
        BluetoothBonds<String> b = new BluetoothBonds<>();
        b.rebuild(Arrays.asList(devices));
        return b;
    }

    static void tokens() {
        BluetoothBonds<String> b = new BluetoothBonds<>();
        List<String> first = b.rebuild(Arrays.asList(HEADPHONES, REMOTE));
        eq(first.size(), 2, "one token each");
        check(!first.get(0).equals(first.get(1)), "different devices, different tokens");
        for (String t : first) check(t.matches("bt[0-9]+"), "an opaque counter, nothing of the device: " + t);
        eq(b.device(first.get(0)), HEADPHONES, "token -> device");
        eq(b.device(first.get(1)), REMOTE, "token -> device");
        eq(b.rebuild(Arrays.asList(REMOTE, HEADPHONES)), Arrays.asList(first.get(1), first.get(0)), "still bonded: the same token, in the list's order");
        List<String> third = b.rebuild(Arrays.asList(REMOTE));
        eq(third, Arrays.asList(first.get(1)), "the map is rebuilt");
        eq(b.device(first.get(0)), null, "a device no longer bonded has no token");
        List<String> back = b.rebuild(Arrays.asList(HEADPHONES));
        check(!back.get(0).equals(first.get(0)), "paired again: a new token, an old one is never reused");
        eq(b.rebuild(Collections.<String>emptyList()), Collections.emptyList(), "none bonded");
        eq(b.device("no-such-id"), null, "unknown");
        eq(b.device(null), null, "null");
    }

    static void unpairs() {
        BluetoothBonds<String> b = new BluetoothBonds<>();
        String t = b.rebuild(Arrays.asList(HEADPHONES)).get(0);
        Fake f = new Fake();
        eq(b.unpair(t, true, true, true, f), "", "headphones: accepted");
        eq(f.removed, Arrays.asList(HEADPHONES), "removed once, that device");
    }

    static void refusals() {
        BluetoothBonds<String> b = new BluetoothBonds<>();
        List<String> ts = b.rebuild(Arrays.asList(HEADPHONES, REMOTE));
        String phones = ts.get(0), remote = ts.get(1);
        Fake f = new Fake();
        eq(b.unpair("no-such-id", true, true, true, f), BluetoothReport.UNPAIR_UNKNOWN, "unknown token");
        eq(b.unpair("", true, true, true, f), BluetoothReport.UNPAIR_UNKNOWN, "empty token");
        eq(b.unpair(null, true, true, true, f), BluetoothReport.UNPAIR_UNKNOWN, "null token");
        eq(b.unpair("no-such-id", false, false, false, f), BluetoothReport.UNPAIR_UNKNOWN, "an unknown token says so whatever else is wrong");
        eq(b.unpair(AA_MAC, true, true, true, f), BluetoothReport.UNPAIR_UNKNOWN, "an address is no token");
        eq(b.unpair(remote, true, true, true, f), BluetoothReport.UNPAIR_REFUSED, "the remote is refused");
        eq(BluetoothReport.UNPAIR_REFUSED, "Remotes and other devices stay paired here so the TV can't lose its remote. Use TV settings to remove them.", "the agreed words");
        eq(b.unpair(phones, false, true, true, f), BluetoothReport.NO_ADAPTER, "no adapter");
        eq(b.unpair(phones, true, false, true, f), BluetoothReport.NO_PERMISSION, "no permission");
        eq(b.unpair(phones, true, true, false, f), BluetoothReport.UNPAIR_UNAVAILABLE, "removeBond did not resolve");
        check(f.removed.isEmpty(), "none of these reached removeBond: " + f.removed);
    }

    /** The list said it may go; the state now says it is an input: refused. The page is not trusted. */
    static void decidedOnTheStateNow() {
        BluetoothBonds<String> b = new BluetoothBonds<>();
        String t = b.rebuild(Arrays.asList(HEADPHONES)).get(0);
        Fake f = new Fake();
        f.state.put(HEADPHONES, audio(t, "Headphones", BluetoothReport.CLASS_HEADPHONES, AUDIO, true, true));
        eq(b.unpair(t, true, true, true, f), BluetoothReport.UNPAIR_REFUSED, "connected on HID since it was listed");
        f.state.put(HEADPHONES, audio(t, "Headphones", BluetoothReport.CLASS_HEADPHONES, AUDIO, false, false));
        eq(b.unpair(t, true, true, true, f), BluetoothReport.UNPAIR_REFUSED, "HID links not readable now");
        f.state.put(HEADPHONES, audio(t, "Headphones", BluetoothReport.CLASS_HEADPHONES, null, false, true));
        eq(b.unpair(t, true, true, true, f), BluetoothReport.UNPAIR_REFUSED, "its UUIDs are gone");
        f.state.remove(HEADPHONES);
        eq(b.unpair(t, true, true, true, f), BluetoothReport.UNPAIR_REFUSED, "nothing read");
        f.readThrown = new SecurityException("BLUETOOTH_CONNECT");
        eq(b.unpair(t, true, true, true, f), BluetoothReport.UNPAIR_FAILED, "the read throws");
        check(f.removed.isEmpty(), "none of these reached removeBond: " + f.removed);
    }

    static void removeBondFails() {
        BluetoothBonds<String> b = new BluetoothBonds<>();
        String t = b.rebuild(Arrays.asList(SPEAKER)).get(0);
        Fake f = new Fake();
        f.answer = false;
        eq(b.unpair(t, true, true, true, f), BluetoothReport.UNPAIR_FAILED, "removeBond false");
        f.answer = true;
        f.thrown = new java.lang.reflect.InvocationTargetException(new SecurityException("no"));
        eq(b.unpair(t, true, true, true, f), BluetoothReport.UNPAIR_FAILED, "removeBond throws");
        f.thrown = new IllegalAccessException("hidden API");
        eq(b.unpair(t, true, true, true, f), BluetoothReport.UNPAIR_FAILED, "removeBond blocked");
        eq(f.removed.size(), 3, "each tried once");
    }

    static void noAddress() {
        BluetoothBonds<String> b = new BluetoothBonds<>();
        List<String> ts = b.rebuild(Arrays.asList(HEADPHONES, REMOTE, SPEAKER));
        Fake f = new Fake();
        List<BluetoothReport.Entry> rows = new ArrayList<>();
        rows.add(audio(ts.get(0), "Headphones", BluetoothReport.CLASS_HEADPHONES, AUDIO, false, true));
        String all = BluetoothReport.ok(true, true, rows) + ts + b.unpair(ts.get(0), true, true, true, f) + b.unpair("x", true, true, true, f)
                + b.unpair(ts.get(1), true, true, true, f);
        check(!all.contains("AA:BB") && !all.matches("(?s).*[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:.*"), "no MAC anywhere: " + all);
    }

    static final String AA_MAC = HEADPHONES;

    interface Case {
        void run();
    }

    public static void main(String[] args) {
        Object[][] cases = {
            {"bluetooth bonds: tokens are opaque, stable while bonded, rebuilt on each list", (Case) BluetoothBondsTest::tokens},
            {"bluetooth bonds: an allowed device is removed", (Case) BluetoothBondsTest::unpairs},
            {"bluetooth bonds: unknown token, refused device, no adapter/permission/method never reach removeBond", (Case) BluetoothBondsTest::refusals},
            {"bluetooth bonds: decided on the device's state now, not the list's", (Case) BluetoothBondsTest::decidedOnTheStateNow},
            {"bluetooth bonds: removeBond false or a throw gives a reason, never a throw", (Case) BluetoothBondsTest::removeBondFails},
            {"bluetooth bonds: no MAC in anything the page can read", (Case) BluetoothBondsTest::noAddress},
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
