package home.cleanframe.tv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The bridge's bluetooth() answer, in plain Java (no Android classes) so tests/fallback.test.sh runs
 * it offline: the kind of each bonded device, whether it counts as connected, whether it may be
 * unpaired here, the order, the JSON, and the reason when there is no list. MainActivity only reads
 * the adapter and its cached profile proxies into Entry values (never waiting for a proxy) and hands
 * them here.
 *
 * Contract (web/device.js reads it):
 *   {"ok":true,"on":&lt;bool&gt;,"canUnpair":&lt;bool&gt;,"devices":[{"name":"&lt;string&gt;","kind":"audio"|"input"|"other",
 *     "connected":&lt;bool&gt;,"id":"&lt;opaque token&gt;","unpair":&lt;bool&gt;}]}
 *   {"ok":false,"reason":"&lt;human sentence&gt;"}
 * Bonded devices only, connected first, then by name. No address (MAC) is ever part of it: Entry
 * has no field for one, and id is a token from BluetoothBonds. The app scans for nothing and opens no
 * Bluetooth connection (docs/RULES.md rule 9; tests/android.test.py bans the APIs that would).
 */
final class BluetoothReport {
    private BluetoothReport() {
    }

    /** android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO and .PERIPHERAL (API 5; tests/java/BluetoothReportTest.java pins them). */
    static final int MAJOR_AUDIO_VIDEO = 0x0400, MAJOR_PERIPHERAL = 0x0500;
    /** android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC, _LE and _DUAL (API 18; DEVICE_TYPE_UNKNOWN is 0). */
    static final int TYPE_CLASSIC = 1, TYPE_LE = 2, TYPE_DUAL = 3;
    /** android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_*: the classes that are headphones or speakers (API 5). */
    static final int CLASS_AV_UNCATEGORIZED = 0x0400, CLASS_WEARABLE_HEADSET = 0x0404, CLASS_HANDSFREE = 0x0408,
            CLASS_LOUDSPEAKER = 0x0414, CLASS_HEADPHONES = 0x0418, CLASS_PORTABLE_AUDIO = 0x041C,
            CLASS_CAR_AUDIO = 0x0420, CLASS_HIFI_AUDIO = 0x0428;
    /** Bluetooth SIG service UUIDs, as ParcelUuid.toString() writes them. */
    static final String UUID_A2DP_SINK = "0000110b-0000-1000-8000-00805f9b34fb",
            UUID_HEADSET = "00001108-0000-1000-8000-00805f9b34fb",
            UUID_HANDSFREE = "0000111e-0000-1000-8000-00805f9b34fb",
            UUID_HID = "00001124-0000-1000-8000-00805f9b34fb",
            UUID_HOGP = "00001812-0000-1000-8000-00805f9b34fb";
    /** Longest name passed to the page; a longer one is cut (a device names itself, up to 248 bytes). */
    static final int NAME_MAX = 64;
    static final String UNNAMED = "Unnamed device";

    static final String NO_ADAPTER = "This TV has no Bluetooth.";
    static final String NO_PERMISSION = "Bluetooth permission not granted";
    static final String FAILED = "Could not read the Bluetooth devices.";
    /** A device unpairable() refuses. web/bluetooth.js says the same (tests/bluetooth.test.js compares them). */
    static final String UNPAIR_REFUSED = "Remotes and other devices stay paired here so the TV can't lose its remote. Use TV settings to remove them.";
    static final String UNPAIR_UNKNOWN = "That device is not paired here any more.";
    static final String UNPAIR_UNAVAILABLE = "This TV can't unpair from here. Use TV settings to remove it.";
    static final String UNPAIR_FAILED = "The TV would not unpair it. Use TV settings to remove it.";

    /**
     * One bonded device as MainActivity read it. id: its BluetoothBonds token. major:
     * BluetoothClass.getMajorDeviceClass() and deviceClass: getDeviceClass(), each -1 when it has no
     * class. type: BluetoothDevice.getType(). uuids: BluetoothDevice.getUuids() as strings, null when it
     * has none (never part of the answer). audioLink: connected on A2DP or the headset profile;
     * inputLink: connected on the HID host profile (each false while its proxy is not connected yet).
     * hidKnown: the HID host links could be read, so inputLink means something; unpairable() refuses
     * without it.
     */
    static final class Entry {
        final String id, name;
        final int major, deviceClass, type;
        final String[] uuids;
        final boolean audioLink, inputLink, hidKnown;

        Entry(String id, String name, int major, int deviceClass, int type, String[] uuids, boolean audioLink, boolean inputLink, boolean hidKnown) {
            this.id = id;
            this.name = name;
            this.major = major;
            this.deviceClass = deviceClass;
            this.type = type;
            this.uuids = uuids;
            this.audioLink = audioLink;
            this.inputLink = inputLink;
            this.hidKnown = hidKnown;
        }
    }

