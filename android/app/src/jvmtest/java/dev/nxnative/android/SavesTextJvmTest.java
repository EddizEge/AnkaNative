package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The saves area's texts (strings_saves.xml) on the JVM: the English that SaveStore, SaveMigrate and SaveUi show and log is the English they built by concatenation
 * before the texts moved into resources (docs/i18n.md: byte-identical), except for the sentences rephrased on purpose (a possessive after the game's name; "1 file"
 * where "1 files" was), which are checked in their new form. SaveUi needs Android, so its texts are rendered here through Tr's XML backend with the arguments SaveUi
 * passes. Run by dm/tools/tests/test_android_saves_text.py with {@code <scratch folder>}: prints "ok <n>" and exits 0, or the first failure and exits 1.
 */
public final class SavesTextJvmTest {
    private static int n;

    static void same(String got, String want, String what) {
        if (!want.equals(got)) throw new AssertionError(what + ":\n  got  " + got + "\n  want " + want);
        n++;
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        File t = new File(args[0]);
        store(t);
        migrate(t);
        ui();
        System.out.println("ok " + n);
    }

    /** SaveStore's refusals: getMessage (logs, tests) and getLocalizedMessage (the screen) are the old English */
    static void store(File t) throws Exception {
        File s = new File(t, "saves/040074A01BF12000");
        write(new File(s, "slot0.bin"), "a");
        try (SaveStore.Lock l = SaveStore.lock(s)) {
            try {
                SaveStore.backup(s, new File(t, "bk"), "x");
                check(false, "a backup while the lock is held is refused");
            } catch (IOException e) {
                String want = "the save " + s + " is in use: its game is running (stop it first) or a backup or restore of it is working";
                same(e.getMessage(), want, "in use, English");
                same(e.getLocalizedMessage(), want, "in use, on screen");
                check(e instanceof LocalizedIOException, "in use is a LocalizedIOException");
            }
        }
        // the first difference, alone and inside a sentence
        SaveStore.Manifest a = SaveStore.scan(s), b = new SaveStore.Manifest();
        same(a.diff(b), "slot0.bin is missing", "diff: missing");
        same(b.diff(a), "slot0.bin is extra", "diff: extra");
        File s2 = new File(t, "other");
        write(new File(s2, "slot0.bin"), "b");
        same(a.diff(SaveStore.scan(s2)), "slot0.bin differs", "diff: differs");
        same(a.diffMsg(SaveStore.scan(s2)).text(), "slot0.bin differs", "diffMsg on screen");
        check(a.diff(SaveStore.scan(s)) == null && a.diffMsg(SaveStore.scan(s)) == null, "no difference: null");
        // a damaged backup: the sentence with the difference inside it
        File bk = new File(t, "bk");
        SaveStore.Backup made = SaveStore.backup(s, bk, "20261103-120000");
        write(new File(made.dir, "main/slot0.bin"), "damaged");
        try {
            SaveStore.restore(made.dir, s, bk, "20261103-130000");
            check(false, "a damaged backup is refused");
        } catch (IOException e) {
            same(e.getMessage(), "the backup " + made.dir + " does not match its manifest (main/slot0.bin differs): it is damaged, so it is not restored", "damaged");
        }
        // refuseSource: English, the Msg for the screen, and restore's refusal (the same sentence)
        File inside = new File(s, "sub");
        inside.mkdirs();
        String want = "the folder to restore from (" + inside + ") and the save folder (" + s + ") overlap";
        same(SaveStore.refuseSource(inside, s), want, "refuseSource: overlap");
        same(SaveStore.refuseSourceMsg(inside, s).text(), want, "refuseSourceMsg: overlap");
        try {
            SaveStore.restore(inside, s, bk, "x");
            check(false, "restore from inside the save is refused");
        } catch (IOException e) {
            same(e.getMessage(), want, "restore's refusal");
        }
        File work = new File(s.getPath() + ".new");
        work.mkdirs();
        same(SaveStore.refuseSource(work, s), work + " is a working folder of the save (.new), not a save to restore", "refuseSource: working folder");
        check(SaveStore.refuseSource(s2, s) == null && SaveStore.refuseSourceMsg(s2, s) == null, "another folder may be restored from");
        // not a folder; a manifest that is not one
        File none = new File(t, "none");
        try { SaveStore.scan(none); check(false, "scan of a missing folder"); } catch (IOException e) { same(e.getMessage(), none + " is not a folder", "not a folder"); }
        File bad = new File(t, "bad.manifest.tsv");
        write(bad, "x\n");
        try { SaveStore.readManifest(bad); check(false, "a bad manifest"); } catch (IOException e) { same(e.getMessage(), bad + " is not a save backup manifest", "not a manifest"); }
        // a marker that names a foreign folder
        File m = SaveStore.marker(s, 1);
        File foreign = new File(t, "foreign");
        foreign.mkdirs();
        write(m, foreign.getAbsolutePath());
        try {
            SaveStore.recover(s);
            check(false, "a foreign marker is refused");
        } catch (IOException e) {
            same(e.getMessage(), m + " names " + foreign.getAbsolutePath() + ", not " + s.getName() + ".new / .re / .restore: the save is left as it is; move that marker away to use the save",
                    "foreign marker");
        }
        m.delete();
        // when: the date, and the automatic backup's note
        same(SaveStore.when("20261103-140509"), "2026-11-03 14:05:09", "when");
        same(SaveStore.when("20261103-140509-before-restore"), "2026-11-03 14:05:09 (automatic, before a restore)", "when: before a restore");
        same(SaveStore.when("odd"), "odd", "when: another name");
        // the remaining SaveStore sentences, rendered with sample arguments against their old concatenation
        File x = new File("/x/a"), y = new File("/x/b");
        same(Tr.english("saves_cannot_create", x), "cannot create " + x, "cannot create");
        same(Tr.english("saves_cannot_list", x), "cannot list " + x, "cannot list");
        same(Tr.english("saves_link_in_save", "d/f"), "d/f is a link: a save folder holds files and folders only", "link in save");
        same(Tr.english("saves_not_file_or_folder", "d/f"), "d/f is neither a file nor a folder", "neither");
        same(Tr.english("saves_cannot_write", x), "cannot write " + x, "cannot write");
        same(Tr.english("saves_no_save_folder", x), "there is no save folder at " + x, "no save folder");
        same(Tr.english("saves_backups_overlap", x, y), "the backups folder " + x + " and the save folder " + y + " overlap", "backups overlap");
        Msg d = new Msg("saves_diff_missing", "f");
        same(Tr.english("saves_copy_mismatch", d), "the copy does not match the save (f is missing)", "copy mismatch");
        same(Tr.english("saves_changed_running", d), "the save changed while it was copied (f is missing): is the game running?", "changed, running");
        same(Tr.english("saves_cannot_name_backup", x), "cannot name the backup " + x, "cannot name");
        same(Tr.english("saves_no_device_save", x, y), x + " has no device save, but " + y + " has one: restoring only the main save would pair it with another device save, so it is not "
                + "restored. A backup made by this app holds both", "no device save");
        same(Tr.english("saves_cannot_clear", x), "cannot clear " + x, "cannot clear");
        same(Tr.english("saves_restore_copy_mismatch", d), "the copy beside the save does not match the backup (f is missing): the save was not changed", "restore copy mismatch");
        same(Tr.english("saves_cannot_remove", x), "cannot remove " + x, "cannot remove");
        same(Tr.english("saves_restore_mismatch_after", d), "the save does not match the backup after the restore (f is missing)", "after restore");
        same(Tr.english("saves_set_foreign", x, "/a /b"), x + " names /a /b, not this save's parts: the save is left as it is; move that file away to use the save", "set foreign");
        same(Tr.english("saves_cannot_move_back", x, y), "cannot move " + x + " back to " + y, "move back");
        same(Tr.english("saves_marker_gone", x, "/a"), x + " names /a, which is gone: the save is left as it is; move that marker away to use the save", "marker gone");
        same(Tr.english("saves_cannot_move", x, y), "cannot move " + x + " to " + y, "cannot move");
        same(Tr.english("saves_is_link", x), x + " is a link", "is a link");
    }

