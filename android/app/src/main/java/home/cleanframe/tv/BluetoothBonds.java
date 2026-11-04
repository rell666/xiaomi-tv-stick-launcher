package home.cleanframe.tv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings > Bluetooth's opaque device tokens and the unpair decision, in plain Java (no Android
 * classes; D is BluetoothDevice there) so tests/fallback.test.sh runs it offline
 * (tests/java/BluetoothBondsTest.java).
 *
 * The page names a device only by its token, so no address ever reaches it. rebuild() replaces the map
 * with exactly the devices bonded now, on each bluetooth() call. A device still bonded keeps its token (a
 * question already on screen survives a refresh, and the page tells "gone" from "still paired"); a device
 * no longer bonded loses it. Tokens are a counter, never derived from the device (its hashCode is
 * its address's).
 *
 * unpair() answers "" or a reason sentence and never throws. The decision is taken again here, on the
 * device's state read now (BluetoothReport.unpairable), not on what the list said: the page is not
 * trusted, and a device may have connected as an input since it was listed.
 */
final class BluetoothBonds<D> {
    /** What unpair() needs from the device: MainActivity's reads and its one reflective call. */
    interface Ops<D> {
        /** The device's state now. */
        BluetoothReport.Entry read(D device, String id);

        /** Unpair it; false or a throw: it did not happen. */
        boolean remove(D device) throws Exception;
    }

    private Map<String, D> byToken = new LinkedHashMap<>();
    private long issued;

    /** The map becomes exactly these devices; the answer is their tokens, in the same order. */
    synchronized List<String> rebuild(List<D> bonded) {
        Map<String, D> fresh = new LinkedHashMap<>();
        List<String> tokens = new ArrayList<>();
        for (D d : bonded) {
            String t = tokenOf(d);
            if (t == null) t = "bt" + (++issued);
            fresh.put(t, d);
            tokens.add(t);
        }
        byToken = fresh;
        return tokens;
    }

    private String tokenOf(D device) {
        for (Map.Entry<String, D> e : byToken.entrySet()) {
            if (e.getValue().equals(device)) return e.getKey();
        }
        return null;
    }

    /** The device behind a token, or null. */
    synchronized D device(String token) {
        return token == null ? null : byToken.get(token);
    }

    /**
     * Unpair the device behind token: "" once the TV has accepted it (removeBond is asynchronous: the
     * page re-reads the list), else why not.
     */
    String unpair(String token, boolean hasAdapter, boolean permitted, boolean canUnpair, Ops<D> ops) {
        try {
            D d = device(token);
            if (d == null) return BluetoothReport.UNPAIR_UNKNOWN;
            if (!hasAdapter) return BluetoothReport.NO_ADAPTER;
            if (!permitted) return BluetoothReport.NO_PERMISSION;
            if (!canUnpair) return BluetoothReport.UNPAIR_UNAVAILABLE;
            if (!BluetoothReport.unpairable(ops.read(d, token))) return BluetoothReport.UNPAIR_REFUSED;
            return ops.remove(d) ? "" : BluetoothReport.UNPAIR_FAILED;
        } catch (Throwable e) {
            return BluetoothReport.UNPAIR_FAILED;
        }
    }
}
