package dev.nxnative.android;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;

/**
 * The time a running job has left (android/README.md, "Time left while preparing"): PackService feeds it the tools' progress lines and the job's running state, and asks it for the
 * minutes to show on the launcher's Preparing card and in the notification. It estimates the whole job from the step in hand to the end, never only that step.
 * <p>
 * Running time only: the clock runs while the service's worker runs the job ({@link #running}); a pause, a wait for the battery or the heat, and a dead service
 * add nothing. After a resume or a new process the first progress line is a baseline: shaders translated before it are not work done in this run, and the first
 * step forward after it starts the timing again (the tool's set-up before it, a minute or so of workers and checks, is no translating). A resume in the same
 * process goes on from the rate measured before the pause; a new process goes on from the rate its process before kept in job.json ({@link #carry},
 * {@link #carried}) when it resumes the same part with the same total, and says nothing otherwise until it has its own.
 * <p>
 * Each part of the job is one of:
 * <ul>
 * <li>counted: the split's scan of the dump's files and the shaders step's "translate" stage. Its rate is the work done in the last {@link #WINDOW_MS} of working
 *     time, so it follows a phone that heats up and throttles (the POCO F8 Pro's cores fell to 1.4 GHz and phase 1 from 9.9 to 8.6 shaders a second), and the
 *     finish time is smoothed over {@link #SMOOTH_MS} so it does not jump with each line. Working time is running time with each gap between two steps forward
 *     cut to a few of the usual gaps ({@link #GAP_LINES}): a stall, or a phone that sleeps while the job runs, is not slow work. The translation runs the
 *     non-vertex shaders first and the vertex ones after them (vk_android_build.py's two phases); a vertex shader counts as {@link #VERTEX_COST} of another,
 *     when the profile's shader_map.tsv, which the tool takes its shaders from, gives the count of each ({@link #shaderMix}) and the tool translates all of
 *     them (a full build; its total is then the map's count);</li>
 * <li>timed: the shaders step's "library" and "check" stages (no count: the library is written from every result, then read back) and the pack step with the
 *     job's cleanup after it: a fixed time each, counted down;</li>
 * <li>unknown: identify, the rewrite (three modules of very different sizes) and the split's start before its scan ("main"): no estimate.</li>
 * </ul>
 * The steps still to come are added from what is known: the shaders after a split at {@code shaderS} each ({@link #SHADER_S}), the library at
 * {@link #LIBRARY_S} an entry and the pack at {@link #PACK_MS}; a timed part that runs past its figure says {@link #LATE_S} more until it has taken twice it.
 * A counted part says nothing until it has its own measurement ({@link #MIN_MS} of running time with progress, or {@link #MIN_SMALL_MS} and
 * {@link #MIN_SMALL_SHARE} of it for a small job such as a shader update), unless an earlier part of this run had one: then the fixed figure stands in
 * meanwhile. It says nothing either when progress stalls ({@link #STALL_MS}), when a part's total is 0, changes or is passed, or its count goes back, when a
 * timed part has taken twice its figure, or when more than {@link #MAX_S} is left: no estimate rather than a wrong or useless one.
 * <p>
 * The figures are the POCO F8 Pro's on the current build (work/big at 7a33169, 2026-11-14 15:16, 6 workers: android/app/src/jvmtest/prepare-eta-6w-current.trace.txt);
 * dm/tools/tests/test_android_eta.py replays that run and the morning's two (6 and 8 workers, results on shared storage), also with stalls and a pause put in.
 * No Android classes: the JVM test runs it.
 */