    /** SaveMigrate's reasons: Result.message English, messageText the screen's */
    static void migrate(File t) throws Exception {
        File old = new File(t, "old/saves/040074A01BF12000"), app = new File(t, "app/saves/040074A01BF12000");
        write(new File(old, "slot0.bin"), "a");
        write(SaveStore.marker(old, 1), "");
        SaveMigrate.Result r = SaveMigrate.migrate(old, app);
        String want = "the save in " + old + " has an unfinished commit or restore: it is finished at this start, and copied at the next";
        check(r.state == SaveMigrate.State.FAILED, "an old save with a journal is not copied: " + r);
        same(r.message, want, "old pending: message");
        same(r.messageText(), want, "old pending: messageText");
        same(r.toString(), "FAILED: " + want, "old pending: toString");
        SaveMigrate.Result e = SaveMigrate.Result.failed(new LocalizedIOException("saves_cannot_create", app));
        same(e.message, "cannot create " + app, "from a LocalizedIOException: message");
        check(e.msg != null && e.messageText().equals(e.message), "from a LocalizedIOException: its Msg kept");
        SaveMigrate.Result p = SaveMigrate.Result.failed(new IOException("No space left on device"));
        check(p.msg == null && p.message.equals("No space left on device") && p.messageText().equals(p.message), "from another exception: its message");
        File x = new File("/x/a"), y = new File("/x/b"), z = new File("/x/c");
        same(Tr.english("saves_migrate_device_foreign", x, y, z, new Msg("saves_migrate_old_has_no_device")),
                x + " holds a device save but " + y + " has no save, and it is not a copy of " + z + " (the old save has none)", "device foreign");
        same(Tr.english("saves_changed", new Msg("saves_diff_extra", "f")), "the save changed while it was copied (f is extra)", "changed");
        same(Tr.english("saves_migrate_cannot_clear_empty", x), "cannot clear the empty " + x, "clear empty");
        same(Tr.english("saves_migrate_mismatch_after_move", new Msg("saves_diff_differs", "f")), "the app's copy does not match the save after the move (f differs)", "after move");
    }

