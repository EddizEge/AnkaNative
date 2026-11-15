package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The launcher. Two pages in one activity (so every picker's result comes back here): Home, the short path to playing (pick your dump, prepare it, Play), and Settings
 * (the defaults of every game, GPU driver, folders, the Readiness check, the self-test, logs, the build), opened with the gear in Home's header; Back returns to Home.
 * <p>
 * Home: the All files access card while it is missing, a game being prepared (Ondevice: shown while there is a job), then the games, each a card with its state and
 * one big button: Play, or Prepare when its pack does not fit yet; ⋯ (or a long press) opens Manage (its folders, its own settings, saves, Prepare again, removal),
 * kept away from Play. With no game, a single card says what to do and has Add game. "Add game" takes a dump folder or .nup of any game this APK has a profile for
 * (Profile: one host per game), or a folder that holds several (all added, one summary: addAll): the best pack PackFind finds (the picked pack folder, the app's,
 * beside the game, &lt;id&gt;-work/&lt;any&gt; up to three folders above it) is used when it fits or only needs the automatic shader update, else the app offers to
 * prepare one on the phone (or a card with Prepare, from a folder of games). A game without a profile in this APK is refused with a sentence (Profile.missing),
 * never started.
 * <p>
 * Each page sits in one ScrollView over the drifting bananas, so it scrolls on any screen (the folded phone's outer display, landscape, a split window), at a
 * readable width, centered on a wide window. The activity handles rotation itself (configChanges), so a size change builds the page again (onConfigurationChanged).
 */
public final class MainActivity extends Activity {
    private static final int PICK_GAME = 1, PICK_PACK = 2, PICK_DLL = 3, PICK_DRIVER = 4, PICK_NUP = 5;

    private final List<Game> games = new ArrayList<>();
    private LinearLayout gameBox;   // the game cards (refresh)
    private View accessCard;
    private BananaBackgroundView bananas;   // the drifting bananas behind the screen
    private Ui.Setting overlayRow, resRow, capRow, lsfgRow, gpuRow, updateRow;
    private boolean resumed, settingsPage;
    private boolean merged;   // the list's duplicates were merged (Game.collapse) since this activity started
    private File pendingGame;   // a game folder (or .nup) waiting for its pack folder
    private String pendingId;
    private File pendingPackPick;   // the pack folder the user just picked (remembered in Prefs once it is accepted)

    /** Android 12: the language picked in Settings › Language (Lang.wrap; Android 13+ applies it itself) */
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) settingsPage = saved.getBoolean("settings");
        FrameLayout frame = new FrameLayout(this);
        bananas = new BananaBackgroundView(this);
        frame.addView(bananas, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        build();
        if (saved == null) fromIntent(getIntent());
        Updater.onStart(this);   // the release app: a newer release on GitHub opens the update popup (once per process start: not on a resume or a rotation)
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("settings", settingsPage);
    }

    /** Rotation, a window resize: the same page again */
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        build();
        if (resumed) { onResumeViews(); if (!settingsPage) Ondevice.resume(this); }
    }

    /** Home ↔ Settings */
    private void page(boolean settings) {
        if (settingsPage == settings) return;
        settingsPage = settings;
        if (settings) Ondevice.pause(this);   // its card is on Home only: no updates while Settings shows
        build();
        if (resumed) { onResumeViews(); if (!settings) Ondevice.resume(this); }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (settingsPage) page(false);
        else super.onBackPressed();
    }

    /** (Re)builds the current page above the bananas (Ui.page: the same gutter, width and header spacing as every screen) */
    private void build() {
        FrameLayout frame = (FrameLayout) bananas.getParent();
        while (frame.getChildCount() > 1) frame.removeViewAt(1);
        LinearLayout content = Ui.page(this, frame, 640);
        if (settingsPage) buildSettings(content);
        else buildHome(content);
    }

    private void buildHome(LinearLayout content) {
        overlayRow = null;
        content.addView(Ui.header(this, Ui.badge(this, 44), "Anka Native", Prefs.buildId(), Ui.iconButton(this, R.drawable.ic_settings, getString(R.string.home_settings), Ui.Kind.TONAL, v -> page(true))));   // i18n-keep: the product name (glossary), whatever the build's app_name. The build identifier under the title (Prefs.buildId, the part of the overlay's build tag after the app's name), so a screenshot of Home names its build; Settings › About has the full tag
        accessCard = accessCard();
        Ui.add(content, accessCard, 16);
        Ondevice.attach(this, content, content.getChildCount());   // a game being prepared and its progress (hidden when there is none)
        fixGap(content, Ui.GAP);
        gameBox = new LinearLayout(this);
        gameBox.setOrientation(LinearLayout.VERTICAL);
        Ui.add(content, gameBox, 16);
        refresh();
    }

    /** The Ondevice card is added without a margin: give it the cards' gap */
    private void fixGap(LinearLayout col, int gapDp) {
        for (int k = 1; k < col.getChildCount(); k++) {
            View v = col.getChildAt(k);
            ViewGroup.LayoutParams p = v.getLayoutParams();
            if (p instanceof LinearLayout.LayoutParams && ((LinearLayout.LayoutParams) p).topMargin == 0) ((LinearLayout.LayoutParams) p).topMargin = dp(gapDp);
        }
    }

    private View accessCard() {
        LinearLayout c = Ui.card(this);
        c.setBackground(Ui.round(Ui.col(this, R.color.bn_warn_tint), dp(20), Ui.col(this, R.color.bn_banana_deep), dp(1)));
        c.addView(Ui.heading(this, getString(R.string.home_access_title)));
        Ui.add(c, Ui.body(this, getString(R.string.home_access_body)), 4);
        Ui.add(c, Ui.button(this, getString(R.string.common_allow), Ui.Kind.PRIMARY, v -> openAccessSettings()), 14, Ui.wrap());
        return c;
    }

    // ---- Settings page ----

    private void buildSettings(LinearLayout content) {
        accessCard = null;
        gameBox = null;
        content.addView(Ui.header(this, Ui.back(this, v -> page(false)), getString(R.string.home_settings), null));

        // Updates first, so the version and the check are easy to find (Updater: the release app also checks at every fresh start)
        Ui.add(content, Ui.section(this, getString(R.string.home_section_updates)), 16);
        LinearLayout upd = settingsCard(content);
        updateRow = new Ui.Setting(this, "🔄", getString(R.string.home_check_updates), v -> Updater.checkNow(this)).chevron(this);
        upd.addView(updateRow.view);

        Ui.add(content, Ui.section(this, getString(R.string.home_section_game_defaults)), Ui.SECTION_GAP);
        LinearLayout play = settingsCard(content);
        overlayRow = new Ui.Setting(this, "👀", getString(R.string.home_overlay), v -> overlayItems())
                .toggle(this, Prefs.overlay(this), (b, on) -> { Prefs.overlay(this, on); refreshSettings(); });
        play.addView(overlayRow.view);
        resRow = new Ui.Setting(this, "🖼️", getString(R.string.home_resolution), v -> gameRes()).chevron(this);
        play.addView(resRow.view);
        if (!Profile.anyResolution()) resRow.view.setVisibility(View.GONE);   // no game of this APK has a window_fn or graphics_scale lever: the default would do nothing
        capRow = new Ui.Setting(this, "⏱️", getString(R.string.home_fps_cap), v -> fpsCap()).chevron(this);
        play.addView(capRow.view);
        lsfgRow = new Ui.Setting(this, "✨", getString(R.string.home_frame_gen), v -> frameGen()).chevron(this);
        play.addView(lsfgRow.view);
        TextView hint = Ui.small(this, getString(R.string.home_defaults_hint));
        hint.setPadding(dp(4), 0, dp(4), 0);
        Ui.add(content, hint, 8);

        Ui.add(content, Ui.section(this, getString(R.string.home_section_system)), Ui.SECTION_GAP);
        LinearLayout sys = settingsCard(content);
        gpuRow = new Ui.Setting(this, "🎮", getString(R.string.home_gpu_driver), v -> GpuDriver.dialog(this, PICK_DRIVER)).chevron(this);   // custom GPU drivers (GpuDriver.java)
        sys.addView(gpuRow.view);
        sys.addView(new Ui.Setting(this, "📁", getString(R.string.home_folders), v -> Folders.dialog(this)).value(getString(R.string.home_folders_value)).chevron(this).view);   // the game, pack and save folders, kept across updates (Folders.java)
        sys.addView(new Ui.Setting(this, "🌐", Lang.title(this), v -> Lang.dialog(this)).value(Lang.label(this)).chevron(this).view);   // the app's language (Lang.java)

        Ui.add(content, Ui.section(this, getString(R.string.home_section_help)), Ui.SECTION_GAP);
        LinearLayout help = settingsCard(content);
        help.addView(new Ui.Setting(this, "📋", getString(R.string.home_readiness), v -> startActivityForResult(new Intent(this, ReadinessActivity.class), ReadinessActivity.REQUEST))
                .value(getString(R.string.home_readiness_value)).chevron(this).view);   // (ReadinessActivity.java)
        help.addView(new Ui.Setting(this, "🧪", getString(R.string.home_selftest), v -> startActivity(new Intent(this, SelftestActivity.class))).value(getString(R.string.home_selftest_value)).chevron(this).view);
        help.addView(new Ui.Setting(this, "📨", getString(R.string.home_share_log), v -> shareLog()).value(getString(R.string.home_share_log_value)).chevron(this).view);
        if (BuildConfig.NX_PLAY) {   // a test build always takes adb commands (Prefs.adbControl)
            Ui.Setting adb = new Ui.Setting(this, "🛠", getString(R.string.home_adb_debug, getString(R.string.home_adb)), null).value(getString(R.string.home_adb_value));
            adb.toggle(this, Prefs.adbControl(this), (b, on) -> {
                if (on == Prefs.adbControl(this)) return;   // the switch set to what is stored (below): nothing to do
                if (!on) { Prefs.adbControl(this, false); return; }
                b.setChecked(false);   // stays off until confirmed
                Ui.confirmDanger(this, getString(R.string.home_adb_confirm_title), getString(R.string.home_adb_confirm_body),
                        getString(R.string.home_adb_turn_on), () -> { Prefs.adbControl(this, true); b.setChecked(true); });
            });
            help.addView(adb.view);
        }

        Ui.add(content, Ui.section(this, getString(R.string.home_section_about)), Ui.SECTION_GAP);
        LinearLayout about = Ui.add(content, Ui.card(this), 8);
        TextView tag = Ui.chip(this, Prefs.buildTag(this));   // which build this is (also the first line of every log)
        tag.setTextIsSelectable(true);
        about.addView(tag, Ui.wrap());
        StringBuilder ids = new StringBuilder();
        for (Profile p : Profile.all()) ids.append(ids.length() == 0 ? "" : ", ").append(p.titleId);
        TextView t = Ui.small(this, getString(R.string.home_about, ids, aboutLicense(), Updater.REPO));
        t.setTextIsSelectable(true);
        Ui.add(about, t, 10);
        refreshSettings();
    }

    /**
     * The About screen's license line. Anka Native is GPL-3.0-or-later as a whole: this APK's GPL-3.0-or-later parts are mesh draws through compute
     * (dm/gfx/vk_android/vka_memu*, in every libnxhost.so), LSFG frame generation (dm/gfx/vk_android/lsfg, unless built with -PnxLsfg=false) and, with the
     * on-phone packer, the shader translator (libdmsh_xlate.so, InstEmitAmpereMesh.cs). The four home_license_* lists all name mesh draws through compute
     */
    static String aboutLicense() {
        boolean xlate = "native".equals(BuildConfig.NX_PACK_ENGINE);
        if (BuildConfig.NX_LSFG && xlate) return Tr.s("home_license_gpl", Tr.s("home_license_lsfg_xlate"));
        if (BuildConfig.NX_LSFG) return Tr.s("home_license_gpl", Tr.s("home_license_lsfg"));
        if (xlate) return Tr.s("home_license_gpl", Tr.s("home_license_xlate"));
        return Tr.s("home_license_gpl", Tr.s("home_license_memu"));
    }

    /** A settings group's card of rows, under its section label */
    private LinearLayout settingsCard(LinearLayout content) { return Ui.add(content, Ui.listCard(this), 8); }

    /** The settings rows' current values (after a dialog, on resume) */
    private void refreshSettings() {
        if (overlayRow == null) return;
        updateRow.value(Updater.available(this) ? getString(R.string.home_update_version, Updater.version(this)) : getString(R.string.home_update_version_release_only, Updater.version(this)));
        boolean ov = Prefs.overlay(this);
        if (overlayRow.toggle.isChecked() != ov) overlayRow.toggle.setChecked(ov);
        int n = 0;
        for (String[] it : Overlay.ITEMS) if (Prefs.overlayItem(this, it[0])) n++;
        overlayRow.value(ov ? getString(R.string.home_overlay_on, n, Overlay.ITEMS.length) : getString(R.string.home_off));
        resRow.value(resLabel());
        capRow.value(capLabel(Prefs.fpsCap(this)));
        lsfgRow.value(!BuildConfig.NX_LSFG ? getString(R.string.home_lsfg_not_built) : !Lsfg.on(this) ? getString(R.string.home_off)
                : Lsfg.find(this) == null ? getString(R.string.home_lsfg_on_no_dll, Lsfg.multiplier(this)) : getString(R.string.home_lsfg_on, Lsfg.multiplier(this)));
        GpuDriver.Info d = GpuDriver.selected(this);
        gpuRow.value(d == null ? getString(R.string.home_gpu_system) : d.label());
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) refreshSettings();   // back from a dialog (GPU driver, Folders, …)
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        fromIntent(i);
    }

    /** adb: am start -n dev.nxnative.android/.MainActivity --es game <game folder> [--es pack <pack folder>] [--es save <save folder>] [--ez run true] adds the game without
     *  the pickers (android/TESTING.md); --es add_game <folder or .nup> takes Add game's own path instead (gamePicked: all of a folder's games added, Prepare asked); save is the save folder Folders would pick for it (test runs of a play or release app keep away from the user's saves/<id>) */
    /** The extras of the adb control commands above and in Ondevice, GpuDriver and PackUi (android/TESTING.md): taken only when Prefs.adbControl allows them */
    static final String[] CONTROL_EXTRAS = {"game", "add_game", "pack", "save", "run", "ondevice_dump", "ondevice_start", "ondevice_pause", "ondevice_pack", "show_when_locked",
            "gpu_driver_zip", "gpu_driver", "pack_title", "pack_export", "pack_import"};

    private void fromIntent(Intent i) {
        if (!Prefs.adbControl(this)) {
            List<String> asked = new ArrayList<>();
            for (String k : CONTROL_EXTRAS) if (i.hasExtra(k)) asked.add(k);
            if (!asked.isEmpty()) {   // another app (or adb) asked for a control command: never acted on while ADB control is off (issue #7)
                android.util.Log.w("nxnative", "launcher intent from " + getReferrer() + " with " + asked + " ignored: ADB control is off (Settings › ADB control)");
                Toast.makeText(this, getString(R.string.home_control_ignored, getString(R.string.home_settings), getString(R.string.home_adb)), Toast.LENGTH_LONG).show();
                i = new Intent(i);
                for (String k : asked) i.removeExtra(k);
            }
        }
        if (settingsPage && (i.hasExtra("game") || i.hasExtra("add_game") || i.hasExtra("ondevice_dump") || i.hasExtra("ondevice_pause") || i.hasExtra(Ondevice.EXTRA_RESUME))) page(false);   // these act on Home's cards
        Ondevice.fromIntent(this, i);   // --es ondevice_dump <dump folder> [--ez ondevice_start true] (Ondevice)
        GpuDriver.fromIntent(this, i);   // --es gpu_driver_zip <zip> / --es gpu_driver <name | system>
        PackUi.fromIntent(this, i);   // test app: --es pack_title <id> --es pack_export <folder> | --es pack_import <zip or folder>
        String[] fg = FrontendLaunch.take(i.getStringExtra("frontend_token"));   // FrontendLaunch: a game it could not start at once, handed over in this process
        if (fg != null) { fromFrontend(new File(fg[0]), fg[1].equals("card")); return; }
        String ag = i.getStringExtra("add_game");   // Add game's own path for a picked folder or .nup (gamePicked), without the picker
        if (ag != null) { if (!needAccess()) gamePicked(new File(ag)); return; }
        String game = i.getStringExtra("game");
        if (game == null) return;
        File dir = new File(game);   // a game folder or a .nup (NUP.md)
        String id;
        try { id = Pack.titleIdOf(dir); } catch (IOException e) { error(e.getLocalizedMessage()); return; }
        String p = i.getStringExtra("pack");
        File pack = p != null ? new File(p) : Folders.packFor(this, dir, id);
        if (pack == null) { error(getString(R.string.home_no_pack_next_to, id, game)); return; }
        if (!addWithPack(dir, id, pack)) return;
        String save = i.getStringExtra("save");
        if (save != null) Prefs.folder(this, "save_" + id, new File(save).getPath());
        if (i.getBooleanExtra("run", false)) for (Game g : games) if (GameList.samePath(g.gameDir, dir.getPath())) { launch(g); break; }
    }

    /**
     * A game a frontend sent that FrontendLaunch could not start at once. A card (card: the game path is the card's): Play, as its button runs it, with no question
     * in front: Play's own steps say what is missing and offer the fix (a pack, Prepare, a shader update), and a game it starts brings the frontend back when it
     * stops. A file that is no card must be one game (not a folder of several, which Add game would add all of), and it never takes the place of a card the user
     * has without being named: a card of the same title and build (which Add game would replace with no question, GameList.matches) is that game, so that card
     * plays, with its own files and pack, and the file is not added; else Home asks before Add game (askAdd), naming another dump of the title on Home, and Add
     * game then asks whether this one replaces it or both stay
     */
    private void fromFrontend(File game, boolean card) {
        if (settingsPage) page(false);
        refresh();
        if (card) {
            for (Game x : games) if (GameList.samePath(x.gameDir, game.getPath())) { launch(x, true); return; }
            return;   // the card went in the meantime
        }
        if (needAccess()) return;
        new Thread(() -> {   // reads the file (a .nup's TOC is hashed for its build): off the main thread
            File one = Pack.npdmOf(game) != null || Nup.isNup(game) || Nup.wrongCase(game) ? game : null;
            if (one == null) {
                List<File> found = PackFind.gamesIn(game);
                if (found.size() == 1) one = found.get(0);
            }
            Game same = null;
            List<Game> others = new ArrayList<>();
            if (one != null) {
                String id;
                try { id = Pack.titleIdOf(one); } catch (IOException e) { id = null; }   // Add game says why it cannot be used
                if (id != null) {
                    String key = Game.key(one.getPath(), id);
                    List<Game> list = Game.load(this);
                    List<Integer> m = GameList.matches(list, id, one.getPath(), key, Game::key);
                    if (!m.isEmpty()) same = list.get(m.get(0));
                    else for (int i : GameList.others(list, id, one.getPath(), key, Game::key)) others.add(list.get(i));
                }
            }
            File f = one;
            Game s = same;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (f == null) { error(getString(R.string.home_frontend_not_one_game, getString(R.string.app_name), game)); return; }
                if (s != null) { launch(s, true); return; }
                askAdd(f, others);
            });
        }, "nx-frontend-add").start();
    }

    /** Home's question before Add game takes a frontend's file that is no card, naming the other dumps of its title on Home (others) */
    private void askAdd(File file, List<Game> others) {
        String msg;
        if (others.isEmpty())
            msg = getString(R.string.home_frontend_add_body, getString(R.string.app_name), file.getPath(), getString(R.string.home_add), getString(R.string.home_add_game));
        else {
            StringBuilder list = new StringBuilder();
            for (Game o : others) list.append(list.length() == 0 ? "" : "\n").append(getString(R.string.home_frontend_other_dump_item, o.gameDir, o.packDir));
            msg = getString(R.string.home_frontend_add_body_others, getString(R.string.app_name), file.getPath(), getString(R.string.home_add), list.toString(), getString(R.string.home_add_game));
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.home_frontend_add_title))
                .setMessage(msg)
                .setPositiveButton(getString(R.string.home_add), (x, w) -> gamePicked(file))
                .setNegativeButton(android.R.string.cancel, null).create();
        d.setOnShowListener(x -> {
            filterTaps(d);
            Button n = d.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (n != null) n.requestFocus();
        });
        d.show();
    }

    /** A dialog another app opened (a frontend's request): its buttons ignore taps that land through another app's overlay, so no hidden tap gets past it */
    private static void filterTaps(AlertDialog d) {
        for (int b : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) if (d.getButton(b) != null) d.getButton(b).setFilterTouchesWhenObscured(true);
    }

    /** One of Play's dialogs; frontend: a frontend's request opened it (launch(g, true)), so its buttons filter taps as askAdd's do (filterTaps) */
    private static void showPlay(AlertDialog.Builder b, boolean frontend) {
        AlertDialog d = b.create();
        if (frontend) d.setOnShowListener(x -> filterTaps(d));
        d.show();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (gameBox != null && Game.load(this).size() != games.size()) refresh();   // the list changed while Home was in the background
        onResumeViews();
        Ondevice.resume(this);
        Updater.resume(this);   // back from "Install unknown apps" with an update download waiting
    }

    private void onResumeViews() {
        if (accessCard != null) accessCard.setVisibility(Environment.isExternalStorageManager() ? View.GONE : View.VISIBLE);
        bananas.setRunning(true);
        refreshSettings();
    }

    /** The bananas stop while the screen is covered (a game, the self-test, settings) or off */
    @Override protected void onPause() {
        resumed = false;
        bananas.setRunning(false);
        Ondevice.pause(this);
        super.onPause();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Home's games: a heading with Add game over one card per game (also over no card while a game is being prepared); with none, a single card that says what to do */
    void refresh() {
        games.clear();
        games.addAll(Game.load(this));
        if (gameBox == null) return;
        if (!merged && games.size() > 1) {   // a list an older version left with two entries of one game: one card each (GameList.collapse; reads the dumps: off the main thread)
            merged = true;
            new Thread(() -> { if (Game.collapse(this)) runOnUiThread(() -> { if (!isFinishing()) refresh(); }); }, "nx-list-merge").start();
        }
        gameBox.removeAllViews();
        if (games.isEmpty() && !Ondevice.showing()) { gameBox.addView(emptyCard()); return; }
        // while a game is being prepared its card says what happens (the steps card would only repeat it), and the heading's Add game still adds another game
        LinearLayout top = Ui.row(this);
        TextView h = Ui.section(this, getString(R.string.home_your_games));
        top.addView(h, Ui.weight());
        Button add = Ui.button(this, getString(R.string.home_add_game), Ui.Kind.QUIET, v -> addGame());
        add.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(this, R.drawable.ic_add, R.color.bn_accent), null, null, null);
        add.setCompoundDrawablePadding(dp(6));
        top.addView(add);
        gameBox.addView(top);
        for (Game g : games) Ui.add(gameBox, gameCard(g), gameBox.getChildCount() == 1 ? 4 : Ui.GAP);
    }

    /** No game yet: the mark, the three steps, and Add game */
    private View emptyCard() {
        LinearLayout c = Ui.card(this);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(20), dp(28), dp(20), dp(20));
        ImageView im = new ImageView(this);
        im.setImageResource(R.drawable.anka_mark);
        im.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        c.addView(im, new LinearLayout.LayoutParams(dp(80), dp(80)));
        TextView h = Ui.heading(this, getString(R.string.home_empty_title));
        h.setGravity(Gravity.CENTER);
        Ui.add(c, h, 12, Ui.wrap());
        TextView t = Ui.small(this, Ondevice.available() ? getString(R.string.home_empty_body) : getString(R.string.home_empty_body_pc));
        t.setGravity(Gravity.CENTER);
        Ui.add(c, t, 6, Ui.wrap());
        LinearLayout steps = Ui.row(this);
        steps.setGravity(Gravity.CENTER);
        String[] s = Ondevice.available() ? new String[] {getString(R.string.home_step_pick_dump), getString(R.string.home_step_prepare), getString(R.string.home_step_play)}
                : new String[] {getString(R.string.home_step_prepare_pc), getString(R.string.home_step_pick_dump), getString(R.string.home_step_play)};
        for (int k = 0; k < s.length; k++) {
            LinearLayout.LayoutParams lp = Ui.wrap();
            if (k > 0) lp.leftMargin = dp(6);
            TextView chip = Ui.chip(this, (k + 1) + "  " + s[k]);   // the step's number, two spaces, its label
            chip.setSingleLine(true);
            steps.addView(chip, lp);
        }
        Ui.add(c, steps, 16, Ui.wrap());
        Ui.add(c, Ui.action(this, R.drawable.ic_add, getString(R.string.home_add_game), Ui.Kind.PRIMARY, v -> addGame()), 20);
        return c;
    }

    /** A game's card: its tile, name and state, ⋯ Manage at the top right (far from the big button), and one big button: Play, or Prepare when the pack does not fit */
    private View gameCard(Game g) {
        LinearLayout c = Ui.card(this);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout top = Ui.row(this);
        top.addView(Ui.tile(this, g.name, 52));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(14), 0, dp(4), 0);
        TextView name = Ui.text(this, g.name, 17, R.color.bn_text, 700);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        boolean other = Profile.of(g.titleId) == null;   // no profile in this APK: it cannot run here (Profile.missing says why)
        TextView st = other ? Ui.status(this, getString(R.string.home_state_unsupported), R.color.bn_danger) : Ui.status(this, getString(R.string.home_state_checking), R.color.bn_text_2);
        LinearLayout.LayoutParams sp = Ui.wrap();
        sp.topMargin = dp(2);
        info.addView(st, sp);
        top.addView(info, Ui.weight());
        top.addView(Ui.more(this, getString(R.string.home_manage_game, g.name), v -> manage(g)));
        c.addView(top);
        Ui.Action play = Ui.action(this, R.drawable.ic_play, getString(R.string.home_play), Ui.Kind.PRIMARY, v -> launch(g));
        Ui.add(c, play, 16);
        if (!other) check(g, st, play, info);
        c.setOnLongClickListener(v -> { manage(g); return true; });
        return c;
    }

    /** Why a game cannot start as it is, in a few words (unfinished or busy pack code, another game build, no current shader library), and whether Prepare fixes it */
    private static final class Problem {
        final String label;
        final boolean prepare;   // Prepare (on the phone, from the dump) makes what is missing
        boolean shadersOnly;   // only the shader library is missing
        boolean playable;      // ... and an older library of the same ABI plays while the update makes it (Pack.playableLibrary)
        boolean legacy;        // ... or only one made before versioned updates, played with a warning (Pack.legacyLibrary)
        String detail = "";    // what was compared (BuildCheck: the build IDs that differ), shown small under the state and logged

        Problem(String label, boolean prepare) { this.label = label; this.prepare = prepare; }
    }

    /** The card's short state when the game cannot start as it is; null when it can */
    private Problem problem(Game g) {
        File pd = new File(g.packDir), gd = new File(g.gameDir);
        boolean dump = Ondevice.isDump(gd);
        Pack p;
        String needsPrepare = getString(R.string.home_state_needs_prepare), noPack = getString(R.string.home_state_no_pack);
        try { p = Pack.read(pd); } catch (IOException e) { return new Problem(dump ? needsPrepare : noPack, dump); }
        if (p.simulated) return new Problem(dump ? needsPrepare : noPack, dump);   // a simulated engine's placeholder (0.1.0's Prepare made one): never started
        if (p.unfinishedModule() != null) return new Problem(dump ? needsPrepare : getString(R.string.home_state_pack_unfinished), dump);
        if (PackCheck.rewriting(this, pd, gd, p.titleId) != null) return new Problem(getString(R.string.home_state_preparing), false);
        BuildCheck.Result b = BuildCheck.build(p, gd);
        if (!b.ok()) {
            android.util.Log.w("nxnative", g.titleId + " " + g.gameDir + " with pack " + g.packDir + ": " + b.label() + ": " + b.detail);
            Problem s = new Problem(b.labelMsg().text(), dump && (b.kind == BuildCheck.Kind.OTHER_BUILD || b.kind == BuildCheck.Kind.MISSING));   // Prepare makes a pack of the dump's build; it cannot fix unreadable files, an unsupported build or another version's data
            s.detail = b.detailMsg == null ? "" : b.detailMsg.text();
            return s;
        }
        if (Pack.libraryProblem(pd, g.titleId) != null) {
            Problem s = new Problem(getString(R.string.home_state_shaders_needed), dump);
            s.shadersOnly = true;
            s.playable = Pack.playableLibrary(pd, g.titleId, dump) != null;
            s.legacy = !s.playable && Pack.legacyLibrary(pd, g.titleId) != null;
            return s;
        }
        return null;
    }

    /** The text of a playable older library's card state (GameGate.Line): only "updating shaders" while its update runs or was just started */
    private static int updateLine(GameGate.Line l) {
        switch (l) {
            case UPDATING: return R.string.home_state_ready_updating;
            case GAME: return R.string.home_state_ready_update_game;
            case PAUSED: return R.string.home_state_ready_update_paused;
            default: return R.string.home_state_ready_older;
        }
    }

    /** The card's state, checked off the main thread (NSO headers, the stamp, and for a ready pack the rewriter's key, which hashes the modules) */
    private void check(Game g, TextView st, Ui.Action play, LinearLayout info) {
        new Thread(() -> {
            // An APK update whose shaders did not change: the pack's older library becomes this version's by its header (Pack.restamp), before anything else is decided
            String restamped = Pack.restamp(new File(g.packDir), g.titleId);
            if (restamped != null) android.util.Log.i("nxnative", g.titleId + ": shader library v-" + restamped + " taken over as v-" + BuildConfig.NX_DMSH_VK_CHAIN + " (restamp)");
            Problem problem = problem(g);
            Boolean ok = null;
            if (problem == null) try { ok = PackCheck.code(this, Pack.read(new File(g.packDir)), new File(g.gameDir)); } catch (IOException e) { ok = null; }
            Boolean code = ok;
            runOnUiThread(() -> {
                if (info.getParent() == null) return;
                // An APK update's new shaders: made in the background with no question when the pack's older library is one the delta manifest knows (Ondevice.autoUpdate)
                boolean queued = problem != null && problem.shadersOnly && problem.prepare && Ondevice.autoUpdate(this, new File(g.gameDir), new File(g.packDir), g.titleId);
                if (problem == null) Ui.status(st, getString(R.string.home_state_ready), R.color.bn_leaf);
                else if (problem.playable) Ui.status(st, getString(updateLine(Ondevice.updateLine(new File(g.packDir), queued))), R.color.bn_leaf);   // how its update stands (GameGate.card)
                else if (problem.legacy) Ui.status(st, getString(R.string.home_state_ready_legacy), R.color.bn_accent);
                else Ui.status(st, problem.label, problem.prepare ? R.color.bn_accent : R.color.bn_danger);
                if (problem != null && !problem.detail.isEmpty()) {   // which build IDs (or which file) the state is about
                    TextView d = Ui.small(this, problem.detail);
                    d.setTextIsSelectable(true);
                    d.setTextSize(11);
                    LinearLayout.LayoutParams dl = Ui.wrap();
                    dl.topMargin = dp(2);
                    info.addView(d, dl);
                }
                if (problem != null && problem.legacy) {   // Play stays Play; a chip says why the shaders want one more update
                    TextView n = Ui.warnChip(this, getString(R.string.home_legacy_chip), getString(R.string.home_legacy_chip), ShaderLibs.legacyWarning());
                    LinearLayout.LayoutParams wl = Ui.wrap();
                    wl.topMargin = dp(6);
                    info.addView(n, wl);
                }
                if (problem != null && problem.prepare && !problem.playable && !problem.legacy) {   // the big button prepares (Ondevice asks first and says what it will make)
                    play.setText(problem.shadersOnly ? getString(R.string.home_prepare_shaders) : getString(R.string.home_prepare));
                    play.setIcon(R.drawable.ic_build);
                    play.setOnClickListener(v -> { if (!needAccess()) Ondevice.prepare(this, new File(g.gameDir), false); });
                }
                if (!Boolean.FALSE.equals(code)) return;
                // A pack whose code other tools made: a small chip; its tap says what that means
                TextView n = Ui.warnChip(this, getString(R.string.home_foreign_pack), getString(R.string.home_foreign_pack),
                        getString(R.string.home_foreign_pack_body, getString(R.string.home_prepare_again)));
                LinearLayout.LayoutParams lp = Ui.wrap();
                lp.topMargin = dp(6);
                info.addView(n, lp);
            });
        }, "nx-pack-check").start();
    }

    /**
     * A game's Manage sheet (⋯ on its card, or a long press): a list of actions (its folders, its settings, its log, its saves, Prepare again for a dump), its facts
     * (title ID, folders) below, and, apart at the bottom, Remove from the list (asked first; it deletes nothing on the storage). Play never sits next to it.
     */
    private void manage(Game g) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        File save = Folders.save(this, g);
        String lp = text(Pack.libraryProblemMsg(new File(g.packDir), g.titleId));
        Msg noProfile = Profile.missingMsg(g.titleId, g.name);
        AlertDialog[] d = new AlertDialog[1];
        if (noProfile != null) Ui.add(box, Ui.small(this, noProfile.text()), 0);
        // Saves: backups in <Switch2>/save-backups/<id>/, verified; a restore backs up the current save first (SaveUi, SaveStore)
        if (noProfile == null) Ui.add(box, new Ui.Setting(this, "⚙️", getString(R.string.home_game_settings), v -> { d[0].dismiss(); GameSettings.dialog(this, g); }).chevron(this).view, 0);
        Ui.add(box, new Ui.Setting(this, "📁", getString(R.string.home_folders), v -> { d[0].dismiss(); Folders.dialog(this, g); }).chevron(this).view, 0);
        Ui.add(box, new Ui.Setting(this, "💾", getString(R.string.home_backup_saves), v -> { d[0].dismiss(); SaveUi.backup(this, g); }).value(getString(R.string.home_backup_to, SaveUi.backups(g))).chevron(this).view, 0);
        Ui.add(box, new Ui.Setting(this, "↩️", getString(R.string.home_restore_saves), v -> { d[0].dismiss(); SaveUi.restoreList(this, g); }).chevron(this).view, 0);
        Ui.add(box, new Ui.Setting(this, "📥", getString(R.string.home_import_save), v -> { d[0].dismiss(); SaveUi.importPick(this, g); }).chevron(this).view, 0);
        if (Ondevice.isDump(new File(g.gameDir)) && noProfile == null)
            Ui.add(box, new Ui.Setting(this, "🔁", getString(R.string.home_prepare_again), v -> { d[0].dismiss(); Ondevice.prepareAgain(this, new File(g.gameDir), new File(g.packDir)); }).value(getString(R.string.home_prepare_again_value)).chevron(this).view, 0);
        // The pack as one .zip, out and in (PackUi, PackZip): an import goes into the app's packs/<id> and is checked before it replaces anything
        Ui.add(box, new Ui.Setting(this, "📦", getString(R.string.home_export_pack), v -> { d[0].dismiss(); PackUi.exportPick(this, g); }).value(getString(R.string.home_export_pack_value)).chevron(this).view, 0);
        if (noProfile == null)
            Ui.add(box, new Ui.Setting(this, "📲", getString(R.string.home_import_pack), v -> { d[0].dismiss(); PackUi.importPick(this, g); }).value(getString(R.string.home_import_pack_value)).chevron(this).view, 0);
        Ui.add(box, new Ui.Setting(this, "📨", getString(R.string.home_share_log), v -> { d[0].dismiss(); shareLog(g.titleId); }).chevron(this).view, 0);
        TextView info = Ui.small(this, getString(R.string.home_manage_facts, g.titleId, Nup.isNup(new File(g.gameDir)) ? getString(R.string.home_manage_game_nup, g.gameDir) : g.gameDir,
                g.packDir, lp == null ? getString(R.string.home_manage_shaders_present, BuildConfig.NX_DMSH_VK_CHAIN) : lp, save != null ? save.getPath() : getString(R.string.home_manage_saves_not_set)));
        info.setTextIsSelectable(true);
        info.setTextSize(12);
        info.setPadding(dp(4), 0, dp(4), 0);
        Ui.add(box, info, 16);
        Ui.add(box, Ui.divider(this), 16, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        Ui.add(box, Ui.button(this, getString(R.string.home_remove), Ui.Kind.DANGER, v -> { d[0].dismiss(); confirmRemove(g); }), 16);
        d[0] = Ui.sheet(this, g.name, box).setPositiveButton(R.string.home_done, null).show();
    }

    private void openAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    boolean needAccess() {
        if (Environment.isExternalStorageManager()) return false;
        new AlertDialog.Builder(this).setTitle(R.string.home_access_title).setMessage(R.string.home_access_needed)
                .setPositiveButton(R.string.home_open_settings, (d, w) -> openAccessSettings()).setNegativeButton(android.R.string.cancel, null).show();
        return true;
    }

    void pickFolder(int request, String title) {
        Toast.makeText(this, title, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        startActivityForResult(i, request);
    }

    /** Add game: the dump is a folder (base/, updates/) or one .nup file (NUP.md); the user says which, then the system picker opens for it */
    void addGame() {
        if (needAccess()) return;
        new AlertDialog.Builder(this).setTitle(R.string.home_add_game_kind)
                .setItems(new String[] {getString(R.string.home_add_game_folder), getString(R.string.home_add_game_nup)}, (d, w) -> {
                    if (w == 0) pickFolder(PICK_GAME, getString(R.string.home_pick_dump_folder));
                    else pickNup();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** The system file picker for a .nup; its document is turned into its /storage path (PathUtil.documentToPath), as a picked folder is */
    private void pickNup() {
        Toast.makeText(this, getString(R.string.home_pick_nup), Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        startActivityForResult(i, PICK_NUP);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (Ondevice.activityResult(this, request, result, data)) return;
        if (request == ReadinessActivity.REQUEST) { if (result == ReadinessActivity.FIX_DRIVER) GpuDriver.dialog(this, PICK_DRIVER); return; }   // its "Pick a GPU driver"
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_DRIVER) { GpuDriver.picked(this, data.getData()); return; }
        if (PackUi.documentResult(this, request, data.getData())) return;
        if (request == PICK_NUP) {
            String p = PathUtil.documentToPath(data.getData());
            if (p == null) { error(getString(R.string.home_nup_not_on_storage, data.getData())); return; }
            gamePicked(new File(p));
            return;
        }
        if (request == PICK_DLL) {
            String err = Lsfg.install(this, data.getData());
            Toast.makeText(this, err == null ? getString(R.string.home_dll_installed) : getString(R.string.home_dll_not_installed, err), Toast.LENGTH_LONG).show();
            return;
        }
        String path = PathUtil.treeToPath(data.getData());
        if (path == null) { error(getString(R.string.home_folder_not_on_storage, data.getData())); return; }
        File dir = new File(path);
        if (Folders.activityResult(this, request, dir)) return;
        if (SaveUi.activityResult(this, request, dir)) return;
        if (PackUi.activityResult(this, request, dir)) return;
        if (request == PICK_GAME) gamePicked(dir);
        else if (request == PICK_PACK && pendingGame != null) { pendingPackPick = dir; addWithPack(pendingGame, pendingId, dir); }
    }

    /**
     * A picked game: one .nup, one dump folder (base/exefs/main.npdm or exefs/main.npdm), or a folder that holds games (PackFind.gamesIn: its .nup files and
     * dump subfolders), all of which are added (addAll); a folder with one game is that game
     */
    private void gamePicked(File game) {
        if (Nup.isNup(game) || Nup.wrongCase(game)) { nupPicked(game); return; }
        if (Pack.npdmOf(game) != null) { addAll(List.of(game), true); return; }
        List<File> found = PackFind.gamesIn(game);
        if (found.size() == 1) { gamePicked(found.get(0)); return; }
        if (found.isEmpty()) {
            error(getString(R.string.home_no_game_in_folder, game));
            return;
        }
        addAll(found, false);
    }

    /** A single .nup (NUP.md): its name and access checked here, the rest as every game (addAll) */
    private void nupPicked(File nup) {
        if (Nup.wrongCase(nup)) { error(getString(R.string.home_nup_wrong_case, nup.getName())); return; }
        if (!nup.canRead()) { error(getString(R.string.home_file_unreadable, nup)); return; }
        addAll(List.of(nup), true);
    }

    /** What the background check of Add game found for one game */
    private static final class Pick {
        final File game;
        String id, name;
        Object error;             // why it cannot be added (unreadable, no profile in this APK), in a sentence: a Msg, or an English String of another area (why, shown)
        boolean unsupported;      // no profile in this APK
        boolean dump;             // a dump Prepare starts from (Pack.isDump)
        PackFind.Found pack;      // a pack that plays now or after the automatic shader update (Folders.find), null: none
        DumpLayout.Found found;   // without a pack: the version Prepare would start from, or why it cannot (DumpLayout.find)
        String key;               // its game build (Game.key: title, version, main's build ID), null when it cannot be told
        final List<Game> others = new ArrayList<>();   // Home's entries of the same title that are another dump (GameList.others): the user decides

        Pick(File game) { this.game = game; name = game.getName(); }
    }

    /**
     * Add game for one or more games: each is identified off the main thread (a .nup's TOC is hashed) with its best pack (Folders.find: the picked folder,
     * the app's, beside the game, &lt;id&gt;-work/&lt;any&gt; above it) and checked (PackCheck.blocking), then added on the main thread. A game with a pack that plays
     * now or after the automatic shader update is added with it, with no question. single: one picked game, as before: a dump without such a pack goes to Prepare
     * (Ondevice asks first; it offers an existing pack folder or Import pack… too), a one-version folder to the pack folder picker, and a problem is one message.
     * Several (a picked folder's games): a dump without a usable pack gets a card with its Prepare button (one job at a time is the user's to start), and one
     * message sums up what was added, what this app cannot run and what needs Prepare.
     * One card per game (GameList): the same file or the same title and build replaces its entry; another dump of a title on the list is asked about (single:
     * replace it or keep both) or kept beside it (several, said in the summary)
     */
    private void addAll(List<File> picked, boolean single) {
        Toast.makeText(this, single ? getString(R.string.home_checking_game, picked.get(0).getName())
                : getResources().getQuantityString(R.plurals.home_checking_games, picked.size(), picked.size()), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Pick> out = new ArrayList<>();
            for (File g : picked) {
                Pick k = new Pick(g);
                out.add(k);
                if (Nup.wrongCase(g)) { k.error = new Msg("home_nup_wrong_case_short", g.getName()); continue; }
                try { k.id = Pack.titleIdOf(g); } catch (IOException e) { k.error = why(e); continue; }
                Msg why = Profile.missingMsg(k.id, single ? new Msg("home_this_game") : g.getName());
                if (why != null) { k.unsupported = true; k.error = why; continue; }
                k.name = Profile.of(k.id).name;
                k.dump = Ondevice.isDump(g);   // false when this APK has no packer: then it is added like any folder without a pack
                String pp = Folders.pickedPack(this, k.id);
                File pd = pp == null ? null : new File(pp);
                try { if (pd != null && !Pack.read(pd).titleId.equals(k.id)) pd = null; } catch (IOException e) { pd = null; }
                k.pack = Folders.find(this, pd, g, k.id);
                if (k.pack == null) k.found = DumpLayout.find(g);   // no pack: whether Prepare can start from it (any layout), or why not (another build, incomplete)
                else if (!k.pack.pack.gameVersion(g).equals(k.pack.pack.version))   // the pack's build under another version number of this dump: the same code, played as it is
                    android.util.Log.i("nxnative", "Add game: " + g.getName() + " keeps the pack's build (version " + k.pack.pack.version + ") as version " + k.pack.pack.gameVersion(g) + ": same build IDs, same code");
                k.key = Game.key(g.getPath(), k.id);   // read here, off the main thread: the add takes the keys from the cache
                List<Game> list = Game.load(this);
                for (int i : GameList.others(list, k.id, g.getPath(), k.key, Game::key)) k.others.add(list.get(i));
                android.util.Log.i("nxnative", "Add game: " + g + " (" + k.id + ", " + k.key + "): " + (k.pack == null ? "no usable pack" : "pack " + k.pack.dir + (k.pack.tier == PackFind.READY ? " (ready)" : " (shader update)"))
                        + (k.others.isEmpty() ? "" : "; " + k.others.size() + " other dump(s) of it on the list"));
            }
            runOnUiThread(() -> { if (!isFinishing()) added(out, single); });
        }, "nx-add-game").start();
    }

    private void added(List<Pick> picks, boolean single) {
        if (single) {
            Pick k = picks.get(0);
            if (k.error != null) { error(shown(k.error)); return; }
            askReplace(k.name, k.game, k.others, replace -> {
                if (k.pack != null) { addWithPack(k.game, k.id, k.pack.dir, replace); return; }
                if (replace != null) removeEntries(replace);   // the user chose this dump: the other's card goes now (nothing is deleted), this one's comes with its pack
                if (Ondevice.available() && k.found != null && k.found.error == null) {
                    if (k.found.build >= 0 && k.found.build != k.found.version)
                        android.util.Log.i("nxnative", "Add game: " + k.game.getName() + " " + DumpLayout.name(k.found.version) + " is " + Profile.of(k.id).buildName(k.found.build) + " by its build IDs");
                    Ondevice.prepare(this, k.game, false);
                    return;
                }   // a dump of a supported build: Prepare makes its pack
                pendingGame = k.game;
                pendingId = k.id;
                boolean nup = Nup.isNup(k.game);
                // Why Prepare cannot start from it (another build than this app's, an incomplete dump), plainly; a pack made elsewhere can still be picked
                String why = k.found != null && k.found.error != null ? k.found.errorMsg.text()
                        : !Ondevice.available() ? (nup ? getString(R.string.home_no_pack_nup_unavailable, Ondevice.unavailableText()) : getString(R.string.home_no_pack_folder_unavailable, Ondevice.unavailableText()))
                        : nup ? getString(R.string.home_no_pack_nup_no_dump) : getString(R.string.home_no_pack_folder_no_dump);
                new AlertDialog.Builder(this).setTitle(k.found != null && k.found.version < 0 && k.found.titleId != null && Profile.of(k.found.titleId) != null && !DumpLayout.versions(k.game).isEmpty()
                                ? getString(R.string.home_other_version_title, k.name) : nup ? getString(R.string.home_no_pack_nup_title) : getString(R.string.home_no_pack_folder_title))
                        .setMessage(nup ? getString(R.string.home_no_pack_nup_body, why) : getString(R.string.home_no_pack_folder_body, why))
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.home_pick_a_pack_folder, (d, w) -> pickFolder(PICK_PACK, getString(R.string.home_pick_android_pack))).show();
            });
            return;
        }
        List<String> ok = new ArrayList<>(), unsupported = new ArrayList<>(), prepare = new ArrayList<>(), noPack = new ArrayList<>(), twice = new ArrayList<>();
        List<Object> failed = new ArrayList<>();   // Msgs, or English Strings of another area: rendered for the dialog and, in English, for the log
        for (Pick k : picks) {
            if (k.unsupported) { unsupported.add(k.game.getName() + " (" + k.id + ")"); continue; }
            if (k.error != null) { failed.add(k.error); continue; }
            if (k.pack != null) {
                Object err = add(k.game, k.id, k.pack.dir, null);
                if (err == null) ok.add(k.pack.pack.name.isEmpty() ? k.name : k.pack.pack.name);
                else failed.add(new Msg("home_added_failed_item", k.game.getName(), err));
            } else if (k.dump) {
                cardFor(k.game, k.id);
                prepare.add(k.name);
            } else noPack.add(k.game.getName());
            if (!k.others.isEmpty() && (k.pack != null || k.dump)) twice.add(k.game.getName());
        }
        refresh();
        // The summary's paragraphs: the dialog shows them in the user's language, the log line keeps them English
        List<Msg> m = new ArrayList<>();
        if (!ok.isEmpty()) m.add(Msg.plural("home_added_ok", ok.size(), TextUtils.join(", ", ok)));
        if (!prepare.isEmpty()) m.add(Msg.plural("home_added_prepare", prepare.size(), TextUtils.join(", ", prepare), new Msg("home_prepare")));
        if (!unsupported.isEmpty()) m.add(new Msg("home_added_unsupported", TextUtils.join(", ", unsupported), TextUtils.join(", ", profileIds())));
        if (!noPack.isEmpty()) m.add(Msg.plural("home_added_no_pack", noPack.size(), TextUtils.join(", ", noPack)));
        List<String> shownM = new ArrayList<>(), englishM = new ArrayList<>();
        for (Msg x : m) { shownM.add(x.text()); englishM.add(x.english()); }
        if (!failed.isEmpty()) {   // each why rendered on its own (a Msg, or another area's English), then joined in each language
            List<String> shownFailed = new ArrayList<>(), englishFailed = new ArrayList<>();
            for (Object f : failed) { shownFailed.add(shown(f)); englishFailed.add(english(f)); }
            shownM.add(getString(R.string.home_added_failed, TextUtils.join(" ", shownFailed)));
            englishM.add(Tr.english("home_added_failed", TextUtils.join(" ", englishFailed)));
        }
        if (!twice.isEmpty()) {
            Msg t = new Msg("home_added_twice", TextUtils.join(", ", twice), new Msg("home_remove"));
            shownM.add(t.text());
            englishM.add(t.english());
        }
        android.util.Log.i("nxnative", "Add game: " + TextUtils.join("\n\n", englishM).replace("\n\n", " | "));
        new AlertDialog.Builder(this).setTitle(ok.isEmpty() && prepare.isEmpty() ? R.string.home_added_none_title : R.string.home_added_title)
                .setMessage(TextUtils.join("\n\n", shownM)).setPositiveButton(android.R.string.ok, null).show();
    }

    /**
     * Another dump of a game that is on the list (another file, another build or one that cannot be told): asks whether this one replaces it (its list entry
     * only; nothing is deleted) or both are kept. then gets the entries' game paths to replace, or null to keep them; nothing to ask: null at once
     */
    private void askReplace(String name, File game, List<Game> others, java.util.function.Consumer<List<String>> then) {
        if (others.isEmpty()) { then.accept(null); return; }
        List<String> dirs = new ArrayList<>();
        StringBuilder s = new StringBuilder();
        for (Game o : others) { dirs.add(o.gameDir); s.append("\n• ").append(o.gameDir); }   // i18n-keep: a list of paths, one a line
        new AlertDialog.Builder(this).setTitle(getString(R.string.home_replace_title, name))
                .setMessage(getString(R.string.home_replace_body, name, s, game.getName()))
                .setPositiveButton(R.string.home_replace, (d, w) -> then.accept(dirs))
                .setNeutralButton(R.string.home_keep_both, (d, w) -> then.accept(null))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** These game paths' list entries go (exact paths, as the list keeps them); nothing on the storage changes */
    private void removeEntries(List<String> gameDirs) {
        synchronized (Game.class) {
            List<Game> list = Game.load(this);
            list.removeIf(x -> gameDirs.contains(x.gameDir));
            Game.save(this, list);
        }
        refresh();
    }

    private static List<String> profileIds() {
        List<String> ids = new ArrayList<>();
        for (Profile p : Profile.all()) ids.add(p.titleId);
        return ids;
    }

    /**
     * The list entry of a game, made when it has none: a game that still needs its pack gets a card whose pack folder is the app's packs/&lt;id&gt;, where Prepare
     * and Import pack… put it (the card says "Needs preparing" and its button prepares). An existing entry of the same game (the same file, or the same title and
     * build: GameList.matches) is kept as it is
     */
    Game cardFor(File game, String id) {
        List<Game> list = Game.load(this);
        List<Integer> same = GameList.matches(list, id, game.getPath(), Game.key(game.getPath(), id), Game::key);
        if (!same.isEmpty()) return list.get(same.get(0));
        Profile p = Profile.of(id);
        Game g = Game.put(this, new Game(id, p == null ? id : p.name, game.getPath(), AppStorage.pack(this, id).getPath()));
        refresh();
        return g;
    }

    /** Prepare's "Use an existing pack…": the picked pack folder becomes the game's (addWithPack; remembered for this title like a pack picked in Folders) */
    void pickPackFor(File game, String id) {
        pendingGame = game;
        pendingId = id;
        pickFolder(PICK_PACK, getString(R.string.home_pick_pack_folder));
    }

    private boolean addWithPack(File game, String id, File packDir) { return addWithPack(game, id, packDir, null); }

    private boolean addWithPack(File game, String id, File packDir, List<String> replace) {
        Object err = add(game, id, packDir, replace);
        if (err != null) { error(shown(err)); return false; }
        refresh();
        if (packDir.equals(pendingPackPick)) Folders.pickedPack(this, id, packDir.getPath());   // a pack folder the user picked is remembered for this game's next pick and updates
        String lp = text(Pack.libraryProblemMsg(packDir, id));
        String name;
        try { name = Pack.read(packDir).name; } catch (IOException e) { name = id; }
        if (name.isEmpty()) name = id;
        Toast.makeText(this, lp == null ? getString(R.string.home_added_game, name) : getString(R.string.home_added_game_note, name, lp), Toast.LENGTH_LONG).show();
        return true;
    }

    /**
     * The game into the list with this pack folder (Game.put: replacing the entries of the same file or the same title and build, and those in replace, which
     * the user chose to replace); null when added, else why not in a sentence: a Msg, or an English String of another area (shown, english)
     */
    private Object add(File game, String id, File packDir, List<String> replace) {
        Pack pack;
        try { pack = Pack.read(packDir); } catch (IOException e) { return why(e); }
        if (!pack.titleId.equals(id)) return Nup.isNup(game) ? new Msg("home_pack_other_title_nup", pack.titleId, id) : new Msg("home_pack_other_title_folder", pack.titleId, id);
        Msg why = Profile.missingMsg(id, pack.name.isEmpty() ? null : pack.name);
        if (why != null) return why;
        if (Nup.isNup(game)) {
            String romfs;
            try { romfs = pack.romfsPath(game); } catch (IOException e) { return new Msg("home_game_unusable", game.getName(), why(e)); }
            if (romfs == null) return new Msg("home_nup_no_romfs", game.getName(), pack.romfsSelector(), pack.version);
        } else if (pack.romfs(game) == null) return new Msg("home_folder_no_romfs", game, pack.romfsRel);
        Game.put(this, new Game(id, pack.name.isEmpty() ? id : pack.name, game.getPath(), packDir.getPath()), replace);
        return null;
    }

    /** An exception's message as add() and Pick keep it: a LocalizedIOException's Msg, else its (English) message */
    private static Object why(Exception e) {
        return e instanceof LocalizedIOException && ((LocalizedIOException) e).msg != null ? ((LocalizedIOException) e).msg : e.getMessage();
    }

    /** A why of add() or Pick for the screen: a Msg in the user's language, a String as it is */
    private static String shown(Object why) { return why instanceof Msg ? ((Msg) why).text() : String.valueOf(why); }

    /** The same for a log line: English */
    private static String english(Object why) { return why instanceof Msg ? ((Msg) why).english() : String.valueOf(why); }

    /** A Msg of another area (a pack's problem) for the screen, null for null */
    private static String text(Msg m) { return m == null ? null : m.text(); }

    /** Remove from the list (the Manage sheet): says exactly what goes (the list entry) and what stays (the folders and the saves) */
    private void confirmRemove(Game g) {
        File save = Folders.save(this, g);
        Ui.confirmDanger(this, getString(R.string.home_remove_title, g.name),
                save != null ? getString(R.string.home_remove_body_save, save.getPath(), getString(R.string.home_add_game)) : getString(R.string.home_remove_body, getString(R.string.home_add_game)),
                getString(R.string.home_remove_yes), () -> {
                    List<Game> list = Game.load(this);
                    list.removeIf(x -> x.gameDir.equals(g.gameDir));
                    Game.save(this, list);
                    refresh();
                });
    }

    void launch(Game g) { launch(g, false); }

    /**
     * Play: PlayRule's decision (Play.check, the same one FrontendLaunch takes), then the game, or the step that comes first with its dialog. frontend: a frontend
     * asked for this card (fromFrontend), so a game started here, also by "Start anyway", brings the frontend back when it stops
     */
    void launch(Game g, boolean frontend) {
        PlayRule.Verdict v = Play.check(this, g);
        if (v.kind.straight()) { Play.start(this, g, v.kind, frontend); return; }
        File gd = new File(g.gameDir);
        boolean dump = Ondevice.isDump(gd);
        Runnable pickPack = () -> { pendingGame = gd; pendingId = g.titleId; pickFolder(PICK_PACK, getString(R.string.home_pick_pack_folder)); };
        switch (v.kind) {
            case NO_ACCESS: needAccess(); return;
            case NO_PROFILE: error(v.why.text()); return;
            case SAVE_BUSY: error(getString(R.string.home_busy_save, g.name, getString(R.string.home_play))); return;
            case PACK_BUSY: error(getString(R.string.home_busy_pack, g.name, getString(R.string.home_play))); return;
            case PACK_UNREADABLE:
                if (dump) packProblem(g, getString(R.string.home_pack_unusable, v.error.getLocalizedMessage()), pickPack, frontend);
                else error(v.error.getLocalizedMessage());
                return;
            case PLACEHOLDER:   // a placeholder of the simulated engine (Anka Native 0.1.0's Prepare made one): it has no game code and no shaders, so it never starts
                if (dump) packProblem(g, getString(R.string.home_pack_placeholder), pickPack, frontend);
                else showPlay(new AlertDialog.Builder(this).setTitle(getString(R.string.home_no_pack_for, g.name)).setMessage(getString(R.string.home_no_pack_for_body, Ondevice.unavailableText(), g.packDir))
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.home_pick_pack_folder_button, (d, w) -> pickPack.run()), frontend);
                return;
            case OTHER_TITLE:
            case PACK_BLOCKED:   // a plain message, and Prepare again from the dump, instead of a start that cannot work
                packProblem(g, v.why.text(), pickPack, frontend);
                return;
            case NO_LIBRARY:
                break;
            default:   // the straight kinds started above
                throw new IllegalStateException("Play's steps for " + v.kind);   // i18n-keep: a caller's bug, never shown
        }
        // NO_LIBRARY: without a library it can play, the game runs but draws nothing. Say so, and offer to prepare the shaders (a dump) or another pack folder.
        // This version's library of older shader tables, with no dump to update it from (a pack made on a computer): the game plays, without the shaders the app's
        // tables added (Ocarina of Time's mesh draws), until the pack is made again with this version's tools
        String lp = v.why.text();
        boolean older = !dump && Pack.ownInputsDelta(new File(g.packDir), g.titleId) >= 0;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(older ? R.string.home_shaders_out_of_date : R.string.home_shaders_missing)
                .setMessage(dump ? getString(R.string.home_shaders_missing_prepare, Ondevice.prepareShadersMsg(new File(g.packDir), g.titleId).text(), lp)
                        : older ? getString(R.string.home_shaders_out_of_date_body, lp) : getString(R.string.home_shaders_missing_body, lp))
                .setNeutralButton(R.string.home_pick_pack_folder_button, (d, w) -> pickPack.run()).setNegativeButton(android.R.string.cancel, null);
        if (dump) b.setPositiveButton(R.string.home_prepare, (d, w) -> Ondevice.prepare(this, gd, false));
        else b.setPositiveButton(R.string.home_start_anyway, (d, w) -> Play.go(this, g, frontend));
        showPlay(b, frontend);
    }

    /** A pack that cannot run with this game folder: why, then Prepare again from the dump (the default), or another pack folder; frontend as for showPlay */
    private void packProblem(Game g, String why, Runnable pickPack, boolean frontend) {
        File gd = new File(g.gameDir);
        boolean dump = Ondevice.isDump(gd);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(R.string.home_pack_cannot_start)
                .setMessage(dump ? getString(R.string.home_pack_problem_dump, getString(R.string.home_prepare_again), why, g.packDir) : getString(R.string.home_pack_problem, why, g.packDir))
                .setNegativeButton(android.R.string.cancel, null).setNeutralButton(R.string.home_pick_pack_folder_button, (d, w) -> pickPack.run());
        if (dump) b.setPositiveButton(R.string.home_prepare_again, (d, w) -> Ondevice.prepare(this, gd, false));
        showPlay(b, frontend);
    }

    /** The overlay's master switch and its items (Overlay.rows); the main screen's switch follows the master switch */
    private void overlayItems() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Overlay.rows(this, box, null);
        Ui.sheet(this, getString(R.string.home_overlay), box).setPositiveButton(R.string.home_done, null).setOnDismissListener(d -> refreshSettings()).show();
    }

    private String resLabel() { return Prefs.windowName(Prefs.window(this), true); }

    /** Game resolution: the window size the host gives the game (DM_WINDOW), from the next start */
    private void gameRes() {
        int i = Math.max(0, java.util.Arrays.asList(Prefs.WINDOWS).indexOf(Prefs.window(this)));
        new AlertDialog.Builder(this).setTitle(R.string.home_game_resolution).setSingleChoiceItems(Prefs.windowNames(), i, (d, w) -> { Prefs.window(this, Prefs.WINDOWS[w]); refreshSettings(); d.dismiss(); })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** A frame rate cap's label, in the user's language (screen only: this list, the settings row, GameSettings) */
    static String capLabel(int v) { return v < 0 ? Tr.s("home_cap_default") : v == 0 ? Tr.s("home_cap_own") : Tr.s("home_cap_fps", v); }

    /** The frame rate cap the next start uses (Prefs.fpsCap, DM_FPS_TARGET); the in-game panel changes it while the game runs */
    private void fpsCap() {
        int cur = Prefs.fpsCap(this), n = Prefs.FPS_CAPS.length;
        String[] labels = new String[n + 1];
        labels[0] = capLabel(-1);
        int checked = 0;
        for (int k = 0; k < n; k++) {
            int v = Prefs.FPS_CAPS[k];
            labels[k + 1] = capLabel(v);
            if (v == cur) checked = k + 1;
        }
        new AlertDialog.Builder(this).setTitle(R.string.home_fps_cap)
                .setSingleChoiceItems(labels, checked, (d, w) -> { Prefs.fpsCap(this, w == 0 ? -1 : Prefs.FPS_CAPS[w - 1]); refreshSettings(); d.dismiss(); })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Frame generation's settings and the Lossless.dll found (or a manual pick when it is in none of the known places) */
    private void frameGen() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        File dll = Lsfg.find(this);
        Lsfg.rows(this, box, dll != null ? Lsfg.noteText() : getString(R.string.home_lsfg_no_dll, Lsfg.noteText()), () -> {});
        Ui.add(box, Ui.small(this, getString(R.string.home_lsfg_credit)), 10);
        Ui.sheet(this, getString(R.string.home_frame_gen), box).setPositiveButton(R.string.home_done, null).setOnDismissListener(d -> refreshSettings())
                .setNeutralButton(R.string.home_pick_dll, (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    startActivityForResult(i, PICK_DLL);
                }).show();
    }

    private void shareLog() { shareLog(null); }

    /** The newest log of a game (titleId), or of any run (null) */
    private void shareLog(String titleId) {
        File log = LogProvider.newest(this, titleId);
        if (log == null) { error(titleId == null ? getString(R.string.home_no_log) : getString(R.string.home_no_game_log)); return; }
        Uri uri = LogProvider.uri(log);
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, getString(R.string.home_log_subject, log.getName()));
        send.setClipData(ClipData.newRawUri(log.getName(), uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, getString(R.string.home_share_file, log.getName())));
    }

    void error(String msg) { new AlertDialog.Builder(this).setMessage(msg).setPositiveButton(android.R.string.ok, null).show(); }
}
