package dev.nxnative.android;

/**
 * The settings area's moved texts on the JVM (docs/i18n.md; run by dm/tools/tests/test_android_settings_texts.py with a stub BuildConfig that has one profile,
 * 040074A01BF12000): prints one case a line, "&lt;name&gt;\t&lt;text&gt;" with a newline in the text written as \n, and the Python side compares each with the
 * English the code wrote before its texts moved into strings_settings.xml.
 */
public final class SettingsTextsJvmTest {
    private static void out(String name, String text) {
        System.out.println(name + "\t" + (text == null ? "<null>" : text.replace("\n", "\\n")));
    }

    public static void main(String[] a) {
        String other = "0100000000010000", ours = "040074A01BF12000";
        out("missing_named", Profile.missing(other, "Mario"));
        out("missing_bare", Profile.missing(other, null));
        out("missing_ours", Profile.missing(ours, "Bananza"));
        Msg m = Profile.missingMsg(other, new Msg("settings_folders_that_pack_game"));
        out("missing_msg", m.english());
        out("missing_msg_text", m.text());
        out("missing_msg_ours", String.valueOf(Profile.missingMsg(ours, "x")));
        out("res_dynamic", Profile.of(ours).resLevelLabel(null));
        out("mb", UpdateCheck.mb(123456789));
        out("gpu_zip_found", Tr.english("settings_gpu_zip_found", "Turnip_v32.zip"));
        out("driverfetch_asset", Tr.english("settings_driverfetch_asset", "v26.3", "turnip.adpkg.zip", UpdateCheck.mb(12L << 20)));
        out("env_lines_1", Tr.englishP("settings_game_env_lines", 1, 1, "DM_WATCH_STALL=25"));
        out("env_lines_2", Tr.englishP("settings_game_env_lines", 2, 2, "A=1 B=2"));
        out("game_default", Tr.english("settings_game_default_value", "60 fps"));
        out("res_level_gone", Tr.english("settings_game_res_level_gone", "fixed:7"));
        out("save_old", Tr.english("settings_folders_save_default_old", "/sdcard/Switch2/saves/X", "/data/saves/X"));
        out("save_app", Tr.english("settings_folders_save_default_app", "/data/saves/X", "/sdcard/Switch2/backups/X", HomeLabels.backUpSaves()));
        out("save_copied", Tr.english("settings_folders_save_default_copied", "/data/saves/X", "/sdcard/Switch2/saves/X", "/sdcard/Switch2/backups/X", HomeLabels.backUpSaves()));
        // Home's labels named inside settings' sentences (docs/i18n.md "References to other labels")
        out("no_games", Tr.english("settings_folders_no_games", HomeLabels.addGame(), HomeLabels.folders()));
        out("game_intro", Tr.english("settings_game_intro", HomeLabels.settings()));
        out("fps_cap_default", Tr.english("settings_game_fps_cap_default", "30 fps", HomeLabels.settings()));
        out("driver_default_system", Tr.english("settings_game_driver_default", HomeLabels.gpuSystem()));
        out("pack_default_app", Tr.english("settings_folders_pack_default_app", "/data/packs/X", Tr.english("settings_folders_library_present", "vk7")));
        out("pack_set_ready", Tr.english("settings_folders_pack_set_ready", "/sdcard/pack", "vk7"));
        out("copy_message", Tr.english("settings_folders_copy_message", "/a", "/b"));
        out("gpu_remove_message", Tr.english("settings_gpu_remove_message", "/data/gpu_drivers/T"));
        out("update_incomplete", Tr.english("settings_update_incomplete", 5L, 10L));
        out("net_http", Tr.english("settings_net_http", 404));
        out("allow_message", Tr.english("settings_update_allow_message"));
        out("driverzip_too_many", new LocalizedIOException("settings_driverzip_too_many_files", 256).getMessage());
        out("driverzip_missing_lib", new LocalizedIOException("settings_driverzip_library_missing", "libvulkan_freedreno.so").getMessage());
        out("mem_app", MemText.app(4508876L * 1024));
    }
}
