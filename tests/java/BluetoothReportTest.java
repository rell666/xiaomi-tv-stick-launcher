package home.cleanframe.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * BluetoothReport, the bridge's bluetooth() answer (kind from the major class, connected from the
 * profile links, which devices may be unpaired, order, JSON shape, names, reasons), offline. Plain Java, no JUnit: compiled and run
 * by tests/fallback.test.sh. Prints one line per case and a total; exits 1 on any failure.
 */
public final class BluetoothReportTest {
    static void check(boolean cond, String msg) {
        if (!cond) throw new FallbackPolicyTest.Fail(msg);
    }

    static void eq(Object got, Object want, String what) {
        if (got == null ? want != null : !got.equals(want)) throw new FallbackPolicyTest.Fail(what + ": got " + got + ", want " + want);
    }

    static BluetoothReport.Entry e(String name, int major, boolean audio, boolean input) {
        return new BluetoothReport.Entry("t", name, major, major == AV ? HEADPHONES : major, BluetoothReport.TYPE_CLASSIC, A2DP, audio, input, true);
    }

    /** Full control, for the unpair decision. The major class is the full class's, as on Android; -1: no class. */
    static BluetoothReport.Entry dev(int cls, int type, String[] uuids, boolean audio, boolean input, boolean hidKnown) {
        return new BluetoothReport.Entry("t", "D", cls < 0 ? -1 : cls & 0x1f00, cls, type, uuids, audio, input, hidKnown);
    }

    /** Classic, not connected, HID links readable: only the class and the UUIDs decide. */
    static boolean by(int cls, String... uuids) {
        return BluetoothReport.unpairable(dev(cls, CLASSIC, uuids, false, false, true));
    }

    static final int AV = BluetoothReport.MAJOR_AUDIO_VIDEO, PERIPHERAL = BluetoothReport.MAJOR_PERIPHERAL,
            COMPUTER = 0x0100, UNCATEGORIZED = 0x1f00,
            CLASSIC = BluetoothReport.TYPE_CLASSIC, LE = BluetoothReport.TYPE_LE, DUAL = BluetoothReport.TYPE_DUAL, UNKNOWN_TYPE = 0,
            HEADPHONES = BluetoothReport.CLASS_HEADPHONES, AV_UNCATEGORIZED = BluetoothReport.CLASS_AV_UNCATEGORIZED,
            KEYBOARD = 0x0540, VIDEO_CAMERA = 0x0430;
    static final String SINK = BluetoothReport.UUID_A2DP_SINK, HFP = BluetoothReport.UUID_HANDSFREE, HSP = BluetoothReport.UUID_HEADSET,
            HID = BluetoothReport.UUID_HID, HOGP = BluetoothReport.UUID_HOGP,
            A2DP_SOURCE = "0000110a-0000-1000-8000-00805f9b34fb", SERIAL = "00001101-0000-1000-8000-00805f9b34fb";
    static final String[] A2DP = {SINK};

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(String json) {
        return (Map<String, Object>) UserLibrary.Json.parse(json);
    }

    static void kinds() {
        eq(BluetoothReport.kind(e("Headphones", AV, false, false)), "audio", "audio/video class");
        eq(BluetoothReport.kind(e("Remote", PERIPHERAL, false, false)), "input", "peripheral class");
        eq(BluetoothReport.kind(e("Laptop", COMPUTER, false, false)), "other", "computer");
        eq(BluetoothReport.kind(e("Thing", -1, false, false)), "other", "no class");
        eq(BluetoothReport.kind(e("BLE remote", UNCATEGORIZED, false, true)), "input", "an uncategorised device on HID is input");
        eq(BluetoothReport.kind(e("Speaker", UNCATEGORIZED, true, false)), "audio", "an uncategorised device on A2DP is audio");
        eq(BluetoothReport.kind(e("Odd", PERIPHERAL, true, false)), "input", "the class wins over the link");
        eq(BluetoothReport.MAJOR_AUDIO_VIDEO, 0x0400, "BluetoothClass.Device.Major.AUDIO_VIDEO");
        eq(BluetoothReport.MAJOR_PERIPHERAL, 0x0500, "BluetoothClass.Device.Major.PERIPHERAL");
    }

