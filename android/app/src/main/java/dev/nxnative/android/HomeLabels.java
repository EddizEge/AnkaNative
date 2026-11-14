package dev.nxnative.android;

/**
 * The home screen's labels that other areas' sentences name (docs/i18n.md "References to other labels"): the Settings page, its rows, a game card's Manage sheet
 * and its buttons. Another area passes one as an argument of its own sentence ("A custom GPU driver (%2$s › %3$s) may help.") instead of typing the words again, so
 * the sentence names the button as the user sees it in every language. Each returns a Msg of the very resource MainActivity draws the label with; a screen passes
 * msg.text() to getString, a Msg or Tr.s takes the Msg itself. No Android classes: the JVM tests compile it with the areas that use it.
 */
final class HomeLabels {
    private HomeLabels() {}

    /** The Settings page's title and its header button (home_settings) */
    static Msg settings() { return new Msg("home_settings"); }

    /** Settings › GPU driver */
    static Msg gpuDriver() { return new Msg("home_gpu_driver"); }

    /** The value Settings › GPU driver shows for the phone's own driver ("System") */
    static Msg gpuSystem() { return new Msg("home_gpu_system"); }

    /** Settings › Readiness check */
    static Msg readinessCheck() { return new Msg("home_readiness"); }

    /** Settings › Share log, and the Manage sheet's Share log */
    static Msg shareLog() { return new Msg("home_share_log"); }

    /** Settings › Folders, and the Manage sheet's Folders */
    static Msg folders() { return new Msg("home_folders"); }

    /** The Add game button */
    static Msg addGame() { return new Msg("home_add_game"); }

    /** The Manage sheet's rows */
    static Msg gameSettings() { return new Msg("home_game_settings"); }

    static Msg backUpSaves() { return new Msg("home_backup_saves"); }

    static Msg restoreSaves() { return new Msg("home_restore_saves"); }

    static Msg prepareAgain() { return new Msg("home_prepare_again"); }

    static Msg exportPack() { return new Msg("home_export_pack"); }

    static Msg importPack() { return new Msg("home_import_pack"); }

    /** A game card's big button when only its shaders are to be made */
    static Msg prepareShaders() { return new Msg("home_prepare_shaders"); }
}
