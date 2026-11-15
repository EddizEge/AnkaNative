package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Eta on the JVM, driven by dm/tools/tests/test_android_eta.py. Argument: the folder with the trace files (android/app/src/jvmtest). Prints "ok &lt;case&gt;" or
 * "FAIL &lt;case&gt;: …" for each case, a "replay" line for each trace and point of the run, and "all ok" at the end (exit 1 on any failure).
 */
public final class EtaJvmTest {
    private static int failed;
    private static final double S = Eta.SHADER_S;   // as PackService passes it
    private static final int SPLIT = 2, SHADERS = 3, PACK = 4;

    private static void check(String name, boolean ok, String why) {
        if (ok) return;
        failed++;
        System.out.println("FAIL " + name + ": " + why);
    }

    private static void ok(String name) { System.out.println("ok " + name); }

    /** A job's clock in ms (any origin: Eta takes elapsedRealtime) */
    private static final class Run {
        final Eta e;
        long now = 1_000_000L;
        Run(Eta e) { this.e = e; }
        void at(double seconds) { now = 1_000_000L + Math.round(seconds * 1000); }
        int min() { return e.minutes(now); }
        double sec() { e.minutes(now); return e.lastSeconds(); }
    }

    /** Translation lines of 64 shaders at rate(t) shaders a second from t0 until done reaches until; → the time reached */
    interface Rate { double at(double t); }

    private static double translate(Run r, double t0, long from, long until, long total, Rate rate, int step, String stage) {
        double t = t0;
        long d = from;
        r.at(t);
        r.e.progress(step, d, total, stage, r.now);
        while (d < until) {
            long n = Math.min(64, until - d);
            t += n / rate.at(t);
            d += n;
            r.at(t);
            r.e.progress(step, d, total, stage, r.now);
        }
        return t;
    }

    static double lib(int n) { return n * Eta.LIBRARY_S; }

    static double pack() { return Eta.PACK_MS / 1000.0; }

    static boolean near(double got, double want, double rel) { return !Double.isNaN(got) && Math.abs(got - want) <= rel * want; }

    /** The measured Prepares: 6 and 8 workers on the 11-14 morning's build, 6 on the current one (work/big at 7a33169, the results in the app's storage) */
    static final String[] TRACES = {"6w", "8w", "6w-current"};

    public static void main(String[] a) throws IOException {
        steady();
        falling();
        pauseResume();
        restart();
        stall();
        totalChange();
        tinyUpdate();
        stepChange();
        library();
        words();
        mix();
        zero();
        trickle();
        unweighted();
        takeover();
        for (String w : TRACES) hostile(new File(a[0], "prepare-eta-" + w + ".trace.txt"), w);
        for (String w : TRACES) replay(new File(a[0], "prepare-eta-" + w + ".trace.txt"), w);
        carried();
        for (String w : TRACES) carriedReplay(new File(a[0], "prepare-eta-" + w + ".trace.txt"), w);
        if (failed > 0) {
            System.out.println(failed + " failed");
            System.exit(1);
        }
        System.out.println("all ok");
    }

    /** A steady 10 shaders a second: nothing before its own rate (no earlier part measured), then the true time left */
    static void steady() {
        Run r = new Run(new Eta(6000, 6000, S));
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        double t = translate(r, 0, 0, 640, 6000, x -> 10, SHADERS, "translate");   // 64 s
        check("steady", r.min() == -1, "an estimate after 64 s: " + r.min());
        t = translate(r, t, 640, 3000, 6000, x -> 10, SHADERS, "translate");
        double want = (6000 - 3000) / 10.0 + lib(6000) + pack();
        check("steady", near(r.sec(), want, 0.02), "got " + r.sec() + " s, want " + want);
        ok("steady");
    }

    /** A phone heating up: the rate falls from 10 to 5 a second over 40 minutes. The estimate follows the last minutes, not the average since the start */
    static void falling() {
        Run r = new Run(new Eta(30000, 30000, S));
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        Rate rate = x -> 10 - 5 * Math.min(1, x / 2400);
        // to 30 minutes
        double t = 0;
        long d = 0;
        r.at(0);
        r.e.progress(SHADERS, 0, 30000, "translate", r.now);
        while (t < 1800) {
            t += 64 / rate.at(t);
            d += 64;
            r.at(t);
            r.e.progress(SHADERS, d, 30000, "translate", r.now);
            r.min();   // the service asks at each line (publish)
        }
        double left = r.sec() - lib(30000) - pack(), implied = (30000 - d) / left, avg = d / t, now = rate.at(t);
        check("falling", Math.abs(implied - now) <= 0.1 * now, String.format(Locale.ROOT, "implied rate %.2f/s, the current %.2f/s", implied, now));
        check("falling", implied < 0.9 * avg, String.format(Locale.ROOT, "implied rate %.2f/s, near the average since the start %.2f/s", implied, avg));
        ok("falling");
    }