    static void connectedByProfile() {
        check(BluetoothReport.connected(e("H", AV, true, false)), "audio on A2DP/headset: connected");
        check(!BluetoothReport.connected(e("H", AV, false, false)), "audio, no audio link (or proxies not ready): not connected");
        check(!BluetoothReport.connected(e("H", AV, false, true)), "audio is judged on the audio profiles only");
        check(BluetoothReport.connected(e("R", PERIPHERAL, false, true)), "input on HID: connected");
        check(!BluetoothReport.connected(e("R", PERIPHERAL, true, false)), "input is judged on HID only");
        check(BluetoothReport.connected(e("X", COMPUTER, true, false)) && BluetoothReport.connected(e("X", COMPUTER, false, true)),
                "other: either link");
        check(!BluetoothReport.connected(e("X", COMPUTER, false, false)), "other, no link: not connected");
    }

    static void orderAndShape() {
        List<BluetoothReport.Entry> bonded = Arrays.asList(
                e("zebra speaker", AV, false, false),
                e("Remote", PERIPHERAL, false, true),
                e("apple keyboard", PERIPHERAL, false, false),
                e("Bose QC", AV, true, false),
                e("Remote", PERIPHERAL, false, false));
        eq(BluetoothReport.ok(true, true, bonded), "{\"ok\":true,\"on\":true,\"canUnpair\":true,\"devices\":["
                + "{\"name\":\"Bose QC\",\"kind\":\"audio\",\"connected\":true,\"id\":\"t\",\"unpair\":true},"
                + "{\"name\":\"Remote\",\"kind\":\"input\",\"connected\":true,\"id\":\"t\",\"unpair\":false},"
                + "{\"name\":\"apple keyboard\",\"kind\":\"input\",\"connected\":false,\"id\":\"t\",\"unpair\":false},"
                + "{\"name\":\"Remote\",\"kind\":\"input\",\"connected\":false,\"id\":\"t\",\"unpair\":false},"
                + "{\"name\":\"zebra speaker\",\"kind\":\"audio\",\"connected\":false,\"id\":\"t\",\"unpair\":true}]}", "connected first, then by name (case-insensitive)");
        eq(BluetoothReport.ok(false, false, Collections.emptyList()), "{\"ok\":true,\"on\":false,\"canUnpair\":false,\"devices\":[]}", "off, none bonded");
        eq(BluetoothReport.ok(true, true, null), "{\"ok\":true,\"on\":true,\"canUnpair\":true,\"devices\":[]}", "no list");
        Map<String, Object> o = obj(BluetoothReport.ok(true, true, Arrays.asList(e("A \"quoted\" name", AV, true, false))));
        eq(((Map<?, ?>) ((List<?>) o.get("devices")).get(0)).get("name"), "A \"quoted\" name", "escaped and read back");
        for (Object d : (List<?>) obj(BluetoothReport.ok(true, true, bonded)).get("devices")) {
            eq(new ArrayList<>(((Map<?, ?>) d).keySet()).toString(), "[name, kind, connected, id, unpair]", "only these (never an address)");
        }
        check(!Arrays.toString(BluetoothReport.Entry.class.getDeclaredFields()).toLowerCase().contains("address"), "an Entry cannot carry an address");
    }

