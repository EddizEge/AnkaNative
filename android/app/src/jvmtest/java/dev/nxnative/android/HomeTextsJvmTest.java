package dev.nxnative.android;

/**
 * The home area's texts (strings_home.xml: MainActivity, Ui, Prefs, FrontendLaunch) through Tr's XML backend on the JVM, against the English the Java built before
 * they moved (docs/i18n.md: English output stays byte-identical): aapt2's escapes (' and "), \n, &lt;version&gt;, the key tables of Prefs, the frame rate cap
 * labels, plurals, and a Msg that renders in English for the Add game log line. Run by dm/tools/tests/test_android_home_texts.py with -Dnx.tr.res=&lt;the app's
 * res&gt;. Prints "ok" and exits 0, or the first failure.
 */
public final class HomeTextsJvmTest {
    private static void same(String got, String want, String what) {
        if (!want.equals(got)) { System.out.println("FAIL " + what + ": got [" + got + "], want [" + want + "]"); System.exit(1); }
    }

    public static void main(String[] a) {
        String name = "Zelda", path = "/storage/emulated/0/Switch2/oot", id = "0400486024DEC000";

        // Prefs: the window sizes, in WINDOWS' order (the names were the array WINDOW_NAMES, the short ones WINDOW_SHORT)
        String[] names = {"Phone 1080p (1920x1080)", "Original (the game's own size)", "Phone 720p (1280x720)"}, shorts = {"1080p", "Original", "720p"};
        String[] keys = {"home_window_1080p", "home_window_original", "home_window_720p"}, shortKeys = {"home_window_1080p_short", "home_window_original_short", "home_window_720p_short"};
        for (int i = 0; i < names.length; i++) {
            same(Tr.s(keys[i]), names[i], "window name " + i);
            same(Tr.s(shortKeys[i]), shorts[i], "window short name " + i);
        }

        // MainActivity.capLabel
        same(Tr.s("home_cap_default"), "Default", "cap default");
        same(Tr.s("home_cap_own"), "The game's own", "cap own");
        same(Tr.s("home_cap_fps", 30), 30 + " fps", "cap fps");

        // FrontendLaunch
        same(Tr.s("home_frontend_not_shared", path), "The frontend sent \"" + path + "\", which is not a file on this phone's shared storage.", "frontend quotes");
        same(Tr.s("home_frontend_no_game"), "The frontend sent no game: send the .nup (or the game folder) as the intent's data, or the extra \"game\" with its path.", "frontend no game");
        same(Tr.s("home_nup_wrong_case", "OOT.NUP"), "OOT.NUP" + ": rename it so that its name ends in \".nup\" (lower case); the game and the tools read only that name.", "wrong case");
        same(Tr.s("home_frontend_no_card", id, "Anka Native"), "No game with title ID " + id + " is on Home. Add it in " + "Anka Native" + " first, or send the game's file instead.", "no card");

        // Ui
        same(Tr.s("home_tap_for_details", "Pack from other tools"), "Pack from other tools" + ": tap for details", "warn chip description");

        // MainActivity: Settings, About, the rows' values
        same(Tr.s("home_about", "0400486024DEC000, 040074A01BF12000", Tr.s("home_license_gpl", Tr.s("home_license_lsfg")), "o/r"),
                "Title IDs: " + "0400486024DEC000, 040074A01BF12000" + "\n" + "License: GPL-3.0-or-later as a whole (this build includes GPL-3.0-or-later code: LSFG frame generation"
                        + " and mesh draws through compute). Credits and the licenses of its parts: NOTICE.md in the source, github.com/" + "o/r" + ".", "about");
        // MainActivity.aboutLicense: mesh draws through compute are in every build, so every list names them
        same(Tr.s("home_license_gpl", Tr.s("home_license_lsfg_xlate")), "License: GPL-3.0-or-later as a whole (this build includes GPL-3.0-or-later code: "
                + "LSFG frame generation, mesh draws through compute and the shader translator).", "license, LSFG and translator");
        same(Tr.s("home_license_gpl", Tr.s("home_license_xlate")), "License: GPL-3.0-or-later as a whole (this build includes GPL-3.0-or-later code: "
                + "mesh draws through compute and the shader translator).", "license, translator");
        same(Tr.s("home_license_gpl", Tr.s("home_license_memu")), "License: GPL-3.0-or-later as a whole (this build includes GPL-3.0-or-later code: "
                + "mesh draws through compute).", "license, no LSFG, no translator");
        same(Tr.s("home_update_version_release_only", "0.2"), "Anka Native " + "0.2" + " · release app only", "update row");
        same(Tr.s("home_overlay_on", 3, 5), "On · " + 3 + " of " + 5 + " items", "overlay row");
        same(Tr.s("home_lsfg_on_no_dll", 2), "On · " + 2 + "x" + " · Lossless.dll missing", "frame generation row");
        same(Tr.s("home_adb_confirm_body"), "While it is on, adb, and any other app on this phone, can add games, start Prepare and install or pick a GPU "
                + "driver through Anka Native's launcher, without asking you. A GPU driver is code that runs inside the game. Turn it on only while you use adb, then off again.", "adb confirm");

        // MainActivity: Home, a frontend's game, Manage
        // Labels named inside sentences are arguments (docs/i18n.md "References to other labels"): the English is the same as when they were typed in
        same(Tr.s("home_frontend_add_body", "Anka Native", path, Tr.s("home_add"), Tr.s("home_add_game")), "Another app asked " + "Anka Native" + " to open\n\n" + path
                + "\n\nwhich is not on your list. Add game checks it and adds it to Home; nothing is added unless you tap Add.", "frontend add body");
        same(Tr.s("home_frontend_add_body_others", "Anka Native", path, Tr.s("home_add"), "• /a (pack /p)", Tr.s("home_add_game")), "Another app asked " + "Anka Native" + " to open\n\n" + path
                + "\n\nwhich is not on your list. Add game checks it and adds it to Home; nothing is added unless you tap Add." + "\n\nYour list has another dump of this game:\n"
                + "• /a (pack /p)" + "\n\nAdd game then asks whether this file replaces it or both are kept.", "frontend add body, other dumps");
        same(Tr.s("home_frontend_start_body", name, Tr.s("home_play")),
                "Another app asked to start " + name + ". It cannot start straight away: Play checks what it needs and asks before it changes anything.", "frontend start body");
        same(Tr.s("home_frontend_same_build_body", "Anka Native", path, name, "/g", Tr.s("home_play")), "Another app asked " + "Anka Native" + " to open\n\n" + path
                + "\n\nwhich is the same game build as your card of " + name + " (" + "/g" + "). Play starts your card with its own files and pack; that file is not added and your card is not changed.",
                "frontend same build");
        same(Tr.s("home_control_ignored", Tr.s("home_settings"), Tr.s("home_adb")), "A command from another app was ignored (Settings › ADB control is off)", "control ignored");
        same(Tr.s("home_adb_debug", Tr.s("home_adb")), "ADB control (debug)", "adb row");
        same(Tr.s("home_busy_save", name, Tr.s("home_play")), "A save backup or restore of " + name + " is still working: Play waits for it.", "busy save");
        same(Tr.s("home_foreign_pack_body", Tr.s("home_prepare_again")),
                "This game's pack was made by other tools than this app's. It usually still runs. If the game misbehaves, ⋯ › Prepare again remakes it from your dump.", "foreign pack");
        same(Tr.s("home_manage_facts", id, Tr.s("home_manage_game_nup", path), "/p", Tr.s("home_manage_shaders_present", "abc"), Tr.s("home_manage_saves_not_set")),
                "Title ID: " + id + "\nGame: " + path + " (.nup)" + "\nPack: " + "/p" + "\nShaders: " + "present (" + "abc" + ")" + "\nSaves: " + "not set", "manage facts");

        // MainActivity: Add game
        same(Tr.s("home_no_game_in_folder", path), path + " has no main.npdm (base/exefs/, updates/<version>/exefs/ or exefs/), no .nup file and no game folder in it. "
                + "Pick the game's own folder, its .nup file, or the folder that holds your games.", "no game in folder");
        same(Tr.s("home_checking_game", "oot.nup"), "Checking " + "oot.nup" + "…", "checking one");
        same(Tr.p("home_checking_games", 3, 3), "Checking " + 3 + " games…", "checking several");
        same(Tr.s("home_no_pack_folder_body", Tr.s("home_no_pack_folder_no_dump")), "No pack found for this " + "folder" + ", and it is not a dump Prepare can start from."
                + "\n\nIf you have a pack folder made for exactly this " + "folder" + " (with pack.tsv), you can pick it.", "no pack, folder");
        same(Tr.s("home_no_pack_nup_unavailable", "U."), "No pack found for this " + ".nup" + ". " + "U.", "no pack, .nup, no packer");
        same(Tr.s("home_folder_no_romfs", path, "romfs"), "No RomFS in " + path + " (looked for " + "romfs" + ", romfs/ and updates/<version>/romfs).", "no RomFS");
        same(Tr.s("home_replace_body", name, "\n• /a\n• /b", "oot2"),
                "Another dump of " + name + " is on your list:" + "\n• /a\n• /b" + "\n\nUse " + "oot2" + " instead? Only the list entry changes: no dump, pack or save is deleted.", "replace");
        // The summary of Add game: a Msg, English for the log line whatever the user's language
        Msg twice = new Msg("home_added_twice", "a.nup, b", new Msg("home_remove"));
        same(twice.english(), "Another dump of the same game was on the list already, so both cards are kept: " + "a.nup, b" + ". ⋯ › Remove from list takes one away (nothing is deleted).", "twice");
        same(Msg.plural("home_added_prepare", 1, "OoT", new Msg("home_prepare")).english(),
                "Needs Prepare: " + "OoT" + ". No pack that fits was found: its card's Prepare makes one on this phone (one game at a time).", "needs prepare");
        same(new Msg("home_added_failed_item", "x.nup", new Msg("home_nup_wrong_case_short", "x.NUP")).english(),
                "x.nup" + ": " + "x.NUP" + ": rename it so that its name ends in \".nup\" (lower case).", "failed item");

        // MainActivity: Remove, Play
        same(Tr.s("home_remove_body_save", "/s", Tr.s("home_add_game")),
                "Only the list entry goes. Your dump, its pack and your saves" + " (" + "/s" + ")" + " stay on the phone; Add game brings it back.", "remove, save");
        same(Tr.s("home_remove_body", Tr.s("home_add_game")), "Only the list entry goes. Your dump, its pack and your saves" + "" + " stay on the phone; Add game brings it back.", "remove");
        same(Tr.s("home_shaders_missing_prepare", Tr.s("prepare_shaders_make_step_default"), "lp"),
             "Prepare " + "Makes the shaders from your dump (about 20-35 minutes, in the background).".replaceFirst("^Makes", "makes") + "\n\n" + "lp", "shaders missing, dump");
        same(Tr.s("home_pack_problem_dump", Tr.s("home_prepare_again"), "why", "/p"), "Prepare again fixes it from your dump, on this phone.\n\n" + "why" + "\n\nPack: " + "/p", "pack problem");
        // Changed on purpose (no possessive after a runtime name): was name + "'s pack is still working"
        same(Tr.s("home_busy_pack", name, Tr.s("home_play")), "An export or import of the pack of " + name + " is still working: Play waits for it.", "busy pack (rephrased)");
        same(Tr.s("home_lsfg_no_dll", "Lossless.dll not found"), "Lossless.dll not found" + ". Put it in Documents/Emulation/bios or Switch2, or pick it.", "frame generation note");
        System.out.println("ok home texts");
    }
}