    /** Paused for 30 minutes: no estimate while paused; after the resume the time left is what it was, not stretched by the pause or shortened by its count */
    static void pauseResume() {
        Run r = new Run(new Eta(20000, 20000, S));
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        double t = translate(r, 0, 0, 3200, 20000, x -> 10, SHADERS, "translate");   // 320 s
        double before = r.sec();
        r.e.running(false, r.now);
        check("pause", r.min() == -1, "an estimate while paused: " + r.min());
        r.at(t + 1800);
        check("pause", r.min() == -1, "an estimate while paused: " + r.min());
        // resumed: the tool's first line at once (the results already there), then 70 s of set-up (its workers, the delta check, the results scan) and the
        // first batch, then 10 a second again. The set-up is no translating: the time left holds through it and is the true one after it
        r.at(t + 1800);
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        r.e.progress(SHADERS, 3200, 20000, "translate", r.now);
        double resumed = r.sec();
        check("resume", near(resumed, before, 0.02), "at the resume " + resumed + " s, before the pause " + before);
        r.at(t + 1800 + 60);
        check("resume", near(r.sec(), resumed, 0.05), "60 s into the set-up " + r.sec() + " s, at the resume " + resumed);
        int top = r.min();
        t = translate(r, t + 1800 + 70, 3264, 3264 + 640, 20000, x -> 10, SHADERS, "translate");
        double after = r.sec(), want = (20000 - 3904) / 10.0 + lib(20000) + pack();
        // (with the 30 minutes counted the rate would be a sixth of the true one and the estimate hours; with the 70 s set-up counted, a fifth over)
        check("resume", near(after, want, 0.03), "got " + after + " s, want " + want + " (before the pause " + before + ")");
        check("resume", r.min() <= top, "the shown minutes rose from " + top + " to " + r.min());
        // a slower phone, a line every 21 s: a 70 s set-up is within the gap a line may take (Eta.GAP_LINES), so only the restart of the timing at the first
        // batch keeps it out of the rate (counted, the estimate was a sixth over)
        Run q = new Run(new Eta(20000, 20000, S));
        q.e.running(true, q.now);
        q.e.step(SHADERS, q.now);
        double u = translate(q, 0, 0, 1280, 20000, x -> 3, SHADERS, "translate");
        q.e.running(false, q.now);
        q.at(u + 600);
        q.e.running(true, q.now);
        q.e.step(SHADERS, q.now);
        q.e.progress(SHADERS, 1280, 20000, "translate", q.now);
        u = translate(q, u + 600 + 70, 1344, 1344 + 192, 20000, x -> 3, SHADERS, "translate");
        double slow = q.sec(), wantSlow = (20000 - 1536) / 3.0 + lib(20000) + pack();
        check("resume", near(slow, wantSlow, 0.04), "a line every 21 s: got " + slow + " s, want " + wantSlow);
        ok("pause and resume");
    }

    /** The service restarted in the middle of the shaders step: 20,000 shaders already there are not this run's work */
    static void restart() {
        Run r = new Run(new Eta(42346, 42346, S));
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        double t = translate(r, 0, 20000, 20000 + 640, 42346, x -> 10, SHADERS, "translate");
        check("restart", r.min() == -1, "an estimate before its own rate: " + r.min());
        t = translate(r, t, 20640, 22000, 42346, x -> 10, SHADERS, "translate");
        double want = (42346 - 22000) / 10.0 + lib(42346) + pack();
        check("restart", near(r.sec(), want, 0.03), "got " + r.sec() + " s, want " + want);
        ok("service restart");
    }

    /** No line for more than STALL_MS: no estimate; progress again: an estimate again */
    static void stall() {
        Run r = new Run(new Eta(20000, 20000, S));
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        double t = translate(r, 0, 0, 3000, 20000, x -> 10, SHADERS, "translate");
        double v0 = r.sec();
        for (double x = t + 10; x <= t + 170; x += 10) { r.at(x); r.min(); }   // asked every 10 s, as the service does
        check("stall", r.min() > 0, "no estimate 170 s after the last line");
        // no progress is no work done: the time left counts down for the gap a line may take, then holds, not through the whole stall (the smoothing,
        // which follows the finish time, still takes off about 85 s of the 145 s it held)
        check("stall", r.sec() >= v0 - 125, "170 s after the last line " + r.sec() + " s, at it " + v0);
        r.at(t + 200);
        check("stall", r.min() == -1, "an estimate 200 s after the last line: " + r.min());
        t = translate(r, t + 200, 3000, 3128, 20000, x -> 10, SHADERS, "translate");
        // the 200 s without work are not slow work: the time left is what the rate says, give or take the Eta.GAP_LINES line gaps the stall may add to the
        // window's working time (with the whole gap counted it was 2.5 times the true time)
        double want = (20000 - 3128) / 10.0 + lib(20000) + pack();
        check("stall", near(r.sec(), want, 0.08), "after the stall " + r.sec() + " s, want " + want);
        t = translate(r, t, 3128, 6200, 20000, x -> 10, SHADERS, "translate");   // the stall out of the window
        want = (20000 - 6200) / 10.0 + lib(20000) + pack();
        check("stall", near(r.sec(), want, 0.03), "later " + r.sec() + " s, want " + want);
        ok("stall");
    }

    /** The part's total changes (the tool's own total, another run of it): nothing until it has its own rate again */
    static void totalChange() {
        Run r = new Run(new Eta(10000, 10000, S));
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        double t = translate(r, 0, 0, 3000, 10000, x -> 10, SHADERS, "translate");
        check("total", r.min() > 0, "no estimate before the change");
        t = translate(r, t, 3000, 3064, 12000, x -> 10, SHADERS, "translate");
        check("total", r.min() == -1, "an estimate right after the total changed: " + r.min());
        t = translate(r, t, 3064, 5000, 12000, x -> 10, SHADERS, "translate");
        double want = (12000 - 5000) / 10.0 + lib(10000) + pack();
        check("total", near(r.sec(), want, 0.03), "got " + r.sec() + " s, want " + want);
        ok("total changes");
    }

