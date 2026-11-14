package dev.nxnative.android;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The game area's texts (strings_game.xml: GameActivity, SidePanel, TouchControls, Overlay, Fsr, Lsfg, SelftestActivity) in English, driven by
 * dm/tools/tests/test_android_game_texts.py. Those classes are Android classes, so this test does not compile them: each case puts the English a resource renders
 * (through Tr's XML backend, which reads strings as aapt2 compiles them) beside the expression the Java built before the text moved, copied from it, with sample
 * arguments. Prints "ok &lt;n&gt;" when all n cases match, else one "diff" line per case that does not and exits 1.
 */
public final class GameTextsJvmTest {
    private static final List<String> diffs = new ArrayList<>();
    private static int n;

    private static void same(String was, String key, Object... args) {
        n++;
        String now = Tr.english(key, args);
        if (!now.equals(was)) diffs.add("diff " + key + ": was [" + was + "] now [" + now + "]");
    }

    public static void main(String[] a) {
        String name = "The Legend of Zelda", id = "0400486024DEC000";
        File dir = new File("/storage/emulated/0/Switch2/saves/" + id), old = new File("/storage/emulated/0/Switch2/old/" + id);
        // GameActivity
        same("Open the in-game panel", "game_panel_open_description");
        same("Paused\n\nTap to continue  (A or Start on a gamepad)", "game_paused");
        same("The game was stopped while it was in the background (Android ended its process). Unsaved progress is lost; start it again from the launcher.", "game_stopped_in_background");
        same("Intro skip on: pressing A and ending movies until gameplay", "game_intro_skip_on");
        same("Intro skip off", "game_intro_skip_off");
        same("The save folder has an unfinished restore that cannot be finished: " + "journal broken", "game_save_unfinished_restore", "journal broken");
        same("cannot create the test save folder " + dir, "game_test_save_cannot_create", dir);
        same("cannot create " + dir, "game_cannot_create", dir);
        same("Your save was copied into the app's storage (" + dir + "). The old folder " + old + " is kept as it was.", "game_save_copied", dir, old);
        same("Your save could not be copied into the app's storage (" + "disk full" + "). This start plays it where it is, " + old + ".", "game_save_not_copied", "disk full", old);
        same(name + ": checking this phone's GPU…", "game_checking_gpu", name);
        same("GPU: " + "Adreno 830, Vulkan 1.3" + "\nMemory: " + "11.2" + " GiB as Android reports it.", "game_details_gpu", "Adreno 830, Vulkan 1.3", "11.2");
        String missing = "VK_KHR_map_memory2, placed memory maps";
        same("Missing: " + missing + ".\n\nThe game may stop or draw wrongly. A custom GPU driver (Settings › GPU driver) may help.", "game_ready_missing", missing);
        same("Missing: " + missing + ".\n\nWith this GPU driver (" + "System" + ") nothing is drawn: the screen stays black while the game runs. " + "Pick a custom driver that has placed memory maps"
                + " in Settings › GPU driver, or in this game's Game settings.", "game_ready_missing_black_system", missing);
        same("Missing: " + missing + ".\n\nWith this GPU driver (" + "Turnip 25.1" + ") nothing is drawn: the screen stays black while the game runs. " + "Pick System (the phone's own driver)"
                + " in Settings › GPU driver, or in this game's Game settings.", "game_ready_missing_black_custom", missing, "Turnip 25.1");
        same("Runs with less: " + "timeline semaphores" + ".", "game_ready_limits_message", "timeline semaphores");
        same("Not ready for this game", "game_ready_not_ready_title");
        same("Start anyway (black screen)", "game_ready_start_black");
        same("Try anyway", "game_ready_try_anyway");
        same("Ready, with limits", "game_ready_limits_title");
        same("Continue", "game_ready_continue");
        same("✕ " + "Placed memory" + ": " + "The driver has none." , "game_details_fail_item", "Placed memory", "The driver has none.");
        same("! " + "Timeline" + ": " + "Slower." , "game_details_warn_item", "Timeline", "Slower.");
        same("Details", "game_ready_details");
        same("Runs with less: x." + "\n\nDetails are also in Settings › Readiness check.", "game_ready_details_also", "Runs with less: x.");
        same("Don't show this again", "game_ready_dont_show");
        same("Back", "game_ready_back");
        same("Readiness check", "game_ready_check_title");
        same("Close", "game_ready_close");
        same("Copy report", "game_ready_copy_report");
        same("game.nup" + " has no " + "base+update" + " with a RomFS", "game_nup_no_romfs", "game.nup", "base+update");
        same("no RomFS in " + dir, "game_no_romfs", dir);
        same("DM_SAVE" + " (" + "the game's settings" + ")", "game_env_refused_item", "DM_SAVE", new Msg("game_env_from_game_settings"));
        same("DM_ROOT" + " (" + "env.txt" + ")", "game_env_refused_item", "DM_ROOT", "env.txt");
        String refused = "DM_SAVE (env.txt), HOME (the game's settings)";
        same("Environment lines " + "not taken: " + refused + ": the launcher sets the game's paths (DM_SAVE, DM_ROMFS, DM_TEMP, DM_ROOT, DM_AOC_DIR, HOME)", "game_env_refused", refused);
        same("Environment lines " + "not taken: " + refused + ": the launcher sets the game's paths (DM_SAVE, DM_ROMFS, DM_TEMP, DM_ROOT, DM_AOC_DIR, HOME)"
                + "; DM_SAVE is taken from env-<title ID>.txt only", "game_env_refused_test", refused);
        for (int r : new int[] {-1, 2, -12}) {
            String was = "the host did not start (" + r + (r == -1 ? ": a start was already tried in this process" : "") + ")";
            same(was, r == -1 ? "game_host_not_started_again" : "game_host_not_started", r);
        }
        same(name + ": starting. Log: " + new File(dir, "x.log"), "game_starting", name, new File(dir, "x.log"));
        same("Cannot start " + name, "game_cannot_start_title", name);
        same(name + " is running", "game_running_title", name);
        same("Stop it first (the in-game panel › Stop the game), then start " + "Mario Kart World" + ". One game runs at a time.", "game_running_message", "Mario Kart World",
                new Msg("game_panel_stop"));
        same("The save " + dir + " is in use: a backup or restore of it is working. Try again when it is done.", "game_save_in_use", dir);
        // SidePanel
        same("The game runs on behind this panel; every change applies now and is kept.", "game_panel_intro");
        same("Frame rate cap", "game_panel_cap_head");
        same("The game's own", "game_panel_cap_own");
        same(30 + " fps", "game_panel_cap_fps", 30);
        same("Overlay", "game_panel_overlay_head");
        same("On-screen controls", "game_panel_controls_head");
        same("Intro skip (debug)", "game_panel_skip_head");
        same("Presses A every few frames and ends movies early until gameplay, then stops by itself. In the game: hold − and + together for 1 s.", "game_panel_skip_note");
        same("Stop the intro skip", "game_panel_skip_stop");
        same("Skip the intro now", "game_panel_skip_now");
        same("Pause the game", "game_panel_pause");
        same("Pause when the game comes back from the background", "game_panel_pause_on_return");
        same("Stop the game", "game_panel_stop");
        same("Stop the game?", "game_panel_stop_title");
        same("The game ends; unsaved progress is lost. The log stays for Share log.", "game_panel_stop_message");
        same("Stop", "game_panel_stop_confirm");
        same("Keep running", "game_panel_keep_running");
        same("Back to the game", "game_panel_back");
        same("This build has no frame rate cap.", "game_panel_no_cap");
        same("Starting…", "game_panel_starting");
        same("This game picks its own frame rate.", "game_panel_own_rate");
        for (int cap : new int[] {0, 30}) {
            int now = 29;
            String was = "Now " + now + " fps" + (cap > 0 ? ", capped at " + cap : ", the game's own") + ".";
            if (cap > 0) same(was, "game_panel_now_capped", now, cap);
            else same(was, "game_panel_now_own", now);
        }
        same("Now: " + "on x2 120 fps", "game_panel_lsfg_now", "on x2 120 fps");
        // TouchControls
        String[] groups = {"left stick", "D-pad", "A B X Y", "right stick", "ZL L −", "ZR R +"};
        String[] groupKeys = {"game_controls_group_left_stick", "game_controls_group_dpad", "game_controls_group_face", "game_controls_group_right_stick",
                "game_controls_group_left_shoulder", "game_controls_group_right_shoulder"};
        for (int g = 0; g < groups.length; g++) {
            same(groups[g], groupKeys[g]);
            float gsc = 1.25f;
            same(groups[g] + ": drag to move, a second finger to resize (" + Math.round(gsc * 100) + " %)", "game_controls_hint_group", new Msg(groupKeys[g]), Math.round(gsc * 100));
        }
        String[] tools = {"Top", "Centre", "Bottom", "Reset", "Done"};
        String[] toolKeys = {"game_controls_top", "game_controls_centre", "game_controls_bottom", "game_controls_reset", "game_controls_done"};
        for (int k = 0; k < tools.length; k++) same(tools[k], toolKeys[k]);
        same("Picture position (this screen)", "game_controls_picture_position");
        same("Edit the controls layout…", "game_controls_edit_layout");
        same("picture: drag into the black bars", "game_controls_hint_picture");
        same("Drag a control or the picture; pinch a control to resize it", "game_controls_hint");
        same("Vibrate on press", "game_controls_vibrate");
        same("Show the log over the game", "game_controls_show_log");
        same("Opacity " + (50 + 10) + " %", "game_controls_opacity", 50 + 10);
        same("Size " + (40 + 60) + " %", "game_controls_size", 40 + 60);
        same("Reset the controls", "game_controls_reset_all");
        // Overlay
        String[] items = {"Frame rate and frame time", "Throttle dot (green / yellow / red)", "GPU time a frame", "CPU clock caps", "Battery temperature and level",
                "Memory: the game's and the phone's"};
        String[] itemKeys = {"game_overlay_item_fps", "game_overlay_item_throttle", "game_overlay_item_gpu", "game_overlay_item_cpu", "game_overlay_item_battery",
                "game_overlay_item_memory"};
        for (int i = 0; i < items.length; i++) same(items[i], itemKeys[i]);
        same("Show the overlay over the game", "game_overlay_master");
        double cap = 2500000 / 1e6, top = 3628800 / 1e6;
        String v = String.format(Locale.ROOT, "%.1f/%.1f", cap, top);
        same("CPU " + v + " GHz", "game_overlay_cpu_one", cap, top);
        same("CPU " + v, "game_overlay_cpu_first", cap, top);
        same(v, "game_overlay_cpu_next", cap, top);
        same(v + " GHz", "game_overlay_cpu_last", cap, top);
        same("CPU cap ?", "game_overlay_cpu_unknown");
        same("-- fps", "game_overlay_fps_none");
        float[] rate = {29.6f, 59.4f, 33.84f, 1, 0, 12.25f};
        same(String.format(Locale.ROOT, "%.0f→%.0f fps", rate[0], rate[1]), "game_overlay_fps_lsfg", rate[0], rate[1]);
        same(String.format(Locale.ROOT, "%.0f fps", rate[0]), "game_overlay_fps", rate[0]);
        same(String.format(Locale.ROOT, "%.1f ms", rate[2]), "game_overlay_frame_ms", rate[2]);
        same("no LSFG", "game_overlay_no_lsfg");
        same(String.format(Locale.ROOT, "GPU %.1f ms", rate[5]), "game_overlay_gpu_ms", rate[5]);
        float batTemp = 36.7f;
        int batLevel = 84;
        for (boolean charging : new boolean[] {false, true}) {
            same(String.format(Locale.ROOT, "%.0f°C ", batTemp) + batLevel + (charging ? "% ⚡\uFE0E" : "%"),
                    charging ? "game_overlay_battery_charging" : "game_overlay_battery", batTemp, batLevel);
            same("" + batLevel + (charging ? "% ⚡\uFE0E" : "%"), charging ? "game_overlay_battery_level_charging" : "game_overlay_battery_level", batLevel);
        }
        same("low", "game_overlay_memory_low");
        // Fsr
        same("Upscaling", "game_fsr_head");
        same("FSR 1 (sharper when the picture is scaled up to the screen)", "game_fsr_on");
        same("Sharpness", "game_fsr_sharpness");
        String[] sharps = {"Max", "Default", "Soft", "Softer"};
        String[] sharpKeys = {"game_fsr_sharp_max", "game_fsr_sharp_default", "game_fsr_sharp_soft", "game_fsr_sharp_softer"};
        for (int i = 0; i < sharps.length; i++) same(sharps[i], sharpKeys[i]);
        // Lsfg
        same("Frame generation (Lossless Scaling, your own Lossless.dll)", "game_lsfg_head");
        same("Not in this build (built with -PnxLsfg=false).", "game_lsfg_not_built");
        same("Frame generation (LSFG)", "game_lsfg_on");
        same("Frames per game frame", "game_lsfg_mult");
        same("Target frame rate (the multiplier follows it)", "game_lsfg_target");
        same("Fixed", "game_lsfg_fixed");
        same("Motion estimation resolution (lower is faster; Auto = the game's own)", "game_lsfg_flow", new Msg("game_lsfg_auto"));
        same("Auto", "game_lsfg_auto");
        same("Mode: performance (the only shader set Eden's LSFG uses)", "game_lsfg_mode");
        String p = "/storage/emulated/0/Switch2/Lossless.dll";
        same("Lossless.dll from " + p, "game_lsfg_from", p);
        same("Lossless.dll at " + p + " could not be copied: " + "cannot replace /data/x", "game_lsfg_not_copied", p, new Msg("game_cannot_replace", new File("/data/x")));
        same("Lossless.dll: the copy picked earlier", "game_lsfg_earlier");
        same("Lossless.dll not found (looked in Documents/Emulation/bios, Switch2, Download and the app's files)", "game_lsfg_not_found");
        same("Lossless.dll: picked by hand", "game_lsfg_by_hand");
        same("cannot open " + "content://x/y", "game_lsfg_cannot_open", "content://x/y");
        same("that file is not a Windows DLL", "game_lsfg_not_dll");
        same("cannot replace " + new File("/data/x"), "game_cannot_replace", new File("/data/x"));
        // SelftestActivity
        File log = new File("/storage/emulated/0/Android/data/x/files/logs/selftest-1.log");
        int frames = 600;
        same("FAIL: cannot open the log " + log, "game_selftest_no_log", log);
        same("Graphics self-test: waiting for the surface", "game_selftest_waiting");
        same("FAIL: this host has no nxhost_vk_selftest", "game_selftest_no_host");
        same("Graphics self-test running (" + frames + " frames, about " + frames / 60 + " s)", "game_selftest_running", frames, frames / 60);
        for (int rc : new int[] {0, -3}) same((rc == 0 ? "PASS" : "FAIL") + " (return code " + rc + ")\nBack to return; Share log sends this run's log.",
                rc == 0 ? "game_selftest_pass" : "game_selftest_fail", rc);
        // LocalizedIOException: the English a log or a test reads, the text a screen shows
        LocalizedIOException e = new LocalizedIOException("game_save_unfinished_restore", new Msg("game_cannot_create", dir));
        n++;
        if (!e.getMessage().equals("The save folder has an unfinished restore that cannot be finished: cannot create " + dir)) diffs.add("diff LocalizedIOException: " + e.getMessage());

        for (String d : diffs) System.out.println(d);
        if (!diffs.isEmpty()) System.exit(1);
        System.out.println("ok " + n);
    }
}