final class Eta {
    /** The window of running time the rate is measured over */
    static final long WINDOW_MS = 5 * 60_000L;
    /** A counted part's own rate is used after this much running time from its first progress */
    static final long MIN_MS = 2 * 60_000L;
    /** …or after this much, once this share of its work is done (a shader update of a few hundred shaders is over in a minute) */
    static final long MIN_SMALL_MS = 30_000L;
    static final double MIN_SMALL_SHARE = 0.2;
    /** No progress for this long: no estimate (the translation's slowest gap, its vertex phase's set-up, was about 40 s on the phone) */
    static final long STALL_MS = 3 * 60_000L;
    /** The finish time follows a new estimate with this time constant */
    static final long SMOOTH_MS = 2 * 60_000L;
    /**
     * The rate is measured over working time: a gap between two steps forward adds at most GAP_LINES times the window's usual gap between lines to it (and at
     * least GAP_MIN_MS, GAP_START_MS before the window has its usual gap), so a pause in the progress that the job does not know of (a phone that sleeps, a tool
     * that waits) does not count as slow work. The translation's longest real gap, its vertex phase's set-up, was about 40 s with a line every 6-7 s
     */
    static final double GAP_LINES = 4;
    static final long GAP_MIN_MS = 20_000L, GAP_START_MS = 60_000L;
    /** More than this left: no estimate (a trickle of progress gives a true but useless figure of many hours) */
    static final double MAX_S = 10 * 3600;
    /**
     * A vertex shader's translation time against a non-vertex one's: 0.37-0.40 with 6 workers on the build with the results in the app's storage (10-11 against
     * about 28 a second), 0.43 with 6 and 0.67 with 8 when they were on shared storage (8 workers' vertex phase was held up by I/O). Replayed on the current
     * build's run (android/app/src/jvmtest/prepare-eta-6w-current.trace.txt), 0.35, 0.4, 0.45 and 0.5 had mean errors of 6.2, 6.8, 11.2 and 15.8 %
     */
    static final double VERTEX_COST = 0.4;
    /**
     * The library's write and check, seconds per entry of the library (all of the game's shaders, also for an update): 50-60 s for 42,854 on the phone with the
     * results in the app's storage (work/big at 7a33169, 2026-11-14 15:16; 3.8-4.2 min when they were on shared storage)
     */
    static final double LIBRARY_S = 0.0013;
    /** The pack step and the job's cleanup after it: 6.9 s on the phone, the same run (2 min 8 s and 2 min 12 s when the results were on shared storage) */
    static final long PACK_MS = 10_000L;
    /** A timed part that runs over its figure says this much is left (at most its figure) until it has taken twice its figure */
    static final double LATE_S = 30;
    /**
     * A shader's translation before the translation has its own rate (the shaders after a split, the translation's first minutes), seconds, both phases and the
     * set-up: 3,011 s for 42,854 on the phone, the same run (70 ms; 86 and 95 ms with 6 and 8 workers when the results were on shared storage)
     */
    static final double SHADER_S = 0.070;

    static final int SPLIT = 2, SHADERS = 3, PACK = 4;   // Job.STEPS' indexes

    private enum Kind { UNKNOWN, COUNTED, LIBRARY, PACK }

    /** Shaders the job's shaders step translates, as known before it says (the update's count, else the game's), its library's entries, the seconds a shader */
    private int shaders;
    private final int library;
    private final double shaderS, libraryEntryS;
    private final long packMs;
    /** The profile's map: its shaders and the non-vertex shaders among them (-1: not known) */
    private int mapRows = -1, slow = -1;

    private boolean running;
    private long runMs, lastNow;
    private int step = -1;
    private String stage = "";
    private Kind kind = Kind.UNKNOWN;
    private long phaseStart;
    /** The part's progress: the last line's count and total, its work units at the baseline, the work seen since, and (run ms, work) samples */
    private long done, total = -1;
    private boolean baseline = true, started;
    private double lastUnits, work;
    private int increments;
    private long firstInc = -1, lastInc;
    /** The working-time clock the samples are taken on (ms), and the running time it last caught up with (the last sample, or a baseline) */
    private double workMs;
    private long lastWorkAt;
    /** After a baseline, until the first step forward: that step's work began before it could be timed (the tool's set-up), so it starts the timing again */
    private boolean awaiting = true;
    private final ArrayDeque<double[]> samples = new ArrayDeque<>();
    /** The part's total changed or its count went back: no estimate until it has its own rate again */
    private boolean disturbed;
    /** progress() said that this part's vertex shaders cannot be weighted */
    private boolean told;
    /** A counted part of this run had its own rate, or the library was reached: the fixed figures may stand in */
    private boolean known;
    private double finish = Double.NaN;
    private long finishAt;
    private int shown = -1;
    /** The carried rate's samples are this far apart in the window (the translation's lines come every 6-7 s; the window then thins as it would) */
    static final long CARRY_STEP_MS = 10_000L;
    /** A rate a process before this one measured for the job ({@link #carried}), not yet matched to a part: taken at this process's first counted line */
    private double[] pending;
    /** The part's rate so far is the one carried over: its samples are that rate's two points until this run's own replace them (within WINDOW_MS) */
    private boolean carriedOver;