    /**
     * A shader update (0.2.2's library to this one): a delta split of a few files, 584 shaders at 10 a second, then the whole library written. An estimate once
     * a fifth is done, and one all through the library's write
     */
    static void tinyUpdate() {
        Run r = new Run(new Eta(584, 42346, S));
        r.e.running(true, r.now);
        r.e.step(SPLIT, r.now);
        r.e.progress(SPLIT, 0, 1, "main", r.now);
        r.at(5);
        r.e.progress(SPLIT, 0, 35, "", r.now);
        for (int f = 1; f <= 35; f++) { r.at(5 + f * 0.8); r.e.progress(SPLIT, f, 35, "", r.now); }
        check("tiny", r.min() == -1, "an estimate from a 30 s split: " + r.min());
        r.at(36);
        r.e.step(SHADERS, r.now);
        double t = translate(r, 37, 0, 192, 584, x -> 10, SHADERS, "translate");   // 19 s in
        check("tiny", r.min() == -1, "an estimate after 19 s: " + r.min());
        t = translate(r, t, 192, 448, 584, x -> 10, SHADERS, "translate");   // 45 s in
        double want = (584 - 448) / 10.0 + lib(42346) + pack();
        check("tiny", near(r.sec(), want, 0.05), "got " + r.sec() + " s, want " + want);
        t = translate(r, t, 448, 584, 584, x -> 10, SHADERS, "translate");
        r.e.progress(SHADERS, 584, 584, "library", r.now);
        int lowest = Integer.MAX_VALUE, highest = -1;
        for (double x = t; x < t + lib(42346); x += 5) { r.at(x); lowest = Math.min(lowest, r.min()); highest = Math.max(highest, r.min()); }
        // the whole library is written (55 s for 42,346 entries) and the pack made: an estimate all along, of a minute or two
        check("tiny", lowest >= 0 && highest <= 2, "while the library is written: " + lowest + "-" + highest + " minutes");
        ok("tiny update");
    }

    /** The split measured, then the shaders step: the estimate goes on through the change (the launcher's figure for the shaders until their own rate) */
    static void stepChange() {
        Run r = new Run(new Eta(42346, 42346, S));
        r.e.running(true, r.now);
        r.e.step(SPLIT, r.now);
        r.e.progress(SPLIT, 0, 1, "main", r.now);
        r.at(60);
        check("step", r.min() == -1, "an estimate in the split's start: " + r.min());
        double t = translate(r, 60, 0, 186583, 186583, x -> 1000, SPLIT, "");
        double inSplit = r.sec(), want = 42346 * S + lib(42346) + pack();
        check("step", near(inSplit, want, 0.03), "at the split's end " + inSplit + " s, want " + want);
        r.at(t + 1);
        r.e.step(SHADERS, r.now);
        r.at(t + 20);
        double first = r.sec();
        check("step", near(first, want - 19, 0.03), "at the shaders step's start " + first + " s, want about " + want);
        ok("step change");
    }

    /** The shaders step's two stages: when the translation ends the library's minutes are still to come, counted down through "check", then the pack */
    static void library() {
        int L = 300000;   // a library of 6.5 minutes (a bigger game's): the stages' times are well apart
        Run r = new Run(new Eta(42346, L, S));
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        double t = translate(r, 0, 40000, 42346, 42346, x -> 15, SHADERS, "translate");
        double end = r.sec();
        check("library", end >= lib(L) + pack() - 10, "at the translation's end " + end + " s");
        r.e.progress(SHADERS, 42346, 42346, "library", r.now);
        check("library", r.min() >= 5, "the library stage starts at " + r.min() + " minutes");   // (6.5 minutes and the pack)
        r.at(t + 200);
        r.e.progress(SHADERS, 42346, 42346, "check", r.now);
        double c = r.sec(), want = lib(L) - 200 + pack();
        check("library", near(c, want, 0.05), "in check " + c + " s, want " + want);
        // a library a little slower than its figure: "a moment more" (Eta.LATE_S), not nothing (asked every 10 s, as the service does)
        for (double x = t + 210; x <= t + lib(L); x += 10) { r.at(x); r.min(); }
        r.at(t + lib(L) + 5);
        double late = r.sec();   // (the smoothing follows the held figure a little behind)
        check("library", r.min() >= 0 && late <= Eta.LATE_S + pack() + 1, "5 s after the library's time ran out: " + late);
        double p0 = t + lib(L) + 10;
        r.at(p0);
        r.e.step(PACK, r.now);
        for (double x = p0; x <= p0 + pack() / 2; x += 10) { r.at(x); r.min(); }
        check("library", r.sec() >= pack() / 2 - 5 && r.sec() <= pack() / 2 + Eta.LATE_S, "half way through the pack step " + r.sec());
        // one far slower than its figure: nothing
        Run q = new Run(new Eta(42346, L, S));
        q.e.running(true, q.now);
        q.e.step(SHADERS, q.now);
        q.e.progress(SHADERS, 42346, 42346, "library", q.now);
        q.at(Math.max(2 * lib(L), lib(L) + 60) + 1);
        check("library", q.min() == -1, "an estimate after twice the library's time: " + q.min());
        ok("two-stage shaders step");
    }

