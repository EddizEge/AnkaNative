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
    private static final double S = 0.080;   // ShaderLibs.SHADER_S_HI, as PackService passes it
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
        for (String w : new String[] {"6w", "8w"}) replay(new File(a[0], "prepare-eta-" + w + ".trace.txt"), w);
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
        // resumed: the tool's first line counts a batch it finished while stopping, then 10 a second again
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        t = translate(r, t + 1800 + 15, 3264, 3264 + 128, 20000, x -> 10, SHADERS, "translate");
        // (a little more than the true time: the restart's 15 s count, the batch its first line brought does not)
        double after = r.sec(), want = (20000 - 3392) / 10.0 + lib(20000) + pack();
        // (with the 30 minutes counted the rate would be a sixth of the true one and the estimate hours)
        check("resume", near(after, want, 0.05) && after < 1.1 * before, "got " + after + " s, want " + want + " (before the pause " + before + ")");
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
        r.at(t + 120);
        check("stall", r.min() > 0, "no estimate 2 min after the last line");
        r.at(t + 200);
        check("stall", r.min() == -1, "an estimate 200 s after the last line: " + r.min());
        translate(r, t + 200, 3000, 3128, 20000, x -> 10, SHADERS, "translate");
        check("stall", r.min() > 0, "no estimate once progress goes on");
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
     * a fifth is done, and never "less than a minute" while the library is still being written
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
        int lowest = Integer.MAX_VALUE;
        for (double x = t; x < t + lib(42346); x += 5) { r.at(x); lowest = Math.min(lowest, r.min()); }
        check("tiny", lowest >= 2, "while the library is written: " + lowest + " minutes");
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
        Run r = new Run(new Eta(42346, 42346, S));
        r.e.running(true, r.now);
        r.e.step(SHADERS, r.now);
        double t = translate(r, 0, 40000, 42346, 42346, x -> 15, SHADERS, "translate");
        double end = r.sec();
        check("library", end >= lib(42346) + pack() - 10, "at the translation's end " + end + " s");
        r.e.progress(SHADERS, 42346, 42346, "library", r.now);
        check("library", r.min() >= 5, "the library stage starts at " + r.min() + " minutes");
        r.at(t + 200);
        r.e.progress(SHADERS, 42346, 42346, "check", r.now);
        double c = r.sec(), want = lib(42346) - 200 + pack();
        check("library", near(c, want, 0.05), "in check " + c + " s, want " + want);
        r.at(t + lib(42346) + 5);
        check("library", r.min() == -1, "an estimate after the library's time ran out: " + r.min());
        r.e.step(PACK, r.now);
        r.at(t + lib(42346) + 30);
        check("library", near(r.sec(), pack() - 25, 0.1), "in the pack step " + r.sec());
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
        String[][] en = {{"0", "Less than a minute left"}, {"1", "About 1 minute left"}, {"7", "About 7 minutes left"}, {"45", "About 45 minutes left"},
                {"60", "About 1 hour left"}, {"75", "About 1 hour 15 minutes left"}, {"120", "About 2 hours left"}, {"125", "About 2 hours 5 minutes left"}};
        for (String[] c : en) {
            String got = Eta.text(Integer.parseInt(c[0])).english();
            check("text", got.equals(c[1]), c[0] + " -> [" + got + "], want [" + c[1] + "]");
        }
        // each language renders every form (no missing item or argument)
        for (Locale l : new Locale[] {Locale.forLanguageTag("tr"), Locale.forLanguageTag("pt-BR"), Locale.forLanguageTag("es-419")})
            for (String[] c : en) System.out.println("text " + l.toLanguageTag() + " " + c[0] + ": " + Eta.text(Integer.parseInt(c[0])).render(l));
        ok("words");
    }

    /** The split's map as Eta reads it: rows and the non-vertex ones, by the header's "stage" column */
    static void mix() throws IOException {
        File f = File.createTempFile("eta_map", ".tsv");
        try {
            Files.write(f.toPath(), ("# kind: evidence\npackage\tindex\tstage\tdm_ctl_sha16\tsource\nps_effect.bin\t0\tpixel\tk1\ts\nvs_effect.bin\t0\tvertex\tk2\ts\n"
                    + "cs_shader.bin\t0\tcompute\tk3\ts\nvs_shader.bin\t1\tvertex\tk4\ts\n").getBytes(StandardCharsets.UTF_8));
            int[] m = Eta.shaderMix(f);
            check("mix", m != null && m[0] == 4 && m[1] == 2, "got " + Arrays.toString(m));
            Files.write(f.toPath(), "package\tindex\n".getBytes(StandardCharsets.UTF_8));
            check("mix", Eta.shaderMix(f) == null, "a map without a stage column");
        } finally {
            f.delete();
        }
        ok("mix");
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
            if (l.startsWith("#") || l.isEmpty()) continue;
            String[] c = l.trim().split("\\s+");
            if (c[0].equals("end")) end = Double.parseDouble(c[1]);
            else ev.add(c);
        }
        List<String> steps = Arrays.asList("identify", "rewrite", "split", "shaders", "pack");
        Run r = new Run(new Eta(42346, 42346, S));
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
                    if (i == SHADERS) r.e.mix(new int[] {42346, 26494});   // the split's map: 15,852 of the 42,346 are vertex shaders
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
