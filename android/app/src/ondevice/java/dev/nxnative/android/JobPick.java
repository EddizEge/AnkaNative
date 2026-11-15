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

    /** What to do with an unfinished job.json found beside the dump */
    enum Found {
        /** the same dump, version and pack folder: asked "Resume?" (or started, for adb); remembered (shown on Home) only when the user takes it */
        TAKE,
        /** unfinished but for another pack folder: never written over; said so, and shown on Home only when the user asks (a forgotten job stays forgotten) */
        OTHER_FOLDER,
        /** done, unreadable or of no state: a new job (or the pack that fits) replaces it */
        NEW
    }

    /** same: samePlace of its dump, its version and (with a pack given) its pack folder; state: job.json's */
    static Found existing(boolean same, String state) {
        boolean open = "running".equals(state) || "paused".equals(state) || "failed".equals(state) || "pending".equals(state);
        if (same && open) return Found.TAKE;
        if (!"done".equals(state) && state != null && !state.isEmpty()) return Found.OTHER_FOLDER;
        return Found.NEW;
    }

    /**
     * Whether two paths name the same folder or file: compared by their canonical paths (links resolved: /sdcard is a link to the shared storage that is also
     * written /storage/emulated/0). When one cannot be resolved (an I/O error reading a link), its absolute path is compared instead: the folder may be gone,
     * and a path written the same way is still the same
     */
    static boolean samePlace(File a, File b) {
        if (a == null || b == null) return a == b;
        return resolved(a).equals(resolved(b));
    }

    private static String resolved(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }
}
