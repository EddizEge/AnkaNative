package dev.nxnative.android;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * JobErrors on the JVM, driven by dm/tools/tests/test_android_joberrors.py with -Dnx.tr.res=&lt;the app's res&gt;. One run:
 * <pre>
 * check    → its own checks (parse, message, the tables), "ok" and exit 0, or the first failure and exit 1
 * codes    → each code and its number of arguments, "code\tn" a line (compared with dm/tools/pack_errors.py CODES)
 * render   → for each line of stdin (the packer's lines): "-" when parse returns null, "?" when message does, else the message's English
 * </pre>
 */
public final class JobErrorsJvmTest {
    private static void check(boolean ok, String what) {
        if (!ok) { System.out.println("FAIL " + what); System.exit(1); }
    }

    private static void same(String got, String want, String what) { check(want.equals(got), what + ": got [" + got + "], want [" + want + "]"); }

    public static void main(String[] a) throws Exception {
        switch (a.length > 0 ? a[0] : "") {
            case "check": selfCheck(); System.out.println("ok"); break;
            case "codes": for (int i = 0; i < JobErrors.CODES.length; i++) System.out.println(JobErrors.CODES[i] + "\t" + JobErrors.ARGS[i]); break;
            case "render": render(); break;
            default: System.out.println("usage: JobErrorsJvmTest check | codes | render"); System.exit(2);
        }
    }

    private static void render() throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (String line; (line = in.readLine()) != null; ) {
            String[] p = JobErrors.parse(line);
            Msg m = p == null ? null : JobErrors.message(p[0], java.util.Arrays.copyOfRange(p, 1, p.length));
            out.append(p == null ? "-" : m == null ? "?" : m.english()).append('\n');
        }
        System.out.print(out);
        System.out.flush();
    }

    private static void selfCheck() {
        check(JobErrors.CODES.length == JobErrors.KEYS.length && JobErrors.CODES.length == JobErrors.ARGS.length, "CODES, KEYS and ARGS have the same length");
        for (int i = 0; i < JobErrors.CODES.length; i++) {
            same(JobErrors.KEYS[i], "errors_" + JobErrors.CODES[i], "key of " + JobErrors.CODES[i]);
            String[] args = new String[JobErrors.ARGS[i]];
            for (int k = 0; k < args.length; k++) args[k] = "<arg" + (k + 1) + ">";
            Msg m = JobErrors.message(JobErrors.CODES[i], args);
            check(m != null, JobErrors.CODES[i] + " with its " + args.length + " arguments has a text");
            String en = m.english();
            for (String x : args) check(en.contains(x), JobErrors.CODES[i] + ": its text shows " + x + ": " + en);
            check(!en.contains("%"), JobErrors.CODES[i] + ": no placeholder left: " + en);
            same(m.text(), en, JobErrors.CODES[i] + ": the user's language is English on the JVM");
            check(JobErrors.message(JobErrors.CODES[i], new String[args.length + 1]) == null, JobErrors.CODES[i] + " with one argument too many: null");
            if (args.length > 0) check(JobErrors.message(JobErrors.CODES[i], new String[args.length - 1]) == null, JobErrors.CODES[i] + " with one argument too few: null");
        }
        // parse
        String[] p = JobErrors.parse("error: wrong_title\t/d/updates/458752\t0100000000001000\t0400486024DEC000\n");
        check(p != null && p.length == 4, "an error: line with three arguments and a newline");
        same(p[0], "wrong_title", "the code");
        same(p[3], "0400486024DEC000", "the last argument, newline dropped");
        p = JobErrors.parse("error: translator_crashed\r\n");
        check(p != null && p.length == 1 && p[0].equals("translator_crashed"), "a code without arguments, CRLF dropped");
        p = JobErrors.parse("error: no_space\t-");
        check(p != null && p.length == 2 && p[1].equals("-"), "an empty argument as the packer writes it");
        check(JobErrors.parse(null) == null, "null");
        check(JobErrors.parse("") == null, "an empty line");
        check(JobErrors.parse("[RED] no updates/5 (have: none)") == null, "a [RED] line");
        check(JobErrors.parse("progress 1/3 main") == null, "a progress line");
        check(JobErrors.parse("pack_android.py: error: the following arguments are required: game") == null, "argparse's error line");
        check(JobErrors.parse("error: line 3: bad SPIR-V") == null, "an error: line whose code is not a code");
        check(JobErrors.parse("error: ") == null, "no code");
        check(JobErrors.parse("error: No_space\tx") == null, "a code with a capital");
        check(JobErrors.parse("Error: no_space\tx") == null, "another prefix");
        // message
        check(JobErrors.message("no_such_code", new String[0]) == null, "an unknown code: null");
        check(JobErrors.message(null, null) == null, "no code: null");
        check(JobErrors.message("translator_crashed", null) != null, "null arguments are none");
        // the English, word for word (the texts the integrator documents)
        same(JobErrors.message("version_missing", new String[] {"458752"}).english(), "The dump has no update 458752. Choose a version the dump holds, or dump that update.",
                "version_missing");
        same(JobErrors.message("wrong_title", new String[] {"/d/updates/458752", "0100000000001000", "0400486024DEC000"}).english(),
                "/d/updates/458752 is not part of this game: its program ID is 0100000000001000, the base game is 0400486024DEC000.", "wrong_title");
        same(JobErrors.message("shaders_other_build", new String[] {"12", "3456"}).english(),
                "The dump lacks 12 of the 3456 shaders this app knows for the game: it is of another game version, or incomplete. Dump the game again.", "shaders_other_build");
        same(JobErrors.message("translator_crashed", new String[0]).english(), "The shader translator crashed. Try again; if it crashes again, report it with the log.",
                "translator_crashed");
        // a Msg: toString is English (job.json, logs)
        Msg m = JobErrors.message("no_profile", new String[] {"0100000000001000"});
        same(String.valueOf(m), "This app has no profile for the game 0100000000001000, so it cannot prepare it.", "toString");
    }
}
