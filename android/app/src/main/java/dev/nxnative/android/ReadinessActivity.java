package dev.nxnative.android;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Readiness check's screen (Settings › Readiness check), in a process of its own (":readiness") because it loads a host (the APK's first game's: any host reports the
 * GPU) to ask Vulkan what this phone has (Native.readiness), on the GPU driver picked for every game. Cards: the phone (Android, memory, files access, storage, battery
 * restrictions, the last game exit and memory kills of the last 7 days), the GPU and driver, then for each game of this APK the host's needs and the game's own (Readiness),
 * each pass / warning / fail with a fix button where there is one. Run again; Copy report and Share report hand the whole report on for a compatibility report.
 * The cards, Run again, the fix buttons, the last exit and the shareable report are adapted from the author's earlier readiness check. The same check runs before every
 * game starts (GameActivity), on that game's own driver, and stops a start that would fail with a sentence and Try anyway.
 */
public final class ReadinessActivity extends Activity {
    /** MainActivity's request: a result of FIX_DRIVER asks it to open the GPU driver dialog (it lives in the launcher's process) */
    static final int REQUEST = 7, FIX_DRIVER = RESULT_FIRST_USER;
    private static final long KILL_WINDOW_MS = 7 * DateUtils.DAY_IN_MILLIS;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView summary, verdict, mark;
    private String report = "";
    // This process's Vulkan facts and the driver they were read on: the driver is opened once per process (vka_driver_open), so they are static, kept by a screen opened
    // again in the same process (left by Home, not Back), which then sees a changed pick as stale instead of reading the old driver as the new one
    private static String vulkan;
    private static String probedDriver;