    /** Rounding, the kept value at a boundary, and the English */
    static void words() {
        int[][] round = {{0, 0}, {59, 0}, {60, 1}, {89, 1}, {90, 2}, {7 * 60 + 20, 7}, {19 * 60 + 20, 19}, {19 * 60 + 40, 20}, {22 * 60, 20}, {23 * 60, 25},
                {44 * 60, 45}, {62 * 60 + 40, 65}, {75 * 60, 75}, {118 * 60, 120}, {124 * 60, 125}};
        for (int[] c : round) check("round", Eta.round(c[0]) == c[1], c[0] + " s -> " + Eta.round(c[0]) + ", want " + c[1]);
        check("keep", Eta.keep(42.6 * 60, 45) == 45, "42.6 min after 45: " + Eta.keep(42.6 * 60, 45));
        check("keep", Eta.keep(42.4 * 60, 40) == 40, "42.4 min after 40: " + Eta.keep(42.4 * 60, 40));
        check("keep", Eta.keep(41.9 * 60, 45) == 40, "41.9 min after 45: " + Eta.keep(41.9 * 60, 45));
        check("keep", Eta.keep(55, 1) == 1 && Eta.keep(50, 1) == 0, "55 s / 50 s after 1 minute: " + Eta.keep(55, 1) + " / " + Eta.keep(50, 1));
        check("keep", Eta.keep(30 * 60, -1) == 30, "the first value");
        // a time read before another caller's later one (the Tail thread against the main thread's ask): no interval counted twice
        Eta e = new Eta(100, 100, S);
        e.running(true, 0);
        e.minutes(10_000);
        e.progress(SHADERS, 0, 100, "translate", 5_000);
        e.minutes(20_000);
        check("clock", e.runMs() == 20_000, "20 s of running counted as " + e.runMs() + " ms");
        check("round", Eta.round(1e12) == 600 && Eta.round(Double.POSITIVE_INFINITY) == 600 && Eta.round(Double.NaN) == 0,
                "1e12 s / infinity / NaN -> " + Eta.round(1e12) + " / " + Eta.round(Double.POSITIVE_INFINITY) + " / " + Eta.round(Double.NaN));
        String[][] en = {{"0", "Less than a minute left"}, {"1", "About 1 minute left"}, {"7", "About 7 minutes left"}, {"45", "About 45 minutes left"},
                {"60", "About 1 hour left"}, {"75", "About 1 hour 15 minutes left"}, {"120", "About 2 hours left"}, {"125", "About 2 hours 5 minutes left"}};
        for (String[] c : en) {
            String got = Eta.text(Integer.parseInt(c[0])).english();
            check("text", got.equals(c[1]), c[0] + " -> [" + got + "], want [" + c[1] + "]");
        }
        // each language renders every form (no missing item or argument)
        for (Locale l : new Locale[] {Locale.forLanguageTag("tr"), Locale.forLanguageTag("pt-BR"), Locale.forLanguageTag("es-419")})
            for (String[] c : en) System.out.println("text " + l.toLanguageTag() + " " + c[0] + ": " + Eta.text(Integer.parseInt(c[0])).render(l));
        // the short form, first in the notification's title (a collapsed notification shows only its title and subText on one line)
        String[][] shortEn = {{"0", "<1 min left"}, {"1", "1 min left"}, {"45", "45 min left"}, {"60", "1 h left"}, {"75", "1 h 15 min left"}, {"125", "2 h 5 min left"},
                {"595", "9 h 55 min left"}};
        for (String[] c : shortEn) {
            String got = Eta.shortText(Integer.parseInt(c[0])).english();
            check("short", got.equals(c[1]), c[0] + " -> [" + got + "], want [" + c[1] + "]");
        }
        for (Locale l : new Locale[] {Locale.forLanguageTag("tr"), Locale.forLanguageTag("pt-BR"), Locale.forLanguageTag("es-419")})
            for (String[] c : shortEn) System.out.println("short " + l.toLanguageTag() + " " + c[0] + ": " + Eta.shortText(Integer.parseInt(c[0])).render(l));
        ok("words");
    }

    /** The split's map as Eta reads it: rows and the non-vertex ones, by the header's "stage" column */
    static void mix() throws IOException {
        File f = File.createTempFile("eta_map", ".tsv");
        try {
            // as vk_android_build.py counts: each key once (k1 twice), a vertex shader by its package file's "vs" prefix, rows of five columns and more
            Files.write(f.toPath(), ("# kind: evidence\npackage\tindex\tstage\tdm_ctl_sha16\tsource\nps_effect.bin\t0\tpixel\tk1\ts\nvs_effect.bin\t0\tvertex\tk2\ts\n"
                    + "cs_shader.bin\t0\tcompute\tk3\ts\nvs_shader.bin\t1\tvertex\tk4\ts\nps_other.bin\t3\tpixel\tk1\ts\nvs_short.bin\t0\tvertex\tk5\n")
                    .getBytes(StandardCharsets.UTF_8));
            int[] m = Eta.shaderMix(f);
            check("mix", m != null && m[0] == 4 && m[1] == 2, "got " + Arrays.toString(m));
            Files.write(f.toPath(), "package\tindex\n".getBytes(StandardCharsets.UTF_8));
            check("mix", Eta.shaderMix(f) == null, "a map without shaders");
        } finally {
            f.delete();
        }
        ok("mix");
    }

    /** Totals of 0, or a count past its total: nothing to go on, no estimate (the fixed figures alone are no estimate of this job) */
    static void zero() {
        Run r = new Run(new Eta(42346, 42346, S));
        r.e.running(true, r.now);
        r.e.step(SPLIT, r.now);
        double t = translate(r, 0, 0, 400, 0, x -> 1, SPLIT, "");
        check("zero", r.min() == -1, "the split's scan of 0: " + r.min());
        r.e.step(SHADERS, r.now);
        t = translate(r, t, 0, 4000, 0, x -> 10, SHADERS, "translate");
        check("zero", r.min() == -1, "a translation of 0: " + r.min());
        Run q = new Run(new Eta(1000, 1000, S));
        q.e.running(true, q.now);
        q.e.step(SHADERS, q.now);
        double u = translate(q, 0, 0, 1000, 1000, x -> 2, SHADERS, "translate");
        translate(q, u, 1000, 1640, 1000, x -> 2, SHADERS, "translate");
        check("zero", q.min() == -1, "a count past its total: " + q.min());
        ok("zero");
    }

