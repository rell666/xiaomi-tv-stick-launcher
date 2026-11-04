package home.cleanframe.tv;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Photos still being prepared (docs/PHOTO-GROUPS.md, contracts 1 and 4): R/groups/&lt;gid&gt;/photos/.&lt;pid&gt;.tmp/.
 * UploadServer creates one per upload and the processor writes wall.jpg, thumb.jpg and meta.json
 * into it; the page then draws the panel and the four materials (web/materials.js) from
 * /home/user-tmp/&lt;gid&gt;/&lt;pid&gt;/wall.jpg and hands each back through AndroidHome.saveMaterial(),
 * which lands here. When all seven files and meta.json are there the directory is renamed to
 * &lt;pid&gt; (one rename: readers see the whole photo or nothing). Plain Java, tested offline by
 * tests/java/PendingPhotosTest.java. Every method that answers the page returns JSON and never throws.
 */
final class PendingPhotos {
    /** The materials the page draws, with each one's size: upstream's &lt;id&gt;-panel.jpg (web/assets/wallpapers/) and &lt;id&gt;-&lt;kind&gt;.jpg (web/assets/materials/). */
    static final String[] KINDS = {"panel", "field-blur", "field-clear", "field-frost", "study-soft"};
    static final int[][] SIZES = {{320, 180}, {1920, 1080}, {1920, 1080}, {1920, 1080}, {1920, 1080}};
    static final int MAX_MATERIAL_BYTES = 1024 * 1024;
    static final String DATA_URL_PREFIX = "data:image/jpeg;base64,";
    /** Unfinished photos older than this are removed at start-up. */
    static final long STALE_MS = 60 * 60 * 1000L;
    static final String TMP_ROUTE_FILE = "wall.jpg";
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    static final String BAD_KIND = "Unknown material.";
    static final String BAD_DATA = "The material is not a JPEG data URL.";
    static final String TOO_BIG = "The material is over 1 MB.";
    static final String BAD_JPEG = "The material is not a whole JPEG.";
    static final String METADATA = "The material carries metadata (EXIF, XMP or a comment).";
    static final String NO_JOB = "That photo is no longer waiting for its materials.";
    static final String NO_SRC = "This photo was added before Adjust. Send it again from the phone to adjust it.";
    static final String BUSY = "This photo is still being saved. Try again in a moment.";
    static final String BAD_CROP = "The crop was not understood.";
    static final String RECROP_FAILED = "Could not save the new crop.";

    /** Adjust in progress, by "gid/pid": "working" while the photo is re-made, else why it failed (read once). */
    private final Map<String, String> recrops = new HashMap<>();

    private final UserLibrary lib;
    private final LongSupplier clock;
    private final Random random;

    PendingPhotos(UserLibrary lib, LongSupplier clock, Random random) {
        this.lib = lib;
        this.clock = clock;
        this.random = random;
    }

    static String tmpName(String pid) {
        return "." + pid + ".tmp";
    }

    Path tmpDir(String gid, String pid) {
        return lib.photosDir(gid).resolve(tmpName(pid));
    }

