package home.cleanframe.tv;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Behavioural tests for UserLibrary (docs/PHOTO-GROUPS.md contract 1, the bridge JSON of contract
 * 2) against the shared fixture tests/fixtures/user-library (R tree + expected.json), copied to a
 * temp dir. Covers: the listing equals expected.json (order, ignored entries), create/rename/delete,
 * limits, traversal ids and names, malformed JSON, tmp dirs, the token (issue, expiry, clear, atomic
 * write), the /home/user/ route's file resolution, and the JSON reader/writer. Plain Java (8+), no
 * JUnit: run by tests/fallback.test.sh from the repo root. Exits 1 on any failure.
 */
public final class UserLibraryTest {
    static final Path FIXTURE = Paths.get("tests/fixtures/user-library");
    static final long NOW = 1_761_901_000_000L; // before the fixture token's expiry (1761901500000)

    static void check(boolean cond, String msg) {
        if (!cond) throw new FallbackPolicyTest.Fail(msg);
    }

    static void eq(Object got, Object want, String what) {
        if (got == null ? want != null : !got.equals(want)) throw new FallbackPolicyTest.Fail(what + ": got " + got + ", want " + want);
    }

    static final class Clock implements java.util.function.LongSupplier {
        long t = NOW;

        public long getAsLong() {
            return t;
        }
    }

    Path r;
    Clock clock;
    UserLibrary lib;

    static Path tmpDir(String name) throws IOException {
        return Files.createTempDirectory(name);
    }