    /** A trickle (64 shaders every 170 s): a true figure of many hours is no use to anyone, so none above Eta.MAX_S */
    static void trickle() {
        Run r = new Run(new Eta(42346, 42346, S));
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        translate(r, 0, 0, 64 * 12, 42346, x -> 64 / 170.0, SHADERS, "translate");
        check("trickle", r.min() == -1, "a trickle's estimate: " + r.min() + " minutes");
        ok("trickle");
    }

    /** A full build whose total is not the profile map's count: Eta says so once (PackService logs it) and counts every shader alike */
    static void unweighted() {
        Eta e = new Eta(1200, 1200, S);
        e.mix(new int[] {1000, 600});
        e.running(true, 0);
        e.step(SHADERS, 0);
        boolean first = e.progress(SHADERS, 0, 1200, "translate", 0), second = e.progress(SHADERS, 64, 1200, "translate", 1000);
        check("unweighted", first && !second, "told " + first + ", then " + second);
        Eta f = new Eta(1000, 1000, S);
        f.mix(new int[] {1000, 600});
        f.running(true, 0);
        f.step(SHADERS, 0);
        check("unweighted", !f.progress(SHADERS, 0, 1000, "translate", 0), "a full build of the map's count");
        Eta g = new Eta(300, 1000, S);
        g.mix(new int[] {1000, 600});
        g.running(true, 0);
        g.step(SHADERS, 0);
        check("unweighted", !g.progress(SHADERS, 0, 300, "translate", 0), "an update (fewer shaders than the map)");
        ok("unweighted");
    }

    /**
     * The translation's own rate takes over from the fixed figure (here 5 a second, against Eta.SHADER_S's 11.6): the shown time follows it over the smoothing's
     * minutes, never in one jump between two of the service's 10 s asks
     */
    static void takeover() {
        Run r = new Run(new Eta(20000, 20000, S));
        r.e.running(true, r.now);
        r.e.step(SPLIT, r.now);
        double t = translate(r, 0, 0, 30000, 30000, x -> 100, SPLIT, "");   // a measured split: the fixed figures stand in at the shaders step's start
        check("takeover", r.min() > 0, "no estimate at the split's end");
        r.e.step(SHADERS, r.now);
        r.e.progress(SHADERS, 0, 20000, "translate", r.now);
        double prev = r.sec(), worst = 0;
        long d = 0;
        for (double x = t + 10; x < t + 600; x += 10) {
            while (d < 20000 && t + 10 + (d + 64) / 5.0 <= x) { d += 64; r.at(t + 10 + d / 5.0); r.e.progress(SHADERS, d, 20000, "translate", r.now); }
            r.at(x);
            double s = r.sec();
            check("takeover", !Double.isNaN(s), "no estimate at " + (x - t) + " s");
            worst = Math.max(worst, Math.abs(s - prev) / prev);
            prev = s;
        }
        check("takeover", worst <= 0.2, String.format(Locale.ROOT, "the estimate moved %.0f %% between two asks", 100 * worst));
        double want = (20000 - d) / 5.0 + lib(20000) + pack();
        check("takeover", near(prev, want, 0.1), "after 10 minutes " + prev + " s, want " + want);
        ok("takeover");
    }