    /** shaders: what the shaders step will translate as far as known; library: the entries of the library it writes; shaderS: seconds a shader before measuring */
    Eta(int shaders, int library, double shaderS) { this(shaders, library, shaderS, LIBRARY_S, PACK_MS); }

    /** …with another build's library and pack figures (the JVM test's replay of a run on an older build) */
    Eta(int shaders, int library, double shaderS, double libraryEntryS, long packMs) {
        this.shaders = Math.max(0, shaders);
        this.library = Math.max(0, library);
        this.shaderS = shaderS;
        this.libraryEntryS = libraryEntryS;
        this.packMs = packMs;
    }

    /**
     * The profile's shader_map.tsv, the table vk_android_build.py takes its shaders from: {shaders, non-vertex shaders}, as the tool counts them (its "loc" and
     * STAGE_OF): a row of more than four columns whose package file ends in .bin is a shader, each key (the fourth column) once, at its first row; a vertex shader
     * is one whose package file's name starts with "vs". null when the map has no shader
     */
    static int[] shaderMix(File map) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(map.toPath(), StandardCharsets.UTF_8)) {
            java.util.HashSet<String> keys = new java.util.HashSet<>();
            int slow = 0;
            for (String l; (l = r.readLine()) != null; ) {
                String[] c = l.split("\t", -1);
                if (c.length <= 4 || !c[0].endsWith(".bin") || !keys.add(c[3])) continue;
                if (!c[0].startsWith("vs")) slow++;
            }
            return keys.isEmpty() ? null : new int[] {keys.size(), slow};
        }
    }

    /**
     * The profile's map as shaderMix read it: the translation's two phases are weighted when the tool translates every shader of it (a full build; its total
     * is then the map's count). An update, which translates only some, counts every shader alike
     */
    synchronized void mix(int[] m) {
        if (m == null) { mapRows = slow = -1; return; }
        mapRows = m[0];
        slow = m[1];
    }

    private void clock(long now) {
        if (running && now > lastNow) runMs += now - lastNow;
        lastNow = Math.max(lastNow, now);   // a caller's time read before it took the lock may be older than the last one: no interval twice
    }

    /**
     * The job runs (true) or not (paused, waiting, stopped, failed, done): only running time counts. After a start the first line is a baseline; the rate measured
     * before a pause in this process is kept (the clock stood still meanwhile), and the window replaces it within WINDOW_MS
     */
    synchronized void running(boolean on, long now) {
        clock(now);
        if (on && !running) {
            baseline = true;
            awaiting = true;
            finish = Double.NaN;
            shown = -1;
            lastInc = runMs;
        }
        running = on;
    }

    /** Step i (Job.STEPS) starts: its first part, before any line. The same step again (a resume) keeps what its part measured, with a baseline */
    synchronized void step(int i, long now) {
        clock(now);
        String s = i == SHADERS ? "translate" : i == SPLIT ? "main" : "";
        if (i == step && kindOf(i, s) == kind && kind == Kind.COUNTED) {
            baseline = true;
            awaiting = true;
            lastInc = runMs;
            phaseStart = runMs;
            return;
        }
        enter(i, s);
    }

    /**
     * A progress line of step i: its count, total and stage (the tools' "progress" lines). true once a part when the translation's total is the whole game's
     * (a full build) but not the profile map's count, so its vertex shaders cannot be weighted: the caller logs it
     */
    synchronized boolean progress(int i, long d, long t, String st, long now) {
        clock(now);
        String s = st == null ? "" : st;
        if (i != step || kindOf(i, s) != kind || kind == Kind.UNKNOWN && !s.equals(stage)) enter(i, s);
        stage = s;
        if (kind != Kind.COUNTED) return false;
        if (started && (t != total || d < done)) {   // another total (a new run of the part), or the count went back: start over, say nothing meanwhile
            disturbed = true;
            carriedOver = false;
            baseline = true;
            firstInc = -1;
            samples.clear();
            work = 0;
            increments = 0;
            told = false;
        }
        started = true;
        done = d;
        total = t;
        boolean unweighted = false;
        if (!told && step == SHADERS && mapRows >= 0 && t != mapRows && (t == library || t > mapRows)) told = unweighted = true;
        double u = units(d);
        if (baseline) {   // the first line of the part or of this run: what was done before is not this run's work
            baseline = false;
            awaiting = true;
            lastUnits = u;
            lastInc = runMs;
            lastWorkAt = runMs;   // the time since the last sample (a pause's start, a resumed tool's set-up) is no working time
            takeCarried();
            return unweighted;
        }
        if (u <= lastUnits) return unweighted;
        if (awaiting) {   // the first step forward after a baseline: its work began before it could be timed (the tool's set-up), so the timing starts again here
            awaiting = false;
            if (firstInc < 0) firstInc = runMs;
            if (samples.isEmpty()) samples.addLast(new double[] {workMs, work});
        } else {
            workMs += Math.min(runMs - lastWorkAt, gapCap());
            work += u - lastUnits;
            increments++;
            samples.addLast(new double[] {workMs, work});
            while (samples.size() > 2 && samples.peekFirst()[0] < workMs - WINDOW_MS && secondTime() <= workMs - WINDOW_MS) samples.removeFirst();
        }
        lastUnits = u;
        lastInc = runMs;
        lastWorkAt = runMs;
        return unweighted;
    }

    /** The most a gap between two steps forward adds to the working time: GAP_LINES times the window's mean gap, at least GAP_MIN_MS (GAP_START_MS at first) */
    private double gapCap() {
        if (samples.size() < 3) return GAP_START_MS;
        return Math.max(GAP_MIN_MS, GAP_LINES * (samples.peekLast()[0] - samples.peekFirst()[0]) / (samples.size() - 1));
    }

    private double secondTime() {
        java.util.Iterator<double[]> it = samples.iterator();
        it.next();
        return it.next()[0];
    }

    private static Kind kindOf(int i, String s) {
        if (i == SPLIT) return s.equals("main") ? Kind.UNKNOWN : Kind.COUNTED;
        if (i == SHADERS) return s.equals("library") || s.equals("check") || s.equals("done") ? Kind.LIBRARY : Kind.COUNTED;
        if (i == PACK) return Kind.PACK;
        return Kind.UNKNOWN;
    }

    private void enter(int i, String s) {
        step = i;
        stage = s;
        kind = kindOf(i, s);
        phaseStart = runMs;
        done = 0;
        total = -1;
        started = false;
        baseline = true;
        awaiting = true;
        told = false;
        firstInc = -1;
        lastInc = runMs;
        lastWorkAt = runMs;
        work = 0;
        increments = 0;
        samples.clear();
        disturbed = false;
        carriedOver = false;
        if (kind == Kind.LIBRARY) known = true;
    }

    /**
     * What a later process of the same job may go on from (PackService keeps it in job.json, "eta_carry"): {step, the part's total, the profile map's shaders
     * (-1: not known), work units a second}, the rate this part measured itself (as raw() takes it); null when it has none now (no counted part, not yet its own
     * rate, its total changed or its count went back)
     */
    synchronized double[] carry() {
        if (pending != null) return pending.clone();   // offered and not yet matched (this process's start): kept for the next one
        if (kind != Kind.COUNTED || disturbed || !started || total <= 1) return null;
        double r = ownRate();
        return r > 0 ? new double[] {step, total, mapRows, r} : null;
    }

    /**
     * A rate a process before this one measured for this job (carry(), from job.json), offered once: this process's first counted line takes it when it is the
     * same part with the same total and map (the same step resumed from its kept work), and it then stands in for this run's own rate from that line on, as a
     * rate measured before a pause in this process does (running): samples over WINDOW_MS of working time at that rate, which this run's own replace as they come.
     * Without it a new process said nothing for over 2 minutes after every resume (the phone test of 2026-11-14: 2:15-2:35). Anything else drops it
     */
    synchronized void carried(double[] c) { pending = c != null && c.length == 4 && c[3] > 0 ? c.clone() : null; }

    /** Whether the part's rate is the one carried over from a process before (the log says so) */
    synchronized boolean carriedOver() { return carriedOver; }

    private void takeCarried() {
        double[] c = pending;
        pending = null;
        if (c == null || kind != Kind.COUNTED || (int) c[0] != step || (long) c[1] != total || (int) c[2] != mapRows || increments > 0) return;
        samples.clear();   // the window as a run at that rate leaves it, a sample every CARRY_STEP_MS: this run's own replace them as they would its own
        for (long back = WINDOW_MS; back >= 0; back -= CARRY_STEP_MS) samples.addLast(new double[] {workMs - back, work - c[3] * back / 1000.0});
        carriedOver = true;
        known = true;
    }

    /** The part's own rate (work units a second, as measured: may be 0) once it has one, -1 while it has none */
    private double ownRate() {
        long span = firstInc < 0 ? 0 : runMs - firstInc;
        boolean own = carriedOver || increments >= 3 && (span >= MIN_MS || span >= MIN_SMALL_MS && work >= MIN_SMALL_SHARE * units(Math.max(total, 0)));
        if (!own || samples.size() < 2) return -1;
        double[] a = samples.peekFirst(), b = samples.peekLast();
        return (b[1] - a[1]) / Math.max(1.0, (b[0] - a[0]) / 1000.0);
    }

    /** Work units of a count: in the translation (a full build, the map known) a vertex shader is VERTEX_COST of another; else one each */
    private double units(long d) {
        if (step != SHADERS || slow < 0 || total != mapRows || d <= slow) return d;
        return slow + (d - slow) * VERTEX_COST;
    }

    private double libraryS() { return library * libraryEntryS; }

    /**
     * A timed part's seconds left: its budget counted down, then, once it runs over, LATE_S (at most the budget) until it has taken twice its budget (and at
     * least a minute more): a part a little slower than its figure says "a moment more", one far slower says nothing
     */
    private static double timed(double budget, double el) {
        if (el > Math.max(2 * budget, budget + 60)) return Double.NaN;
        return Math.max(budget - el, Math.min(budget, LATE_S));
    }

    /** Seconds left in the whole job, unsmoothed; NaN for none */
    private double raw() {
        if (!running) return Double.NaN;
        long sinceInc = runMs - Math.max(lastInc, phaseStart);
        double packS = packMs / 1000.0;
        switch (kind) {
            case LIBRARY: {
                double lib = timed(libraryS(), (runMs - phaseStart) / 1000.0);
                return lib + packS;
            }
            case PACK: {
                if (!known) return Double.NaN;
                return timed(packS, (runMs - phaseStart) / 1000.0);
            }
            case COUNTED: {
                if (sinceInc > STALL_MS) return Double.NaN;
                if (started && (total <= 0 || done > total)) return Double.NaN;   // no count to go on
                double rest;
                double rate = ownRate();
                if (rate >= 0) {
                    if (rate <= 0) return Double.NaN;
                    // the time since the last step forward counts down, as working time: not past the gap's cap, and not while a tool sets up after a baseline
                    double since = awaiting || baseline ? 0 : Math.min(runMs - lastWorkAt, gapCap()) / 1000.0;
                    rest = Math.max(0, (units(total) - units(done)) / rate - since);
                    known = true;
                } else if (known && !disturbed && step == SHADERS) {   // the translation's start: the launcher's figure until its own rate
                    long t = started ? total : shaders;
                    rest = Math.max(0, t - (started ? done : 0)) * shaderS;
                } else return Double.NaN;
                if (step == SPLIT) rest += shaders * shaderS;
                rest += libraryS() + packS;
                return rest > MAX_S ? Double.NaN : rest;
            }
            default:
                return Double.NaN;
        }
    }

    /** Seconds left in the whole job, smoothed; NaN when there is no estimate */
    synchronized double seconds(long now) {
        clock(now);
        double r = raw();
        if (Double.isNaN(r)) { finish = Double.NaN; return r; }
        double t = runMs / 1000.0, f = t + r;
        if (Double.isNaN(finish)) finish = f;
        else {
            double dt = Math.max(0, runMs - finishAt);
            finish += (f - finish) * dt / (SMOOTH_MS + dt);
        }
        finishAt = runMs;
        return Math.max(0, finish - t);
    }

    /** The minutes to show (0: less than a minute), -1 for none: rounded ({@link #round}), and kept while the estimate stays near them (no flicker between two) */
    synchronized int minutes(long now) {
        double s = seconds(now);
        lastSeconds = s;
        shown = Double.isNaN(s) ? -1 : keep(s, shown);
        return shown;
    }

    /** The seconds behind the last minutes() (NaN for none): the JVM test's replay measures its error */
    private double lastSeconds = Double.NaN;

    synchronized double lastSeconds() { return lastSeconds; }

    /** The running time counted so far (ms): the JVM test checks the clock */
    synchronized long runMs() { return runMs; }

    /** The shaders the shaders step translates, once known better (the tool's own total) */
    synchronized void shaders(int n) { if (n >= 0) shaders = n; }

    /**
     * Seconds as minutes to show: 0 under a minute, whole minutes under 20 (at least 1), then multiples of 5 (an hour and more is said as hours and minutes)
     */
    static int round(double s) {
        if (!(s >= 60)) return 0;
        double m = Math.min(s, MAX_S) / 60;   // (no int overflow; raw() gives nothing above MAX_S)
        if (m < 19.5) return (int) Math.max(1, Math.round(m));
        return (int) Math.round(m / 5) * 5;
    }

    /** round(s), or last while s is within a tenth of last's rounding step of rounding to it (no flicker at a rounding boundary) */
    static int keep(double s, int last) {
        int r = round(s);
        if (last < 0 || r == last) return r;
        double d = (last < 20 ? 60 : 300) * 0.1;
        return round(s + d) == last || round(s - d) == last ? last : r;
    }

    /** The minutes in words: "Less than a minute left", "About 45 minutes left", "About 1 hour 15 minutes left" */
    static Msg text(int minutes) {
        if (minutes <= 0) return new Msg("prepare_eta_less");
        if (minutes < 60) return Msg.plural("prepare_eta_minutes", minutes, minutes);
        int h = minutes / 60, m = minutes % 60;
        return m == 0 ? Msg.plural("prepare_eta_hours", h, h) : Msg.plural("prepare_eta_hours_minutes", h, h, m);
    }

    /**
     * The minutes in short, for the running notification's collapsed title after the state ("Preparing · 45 min"): "<1 min", "45 min", "1 h 15 min" (the
     * same rounding as text). The phone check of rc1 (HyperOS, 2026-11-15) showed only "45 dk kaldı ·…" of "45 dk kaldı · Hazırlanıyor: <game>"
     */
    static Msg compactText(int minutes) {
        if (minutes <= 0) return new Msg("prepare_eta_compact_less");
        if (minutes < 60) return new Msg("prepare_eta_compact_minutes", minutes);
        int h = minutes / 60, m = minutes % 60;
        return m == 0 ? new Msg("prepare_eta_compact_hours", h) : new Msg("prepare_eta_compact_hours_minutes", h, m);
    }
}