    /** The allowlist, on what the device IS: only headphones and speakers; unpairing the remote would strand the stick. */
    static void unpairDecision() {
        // BluetoothClass.Device.AUDIO_VIDEO_*, read off android.jar (API 34) with javap -constants.
        eq(BluetoothReport.CLASS_AV_UNCATEGORIZED, 1024, "AUDIO_VIDEO_UNCATEGORIZED");
        eq(BluetoothReport.CLASS_WEARABLE_HEADSET, 1028, "AUDIO_VIDEO_WEARABLE_HEADSET");
        eq(BluetoothReport.CLASS_HANDSFREE, 1032, "AUDIO_VIDEO_HANDSFREE");
        eq(BluetoothReport.CLASS_LOUDSPEAKER, 1044, "AUDIO_VIDEO_LOUDSPEAKER");
        eq(BluetoothReport.CLASS_HEADPHONES, 1048, "AUDIO_VIDEO_HEADPHONES");
        eq(BluetoothReport.CLASS_PORTABLE_AUDIO, 1052, "AUDIO_VIDEO_PORTABLE_AUDIO");
        eq(BluetoothReport.CLASS_CAR_AUDIO, 1056, "AUDIO_VIDEO_CAR_AUDIO");
        eq(BluetoothReport.CLASS_HIFI_AUDIO, 1064, "AUDIO_VIDEO_HIFI_AUDIO");
        eq(BluetoothReport.TYPE_CLASSIC + "," + BluetoothReport.TYPE_LE + "," + BluetoothReport.TYPE_DUAL, "1,2,3", "BluetoothDevice.DEVICE_TYPE_*");

        // the audio classes, each with an audio UUID
        for (int cls : new int[]{0x0404, 0x0408, 0x0414, 0x0418, 0x041C, 0x0420, 0x0428}) {
            check(by(cls, SINK), "audio class 0x" + Integer.toHexString(cls) + " with A2DP sink passes");
        }
        check(by(HEADPHONES, SINK), "headphones with the A2DP UUID allowed");
        check(by(HEADPHONES, HFP), "handsfree UUID is enough");
        check(by(HEADPHONES, HSP), "headset UUID is enough");
        check(by(HEADPHONES, SERIAL, SINK.toUpperCase(java.util.Locale.ROOT)), "other services beside it, and any case");
        check(BluetoothReport.unpairable(dev(HEADPHONES, DUAL, A2DP, false, false, true)), "dual mode (WH-CH510 on the Hugsun) passes");

        // every other audio/video class, whatever it claims to offer
        for (int cls : new int[]{VIDEO_CAMERA, 0x0434, 0x0438, 0x043C, 0x0440, 0x0448, 0x042C, 0x0424, 0x0410}) {
            check(!by(cls, SINK), "not an audio device: class 0x" + Integer.toHexString(cls) + " refused");
        }
        check(!by(VIDEO_CAMERA, SINK), "video camera refused");
        check(!by(0x0100, SINK), "a computer refused");
        check(!by(0x1f00, SINK), "unknown class refused, even with an audio UUID");
        check(!by(-1, SINK), "no class refused, even with an audio UUID");

        // AV uncategorised: only the UUIDs can confirm it
        check(by(AV_UNCATEGORIZED, SINK), "AV-uncategorised with A2DP allowed");
        check(!by(AV_UNCATEGORIZED, SERIAL), "AV-uncategorised without an audio UUID refused");
        check(!BluetoothReport.unpairable(dev(AV_UNCATEGORIZED, CLASSIC, new String[0], true, false, true)), "AV-uncategorised, an audio link is no substitute");

        // the UUIDs
        check(!by(HEADPHONES, SINK, HID), "headphones with the HID UUID refused");
        check(!by(HEADPHONES, HID, SINK), "HID first, the same");
        check(!by(HEADPHONES, SINK, HOGP), "headphones with the HOGP UUID refused");
        check(!by(HEADPHONES, HID.toUpperCase(java.util.Locale.ROOT), SINK), "HID in capitals, the same");
        check(!BluetoothReport.unpairable(dev(HEADPHONES, CLASSIC, null, false, false, true)), "headphones with null UUIDs refused");
        check(!BluetoothReport.unpairable(dev(HEADPHONES, CLASSIC, null, true, false, true)), "null UUIDs refused, even on an audio link");
        check(!by(HEADPHONES), "no UUIDs refused");
        check(!by(HEADPHONES, SERIAL), "no audio UUID refused");
        check(!by(HEADPHONES, A2DP_SOURCE), "an audio source (a phone) is not a sink");
        check(!by(HEADPHONES, (String) null), "a null UUID is no audio");

        // connection state qualifies nothing
        check(!BluetoothReport.unpairable(dev(0x1f00, CLASSIC, A2DP, true, false, true)), "an audio link on an unknown class refused");
        check(!BluetoothReport.unpairable(dev(-1, CLASSIC, A2DP, true, false, true)), "an audio link on no class refused");
        eq(BluetoothReport.kind(dev(0x1f00, CLASSIC, A2DP, true, false, true)), "audio", "kind() still shows it as audio: display only");

        // the rest
        check(!BluetoothReport.unpairable(dev(KEYBOARD, CLASSIC, A2DP, false, false, true)), "PERIPHERAL refused");
        check(!BluetoothReport.unpairable(new BluetoothReport.Entry("t", "D", PERIPHERAL, HEADPHONES, CLASSIC, A2DP, true, false, true)), "PERIPHERAL major refused whatever else it says");
        check(!BluetoothReport.unpairable(dev(HEADPHONES, LE, A2DP, false, false, true)), "LE-only refused even as headphones");
        check(!BluetoothReport.unpairable(dev(HEADPHONES, LE, A2DP, true, false, true)), "LE-only refused even on an audio link");
        check(!BluetoothReport.unpairable(dev(-1, LE, null, false, false, true)), "Xiaomi RC / MG4: LE-only, no class");
        check(!BluetoothReport.unpairable(dev(HEADPHONES, CLASSIC, A2DP, false, true, true)), "HID-connected refused even as headphones");
        check(!BluetoothReport.unpairable(dev(HEADPHONES, DUAL, A2DP, true, true, true)), "HID-connected refused even on an audio link");
        check(!BluetoothReport.unpairable(dev(HEADPHONES, UNKNOWN_TYPE, A2DP, false, false, true)), "unknown type refused (default is refuse)");
        check(!BluetoothReport.unpairable(dev(HEADPHONES, CLASSIC, A2DP, false, false, false)), "HID links not readable: refused (default is refuse)");
        check(!BluetoothReport.unpairable(null), "nothing read: refused");

        String json = BluetoothReport.ok(true, false, Arrays.asList(
                new BluetoothReport.Entry("bt7", "Headphones", AV, HEADPHONES, DUAL, A2DP, false, false, true),
                new BluetoothReport.Entry("bt8", "Xiaomi RC", -1, -1, LE, null, false, true, true)));
        eq(json, "{\"ok\":true,\"on\":true,\"canUnpair\":false,\"devices\":["
                + "{\"name\":\"Xiaomi RC\",\"kind\":\"input\",\"connected\":true,\"id\":\"bt8\",\"unpair\":false},"
                + "{\"name\":\"Headphones\",\"kind\":\"audio\",\"connected\":false,\"id\":\"bt7\",\"unpair\":true}]}", "canUnpair is the caller's, id and unpair per device");
        check(!json.matches("(?s).*[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:.*"), "no MAC in the JSON");
        check(!json.contains("-0000-1000-"), "no UUID in the JSON");
    }