    /**
     * A rate carried over to a new process (Eta.carry, Eta.carried: job.json's "eta_carry"): taken only by the same part with the same total and map, and then an
     * estimate at once instead of none for 2 minutes; anything else drops it. Not yet matched, it is kept for the process after
     */
    static void carried() {
        Run r = new Run(new Eta(20000, 20000, S));
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        check("carried", r.e.carry() == null, "a rate before any line");
        double t = translate(r, 0, 0, 640, 20000, x -> 10, SHADERS, "translate");
        check("carried", r.e.carry() == null, "a rate before the part's own (64 s)");
        t = translate(r, t, 640, 3200, 20000, x -> 10, SHADERS, "translate");
        double[] c = r.e.carry();
        check("carried", c != null && c[0] == SHADERS && c[1] == 20000 && near(c[3], 10, 0.02), "the carried rate " + Arrays.toString(c));
        double want = (20000 - 3200) / 10.0 + lib(20000) + pack();
        // a new process: its first line is the same part with the same total
        Run q = new Run(new Eta(20000, 20000, S));
        q.at(t + 600);
        q.e.carried(c);
        q.e.running(true, q.now);
        q.e.step(SHADERS, q.now);
        check("carried", Arrays.equals(q.e.carry(), c), "the offered rate is kept for the next process until a line takes it");
        q.e.progress(SHADERS, 3200, 20000, "translate", q.now);
        check("carried", q.e.carriedOver() && near(q.sec(), want, 0.02), "at the new process's first line " + q.sec() + " s, want " + want);
        check("carried", Arrays.equals(q.e.carry(), c), "the carried rate is kept for a process after this one");
        q.at(t + 600 + 60);
        check("carried", near(q.sec(), want, 0.05), "60 s into the tool's set-up " + q.sec() + " s, want about " + want);
        // the new process's own speed (5 a second) takes over within the window
        // the new process's own speed (half) takes over as it does after a pause in the same process (asked at the same moments: the shown time is smoothed)
        r.e.running(false, r.now);
        r.at(t + 600);
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        r.e.progress(SHADERS, 3200, 20000, "translate", r.now);
        r.sec();
        r.at(t + 600 + 60);
        r.sec();
        for (double x = 0; x <= 600; x += 150) {
            translate(q, t + 600 + 70 + x, 3264 + (long) (5 * x), 3264 + (long) (5 * (x + 150)), 20000, y -> 5, SHADERS, "translate");
            translate(r, t + 600 + 70 + x, 3264 + (long) (5 * x), 3264 + (long) (5 * (x + 150)), 20000, y -> 5, SHADERS, "translate");
            check("carried", near(q.sec(), r.sec(), 0.01), String.format(Locale.ROOT, "%.0f s at half the speed: %.0f s, a resume in the same process %.0f s", x + 150, q.sec(), r.sec()));
        }
        // another total (an update's), another step, another map: dropped, and nothing shown until the part's own rate
        for (Object[] x : new Object[][] {{SHADERS, 584L, "another total"}, {SPLIT, 20000L, "another step"}}) {
            Run o = new Run(new Eta(20000, 20000, S));
            o.e.carried(c);
            o.e.running(true, o.now);
            o.e.step((Integer) x[0], o.now);
            o.e.progress((Integer) x[0], 100, (Long) x[1], (Integer) x[0] == SHADERS ? "translate" : "", o.now);
            check("carried", !o.e.carriedOver() && o.min() == -1 && o.e.carry() == null, x[2] + ": taken, or an estimate at once");
        }
        Run m = new Run(new Eta(20000, 20000, S));
        m.e.carried(c);
        m.e.running(true, m.now);
        m.e.step(SHADERS, m.now);
        m.e.mix(new int[] {20000, 12000});
        m.e.progress(SHADERS, 3200, 20000, "translate", m.now);
        check("carried", !m.e.carriedOver() && m.min() == -1, "another map: taken");
        // a stage line first (the split's "main"): not a counted part, the offer waits for the counted line
        Run n = new Run(new Eta(20000, 20000, S));
        double[] sc = {SPLIT, 186583, -1, 400};
        n.e.carried(sc);
        n.e.running(true, n.now);
        n.e.step(SPLIT, n.now);
        n.e.progress(SPLIT, 0, 1, "main", n.now);
        check("carried", Arrays.equals(n.e.carry(), sc), "the offer is kept through the split's start");
        n.at(39);
        n.e.progress(SPLIT, 171712, 186583, "", n.now);
        double split = (186583 - 171712) / 400.0 + 20000 * S + lib(20000) + pack();
        check("carried", n.e.carriedOver() && near(n.sec(), split, 0.02), "the split resumed: " + n.sec() + " s, want " + split);
        ok("carried");
    }

    /**
     * Each trace's run with its process ended at 25, 50 and 75 % of the translation and a new one started at once (a kill: no pause), the tool's first line
     * at once and its first batch 10 s later: the new process's estimate at its first line against the true time left, and against the estimate the old
     * process gave at that moment. Prints "carried &lt;name&gt; &lt;percent&gt; &lt;new process's s&gt; &lt;old process's s&gt; &lt;true s&gt;"
     */
    static void carriedReplay(File f, String name) throws IOException {
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        List<Ev> ev = fine(lines);
        int[] mx = mixOf(lines);
        double end = end(lines), first = -1, last = -1;
        for (Ev e : ev) if (e.step() == SHADERS && e.stage().equals("translate")) { if (first < 0) first = e.t(); last = e.t(); }
        for (double share : new double[] {0.25, 0.5, 0.75}) {
            double at = first + share * (last - first), setup = 10;
            Run r = new Run(etaOf(lines, mx));
            r.e.running(true, r.now);
            int k = 0, step = -1;
            long done = 0, total = 0;
            for (; k < ev.size() && ev.get(k).t() <= at; k++) {
                Ev e = ev.get(k);
                r.at(e.t());
                if (e.step() != step) { step = e.step(); r.e.step(step, r.now); if (step == SHADERS) r.e.mix(mx); }
                r.e.progress(e.step(), e.done(), e.total(), e.stage(), r.now);
                if (e.step() == SHADERS) { done = e.done(); total = e.total(); }
            }
            r.at(at);
            double old = r.sec();
            double[] c = r.e.carry();
            // the new process: PackService.etaFor, then work(): running, the step (its map), the tool's first line
            Run q = new Run(etaOf(lines, mx));
            q.at(at);
            q.e.carried(c);
            q.e.running(true, q.now);
            q.e.step(SHADERS, q.now);
            q.e.mix(mx);
            q.e.progress(SHADERS, done, total, "translate", q.now);
            double got = q.sec(), truth = end - at + setup;
            System.out.println(String.format(Locale.ROOT, "carried %s %.0f %.0f %.0f %.0f", name, share * 100, got, old, truth));
            check("carried " + name, c != null && near(got, truth, 0.25), String.format(Locale.ROOT, "at %.0f %%: %.0f s, true %.0f s", share * 100, got, truth));
            check("carried " + name, near(got, old, 0.05), String.format(Locale.ROOT, "at %.0f %%: %.0f s, the old process said %.0f s", share * 100, got, old));
            // the next 15 minutes (where more than 5 are left): the new process's estimate against the old process's had it gone on (no jump when the new
            // process's own rate takes over)
            double worst = 0, worstOld = 0;
            int ko = k, so = step;
            for (double t = at + setup; t < Math.min(end, at + 900); t += 10) {
                for (; k < ev.size() && ev.get(k).t() + setup <= t; k++) {
                    Ev e = ev.get(k);
                    q.at(e.t() + setup);
                    if (e.step() != step) { step = e.step(); q.e.step(step, q.now); }
                    q.e.progress(e.step(), e.done(), e.total(), e.stage(), q.now);
                }
                for (; ko < ev.size() && ev.get(ko).t() <= t - setup; ko++) {
                    Ev e = ev.get(ko);
                    r.at(e.t());
                    if (e.step() != so) { so = e.step(); r.e.step(so, r.now); }
                    r.e.progress(e.step(), e.done(), e.total(), e.stage(), r.now);
                }
                q.at(t);
                r.at(t - setup);
                double s = q.sec(), o = r.sec(), left = end + setup - t;
                if (Double.isNaN(s)) { check("carried " + name, false, String.format(Locale.ROOT, "at %.0f %%: no estimate %.0f s after the restart", share * 100, t - at)); break; }
                if (left <= 300) continue;
                worst = Math.max(worst, Math.abs(s - left) / left);
                if (!Double.isNaN(o)) worstOld = Math.max(worstOld, Math.abs(o - (left - setup)) / (left - setup));
            }
            System.out.println(String.format(Locale.ROOT, "carried %s %.0f worst %.1f %% in the 15 minutes after, %.1f %% without the restart", name, share * 100,
                    100 * worst, 100 * worstOld));
            check("carried " + name, worst <= worstOld + 0.05, String.format(Locale.ROOT, "at %.0f %%: worst %.1f %%, %.1f %% without the restart", share * 100,
                    100 * worst, 100 * worstOld));
        }
        ok("carried " + name);
    }

