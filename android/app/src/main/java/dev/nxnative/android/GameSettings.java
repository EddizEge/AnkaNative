package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A game's own settings (its Manage sheet › Game settings…): resolution, 3D resolution, frame rate cap, GPU driver, GPU timing and extra environment lines, stored per title ID (Prefs.gameKey,
 * GpuDriver.choice). Each starts as "Default", which follows the main screen's Settings (global); a value set here wins for this game only. The in-game panel's frame
 * rate cap is this game's too. GameActivity reads them at start.
 */
final class GameSettings {
    private GameSettings() {}

    static void dialog(MainActivity a, Game g) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        Runnable again = () -> { d[0].dismiss(); dialog(a, g); };
        String id = g.titleId;
        box.addView(Ui.small(a, a.getString(R.string.settings_game_intro, HomeLabels.settings().text())));
        Profile prof = Profile.of(id);
        if (prof != null && prof.hasResolution())   // only where it does something: a profile with window_fn or graphics_scale rows
            Ui.add(box, new Ui.Setting(a, "🖼️", a.getString(R.string.settings_game_resolution), v -> resolution(a, g, again)).value(windowLabel(a, id)).chevron(a).view, 8);
        if (prof != null && prof.hasResLevel())   // a profile with drs_level and res_choice rows (Ocarina of Time)
            Ui.add(box, new Ui.Setting(a, "🔍", a.getString(R.string.settings_game_res_level), v -> resLevel(a, g, again)).value(resLevelLabel(a, id)).chevron(a).view, 0);
        if (Prefs.ownRate(a, id)) {   // the host found no limiter it can hold for this game: choices would do nothing
            Ui.Setting fixed = new Ui.Setting(a, "⏱️", a.getString(R.string.settings_game_fps_cap), null).value(a.getString(R.string.settings_game_fps_own));
            fixed.view.setClickable(false);
            fixed.view.setAlpha(0.6f);
            Ui.add(box, fixed.view, 0);
        } else Ui.add(box, new Ui.Setting(a, "⏱️", a.getString(R.string.settings_game_fps_cap), v -> cap(a, g, again)).value(capLabel(a, id)).chevron(a).view, 0);
        Ui.add(box, new Ui.Setting(a, "🎮", a.getString(R.string.settings_game_gpu_driver), v -> driver(a, g, again)).value(driverLabel(a, id)).chevron(a).view, 0);
        Ui.add(box, new Ui.Setting(a, "⏲️", a.getString(R.string.settings_game_gpu_timing), v -> gpuTiming(a, g, again)).value(gpuTsLabel(a, id)).chevron(a).view, 0);
        Ui.Setting ret = new Ui.Setting(a, "⏸️", a.getString(R.string.settings_game_pause_on_return), null).toggle(a, Prefs.pauseOnReturn(a, id), (b, on) -> Prefs.pauseOnReturn(a, id, on));
        ret.value(a.getString(R.string.settings_game_pause_on_return_about));
        Ui.add(box, ret.view, 0);
        Ui.Setting skip = new Ui.Setting(a, "⏩", a.getString(R.string.settings_game_intro_skip), null).toggle(a, Prefs.introSkip(a, id), (b, on) -> Prefs.introSkip(a, id, on));
        skip.value(a.getString(R.string.settings_game_intro_skip_about));
        Ui.add(box, skip.view, 0);
        Ui.Setting ram = new Ui.Setting(a, "💾", a.getString(R.string.settings_game_ram_saver), null).toggle(a, Prefs.ramSaver(a, id), (b, on) -> Prefs.ramSaver(a, id, on));
        ram.value(a.getString(R.string.settings_game_ram_saver_about));
        Ui.add(box, ram.view, 0);
        String env = Prefs.gameEnv(a, id);
        int envLines = env.split("\n").length;
        Ui.add(box, new Ui.Setting(a, "🧪", a.getString(R.string.settings_game_env), v -> env(a, g, again))
                .value(env.isEmpty() ? a.getString(R.string.settings_game_env_none)
                        : a.getResources().getQuantityString(R.plurals.settings_game_env_lines, envLines, envLines, env.replace('\n', ' '))).chevron(a).view, 0);
        d[0] = Ui.sheet(a, a.getString(R.string.settings_game_title, g.name), box).setPositiveButton(a.getString(R.string.settings_done), null).show();
    }

    static String windowLabel(MainActivity a, String id) {
        String w = Prefs.gameWindow(a, id);
        String l = Prefs.windowName(w, false);
        return Prefs.ownWindow(a, id) ? l : a.getString(R.string.settings_game_default_value, l);
    }

    static String capLabel(MainActivity a, String id) {
        String l = MainActivity.capLabel(Prefs.fpsCap(a, id));
        return Prefs.ownFpsCap(a, id) ? l : a.getString(R.string.settings_game_default_value, l);
    }

    static String driverLabel(MainActivity a, String id) {
        GpuDriver.Info i = GpuDriver.selected(a, id);
        String l = i == null ? a.getString(R.string.settings_gpu_system) : i.label();
        return GpuDriver.choice(a, id) != null ? l : a.getString(R.string.settings_game_default_value, l);
    }

    static final String[] GPU_TS = {"segment", "submission"};

    private static String gpuTsName(Context c, String mode) { return c.getString(mode.equals("segment") ? R.string.settings_game_gpu_ts_segment : R.string.settings_game_gpu_ts_submission); }

    static String gpuTsLabel(MainActivity a, String id) {
        String m = Prefs.gpuTs(a, id);
        Profile p = Profile.of(id);
        return m != null ? gpuTsName(a, m) : a.getString(R.string.settings_game_gpu_ts_default_value, gpuTsName(a, p == null ? "submission" : p.gpuTs));
    }

    /**
     * GPU timing: what the game's GPU timestamps measure (DM_NVN_GPU_TS; game.tsv gpu_timestamps is the default). Per segment: each timestamp gets the GPU time of
     * its place in the submission, so a game's dynamic resolution and frame pacing see the real GPU time; per submission: every timestamp of a submission gets
     * its completion time (the older behaviour)
     */
    private static void gpuTiming(MainActivity a, Game g, Runnable done) {
        Profile p = Profile.of(g.titleId);
        String[] labels = {a.getString(R.string.settings_game_gpu_ts_default, gpuTsName(a, p == null ? "submission" : p.gpuTs)), a.getString(R.string.settings_game_gpu_ts_segment),
                a.getString(R.string.settings_game_gpu_ts_submission)};
        String m = Prefs.gpuTs(a, g.titleId);
        int checked = m == null ? 0 : 1 + Math.max(0, Arrays.asList(GPU_TS).indexOf(m));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        box.addView(Ui.small(a, a.getString(R.string.settings_game_gpu_ts_about)));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(a);
        for (int k = 0; k < labels.length; k++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(a);
            rb.setText(labels[k]);
            rb.setId(k + 1);
            rg.addView(rb);
        }
        rg.check(checked + 1);
        rg.setOnCheckedChangeListener((x, w) -> {
            Prefs.gpuTs(a, g.titleId, w <= 1 ? null : GPU_TS[w - 2]);
            d[0].dismiss();
            done.run();
        });
        Ui.add(box, rg, 8);
        d[0] = Ui.sheet(a, a.getString(R.string.settings_game_gpu_timing_title, g.name), box).setNegativeButton(android.R.string.cancel, null).show();
    }

    static String resLevelLabel(MainActivity a, String id) {
        Profile p = Profile.of(id);
        String v = Prefs.resLevel(a, id), l = p == null ? null : p.resLevelLabel(v);
        return l != null ? l : a.getString(R.string.settings_game_res_level_gone, v);
    }

    /**
     * 3D resolution: the game's own dynamic resolution (the default), a level it keeps, or a level it never goes above (DM_RES_LEVEL; loader.c drs_level_apply writes
     * the game's code at load, so no Prepare is needed). The choices and their sizes are the profile's res_choice rows
     */
    private static void resLevel(MainActivity a, Game g, Runnable done) {
        Profile p = Profile.of(g.titleId);
        List<String[]> ch = p.resLevels();
        String[] labels = new String[ch.size() + 1];
        labels[0] = p.resLevelLabel(null);
        for (int k = 0; k < ch.size(); k++) labels[k + 1] = ch.get(k)[1];
        String cur = Prefs.resLevel(a, g.titleId);
        // A stored value the profile no longer offers passes nothing (Profile.resLevelEnv): clear it, so Dynamic, checked below, is what is stored too, and Cancel
        // refreshes the row that named the old value
        boolean stale = cur != null && p.resLevelLabel(cur) == null;
        if (stale) Prefs.resLevel(a, g.titleId, null);
        int checked = 0;
        for (int k = 0; k < ch.size(); k++) if (ch.get(k)[0].equals(cur)) checked = k + 1;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        box.addView(Ui.small(a, a.getString(R.string.settings_game_res_level_about)));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(a);
        for (int k = 0; k < labels.length; k++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(a);
            rb.setText(labels[k]);
            rb.setId(k + 1);
            rg.addView(rb);
        }
        rg.check(checked + 1);
        rg.setOnCheckedChangeListener((x, w) -> {
            Prefs.resLevel(a, g.titleId, w <= 1 ? null : ch.get(w - 2)[0]);
            d[0].dismiss();
            done.run();
        });
        Ui.add(box, rg, 8);
        d[0] = Ui.sheet(a, a.getString(R.string.settings_game_res_level_title, g.name), box).setNegativeButton(android.R.string.cancel, stale ? (x, y) -> done.run() : null).show();
    }

    private static void resolution(MainActivity a, Game g, Runnable done) {
        String[] labels = new String[Prefs.WINDOWS.length + 1];
        labels[0] = a.getString(R.string.settings_game_default_choice, Prefs.windowName(Prefs.defaultWindow(a, g.titleId), false));
        String[] names = Prefs.windowNames();
        System.arraycopy(names, 0, labels, 1, names.length);
        int checked = Prefs.ownWindow(a, g.titleId) ? 1 + Math.max(0, Arrays.asList(Prefs.WINDOWS).indexOf(Prefs.gameWindow(a, g.titleId))) : 0;
        new AlertDialog.Builder(a).setTitle(a.getString(R.string.settings_game_resolution_title, g.name)).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.gameWindow(a, g.titleId, w == 0 ? null : Prefs.WINDOWS[w - 1]);
            x.dismiss();
            done.run();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void cap(MainActivity a, Game g, Runnable done) {
        int n = Prefs.FPS_CAPS.length;
        String[] labels = new String[n + 2];
        labels[0] = a.getString(R.string.settings_game_fps_cap_default, MainActivity.capLabel(Prefs.fpsCap(a)), HomeLabels.settings().text());
        labels[1] = MainActivity.capLabel(-1);
        int checked = 0;
        boolean own = Prefs.ownFpsCap(a, g.titleId);
        int cur = Prefs.fpsCap(a, g.titleId);
        if (own && cur < 0) checked = 1;
        for (int k = 0; k < n; k++) {
            labels[k + 2] = MainActivity.capLabel(Prefs.FPS_CAPS[k]);
            if (own && Prefs.FPS_CAPS[k] == cur) checked = k + 2;
        }
        new AlertDialog.Builder(a).setTitle(a.getString(R.string.settings_game_fps_cap_title, g.name)).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.fpsCap(a, g.titleId, w == 0 ? null : w == 1 ? -1 : Prefs.FPS_CAPS[w - 2]);
            x.dismiss();
            done.run();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void driver(MainActivity a, Game g, Runnable done) {
        List<GpuDriver.Info> inst = GpuDriver.installed(a);
        GpuDriver.Info def = GpuDriver.selected(a);
        List<String> labels = new ArrayList<>();
        // the main screen's GPU driver row as it shows it: a driver's name, or home's label for the system's (HomeLabels.gpuSystem, never typed again here)
        labels.add(a.getString(R.string.settings_game_driver_default, def == null ? HomeLabels.gpuSystem().text() : def.label()));
        labels.add(a.getString(R.string.settings_gpu_system));
        String c = GpuDriver.choice(a, g.titleId);
        int checked = c == null ? 0 : c.equals(GpuDriver.SYSTEM) ? 1 : 0;
        for (int k = 0; k < inst.size(); k++) {
            labels.add(inst.get(k).label());
            if (c != null && inst.get(k).dir.getName().equals(c)) checked = k + 2;
        }
        new AlertDialog.Builder(a).setTitle(a.getString(R.string.settings_game_gpu_driver_title, g.name)).setSingleChoiceItems(labels.toArray(new String[0]), checked, (x, w) -> {
            GpuDriver.choice(a, g.titleId, w == 0 ? null : w == 1 ? GpuDriver.SYSTEM : inst.get(w - 2).dir.getName());
            x.dismiss();
            done.run();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void env(MainActivity a, Game g, Runnable done) {
        EditText t = new EditText(a);
        t.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        t.setMinLines(4);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(13);
        t.setHint("DM_WATCH_STALL=25");
        t.setText(Prefs.gameEnv(a, g.titleId));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.small(a, a.getString(R.string.settings_game_env_about, g.name)));
        Ui.add(box, t, 8);
        Ui.sheet(a, a.getString(R.string.settings_game_env_title, g.name), box)
                .setPositiveButton(a.getString(R.string.settings_save), (x, w) -> { Prefs.gameEnv(a, g.titleId, t.getText().toString()); done.run(); })
                .setNeutralButton(a.getString(R.string.settings_clear), (x, w) -> { Prefs.gameEnv(a, g.titleId, null); done.run(); }).setNegativeButton(android.R.string.cancel, null).show();
    }
}
