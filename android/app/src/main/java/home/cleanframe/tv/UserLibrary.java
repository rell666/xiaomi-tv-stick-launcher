package home.cleanframe.tv;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The stick's photo library (docs/PHOTO-GROUPS.md, contract 1): user groups and uploaded photos
 * under root R, and the upload token. Plain Java (no Android types, no org.json): the root, clock
 * and random are injected, so tests/fallback.test.sh runs tests/java/UserLibraryTest.java against
 * tests/fixtures/user-library offline. MainActivity only wires it up (bridge, /home/user/ route).
 *
 * Layout: R/groups/&lt;gid&gt;/group.json (user groups), R/groups/&lt;gid&gt;/photos/&lt;pid&gt;/ with the
 * seven JPEGs and meta.json, R/upload-token.json. Nothing is a shared mutable file: every group and
 * photo is its own directory, created as a dot-named tmp directory and renamed into place, removed
 * by renaming it to a dot name and deleting that. Readers skip dot-entries, symlinks, bad ids and
 * any photo without a valid meta.json and all seven files.
 *
 * Every public method that answers the page returns a JSON string and never throws.
 */
final class UserLibrary {
    static final int MAX_GROUPS = 20, MAX_PHOTOS_PER_GROUP = 60, MAX_PHOTOS = 300;
    static final int NAME_MAX = 24, PHOTO_NAME_MAX = 200;
    static final long TOKEN_TTL_MS = 15 * 60 * 1000L;
    static final String TOKEN_FILE = "upload-token.json";
    /** Largest meta.json / group.json / upload-token.json read; anything bigger is malformed. */
    static final int JSON_MAX_BYTES = 16 * 1024;
    static final Pattern USER_GID = Pattern.compile("g[0-9a-z]{8}");
    static final Pattern PID = Pattern.compile("p[0-9a-z]{10}");
    static final Pattern UPLOAD_BASE = Pattern.compile("http://([A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?):([0-9]{1,5})");
    /** Built-in groups, listed first, in this order; their names come from the app. */
    static final String[][] BUILTINS = {{"scenery", "Scenery"}, {"space", "Space"}, {"cyberpunk", "Cyberpunk"}};
    /** The seven files of a photo, and the only names the /home/user/ route serves. */
    static final List<String> PHOTO_FILES = Collections.unmodifiableList(Arrays.asList(
            "wall.jpg", "thumb.jpg", "panel.jpg", "field-blur.jpg", "field-clear.jpg", "field-frost.jpg", "study-soft.jpg"));
    /** What the /home/user/ route serves: the seven, and src.jpg (Adjust's upright copy, when the photo has one). */
    static final List<String> SERVED;

    static {
        List<String> s = new ArrayList<>(PHOTO_FILES);
        s.add("src.jpg");
        SERVED = Collections.unmodifiableList(s);
    }

    static final List<String> TONES = Arrays.asList("dark", "light");
    static final List<String> INKS = Arrays.asList("light", "mist", "dark");
    /** Upstream catalog's frost veils go up to 0.44 (main.js frostClass: .12 .20 .30 .36 .44). */
    static final double FROST_VEIL_MAX = 0.44;
    static final int LIST_COLUMN_MAX = 3;
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    private final Path root;
    private final LongSupplier clock;
    private final Random random;

    /**
     * library()'s listing, kept between calls (review 006cfe9: while Add photo is open the page asks for
     * library() and the phone for state() every 2 s, and each re-read every photo's files and meta.json,
     * up to 300). Every change to what is listed calls changed(), which drops it: addGroup, renameGroup,
     * deleteGroup, deletePhoto, and a photo moved into place (PendingPhotos.moveIntoPlace: an upload
     * finished, an Adjust swapped in, or the start-up sweep finishing a complete one; the sweep's other
     * removals, dot-named leftovers, are never listed).
     * A listing read while a change ran (listingGen moved) or while a directory could not be read is
     * not kept, and none is kept longer than LISTING_MAX_AGE_MS, so a stale one cannot outlive a minute.
     */
    static final long LISTING_MAX_AGE_MS = 60_000;
    private final Object listingLock = new Object();
    private Listing listing;
    private long listingAt, listingGen;
    private final java.util.concurrent.atomic.AtomicInteger readErrors = new java.util.concurrent.atomic.AtomicInteger();

    UserLibrary(File root, LongSupplier clock, Random random) {
        this.root = root.toPath().toAbsolutePath().normalize();
        this.clock = clock;
        this.random = random;
    }

    // ---------- ids and names ----------