    /**
     * May this device be unpaired here? Only headphones and speakers: the stick trusts adb from one
     * host and pairing a remote needs the remote, so unpairing the remote would strand the TV. A
     * positive allowlist on what the device IS, never on how it is connected right now (kind() is for
     * display and plays no part). All must hold: it carries classic Bluetooth (LE-only is almost
     * certainly a remote; unknown is refused); it is no peripheral; it is not connected as an input,
     * and the HID links could be read at all (an empty list only says nothing is connected, which is
     * why the UUIDs below decide capability); its full class is an audio one (never a camera, monitor,
     * VCR, TV, set-top box or toy; an uncategorised audio/video device passes only because the UUIDs
     * must confirm it); and its UUIDs are known, offer A2DP sink, handsfree or headset, and offer neither
     * HID nor HOGP. An audio link alone qualifies nothing.
     */
    static boolean unpairable(Entry e) {
        if (e == null || (e.type != TYPE_CLASSIC && e.type != TYPE_DUAL)) return false;
        if (e.major == MAJOR_PERIPHERAL || e.inputLink || !e.hidKnown) return false;
        if (!audioClass(e.deviceClass) || e.uuids == null) return false;
        boolean audio = false;
        for (String raw : e.uuids) {
            if (raw == null) continue;
            String u = raw.toLowerCase(Locale.ROOT);
            if (u.equals(UUID_HID) || u.equals(UUID_HOGP)) return false;
            if (u.equals(UUID_A2DP_SINK) || u.equals(UUID_HANDSFREE) || u.equals(UUID_HEADSET)) audio = true;
        }
        return audio;
    }

    private static boolean audioClass(int deviceClass) {
        switch (deviceClass) {
            case CLASS_AV_UNCATEGORIZED:
            case CLASS_WEARABLE_HEADSET:
            case CLASS_HANDSFREE:
            case CLASS_LOUDSPEAKER:
            case CLASS_HEADPHONES:
            case CLASS_PORTABLE_AUDIO:
            case CLASS_CAR_AUDIO:
            case CLASS_HIFI_AUDIO:
                return true;
            default:
                return false;
        }
    }

    /**
     * "audio" for an audio/video device, "input" for a peripheral (remote, keyboard, game pad); a
     * device with another or no class is named by the link it is connected on, else "other". For
     * display only: unpairable() never reads it.
     */
    static String kind(Entry e) {
        if (e.major == MAJOR_AUDIO_VIDEO) return "audio";
        if (e.major == MAJOR_PERIPHERAL) return "input";
        if (e.inputLink) return "input";
        if (e.audioLink) return "audio";
        return "other";
    }

    /** Audio: connected on A2DP or headset; input: on HID; other: on either. */
    static boolean connected(Entry e) {
        switch (kind(e)) {
            case "audio": return e.audioLink;
            case "input": return e.inputLink;
            default: return e.audioLink || e.inputLink;
        }
    }

    /** The name to show: trimmed, control characters dropped, at most NAME_MAX characters; UNNAMED if none is left. */
    static String name(String raw) {
        if (raw == null) return UNNAMED;
        StringBuilder b = new StringBuilder(Math.min(raw.length(), NAME_MAX + 8));
        for (int i = 0; i < raw.length(); ) {
            int c = raw.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isISOControl(c) || c == 0x2028 || c == 0x2029) continue;
            b.appendCodePoint(c);
        }
        String s = b.toString().trim();
        if (s.codePointCount(0, s.length()) > NAME_MAX) s = s.substring(0, s.offsetByCodePoints(0, NAME_MAX)).trim();
        return s.isEmpty() ? UNNAMED : s;
    }

    /**
     * The success answer: on = the adapter is on; canUnpair = removeBond resolved on this TV; devices
     * sorted connected first, then by name.
     */
    static String ok(boolean on, boolean canUnpair, List<Entry> bonded) {
        List<Object[]> rows = new ArrayList<>();
        if (bonded != null) {
            for (Entry e : bonded) {
                if (e != null) rows.add(new Object[]{name(e.name), kind(e), connected(e), e.id, unpairable(e)});
            }
        }
        rows.sort((a, b) -> {
            boolean ca = (Boolean) a[2], cb = (Boolean) b[2];
            if (ca != cb) return ca ? -1 : 1;
            String na = (String) a[0], nb = (String) b[0];
            int c = na.toLowerCase(Locale.ROOT).compareTo(nb.toLowerCase(Locale.ROOT));
            return c != 0 ? c : na.compareTo(nb);
        });
        UserLibrary.Json.W w = new UserLibrary.Json.W().beginObject().key("ok").value(true).key("on").value(on)
                .key("canUnpair").value(canUnpair).key("devices").beginArray();
        for (Object[] r : rows) {
            w.beginObject().key("name").value((String) r[0]).key("kind").value((String) r[1])
                    .key("connected").value((Boolean) r[2]).key("id").value((String) r[3])
                    .key("unpair").value((Boolean) r[4]).endObject();
        }
        return w.endArray().endObject().toString();
    }

    /** {"ok":false,"reason":...}. */
    static String fail(String reason) {
        return new UserLibrary.Json.W().beginObject().key("ok").value(false).key("reason").value(reason).endObject().toString();
    }

    /**
     * Why there is no list, or null to read it: no adapter, then the permission (BLUETOOTH_CONNECT on
     * Android 12+; on 11 and older BLUETOOTH, granted at install).
     */
    static String reason(boolean hasAdapter, boolean permitted) {
        if (!hasAdapter) return fail(NO_ADAPTER);
        if (!permitted) return fail(NO_PERMISSION);
        return null;
    }
}
