package dev.nxnative.android;

import java.io.File;
import java.util.Locale;

/**
 * The prepare area's English (strings_prepare.xml: Ondevice, Job, PackService, DumpCheck) on the JVM, driven by dm/tools/tests/test_android_prepare_texts.py.
 * Those classes use Android and do not compile here, so each text is rendered from its key through Tr's XML backend (the English the app shows, aapt2's
 * unescaping) and compared with the English the Java built before the texts moved: the same sentence, written the old way (concatenation, String.format).
 * Every key of the area is rendered at least once, so a key the file lacks or a format that does not fit its arguments fails too. Prints "all ok".
 */
public final class PrepareTextsJvmTest {
    private static int failed;

    private static void check(String key, String got, String want) {
        if (!got.equals(want)) {
            failed++;
            System.out.println("FAIL " + key + ":\n  got  [" + got + "]\n  want [" + want + "]");
        }
    }

    /** The key's English with its arguments against the old English */
    private static void eq(String want, String key, Object... args) { check(key, new Msg(key, args).english(), want); }

    public static void main(String[] a) {
        File dump = new File("/storage/emulated/0/Switch/OoT"), work = new File("/storage/emulated/0/Switch/0400486024DEC000-work");
        File pack = new File("/data/user/0/dev.nxnative.android/files/packs/0400486024DEC000"), save = new File("/storage/emulated/0/AnkaNative/saves/0400486024DEC000");
        File dir = new File(work, "ondevice");
        String name = "The Legend of Zelda: Ocarina of Time", minutes = "about 20-35 minutes";
        long version = 65536;

        // Ondevice
        eq("This version of Anka Native cannot prepare a game on the phone. Prepare it once on a computer (Linux or Mac), as the README's "
                + "\"Install\" steps say (github.com/EddizEge/AnkaNative), copy the pack next to the dump as <title ID>-work/android-pack, then add the game again or pick "
                + "that pack folder.", "prepare_unavailable");
        check("prepare_unavailable (Tr.english)", Tr.english("prepare_unavailable"), new Msg("prepare_unavailable").english());
        for (int n : new int[] {0, 1, 812, 12345}) {   // work/mesh-compute's English (String.format, the "Prepare " form as MainActivity made it)
            String quick = String.format(Locale.ROOT, "Updates the shaders from your dump: %,d of the %,d are new or changed in this app version; the others are kept as they are. "
                    + "Only the parts of your dump that hold the new ones are read: %s in all, in the background.", n, 22852, minutes);
            String full = String.format(Locale.ROOT, "Updates the shaders from your dump: %,d of the %,d are new or changed in this app version; the others are kept as they are. "
                    + "The dump's shaders are read again first, which is most of the time: %s in all, in the background.", n, 22852, minutes);
            eq(quick, "prepare_shaders_update_quick", n, 22852, minutes);
            eq(full, "prepare_shaders_update_full", n, 22852, minutes);
            eq("Prepare " + quick.replaceFirst("^Updates", "updates"), "prepare_shaders_update_quick_step", n, 22852, minutes);
            eq("Prepare " + full.replaceFirst("^Updates", "updates"), "prepare_shaders_update_full_step", n, 22852, minutes);
        }
        eq("Makes the shaders from your dump (" + minutes + ", in the background).", "prepare_shaders_make", minutes);
        eq("Makes the shaders from your dump (" + "about 20-35 minutes" + ", in the background).", "prepare_shaders_make_default");
        eq("Prepare " + ("Makes the shaders from your dump (" + minutes + ", in the background).").replaceFirst("^Makes", "makes"), "prepare_shaders_make_step", minutes);
        eq("Prepare " + "Makes the shaders from your dump (about 20-35 minutes, in the background).".replaceFirst("^Makes", "makes"), "prepare_shaders_make_step_default");
        String uri = "content://com.android.externalstorage.documents/tree/1234-5678%3AOoT";
        eq("That folder is not on this phone's storage (" + uri + ").", "prepare_not_on_storage", uri);
        eq("Preparing", "prepare_card_heading");
        eq("Manage the preparing job", "prepare_card_manage");
        eq("Play", "prepare_button_play");
        eq("Ready to prepare: " + name, "prepare_head_pending", name);
        eq("Could not prepare " + name, "prepare_head_failed", name);
        eq("Preparing " + name, "prepare_head_running", name);
        eq("Update " + version, "prepare_card_update", String.valueOf(version));
        eq("Update " + version + "  (simulated engine)", "prepare_card_simulated", "Update " + version);
        eq(String.format(Locale.ROOT, "%d %%  ·  shaders %,d of %,d", Math.round(0.4567 * 100), 12345L, 22852L), "prepare_card_progress", Math.round(0.4567 * 100), 12345L, 22852L);
        eq("Pause", "prepare_button_pause");
        eq("Prepare again", "prepare_button_prepare_again");
        eq("Retry", "prepare_button_retry");
        eq("Start", "prepare_button_start");
        eq("Resume", "prepare_button_resume");
        eq(name + " is ready to play.", "prepare_game_ready_toast", name);
        eq("Checking " + dump + "…", "prepare_checking", dump);
        eq("Resume preparing?", "prepare_resume_title");
        String status = "Paused: by you. Translating shaders: 12,345 of 22,852.";
        eq(status + "\n\nIt goes on from where it stopped.", "prepare_resume_body", status);
        eq("Delete working files", "prepare_label_delete_work");
        eq("An unfinished job for this dump writes to " + pack + ": " + status + "\n\nResume it from the card, or delete its working files "
                + "(⋯ › Delete working files) before preparing into another folder.", "prepare_other_folder_job", pack, status, new Msg("prepare_label_delete_work"));
        eq(name + " is being prepared now (" + status + "). Pause it first, then try again.", "prepare_busy", name, status);
        String shaders = new Msg("prepare_shaders_update_quick", 812, 22852, minutes).english();
        eq("Rewrites the game code (a few minutes). " + shaders, "prepare_redo_code_update", shaders);
        eq("Makes the game code and the shaders again from your dump (" + "about 22-40 minutes" + ", in the background).", "prepare_redo_both", "about 22-40 minutes");
        eq("Rewrites the game code from your dump (a few minutes, in the background). The shaders are kept.", "prepare_redo_code");
        eq("The pack's game code was made by other tools than this app's.", "prepare_redo_other_tools");
        eq("Pack: " + pack, "prepare_pack_line", pack);
        eq("Update the pack?", "prepare_redo_title_code");
        eq("Prepare shaders?", "prepare_redo_title_shaders");
        eq("Prepare", "prepare_button_prepare");
        eq("Pick another pack folder", "prepare_pick_other_pack");
        String build = "1.8.0 (the base game, version 0, main d00e4f399e411d06…)";
        eq("Base game", "prepare_what_base");
        eq("Update " + version, "prepare_what_update", String.valueOf(version));
        eq("Base game" + " (the code of " + build + ")", "prepare_what_base_code", build);
        eq("Update " + 65538 + " (the code of " + build + ")", "prepare_what_update_code", "65538", build);
        for (double free : new double[] {1.97, 23.456}) {
            String which = "Update " + version;
            eq(String.format(Locale.ROOT, "%s, %d DLC.\n\nThe app builds the game's pack and %,d shaders from your dump, once: %s "
                    + "in the background, best on the charger. You can pause any time.\n\nNeeds about 1.5 GB (%.1f GB free): working files in %s, the pack in the app's storage (%s).",
                    which, 2, 22852, "about 22-40 minutes", free, work, pack), "prepare_ask_text", which, 2, 22852, "about 22-40 minutes", free, work, pack);
        }
        eq("Cannot use " + pack + ": " + "no pack.tsv", "prepare_cannot_use", pack, "no pack.tsv");
        eq("Cannot start: " + "cannot make " + dir, "prepare_cannot_start", new LocalizedIOException("prepare_cannot_make_folder", dir).getMessage());
        eq("Have a pack made elsewhere (on a computer, or exported from another phone)?", "prepare_ask_elsewhere");
        eq("Use an existing pack…", "prepare_use_existing");
        eq("Import pack…", "prepare_import");
        eq("Prepare " + "Ocarina of Time" + "?", "prepare_ask_title", "Ocarina of Time");
        eq("Ready to play", "prepare_ready_title");
        eq("Its pack fits: nothing needs preparing.\n\nPack: " + pack, "prepare_ready_body", pack);
        eq("This pack was made by the simulated engine: it is a placeholder, so the game is not started. Play is enabled because the pack and its library are complete.",
                "prepare_simulated_pack", new Msg("prepare_button_play"));
        eq(status + "\n\nDump: " + dump + "\nWorking files: " + dir + "\nPack: " + pack + "\nLogs: " + new File(dir, "logs"), "prepare_manage_info", status, dump, dir, pack,
                new File(dir, "logs"));
        eq("Pause first to forget or delete.", "prepare_manage_running");
        eq("Forget this job…", "prepare_manage_forget");
        eq("Delete working files…", "prepare_manage_delete_work", new Msg("prepare_label_delete_work"));   // the row: the label, with … (one translated text)
        eq("Manage", "prepare_label_manage");   // Ondevice.manageLabel(): the ⋯ button as packs' import refusal names it
        eq("Delete the prepared pack too…", "prepare_manage_delete_pack");
        eq("Preparing " + name, "prepare_manage_title", name);
        eq("Done", "prepare_button_done");
        eq("Forget this job?", "prepare_forget_title");
        eq("Deleted: nothing. The screen stops showing the job.\n\nKept: its files in " + dir
                + ", the pack, your dump and your saves. Picking the same dump again finds the job.\n\nThe split's kept progress (a copy in the app's own storage) "
                + "and the translated shaders (up to about 1 GB, also in the app's own storage) are deleted when a job of another game starts; until then an "
                + "unfinished job resumes from them.", "prepare_forget_body", dir);   // work/shader-speed's English (the translated shaders moved to the app's storage)
        eq("Forget", "prepare_button_forget");
        eq("Not deleted: " + dir + " holds or is inside " + "your dump" + ".", "prepare_not_deleted_dump", dir);
        eq("Not deleted: " + dir + " holds or is inside " + "your save folder" + ".", "prepare_not_deleted_save", dir);
        // Delete working files: the old message for each case (a pack of its own or not, inside the working files or not, a save folder or not), paragraph by paragraph
        for (int pkCase = 0; pkCase < 3; pkCase++) {
            for (boolean hasSave : new boolean[] {false, true}) {
                File pk = pkCase == 0 ? null : pkCase == 1 ? new File(dir, "fake-pack") : pack;
                boolean inside = pkCase == 1;
                StringBuilder m = new StringBuilder("Deleted:\n· the working files in " + dir + " (rewritten code, split shaders, the job's progress)"   // work/shader-speed's English
                        + "\n· the split's kept progress and the translated shaders, in the app's own storage");
                if (pk != null) m.append(inside ? " with the pack inside it" : "\n· the prepared pack and its shader library in " + pk);
                m.append("\n\nKept: your dump (" + dump + ") and your saves").append(hasSave ? " (" + save + ")" : "")
                        .append(pk == null ? ", and the pack (" + pack + ")" : "").append(". Saves are never deleted here.");
                m.append(pk != null ? "\n\nThe game will not start until it is prepared again (about 30-45 minutes)." : "\n\nA new Prepare starts from the beginning.");
                // as Ondevice.deleteWork builds it now
                String deleted = pk == null ? Tr.english("prepare_deleted_work", dir)
                        : inside ? Tr.english("prepare_deleted_work_pack_inside", dir) : Tr.english("prepare_deleted_work_and_pack", dir, pk);
                String kept = pk == null ? (hasSave ? Tr.english("prepare_kept_pack_save", dump, save, pack) : Tr.english("prepare_kept_pack", dump, pack))
                        : hasSave ? Tr.english("prepare_kept_save", dump, save) : Tr.english("prepare_kept", dump);
                String now = deleted + "\n\n" + kept + "\n\n" + Tr.english(pk != null ? "prepare_deleted_pack_end" : "prepare_deleted_work_end");
                check("delete working files (pack case " + pkCase + ", save " + hasSave + ")", now, m.toString());
            }
        }
        eq("Delete the prepared pack?", "prepare_delete_pack_title");
        eq("Delete working files?", "prepare_delete_work_title");
        eq("Delete", "prepare_button_delete");
        eq("Deleting…", "prepare_deleting");
        eq("Deleted. Your dump and saves are untouched.", "prepare_deleted_toast");

        // Job: the steps, and the same lower-cased the old way (Locale.ROOT) for a sentence
        String[] stepText = {"Checking the dump", "Rewriting the game code", "Finding the shaders", "Translating shaders", "Finishing the pack"};
        String[] stepKeys = {"prepare_step_identify", "prepare_step_rewrite", "prepare_step_split", "prepare_step_shaders", "prepare_step_pack"};
        for (int i = 0; i < stepText.length; i++) {
            eq(stepText[i], stepKeys[i]);
            eq(stepText[i].toLowerCase(Locale.ROOT), stepKeys[i] + "_in_sentence");
            eq(stepText[i] + String.format(Locale.ROOT, " %,d / %,d", 1234L, 5678L), "prepare_step_progress", new Msg(stepKeys[i]), 1234L, 5678L);
            eq(stepText[i] + String.format(Locale.ROOT, " %,d", 1234L), "prepare_step_count", new Msg(stepKeys[i]), 1234L);
        }
        eq("Writing the shader library", "prepare_stage_library");
        eq("Checking the shader library", "prepare_stage_check");
        eq(String.format(Locale.ROOT, "%s: %,d of %,d", "Translating shaders", 12345L, 22852L), "prepare_step_shaders_progress", 12345L, 22852L);
        Msg step = new Msg("prepare_step_shaders_progress", 12345L, 22852L);
        String stepOld = String.format(Locale.ROOT, "%s: %,d of %,d", "Translating shaders", 12345L, 22852L);
        eq("Ready to play.", "prepare_status_done");
        String error = "pack stopped with code 1: the dump is incomplete (log: /x/pack.log).";
        eq("Stopped at " + "translating shaders" + ": " + error.replaceAll("[.\\s]+$", "") + ". Tap Retry to go on.", "prepare_status_failed",
                new Msg("prepare_step_shaders_in_sentence"), error.replaceAll("[.\\s]+$", ""), new Msg("prepare_button_retry"));
        // a failure with the packer's error code (Job.statusMsg with JobErrors' text, added by the integrator): whole sentences, one full stop each
        eq("Stopped at translating shaders. Not enough free space (/storage/emulated/0). Free some space, then try again. Tap Retry to go on.", "prepare_status_failed_coded",
                new Msg("prepare_step_shaders_in_sentence"), new Msg("errors_no_space", "/storage/emulated/0"), new Msg("prepare_button_retry"));
        eq("Not started yet.", "prepare_status_pending");
        eq("Paused: " + "by you" + ". " + stepOld + ".", "prepare_status_paused", new Msg("prepare_reason_user"), step);
        eq(stepOld + " (" + ("step " + 4 + " of " + 5) + ").", "prepare_status_running", step, 4, 5);
        eq("Interrupted (" + stepOld + "). Tap Resume to go on.", "prepare_status_interrupted", step, new Msg("prepare_button_resume"));
        eq("adopting", "prepare_status_raw", "adopting");
        eq("by you", "prepare_reason_user");
        eq("battery below 20 %: plug in the charger to go on", "prepare_reason_battery");
        eq("the phone is too hot: it goes on when it has cooled", "prepare_reason_thermal");
        eq("Android's daily limit for background work: open the app and tap Resume", "prepare_reason_timeout", new Msg("prepare_button_resume"));
        eq("Android did not let it start in the background: open the app and tap Resume", "prepare_reason_refused", new Msg("prepare_button_resume"));
        eq("the app was updated with other shader tools: the shaders are translated again", "prepare_reason_apk");
        eq("stopped", "prepare_reason_none");
        eq("cannot make " + dir, "prepare_cannot_make_folder", dir);
        check("LocalizedIOException prepare_cannot_make_folder", new LocalizedIOException("prepare_cannot_make_folder", dir).getMessage(), "cannot make " + dir);

        // PackService: the notification
        eq("Preparing a game", "prepare_channel_name");
        eq("Progress while the app prepares a game from your dump", "prepare_channel_description");
        eq("Preparing " + "a game", "prepare_note_none");
        eq("Preparing " + "a game" + " (simulated)", "prepare_note_simulated", new Msg("prepare_note_none"));
        eq(name + " is ready to play" + " (simulated)", "prepare_note_simulated", new Msg("prepare_note_done", name));
        eq("Starting…", "prepare_note_starting");
        eq(name + " is ready to play", "prepare_note_done", name);
        eq("Open Anka Native and tap Play.", "prepare_note_done_text", new Msg("prepare_button_play"));
        eq("Preparing " + name, "prepare_note_running", name);
        eq("Waiting: " + name, "prepare_note_waiting", name);
        eq("Could not prepare " + name, "prepare_note_failed", name);
        eq("Paused: " + name, "prepare_note_paused", name);
        eq("Step " + (3 + 1) + " of " + 5 + " · " + 57 + " %", "prepare_note_step", 3 + 1, 5, 57);
        eq(stepOld + " · " + 57 + " %", "prepare_note_progress", step, 57);
        eq(stepOld + " · " + 57 + " %\n" + "battery below 20 %: plug in the charger to go on", "prepare_note_progress_why", step, 57, new Msg("prepare_reason_battery"));

        // PackService: job.json's errors (English)
        for (String s : new String[] {"rewrite", "shaders"}) {
            int i = s.equals("rewrite") ? 1 : 3, starts = 3;
            // reworded 2026-11-14 (the step is the object, so a translation needs no participle that agrees with it); the GB a number in the app's language
            eq("Android stopped " + stepText[i].toLowerCase(Locale.ROOT) + " " + (starts - 1) + " times before it finished, most likely "
                    + "for memory (it needs up to " + (s.equals("rewrite") ? "2.7" : "1.5") + " GB). Close other apps, then tap Retry", "prepare_error_step_killed",
                    new Msg(stepKeys[i] + "_in_sentence"), starts - 1, s.equals("rewrite") ? 2.7 : 1.5, new Msg("prepare_button_retry"));
        }
        eq("the dump changed: it is now " + "0400486024DEC000" + " " + version, "prepare_error_dump_changed", "0400486024DEC000", String.valueOf(version));
        File log = new File(dir, "logs/pack.log");
        eq("pack" + " stopped with code " + 1 + "" + " (log: " + log + ")", "prepare_error_tool_exit", "pack", 1, log);
        eq("pack" + " stopped with code " + 1 + ": " + "out of memory" + " (log: " + log + ")", "prepare_error_tool_exit_red", "pack", 1, "out of memory", log);
        eq("cannot compare the dump's shaders with the app's list: " + "no such file", "prepare_error_compare", "no such file");
        eq("the dump's shaders are not the ones this app knows for " + name + " (" + "update 65536" + ", " + "shader_map.tsv"
                + " differs). The dump may be incomplete or modified: dump the game again, then Retry", "prepare_error_shader_map", name, "update 65536", "shader_map.tsv",
                new Msg("prepare_button_retry"));

        // DumpCheck
        eq("Cannot read the free space of " + work + ".", "prepare_dump_free_unknown", work);
        for (long free : new long[] {0, 123456789L, 2047L << 20}) {
            long need = 2048L << 20;
            eq(String.format(Locale.ROOT, "Preparing needs %.1f GB free beside the dump; %s has %.2f GB free.", need / 1073741824.0, work, free / 1073741824.0), "prepare_dump_space",
                    need / 1073741824.0, work, free / 1073741824.0);
        }
        eq("Cannot make " + dir + ": the folder beside the dump is not writable.", "prepare_dump_not_writable", dir);

        if (failed > 0) {
            System.out.println(failed + " failed");
            System.exit(1);
        }
        System.out.println("all ok");
    }
}