    /** SaveUi's sentences with the arguments SaveUi passes, against its old concatenation (the rephrased ones in their new form) */
    static void ui() {
        String g = "Donkey Kong Bananza", size = "1.2 MB";
        File save = new File("/s/saves/040074A01BF12000"), to = new File("/s/save-backups/040074A01BF12000"), dir = new File(to, "20261103-140509");
        same(Tr.english("saves_busy", g), "A save backup or restore of " + g + " is still working.", "busy");
        for (String[] w : new String[][] {{"saves_running_backup", "back up"}, {"saves_running_restore", "restore"}, {"saves_running_import", "import"}})
            same(Tr.english(w[0], g, new Msg("game_panel_stop") /* SidePanel.stopLabel(), an Android class */), g + " is running (it holds its save): stop it first (its in-game panel › Stop the game), then " + w[1] + ". The game may be writing its save.", w[0]);
        same(Tr.english("saves_backup_nothing", g, save), g + " has no save yet in " + save + ": nothing to back up.", "nothing to back up");
        same(Tr.english("saves_backup_wait", g), "Backing up the save of " + g + "…", "backup wait (rephrased)");
        same(Tr.english("saves_backup_failed", "why", save), "No backup was made: why\n\nThe save in " + save + " was only read, never changed.", "backup failed");
        same(Tr.english("saves_backup_done_title"), "Saves backed up", "done title");
        for (boolean device : new boolean[] {false, true}) {
            String key = device ? "saves_backup_done_body_device" : "saves_backup_done_body";
            String tail = (device ? ", with the device save" : "") + ", checked file by file (SHA-256) against the save.\n\n" + dir
                    + "\n\nThe save itself was not changed. Backups are never deleted by the app.";
            same(Tr.englishP(key, 3, 3, size, dir), 3 + " files, " + size + tail, key);
            same(Tr.englishP(key, 1, 1, size, dir), "1 file, " + size + tail, key + ", one (was \"1 files\")");
        }
        same(Tr.english("saves_restore_none", g, to, HomeLabels.backUpSaves()), "No backups of " + g + " yet in " + to + ". Back up saves makes one.", "no backups");
        String when = "2026-11-03 14:05:09";
        same(Tr.englishP("saves_restore_item", 2, when, 2, size), when + "\n" + 2 + " files, " + size, "restore item");
        same(Tr.englishP("saves_restore_item_device", 2, when, 2, size), when + "\n" + 2 + " files, " + size + " (with the device save)", "restore item, device");
        same(Tr.englishP("saves_restore_item", 1, when, 1, size), when + "\n1 file, " + size, "restore item, one (was \"1 files\")");
        same(Tr.english("saves_restore_item_from", "L", "saves/040074A01BF12000"), "L" + "\nfrom saves/040074A01BF12000", "restore item from");
        same(Tr.english("saves_restore_list_title", g), "Restore the save of " + g + " from", "list title (rephrased)");
        same(Tr.english("saves_import_pick", "0400486024DEC000"), "Pick the save folder to import (the folder that holds the save's files, like saves/0400486024DEC000)", "import pick");
        same(Tr.english("saves_import_source", dir), "the folder " + dir, "import source");
        same(Tr.english("saves_not_restored_refused", "why"), "Not restored: why.", "refused");
        same(Tr.english("saves_restore_confirm_title", g), "Restore the save of " + g + "?", "confirm title (rephrased)");
        File before = new File(to, "20261103-150000-before-restore");
        same(Tr.english("saves_restore_confirm_body", save, when, before, HomeLabels.restoreSaves()), "Replaced: the save in " + save + ", with " + when + ".\n\nFirst, the current save is backed up to "
                + before + " (checked), so this can be undone with Restore saves…. Nothing is deleted from the backups.", "confirm body");
        same(Tr.english("saves_restore_button"), "Restore", "restore button");
        same(Tr.english("saves_restore_wait", g), "Restoring the save of " + g + "…", "restore wait (rephrased)");
        same(Tr.english("saves_restore_failed", "why", save), "Not restored: why\n\nThe save in " + save + " is as it was.", "restore failed");
        same(Tr.english("saves_restore_failed_pending", "why", save), "Not restored: why\n\nThe save in " + save + " is as it was, except for an unfinished swap that is finished at the next "
                + "backup, restore or game start.", "restore failed, pending");
        for (int k = 0; k < 4; k++) {
            boolean device = (k & 1) != 0, old = (k & 2) != 0;
            String key = "saves_restored" + (device ? "_device" : "") + (old ? "_before" : "");
            String want = "Restored " + 5 + " files (" + size + (device ? ", with the device save" : "") + ")" + (old ? "; the old save is in " + before : "");
            same(old ? Tr.englishP(key, 5, 5, size, before) : Tr.englishP(key, 5, 5, size), want, key);
            String one = "Restored 1 file (" + size + (device ? ", with the device save" : "") + ")" + (old ? "; the old save is in " + before : "");
            same(old ? Tr.englishP(key, 1, 1, size, before) : Tr.englishP(key, 1, 1, size), one, key + ", one (was \"1 files\")");
        }
    }
}
