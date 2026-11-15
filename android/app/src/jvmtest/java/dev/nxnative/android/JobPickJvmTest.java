package dev.nxnative.android;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JobPick on the JVM: which pack a picked dump's check reads, what it does with an unfinished job.json beside the dump, and how folders are compared. The cases
 * of the rc1 phone check (a card's Prepare again moved to another pack folder; a forgotten job came back) and of the review of work/rc1-fixes (a forgotten job of
 * another pack folder came back without asking; /sdcard and /storage/emulated/0). Prints "ok" lines, exits 1 on the first failed check. Run by
 * dm/tools/tests/test_android_prepare_again.py with a scratch folder as its argument.
 */
public final class JobPickJvmTest {
    private static void check(boolean ok, String what) {
        if (!ok) { System.out.println("FAIL " + what); System.exit(1); }
        System.out.println("ok " + what);
    }

    public static void main(String[] a) throws Exception {
        check(JobPick.packToCheck(false, true, true) == JobPick.Pack.CARD, "a card's Prepare again checks the card's own pack, never another folder");
        check(JobPick.packToCheck(true, false, true) == JobPick.Pack.CARD, "a card's automatic update checks the card's pack");
        check(JobPick.packToCheck(false, false, false) == JobPick.Pack.FOUND, "Add game: the pack Folders finds (one that fits is adopted)");
        check(JobPick.packToCheck(false, true, false) == JobPick.Pack.FOUND, "Prepare again without a card pack: the pack Folders finds");
        check(JobPick.packToCheck(false, false, true) == JobPick.Pack.NONE, "adb's ondevice_pack: a folder for a new job, never checked");

        for (String s : new String[] {"running", "paused", "failed", "pending"})
            check(JobPick.existing(true, s) == JobPick.Found.TAKE, "the same dump and pack, " + s + ": Resume is asked (remembered only on take)");
        for (String s : new String[] {"running", "paused", "failed", "pending"})
            check(JobPick.existing(false, s) == JobPick.Found.OTHER_FOLDER, "another pack folder, " + s + ": said so, shown only when asked");
        check(JobPick.existing(true, "done") == JobPick.Found.NEW && JobPick.existing(false, "done") == JobPick.Found.NEW, "a done job: a new job or the pack that fits");
        check(JobPick.existing(false, "") == JobPick.Found.NEW, "no state: a new job");

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
        System.out.println("all ok");
    }
}
