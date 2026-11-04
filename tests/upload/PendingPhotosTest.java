package home.cleanframe.tv;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import static home.cleanframe.tv.UploadTests.check;
import static home.cleanframe.tv.UploadTests.eq;
import static home.cleanframe.tv.UploadTests.obj;
import static home.cleanframe.tv.UploadTests.run;

/**
 * PendingPhotos (unfinished photos and the page's material step) against the committed tmp tree
 * tests/upload/fixture/R: materialJobs() lists exactly the ready jobs with what each still needs;
 * saveMaterial() refuses a bad kind, ids, a data URL that is not JPEG, an oversize one, a broken
 * JPEG and the wrong dimensions, and moves the photo into place on the last material; the counts
 * against the limits; start-up clean-up; the /home/user-tmp/ route's file resolution. Adjust: a photo
 * re-made from its src.jpg (recropBegin/recropRun/recropState) is an ordinary job and is swapped in
 * over the photo under the same id; refusals, a failed re-make and a photo deleted meanwhile.
 */
final class PendingPhotosTest {
    static final long NOW = 1_761_901_000_000L;
    static final Path FIXTURE = Paths.get("tests/upload/fixture/R");

    Path r;
    UploadTests.Clock clock = new UploadTests.Clock(NOW);
    UserLibrary lib;
    PendingPhotos pp;

    PendingPhotosTest() throws Exception {
        r = UploadTests.tmp("cf-pending").resolve("R");
        UploadTests.copyTree(FIXTURE, r);
        lib = new UserLibrary(r.toFile(), clock, new Random(7));
        pp = new PendingPhotos(lib, clock, new Random(11));
        // git keeps no mtimes: everything in the copy is "now", except what a case ages on purpose
        try (java.util.stream.Stream<Path> all = Files.walk(r)) {
            for (Path p : (Iterable<Path>) all::iterator) Files.setLastModifiedTime(p, FileTime.fromMillis(NOW - 60_000));
        }
    }

    static void refused(String answer, String reason, String what) {
        Map<String, Object> o = obj(answer);
        eq(o.get("ok"), false, what + " ok");
        eq(o.get("reason"), reason, what + " reason");
    }

    static void saved(String answer, boolean done, String what) {
        eq(answer, "{\"ok\":true,\"done\":" + done + "}", what);
    }

