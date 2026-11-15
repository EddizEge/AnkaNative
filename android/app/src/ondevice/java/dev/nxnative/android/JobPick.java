package dev.nxnative.android;

import java.io.File;
import java.io.IOException;

/**
 * What a picked dump's check (Ondevice.Panel.picked, checked) does with the pack folders and with a job.json it finds beside the dump. No Android classes: the JVM
 * test (android/app/src/jvmtest JobPickJvmTest, dm/tools/tests/test_android_prepare_again.py) runs it.
 * <p>
 * The rc1 phone check (2026-11-15): a game card's Prepare again went through Folders.packFor, which with several pack folders in the work folder picked another
 * one, found it ready and moved the card to it; and a job the user had forgotten (Manage › Forget) came back onto Home before the user said anything. The review
 * of work/rc1-fixes found a second way back for a forgotten job (the "another pack folder" case below) and that the folders were compared as written, so the
 * same folder written two ways (/sdcard/… and /storage/emulated/0/…) counted as another.
 */
final class JobPick {
    private JobPick() {}

    /** Which pack the check reads */
    enum Pack {
        /** the game card's own pack (its automatic update, its Prepare again): checked and kept, never another folder */
        CARD,
        /** the pack Folders.packFor finds for the dump (Add game, the Preparing card's Prepare): one that fits is adopted as it is */
        FOUND,
        /** none: an adb test's ondevice_pack is a folder for a new job, never checked */
        NONE
    }

    /** card: an automatic update of a card's pack; again: the user's Prepare again; override: a pack folder was given (the card's, or adb's ondevice_pack) */
    static Pack packToCheck(boolean card, boolean again, boolean override) {
        if (card || again && override) return Pack.CARD;
        return override ? Pack.NONE : Pack.FOUND;
    }

    /** What a pick does with the job.json files it finds in the dump's job folders (place) */
    enum Found {
        /** the pick's own job, of the same dump and version: asked "Resume?" (or started, for adb and a card's update); remembered (shown on Home) only when taken */
        TAKE,
        /** an unfinished job of another dump or version in the folder the pick's job would use: never written over; said so, and shown on Home only when the
         *  user asks (a forgotten job stays forgotten); a card's automatic update leaves it */
        OTHER_FOLDER,
        /** none of the pick's: a new job (or the pack that fits) in the folder place names */
        NEW
    }

    /** A job that is not finished: it may be resumed, so its job.json is never written over */
    static boolean open(String state) {
        return "running".equals(state) || "paused".equals(state) || "failed".equals(state) || "pending".equals(state);
    }

    // ---- Which job folder a pick's job lives in ----
    //
    // A job belongs to the pack folder it writes (job.json's "pack"). A dump's work folder has one default job folder, <work>/ondevice, which every job used
    // until rc2: so an unfinished job there for one pack folder (the owner's phone: a paused test job of 2026-11-08 writing <work>/ondevice/test-pack) stood in
    // the way of every other pack folder's job of that dump. The rc2 phone check (session-2026-11-14/rc2-phone-check.md A1, NIGHT.md 11-14): the game card's
    // automatic shader update (its pack <work>/android-pack needing the 584-shader delta) found that job, classed it OTHER_FOLDER and returned, at every app
    // start, for as long as the folder was there; and its Prepare again offered "Show the job", after which Home resumed the hour-long test job by itself.
    // Now a pick that names its pack (a card's update or Prepare again, adb's ondevice_pack) takes only a job of that pack folder, and when the default folder
    // holds an unfinished job of another pack folder, its own job lives in a folder of its pack's own, <work>/ondevice-<pack's name>-<digest>; the other job is
    // neither read further nor changed, and stays where it is (Add game on its dump, or the card of its own pack, finds it).

    /** The facts of a job.json a pick decides on (Job.read on the phone, a test's own in JobPickJvmTest) */
    static final class Seen {
        final File dump, pack;
        final long version;
        final String state;

        Seen(File dump, long version, File pack, String state) {
            this.dump = dump;
            this.version = version;
            this.pack = pack;
            this.state = state;
        }
    }

    /** Reads a job.json's facts; an IOException: damaged, or of another format (a new job replaces it, as before) */
    interface Reader {
        Seen read(File jobFile) throws IOException;
    }

    /** What a pick found in its job folders */
    static final class Place {
        /** TAKE: the pick's own unfinished job (file); OTHER_FOLDER: an unfinished job that is not the pick's and holds the folder its job would use (file);
         *  NEW: none, and file is where the new job writes its job.json */
        final Found found;
        final File file;
        /** NEW only: an unfinished job of another pack folder in the default folder, kept as it is (the new job went beside it), else null; for the log */
        final File kept;