    /** A trace's events: {seconds, step, done, total} and the stage */
    private record Ev(double t, int step, String stage, long done, long total) {}

    /** The trace as the tool prints it: the translation's 30 s samples filled in with a line every 64 shaders (vk_android_build.py BATCH), at the sample's rate */
    static List<Ev> fine(List<String> lines) {
        List<String> steps = Arrays.asList("identify", "rewrite", "split", "shaders", "pack");
        List<Ev> ev = new ArrayList<>(), out = new ArrayList<>();
        for (String l : lines) {
            if (l.startsWith("#") || l.isEmpty() || l.startsWith("end") || l.startsWith("mix") || l.startsWith("build")) continue;
            String[] c = l.trim().split("\\s+");
            ev.add(new Ev(Double.parseDouble(c[0]), steps.indexOf(c[1]), c[2].equals("-") ? "" : c[2], Long.parseLong(c[3]), Long.parseLong(c[4])));
        }
        for (int k = 0; k < ev.size(); k++) {
            Ev b = ev.get(k);
            Ev a = k > 0 ? ev.get(k - 1) : null;
            if (a != null && b.stage().equals("translate") && a.stage().equals("translate") && a.total() == b.total() && b.done() > a.done()) {
                for (long d = (a.done() / 64 + 1) * 64; d < b.done(); d += 64)
                    out.add(new Ev(a.t() + (b.t() - a.t()) * (d - a.done()) / (b.done() - a.done()), b.step(), b.stage(), d, b.total()));
            }
            out.add(b);
        }
        return out;
    }

    static double end(List<String> lines) {
        for (String l : lines) if (l.startsWith("end")) return Double.parseDouble(l.trim().split("\\s+")[1]);
        throw new IllegalStateException("no end line");
    }

    /** An Eta for the trace's run: its "build" line gives an older build's figures (seconds a shader, a library entry, the pack's ms), else the current ones */
    static Eta etaOf(List<String> lines, int[] mx) {
        for (String l : lines)
            if (l.startsWith("build")) {
                String[] c = l.trim().split("\\s+");
                return new Eta(mx[0], mx[0], Double.parseDouble(c[1]), Double.parseDouble(c[2]), Long.parseLong(c[3]));
            }
        return new Eta(mx[0], mx[0], S);
    }

    /** The trace's "mix" line: the map's shaders (also the library's entries) and its non-vertex shaders */
    static int[] mixOf(List<String> lines) {
        for (String l : lines) if (l.startsWith("mix")) { String[] c = l.trim().split("\\s+"); return new int[] {Integer.parseInt(c[1]), Integer.parseInt(c[2])}; }
        throw new IllegalStateException("no mix line");
    }

    /**
     * The trace at the tool's own cadence with a gap at 40 minutes, in the translation's first phase: no line for gap seconds while the job runs (a stall), or,
     * with pause, the job paused for gap seconds and then the tool's restart: its first line at once, its first batch after setup seconds. Returns the worst
     * error of the estimate against the true time left in the 15 minutes after the gap (a gap of 0: the same minutes of the run without one), and checks that the shown minutes never
     * rise by more than one rounding step from one ask to the next
     */
    static double gap(List<String> lines, String name, double gap, boolean pause, double setup) {
        List<Ev> ev = fine(lines);
        double at = 2400, shift = gap + (pause ? setup : 0), end = end(lines) + shift;
        int[] mx = mixOf(lines);
        Run r = new Run(etaOf(lines, mx));
        r.e.running(true, r.now);
        int k = 0, step = -1, last = -1;
        long lastDone = 0;
        boolean paused = false, resumed = false;
        double worst = 0;
        String rise = null;
        for (double t = 0; t < end; t += 10) {
            while (k < ev.size()) {
                Ev e = ev.get(k);
                double et = e.t() < at ? e.t() : e.t() + shift;
                if (pause && !paused && t >= at) {   // the user pauses: the worker stops at once
                    paused = true;
                    r.at(at);
                    r.e.running(false, r.now);
                }
                if (pause && paused && !resumed && t >= at + gap) {   // resumed: the worker starts the step again, the tool prints its first line at once
                    resumed = true;
                    r.at(at + gap);
                    r.e.running(true, r.now);
                    r.e.step(SHADERS, r.now);
                    r.e.progress(SHADERS, lastDone, mx[0], "translate", r.now);
                }
                if (et > t) break;
                k++;
                r.at(et);
                if (e.step() != step) {
                    step = e.step();
                    r.e.step(step, r.now);
                    if (step == SHADERS) r.e.mix(mx);
                }
                r.e.progress(e.step(), e.done(), e.total(), e.stage(), r.now);
                if (e.step() == SHADERS) lastDone = e.done();
            }
            r.at(t);
            int m = r.min();
            double s = r.e.lastSeconds(), truth = end - t;
            // (the job truly lost the stall's time: the shown minutes may rise by that much after it, else by one rounding step at most)
            if (m >= 0 && last >= 0 && m > last + Math.max(last < 20 ? 1 : 5, (int) Math.ceil(pause ? 0 : gap / 60)) && rise == null) rise = last + " -> " + m + " at " + t + " s";
            if (m >= 0) last = m;
            if (m >= 0 && t >= at + shift && t < at + shift + 900) worst = Math.max(worst, Math.abs(s - truth) / truth);
        }
        String what = String.format(Locale.ROOT, "%s %s %.0f s%s", name, pause ? "pause" : "stall", gap, pause ? String.format(Locale.ROOT, " + %.0f s set-up", setup) : "");
        check("gap", rise == null, what + ": the shown minutes rose " + rise);
        System.out.println(String.format(Locale.ROOT, "gap %s: worst error after it %.1f %%", what, 100 * worst));
        return worst;
    }