    static void names() {
        eq(BluetoothReport.name(null), BluetoothReport.UNNAMED, "null");
        eq(BluetoothReport.name("   "), BluetoothReport.UNNAMED, "blank");
        eq(BluetoothReport.name("  Sony\u0000 WH\n-1000  "), "Sony WH-1000", "controls dropped, trimmed");
        String longName = String.join("", Collections.nCopies(100, "🎧")); // 100 headphone emoji (surrogate pairs)
        String cut = BluetoothReport.name(longName);
        eq(cut.codePointCount(0, cut.length()), BluetoothReport.NAME_MAX, "cut to NAME_MAX characters");
        check(!Character.isHighSurrogate(cut.charAt(cut.length() - 1)), "never inside a surrogate pair");
    }

    static void reasons() {
        eq(BluetoothReport.reason(false, true), "{\"ok\":false,\"reason\":\"This TV has no Bluetooth.\"}", "no adapter");
        eq(BluetoothReport.reason(false, false), "{\"ok\":false,\"reason\":\"This TV has no Bluetooth.\"}", "no adapter comes first");
        eq(BluetoothReport.reason(true, false), "{\"ok\":false,\"reason\":\"Bluetooth permission not granted\"}", "no permission, said so");
        eq(BluetoothReport.reason(true, true), null, "go ahead");
        eq(BluetoothReport.fail(BluetoothReport.FAILED), "{\"ok\":false,\"reason\":\"Could not read the Bluetooth devices.\"}", "failure");
    }

    interface Case {
        void run();
    }

    public static void main(String[] args) {
        Object[][] cases = {
            {"bluetooth: kind from the major class, else from the link", (Case) BluetoothReportTest::kinds},
            {"bluetooth: connected from the profile that fits the kind", (Case) BluetoothReportTest::connectedByProfile},
            {"bluetooth: connected first then by name; the JSON shape; no address", (Case) BluetoothReportTest::orderAndShape},
            {"bluetooth: which devices may be unpaired (allowlist; the remote never)", (Case) BluetoothReportTest::unpairDecision},
            {"bluetooth: names are cleaned and capped", (Case) BluetoothReportTest::names},
            {"bluetooth: reasons", (Case) BluetoothReportTest::reasons},
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