        Place(Found found, File file, File kept) {
            this.found = found;
            this.file = file;
            this.kept = kept;
        }
    }

    static File jobFile(File folder) { return new File(folder, "job.json"); }

    /** A dump's default job folder: <work>/ondevice */
    static File defaultFolder(File work) { return new File(work, "ondevice"); }

    /**
     * The job folder of a pack of its own, for when the default one holds another pack folder's unfinished job: <work>/ondevice-<the pack folder's name>-<the
     * first 8 hex digits of the SHA-256 of its canonical path>. The same pack (also written another way: /sdcard/…) always gives the same folder, so the pack's
     * job is found there again; two pack folders of the same name (packs/<id> of two apps) give two
     */
    static File packFolder(File work, File pack) {
        String name = pack.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > 40) name = name.substring(0, 40);
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(resolved(pack).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int k = 0; k < 4; k++) hex.append(String.format("%02x", d[k] & 0xff));
            return new File(work, "ondevice-" + name + "-" + hex);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every Java has SHA-256
        }
    }

    /** The job.json files a pick for this pack looks at, in order: the default folder's, then (with a pack) the pack's own folder's */
    static File[] candidates(File work, File pack) {
        File def = jobFile(defaultFolder(work));
        return pack == null ? new File[] {def} : new File[] {def, jobFile(packFolder(work, pack))};
    }

    /**
     * Which job a pick of dump (at version) finds, and where its new job goes. pack: the pack folder the pick's job writes (null: not known). strict: the pick
     * names that pack (a card's automatic update or Prepare again, adb's ondevice_pack): only a job writing that pack folder is its own. Not strict (Add game,
     * the Preparing card's Prepare): the default folder's job is the dump's whatever pack it writes, as before.
     * <ul>
     * <li>TAKE: the pick's own job of the same dump and version, unfinished (the default folder first);</li>
     * <li>OTHER_FOLDER: the pick's own folder holds an unfinished job of another dump or version (never written over: said, shown only when asked);</li>
     * <li>NEW: a folder for the new job: the one with the pick's own finished job, else the default folder when it holds no unfinished job, else the pack's own
     *     folder (the default one holds another pack folder's unfinished job, which is kept as it is).</li>
     * </ul>
     */
    static Place place(File work, File dump, long version, File pack, boolean strict, Reader read) {
        File[] files = candidates(work, pack);
        Seen[] seen = new Seen[files.length];
        for (int k = 0; k < files.length; k++) {
            if (!files[k].isFile()) continue;
            try {
                seen[k] = read.read(files[k]);
            } catch (IOException e) {
                seen[k] = null;   // damaged, or of another format: replaced by a new job, as before
            }
        }
        boolean[] own = new boolean[files.length];
        for (int k = 0; k < files.length; k++) own[k] = seen[k] != null && (!strict && k == 0 || pack != null && samePlace(seen[k].pack, pack));
        for (int k = 0; k < files.length; k++)
            if (own[k] && open(seen[k].state) && samePlace(seen[k].dump, dump) && seen[k].version == version) return new Place(Found.TAKE, files[k], null);
        for (int k = 0; k < files.length; k++) if (own[k] && open(seen[k].state)) return new Place(Found.OTHER_FOLDER, files[k], null);
        for (int k = 0; k < files.length; k++) if (own[k]) return new Place(Found.NEW, files[k], null);
        File kept = null;
        for (int k = 0; k < files.length; k++) {
            if (seen[k] == null || !open(seen[k].state)) return new Place(Found.NEW, files[k], kept);
            if (k == 0) kept = files[k];
        }
        // the pack's own folder holds an unfinished job of another pack (a folder renamed by hand): never written over
        return new Place(Found.OTHER_FOLDER, files[files.length - 1], null);
    }

    /**
     * The mark of a job the user chose only to see (the other-folder dialog's "Show the job", never Resume): its job.json as it was then. While job.json is
     * unchanged (no service has run it since), the app does not start it by itself (Home's open, a game's end, the job watch), as a job that died while
     * working would be: the job may be paused for the battery, which counts as working, and the user did not ask for it to run
     */
    static String shownKey(File jobFile, long updated) { return jobFile.getPath() + "@" + updated; }

    /**
     * Whether two paths name the same folder or file: compared by their canonical paths (links resolved: /sdcard is a link to the shared storage that is also
     * written /storage/emulated/0). When one cannot be resolved (an I/O error reading a link), its absolute path is compared instead: the folder may be gone,
     * and a path written the same way is still the same
     */
    static boolean samePlace(File a, File b) {
        if (a == null || b == null) return a == b;
        return resolved(a).equals(resolved(b));
    }

    static String resolved(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }
}