    static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.copy(f, to.resolve(from.relativize(f).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** A copy of the fixture's R; gnojson03 (an empty dir, which git cannot hold) is made here. */
    void fixture() throws IOException {
        check(Files.isDirectory(FIXTURE.resolve("R")), "fixture missing: run from the repo root (" + FIXTURE + "/R)");
        r = tmpDir("cf-userlib").resolve("R");
        copyTree(FIXTURE.resolve("R"), r);
        Files.createDirectories(r.resolve("groups/gnojson03"));
        clock = new Clock();
        lib = new UserLibrary(r.toFile(), clock, new Random(42));
    }

    /** An empty R (not yet created on disk). */
    void empty() throws IOException {
        r = tmpDir("cf-userlib").resolve("R");
        clock = new Clock();
        lib = new UserLibrary(r.toFile(), clock, new Random(7));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(String json) {
        Object o = UserLibrary.Json.parse(json);
        check(o instanceof Map, "not a JSON object: " + json);
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    static String okId(String json) {
        Map<String, Object> m = obj(json);
        eq(m.get("ok"), true, "ok in " + json);
        return (String) m.get("id");
    }

    static void refused(String json, String reason, String what) {
        Map<String, Object> m = obj(json);
        eq(m.get("ok"), false, what + ": ok");
        eq(m.get("reason"), reason, what + ": reason");
    }

    static String sevenFiles(Path dir, String pid, long added) throws IOException {
        Files.createDirectories(dir);
        for (String f : UserLibrary.PHOTO_FILES) Files.write(dir.resolve(f), new byte[]{(byte) 0xff, (byte) 0xd8});
        String meta = "{\"v\":1,\"id\":\"" + pid + "\",\"name\":\"N\",\"added\":" + added + ",\"tone\":\"dark\","
                + "\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":0,\"dockQuiet\":false,\"clockMist\":false}";
        Files.write(dir.resolve("meta.json"), meta.getBytes(StandardCharsets.UTF_8));
        return pid;
    }

    static String pid(int n) {
        return String.format("p%010d", n);
    }

    // ---------- the fixture ----------

    void listingMatchesExpected() throws IOException {
        fixture();
        Map<String, Object> want = obj(new String(Files.readAllBytes(FIXTURE.resolve("expected.json")), StandardCharsets.UTF_8));
        Map<String, Object> got = obj(lib.library());
        eq(got.get("v"), 1L, "library v");
        eq(got.keySet().toString(), "[v, groups, photos]", "library keys");
        List<Map<String, Object>> wg = list(want, "groups"), gg = list(got, "groups");
        eq(gg.size(), wg.size(), "group count");
        for (int i = 0; i < wg.size(); i++) {
            for (Map.Entry<String, Object> e : wg.get(i).entrySet()) {
                eq(gg.get(i).get(e.getKey()), e.getValue(), "groups[" + i + "]." + e.getKey());
            }
            eq(gg.get(i).keySet().toString(), "[id, name, created, builtin]", "groups[" + i + "] keys");
        }
        eq(gg.get(0).get("name"), "Scenery", "built-in name");
        eq(gg.get(1).get("name"), "Space", "built-in name");
        eq(gg.get(2).get("name"), "Cyberpunk", "built-in name");
        List<Map<String, Object>> wp = list(want, "photos"), gp = list(got, "photos");
        eq(gp.size(), wp.size(), "photo count");
        for (int i = 0; i < wp.size(); i++) {
            eq(gp.get(i).get("group"), wp.get(i).get("group"), "photos[" + i + "].group");
            eq(gp.get(i).get("id"), wp.get(i).get("id"), "photos[" + i + "].id");
        }
        // every meta field reaches the page, values as in meta.json; "v" does not
        Map<String, Object> moon = gp.get(2);
        Map<String, Object> meta = obj(new String(Files.readAllBytes(r.resolve("groups/space/photos/pmoon000003/meta.json")),
                StandardCharsets.UTF_8));
        eq(moon.keySet().toString(), "[group, id, name, added, tone, ut, frostVeil, listColumn, dockQuiet, clockMist]", "photo keys");
        for (String k : new String[]{"id", "name", "added", "tone", "ut", "frostVeil", "listColumn", "dockQuiet", "clockMist"}) {
            eq(moon.get(k), meta.get(k), "pmoon000003." + k);
        }
        // the ignored entries exist in the tree and appear nowhere in the answer
        String json = lib.library();
        @SuppressWarnings("unchecked") List<Object> ignored = (List<Object>) want.get("ignored");
        check(ignored.size() >= 7, "expected.json lists its ignored entries");
        for (Object o : ignored) {
            Path p = r.resolve((String) o);
            check(Files.isDirectory(p), "ignored entry " + o + " exists in the fixture copy");
            check(!json.contains("\"" + p.getFileName() + "\""), "ignored " + o + " is not listed");
        }
    }

    void exactJsonShape() throws IOException {
        fixture();
        String json = lib.library();
        check(json.startsWith("{\"v\":1,\"groups\":[{\"id\":\"scenery\",\"name\":\"Scenery\",\"created\":0,\"builtin\":true},"
                + "{\"id\":\"space\",\"name\":\"Space\",\"created\":0,\"builtin\":true},"
                + "{\"id\":\"cyberpunk\",\"name\":\"Cyberpunk\",\"created\":0,\"builtin\":true},"
                + "{\"id\":\"gholiday1\",\"name\":\"Holiday\",\"created\":1761900000000,\"builtin\":false},"), "library() exact prefix: " + json);
        check(json.contains("{\"group\":\"space\",\"id\":\"pmoon000003\",\"name\":\"My moon shot\",\"added\":1761900400000,"
                + "\"tone\":\"dark\",\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":0,"
                + "\"dockQuiet\":false,\"clockMist\":false}"), "photo record exact: " + json);
        eq(UserLibrary.emptyLibrary(), "{\"v\":1,\"groups\":[{\"id\":\"scenery\",\"name\":\"Scenery\",\"created\":0,\"builtin\":true},"
                + "{\"id\":\"space\",\"name\":\"Space\",\"created\":0,\"builtin\":true},"
                + "{\"id\":\"cyberpunk\",\"name\":\"Cyberpunk\",\"created\":0,\"builtin\":true}],\"photos\":[]}", "emptyLibrary()");
    }

    void fixtureToken() throws IOException {
        fixture();
        Map<String, Object> t = lib.currentToken();
        check(t != null, "fixture token valid before expiry");
        eq(t.get("token"), "0123456789abcdef0123456789abcdef", "fixture token");
        eq(t.get("code"), "482913", "fixture code");
        eq(t.get("group"), "gholiday1", "fixture group");
        clock.t = 1_761_901_500_000L - 1;
        check(lib.currentToken() != null, "valid 1 ms before expires");
        clock.t = 1_761_901_500_000L;
        check(lib.currentToken() == null, "expired at expires");
    }

    void missingRootIsEmpty() throws IOException {
        empty();
        Map<String, Object> m = obj(lib.library());
        eq(list(m, "groups").size(), 3, "only the built-ins");
        eq(list(m, "photos").size(), 0, "no photos");
        check(!Files.exists(r), "listing creates nothing");
    }

    // ---------- skipping ----------

    void malformedSkipped() throws IOException {
        fixture();
        Path h = r.resolve("groups/gholiday1/photos");
        String[] bad = {
                "{\"v\":2,\"id\":\"%s\",\"name\":\"N\",\"added\":1,\"tone\":\"dark\",\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":0,\"dockQuiet\":false,\"clockMist\":false}",
                "{\"v\":1,\"id\":\"pzzzzzzzzzz\",\"name\":\"N\",\"added\":1,\"tone\":\"dark\",\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":0,\"dockQuiet\":false,\"clockMist\":false}",
                "{\"v\":1,\"id\":\"%s\",\"name\":\"N\",\"added\":1,\"tone\":\"grey\",\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":0,\"dockQuiet\":false,\"clockMist\":false}",
                "{\"v\":1,\"id\":\"%s\",\"name\":\"N\",\"added\":1,\"tone\":\"dark\",\"ut\":{\"tr\":\"pink\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":0,\"dockQuiet\":false,\"clockMist\":false}",
                "{\"v\":1,\"id\":\"%s\",\"name\":\"N\",\"added\":1,\"tone\":\"dark\",\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.9,\"listColumn\":0,\"dockQuiet\":false,\"clockMist\":false}",
                "{\"v\":1,\"id\":\"%s\",\"name\":\"N\",\"added\":1,\"tone\":\"dark\",\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":9,\"dockQuiet\":false,\"clockMist\":false}",
                "{\"v\":1,\"id\":\"%s\",\"name\":\"N\",\"added\":1,\"tone\":\"dark\",\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":0,\"dockQuiet\":\"no\",\"clockMist\":false}",
                "{\"v\":1,\"id\":\"%s\",\"name\":\"N\",\"added\":-5,\"tone\":\"dark\",\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":0,\"dockQuiet\":false,\"clockMist\":false}",
                "{\"v\":1,\"id\":\"%s\",\"name\":\"\",\"added\":1,\"tone\":\"dark\",\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":0,\"dockQuiet\":false,\"clockMist\":false}",
                "{\"v\":1,\"id\":\"%s\",\"name\":\"a\\u0000b\",\"added\":1,\"tone\":\"dark\",\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":0,\"dockQuiet\":false,\"clockMist\":false}",
                "{\"v\":1,\"id\":\"%s\",\"name\":\"N\",\"added\":1,\"tone\":\"dark\",\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":0,\"dockQuiet\":false,\"clockMist\":false,}",
                "{\"v\":1,\"id\":\"%s\",\"id\":\"%s\",\"name\":\"N\",\"added\":1,\"tone\":\"dark\",\"ut\":{\"tr\":\"light\",\"br\":\"mist\"},\"frostVeil\":0.12,\"listColumn\":0,\"dockQuiet\":false,\"clockMist\":false}",
                "[1,2]",
                "",
        };
        for (int i = 0; i < bad.length; i++) {
            String p = pid(900 + i);
            sevenFiles(h.resolve(p), p, 5);
            Files.write(h.resolve(p).resolve("meta.json"), String.format(bad[i], p, p).getBytes(StandardCharsets.UTF_8));
        }
        // a valid photo that is a symlink, a file where a photo dir should be, a dot dir and a tmp dir
        Path real = r.resolve("../outside");
        sevenFiles(real.resolve(pid(999)), pid(999), 5);
        Files.createSymbolicLink(h.resolve(pid(999)), real.resolve(pid(999)));
        Files.write(h.resolve(pid(998)), new byte[]{1});
        sevenFiles(h.resolve(".p0000000997"), "p0000000997", 5);
        sevenFiles(h.resolve(".p0000000996.tmp"), "p0000000996", 5);
        // an oversized meta.json
        String big = pid(995);
        sevenFiles(h.resolve(big), big, 5);
        StringBuilder pad = new StringBuilder();
        while (pad.length() <= UserLibrary.JSON_MAX_BYTES) pad.append(' ');
        Files.write(h.resolve(big).resolve("meta.json"), (new String(Files.readAllBytes(h.resolve(big).resolve("meta.json")),
                StandardCharsets.UTF_8) + pad).getBytes(StandardCharsets.UTF_8));
        // malformed group.json variants (dir name valid)
        String[] badGroups = {"{\"v\":1,\"id\":\"gwrongid1\",\"name\":\"X\",\"created\":1}", "{\"v\":1,\"id\":\"%s\",\"name\":\" X \",\"created\":1}",
                "{\"v\":1,\"id\":\"%s\",\"name\":\"X\",\"created\":\"1\"}", "{\"v\":1,\"id\":\"%s\",\"name\":\"\",\"created\":1}", "{not json"};
        for (int i = 0; i < badGroups.length; i++) {
            String g = String.format("gbadgrp0%d", i);
            Files.createDirectories(r.resolve("groups").resolve(g));
            Files.write(r.resolve("groups").resolve(g).resolve("group.json"), String.format(badGroups[i], g).getBytes(StandardCharsets.UTF_8));
        }
        Map<String, Object> m = obj(lib.library());
        eq(list(m, "groups").size(), 5, "malformed groups skipped");
        eq(list(m, "photos").size(), 3, "malformed photos skipped: " + lib.library());
    }

    void validMetaAccepted() throws IOException {
        // the boundaries of each value set are accepted, so the skips above are for the right reason
        fixture();
        Path h = r.resolve("groups/gfamily02/photos");
        String p = pid(1);
        sevenFiles(h.resolve(p), p, 9);
        Files.write(h.resolve(p).resolve("meta.json"), ("{\"v\":1,\"id\":\"" + p + "\",\"name\":\"\\u00e9t\\u00e9 \\\"x\\\"\",\"added\":1761900150000,"
                + "\"tone\":\"light\",\"ut\":{\"tr\":\"dark\",\"br\":\"light\"},\"frostVeil\":0.44,\"listColumn\":3,"
                + "\"dockQuiet\":true,\"clockMist\":true,\"extra\":[null,{}]}").getBytes(StandardCharsets.UTF_8));
        String p2 = pid(2);
        sevenFiles(h.resolve(p2), p2, 10);
        // same added as pbeach00001 (gholiday1): the tie goes to group order, so beach first
        Files.write(h.resolve(p2).resolve("meta.json"), ("{\"v\":1,\"id\":\"" + p2 + "\",\"name\":\"z\",\"added\":1761900100000,"
                + "\"tone\":\"dark\",\"ut\":{\"tr\":\"mist\",\"br\":\"mist\"},\"frostVeil\":0,\"listColumn\":0,"
                + "\"dockQuiet\":false,\"clockMist\":false}").getBytes(StandardCharsets.UTF_8));
        String p3 = pid(3); // same group and added as p2: the tie goes to id
        sevenFiles(h.resolve(p3), p3, 1761900100000L);
        Map<String, Object> m = obj(lib.library());
        List<Map<String, Object>> ps = list(m, "photos");
        StringBuilder order = new StringBuilder();
        for (Map<String, Object> x : ps) order.append(x.get("id")).append(' ');
        eq(order.toString().trim(), "pbeach00001 " + p2 + " " + p3 + " " + p + " psunset0002 pmoon000003",
                "by added across groups; ties by group order, then id");
        Map<String, Object> e = ps.get(3);
        eq(e.get("name"), "\u00e9t\u00e9 \"x\"", "name round-trips");
        eq(e.get("frostVeil"), 0.44, "frostVeil 0.44 (upstream max)");
        eq(e.get("listColumn"), 3L, "listColumn 3");
        eq(e.get("dockQuiet"), true, "dockQuiet true");
        check(!e.containsKey("extra"), "unknown meta keys are not passed on");
        eq(ps.get(1).get("frostVeil"), 0L, "frostVeil 0");
    }

    void missingFileSkipped() throws IOException {
        fixture();
        for (String f : UserLibrary.PHOTO_FILES) {
            Path p = r.resolve("groups/gholiday1/photos/pbeach00001").resolve(f);
            Path aside = p.resolveSibling("aside");
            Files.move(p, aside);
            lib.changed(); // a disk state written behind the library's back: drop its kept listing
            check(!lib.library().contains("pbeach00001"), "photo without " + f + " is skipped");
            Files.move(aside, p);
            lib.changed();
        }
        check(lib.library().contains("pbeach00001"), "listed again with all seven");
        Files.delete(r.resolve("groups/gholiday1/photos/pbeach00001/meta.json"));
        lib.changed();
        check(!lib.library().contains("pbeach00001"), "photo without meta.json is skipped");
    }

    void photosOfUnlistedGroupNotListed() throws IOException {
        fixture();
        sevenFiles(r.resolve("groups/gnojson03/photos").resolve(pid(5)), pid(5), 1);
        sevenFiles(r.resolve("groups/Gbadgroup/photos").resolve(pid(6)), pid(6), 1);
        sevenFiles(r.resolve("groups/scenery/photos").resolve(pid(7)), pid(7), 1);
        String json = lib.library();
        check(!json.contains(pid(5)) && !json.contains(pid(6)), "photos of skipped groups are not listed");
        Map<String, Object> first = list(obj(json), "photos").get(0);
        eq(first.get("group") + "/" + first.get("id"), "scenery/" + pid(7), "a Scenery upload comes first (group order)");
    }

    // ---------- changes ----------

    void addRenameDeleteGroup() throws IOException {
        empty();
        clock.t = 5000;
        String a = okId(lib.addGroup("  Trip  "));
        check(UserLibrary.validUserGid(a), "new id matches ^g[0-9a-z]{8}$: " + a);
        Map<String, Object> g = UserLibrary.readObject(r.resolve("groups").resolve(a).resolve("group.json"));
        eq(g.toString(), "{v=1, id=" + a + ", name=Trip, created=5000}", "group.json as in contract 1 (trimmed name)");
        String[] left = r.resolve("groups").toFile().list();
        eq(left.length, 1, "no tmp dir left behind");
        clock.t = 4000;
        String b = okId(lib.addGroup("Older"));
        List<Map<String, Object>> gs = list(obj(lib.library()), "groups");
        eq(gs.get(3).get("id"), b, "user groups by created");
        eq(obj(lib.renameGroup(a, " New name ")).get("ok"), true, "rename ok");
        Map<String, Object> g2 = UserLibrary.readObject(r.resolve("groups").resolve(a).resolve("group.json"));
        eq(g2.get("name"), "New name", "renamed");
        eq(g2.get("created"), 5000L, "rename keeps created");
        check(!Files.exists(r.resolve("groups").resolve(a).resolve(".group.json.tmp")), "rename leaves no tmp file");
        refused(lib.renameGroup(a, ""), UserLibrary.BAD_NAME, "rename to empty");
        refused(lib.renameGroup("gnothere1", "X"), UserLibrary.NO_GROUP, "rename missing");
        sevenFiles(r.resolve("groups").resolve(a).resolve("photos").resolve(pid(1)), pid(1), 1);
        eq(obj(lib.deleteGroup(a)).get("ok"), true, "delete ok");
        check(!Files.exists(r.resolve("groups").resolve(a)), "group dir removed with its photos");
        eq(r.resolve("groups").toFile().list().length, 1, "no .del dir left behind");
        refused(lib.deleteGroup(a), UserLibrary.NO_GROUP, "delete twice");
    }

    /** Makes every directory under p writable again (a case made one read-only to fail a delete). */
    static void writable(Path p) throws IOException {
        try (java.util.stream.Stream<Path> all = Files.walk(p)) {
            all.forEach(f -> f.toFile().setWritable(true));
        }
    }

    /**
     * Review 999ba07 (L4): a delete that failed after the rename answered FAILED although the group (or
     * photo) was already gone from the list, and groups/.<gid>.del-* and .<gid>.tmp were never swept.
     */
    void failedDeleteStillGoneAndSwept() throws IOException {
        empty();
        String a = okId(lib.addGroup("Stuck"));
        Path groups = r.resolve("groups"), photo = groups.resolve(a).resolve("photos").resolve(pid(1));
        sevenFiles(photo, pid(1), 1);
        check(photo.toFile().setWritable(false), "chmod: the photo's files cannot be deleted");
        String b = null;
        try {
            eq(lib.deleteGroup(a), UserLibrary.ok(), "the group is gone from the list: deleteGroup says so");
            check(!Files.exists(groups.resolve(a)), "renamed out of the list");
            for (Map<String, Object> g : list(obj(lib.library()), "groups")) check(!a.equals(g.get("id")), "not listed");
            refused(lib.deleteGroup(a), UserLibrary.NO_GROUP, "delete again");
            b = okId(lib.addGroup("Photo stuck"));
            Path p2 = groups.resolve(b).resolve("photos").resolve(pid(2));
            sevenFiles(p2, pid(2), 2);
            Path inner = p2.resolve("sub");
            Files.createDirectories(inner);
            Files.write(inner.resolve("x"), new byte[1]);
            check(inner.toFile().setWritable(false), "chmod: the photo cannot be fully deleted");
            eq(lib.deletePhoto(b, pid(2)), UserLibrary.ok(), "deletePhoto: gone from the list, so ok");
            eq(list(obj(lib.library()), "photos").size(), 0, "not listed");
        } finally {
            writable(r);
        }
        Files.createDirectories(groups.resolve(".gabcdefgh.tmp").resolve("x"));   // an addGroup() that failed
        Files.write(groups.resolve(".keep"), new byte[1]);                       // not ours: kept
        Files.createDirectories(groups.resolve(".gshort.tmp"));                    // not a gid: kept
        Path outside = r.resolveSibling("outside");
        Files.createDirectories(outside.resolve("keep"));
        Files.createSymbolicLink(groups.resolve(".gzzzzzzzz.tmp"), outside);      // a link: not followed, kept
        eq(lib.removeLeftovers(), 2, "the half-deleted group and the failed addGroup's tmp");
        try (java.util.stream.Stream<Path> all = Files.list(groups)) {
            List<String> left = new ArrayList<>();
            all.forEach(p -> left.add(p.getFileName().toString()));
            java.util.Collections.sort(left);
            eq(left.toString(), "[.gshort.tmp, .gzzzzzzzz.tmp, .keep, " + b + "]", "what is left in groups/");
        }
        check(Files.isDirectory(outside.resolve("keep")), "nothing followed through a link");
        check(Files.exists(groups.resolve(b).resolve("photos")), "the other group kept");
        eq(lib.removeLeftovers(), 0, "second run");
        eq(new UserLibrary(r.resolveSibling("none").toFile(), clock, new Random(1)).removeLeftovers(), 0, "no R yet");
    }

    void builtinsProtected() throws IOException {
        fixture();
        refused(lib.renameGroup("scenery", "X"), UserLibrary.BUILTIN, "rename scenery");
        refused(lib.deleteGroup("space"), UserLibrary.BUILTIN, "delete space");
        refused(lib.renameGroup("cyberpunk", "X"), UserLibrary.BUILTIN, "rename cyberpunk");
        refused(lib.deleteGroup("cyberpunk"), UserLibrary.BUILTIN, "delete cyberpunk");
        check(Files.isDirectory(r.resolve("groups/space/photos/pmoon000003")), "space untouched");
        eq(obj(lib.deletePhoto("space", "pmoon000003")).get("ok"), true, "uploaded photo in a built-in can be deleted");
        check(!Files.exists(r.resolve("groups/space/photos/pmoon000003")), "photo dir removed");
        refused(lib.deletePhoto("space", "pmoon000003"), UserLibrary.NO_PHOTO, "delete photo twice");
        refused(lib.deletePhoto("gnothere1", "pbeach00001"), UserLibrary.NO_GROUP, "photo of missing group");
        eq(obj(lib.deletePhoto("gholiday1", "pbeach00001")).get("ok"), true, "delete user-group photo");
        eq(list(obj(lib.library()), "photos").size(), 1, "one photo left");
    }

    void groupLimit() throws IOException {
        empty();
        for (int i = 0; i < 20; i++) okId(lib.addGroup("Group " + (i + 1))); // contract 1: 20, a literal on purpose
        refused(lib.addGroup("One more"), UserLibrary.TOO_MANY_GROUPS, "21st group");
        eq(list(obj(lib.library()), "groups").size(), 23, "20 user groups + 3 built-ins");
        String first = (String) list(obj(lib.library()), "groups").get(3).get("id");
        lib.deleteGroup(first);
        okId(lib.addGroup("Room again"));
    }

    void photoLimits() throws IOException {
        empty();
        String g = okId(lib.addGroup("Full"));
        Path ph = r.resolve("groups").resolve(g).resolve("photos");
        for (int i = 0; i < 59; i++) sevenFiles(ph.resolve(pid(i)), pid(i), i);
        eq(lib.photoRoom(g), 1, "one place left in the group");
        eq(obj(lib.uploadStart(g, "http://192.168.0.6:8097")).get("ok"), true, "upload allowed with room");
        sevenFiles(ph.resolve(pid(59)), pid(59), 59);
        eq(lib.photoRoom(g), 0, "group full at 60");
        refused(lib.uploadStart(g, "http://192.168.0.6:8097"), UserLibrary.FULL, "upload to a full group");
        // 300 in all: four more user groups of 60 = 300
        for (int k = 0; k < 4; k++) {
            String o = okId(lib.addGroup("G" + k));
            for (int i = 0; i < 60; i++) {
                String p = String.format("p%02d%08d", k, i);
                sevenFiles(r.resolve("groups").resolve(o).resolve("photos").resolve(p), p, i);
            }
        }
        String e = okId(lib.addGroup("Empty"));
        eq(lib.photoRoom(e), 0, "300 in all: no room even in an empty group");
        eq(lib.photoRoom("space"), 0, "nor in a built-in");
        refused(lib.uploadStart(e, "http://192.168.0.6:8097"), UserLibrary.FULL, "upload past 300");
    }

    // ---------- traversal ----------

    void traversalIdsAndNames() throws IOException {
        fixture();
        String[] badIds = {"..", "../x", "g1234567/", "g12345678/..", "g1234\\5678", "g1234\u00005678", "", null, "G12345678",
                "g123456789", "g1234567", "p0123456789", "/etc", "g12345678\n", "scenery/..", "spacex"};
        for (String id : badIds) {
            check(!UserLibrary.validGid(id), "gid refused: " + id);
            refused(lib.deleteGroup(id), UserLibrary.NO_GROUP, "deleteGroup(" + id + ")");
            refused(lib.renameGroup(id, "X"), UserLibrary.NO_GROUP, "renameGroup(" + id + ")");
            refused(lib.uploadStart(id, "http://192.168.0.6:8097"), UserLibrary.NO_GROUP, "uploadStart(" + id + ")");
            refused(lib.deletePhoto(id, "pbeach00001"), UserLibrary.NO_GROUP, "deletePhoto(" + id + ", ...)");
        }
        String[] badPids = {"..", "../pbeach00001", "pbeach0000", "pbeach000011", "Pbeach00001", "pbeach0000/", "pbeach\\0001",
                "pbeach\u00000001", "", null, ".pinflight04.tmp", "BAD..ID"};
        for (String p : badPids) {
            check(!UserLibrary.validPid(p), "pid refused: " + p);
            refused(lib.deletePhoto("gholiday1", p), UserLibrary.NO_PHOTO, "deletePhoto(gholiday1, " + p + ")");
        }
        check(Files.isDirectory(r.resolve("groups/gholiday1/photos/BAD..ID")), "BAD..ID left alone");
        String[] badNames = {"", "   ", "a\u0000b", "a\nb", "tab\there", "\u0007", "x\u2028y", repeat('a', 25), repeat('\u00e9', 25), null};
        for (String n : badNames) {
            check(UserLibrary.cleanName(n) == null, "name refused: " + n);
            refused(lib.addGroup(n), UserLibrary.BAD_NAME, "addGroup(" + n + ")");
        }
        eq(UserLibrary.cleanName(repeat('a', 24)), repeat('a', 24), "24 characters allowed");
        eq(UserLibrary.cleanName(repeat("\ud83d\ude00", 24)), repeat("\ud83d\ude00", 24), "24 emoji (code points, not chars)");
        check(UserLibrary.cleanName("\ud83d") == null, "a lone surrogate is refused");
        // names are display text, never a path: "../x" and "a/b\\c" are stored as text under a random gid
        for (String n : new String[]{"../../x", "a/b\\c", ".."}) {
            String gid = okId(lib.addGroup(n));
            eq(UserLibrary.readObject(r.resolve("groups").resolve(gid).resolve("group.json")).get("name"), n, "name kept as text");
        }
        check(!Files.exists(r.resolve("x")) && !Files.exists(r.getParent().resolve("x")), "nothing written outside groups/");
    }

    static String repeat(char c, int n) {
        return repeat(String.valueOf(c), n);
    }

    static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    void routeFiles() throws IOException {
        fixture();
        for (String f : UserLibrary.PHOTO_FILES) {
            File got = lib.photoFile("gholiday1/pbeach00001/" + f);
            check(got != null && got.getName().equals(f), "route serves " + f);
        }
        check(lib.photoFile("space/pmoon000003/wall.jpg") != null, "route serves a built-in group's upload");
        eq(UserLibrary.PHOTO_FILES.size(), 7, "seven names");
        check(lib.photoFile("gholiday1/pbeach00001/src.jpg") == null, "no src.jpg: 404");
        check(!lib.library().contains("\"src\""), "and not listed with one");
        Files.write(r.resolve("groups/gholiday1/photos/pbeach00001/src.jpg"), new byte[]{1, 2, 3});
        lib.changed(); // written behind the library's back (Adjust writes it in .<pid>.tmp, then moveIntoPlace)
        check(lib.photoFile("gholiday1/pbeach00001/src.jpg") != null, "route serves src.jpg (Adjust)");
        check(lib.library().contains("\"id\":\"pbeach00001\""), "still listed");
        String listed = lib.library();
        int at = listed.indexOf("\"id\":\"pbeach00001\""), end = listed.indexOf("{\"group\"", at);
        check(listed.substring(at, end < 0 ? listed.length() : end).contains("\"src\":true"), "listed with src");
        check(lib.photoFile("gholiday1/pbeach00001/source.jpg") == null && lib.photoFile("gholiday1/pbeach00001/src.jpeg") == null, "no other name");
        String[] bad = {"gholiday1/pbeach00001/meta.json", "gholiday1/pbeach00001/../psunset0002/wall.jpg",
                "gholiday1/pbeach00001/wall.jpeg", "gholiday1/pbeach00001/WALL.jpg", "gholiday1/pbeach00001", "gholiday1/pbeach00001/",
                "gholiday1/pbeach00001/wall.jpg/x", "../R/groups/gholiday1/photos/pbeach00001/wall.jpg", "gholiday1/BAD..ID/wall.jpg",
                "gholiday1/.pinflight04.tmp/wall.jpg", "Gbadgroup/pbeach00001/wall.jpg", "gholiday1/pmissing006/field-blur.jpg",
                "gholiday1//pbeach00001/wall.jpg", "/gholiday1/pbeach00001/wall.jpg", "gholiday1\\pbeach00001\\wall.jpg", "", null,
                "gholiday1/pbeach00001/wall.jpg\u0000", "upload-token.json"};
        for (String b : bad) check(lib.photoFile(b) == null, "route refuses " + b);
        // a symlinked photo dir that leaves R is refused by the canonical check
        Path outside = r.getParent().resolve("secret");
        sevenFiles(outside, "p0000000001", 1);
        Files.createSymbolicLink(r.resolve("groups/gholiday1/photos/p0000000001"), outside);
        check(lib.photoFile("gholiday1/p0000000001/wall.jpg") == null, "route refuses a path whose canonical form leaves R");
        check(!lib.library().contains("p0000000001"), "and the listing skips the symlink");
        // a directory named like a file is not served
        Files.createDirectories(r.resolve("groups/gfamily02/photos/p0000000002/wall.jpg"));
        check(lib.photoFile("gfamily02/p0000000002/wall.jpg") == null, "a directory is not a file");
    }

    // ---------- token ----------

    void tokenIssueExpireClear() throws IOException {
        fixture();
        clock.t = 1_000_000;
        Map<String, Object> a = obj(lib.uploadStart("gholiday1", "http://192.168.0.6:8097"));
        eq(a.keySet().toString(), "[ok, url, base, code, expires]", "uploadStart keys");
        eq(a.get("ok"), true, "uploadStart ok");
        eq(a.get("base"), "http://192.168.0.6:8097", "base");
        eq(a.get("expires"), 1_000_000L + 15 * 60 * 1000L, "expires = now + 15 min");
        check(((String) a.get("code")).matches("[0-9]{6}"), "code is 6 digits");
        String url = (String) a.get("url");
        check(url.matches("http://192\\.168\\.0\\.6:8097/u/[0-9a-f]{32}"), "url = base/u/<32 hex>: " + url);
        Map<String, Object> f = UserLibrary.readObject(r.resolve("upload-token.json"));
        eq(f.keySet().toString(), "[v, token, code, group, expires]", "token file keys");
        eq(f.get("token"), url.substring(url.length() - 32), "file token = url token");
        eq(f.get("code"), a.get("code"), "file code");
        eq(f.get("group"), "gholiday1", "file group");
        eq(f.get("expires"), a.get("expires"), "file expires");
        check(!Files.exists(r.resolve(".upload-token.json.tmp")), "no tmp token left");
        Map<String, Object> b = obj(lib.uploadStart("space", "http://192.168.0.6:8097"));
        check(!b.get("url").equals(a.get("url")), "a fresh token on each start");
        eq(UserLibrary.readObject(r.resolve("upload-token.json")).get("group"), "space", "replaced, for the new group");
        clock.t = (Long) b.get("expires") - 1;
        check(lib.currentToken() != null, "valid until expires");
        clock.t = (Long) b.get("expires");
        check(lib.currentToken() == null, "expired at expires");
        eq(lib.uploadStop(), "{\"ok\":true}", "uploadStop");
        check(!Files.exists(r.resolve("upload-token.json")), "token removed");
        eq(lib.uploadStop(), "{\"ok\":true}", "uploadStop with no token");
        // codes keep leading zeros and are not all the same
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            lib.uploadStop();
            codes.add((String) obj(lib.uploadStart("space", "http://h:1")).get("code"));
        }
        check(codes.size() > 150, "codes vary: " + codes.size());
        Random zero = new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        lib.uploadStop();
        UserLibrary z = new UserLibrary(r.toFile(), clock, zero);
        eq(obj(z.uploadStart("space", "http://h:1")).get("code"), "000000", "code is zero-padded");
        lib.uploadStop();
    }

    void tokenRenewal() throws IOException {
        fixture();
        lib.uploadStop();
        clock.t = 1_000_000;
        Map<String, Object> a = obj(lib.uploadStart("gholiday1", "http://h:1"));
        Object inode = Files.readAttributes(r.resolve("upload-token.json"), BasicFileAttributes.class).fileKey();
        clock.t += 14 * 60 * 1000L; // 60 s before expiry, as the Add photo screen renews
        Map<String, Object> b = obj(lib.uploadStart("gholiday1", "http://h:1"));
        eq(b.get("url"), a.get("url"), "same group within its lifetime: same token");
        eq(b.get("code"), a.get("code"), "same group within its lifetime: same code");
        eq(b.get("expires"), clock.t + 15 * 60 * 1000L, "renewal pushes expires to now + 15 min");
        Map<String, Object> f = UserLibrary.readObject(r.resolve("upload-token.json"));
        eq(f.get("expires"), b.get("expires"), "renewed expiry is on disk");
        eq(f.get("token"), ((String) a.get("url")).substring(((String) a.get("url")).length() - 32), "token on disk kept");
        check(inode == null || !inode.equals(Files.readAttributes(r.resolve("upload-token.json"), BasicFileAttributes.class).fileKey()),
                "renewal is written atomically (tmp + rename)");
        check(!Files.exists(r.resolve(".upload-token.json.tmp")), "no tmp token left");
        // after expiry: a new token
        clock.t = (Long) b.get("expires");
        Map<String, Object> c = obj(lib.uploadStart("gholiday1", "http://h:1"));
        check(!c.get("url").equals(b.get("url")), "same group after expiry: a new token");
        eq(c.get("expires"), clock.t + 15 * 60 * 1000L, "new token's expiry");
        // a different group: a new token
        Map<String, Object> d = obj(lib.uploadStart("space", "http://h:1"));
        check(!d.get("url").equals(c.get("url")), "a different group: a new token");
        eq(UserLibrary.readObject(r.resolve("upload-token.json")).get("group"), "space", "token now for space");
        // after uploadStop: a new token
        lib.uploadStop();
        Map<String, Object> e = obj(lib.uploadStart("space", "http://h:1"));
        check(!e.get("url").equals(d.get("url")), "after uploadStop: a new token");
    }

    void tokenGroupAndBase() throws IOException {
        fixture();
        refused(lib.uploadStart("gnojson03", "http://192.168.0.6:8097"), UserLibrary.NO_GROUP, "group without group.json");
        refused(lib.uploadStart("gnothere1", "http://192.168.0.6:8097"), UserLibrary.NO_GROUP, "missing group");
        String[] badBase = {"https://192.168.0.6:8097", "http://192.168.0.6", "http://192.168.0.6:8097/", "http://192.168.0.6:0",
                "http://192.168.0.6:65536", "http://a b:1", "http://x:1/u", "ftp://x:1", "http://:1", "http://x\":1", null, ""};
        for (String b : badBase) {
            check(UserLibrary.validUploadBase(b) == null, "base refused: " + b);
            refused(lib.uploadStart("space", b), UserLibrary.BAD_BASE, "uploadStart base " + b);
        }
        eq(UserLibrary.validUploadBase("http://build-box.lan:65535"), "http://build-box.lan:65535", "host name base");
        // deleting the token's group clears the token
        lib.uploadStart("gfamily02", "http://h:1");
        lib.deleteGroup("gfamily02");
        check(!Files.exists(r.resolve("upload-token.json")), "token for a deleted group removed");
        lib.uploadStart("gholiday1", "http://h:1");
        lib.deletePhoto("gholiday1", "pbeach00001");
        check(Files.exists(r.resolve("upload-token.json")), "deleting a photo keeps the token");
    }

    /**
     * Review 006cfe9: deleting the group that held the live upload code removed the code but left the
     * upload server listening until Add photo closed. deleteGroup(gid, codeDropped) runs codeDropped
     * (MainActivity's stopUpload()) exactly then, after the library's lock is released.
     */
    /** Review 30e7356 (A4): the code never changed while Add photo stayed open; now it does after CODE_ROTATE_AFTER wrong codes. */
    void codeRotatesAfterWrongCodes() throws IOException {
        fixture();
        lib.uploadStop();
        clock.t = 1_000_000;
        Map<String, Object> a = obj(lib.uploadStart("gholiday1", "http://h:1"));
        for (int i = 0; i < UserLibrary.CODE_ROTATE_AFTER - 1; i++) lib.codeWrong();
        clock.t += 60_000;
        Map<String, Object> b = obj(lib.uploadStart("gholiday1", "http://h:1"));
        eq(b.get("code"), a.get("code"), (UserLibrary.CODE_ROTATE_AFTER - 1) + " wrong codes: the renewal keeps the code");
        lib.codeWrong();
        clock.t += 60_000;
        Map<String, Object> c = obj(lib.uploadStart("gholiday1", "http://h:1"));
        check(!c.get("code").equals(a.get("code")), UserLibrary.CODE_ROTATE_AFTER + " wrong codes: the renewal issues a new code");
        eq(c.get("url"), a.get("url"), "the token is kept: a phone already on its page is not cut off");
        eq(UserLibrary.readObject(r.resolve("upload-token.json")).get("code"), c.get("code"), "the new code is on disk (UploadServer reads it there)");
        eq(lib.currentToken().get("code"), c.get("code"), "currentToken() has it");
        clock.t += 60_000;
        eq(obj(lib.uploadStart("gholiday1", "http://h:1")).get("code"), c.get("code"), "the count starts again: the next renewal keeps the new code");
        for (int i = 0; i < UserLibrary.CODE_ROTATE_AFTER; i++) lib.codeWrong();
        lib.uploadStop();
        Map<String, Object> d = obj(lib.uploadStart("gholiday1", "http://h:1"));
        for (int i = 0; i < UserLibrary.CODE_ROTATE_AFTER - 1; i++) lib.codeWrong();
        clock.t += 60_000;
        eq(obj(lib.uploadStart("gholiday1", "http://h:1")).get("code"), d.get("code"), "a fresh token's count starts at 0");
        check(UserLibrary.CODE_ROTATE_AFTER >= 3 && UserLibrary.CODE_ROTATE_AFTER <= 10,
                "N stays above a typo or two and at most one address's limit a minute (UploadServer.CODE_FAILS_PER_MIN, 10)");
    }

    void deleteGroupWithCodeStopsUpload() throws IOException {
        fixture();
        int[] runs = {0};
        boolean[] heldLock = {false};
        Runnable stop = () -> {
            runs[0]++;
            heldLock[0] |= Thread.holdsLock(lib);
        };
        lib.uploadStart("gholiday1", "http://h:1");
        eq(lib.deleteGroup("gfamily02", stop), UserLibrary.ok(), "another group deleted");
        eq(runs[0], 0, "a group without the code: the upload page keeps running");
        check(Files.exists(r.resolve("upload-token.json")), "and the code is kept");
        refused(lib.deleteGroup("gnothere1", stop), UserLibrary.NO_GROUP, "a missing group");
        refused(lib.deleteGroup("space", stop), UserLibrary.BUILTIN, "a built-in");
        eq(runs[0], 0, "refusals stop nothing");
        eq(lib.deleteGroup("gholiday1", stop), UserLibrary.ok(), "the code's group deleted");
        eq(runs[0], 1, "its code dropped: the upload page is stopped once");
        check(!heldLock[0], "outside the library's lock");
        check(!Files.exists(r.resolve("upload-token.json")), "the code is gone");
        eq(lib.deleteGroup("gnojson03", (Runnable) null), UserLibrary.ok(), "no hook: still deletes");
    }

    /**
     * Review 006cfe9: library() (the page, every 2 s while Add photo is open) and the phone's state()
     * re-read every photo's files and meta.json. The listing is now kept until the library changes:
     * a photo written behind the library's back stays unlisted until the next change, which shows it.
     */
    void listingCachedUntilChanged() throws IOException {
        fixture();
        Path photos = r.resolve("groups/gholiday1/photos");
        String before = lib.library();
        check(lib.listing() == lib.listing(), "the same listing between changes");
        int n = 90;
        sevenFiles(photos.resolve(pid(n)), pid(n), 5);
        eq(lib.library(), before, "kept: not re-read while nothing changed");
        String g = okId(lib.addGroup("Cached"));
        check(lib.library().contains(g) && lib.library().contains(pid(n)), "addGroup: re-read");
        sevenFiles(photos.resolve(pid(++n)), pid(n), 5);
        eq(lib.renameGroup(g, "Renamed"), UserLibrary.ok(), "rename");
        check(lib.library().contains("Renamed") && lib.library().contains(pid(n)), "renameGroup: re-read");
        sevenFiles(photos.resolve(pid(++n)), pid(n), 5);
        eq(lib.deletePhoto("gholiday1", pid(90)), UserLibrary.ok(), "delete a photo");
        check(!lib.library().contains(pid(90)) && lib.library().contains(pid(n)), "deletePhoto: re-read");
        sevenFiles(photos.resolve(pid(++n)), pid(n), 5);
        eq(lib.deleteGroup(g), UserLibrary.ok(), "delete a group");
        check(!lib.library().contains(g) && lib.library().contains(pid(n)), "deleteGroup: re-read");
        sevenFiles(photos.resolve(pid(++n)), pid(n), 5);
        lib.changed();
        check(lib.library().contains(pid(n)), "changed() (PendingPhotos moving a photo into place): re-read");
        // never older than LISTING_MAX_AGE_MS, and a clock that went back reads again
        sevenFiles(photos.resolve(pid(++n)), pid(n), 5);
        clock.t += UserLibrary.LISTING_MAX_AGE_MS - 1;
        check(!lib.library().contains(pid(n)), "kept within its age");
        clock.t += 1;
        check(lib.library().contains(pid(n)), "re-read at LISTING_MAX_AGE_MS");
        sevenFiles(photos.resolve(pid(++n)), pid(n), 5);
        clock.t -= 1;
        check(lib.library().contains(pid(n)), "re-read when the clock went back");
        // a listing read while a folder could not be read is not kept
        Path groups = r.resolve("groups");
        check(groups.toFile().setReadable(false), "chmod groups/");
        String broken;
        try {
            lib.changed();
            broken = lib.library();
        } finally {
            groups.toFile().setReadable(true);
        }
        check(!broken.contains("gholiday1"), "unreadable: only the built-ins (" + broken + ")");
        check(lib.library().contains("gholiday1"), "readable again: read again, not the broken listing");
    }

    void malformedTokenRejected() throws IOException {
        fixture();
        String[] bad = {"{\"v\":1,\"token\":\"0123\",\"code\":\"482913\",\"group\":\"gholiday1\",\"expires\":9999999999999}",
                "{\"v\":1,\"token\":\"0123456789abcdef0123456789abcdef\",\"code\":\"48291\",\"group\":\"gholiday1\",\"expires\":9999999999999}",
                "{\"v\":1,\"token\":\"0123456789abcdef0123456789abcdef\",\"code\":\"482913\",\"group\":\"../x\",\"expires\":9999999999999}",
                "{\"v\":1,\"token\":\"0123456789abcdef0123456789abcdef\",\"code\":\"482913\",\"group\":\"gholiday1\",\"expires\":\"9\"}",
                "{\"v\":2,\"token\":\"0123456789abcdef0123456789abcdef\",\"code\":\"482913\",\"group\":\"gholiday1\",\"expires\":9999999999999}",
                "{\"v\":1,\"token\":\"0123456789ABCDEF0123456789abcdef\",\"code\":\"482913\",\"group\":\"gholiday1\",\"expires\":9999999999999}",
                "{oops"};
        for (String b : bad) {
            Files.write(r.resolve("upload-token.json"), b.getBytes(StandardCharsets.UTF_8));
            check(lib.currentToken() == null, "token refused: " + b);
        }
    }

    void atomicWrite() throws IOException {
        empty();
        Files.createDirectories(r);
        Path f = r.resolve("x.json");
        UserLibrary.writeAtomically(f, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        eq(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), "{\"a\":1}", "written");
        Object inode = Files.readAttributes(f, BasicFileAttributes.class).fileKey();
        UserLibrary.writeAtomically(f, "{\"a\":2}".getBytes(StandardCharsets.UTF_8));
        eq(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), "{\"a\":2}", "replaced");
        check(inode == null || !inode.equals(Files.readAttributes(f, BasicFileAttributes.class).fileKey()),
                "replaced by rename (a new file), not rewritten in place");
        eq(r.toFile().list().length, 1, "tmp file gone");
        // a tmp name that cannot be written leaves the old file intact
        Files.createDirectories(r.resolve(".x.json.tmp"));
        Files.write(r.resolve(".x.json.tmp").resolve("in-the-way"), new byte[]{1});
        boolean threw = false;
        try {
            UserLibrary.writeAtomically(f, "{\"a\":3}".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            threw = true;
        }
        check(threw, "write through a blocked tmp throws");
        eq(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), "{\"a\":2}", "old content intact after a failed write");
        // the token write goes through the same path: a blocked tmp fails the start, no partial token
        UserLibrary.deleteTree(r.resolve(".x.json.tmp"));
        Files.createDirectories(r.resolve(".upload-token.json.tmp/x"));
        lib.addGroup("A");
        refused(lib.uploadStart("space", "http://h:1"), UserLibrary.FAILED, "token write blocked");
        check(!Files.exists(r.resolve("upload-token.json")), "no token after a failed write");
    }

    // ---------- JSON ----------

    void jsonReader() {
        Map<String, Object> m = obj(" {\"a\":[1,-2,3.5,1e2,true,false,null,\"\\u00e9\\n\\\"\\\\\\/\"],\"b\":{},\"c\":[]} ");
        eq(m.get("a").toString(), "[1, -2, 3.5, 100.0, true, false, null, \u00e9\n\"\\/]", "parsed values");
        check(((List<?>) m.get("a")).get(0) instanceof Long, "integers read as Long");
        check(((List<?>) m.get("a")).get(6) == UserLibrary.Json.NULL, "null is Json.NULL");
        eq(UserLibrary.Json.parse("12345678901234567890"), 1.2345678901234567e19, "a too-big integer reads as a double");
        String deep = repeat("[", 17) + repeat("]", 17);
        String[] bad = {"", "{", "{\"a\":1,}", "[1,]", "{\"a\" 1}", "{a:1}", "{'a':1}", "[01]", "[1.]", "[.5]", "[1e]", "[-]",
                "[+1]", "[NaN]", "[Infinity]", "[1e999]", "\"a\u0001\"", "\"\\x\"", "\"\\u12\"", "\"\\u12g4\"", "[1] x", "{} {}",
                "{\"a\":1,\"a\":2}", "/*c*/{}", "[tru]", "[nul]", "\"open", deep, "[1 2]"};
        for (String b : bad) {
            boolean threw = false;
            try {
                UserLibrary.Json.parse(b);
            } catch (UserLibrary.Json.Malformed e) {
                threw = true;
            }
            check(threw, "malformed JSON refused: " + b);
        }
        UserLibrary.Json.parse(repeat("[", 16) + repeat("]", 16)); // depth 16 is allowed
    }

    void jsonWriter() {
        String s = new UserLibrary.Json.W().beginObject().key("s").value("q\"b\\n\nr\rt\tc\u0001d\u2028e\u007f/<").key("n").value(-3)
                .key("d").value((Number) 0.12).key("i").value((Number) 2.0).key("b").value(true).key("z").value((String) null)
                .key("a").beginArray().value(1).beginObject().endObject().beginArray().endArray().endArray().endObject().toString();
        eq(s, "{\"s\":\"q\\\"b\\\\n\\nr\\rt\\tc\\u0001d\\u2028e\\u007f/<\",\"n\":-3,\"d\":0.12,\"i\":2,\"b\":true,\"z\":null,"
                + "\"a\":[1,{},[]]}", "writer output");
        Map<String, Object> back = obj(s);
        eq(back.get("s"), "q\"b\\n\nr\rt\tc\u0001d\u2028e\u007f/<", "round trip");
        boolean threw = false;
        try {
            new UserLibrary.Json.W().value((Number) Double.NaN);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "NaN is not written");
        eq(UserLibrary.fail("a \"b\""), "{\"ok\":false,\"reason\":\"a \\\"b\\\"\"}", "fail() escapes");
    }

    public static void main(String[] args) throws Exception {
        UserLibraryTest t = new UserLibraryTest();
        String[] names = {"listingMatchesExpected", "exactJsonShape", "fixtureToken", "missingRootIsEmpty", "malformedSkipped",
                "validMetaAccepted", "missingFileSkipped", "photosOfUnlistedGroupNotListed", "addRenameDeleteGroup", "failedDeleteStillGoneAndSwept", "builtinsProtected",
                "groupLimit", "photoLimits", "traversalIdsAndNames", "routeFiles", "tokenIssueExpireClear", "tokenGroupAndBase",
                "tokenRenewal", "codeRotatesAfterWrongCodes", "deleteGroupWithCodeStopsUpload", "listingCachedUntilChanged", "malformedTokenRejected", "atomicWrite", "jsonReader", "jsonWriter"};
        int failed = 0;
        for (String n : names) {
            try {
                UserLibraryTest.class.getDeclaredMethod(n).invoke(t);
                System.out.println("ok   " + n);
            } catch (java.lang.reflect.InvocationTargetException e) {
                failed++;
                Throwable c = e.getCause();
                System.out.println("FAIL " + n + ": " + (c instanceof FallbackPolicyTest.Fail ? c.getMessage() : c));
            }
        }
        System.out.println((names.length - failed) + "/" + names.length + " passed");
        if (failed > 0) System.exit(1);
    }
}