    /**
     * Gaps in the progress at the tool's real cadence (a line every 64 shaders): stalls of 90 s, 150 s, 4 and 10 minutes, and a pause of an hour with the
     * tool's 70 s set-up after it. The estimate after the gap stays as near as it is without one (no rate measured across idle time)
     */
    static void hostile(File f, String name) throws IOException {
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        double none = gap(lines, name, 0, false, 0);
        for (double g : new double[] {90, 150, 240, 600}) {
            double w = gap(lines, name, g, false, 0);
            check("gap", w <= none + 0.05, String.format(Locale.ROOT, "%s stall %.0f s: worst error %.1f %%, %.1f %% without one", name, g, 100 * w, 100 * none));
        }
        double w = gap(lines, name, 3600, true, 70);
        check("gap", w <= none + 0.05, String.format(Locale.ROOT, "%s pause: worst error %.1f %%, %.1f %% without one", name, 100 * w, 100 * none));
        ok("gaps " + name);
    }

    /**
     * A trace of a real Prepare replayed: each line fed at its time, the estimate asked every 10 s (as the service's tick does). Prints "replay &lt;name&gt;
     * &lt;percent of the run&gt; &lt;estimate s or -&gt; &lt;true s&gt; &lt;shown minutes&gt;" at 10, 25, 50, 75 and 90 % and "replay &lt;name&gt; summary …"
     */
    static void replay(File f, String name) throws IOException {
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        double end = -1;
        List<String[]> ev = new ArrayList<>();
        for (String l : lines) {
            if (l.startsWith("#") || l.isEmpty() || l.startsWith("mix") || l.startsWith("build")) continue;
            String[] c = l.trim().split("\\s+");
            if (c[0].equals("end")) end = Double.parseDouble(c[1]);
            else ev.add(c);
        }
        int[] mx = mixOf(lines);
        List<String> steps = Arrays.asList("identify", "rewrite", "split", "shaders", "pack");
        Run r = new Run(etaOf(lines, mx));
        r.e.running(true, r.now);
        int k = 0, step = -1, shown = 0, asked = 0, ups = 0, last = -1;
        double worstAt = -1;
        double sumErr = 0, maxErr = 0;
        int nErr = 0;
        double[] pts = {0.10, 0.25, 0.50, 0.75, 0.90};
        double[][] at = new double[pts.length][];
        for (double t = 0; t < end; t += 10) {
            while (k < ev.size() && Double.parseDouble(ev.get(k)[0]) <= t) {
                String[] c = ev.get(k++);
                int i = steps.indexOf(c[1]);
                r.at(Double.parseDouble(c[0]));
                if (i != step) {
                    step = i;
                    r.e.step(i, r.now);
                    if (i == SHADERS) r.e.mix(mx);   // the profile's map: 15,852 vertex shaders
                }
                r.e.progress(i, Long.parseLong(c[3]), Long.parseLong(c[4]), c[2].equals("-") ? "" : c[2], r.now);
            }
            r.at(t);
            int m = r.min();
            double s = r.e.lastSeconds(), truth = end - t;
            asked++;
            if (m >= 0 && last >= 0 && m > last) ups++;
            last = m;
            if (m >= 0) {
                shown++;
                if (truth > 300) {
                    double e = Math.abs(s - truth) / truth;
                    sumErr += e;
                    nErr++;
                    if (e > maxErr) { maxErr = e; worstAt = t; }
                }
            }
            for (int p = 0; p < pts.length; p++) if (at[p] == null && t >= pts[p] * end - 5) at[p] = new double[] {t, m >= 0 ? s : Double.NaN, truth, m};
        }
        for (int p = 0; p < pts.length; p++) {
            double[] x = at[p];
            System.out.println(String.format(Locale.ROOT, "replay %s %d %s %.0f %d", name, Math.round(pts[p] * 100), Double.isNaN(x[1]) ? "-" : String.format(Locale.ROOT, "%.0f", x[1]),
                    x[2], (int) x[3]));
        }
        System.out.println(String.format(Locale.ROOT, "replay %s summary shown %d of %d asks, mean error %.1f %%, max %.1f %% (at %.0f s; where more than 5 min were left), "
                + "the shown minutes went up %d times", name, shown, asked, 100 * sumErr / Math.max(1, nErr), 100 * maxErr, worstAt, ups));
    }
}
