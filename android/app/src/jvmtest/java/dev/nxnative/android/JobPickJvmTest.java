package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JobPick on the JVM: which pack a picked dump's check reads, which job.json it takes and where its new job goes, and how folders are compared. The cases of
 * the rc1 phone check (a card's Prepare again moved to another pack folder; a forgotten job came back), of the review of work/rc1-fixes (a forgotten job of
 * another pack folder came back without asking; /sdcard and /storage/emulated/0), and of the rc2 phone check (an unfinished job of another pack folder in the
 * dump's job folder blocked the card's automatic shader update for good). Prints "ok" lines, exits 1 on the first failed check. Run by
 * dm/tools/tests/test_android_prepare_again.py with a scratch folder as its argument.
 */
public final class JobPickJvmTest {
    private static void check(boolean ok, String what) {
        if (!ok) { System.out.println("FAIL " + what); System.exit(1); }
        System.out.println("ok " + what);
    }

    private static final long V = 131072;   // the owner's dump: Ocarina of Time 2.0.0

    /** A job.json as PackService writes it (Job.save; the fields of the owner's 11-08 job, session-2026-11-14 memu-scratch/pad/owner-ondevice-job.json) */
    private static void job(File f, File dump, long version, File pack, String state, String reason, long updated) throws IOException {
        Files.createDirectories(f.getParentFile().toPath());
        String steps = "{\"identify\": {\"state\": \"done\"}, \"rewrite\": {\"state\": \"done\"}, \"split\": {\"state\": \"done\"}, "
                + "\"shaders\": {\"state\": \"" + ("done".equals(state) ? "done" : "running") + "\", \"done\": 3776, \"total\": 42346, \"stage\": \"vertex\"}, "
                + "\"pack\": {\"state\": \"" + ("done".equals(state) ? "done" : "pending") + "\"}}";
        String json = "{\n \"format\": 2,\n \"title_id\": \"0400486024DEC000\",\n \"name\": \"The Legend of Zelda: Ocarina of Time\",\n \"version\": " + version
                + ",\n \"dump\": \"" + dump.getPath() + "\",\n \"work\": \"" + f.getParentFile().getParent() + "\",\n \"state\": \"" + state + "\",\n \"reason\": \""
                + reason + "\",\n \"error\": \"\",\n \"step\": \"shaders\",\n \"created\": 1794160811868,\n \"pack\": \"" + pack.getPath()
                + "\",\n \"own_pack\": true,\n \"steps\": " + steps + ",\n \"updated\": " + updated + "\n}";
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\\s*(\"([^\"]*)\"|([0-9]+))").matcher(json);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(3)) : null;
    }

    /** Job.read's facts, from the file's text (org.json is Android's): a file without a format-2 header is unreadable, as Job.read says */
    private static final JobPick.Reader READ = f -> {
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        if (!"2".equals(field(s, "format"))) throw new IOException(f + ": job format " + field(s, "format") + ", this app writes 2");
        return new JobPick.Seen(new File(field(s, "dump")), Long.parseLong(field(s, "version")), new File(field(s, "pack")), field(s, "state"));
    };

    public static void main(String[] a) throws Exception {
        check(JobPick.packToCheck(false, true, true) == JobPick.Pack.CARD, "a card's Prepare again checks the card's own pack, never another folder");
        check(JobPick.packToCheck(true, false, true) == JobPick.Pack.CARD, "a card's automatic update checks the card's pack");
        check(JobPick.packToCheck(false, false, false) == JobPick.Pack.FOUND, "Add game: the pack Folders finds (one that fits is adopted)");
        check(JobPick.packToCheck(false, true, false) == JobPick.Pack.FOUND, "Prepare again without a card pack: the pack Folders finds");
        check(JobPick.packToCheck(false, false, true) == JobPick.Pack.NONE, "adb's ondevice_pack: a folder for a new job, never checked");
        for (String s : new String[] {"running", "paused", "failed", "pending"}) check(JobPick.open(s), s + " is unfinished");
        for (String s : new String[] {"done", "", null}) check(!JobPick.open(s), s + " is not");

        Path d = Files.createDirectories(Path.of(a[0], "jobpick"));
        File storage = Files.createDirectories(d.resolve("storage/emulated/0/work/pack-a")).toFile();
        Path link = d.resolve("sdcard");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, d.resolve("storage/emulated/0"));
        File viaLink = link.resolve("work/pack-a").toFile();
        check(!viaLink.getPath().equals(storage.getPath()), "the two spellings differ as written");
        check(JobPick.samePlace(viaLink, storage), "/sdcard/… and /storage/emulated/0/… are the same folder");
        check(JobPick.samePlace(new File(storage, "../pack-a"), storage), "a path with .. is the same folder");
        check(!JobPick.samePlace(Files.createDirectories(d.resolve("storage/emulated/0/work/pack-b")).toFile(), storage), "another folder is another");
        check(JobPick.samePlace(new File(d.toFile(), "gone/x"), new File(d.toFile(), "gone/x")), "a folder that is gone is still the same as itself");
        check(!JobPick.samePlace(null, storage) && JobPick.samePlace(null, null), "null");

        // ---- The rc2 phone check: the owner's phone ----
        // <games>/<dump>.nup, <games>/0400486024DEC000-work/android-pack (the card's pack, pack A: an older shader library, so it needs the 584-shader delta
        // update), and <work>/ondevice/job.json: the unfinished test job of 2026-11-08 (paused/battery in its shaders step) whose pack is <work>/ondevice/test-pack
        // (pack B). Its paths are written /sdcard/…, the card's /storage/emulated/0/…, as on the phone
        Path games = Files.createDirectories(d.resolve("storage/emulated/0/Games/Nintendo Switch 2"));
        File dump = games.resolve("The Legend of Zelda Ocarina of Time [0400486024DEC000][v131072].nup").toFile();
        Files.write(dump.toPath(), new byte[] {'N', 'U', 'P'});
        File work = Files.createDirectories(games.resolve("0400486024DEC000-work")).toFile();
        File sdWork = link.resolve("Games/Nintendo Switch 2/0400486024DEC000-work").toFile(), sdDump = link.resolve("Games/Nintendo Switch 2/" + dump.getName()).toFile();
        File packA = new File(work, "android-pack");
        Files.createDirectories(packA.toPath().resolve("shaders/v-0dd4341ae6ce"));
        Files.write(packA.toPath().resolve("pack.tsv"), "title_id\t0400486024DEC000\n".getBytes(StandardCharsets.UTF_8));
        Files.write(packA.toPath().resolve("shaders/v-0dd4341ae6ce/inputs.tsv"), "inputs\t4610dec2c950\n".getBytes(StandardCharsets.UTF_8));   // older inputs: the delta update
        File packB = new File(sdWork, "ondevice/test-pack");
        Files.createDirectories(packB.toPath());
        File foreign = new File(work, "ondevice/job.json");
        job(new File(sdWork, "ondevice/job.json"), sdDump, V, packB, "paused", "battery", 1794162964322L);
        byte[] before = Files.readAllBytes(foreign.toPath());
        long beforeTime = foreign.lastModified();
        File own = JobPick.packFolder(work, packA);

        JobPick.Place p = JobPick.place(work, dump, V, packA, true, READ);
        check(p.found == JobPick.Found.NEW, "the card's update of pack A is not blocked by the unfinished job of pack B (rc2: OTHER_FOLDER, no update): " + p.found);
        check(p.file.equals(JobPick.jobFile(own)) && !JobPick.samePlace(p.file, foreign), "its job goes into pack A's own job folder " + own.getName() + ", beside the other job");
        check(own.getParentFile().equals(work) && own.getName().startsWith("ondevice-android-pack-"), "that folder is in the dump's work folder, named after pack A");
        check(JobPick.samePlace(p.kept, foreign), "the other job is named as kept (for the log)");
        check(Arrays.equals(before, Files.readAllBytes(foreign.toPath())) && foreign.lastModified() == beforeTime, "the other job's job.json is unchanged");
        check(JobPick.place(work, dump, V, viaLink(link, d, packA), true, READ).file.equals(p.file), "pack A written /sdcard/… finds the same folder");

        JobPick.Place again = JobPick.place(work, dump, V, packA, true, READ);   // Prepare again of the card names its pack the same way
        check(again.found == JobPick.Found.NEW && again.file.equals(p.file), "the card's Prepare again: its own job too, never the dialog for the other job");

        // the card's update writes its job (Job.adopt: shaders of pack A) and is paused, killed or forgotten: the card finds that job again, never the other
        job(p.file, dump, V, packA, "paused", "battery", 1794170000000L);
        JobPick.Place back = JobPick.place(work, dump, V, packA, true, READ);
        check(back.found == JobPick.Found.TAKE && back.file.equals(p.file), "the card finds its own paused update in its own folder: " + back.found + " " + back.file);
        check(Arrays.equals(before, Files.readAllBytes(foreign.toPath())), "the other job's job.json is still unchanged");

        // pack B's own card (or adb's ondevice_pack test-pack) still takes pack B's job: it stays reachable where it belongs
        JobPick.Place b = JobPick.place(work, dump, V, new File(work, "ondevice/test-pack"), true, READ);
        check(b.found == JobPick.Found.TAKE && JobPick.samePlace(b.file, foreign), "a pick naming pack B takes the other job, in the default folder");
        // Add game on the dump (no pack named): the default folder's job of this dump, as before
        JobPick.Place add = JobPick.place(work, dump, V, new File(d.toFile(), "data/files/packs/0400486024DEC000"), false, READ);
        check(add.found == JobPick.Found.TAKE && JobPick.samePlace(add.file, foreign), "Add game finds the default folder's job of the dump and asks Resume");

        // the card's update ends: done, then a later update reuses its folder; once the other job's folder is gone, the default folder is free again
        job(p.file, dump, V, packA, "done", "", 1794171000000L);
        JobPick.Place done = JobPick.place(work, dump, V, packA, true, READ);
        check(done.found == JobPick.Found.NEW && done.file.equals(p.file), "a later update of pack A writes over its own done job, in its own folder");
        Files.move(foreign.getParentFile().toPath(), work.toPath().resolve("ondevice.old-1108"));
        JobPick.Place free = JobPick.place(work, dump, V, packA, true, READ);
        check(free.found == JobPick.Found.NEW && free.file.equals(p.file) && free.kept == null, "with the other job moved aside, pack A's own job folder is still its job's");
        Files.delete(p.file.toPath());
        JobPick.Place none = JobPick.place(work, dump, V, packA, true, READ);
        check(none.found == JobPick.Found.NEW && none.file.equals(JobPick.jobFile(JobPick.defaultFolder(work))) && none.kept == null,
                "no job anywhere: the default folder, as before");

        // ---- The default folder's other cases, as before ----
        File def = JobPick.jobFile(JobPick.defaultFolder(work));
        job(def, dump, V, new File(work, "ondevice/test-pack"), "done", "", 1L);
        check(JobPick.place(work, dump, V, packA, true, READ).file.equals(def), "a done job of another pack folder in the default folder is replaced, as before");
        job(def, dump, 0, packA, "paused", "user", 2L);
        JobPick.Place ver = JobPick.place(work, dump, V, packA, true, READ);
        check(ver.found == JobPick.Found.OTHER_FOLDER && ver.file.equals(def), "an unfinished job of pack A for another version: never written over, said (OTHER_FOLDER)");
        File otherDump = games.resolve("Ocarina [v0].nup").toFile();
        job(def, otherDump, V, packA, "running", "", 3L);
        check(JobPick.place(work, dump, V, packA, false, READ).found == JobPick.Found.OTHER_FOLDER, "Add game: the default folder's job of another dump, said (OTHER_FOLDER)");
        job(def, dump, V, packA, "failed", "", 4L);
        check(JobPick.place(work, dump, V, packA, true, READ).found == JobPick.Found.TAKE, "pack A's own failed job in the default folder: Retry is asked (TAKE)");
        Files.write(def.toPath(), "{\"format\": 1}".getBytes(StandardCharsets.UTF_8));
        JobPick.Place dmg = JobPick.place(work, dump, V, packA, true, READ);
        check(dmg.found == JobPick.Found.NEW && dmg.file.equals(def), "a job.json of another format: replaced by a new job, as before");

        // two pack folders of the same name are two job folders; the name is kept to what a folder name may hold
        File packs1 = new File(d.toFile(), "one/packs/0400486024DEC000"), packs2 = new File(d.toFile(), "two/packs/0400486024DEC000");
        check(!JobPick.packFolder(work, packs1).equals(JobPick.packFolder(work, packs2)), "packs/<id> of two apps: two job folders");
        check(JobPick.packFolder(work, new File(d.toFile(), "a b:c")).getName().matches("ondevice-a_b_c-[0-9a-f]{8}"), "a pack name with spaces is made safe");

        check(!JobPick.shownKey(foreign, 1).equals(JobPick.shownKey(foreign, 2)), "a job shown only is shown only while its job.json is unchanged");
        System.out.println("all ok");
    }

    private static File viaLink(Path link, Path d, File f) {
        return link.resolve(d.resolve("storage/emulated/0").relativize(f.toPath())).toFile();
    }
}