    /**
     * Starts call on its own thread while this thread holds the library's lock (as a running
     * UserLibrary.deleteGroup() does), and checks that the call waits for that lock (BLOCKED) instead
     * of running past it; then runs whileHeld, still holding it. The call's result or exception.
     */
    static Object underLibraryLock(UserLibrary lib, java.util.concurrent.Callable<Object> call, UploadTests.Case whileHeld, String what) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Object> out = new java.util.concurrent.atomic.AtomicReference<>();
        Thread c = new Thread(() -> {
            try {
                out.set(call.call());
            } catch (Throwable e) {
                out.set(e);
            }
        }, "pending-race");
        synchronized (lib) {
            c.start();
            long end = System.nanoTime() + 10_000_000_000L;
            while (c.getState() != Thread.State.BLOCKED && c.isAlive() && System.nanoTime() < end) Thread.sleep(1);
            eq(c.getState(), Thread.State.BLOCKED, what + " waits for the library's lock (it finished as " + out.get() + ")");
            whileHeld.run();
        }
        c.join(10_000);
        check(!c.isAlive(), what + " finished");
        return out.get();
    }

    public static void main(String[] a) {
        run("pending: materialJobs lists the ready tmp photos, oldest first, with what they need", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            eq(t.pp.materialJobs(), "{\"v\":1,\"jobs\":["
                    + "{\"gid\":\"gholiday1\",\"pid\":\"pjob0000002\",\"wall\":\"user-tmp/gholiday1/pjob0000002/wall.jpg\",\"tone\":\"light\","
                    + "\"need\":[\"field-clear\",\"field-frost\",\"study-soft\"]},"
                    + "{\"gid\":\"gholiday1\",\"pid\":\"pjob0000001\",\"wall\":\"user-tmp/gholiday1/pjob0000001/wall.jpg\",\"tone\":\"dark\","
                    + "\"need\":[\"panel\",\"field-blur\",\"field-clear\",\"field-frost\",\"study-soft\"]},"
                    + "{\"gid\":\"space\",\"pid\":\"pspace00006\",\"wall\":\"user-tmp/space/pspace00006/wall.jpg\",\"tone\":\"dark\","
                    + "\"need\":[\"panel\",\"field-blur\",\"field-clear\",\"field-frost\",\"study-soft\"]}]}", "jobs");
            // not listed: no meta.json yet, a bad meta.json, a bad id, a group without group.json, a finished
            // photo, and pfull000010 (complete, needs nothing: start-up moves it into place)
            eq(t.lib.photos("gholiday1").size(), 1, "the finished photo is UserLibrary's");
            eq(UserLibrary.allPhotos(t.lib.groups(), t.lib).size(), 1, "tmp photos are never in library()");
        });
        run("pending: counts and room count unfinished photos against the limits", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            eq(t.pp.count("gholiday1"), 5, "holiday: 2 ready, 1 complete, 1 processing, 1 bad meta");
            eq(t.pp.count("space"), 1, "space");
            eq(t.pp.count("scenery"), 0, "scenery");
            eq(t.pp.count(null), 6, "all listed groups");
            eq(t.pp.room("gholiday1"), 54, "60 - 1 finished - 5 unfinished");
            eq(t.pp.room("gfamily02"), 60, "an empty group");
        });
        run("pending: saveMaterial refuses bad kinds, ids, data and sizes", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            String panel = UploadTests.dataUrl(UploadTests.jpeg(320, 180));
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "wall", panel), PendingPhotos.BAD_KIND, "kind wall");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "meta", panel), PendingPhotos.BAD_KIND, "kind meta");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", null, panel), PendingPhotos.BAD_KIND, "kind null");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "../panel", panel), PendingPhotos.BAD_KIND, "kind traversal");
            refused(t.pp.saveMaterial("../gholiday1", "pjob0000001", "panel", panel), PendingPhotos.NO_JOB, "gid traversal");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001/..", "panel", panel), PendingPhotos.NO_JOB, "pid traversal");
            refused(t.pp.saveMaterial("gholiday1", "pnothere000", "panel", panel), PendingPhotos.NO_JOB, "no such job");
            refused(t.pp.saveMaterial("gholiday1", "pnometa0003", "panel", panel), PendingPhotos.NO_JOB, "processing not done");
            refused(t.pp.saveMaterial("gholiday1", "pdone000005", "panel", panel), PendingPhotos.NO_JOB, "a finished photo");
            refused(t.pp.saveMaterial("gnogroup1", "pjob0000007", "panel", panel), PendingPhotos.NO_JOB, "a group without group.json");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "panel", null), PendingPhotos.BAD_DATA, "null data");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "panel", panel.replace("image/jpeg", "image/png")), PendingPhotos.BAD_DATA, "png data URL");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "panel", "data:image/jpeg;base64,***"), PendingPhotos.BAD_DATA, "not base64");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "panel", "data:image/jpeg;base64," + "A".repeat(1398108)), PendingPhotos.TOO_BIG,
                    "over the limit before decoding");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "panel", UploadTests.dataUrl(new byte[1024 * 1024 + 1])), PendingPhotos.TOO_BIG,
                    "1 MB + 1 decoded");
            byte[] real = UploadTests.jpeg(320, 180);
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "panel", UploadTests.dataUrl(Arrays.copyOf(real, real.length - 2))),
                    PendingPhotos.BAD_JPEG, "a truncated JPEG");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "panel", UploadTests.dataUrl("not a jpeg at all".getBytes())),
                    PendingPhotos.BAD_JPEG, "not a JPEG");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "panel", UploadTests.dataUrl(UploadTests.jpeg(1920, 1080))),
                    "The panel material must be 320x180, not 1920x1080.", "a field-size panel");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "field-blur", panel),
                    "The field-blur material must be 1920x1080, not 320x180.", "a panel-size field");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "study-soft", UploadTests.dataUrl(UploadTests.jpeg(1920, 1079))),
                    "The study-soft material must be 1920x1080, not 1920x1079.", "one row short");
            byte[] gps = PhotoMathTest.plant(real, PhotoMathTest.segment(0xe1, "Exif\0\0MM\0*GPS-51.5N"));
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "panel", UploadTests.dataUrl(gps)), PendingPhotos.METADATA, "a material with EXIF (A6)");
            byte[] com = PhotoMathTest.plant(real, PhotoMathTest.segment(0xfe, "a comment"));
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "panel", UploadTests.dataUrl(com)), PendingPhotos.METADATA, "a material with a comment (A6)");
            for (String k : PendingPhotos.KINDS) {
                check(!Files.exists(t.r.resolve("groups/gholiday1/photos/.pjob0000001.tmp/" + k + ".jpg")), "nothing written for " + k);
            }
        });
        run("pending: the sizes table is upstream's files' sizes", () -> {
            eq(Arrays.deepToString(PendingPhotos.SIZES), "[[320, 180], [1920, 1080], [1920, 1080], [1920, 1080], [1920, 1080]]", "table");
            byte[] p = Files.readAllBytes(Paths.get("web/assets/wallpapers/golden-panel.jpg"));
            Jpeg.Info i = Jpeg.parse(p, p.length);
            eq(i.width + "x" + i.height, PendingPhotos.SIZES[0][0] + "x" + PendingPhotos.SIZES[0][1], "golden-panel.jpg");
            for (int k = 1; k < PendingPhotos.KINDS.length; k++) {
                byte[] m = Files.readAllBytes(Paths.get("web/assets/materials/golden-" + PendingPhotos.KINDS[k] + ".jpg"));
                Jpeg.Info j = Jpeg.parse(m, m.length);
                eq(j.width + "x" + j.height, PendingPhotos.SIZES[k][0] + "x" + PendingPhotos.SIZES[k][1], "golden-" + PendingPhotos.KINDS[k] + ".jpg");
            }
            eq(String.join(",", PendingPhotos.KINDS) + ",wall.jpg,thumb.jpg", "panel,field-blur,field-clear,field-frost,study-soft,wall.jpg,thumb.jpg", "kinds");
            for (String k : PendingPhotos.KINDS) check(UserLibrary.PHOTO_FILES.contains(k + ".jpg"), k + " is a contract 1 file");
        });
        run("pending: Adjust re-makes a photo from its src.jpg and swaps it in under the same id", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            Path photo = t.r.resolve("groups/gholiday1/photos/pdone000005");
            byte[] oldWall = Files.readAllBytes(photo.resolve("wall.jpg"));
            refused(t.pp.recropBegin("gholiday1", "pdone000005", "0.5,0.5,1"), PendingPhotos.NO_SRC, "no src.jpg");
            Files.write(photo.resolve("src.jpg"), UploadTests.jpeg(400, 300));
            refused(t.pp.recropBegin("gholiday1", "pdone000005", "0.5,0.5"), PendingPhotos.BAD_CROP, "bad crop");
            refused(t.pp.recropBegin("gholiday1", "pnothere001", "0.5,0.5,1"), UserLibrary.NO_PHOTO, "no photo");
            refused(t.pp.recropBegin("gnothere1", "pdone000005", "0.5,0.5,1"), UserLibrary.NO_GROUP, "no group");
            eq(t.pp.recropBegin("gholiday1", "pdone000005", "0.2,0.8,2"), "{\"ok\":true}", "begun");
            eq(t.pp.recropState("gholiday1", "pdone000005"), "{\"ok\":true,\"state\":\"working\"}", "working");
            refused(t.pp.recropBegin("gholiday1", "pdone000005", "0.5,0.5,1"), PendingPhotos.BUSY, "one at a time");
            UploadServerTest.Stub stub = new UploadServerTest.Stub();
            t.pp.recropRun("gholiday1", "pdone000005", "0.2,0.8,2", stub);
            eq(stub.lastCrop.x + "," + stub.lastCrop.y + "," + stub.lastCrop.z, "0.2,0.8,2.0", "the crop");
            eq(stub.lastEdited, NOW, "edited now");
            eq(t.pp.recropState("gholiday1", "pdone000005"), "{\"ok\":true,\"state\":\"saving\"}", "waiting for materials");
            check(t.pp.materialJobs().contains("\"pid\":\"pdone000005\""), "an ordinary material job");
            check(t.lib.library().contains("pdone000005"), "the photo is still listed meanwhile");
            for (int k = 0; k < PendingPhotos.KINDS.length; k++) {
                saved(t.pp.saveMaterial("gholiday1", "pdone000005", PendingPhotos.KINDS[k],
                        UploadTests.dataUrl(UploadTests.jpeg(PendingPhotos.SIZES[k][0], PendingPhotos.SIZES[k][1]))), k == PendingPhotos.KINDS.length - 1, PendingPhotos.KINDS[k]);
            }
            check(!Arrays.equals(Files.readAllBytes(photo.resolve("wall.jpg")), oldWall), "the new wall is in place");
            check(Files.isRegularFile(photo.resolve("src.jpg")), "src.jpg kept");
            Map<String, Object> m = UserLibrary.readObject(photo.resolve("meta.json"));
            eq(m.get("edited"), NOW, "meta.json says when");
            eq(PhotoMath.metaCrop(m).z, 2.0, "and the crop");
            eq(t.pp.recropState("gholiday1", "pdone000005"), "{\"ok\":true,\"state\":\"done\"}", "done");
            String lib = t.lib.library();
            check(lib.contains("\"src\":true,\"crop\":{\"x\":0.2,\"y\":0.8,\"z\":2},\"edited\":" + NOW), "listed with src, crop, edited: " + lib);
            try (java.util.stream.Stream<Path> left = Files.list(photo.getParent())) {
                eq(left.map(x -> x.getFileName().toString()).filter(x -> x.contains("pdone000005")).sorted().collect(java.util.stream.Collectors.joining(",")),
                        "pdone000005", "no tmp or old copy of it left");
            }
        });
        run("pending: a failed Adjust says why once and leaves nothing; a photo deleted meanwhile is not brought back", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            Path photo = t.r.resolve("groups/gholiday1/photos/pdone000005");
            Files.write(photo.resolve("src.jpg"), UploadTests.jpeg(400, 300));
            UploadServerTest.Stub stub = new UploadServerTest.Stub();
            stub.refuse = "broken";
            eq(t.pp.recropBegin("gholiday1", "pdone000005", "0.5,0.5,1"), "{\"ok\":true}", "begun");
            t.pp.recropRun("gholiday1", "pdone000005", "0.5,0.5,1", stub);
            eq(t.pp.recropState("gholiday1", "pdone000005"), "{\"ok\":true,\"state\":\"failed\",\"reason\":\"" + PhotoProcessor.BROKEN + "\"}", "failed");
            eq(t.pp.recropState("gholiday1", "pdone000005"), "{\"ok\":true,\"state\":\"done\"}", "said once");
            check(!Files.exists(t.pp.tmpDir("gholiday1", "pdone000005")), "tmp removed");
            stub.refuse = null;
            eq(t.pp.recropBegin("gholiday1", "pdone000005", "0.5,0.5,1"), "{\"ok\":true}", "again");
            t.pp.recropRun("gholiday1", "pdone000005", "0.5,0.5,1", stub);
            eq(t.lib.deletePhoto("gholiday1", "pdone000005"), "{\"ok\":true}", "deleted while its materials are drawn");
            for (int k = 0; k < PendingPhotos.KINDS.length; k++) {
                t.pp.saveMaterial("gholiday1", "pdone000005", PendingPhotos.KINDS[k],
                        UploadTests.dataUrl(UploadTests.jpeg(PendingPhotos.SIZES[k][0], PendingPhotos.SIZES[k][1])));
            }
            check(!Files.exists(photo), "not brought back");
            check(!Files.exists(t.pp.tmpDir("gholiday1", "pdone000005")), "and its tmp is gone");
        });
        run("pending: an Adjust a dead process left unfinished is not saving, not counted, replaced and swept at start-up", () -> {
            // review 006cfe9: the process died mid-Adjust, leaving an empty .<pid>.tmp beside the photo;
            // Adjust then said "still being saved", recropState "saving", it counted against the limits,
            // and start-up removed it only once an hour old. A new PendingPhotos is a new process.
            PendingPhotosTest t = new PendingPhotosTest();
            Path photos = t.r.resolve("groups/gholiday1/photos"), left = photos.resolve(".pdone000005.tmp");
            Files.write(photos.resolve("pdone000005/src.jpg"), UploadTests.jpeg(400, 300));
            Files.createDirectory(left);
            Files.write(left.resolve("wall.jpg"), UploadTests.jpeg(96, 64)); // got as far as the wall
            eq(t.pp.recropState("gholiday1", "pdone000005"), "{\"ok\":true,\"state\":\"done\"}", "not saving");
            eq(t.pp.count("gholiday1"), 5, "not counted (the fixture's 5)");
            eq(t.pp.room("gholiday1"), 54, "no room taken");
            eq(t.pp.recropBegin("gholiday1", "pdone000005", "0.5,0.5,1"), "{\"ok\":true}", "a new Adjust replaces it");
            check(!Files.exists(left.resolve("wall.jpg")), "the dead one's files are gone");
            eq(t.pp.recropState("gholiday1", "pdone000005"), "{\"ok\":true,\"state\":\"working\"}", "working");
            eq(t.pp.count("gholiday1"), 6, "a live Adjust counts");
            refused(t.pp.recropBegin("gholiday1", "pdone000005", "0.5,0.5,1"), PendingPhotos.BUSY, "one at a time still");
            eq(t.pp.removeStale(), 2, "start-up beside a live Adjust: the fixture's 2, the live Adjust kept");
            check(Files.isDirectory(left), "this process's Adjust is not swept");
            // the process dies here: a fresh one's start-up removes the half-made Adjust, however young
            PendingPhotos next = new PendingPhotos(t.lib, t.clock, new Random(12));
            eq(next.recropState("gholiday1", "pdone000005"), "{\"ok\":true,\"state\":\"done\"}", "after the restart: not saving");
            eq(next.removeStale(), 1, "the dead Adjust removed at start-up");
            check(!Files.exists(left) && Files.isDirectory(photos.resolve("pdone000005")), "tmp gone, the photo kept");
            check(Files.isDirectory(photos.resolve(".pnometa0003.tmp")), "an upload still being prepared is not an Adjust: left to the hour rule");
            // a finished re-make whose materials are still to draw survives a restart: the page completes it
            eq(next.recropBegin("gholiday1", "pdone000005", "0.2,0.8,2"), "{\"ok\":true}", "again");
            next.recropRun("gholiday1", "pdone000005", "0.2,0.8,2", new UploadServerTest.Stub());
            PendingPhotos third = new PendingPhotos(t.lib, t.clock, new Random(13));
            eq(third.recropState("gholiday1", "pdone000005"), "{\"ok\":true,\"state\":\"saving\"}", "a finished re-make is saving");
            eq(third.removeStale(), 0, "and kept at start-up");
            eq(third.count("gholiday1"), 5, "counted (the fixture's 4 left and this one)");
            check(third.materialJobs().contains("\"pid\":\"pdone000005\""), "still a job for the page");
        });
        run("pending: a process that died mid-Adjust-swap loses no photo: start-up completes or undoes the swap (G2)", () -> {
            // The swap is two renames: <pid> -> .<pid>.old-*, then .<pid>.tmp -> <pid>. Until 30e7356 the old
            // copy was a .del-*, so a death between them lost both: start-up swept the .del-* and dropped the
            // complete Adjust result as "photo deleted meanwhile".
            for (int c = 0; c < 3; c++) {
                PendingPhotosTest t = new PendingPhotosTest();
                Path photos = t.r.resolve("groups/gholiday1/photos"), photo = photos.resolve("pdone000005");
                Files.write(photo.resolve("src.jpg"), UploadTests.jpeg(400, 300));
                byte[] oldWall = Files.readAllBytes(photo.resolve("wall.jpg"));
                eq(t.pp.recropBegin("gholiday1", "pdone000005", "0.2,0.8,2"), "{\"ok\":true}", "begun");
                t.pp.recropRun("gholiday1", "pdone000005", "0.2,0.8,2", new UploadServerTest.Stub());
                Path tmp = t.pp.tmpDir("gholiday1", "pdone000005");
                for (int k = 0; k < PendingPhotos.KINDS.length; k++) { // every material but the move: the job is complete
                    Files.write(tmp.resolve(PendingPhotos.KINDS[k] + ".jpg"), UploadTests.jpeg(PendingPhotos.SIZES[k][0], PendingPhotos.SIZES[k][1]));
                }
                check(PendingPhotos.complete(tmp, "pdone000005"), "the Adjust result is complete");
                byte[] newWall = Files.readAllBytes(tmp.resolve("wall.jpg"));
                Path old = photos.resolve(".pdone000005" + PendingPhotos.OLD + "abc123");
                Files.move(photo, old); // the first rename; the process dies here
                if (c == 1) UserLibrary.deleteTree(tmp); // case 1: no result to finish with (it was never complete)
                if (c == 2) Files.delete(tmp.resolve("study-soft.jpg")); // case 2: a result still missing a material
                PendingPhotos next = new PendingPhotos(t.lib, t.clock, new Random(21));
                next.removeStale();
                check(Files.isDirectory(photo), "case " + c + ": the photo is there after the restart");
                check(Files.isRegularFile(photo.resolve("meta.json")) && UserLibrary.validMeta(UserLibrary.readObject(photo.resolve("meta.json")), "pdone000005"),
                        "case " + c + ": a whole photo");
                if (c == 0) {
                    check(Arrays.equals(Files.readAllBytes(photo.resolve("wall.jpg")), newWall), "case 0: the swap is completed with the new copy");
                    eq(UserLibrary.readObject(photo.resolve("meta.json")).get("edited"), NOW, "case 0: edited");
                } else {
                    check(Arrays.equals(Files.readAllBytes(photo.resolve("wall.jpg")), oldWall), "case " + c + ": the old copy is put back");
                }
                check(!Files.exists(old), "case " + c + ": no old copy left");
                check(t.lib.library().contains("\"id\":\"pdone000005\""), "case " + c + ": listed again");
            }
            // the swap finished but the old copy was not removed yet: it goes, the photo stays as it is
            PendingPhotosTest t = new PendingPhotosTest();
            Path photos = t.r.resolve("groups/gholiday1/photos"), photo = photos.resolve("pdone000005");
            byte[] wall = Files.readAllBytes(photo.resolve("wall.jpg"));
            UploadTests.copyTree(photo, photos.resolve(".pdone000005" + PendingPhotos.OLD + "zz9999"));
            Files.write(photos.resolve(".pdone000005" + PendingPhotos.OLD + "zz9999/wall.jpg"), UploadTests.jpeg(64, 48));
            new PendingPhotos(t.lib, t.clock, new Random(22)).removeStale();
            check(!Files.exists(photos.resolve(".pdone000005" + PendingPhotos.OLD + "zz9999")), "the finished swap's old copy is removed");
            check(Arrays.equals(Files.readAllBytes(photo.resolve("wall.jpg")), wall), "the photo is untouched");
        });
        run("pending: the last material moves the photo into place", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            Path tmp = t.r.resolve("groups/gholiday1/photos/.pjob0000002.tmp");
            byte[] clear = UploadTests.jpeg(1920, 1080), frost = UploadTests.jpeg(1920, 1080), soft = UploadTests.jpeg(1920, 1080);
            saved(t.pp.saveMaterial("gholiday1", "pjob0000002", "field-clear", UploadTests.dataUrl(clear)), false, "1 of 3");
            saved(t.pp.saveMaterial("gholiday1", "pjob0000002", "field-clear", UploadTests.dataUrl(clear)), false, "again: replaced");
            check(!Files.exists(tmp.resolve(".field-clear.jpg.tmp")), "atomic write left no tmp file");
            saved(t.pp.saveMaterial("gholiday1", "pjob0000002", "field-frost", UploadTests.dataUrl(frost)), false, "2 of 3");
            eq(t.lib.photos("gholiday1").size(), 1, "still unfinished");
            saved(t.pp.saveMaterial("gholiday1", "pjob0000002", "study-soft", UploadTests.dataUrl(soft)), true, "3 of 3: done");
            check(!Files.exists(tmp), "tmp dir renamed");
            Path fin = t.r.resolve("groups/gholiday1/photos/pjob0000002");
            check(Arrays.equals(Files.readAllBytes(fin.resolve("study-soft.jpg")), soft), "bytes as sent");
            eq(t.lib.photos("gholiday1").size(), 2, "now listed");
            check(t.lib.library().contains("\"id\":\"pjob0000002\""), "in library()");
            check(!t.pp.materialJobs().contains("pjob0000002"), "no longer a job");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000002", "panel", UploadTests.dataUrl(UploadTests.jpeg(320, 180))), PendingPhotos.NO_JOB, "after done");
            eq(t.pp.count("gholiday1"), 4, "one fewer unfinished");
        });
        run("pending: library()'s kept listing is dropped when a photo moves into place (upload, Adjust, start-up)", () -> {
            // review 006cfe9: UserLibrary keeps the listing between changes; PendingPhotos moves photos in
            // outside the library's methods, so moveIntoPlace() must drop it (a stale listing is worse than a slow one)
            PendingPhotosTest t = new PendingPhotosTest();
            check(!t.lib.library().contains("pjob0000002"), "unfinished: not listed");
            for (String k : new String[]{"field-clear", "field-frost", "study-soft"}) {
                t.pp.saveMaterial("gholiday1", "pjob0000002", k, UploadTests.dataUrl(UploadTests.jpeg(1920, 1080)));
            }
            check(t.lib.library().contains("\"id\":\"pjob0000002\""), "the last material: listed at once");
            check(!t.lib.library().contains("pfull000010"), "complete, not yet moved: not listed");
            t.pp.removeStale();
            check(t.lib.library().contains("\"id\":\"pfull000010\""), "start-up moved it into place: listed at once");
            Files.write(t.r.resolve("groups/gholiday1/photos/pdone000005/src.jpg"), UploadTests.jpeg(400, 300));
            eq(t.pp.recropBegin("gholiday1", "pdone000005", "0.2,0.8,2"), "{\"ok\":true}", "Adjust");
            t.pp.recropRun("gholiday1", "pdone000005", "0.2,0.8,2", new UploadServerTest.Stub());
            check(!t.lib.library().contains("\"edited\""), "before the swap: the old photo");
            for (int k = 0; k < PendingPhotos.KINDS.length; k++) {
                t.pp.saveMaterial("gholiday1", "pdone000005", PendingPhotos.KINDS[k],
                        UploadTests.dataUrl(UploadTests.jpeg(PendingPhotos.SIZES[k][0], PendingPhotos.SIZES[k][1])));
            }
            check(t.lib.library().contains("\"edited\":" + NOW), "the Adjust swapped in: listed at once");
        });
        run("pending: a photo whose final directory exists is not overwritten", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            Files.createDirectories(t.r.resolve("groups/gholiday1/photos/pjob0000002"));
            saved(t.pp.saveMaterial("gholiday1", "pjob0000002", "field-clear", UploadTests.dataUrl(UploadTests.jpeg(1920, 1080))), false, "1");
            saved(t.pp.saveMaterial("gholiday1", "pjob0000002", "field-frost", UploadTests.dataUrl(UploadTests.jpeg(1920, 1080))), false, "2");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000002", "study-soft", UploadTests.dataUrl(UploadTests.jpeg(1920, 1080))), UserLibrary.FAILED, "3");
            check(Files.isDirectory(t.r.resolve("groups/gholiday1/photos/.pjob0000002.tmp")), "tmp kept");
        });
        run("pending: a deleted group takes its jobs with it", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            eq(t.lib.deleteGroup("gholiday1"), UserLibrary.ok(), "delete");
            check(!t.pp.materialJobs().contains("gholiday1"), "no jobs left for it");
            refused(t.pp.saveMaterial("gholiday1", "pjob0000001", "panel", UploadTests.dataUrl(UploadTests.jpeg(320, 180))), PendingPhotos.NO_JOB, "save");
        });
        run("pending: create and discard", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            String pid = t.pp.create("gholiday1");
            check(UserLibrary.validPid(pid), "valid pid " + pid);
            check(Files.isDirectory(t.r.resolve("groups/gholiday1/photos/." + pid + ".tmp")), "tmp made");
            String pid2 = t.pp.create("scenery");
            check(Files.isDirectory(t.r.resolve("groups/scenery/photos/." + pid2 + ".tmp")), "a built-in group gets its photos dir");
            check(!pid.equals(pid2), "fresh ids");
            for (String g : new String[]{"gnogroup1", "gnothere1", "../x", "Gbadgroup", null}) {
                boolean thrown = false;
                try {
                    t.pp.create(g);
                } catch (java.io.IOException e) {
                    thrown = true;
                }
                check(thrown, "create(" + g + ") refused");
            }
            t.pp.discard("gholiday1", pid);
            check(!Files.exists(t.r.resolve("groups/gholiday1/photos/." + pid + ".tmp")), "discarded");
            t.pp.discard("gholiday1", "../../etc"); // never throws, touches nothing
            t.pp.discard(null, null);
        });
        run("pending: discard leaves no job even when the delete fails while a late worker still writes", () -> {
            // review 999ba07 (L3): a timed-out decode keeps writing into .<pid>.tmp; a delete that failed
            // under it left the dir, the worker's meta.json made it a job, and the page added a photo the
            // phone had been told failed. discard() now renames it out of the way first.
            PendingPhotosTest t = new PendingPhotosTest();
            String pid = t.pp.create("gholiday1");
            Path photos = t.r.resolve("groups/gholiday1/photos"), dir = photos.resolve("." + pid + ".tmp");
            Files.write(dir.resolve("wall.jpg"), UploadTests.jpeg(96, 64));
            Path stuck = dir.resolve("stuck");
            Files.createDirectories(stuck);
            Files.write(stuck.resolve("busy"), new byte[1]);
            check(stuck.toFile().setWritable(false), "chmod: the delete cannot finish (the worker is still in there)");
            try {
                t.pp.discard("gholiday1", pid);
                check(!Files.exists(dir), "the unfinished photo's .tmp is gone at once, even though its delete failed");
                boolean late = false;
                try { // the worker's last writes
                    Files.write(dir.resolve("thumb.jpg"), UploadTests.jpeg(48, 32));
                    Files.write(dir.resolve("meta.json"), PhotoMath.metaJson(pid, "late.jpg", NOW,
                            new Readability.Fields("dark", "light", "mist", .12, 0, false, false)));
                    late = true;
                } catch (java.nio.file.NoSuchFileException e) {
                    // nowhere to write
                }
                check(!late, "a late worker could still write meta.json into the discarded photo");
                check(!t.pp.materialJobs().contains(pid), "no job for the discarded photo");
                eq(t.pp.count("gholiday1"), 5, "not counted against the limits (the fixture's 5)");
            } finally {
                try (java.util.stream.Stream<Path> all = Files.walk(photos)) {
                    all.forEach(p -> p.toFile().setWritable(true));
                }
            }
            t.pp.removeStale();
            try (java.util.stream.Stream<Path> all = Files.list(photos)) {
                check(all.noneMatch(p -> p.getFileName().toString().startsWith("." + pid)), "start-up removes what the failed delete left");
            }
        });
        run("pending: start-up removes tmp dirs older than an hour and half-deleted dirs", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            Path h = t.r.resolve("groups/gholiday1/photos");
            Files.setLastModifiedTime(h.resolve(".pjob0000001.tmp"), FileTime.fromMillis(NOW - PendingPhotos.STALE_MS - 1));
            Files.setLastModifiedTime(h.resolve(".pjob0000002.tmp"), FileTime.fromMillis(NOW - PendingPhotos.STALE_MS + 1000));
            Files.setLastModifiedTime(t.r.resolve("groups/gnogroup1/photos/.pjob0000007.tmp"), FileTime.fromMillis(NOW - 2 * PendingPhotos.STALE_MS));
            eq(t.pp.removeStale(), 4, "1 complete finished, 1 stale, 1 .del-, the old one in a group without group.json");
            check(Files.isDirectory(h.resolve("pfull000010")) && !Files.exists(h.resolve(".pfull000010.tmp")), "complete one moved into place");
            check(t.lib.library().contains("pfull000010"), "and listed");
            check(!Files.exists(h.resolve(".pjob0000001.tmp")), "stale gone");
            check(!Files.exists(h.resolve(".pdead000008.del-abc123")), "half-deleted gone");
            check(Files.exists(h.resolve(".pjob0000002.tmp")), "younger than an hour kept");
            check(Files.exists(h.resolve("pdone000005")), "finished photo kept");
            check(Files.exists(t.r.resolve("groups/space/photos/.pspace00006.tmp")), "fresh kept");
            eq(t.pp.removeStale(), 0, "second run");
            PendingPhotos none = new PendingPhotos(new UserLibrary(t.r.resolve("nothing").toFile(), t.clock, new Random(1)), t.clock, new Random(1));
            eq(none.removeStale(), 0, "no R yet");
        });
        run("pending: create() and deleteGroup() share the library's lock: a deleted group gets no photos folder again", () -> {
            // review 687edfd: UploadServer takes its own lock around create(), Bridge.deleteGroup only the
            // library's, so a group deleted between create()'s check and its mkdirs got groups/<gid>/photos back.
            PendingPhotosTest t = new PendingPhotosTest();
            Object r = underLibraryLock(t.lib, () -> t.pp.create("gholiday1"),
                    () -> eq(t.lib.deleteGroup("gholiday1"), UserLibrary.ok(), "the group is deleted meanwhile"), "create()");
            check(r instanceof java.io.IOException && "no such group".equals(((Exception) r).getMessage()), "create() refuses the deleted group: " + r);
            check(!Files.exists(t.r.resolve("groups/gholiday1")), "no orphan groups/gholiday1/photos");
            check(UserLibrary.validPid(t.pp.create("space")), "create() still works for a live group");
        });
        run("pending: start-up also sweeps groups/.<gid>.del-* and .<gid>.tmp", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            Path groups = t.r.resolve("groups");
            Files.createDirectories(groups.resolve(".gdeleted1.del-a1b2c3/photos/pabcdefghij"));
            Files.createDirectories(groups.resolve(".gfailed01.tmp"));
            eq(t.pp.removeStale(), 4, "the fixture's 2 (complete photo, .del- photo) and the two group leftovers");
            check(!Files.exists(groups.resolve(".gdeleted1.del-a1b2c3")) && !Files.exists(groups.resolve(".gfailed01.tmp")), "both gone");
        });
        run("pending: start-up clean-up holds the library's lock (it runs beside the page's bridge calls)", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            Object n = underLibraryLock(t.lib, () -> t.pp.removeStale(), () -> { }, "removeStale()");
            eq(n, 2, "and then does its work (the complete photo moved into place, the half-deleted dir removed)");
        });
        run("pending: /home/user-tmp/ resolves only an unfinished photo's wall.jpg under R", () -> {
            PendingPhotosTest t = new PendingPhotosTest();
            File f = t.pp.tmpWall("gholiday1/pjob0000001/wall.jpg");
            check(f != null && f.getPath().endsWith("photos/.pjob0000001.tmp/wall.jpg"), "wall: " + f);
            check(t.pp.tmpWall("space/pspace00006/wall.jpg") != null, "built-in group");
            for (String bad : new String[]{"gholiday1/pjob0000001/thumb.jpg", "gholiday1/pjob0000001/meta.json", "gholiday1/pdone000005/wall.jpg",
                    "gholiday1/.pjob0000001.tmp/wall.jpg", "gholiday1/../gholiday1/pjob0000001/wall.jpg", "gholiday1/pjob0000001/wall.jpg/",
                    "gholiday1/pjob0000001", "gholiday1/pnometa0003/wall.jpg/x", "gholiday1/pnothere000/wall.jpg", "/gholiday1/pjob0000001/wall.jpg",
                    "Gbadgroup/pjob0000001/wall.jpg", "", null}) {
                check(t.pp.tmpWall(bad) == null, "refused: " + bad);
            }
            Path outside = UploadTests.tmp("cf-outside");
            Files.write(outside.resolve("wall.jpg"), "secret".getBytes());
            Path link = t.r.resolve("groups/gholiday1/photos/.plink000009.tmp");
            Files.createSymbolicLink(link, outside);
            check(t.pp.tmpWall("gholiday1/plink000009/wall.jpg") == null, "a symlinked tmp dir out of R");
            check(!t.pp.materialJobs().contains("plink000009"), "a symlinked tmp dir is no job");
        });
    }
}