    static boolean isBuiltin(String gid) {
        for (String[] b : BUILTINS) if (b[0].equals(gid)) return true;
        return false;
    }

    static boolean validUserGid(String gid) {
        return gid != null && USER_GID.matcher(gid).matches();
    }

    /** A group id the layout allows: a built-in or ^g[0-9a-z]{8}$. */
    static boolean validGid(String gid) {
        return isBuiltin(gid) || validUserGid(gid);
    }

    static boolean validPid(String pid) {
        return pid != null && PID.matcher(pid).matches();
    }

    /** The trimmed name if it is 1-NAME_MAX characters with no control characters, else null. */
    static String cleanName(String name) {
        if (name == null) return null;
        String t = name.trim();
        int n = t.codePointCount(0, t.length());
        if (n < 1 || n > NAME_MAX || hasControl(t)) return null;
        return t;
    }

    static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            int type = Character.getType(c);
            if (type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE) return true;
            i += Character.charCount(c);
        }
        return false;
    }

    /** "http://host:port" (port 1-65535) or null: the only form the Add photo screen shows. */
    static String validUploadBase(String base) {
        if (base == null) return null;
        java.util.regex.Matcher m = UPLOAD_BASE.matcher(base);
        if (!m.matches()) return null;
        int port = Integer.parseInt(m.group(2));
        return port >= 1 && port <= 65535 ? base : null;
    }

    // ---------- paths ----------

    private Path groupsDir() {
        return root.resolve("groups");
    }

    private Path groupDir(String gid) {
        return groupsDir().resolve(gid);
    }

    /** R/groups/&lt;gid&gt;/photos (PendingPhotos keeps unfinished photos there as dot-named tmp dirs). */
    Path photosDir(String gid) {
        return groupDir(gid).resolve("photos");
    }

    /** R itself (absolute, normalised). */
    Path root() {
        return root;
    }

    Path tokenFile() {
        return root.resolve(TOKEN_FILE);
    }

    /**
     * The file for the route /home/user/&lt;gid&gt;/&lt;pid&gt;/&lt;name&gt; (rel = "gid/pid/name"), or null:
     * exactly three segments, a valid gid and pid, one of SERVED, an existing regular file
     * whose canonical path stays under R's canonical path.
     */
    File photoFile(String rel) {
        try {
            if (rel == null) return null;
            String[] s = rel.split("/", -1);
            if (s.length != 3 || !validGid(s[0]) || !validPid(s[1]) || !SERVED.contains(s[2])) return null;
            File f = photosDir(s[0]).resolve(s[1]).resolve(s[2]).toFile();
            String canonRoot = root.toFile().getCanonicalPath() + File.separator;
            String canon = f.getCanonicalPath();
            if (!canon.startsWith(canonRoot) || !f.isFile()) return null;
            return f;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---------- listing ----------

    static final class Group {
        final String id, name;
        final long created;
        final boolean builtin;

        Group(String id, String name, long created, boolean builtin) {
            this.id = id;
            this.name = name;
            this.created = created;
            this.builtin = builtin;
        }
    }

    static final class Photo {
        final String group, id, name, tone, tr, br;
        final long added, listColumn;
        final Number frostVeil;
        final boolean dockQuiet, clockMist;
        /** Adjust: src.jpg is there; the crop its wall was made with (null: older photos, the centre); when it was last adjusted (0: never). */
        boolean src;
        PhotoMath.Crop crop;
        long edited;

        Photo(String group, String id, Map<String, Object> m) {
            this.group = group;
            this.id = id;
            @SuppressWarnings("unchecked") Map<String, Object> ut = (Map<String, Object>) m.get("ut");
            name = (String) m.get("name");
            added = (Long) m.get("added");
            tone = (String) m.get("tone");
            tr = (String) ut.get("tr");
            br = (String) ut.get("br");
            frostVeil = (Number) m.get("frostVeil");
            listColumn = (Long) m.get("listColumn");
            dockQuiet = (Boolean) m.get("dockQuiet");
            clockMist = (Boolean) m.get("clockMist");
            crop = PhotoMath.metaCrop(m);
            Object e = m.get("edited");
            edited = e instanceof Long && (Long) e > 0 ? (Long) e : 0;
        }
    }

    /** Built-ins first, then valid user groups by created (then id). */
    List<Group> groups() {
        List<Group> out = new ArrayList<>();
        for (String[] b : BUILTINS) out.add(new Group(b[0], b[1], 0, true));
        List<Group> user = new ArrayList<>();
        for (Path d : entries(groupsDir())) {
            String gid = d.getFileName().toString();
            if (!validUserGid(gid) || !Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) continue;
            Group g = readGroup(gid);
            if (g != null) user.add(g);
        }
        user.sort((a, b) -> a.created != b.created ? Long.compare(a.created, b.created) : a.id.compareTo(b.id));
        out.addAll(user);
        return out;
    }

    /** A user group's group.json, or null if missing or malformed (v 1, id = the directory, a valid name). */
    Group readGroup(String gid) {
        if (!validUserGid(gid)) return null;
        Map<String, Object> m = readObject(groupDir(gid).resolve("group.json"));
        if (m == null || !Long.valueOf(1).equals(m.get("v")) || !gid.equals(m.get("id"))) return null;
        Object name = m.get("name"), created = m.get("created");
        if (!(name instanceof String) || !name.equals(cleanName((String) name))) return null;
        if (!(created instanceof Long) || (Long) created < 0) return null;
        return new Group(gid, (String) name, (Long) created, false);
    }

    /** Valid photos of one group, by added (then id). */
    List<Photo> photos(String gid) {
        List<Photo> out = new ArrayList<>();
        for (Path d : entries(photosDir(gid))) {
            String pid = d.getFileName().toString();
            if (!validPid(pid) || !Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) continue;
            Photo p = readPhoto(gid, pid, d);
            if (p != null) out.add(p);
        }
        out.sort((a, b) -> a.added != b.added ? Long.compare(a.added, b.added) : a.id.compareTo(b.id));
        return out;
    }

    private Photo readPhoto(String gid, String pid, Path dir) {
        for (String f : PHOTO_FILES) {
            if (!Files.isRegularFile(dir.resolve(f), LinkOption.NOFOLLOW_LINKS)) return null;
        }
        Map<String, Object> m = readObject(dir.resolve("meta.json"));
        if (m == null || !validMeta(m, pid)) return null;
        Photo p = new Photo(gid, pid, m);
        p.src = Files.isRegularFile(dir.resolve("src.jpg"), LinkOption.NOFOLLOW_LINKS);
        return p;
    }

    /** meta.json per contract 1, with upstream catalog value sets. Extra keys are ignored. */
    static boolean validMeta(Map<String, Object> m, String pid) {
        if (!Long.valueOf(1).equals(m.get("v")) || !pid.equals(m.get("id"))) return false;
        Object name = m.get("name"), added = m.get("added"), ut = m.get("ut"), fv = m.get("frostVeil"),
                lc = m.get("listColumn");
        if (!(name instanceof String)) return false;
        String n = (String) name;
        int len = n.codePointCount(0, n.length());
        if (n.trim().isEmpty() || len > PHOTO_NAME_MAX || hasControl(n)) return false;
        if (!(added instanceof Long) || (Long) added < 0) return false;
        if (!TONES.contains(m.get("tone"))) return false;
        if (!(ut instanceof Map)) return false;
        Map<?, ?> u = (Map<?, ?>) ut;
        if (!INKS.contains(u.get("tr")) || !INKS.contains(u.get("br"))) return false;
        if (!(fv instanceof Number)) return false;
        double v = ((Number) fv).doubleValue();
        if (!(v >= 0 && v <= FROST_VEIL_MAX)) return false;
        if (!(lc instanceof Long) || (Long) lc < 0 || (Long) lc > LIST_COLUMN_MAX) return false;
        return m.get("dockQuiet") instanceof Boolean && m.get("clockMist") instanceof Boolean;
    }

    /** Non-dot, non-symlink entries of dir in name order; empty if dir is missing or unreadable (counted in readErrors). */
    private List<Path> entries(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.startsWith(".") || Files.isSymbolicLink(p)) continue;
                out.add(p);
            }
        } catch (IOException | RuntimeException e) {
            readErrors.incrementAndGet();
            return new ArrayList<>();
        }
        out.sort(null);
        return out;
    }

    /** The groups and every listed photo (allPhotos order), and library()'s JSON of them; read-only. */
    static final class Listing {
        final List<Group> groups;
        final List<Photo> photos;
        final String json;

        Listing(List<Group> groups, List<Photo> photos, String json) {
            this.groups = Collections.unmodifiableList(groups);
            this.photos = Collections.unmodifiableList(photos);
            this.json = json;
        }
    }

    /** The listing, from the cache when no change has been made since it was read (see listing above). */
    synchronized Listing listing() {
        long now = clock.getAsLong(), gen;
        synchronized (listingLock) {
            if (listing != null && now >= listingAt && now - listingAt < LISTING_MAX_AGE_MS) return listing;
            gen = listingGen;
        }
        int errors = readErrors.get();
        List<Group> gs = groups();
        List<Photo> ps = allPhotos(gs);
        Listing l = new Listing(gs, ps, libraryJson(gs, ps));
        synchronized (listingLock) {
            if (listingGen == gen && readErrors.get() == errors) {
                listing = l;
                listingAt = now;
            }
        }
        return l;
    }

    /** The library changed on disk: the next listing() reads it again. Never blocks on the library's lock. */
    void changed() {
        synchronized (listingLock) {
            listingGen++;
            listing = null;
        }
    }

    /** The bridge's library(): {"v":1,"groups":[...],"photos":[...]} (contract 2). */
    synchronized String library() {
        try {
            return listing().json;
        } catch (RuntimeException e) {
            return emptyLibrary();
        }
    }

    private static String libraryJson(List<Group> gs, List<Photo> ps) {
        Json.W w = new Json.W();
        w.beginObject().key("v").value(1).key("groups").beginArray();
        for (Group g : gs) {
            w.beginObject().key("id").value(g.id).key("name").value(g.name).key("created").value(g.created)
                    .key("builtin").value(g.builtin).endObject();
        }
        w.endArray().key("photos").beginArray();
        for (Photo p : ps) {
            {
                w.beginObject().key("group").value(p.group).key("id").value(p.id).key("name").value(p.name)
                        .key("added").value(p.added).key("tone").value(p.tone)
                        .key("ut").beginObject().key("tr").value(p.tr).key("br").value(p.br).endObject()
                        .key("frostVeil").value(p.frostVeil).key("listColumn").value(p.listColumn)
                        .key("dockQuiet").value(p.dockQuiet).key("clockMist").value(p.clockMist);
                if (p.src) w.key("src").value(true);
                if (p.crop != null) {
                    w.key("crop").beginObject().key("x").value((Number) PhotoMath.round6(p.crop.x))
                            .key("y").value((Number) PhotoMath.round6(p.crop.y)).key("z").value((Number) PhotoMath.round6(p.crop.z)).endObject();
                }
                if (p.edited > 0) w.key("edited").value(p.edited);
                w.endObject();
            }
        }
        return w.endArray().endObject().toString();
    }

    /**
     * Every listed photo, by added, then group order, then id. This is the order
     * tests/fixtures/user-library/expected.json gives (gholiday1's photos before the older-listed
     * space group's later one); its note's "(group order, then added)" does not match its own data.
     */
    static List<Photo> allPhotos(List<Group> gs, UserLibrary lib) {
        List<Photo> out = new ArrayList<>();
        Map<String, Integer> order = new LinkedHashMap<>();
        for (Group g : gs) {
            order.put(g.id, order.size());
            out.addAll(lib.photos(g.id));
        }
        out.sort((a, b) -> a.added != b.added ? Long.compare(a.added, b.added)
                : !a.group.equals(b.group) ? Integer.compare(order.get(a.group), order.get(b.group)) : a.id.compareTo(b.id));
        return out;
    }

    private List<Photo> allPhotos(List<Group> gs) {
        return allPhotos(gs, this);
    }

    /** library() when R cannot be read at all: the built-ins, no photos. */
    static String emptyLibrary() {
        Json.W w = new Json.W().beginObject().key("v").value(1).key("groups").beginArray();
        for (String[] b : BUILTINS) {
            w.beginObject().key("id").value(b[0]).key("name").value(b[1]).key("created").value(0)
                    .key("builtin").value(true).endObject();
        }
        return w.endArray().key("photos").beginArray().endArray().endObject().toString();
    }

    /** Photos that may still be added to gid: limited by MAX_PHOTOS_PER_GROUP and MAX_PHOTOS in all. */
    int photoRoom(String gid) {
        int all = 0, here = 0;
        for (Group g : groups()) {
            int n = photos(g.id).size();
            all += n;
            if (g.id.equals(gid)) here = n;
        }
        return Math.max(0, Math.min(MAX_PHOTOS_PER_GROUP - here, MAX_PHOTOS - all));
    }

    boolean groupExists(String gid) {
        return isBuiltin(gid) || readGroup(gid) != null;
    }

    // ---------- changes ----------

    static final String BAD_NAME = "Names are 1 to " + NAME_MAX + " characters, with no control characters.";
    static final String TOO_MANY_GROUPS = "There are already " + MAX_GROUPS + " groups. Delete one first.";
    static final String NO_GROUP = "That group no longer exists.";
    static final String BUILTIN = "Built-in groups cannot be renamed or deleted.";
    static final String NO_PHOTO = "That photo no longer exists.";
    static final String FULL = "This group is full (" + MAX_PHOTOS_PER_GROUP + " photos, " + MAX_PHOTOS + " in all).";
    static final String BAD_BASE = "The upload address is not http://host:port.";
    static final String FAILED = "Could not save to the TV's storage.";

    synchronized String addGroup(String name) {
        try {
            String n = cleanName(name);
            if (n == null) return fail(BAD_NAME);
            if (groups().size() - BUILTINS.length >= MAX_GROUPS) return fail(TOO_MANY_GROUPS);
            String gid = null;
            for (int i = 0; i < 100 && gid == null; i++) {
                String c = "g" + randomId(8);
                if (!Files.exists(groupDir(c), LinkOption.NOFOLLOW_LINKS) && !Files.exists(groupsDir().resolve("." + c + ".tmp"),
                        LinkOption.NOFOLLOW_LINKS)) gid = c;
            }
            if (gid == null) return fail(FAILED);
            Path tmp = groupsDir().resolve("." + gid + ".tmp");
            Files.createDirectories(tmp);
            try {
                Files.write(tmp.resolve("group.json"), groupJson(gid, n, clock.getAsLong()));
                Files.move(tmp, groupDir(gid), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                deleteTree(tmp);
                throw e;
            }
            return new Json.W().beginObject().key("ok").value(true).key("id").value(gid).endObject().toString();
        } catch (IOException | RuntimeException e) {
            return fail(FAILED);
        } finally {
            changed();
        }
    }

    synchronized String renameGroup(String gid, String name) {
        try {
            if (isBuiltin(gid)) return fail(BUILTIN);
            Group g = readGroup(gid);
            if (g == null) return fail(NO_GROUP);
            String n = cleanName(name);
            if (n == null) return fail(BAD_NAME);
            writeAtomically(groupDir(gid).resolve("group.json"), groupJson(gid, n, g.created));
            return ok();
        } catch (IOException | RuntimeException e) {
            return fail(FAILED);
        } finally {
            changed();
        }
    }

    /**
     * A user group and every photo in it; the upload token too if it was for this group. ok once the
     * group is renamed out of the list, even if deleting its files then fails (review 999ba07, L4: the
     * page was told it failed while the group was already gone); removeLeftovers() finishes that.
     * codeDropped[0] is set when the token went with it.
     */
    private synchronized String deleteGroup(String gid, boolean[] codeDropped) {
        try {
            if (isBuiltin(gid)) return fail(BUILTIN);
            if (!validUserGid(gid) || !Files.isDirectory(groupDir(gid), LinkOption.NOFOLLOW_LINKS)) return fail(NO_GROUP);
            removeDir(groupsDir(), gid);
            Map<String, Object> t = readObject(tokenFile());
            if (t != null && gid.equals(t.get("group"))) {
                codeDropped[0] = true;
                uploadStop();
            }
            return ok();
        } catch (IOException | RuntimeException e) {
            return fail(FAILED);
        } finally {
            changed();
        }
    }

    String deleteGroup(String gid) {
        return deleteGroup(gid, (Runnable) null);
    }

    /**
     * deleteGroup(gid), then, when the group held the upload code, codeDropped (MainActivity's
     * stopUpload(): the upload page stops listening at once, not when Add photo closes; review
     * 006cfe9). It runs after this library's lock is released (stopUpload() orders itself against
     * uploadStart() through UploadGate, whose start takes this lock), and never throws.
     */
    String deleteGroup(String gid, Runnable codeDropped) {
        boolean[] dropped = new boolean[1];
        String answer = deleteGroup(gid, dropped);
        if (dropped[0] && codeDropped != null) {
            try {
                codeDropped.run();
            } catch (RuntimeException e) {
                // the token is gone already; the server stops with the screen
            }
        }
        return answer;
    }

    /** One uploaded photo, from a built-in or a user group. */
    synchronized String deletePhoto(String gid, String pid) {
        try {
            if (!validGid(gid) || !groupExists(gid)) return fail(NO_GROUP);
            if (!validPid(pid) || !Files.isDirectory(photosDir(gid).resolve(pid), LinkOption.NOFOLLOW_LINKS)) return fail(NO_PHOTO);
            removeDir(photosDir(gid), pid);
            return ok();
        } catch (IOException | RuntimeException e) {
            return fail(FAILED);
        } finally {
            changed();
        }
    }

    /**
     * Wrong codes typed against the code now issued (UploadServer.checkCode() reports each through
     * codeWrong()). At the next renewal after CODE_ROTATE_AFTER of them the code is replaced (review
     * 30e7356, A4: it never changed while Add photo stayed open, so guesses added up for as long as the
     * screen did). 10 is one client address's limit for a minute (UploadServer.CODE_FAILS_PER_MIN):
     * a phone mistyping a few times keeps the code it is reading, a guesser gets a new one to find.
     * Counted in memory only (a new process starts again at 0).
     */
    static final int CODE_ROTATE_AFTER = 10;
    private int wrongCodes;

    /** One wrong code typed against the current code (UploadServer.checkCode). */
    synchronized void codeWrong() {
        wrongCodes++;
    }

    /**
     * The upload token for gid: {token 32 hex, code 6 digits, group, expires = now + TOKEN_TTL_MS},
     * written to R/upload-token.json via a tmp file and a rename. A live token for the same gid is
     * renewed (same token, later expiry) so a phone already on its URL is not cut off, with the same
     * code unless CODE_ROTATE_AFTER wrong codes were typed against it (then a new one); a different
     * group, an expired token or none gets a fresh one. uploadStop() ends it.
     */
    synchronized String uploadStart(String gid, String base) {
        try {
            String b = validUploadBase(base);
            if (b == null) return fail(BAD_BASE);
            if (!validGid(gid) || !groupExists(gid)) return fail(NO_GROUP);
            if (photoRoom(gid) == 0) return fail(FULL);
            Map<String, Object> live = currentToken();
            String tok, code;
            if (live != null && gid.equals(live.get("group"))) {
                tok = (String) live.get("token");
                code = (String) live.get("code");
                if (wrongCodes >= CODE_ROTATE_AFTER) {
                    String old = code;
                    do {
                        code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000));
                    } while (code.equals(old));
                    wrongCodes = 0;
                }
            } else {
                StringBuilder t = new StringBuilder(32);
                for (int i = 0; i < 32; i++) t.append(ALPHABET.charAt(random.nextInt(16)));
                tok = t.toString();
                code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000));
                wrongCodes = 0;
            }
            long expires = clock.getAsLong() + TOKEN_TTL_MS;
            byte[] json = new Json.W().beginObject().key("v").value(1).key("token").value(tok)
                    .key("code").value(code).key("group").value(gid).key("expires").value(expires).endObject()
                    .toString().getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(root);
            writeAtomically(tokenFile(), json);
            return new Json.W().beginObject().key("ok").value(true).key("url").value(b + "/u/" + tok)
                    .key("base").value(b).key("code").value(code).key("expires").value(expires).endObject().toString();
        } catch (IOException | RuntimeException e) {
            return fail(FAILED);
        }
    }

    /** Removes the token (and a leftover tmp file). Always {"ok":true} unless the file stays. */
    synchronized String uploadStop() {
        wrongCodes = 0;
        try {
            removeToken(root);
            return ok();
        } catch (IOException | RuntimeException e) {
            return fail(FAILED);
        }
    }

    /**
     * The token file and its tmp file under R, without a UserLibrary or its lock: MainActivity.stopUpload()
     * on the UI thread (making the library there would run start-up work on that thread, and its lock
     * may be held by that work), ordered against uploadStart() by UploadGate (stop() runs it under the gate's lock).
     */
    static void removeToken(Path root) throws IOException {
        Files.deleteIfExists(root.resolve(TOKEN_FILE));
        Files.deleteIfExists(root.resolve(".upload-token.json.tmp"));
    }

    /** The token on disk if it is well-formed and not expired (now &lt; expires), else null. */
    synchronized Map<String, Object> currentToken() {
        Map<String, Object> t = readObject(tokenFile());
        if (t == null || !Long.valueOf(1).equals(t.get("v"))) return null;
        Object tok = t.get("token"), code = t.get("code"), group = t.get("group"), exp = t.get("expires");
        if (!(tok instanceof String) || !((String) tok).matches("[0-9a-f]{32}")) return null;
        if (!(code instanceof String) || !((String) code).matches("[0-9]{6}")) return null;
        if (!(group instanceof String) || !validGid((String) group)) return null;
        if (!(exp instanceof Long) || clock.getAsLong() >= (Long) exp) return null;
        return t;
    }

    // ---------- helpers ----------

    private String randomId(int n) {
        StringBuilder s = new StringBuilder(n);
        for (int i = 0; i < n; i++) s.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return s.toString();
    }

    static byte[] groupJson(String gid, String name, long created) {
        return new Json.W().beginObject().key("v").value(1).key("id").value(gid).key("name").value(name)
                .key("created").value(created).endObject().toString().getBytes(StandardCharsets.UTF_8);
    }

    /** dir/.name.tmp, then one rename over dir/name, so a reader sees the old file or the new one. */
    static void writeAtomically(Path file, byte[] data) throws IOException {
        Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Rename parent/name to a dot name (gone for readers at once: only a failed rename throws), then
     * delete it, links not followed. A delete that fails leaves .&lt;name&gt;.del-* for the start-up sweep
     * (removeLeftovers() in groups/, PendingPhotos.removeStale() in a photos/ folder).
     */
    private void removeDir(Path parent, String name) throws IOException {
        Path gone = parent.resolve("." + name + ".del-" + randomId(6));
        Files.move(parent.resolve(name), gone, StandardCopyOption.ATOMIC_MOVE);
        try {
            deleteTree(gone);
        } catch (IOException | RuntimeException e) {
            // already gone from the list; swept at the next start
        }
    }

    /** groups/.&lt;gid&gt;.tmp (an addGroup() that failed) and groups/.&lt;gid&gt;.del-* (a deleteGroup() whose delete failed). */
    static final Pattern GROUP_LEFTOVER = Pattern.compile("\\.g[0-9a-z]{8}\\.(?:tmp|del-[0-9a-z]+)");

    /**
     * Start-up clean-up of groups/ itself (PendingPhotos.removeStale() calls it; it sweeps photos/
     * folders): every GROUP_LEFTOVER directly under groups/, links not followed. Under this library's
     * lock, so no addGroup() or deleteGroup() is between its steps. Returns how many were removed.
     */
    synchronized int removeLeftovers() {
        List<Path> gone = new ArrayList<>();
        Path groups = groupsDir();
        if (!Files.isDirectory(groups, LinkOption.NOFOLLOW_LINKS)) return 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(groups)) {
            for (Path p : ds) {
                if (GROUP_LEFTOVER.matcher(p.getFileName().toString()).matches() && !Files.isSymbolicLink(p)) gone.add(p);
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
        int n = 0;
        for (Path p : gone) {
            try {
                deleteTree(p);
                n++;
            } catch (IOException | RuntimeException e) {
                // tried again at the next start
            }
        }
        return n;
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** A JSON object from a regular file of at most JSON_MAX_BYTES, or null (missing, too big, malformed). */
    static Map<String, Object> readObject(Path f) {
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > JSON_MAX_BYTES) return null;
            Object o = Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked") Map<String, Object> m = o instanceof Map ? (Map<String, Object>) o : null;
            return m;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static String ok() {
        return "{\"ok\":true}";
    }

    static String fail(String reason) {
        return new Json.W().beginObject().key("ok").value(false).key("reason").value(reason).endObject().toString();
    }

    /**
     * Minimal JSON: a writer for the bridge answers and the files we write, and a strict reader
     * (RFC 8259: no trailing commas, comments, NaN, duplicate keys, raw control characters or
     * trailing text; depth at most 16) for meta.json, group.json and upload-token.json. Integers
     * that fit a long read as Long, other numbers as Double; null reads as Json.NULL.
     */
    static final class Json {
        static final Object NULL = new Object() {
            @Override
            public String toString() {
                return "null";
            }
        };
        static final int MAX_DEPTH = 16;

        static final class Malformed extends RuntimeException {
            Malformed(String m, int at) {
                super(m + " at " + at);
            }
        }

        static Object parse(String s) {
            Json.R r = new Json.R(s);
            r.ws();
            Object v = r.value(0);
            r.ws();
            if (r.i != s.length()) throw new Malformed("trailing text", r.i);
            return v;
        }

        private static final class R {
            final String s;
            int i;

            R(String s) {
                this.s = s;
            }

            void ws() {
                while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0) i++;
            }

            char peek() {
                if (i >= s.length()) throw new Malformed("unexpected end", i);
                return s.charAt(i);
            }

            void expect(char c) {
                if (peek() != c) throw new Malformed("expected '" + c + "'", i);
                i++;
            }

            Object value(int depth) {
                if (depth >= MAX_DEPTH) throw new Malformed("too deep", i); // depth 0 is the outermost value
                char c = peek();
                if (c == '{') return object(depth);
                if (c == '[') return array(depth);
                if (c == '"') return string();
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
                if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
                if (s.startsWith("null", i)) { i += 4; return NULL; }
                throw new Malformed("unexpected '" + c + "'", i);
            }

            Map<String, Object> object(int depth) {
                Map<String, Object> m = new LinkedHashMap<>();
                expect('{');
                ws();
                if (peek() == '}') { i++; return m; }
                while (true) {
                    ws();
                    if (peek() != '"') throw new Malformed("expected a key", i);
                    int at = i;
                    String k = string();
                    ws();
                    expect(':');
                    ws();
                    if (m.containsKey(k)) throw new Malformed("duplicate key " + k, at);
                    m.put(k, value(depth + 1));
                    ws();
                    if (peek() == ',') { i++; continue; }
                    expect('}');
                    return m;
                }
            }

            List<Object> array(int depth) {
                List<Object> a = new ArrayList<>();
                expect('[');
                ws();
                if (peek() == ']') { i++; return a; }
                while (true) {
                    ws();
                    a.add(value(depth + 1));
                    ws();
                    if (peek() == ',') { i++; continue; }
                    expect(']');
                    return a;
                }
            }

            String string() {
                expect('"');
                StringBuilder b = new StringBuilder();
                while (true) {
                    char c = peek();
                    i++;
                    if (c == '"') return b.toString();
                    if (c < 0x20) throw new Malformed("raw control character in a string", i - 1);
                    if (c != '\\') { b.append(c); continue; }
                    char e = peek();
                    i++;
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new Malformed("short \\u escape", i);
                            int v = 0;
                            for (int k = 0; k < 4; k++) {
                                int d = Character.digit(s.charAt(i + k), 16);
                                if (d < 0) throw new Malformed("bad \\u escape", i);
                                v = v * 16 + d;
                            }
                            i += 4;
                            b.append((char) v);
                            break;
                        default:
                            throw new Malformed("bad escape \\" + e, i - 1);
                    }
                }
            }

            Object number() {
                int start = i;
                if (peek() == '-') i++;
                if (peek() == '0') {
                    i++;
                } else if (peek() >= '1' && peek() <= '9') {
                    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                } else {
                    throw new Malformed("bad number", start);
                }
                boolean integral = true;
                if (i < s.length() && s.charAt(i) == '.') {
                    integral = false;
                    i++;
                    int d = i;
                    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                    if (i == d) throw new Malformed("bad fraction", start);
                }
                if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                    integral = false;
                    i++;
                    if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                    int d = i;
                    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                    if (i == d) throw new Malformed("bad exponent", start);
                }
                String t = s.substring(start, i);
                if (integral) {
                    try {
                        return Long.parseLong(t);
                    } catch (NumberFormatException e) {
                        // too big for a long: falls through to a double
                    }
                }
                double v = Double.parseDouble(t);
                if (Double.isInfinite(v)) throw new Malformed("number out of range", start);
                return v;
            }
        }

        /** Writer: keys and values in the order given; commas are placed automatically. */
        static final class W {
            private final StringBuilder b = new StringBuilder();
            private boolean first = true;

            private void sep() {
                if (!first) b.append(',');
                first = false;
            }

            W beginObject() {
                sep();
                b.append('{');
                first = true;
                return this;
            }

            W endObject() {
                b.append('}');
                first = false;
                return this;
            }

            W beginArray() {
                sep();
                b.append('[');
                first = true;
                return this;
            }

            W endArray() {
                b.append(']');
                first = false;
                return this;
            }

            W key(String k) {
                sep();
                quote(k);
                b.append(':');
                first = true; // the value that follows takes no comma
                return this;
            }

            W value(String v) {
                sep();
                if (v == null) b.append("null"); else quote(v);
                return this;
            }

            W value(long v) {
                sep();
                b.append(v);
                return this;
            }

            W value(boolean v) {
                sep();
                b.append(v);
                return this;
            }

            W value(Number v) {
                sep();
                if (v instanceof Long || v instanceof Integer) {
                    b.append(v.longValue());
                } else {
                    double d = v.doubleValue();
                    if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("not a JSON number: " + d);
                    if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d); else b.append(d);
                }
                return this;
            }

            private void quote(String s) {
                b.append('"');
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    switch (c) {
                        case '"': b.append("\\\""); break;
                        case '\\': b.append("\\\\"); break;
                        case '\n': b.append("\\n"); break;
                        case '\r': b.append("\\r"); break;
                        case '\t': b.append("\\t"); break;
                        default:
                            if (c < 0x20 || c == ' ' || c == ' ' || c == 0x7f) {
                                b.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                            } else {
                                b.append(c);
                            }
                    }
                }
                b.append('"');
            }

            @Override
            public String toString() {
                return b.toString();
            }
        }
    }
}
