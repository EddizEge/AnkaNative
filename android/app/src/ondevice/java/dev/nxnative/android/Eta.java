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
 * add nothing. After a resume or a new process the first progress line is a baseline: shaders translated before it are not work done in this run.
 * <p>
 * Each part of the job is one of:
 * <ul>
 * <li>counted: the split's scan of the dump's files and the shaders step's "translate" stage. Its rate is the work done in the last {@link #WINDOW_MS} of running
 *     time, so it follows a phone that heats up and throttles (the POCO F8 Pro's cores fell to 1.4 GHz and phase 1 from 9.9 to 8.6 shaders a second), and the
 *     finish time is smoothed over {@link #SMOOTH_MS} so it does not jump with each line. The translation runs the non-vertex shaders first and the vertex ones
 *     after them (vk_android_build.py's two phases); a vertex shader counts as {@link #VERTEX_COST} of another (21 against 9.3 a second on that phone), when the
 *     split's shader_map.tsv gives the count of each ({@link #shaderMix}) and the tool translates all of them (a full build);</li>
 * <li>timed: the shaders step's "library" and "check" stages (no count: the library is written from every result, then read back) and the pack step with the
 *     job's cleanup after it: a fixed time each, counted down;</li>
 * <li>unknown: identify, the rewrite (three modules of very different sizes) and the split's start before its scan ("main"): no estimate.</li>
 * </ul>
 * The steps still to come are added from what is known: the shaders after a split at {@code shaderS} each (the launcher's own figure, ShaderLibs.SHADER_S_HI),
 * the library at {@link #LIBRARY_S} an entry and the pack at {@link #PACK_MS}. A counted part says nothing until it has its own measurement ({@link #MIN_MS} of
 * running time with progress, or {@link #MIN_SMALL_MS} and {@link #MIN_SMALL_SHARE} of it for a small job such as a shader update), unless an earlier part of
 * this run had one: then the fixed figure stands in meanwhile. It says nothing either when progress stalls ({@link #STALL_MS}), when a part's total changes or
 * its count goes back, or when a timed part runs over its time: no estimate rather than a wrong one.
 * <p>
 * The constants were picked by replaying the two full Prepares measured on the POCO F8 Pro on 2026-11-14 (dm/tools/tests/test_android_eta.py, the trace in
 * android/app/src/jvmtest/prepare-eta-6w.trace.txt). No Android classes: the JVM test runs it.
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
     * A vertex shader's translation time against a non-vertex one's: 0.43 with 6 workers (9.3 against 21.5 a second), 0.67 with 8, whose vertex phase was held up
     * by I/O. Of 0.43, 0.5, 0.6 and 0.67 replayed on both traces, 0.5 had the smallest mean error on the 6-worker run (the default; 0.6 on the 8-worker one)
     */
    static final double VERTEX_COST = 0.5;
    /** The library's write and check, seconds per entry of the library (all of the game's shaders, also for an update): 3.8-4.2 min for 42,346 on the phone */
    static final double LIBRARY_S = 0.006;
    /** The pack step and the job's cleanup after it: 2 min 8 s and 2 min 12 s on the phone */
    static final long PACK_MS = 120_000L;

    static final int SPLIT = 2, SHADERS = 3, PACK = 4;   // Job.STEPS' indexes

    private enum Kind { UNKNOWN, COUNTED, LIBRARY, PACK }

    /** Shaders the job's shaders step translates, as known before it says (the update's count, else the game's), its library's entries, the seconds a shader */
    private int shaders;
    private final int library;
    private final double shaderS;
    /** The split's map: its rows and the non-vertex shaders among them (-1: not known) */
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
    private final ArrayDeque<double[]> samples = new ArrayDeque<>();
    /** The part's total changed or its count went back: no estimate until it has its own rate again */
    private boolean disturbed;
    /** A counted part of this run had its own rate, or the library was reached: the fixed figures may stand in */
    private boolean known;
    private double finish = Double.NaN;
    private long finishAt;
    private int shown = -1;

    /** shaders: what the shaders step will translate as far as known; library: the entries of the library it writes; shaderS: seconds a shader before measuring */
    Eta(int shaders, int library, double shaderS) {
        this.shaders = Math.max(0, shaders);
        this.library = Math.max(0, library);
        this.shaderS = shaderS;
    }

    /** The split's shader_map.tsv: {rows, non-vertex rows} (the "stage" column), null when it has no such column or a row lacks it */
    static int[] shaderMix(File map) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(map.toPath(), StandardCharsets.UTF_8)) {
            int rows = 0, slow = 0, col = -1;
            for (String l; (l = r.readLine()) != null; ) {
                if (l.isEmpty() || l.startsWith("#")) continue;
                String[] c = l.split("\t");
                if (col < 0) {   // the header row names the columns
                    col = java.util.Arrays.asList(c).indexOf("stage");
                    if (col < 0) return null;
                    continue;
                }
                if (c.length <= col) return null;
                rows++;
                if (!c[col].equals("vertex")) slow++;
            }
            return col < 0 ? null : new int[] {rows, slow};
        }
    }

    /** The split's map as shaderMix read it: the translation's two phases are weighted when the tool translates every row of it */
    synchronized void mix(int[] m) {
        if (m == null) { mapRows = slow = -1; return; }
        mapRows = m[0];
        slow = m[1];
    }

    private void clock(long now) {
        if (running && now > lastNow) runMs += now - lastNow;
        lastNow = now;
    }

    /**
     * The job runs (true) or not (paused, waiting, stopped, failed, done): only running time counts. After a start the first line is a baseline; the rate measured
     * before a pause in this process is kept (the clock stood still meanwhile), and the window replaces it within WINDOW_MS
     */
    synchronized void running(boolean on, long now) {
        clock(now);
        if (on && !running) {
            baseline = true;
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
            lastInc = runMs;
            phaseStart = runMs;
            return;
        }
        enter(i, s);
    }

    /** A progress line of step i: its count, total and stage (the tools' "progress" lines) */
    synchronized void progress(int i, long d, long t, String st, long now) {
        clock(now);
        String s = st == null ? "" : st;
        if (i != step || kindOf(i, s) != kind || kind == Kind.UNKNOWN && !s.equals(stage)) enter(i, s);
        stage = s;
        if (kind != Kind.COUNTED) return;
        if (started && (t != total || d < done)) {   // another total (a new run of the part), or the count went back: start over, say nothing meanwhile
            disturbed = true;
            baseline = true;
            firstInc = -1;
            samples.clear();
            work = 0;
            increments = 0;
        }
        started = true;
        done = d;
        total = t;
        double u = units(d);
        if (baseline) {   // the first line of the part or of this run: what was done before is not this run's work
            baseline = false;
            lastUnits = u;
            lastInc = runMs;
            return;
        }
        if (u <= lastUnits) return;
        if (firstInc < 0) {   // the first step forward: its work began before we could time it (a worker's set-up), so the rate starts here
            firstInc = runMs;
        } else {
            work += u - lastUnits;
            increments++;
        }
        lastUnits = u;
        lastInc = runMs;
        samples.addLast(new double[] {runMs, work});
        while (samples.size() > 2 && samples.peekFirst()[0] < runMs - WINDOW_MS && secondTime() <= runMs - WINDOW_MS) samples.removeFirst();
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
        firstInc = -1;
        lastInc = runMs;
        work = 0;
        increments = 0;
        samples.clear();
        disturbed = false;
        if (kind == Kind.LIBRARY) known = true;
    }

    /** Work units of a count: in the translation (a full build, the map known) a vertex shader is VERTEX_COST of another; else one each */
    private double units(long d) {
        if (step != SHADERS || slow < 0 || total != mapRows || d <= slow) return d;
        return slow + (d - slow) * VERTEX_COST;
    }

    private double libraryS() { return library * LIBRARY_S; }

    /** Seconds left in the whole job, unsmoothed; NaN for none */
    private double raw() {
        if (!running) return Double.NaN;
        long sinceInc = runMs - Math.max(lastInc, phaseStart);
        switch (kind) {
            case LIBRARY: {
                double el = (runMs - phaseStart) / 1000.0;
                return el > libraryS() ? Double.NaN : libraryS() - el + PACK_MS / 1000.0;
            }
            case PACK: {
                if (!known) return Double.NaN;
                long el = runMs - phaseStart;
                return el > PACK_MS ? Double.NaN : (PACK_MS - el) / 1000.0;
            }
            case COUNTED: {
                if (sinceInc > STALL_MS) return Double.NaN;
                double rest;
                long span = firstInc < 0 ? 0 : runMs - firstInc;
                boolean own = increments >= 3 && (span >= MIN_MS || span >= MIN_SMALL_MS && work >= MIN_SMALL_SHARE * units(Math.max(total, 0)));
                if (own) {
                    double[] a = samples.peekFirst(), b = samples.peekLast();
                    double rate = (b[1] - a[1]) / Math.max(1.0, (b[0] - a[0]) / 1000.0);
                    if (rate <= 0) return Double.NaN;
                    rest = Math.max(0, (units(total) - units(done)) / rate - (runMs - b[0]) / 1000.0);
                    known = true;
                } else if (known && !disturbed && step == SHADERS) {   // the translation's start: the launcher's figure until its own rate
                    long t = started ? total : shaders;
                    rest = Math.max(0, t - (started ? done : 0)) * shaderS;
                } else return Double.NaN;
                if (step == SPLIT) rest += shaders * shaderS;
                return rest + libraryS() + PACK_MS / 1000.0;
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

    /** The shaders the shaders step translates, once known better (the tool's own total) */
    synchronized void shaders(int n) { if (n >= 0) shaders = n; }

    /**
     * Seconds as minutes to show: 0 under a minute, whole minutes under 20 (at least 1), then multiples of 5 (an hour and more is said as hours and minutes)
     */
    static int round(double s) {
        if (s < 60) return 0;
        double m = s / 60;
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
}