    /** Android 12: the language picked in Settings › Language (Lang.wrap; Android 13+ applies it itself) */
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.col(this, R.color.bn_bg));
        setContentView(frame);
        LinearLayout content = Ui.page(this, frame, 640);   // the same gutter, width and header as the launcher's pages
        content.addView(Ui.header(this, Ui.back(this, v -> leave()), getString(R.string.readiness_title), null));
        // The verdict: a mark, one line, the counts; then Run again and the report. Each check below shows its details on a tap (failures and warnings open)
        LinearLayout top = Ui.add(content, Ui.card(this), 16);
        LinearLayout vr = Ui.row(this);
        mark = Ui.text(this, "…", 26, R.color.bn_on_banana, 900);
        mark.setGravity(android.view.Gravity.CENTER);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        vr.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        LinearLayout vc = new LinearLayout(this);
        vc.setOrientation(LinearLayout.VERTICAL);
        vc.setPadding(Ui.dp(this, 14), 0, 0, 0);
        verdict = Ui.heading(this, getString(R.string.readiness_checking));
        vc.addView(verdict);
        summary = Ui.small(this, "");
        vc.addView(summary);
        vr.addView(vc, Ui.weight());
        top.addView(vr);
        setMark(-1);
        Button share = Ui.button(this, getString(R.string.readiness_share), Ui.Kind.TONAL, v -> share());
        share.setOnLongClickListener(v -> { copy(this, report); return true; });
        Ui.add(top, Ui.pair(this, Ui.button(this, getString(R.string.readiness_run_again), Ui.Kind.TONAL, v -> run()), share), 16);
        Ui.add(top, Ui.button(this, getString(R.string.readiness_copy_report), Ui.Kind.QUIET, v -> copy(this, report)), 6, Ui.wrap());
        list = Ui.add(content, new LinearLayout(this), 0);
        list.setOrientation(LinearLayout.VERTICAL);
        try {
            Native.load(Profile.first().titleId);   // any game's host reports the GPU: the APK's first game's
        } catch (RuntimeException e) {   // (IllegalStateException: no host; an APK always has a game)
            vulkan = "unchecked=" + e.getMessage();
        }
        run();
    }

    @Override protected void onResume() {
        super.onResume();
        if (list != null && list.getChildCount() > 0) run();   // back from a fix (the app's settings, files access)
    }

    /** The checks on a thread of their own (the Vulkan query loads the driver), then the cards */
    private boolean running;
    private void run() {
        if (running) return;
        running = true;
        verdict.setText(R.string.readiness_checking);
        summary.setText("");
        setMark(-1);
        Context app = getApplicationContext();
        new Thread(() -> {
            GpuDriver.Info d = GpuDriver.selected(app);
            String drv = d == null ? "" : d.dir.getPath();
            if (vulkan == null || !vulkan.startsWith("unchecked=")) {
                if (probedDriver == null || probedDriver.equals(drv)) {
                    List<String> env = new ArrayList<>();
                    GpuDriver.env(app, null, env);
                    String v = Native.readiness(Readiness.driverEnv(env));
                    vulkan = v;
                    probedDriver = drv;
                }
            }
            boolean stale = probedDriver != null && !probedDriver.equals(drv);
            Result r = collect(app, vulkan, stale);
            ui.post(() -> { running = false; show(r); });
        }, "nxreadiness").start();
    }

    private static final class Section {
        final String heading;
        final List<Readiness.Check> checks;

        Section(String heading, List<Readiness.Check> checks) { this.heading = heading; this.checks = checks; }
    }

    private static final class Result {
        final List<Section> sections = new ArrayList<>();
        String report, verdict, summary;
        int level;   // 0 ready, 1 ready with less, 2 not ready
    }

    /** The checks and the report. c is the application context (this runs on a thread of its own), which Android 12's language choice (Lang.wrap) does not reach:
     *  the screen's texts come through Tr and Msg, which read it (TrAndroid) */
    private static Result collect(Context c, String vulkan, boolean stale) {
        Result r = new Result();
        Map<String, String> f = Readiness.merge(vulkan, ramGib(c), Build.VERSION.SDK_INT);
        List<Readiness.Check> phone = phoneChecks(c);
        GpuDriver.Info d = GpuDriver.selected(c);
        List<Readiness.Check> gpu = new ArrayList<>();
        Msg gpuLine = Readiness.gpuMsg(f);
        gpu.add(new Readiness.Check(new Msg("readiness_check_gpu"), stale ? Readiness.Status.WARNING : f.containsKey("gpu.name") ? Readiness.Status.INFO : f.containsKey("error") ? Readiness.Status.FAIL : Readiness.Status.WARNING,
                stale ? new Msg("readiness_gpu_stale", gpuLine) : gpuLine, "driver"));
        gpu.add(new Readiness.Check(new Msg("readiness_check_driver_picked"), Readiness.Status.INFO, new Msg("readiness_driver_picked", driverName(d), HomeLabels.settings(), HomeLabels.gpuDriver()), "driver"));
        r.sections.add(new Section(Tr.s("readiness_section_phone"), phone));
        r.sections.add(new Section(Tr.s("readiness_section_gpu"), gpu));
        StringBuilder rep = new StringBuilder(header(c, null)).append('\n');
        int fails = 0, warns = 0;
        for (Readiness.Check x : phone) rep.append(x).append('\n');
        for (Readiness.Check x : gpu) rep.append(x).append('\n');
        List<Game> added = Game.load(c);
        for (Profile p : Profile.all()) {
            List<Readiness.Check> l = new ArrayList<>();
            Readiness.Check st = Readiness.status(p.status);
            if (st != null) l.add(st);
            l.addAll(Readiness.evaluate(f, Readiness.parseReqs(p.reqs)));
            String own = GpuDriver.choice(c, p.titleId);
            if (own != null) {
                // This screen probes the main screen's driver only (the driver opens once a process, vka_driver_open): a game with another driver of its own is
                // checked on that one when it starts, and that check can differ from this screen's
                GpuDriver.Info g = GpuDriver.selected(c, p.titleId);
                Object gl = driverName(g), ml = driverName(d);
                boolean same = g == null ? d == null : d != null && g.dir.equals(d.dir);
                l.add(same ? new Readiness.Check(new Msg("readiness_check_own_driver"), Readiness.Status.INFO, new Msg("readiness_own_driver_same", gl), "driver")
                        : new Readiness.Check(new Msg("readiness_check_own_driver"), Readiness.Status.WARNING, new Msg("readiness_own_driver_other", gl, ml), "driver"));
            }
            String name = p.titleId;   // a game is named as the user's dump names it (its card), else by its title ID
            for (Game g : added) if (g.titleId.equals(p.titleId)) { name = g.name; break; }
            for (Game g : added) if (g.titleId.equals(p.titleId)) l.add(gameFiles(g));
            r.sections.add(new Section(name, l));
            boolean fail = Readiness.any(l, Readiness.Status.FAIL), warn = Readiness.any(l, Readiness.Status.WARNING);
            if (fail) fails++; else if (warn) warns++;
            rep.append("-- game ").append(p.titleId).append(" --\n");   // i18n-keep: the report's marker
            for (Readiness.Check x : l) rep.append(x).append('\n');
        }
        String last = lastReports(c);
        if (!last.isEmpty()) rep.append(last);
        rep.append("-- facts --\n");
        for (Map.Entry<String, String> e : f.entrySet()) rep.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        r.report = rep.toString();
        int phoneFails = Readiness.of(phone, Readiness.Status.FAIL).size();
        int phoneWarns = Readiness.of(phone, Readiness.Status.WARNING).size();
        r.level = fails > 0 || phoneFails > 0 ? 2 : warns > 0 ? 1 : 0;
        r.verdict = r.level == 2 ? Tr.s("readiness_verdict_not_ready") : r.level == 1 ? Tr.s("readiness_verdict_limited") : Tr.s("readiness_verdict_ready");
        if (r.level == 2)   // level 2: a game fails, or the phone does (or both)
            r.summary = fails > 0 && phoneFails > 0
                    ? Tr.s("readiness_summary_both", Tr.p("readiness_summary_games_lack_part", fails, fails), Tr.p("readiness_summary_phone_problems_part", phoneFails, phoneFails))
                    : fails > 0 ? Tr.p("readiness_summary_games_lack", fails, fails) : Tr.p("readiness_summary_phone_problems", phoneFails, phoneFails);
        else r.summary = r.level == 1 ? Tr.p("readiness_summary_limited", warns, warns)
                : phoneWarns > 0 ? Tr.s("readiness_summary_met_warnings") : Tr.s("readiness_summary_met");
        return r;
    }

    private void show(Result r) {
        if (isFinishing() || isDestroyed()) return;
        report = r.report;
        verdict.setText(r.verdict);
        summary.setText(r.summary);
        setMark(r.level);
        list.removeAllViews();
        for (Section s : r.sections) {
            Ui.add(list, Ui.section(this, s.heading), Ui.SECTION_GAP);
            LinearLayout card = Ui.add(list, Ui.listCard(this), 8);
            boolean gpu = s == r.sections.get(1);   // the GPU section's facts are what a report needs most: open
            for (Readiness.Check x : s.checks) Ui.add(card, row(x, gpu), 2);
        }
    }

    /** The verdict's mark: ✓ on leaf green, ! on banana, ✕ on red-orange; … while checking */
    private void setMark(int level) {
        mark.setText(level == 0 ? "✓" : level == 1 ? "!" : level == 2 ? "✕" : "…");
        int fill = level == 0 ? R.color.bn_leaf : level == 2 ? R.color.bn_danger : R.color.bn_banana;
        mark.setTextColor(Ui.col(this, level == 0 ? R.color.bn_on_leaf : level == 2 ? R.color.bn_card : R.color.bn_on_banana));
        mark.setBackground(Ui.round(Ui.col(this, fill), Ui.dp(this, 999), 0, 0));
    }

    /** One check: its mark and title; the details show on a tap, and open from the start for a failure or a warning (with its fix button) */
    private View row(Readiness.Check x, boolean openInfo) {
        boolean bad = x.status == Readiness.Status.FAIL || x.status == Readiness.Status.WARNING, open0 = bad || openInfo && x.status == Readiness.Status.INFO;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        box.setBackground(Ui.ripple(this, Ui.round(bad ? Ui.col(this, x.status == Readiness.Status.FAIL ? R.color.bn_danger_tint : R.color.bn_warn_tint) : android.graphics.Color.TRANSPARENT,
                Ui.dp(this, 16), 0, 0)));
        LinearLayout top = Ui.row(this);
        String m = x.status == Readiness.Status.PASS ? "✓" : x.status == Readiness.Status.WARNING ? "!" : x.status == Readiness.Status.FAIL ? "✕" : "i";
        TextView st = Ui.text(this, m, 15, x.status == Readiness.Status.FAIL ? R.color.bn_danger : x.status == Readiness.Status.PASS ? R.color.bn_leaf : R.color.bn_text_2, 900);
        st.setGravity(android.view.Gravity.CENTER);
        top.addView(st, new LinearLayout.LayoutParams(Ui.dp(this, 24), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = Ui.text(this, x.titleText(), 15, R.color.bn_text, 600);
        t.setPadding(Ui.dp(this, 8), 0, 0, 0);
        top.addView(t, Ui.weight());
        android.widget.ImageView chev = new android.widget.ImageView(this);   // turned down when closed, up when open
        chev.setImageDrawable(Ui.icon(this, R.drawable.ic_chevron, R.color.bn_text_2));
        chev.setRotation(open0 ? -90 : 90);
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        top.addView(chev, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        box.addView(top);
        LinearLayout more = new LinearLayout(this);
        more.setOrientation(LinearLayout.VERTICAL);
        more.setPadding(Ui.dp(this, 32), 0, 0, 0);
        TextView msg = Ui.small(this, x.messageText());
        msg.setTextIsSelectable(false);
        more.addView(msg);
        View.OnClickListener fix = fix(x);
        if (fix != null && bad) Ui.add(more, Ui.button(this, getString(fixLabel(x.fix)), Ui.Kind.SOFT, fix), 8, Ui.wrap());
        Ui.add(box, more, 4);
        more.setVisibility(open0 ? View.VISIBLE : View.GONE);
        box.setOnClickListener(v -> {
            boolean open = more.getVisibility() != View.VISIBLE;
            more.setVisibility(open ? View.VISIBLE : View.GONE);
            chev.setRotation(open ? -90 : 90);
        });
        return box;
    }

    private static int fixLabel(String fix) {
        switch (fix) {
            case "driver": return R.string.readiness_fix_driver;
            case "access": return R.string.readiness_fix_access;
            default: return R.string.readiness_fix_app;
        }
    }

    /** A GPU driver as the screen names it: its label (a name and a version), or the name Settings › GPU driver shows for the phone's own driver */
    private static Object driverName(GpuDriver.Info d) { return d == null ? HomeLabels.gpuSystem() : d.label(); }

    /** An exception's message as an argument of a Msg: a LocalizedIOException's own Msg (English in the report, the user's language on the screen), else its message */
    private static Object detail(Exception e) {
        return e instanceof LocalizedIOException && ((LocalizedIOException) e).msg != null ? ((LocalizedIOException) e).msg : e.getMessage();
    }

    private View.OnClickListener fix(Readiness.Check x) {
        if (x.fix == null) return null;
        switch (x.fix) {
            case "driver": return v -> { setResult(FIX_DRIVER); leave(); };
            case "access": return v -> settings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            case "app": return v -> settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            default: return null;   // memory: nothing on the phone changes it
        }
    }

    private void settings(String action) {
        try { startActivity(new Intent(action, Uri.parse("package:" + getPackageName()))); }
        catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void share() {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Anka Native readiness report").putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(send, getString(R.string.readiness_share_title)));
    }

    static void copy(Context c, String text) {
        ClipboardManager cm = c.getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(c.getString(R.string.readiness_clip_label), text));
        Toast.makeText(c, c.getString(R.string.readiness_report_copied), Toast.LENGTH_SHORT).show();
    }

    // ---- What the launcher knows without Vulkan (shared with GameActivity) ----

    /** The phone's memory as Android reports it (ActivityManager totalMem, GiB): less than the label, by what the firmware and kernel keep (a 16 GB phone reports about
     *  14.5-15.5 GiB, a 12 GB one about 10.5-11.5) */
    static double ramGib(Context c) {
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        c.getSystemService(ActivityManager.class).getMemoryInfo(m);
        return m.totalMem / (double) (1L << 30);
    }

    /**
     * Whether the dump of an added game can be read: a folder (readable, with base/exefs/main.npdm or exefs/main.npdm), or a .nup (Nup.open: its header and the
     * SHA-256 of its table of contents; its packages listed)
     */
    static Readiness.Check gameFiles(Game g) {
        File f = new File(g.gameDir);
        if (Nup.isNup(f)) {
            try {
                Nup n = Nup.open(f);
                StringBuilder s = new StringBuilder();
                for (Nup.Pkg p : n.packages) s.append(s.length() == 0 ? "" : ", ").append(p.selector() != null ? p.selector() : "kind " + p.kind);   // i18n-keep: a package's technical name
                return new Readiness.Check(new Msg("readiness_check_files"), Readiness.Status.PASS, new Msg("readiness_files_nup", f, s.toString()), null);
            } catch (IOException e) {
                return new Readiness.Check(new Msg("readiness_check_files"), Readiness.Status.FAIL, new Msg("readiness_files_unusable", f, detail(e)), f.canRead() ? null : "access");
            }
        }
        if (!f.isDirectory() || !f.canRead()) return new Readiness.Check(new Msg("readiness_check_files"), Readiness.Status.FAIL, new Msg("readiness_files_unreadable", f), "access");
        if (Pack.npdmOf(f) == null) return new Readiness.Check(new Msg("readiness_check_files"), Readiness.Status.FAIL, new Msg("readiness_files_no_npdm", f), null);
        return new Readiness.Check(new Msg("readiness_check_files"), Readiness.Status.PASS, new Msg("readiness_files_folder", f), null);
    }

    /** The report's first lines: the build, the phone, Android, and the game when there is one */
    static String header(Context c, String game) {
        return "Anka Native readiness report: " + Prefs.buildTag(c) + "\nSigner: " + Prefs.signerId(c) + " (SHA-256 of the signing certificate, first 16 hex digits)\nPhone: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL + "), Android "   // i18n-keep: the report
                + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.SUPPORTED_ABIS[0] + (game == null ? "" : "\nGame: " + game);   // i18n-keep: the report
    }

    /** The newest start-up check of each game (GameActivity writes files/readiness-&lt;title ID&gt;.txt), for the report */
    private static String lastReports(Context c) {
        StringBuilder s = new StringBuilder();
        File files = c.getExternalFilesDir(null);
        for (Profile p : Profile.all()) {
            File f = new File(files, "readiness-" + p.titleId + ".txt");
            if (!f.isFile()) continue;
            try {
                String t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                String first = t.contains("\n[") ? t.substring(t.indexOf("\n[") + 1) : t;
                int end = first.indexOf("-- facts --");
                s.append("-- last start of ").append(p.titleId).append(" (").append(Readiness.ago(f.lastModified(), System.currentTimeMillis()).english()).append(") --\n")   // i18n-keep: the report's marker
                        .append(end >= 0 ? first.substring(0, end) : first);
            } catch (IOException ignored) { /* left out */ }
        }
        return s.toString();
    }

    private static List<Readiness.Check> phoneChecks(Context c) {
        List<Readiness.Check> l = new ArrayList<>();
        boolean arm64 = java.util.Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a");
        l.add(new Readiness.Check(new Msg("readiness_check_phone"), arm64 ? Readiness.Status.PASS : Readiness.Status.FAIL,
                arm64 ? new Msg("readiness_phone_arm64", Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE)
                        : new Msg("readiness_phone_not_arm64", Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE), null));
        ActivityManager am = c.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(m);
        double total = m.totalMem / (double) (1L << 30), avail = m.availMem / (double) (1L << 30);
        l.add(new Readiness.Check(new Msg("readiness_check_memory"), Readiness.Status.INFO,
                m.lowMemory ? new Msg("readiness_memory_low", total, avail) : new Msg("readiness_memory", total, avail), null));
        boolean access = Environment.isExternalStorageManager();
        l.add(new Readiness.Check(new Msg("readiness_check_access"), access ? Readiness.Status.PASS : Readiness.Status.FAIL,
                access ? new Msg("readiness_access_granted") : new Msg("readiness_access_denied"), "access"));
        try {
            File ext = c.getExternalFilesDir(null);
            long free = new StatFs((ext != null ? ext : c.getFilesDir()).getPath()).getAvailableBytes();
            double gb = free / 1e9;
            l.add(new Readiness.Check(new Msg("readiness_check_storage"), gb >= 10 ? Readiness.Status.PASS : Readiness.Status.WARNING,
                    gb >= 10 ? new Msg("readiness_storage_ok", gb) : new Msg("readiness_storage_low", gb), null));
        } catch (IllegalArgumentException e) { /* no such folder: left out */ }
        UsageStatsManager us = c.getSystemService(UsageStatsManager.class);
        if (am.isBackgroundRestricted()) l.add(new Readiness.Check(new Msg("readiness_check_battery"), Readiness.Status.WARNING, new Msg("readiness_battery_restricted"), "app"));
        else if (us != null && us.getAppStandbyBucket() == UsageStatsManager.STANDBY_BUCKET_RESTRICTED)
            l.add(new Readiness.Check(new Msg("readiness_check_battery"), Readiness.Status.WARNING, new Msg("readiness_battery_standby"), "app"));
        else l.add(new Readiness.Check(new Msg("readiness_check_battery"), Readiness.Status.PASS, new Msg("readiness_battery_ok"), null));
        // The game's process (:game) as Android remembers its ends: the newest, and kills for memory in the last 7 days
        try {
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 0);
            ApplicationExitInfo lastGame = null; int kills = 0; long lastKill = 0, since = System.currentTimeMillis() - KILL_WINDOW_MS;
            for (ApplicationExitInfo e : exits) {
                if (e.getProcessName() == null || !e.getProcessName().endsWith(":game")) continue;
                if (lastGame == null || e.getTimestamp() > lastGame.getTimestamp()) lastGame = e;
                if (e.getTimestamp() >= since && memoryKill(e)) { kills++; lastKill = Math.max(lastKill, e.getTimestamp()); }
            }
            if (lastGame == null) l.add(new Readiness.Check(new Msg("readiness_check_last_exit"), Readiness.Status.INFO, new Msg("readiness_last_exit_none"), null));
            else {
                Msg when = Readiness.ago(lastGame.getTimestamp(), System.currentTimeMillis());   // the screen's language on the screen, English in the report
                int why = lastGame.getReason();
                boolean normal = why == ApplicationExitInfo.REASON_EXIT_SELF || why == ApplicationExitInfo.REASON_USER_REQUESTED || why == ApplicationExitInfo.REASON_USER_STOPPED;
                Msg what = memoryKill(lastGame) ? new Msg("readiness_exit_memory") : why == ApplicationExitInfo.REASON_CRASH || why == ApplicationExitInfo.REASON_CRASH_NATIVE ? new Msg("readiness_exit_crashed")
                        : why == ApplicationExitInfo.REASON_ANR ? new Msg("readiness_exit_anr") : normal ? new Msg("readiness_exit_normal") : new Msg("readiness_exit_other", exitName(why));
                String desc = lastGame.getDescription();   // Android's own words, as written
                Msg line = normal ? new Msg("readiness_last_exit_normal", what, when)
                        : desc != null && !desc.isEmpty() ? new Msg("readiness_last_exit_desc", what, when, desc, HomeLabels.settings(), HomeLabels.shareLog())
                        : new Msg("readiness_last_exit", what, when, HomeLabels.settings(), HomeLabels.shareLog());
                l.add(new Readiness.Check(new Msg("readiness_check_last_exit"), normal ? Readiness.Status.PASS : Readiness.Status.WARNING, line, null));
            }
            if (kills > 0) l.add(new Readiness.Check(new Msg("readiness_check_kills"), Readiness.Status.WARNING, Msg.plural("readiness_kills", kills, kills,
                    Readiness.ago(lastKill, System.currentTimeMillis())), null));
            else l.add(new Readiness.Check(new Msg("readiness_check_kills"), Readiness.Status.PASS, new Msg("readiness_kills_none"), null));
        } catch (RuntimeException e) { /* not available on this phone: left out */ }
        if (GameActivity.testApp()) {   // the test app's switches (env.txt): a report says which were on
            File env = new File(c.getExternalFilesDir(null), "env.txt");
            List<String> keys = new ArrayList<>();
            boolean unreadable = false;   // (the file is read whole before any key is taken: unreadable leaves keys empty)
            try {
                if (env.isFile()) for (String kv : GameActivity.envLines(new String(Files.readAllBytes(env.toPath()), StandardCharsets.UTF_8))) keys.add(kv.substring(0, kv.indexOf('=')).trim());
            } catch (IOException | SecurityException e) { unreadable = true; }
            l.add(new Readiness.Check(new Msg("readiness_check_switches"), Readiness.Status.INFO, unreadable ? new Msg("readiness_switches_unreadable")
                    : keys.isEmpty() ? new Msg("readiness_switches_none") : new Msg("readiness_switches_on", String.join(", ", keys)), null));
        }
        return l;
    }

    /** An end for memory: Android's low-memory reason, or a kill whose description names the low-memory killer (phones without the reason report a signal) */
    private static boolean memoryKill(ApplicationExitInfo e) {
        if (e.getReason() == ApplicationExitInfo.REASON_LOW_MEMORY) return true;
        String d = e.getDescription();
        return d != null && (d.contains("lmk") || d.toLowerCase(Locale.ROOT).contains("low memory"));
    }

    private static Msg exitName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_SIGNALED: return new Msg("readiness_exit_reason_signal");
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return new Msg("readiness_exit_reason_resources");
            case ApplicationExitInfo.REASON_FREEZER: return new Msg("readiness_exit_reason_freezer");
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return new Msg("readiness_exit_reason_permission");
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return new Msg("readiness_exit_reason_dependency");
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return new Msg("readiness_exit_reason_init");
            default: return new Msg("readiness_exit_reason_code", reason);
        }
    }

    /** The host and the driver live in this process: leaving ends it, so the next check opens the driver picked then */
    private void leave() {
        finish();
        ui.postDelayed(() -> Process.killProcess(Process.myPid()), 300);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { leave(); }
}