    static int kindIndex(String kind) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(kind)) return i;
        return -1;
    }

    /**
     * An Adjust's .&lt;pid&gt;.tmp (it sits beside the photo pid) that nothing will finish: no Adjust in
     * THIS process is re-making it (recrops "working") and it holds no finished re-make (job() is
     * null: wall.jpg, thumb.jpg and a valid meta.json, whose materials the page still draws, even
     * after a restart). The process died mid-Adjust (review 006cfe9): such a folder answered "still
     * being saved" to every Adjust, said "saving" for ever, counted against the limits and was only
     * removed once an hour old. It is treated as gone: not counted, not saving, replaced by the next
     * Adjust and removed at start-up. Caller holds this lock.
     */
    private boolean deadRecrop(String gid, String pid, Path d) {
        return Files.isDirectory(lib.photosDir(gid).resolve(pid), LinkOption.NOFOLLOW_LINKS)
                && !"working".equals(recrops.get(gid + "/" + pid)) && job(gid, pid, d) == null;
    }

    /**
     * A new, empty .&lt;pid&gt;.tmp in gid (an existing group); its pid. The check and the mkdirs hold the
     * library's lock, which UserLibrary.deleteGroup() holds too (Bridge.deleteGroup does not go through
     * UploadServer's lock): a group deleted in between used to get groups/&lt;gid&gt;/photos made again,
     * an orphan folder. Lock order: this, then the library (never the other way round).
     */
    synchronized String create(String gid) throws IOException {
        synchronized (lib) {
            if (!UserLibrary.validGid(gid) || !lib.groupExists(gid)) throw new IOException("no such group");
            Path photos = lib.photosDir(gid);
            Files.createDirectories(photos);
            for (int i = 0; i < 100; i++) {
                StringBuilder s = new StringBuilder("p");
                for (int k = 0; k < 10; k++) s.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
                String pid = s.toString();
                if (Files.exists(photos.resolve(pid), LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    Files.createDirectory(photos.resolve(tmpName(pid)));
                    return pid;
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    // taken: try another
                }
            }
            throw new IOException("no free photo id");
        }
    }

    /**
     * Removes an unfinished photo (its processing failed, or the phone was told so); never throws. It is
     * first renamed to .&lt;pid&gt;.del-&lt;random&gt;, which jobs() and count() never read, so a processing thread
     * still writing into .&lt;pid&gt;.tmp (a decode past its deadline cannot be stopped) has no directory to
     * write meta.json into, and a delete that fails while it writes cannot leave a job behind (review
     * 999ba07, L3). What is left is removed by removeStale() at the next start.
     */
    synchronized void discard(String gid, String pid) {
        if (!UserLibrary.validGid(gid) || !UserLibrary.validPid(pid)) return;
        Path d = tmpDir(gid, pid);
        if (!Files.exists(d, LinkOption.NOFOLLOW_LINKS)) return;
        StringBuilder s = new StringBuilder("." + pid + ".del-");
        for (int k = 0; k < 6; k++) s.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        Path gone = d.resolveSibling(s.toString());
        try {
            Files.move(d, gone, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            gone = d; // could not be renamed: deleted where it is
        }
        try {
            UserLibrary.deleteTree(gone);
        } catch (IOException | RuntimeException e) {
            // left for removeStale()
        }
    }

    static final class Job {
        final String gid, pid, name, tone;
        final long added;
        final List<String> need;

        Job(String gid, String pid, String name, String tone, long added, List<String> need) {
            this.gid = gid;
            this.pid = pid;
            this.name = name;
            this.tone = tone;
            this.added = added;
            this.need = need;
        }
    }

    /**
     * Every unfinished photo whose processing is done (wall.jpg, thumb.jpg and a valid meta.json in
     * its tmp directory) in a listed group, oldest first; need = the materials it still lacks.
     */
    synchronized List<Job> jobs() {
        List<Job> out = new ArrayList<>();
        for (UserLibrary.Group g : lib.groups()) {
            Path photos = lib.photosDir(g.id);
            if (!Files.isDirectory(photos, LinkOption.NOFOLLOW_LINKS)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(photos)) {
                for (Path d : ds) {
                    String n = d.getFileName().toString();
                    if (!n.startsWith(".") || !n.endsWith(".tmp")) continue;
                    String pid = n.substring(1, n.length() - 4);
                    if (!UserLibrary.validPid(pid) || !Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) continue;
                    Job j = job(g.id, pid, d);
                    if (j != null) out.add(j);
                }
            } catch (IOException | RuntimeException e) {
                // an unreadable group lists no jobs
            }
        }
        out.sort((a, b) -> a.added != b.added ? Long.compare(a.added, b.added)
                : !a.gid.equals(b.gid) ? a.gid.compareTo(b.gid) : a.pid.compareTo(b.pid));
        return out;
    }

    private static Job job(String gid, String pid, Path d) {
        for (String f : new String[]{"wall.jpg", "thumb.jpg", "meta.json"}) {
            if (!Files.isRegularFile(d.resolve(f), LinkOption.NOFOLLOW_LINKS)) return null;
        }
        Map<String, Object> m = UserLibrary.readObject(d.resolve("meta.json"));
        if (m == null || !UserLibrary.validMeta(m, pid)) return null;
        List<String> need = new ArrayList<>();
        for (String k : KINDS) if (!Files.isRegularFile(d.resolve(k + ".jpg"), LinkOption.NOFOLLOW_LINKS)) need.add(k);
        return new Job(gid, pid, (String) m.get("name"), (String) m.get("tone"), (Long) m.get("added"), Collections.unmodifiableList(need));
    }

    /** Unfinished photos (processing done or not) in gid, or in all groups when gid is null. */
    synchronized int count(String gid) {
        int n = 0;
        for (UserLibrary.Group g : lib.groups()) {
            if (gid != null && !gid.equals(g.id)) continue;
            Path photos = lib.photosDir(g.id);
            if (!Files.isDirectory(photos, LinkOption.NOFOLLOW_LINKS)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(photos)) {
                for (Path d : ds) {
                    String f = d.getFileName().toString();
                    if (!f.startsWith(".p") || !f.endsWith(".tmp")) continue;
                    String pid = f.substring(1, f.length() - 4);
                    if (UserLibrary.validPid(pid) && !deadRecrop(g.id, pid, d)) n++;
                }
            } catch (IOException | RuntimeException e) {
                // counts nothing
            }
        }
        return n;
    }

    /** Photos that may still be added to gid, counting the unfinished ones against the limits. */
    synchronized int room(String gid) {
        int all = 0, here = 0;
        for (UserLibrary.Group g : lib.groups()) {
            int n = lib.photos(g.id).size();
            all += n;
            if (g.id.equals(gid)) here = n;
        }
        int pendingAll = count(null), pendingHere = count(gid);
        return Math.max(0, Math.min(UserLibrary.MAX_PHOTOS_PER_GROUP - here - pendingHere, UserLibrary.MAX_PHOTOS - all - pendingAll));
    }

    /**
     * The bridge's materialJobs(): {"v":1,"jobs":[{"gid","pid","wall","tone","need":[...]}]}, every
     * job that still needs a material (one that needs none waits for removeStale() at start-up).
     */
    String materialJobs() {
        try {
            UserLibrary.Json.W w = new UserLibrary.Json.W().beginObject().key("v").value(1).key("jobs").beginArray();
            for (Job j : jobs()) {
                if (j.need.isEmpty()) continue;
                w.beginObject().key("gid").value(j.gid).key("pid").value(j.pid)
                        .key("wall").value("user-tmp/" + j.gid + "/" + j.pid + "/" + TMP_ROUTE_FILE).key("tone").value(j.tone)
                        .key("need").beginArray();
                for (String k : j.need) w.value(k);
                w.endArray().endObject();
            }
            return w.endArray().endObject().toString();
        } catch (RuntimeException e) {
            return "{\"v\":1,\"jobs\":[]}";
        }
    }

    /**
     * The bridge's saveMaterial(): one material for an unfinished photo, as a JPEG data URL of at
     * most MAX_MATERIAL_BYTES whose frame is that kind's size and that carries no metadata segment
     * (Jpeg.parse(): no EXIF, XMP, IPTC, comment or trailer may reach R). Written atomically; the photo is
     * moved into place once it is complete: {"ok":true,"done":&lt;bool&gt;} or {"ok":false,"reason"}.
     */
    synchronized String saveMaterial(String gid, String pid, String kind, String dataUrl) {
        try {
            int k = kindIndex(kind);
            if (k < 0) return UserLibrary.fail(BAD_KIND);
            if (!UserLibrary.validGid(gid) || !UserLibrary.validPid(pid) || !lib.groupExists(gid)) return UserLibrary.fail(NO_JOB);
            Path d = tmpDir(gid, pid);
            if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS) || job(gid, pid, d) == null) return UserLibrary.fail(NO_JOB);
            if (dataUrl == null || !dataUrl.startsWith(DATA_URL_PREFIX)) return UserLibrary.fail(BAD_DATA);
            int chars = dataUrl.length() - DATA_URL_PREFIX.length();
            if (chars > (MAX_MATERIAL_BYTES + 2) / 3 * 4) return UserLibrary.fail(TOO_BIG); // before decoding
            byte[] jpg;
            try {
                jpg = Base64.getDecoder().decode(dataUrl.substring(DATA_URL_PREFIX.length()));
            } catch (IllegalArgumentException e) {
                return UserLibrary.fail(BAD_DATA);
            }
            if (jpg.length > MAX_MATERIAL_BYTES) return UserLibrary.fail(TOO_BIG);
            Jpeg.Info info = Jpeg.parse(jpg, jpg.length);
            if (info == null) return UserLibrary.fail(BAD_JPEG);
            if (!info.metadata.isEmpty()) return UserLibrary.fail(METADATA); // review 30e7356 (A6): Jpeg.java says materials are checked
            if (info.width != SIZES[k][0] || info.height != SIZES[k][1]) {
                return UserLibrary.fail("The " + kind + " material must be " + SIZES[k][0] + "x" + SIZES[k][1] + ", not "
                        + info.width + "x" + info.height + ".");
            }
            UserLibrary.writeAtomically(d.resolve(kind + ".jpg"), jpg);
            boolean done = complete(d, pid);
            if (done) moveIntoPlace(d, lib.photosDir(gid).resolve(pid));
            return new UserLibrary.Json.W().beginObject().key("ok").value(true).key("done").value(done).endObject().toString();
        } catch (IOException | RuntimeException e) {
            return UserLibrary.fail(UserLibrary.FAILED);
        }
    }

    /**
     * Adjust (the bridge's recrop()): re-make photo pid of gid from its src.jpg with crop "x,y,z". This
     * checks and makes its .&lt;pid&gt;.tmp beside it and marks it working: {"ok":true} or {"ok":false,
     * "reason"}; recropRun() does the work (seconds) on another thread. The result is an ordinary job
     * (the page draws its materials), whose meta.json carries edited, and is then swapped in over the
     * photo under the same id (moveIntoPlace), so an applied wallpaper stays applied.
     */
    synchronized String recropBegin(String gid, String pid, String crop) {
        synchronized (lib) {
            try {
                if (!UserLibrary.validGid(gid) || !lib.groupExists(gid)) return UserLibrary.fail(UserLibrary.NO_GROUP);
                Path photo = UserLibrary.validPid(pid) ? lib.photosDir(gid).resolve(pid) : null;
                if (photo == null || !Files.isDirectory(photo, LinkOption.NOFOLLOW_LINKS)) return UserLibrary.fail(UserLibrary.NO_PHOTO);
                if (!Files.isRegularFile(photo.resolve("src.jpg"), LinkOption.NOFOLLOW_LINKS)) return UserLibrary.fail(NO_SRC);
                if (PhotoMath.parseCrop(crop) == null) return UserLibrary.fail(BAD_CROP);
                Path tmp = tmpDir(gid, pid);
                if (Files.isDirectory(tmp, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(tmp) && deadRecrop(gid, pid, tmp)) {
                    UserLibrary.deleteTree(tmp); // a dead process's Adjust: nothing will finish it
                }
                if ("working".equals(recrops.get(gid + "/" + pid)) || Files.exists(tmp, LinkOption.NOFOLLOW_LINKS)) {
                    return UserLibrary.fail(BUSY);
                }
                Files.createDirectory(tmp);
                recrops.put(gid + "/" + pid, "working");
                return UserLibrary.ok();
            } catch (IOException | RuntimeException e) {
                return UserLibrary.fail(UserLibrary.FAILED);
            }
        }
    }

    /** The work recropBegin() started, run off this lock (a decode takes seconds); never throws. */
    void recropRun(String gid, String pid, String crop, PhotoProcessor processor) {
        String failure = null;
        try {
            Path photo = lib.photosDir(gid).resolve(pid);
            Map<String, Object> m = UserLibrary.readObject(photo.resolve("meta.json"));
            if (m == null || !UserLibrary.validMeta(m, pid)) throw new IOException("the photo has no meta.json");
            File dir = tmpDir(gid, pid).toFile();
            processor.recrop(photo.resolve("src.jpg").toFile(), dir, pid, (String) m.get("name"), (Long) m.get("added"),
                    PhotoMath.parseCrop(crop), Math.max(1, clock.getAsLong()));
            for (String f : new String[]{"wall.jpg", "thumb.jpg", "src.jpg", "meta.json"}) {
                if (!new File(dir, f).isFile()) throw new IOException("no " + f);
            }
        } catch (PhotoProcessor.Refused e) {
            failure = e.getMessage();
        } catch (IOException | RuntimeException e) {
            failure = RECROP_FAILED;
        }
        if (failure != null) discard(gid, pid);
        synchronized (this) {
            if (failure == null) recrops.remove(gid + "/" + pid);
            else recrops.put(gid + "/" + pid, failure);
        }
    }

    /**
     * The bridge's recropState(): {"ok":true,"state":s} with s "working" (being re-made), "saving" (its
     * materials are being drawn), "done", or "failed" with "reason" (said once, then "done").
     */
    synchronized String recropState(String gid, String pid) {
        String key = gid + "/" + pid, r = recrops.get(key), state;
        if ("working".equals(r)) state = "working";
        else if (r != null) state = "failed";
        else if (UserLibrary.validGid(gid) && UserLibrary.validPid(pid) && Files.isDirectory(tmpDir(gid, pid), LinkOption.NOFOLLOW_LINKS)
                && !deadRecrop(gid, pid, tmpDir(gid, pid))) state = "saving";
        else state = "done";
        UserLibrary.Json.W w = new UserLibrary.Json.W().beginObject().key("ok").value(true).key("state").value(state);
        if ("failed".equals(state)) {
            w.key("reason").value(r);
            recrops.remove(key);
        }
        return w.endObject().toString();
    }

    /** All seven files and a valid meta.json. */
    static boolean complete(Path d, String pid) {
        for (String f : UserLibrary.PHOTO_FILES) {
            if (!Files.isRegularFile(d.resolve(f), LinkOption.NOFOLLOW_LINKS)) return false;
        }
        Map<String, Object> m = UserLibrary.readObject(d.resolve("meta.json"));
        return m != null && UserLibrary.validMeta(m, pid);
    }

    /**
     * A finished photo into place. An Adjust result (meta.json has edited) replaces the photo of the same
     * id: the old one is renamed to .&lt;pid&gt;.old-* (never listed), the new one moved in, then the old
     * one removed; if the photo was deleted meanwhile, the result is dropped too. The old copy is not
     * a .del-* (review 30e7356, G2): a process that died between the two renames left the photo only
     * as .&lt;pid&gt;.del-*, which start-up swept, and dropped the Adjust result as "deleted meanwhile",
     * so both were lost. Start-up (finishSwaps()) now completes such a swap, or puts the old one back.
     */
    private void moveIntoPlace(Path tmp, Path dest) throws IOException {
        try {
            moveIntoPlaceUnlisted(tmp, dest);
        } finally {
            lib.changed(); // library()'s cached listing (even a half-done swap changed what is listed)
        }
    }

    private void moveIntoPlaceUnlisted(Path tmp, Path dest) throws IOException {
        Map<String, Object> m = UserLibrary.readObject(tmp.resolve("meta.json"));
        boolean edit = m != null && m.get("edited") instanceof Long;
        Path old = null;
        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
            if (!edit) throw new IOException("photo already exists");
            StringBuilder s = new StringBuilder("." + dest.getFileName() + OLD);
            for (int k = 0; k < 6; k++) s.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            old = dest.resolveSibling(s.toString());
            move(dest, old);
        } else if (edit) {
            UserLibrary.deleteTree(tmp);
            return;
        }
        try {
            move(tmp, dest);
        } catch (IOException | RuntimeException e) {
            if (old != null) {
                try {
                    move(old, dest); // the photo as it was; the Adjust result stays a job
                } catch (IOException | RuntimeException e2) {
                    // left as .<pid>.old-*: start-up puts it back (finishSwaps)
                }
            }
            throw e;
        }
        if (old != null) {
            try {
                UserLibrary.deleteTree(old);
            } catch (IOException | RuntimeException e) {
                // removeStale() removes it at the next start
            }
        }
    }

    /** An Adjust's swap in progress: the photo's previous copy, .&lt;pid&gt;.old-&lt;random&gt;. */
    static final String OLD = ".old-";

    /**
     * Start-up, in one photos folder, before anything else there: every .&lt;pid&gt;.old-* is an Adjust
     * swap the process died in. If &lt;pid&gt; is there the swap finished and the old copy goes. If not,
     * the swap is completed when .&lt;pid&gt;.tmp is a complete Adjust result, else the old copy (the
     * newest, if there are several) is put back. A photo is never lost. Returns the directories moved
     * or removed. Caller holds the library's lock.
     */
    private int finishSwaps(Path photos) {
        int n = 0;
        Map<String, List<Path>> olds = new java.util.TreeMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(photos)) {
            for (Path d : ds) {
                String name = d.getFileName().toString();
                int at = name.indexOf(OLD);
                if (!name.startsWith(".") || at < 2 || Files.isSymbolicLink(d) || !Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) continue;
                String pid = name.substring(1, at);
                if (UserLibrary.validPid(pid)) olds.computeIfAbsent(pid, k -> new ArrayList<>()).add(d);
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
        for (Map.Entry<String, List<Path>> e : olds.entrySet()) {
            String pid = e.getKey();
            List<Path> list = e.getValue();
            Path dest = photos.resolve(pid), tmp = photos.resolve(tmpName(pid));
            try {
                if (!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
                    Map<String, Object> m = Files.isDirectory(tmp, LinkOption.NOFOLLOW_LINKS) && complete(tmp, pid)
                            ? UserLibrary.readObject(tmp.resolve("meta.json")) : null;
                    if (m != null && m.get("edited") instanceof Long) {
                        move(tmp, dest); // the swap, completed
                    } else {
                        Path newest = list.get(0);
                        for (Path p : list) {
                            if (Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).compareTo(
                                    Files.getLastModifiedTime(newest, LinkOption.NOFOLLOW_LINKS)) > 0) newest = p;
                        }
                        move(newest, dest); // the photo as it was before the Adjust
                        list.remove(newest);
                    }
                    n++;
                    lib.changed();
                }
            } catch (IOException | RuntimeException x) {
                continue; // left as it is: tried again at the next start
            }
            for (Path p : list) {
                try {
                    UserLibrary.deleteTree(p);
                    n++;
                } catch (IOException | RuntimeException x) {
                    // tried again at the next start
                }
            }
        }
        return n;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    /**
     * Start-up clean-up, in every group: an unfinished photo that is complete (the app stopped between
     * its last material and the rename) is moved into place; other unfinished photos last changed more
     * than STALE_MS ago, an Adjust a dead process left unfinished (deadRecrop(), whatever its age), and directories a delete left half-removed (.&lt;name&gt;.del-*), are removed; an Adjust swap a dead
     * process left half-done (.&lt;pid&gt;.old-*) is finished or undone first (finishSwaps()); so
     * are groups/.&lt;gid&gt;.tmp and groups/.&lt;gid&gt;.del-* (UserLibrary.removeLeftovers()).
     * Returns how many directories were finished or removed. Holds the library's lock throughout
     * (MainActivity runs it on a background thread while the page's bridge calls may already run, so a
     * delete in progress, a .&lt;name&gt;.del-* of its own, is never removed under it). Lock order: this,
     * then the library.
     */
    synchronized int removeStale() {
        synchronized (lib) {
            return removeStaleLocked();
        }
    }

    private int removeStaleLocked() {
        int removed = lib.removeLeftovers();
        Path groups = lib.root().resolve("groups");
        if (!Files.isDirectory(groups, LinkOption.NOFOLLOW_LINKS)) return removed;
        long cutoff = clock.getAsLong() - STALE_MS;
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(groups)) {
            for (Path g : ds) {
                if (UserLibrary.validGid(g.getFileName().toString()) && Files.isDirectory(g, LinkOption.NOFOLLOW_LINKS)) {
                    dirs.add(g.resolve("photos"));
                }
            }
        } catch (IOException | RuntimeException e) {
            return removed;
        }
        for (Path photos : dirs) {
            if (!Files.isDirectory(photos, LinkOption.NOFOLLOW_LINKS)) continue;
            removed += finishSwaps(photos); // first: a .<pid>.tmp below may be the swap's new copy
            List<Path> gone = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(photos)) {
                for (Path d : ds) {
                    String n = d.getFileName().toString();
                    if (!n.startsWith(".") || Files.isSymbolicLink(d)) continue;
                    if (n.contains(".del-")) {
                        gone.add(d);
                    } else if (n.endsWith(".tmp")) {
                        String pid = n.substring(1, n.length() - 4);
                        if (UserLibrary.validPid(pid) && Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS) && complete(d, pid)) {
                            try {
                                moveIntoPlace(d, photos.resolve(pid));
                                removed++;
                                continue;
                            } catch (IOException | RuntimeException e) {
                                // left as it is: removed once stale
                            }
                        }
                        if (Files.getLastModifiedTime(d, LinkOption.NOFOLLOW_LINKS).toMillis() < cutoff
                                || UserLibrary.validPid(pid) && Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)
                                && deadRecrop(photos.getParent().getFileName().toString(), pid, d)) gone.add(d);
                    }
                }
            } catch (IOException | RuntimeException e) {
                continue;
            }
            for (Path d : gone) {
                try {
                    UserLibrary.deleteTree(d);
                    removed++;
                } catch (IOException | RuntimeException e) {
                    // tried again at the next start
                }
            }
        }
        return removed;
    }

    /**
     * The file for the route /home/user-tmp/&lt;gid&gt;/&lt;pid&gt;/wall.jpg (rel = "gid/pid/wall.jpg"), or
     * null: exactly three segments, valid ids, only wall.jpg, a regular file of an unfinished photo
     * whose canonical path stays under R.
     */
    File tmpWall(String rel) {
        try {
            if (rel == null) return null;
            String[] s = rel.split("/", -1);
            if (s.length != 3 || !UserLibrary.validGid(s[0]) || !UserLibrary.validPid(s[1]) || !TMP_ROUTE_FILE.equals(s[2])) return null;
            File f = tmpDir(s[0], s[1]).resolve(TMP_ROUTE_FILE).toFile();
            String canonRoot = lib.root().toFile().getCanonicalPath() + File.separator;
            if (!f.getCanonicalPath().startsWith(canonRoot) || !f.isFile()) return null;
            return f;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
