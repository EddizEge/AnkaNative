package dev.nxnative.android;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Prepare's own failure codes (PrepareErrors, docs/i18n.md "Errors of Prepare") on the JVM: each code's English is byte for byte the error PackService and
 * DumpCheck wrote before they stored codes (the same Msg built by hand here), and so is the status line Job builds from it; job.json's strings come back as the
 * same text; a code, an argument count or an argument of another version gives null (the English error stays); the codes are never the packer's (JobErrors);
 * and a code renders in another language (a scratch Turkish text). Run by dm/tools/tests/test_android_prepare_errors.py with -Dnx.tr.res=&lt;the app's res&gt; and a
 * stub BuildConfig (Profile). Prints "ok &lt;n&gt;" and exits 0, or the first failure and exits 1.
 */
public final class PrepareErrorsJvmTest {
    private static int n;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    static void same(String got, String want, String what) { check(want.equals(got), what + ": got [" + got + "], want [" + want + "]"); }

    /** Job.statusMsg's failed line before codes: the English error, its trailing full stops and spaces cut, inside prepare_status_failed */
    static String statusBefore(Msg step, String error) {
        return new Msg("prepare_status_failed", step, error.replaceAll("[.\\s]+$", ""), new Msg("prepare_button_retry")).english();
    }

    /** Job.statusMsg's failed line with a code of PrepareErrors */
    static String statusNow(Msg step, String[] coded) {
        Msg own = PrepareErrors.message(coded[0], Arrays.copyOfRange(coded, 1, coded.length));
        return (PrepareErrors.sentence(coded[0]) ? new Msg("prepare_status_failed_sentence", step, own, new Msg("prepare_button_retry"))
                : new Msg("prepare_status_failed", step, own, new Msg("prepare_button_retry"))).english();
    }

    public static void main(String[] a) throws Exception {
        try {
            run();
        } catch (AssertionError e) {
            System.out.println("FAIL: " + e.getMessage());
            System.exit(1);
        }
        System.out.println("ok " + n);
    }

    static void run() throws Exception {
        check(PrepareErrors.CODES.length == PrepareErrors.ARGS.length && new HashSet<>(Arrays.asList(PrepareErrors.CODES)).size() == PrepareErrors.CODES.length, "CODES and ARGS");
        Set<String> packer = new HashSet<>(Arrays.asList(JobErrors.CODES));
        for (String c : PrepareErrors.CODES) check(!packer.contains(c), c + " is not a packer code (Job asks PrepareErrors first)");

        File log = new File("/storage/emulated/0/Switch/0400486024DEC000-work/ondevice/logs/rewrite.log"), probe = new File("/storage/emulated/0/Switch");
        Msg rewrite = new Msg("prepare_step_rewrite_in_sentence"), identify = new Msg("prepare_step_identify_in_sentence"), shaders = new Msg("prepare_step_shaders_in_sentence");
        Msg retry = new Msg("prepare_button_retry");
        // {the code with its arguments as PackService / DumpCheck pass them, the Msg they built before, the step it fails in}
        Object[][] cases = {
            {PrepareErrors.coded("step_killed", "rewrite", 2, "2.7"), new Msg("prepare_error_step_killed", rewrite, 2, "2.7", retry), rewrite},
            {PrepareErrors.coded("dump_changed", "0400486024DEC000", String.valueOf(65536L)), new Msg("prepare_error_dump_changed", "0400486024DEC000", "65536"), identify},
            {PrepareErrors.coded("tool_exit", "rewrite", 1, log), new Msg("prepare_error_tool_exit", "rewrite", 1, log), rewrite},
            {PrepareErrors.coded("tool_exit_red", "pack", 1, "no updates/65536 (have: none).", log), new Msg("prepare_error_tool_exit_red", "pack", 1, "no updates/65536 (have: none).", log),
                    new Msg("prepare_step_pack_in_sentence")},
            {PrepareErrors.coded("compare_failed", "No such file"), new Msg("prepare_error_compare", "No such file"), shaders},
            {PrepareErrors.coded("compare_failed", (Object) null), new Msg("prepare_error_compare", (Object) null), shaders},
            {PrepareErrors.coded("shader_map", "The Legend of Zelda", 65536L, "shader_map.tsv"),
                    new Msg("prepare_error_shader_map", "The Legend of Zelda", Profile.versionName(65536), "shader_map.tsv", retry), shaders},
            {PrepareErrors.coded("shader_map", "Zelda", 0L, "shader_map.tsv"), new Msg("prepare_error_shader_map", "Zelda", Profile.versionName(0), "shader_map.tsv", retry), shaders},
            {PrepareErrors.coded("dump_free_unknown", probe), new Msg("prepare_dump_free_unknown", probe), identify},
            {PrepareErrors.coded("dump_space", (2048L << 20) / 1073741824.0, probe, 1234567890L / 1073741824.0),
                    new Msg("prepare_dump_space", (2048L << 20) / 1073741824.0, probe, 1234567890L / 1073741824.0), identify},
            {PrepareErrors.coded("dump_not_writable", new File(probe, "0400486024DEC000-work/ondevice")), new Msg("prepare_dump_not_writable", new File(probe, "0400486024DEC000-work/ondevice")), identify},
        };
        Set<String> seen = new HashSet<>();
        for (Object[] c : cases) {
            String[] coded = (String[]) c[0];
            String before = ((Msg) c[1]).english();
            seen.add(coded[0]);
            Msg m = PrepareErrors.message(coded);
            check(m != null, coded[0] + ": a text");
            same(m.english(), before, coded[0] + ": job.json's error is the English it was");
            same(statusNow((Msg) c[2], coded), statusBefore((Msg) c[2], before), coded[0] + ": the status line is the English it was");
            // job.json keeps strings: the same text from the stored array (Job.errorArgs)
            same(PrepareErrors.message(coded[0], Arrays.copyOfRange(coded, 1, coded.length)).english(), before, coded[0] + ": from job.json's strings");
        }
        check(seen.equals(new HashSet<>(Arrays.asList(PrepareErrors.CODES))), "every code has a case: " + seen);

        // Another version's job.json: an unknown code, another number of arguments, an argument that does not parse, a step this build lacks
        check(PrepareErrors.message("no_such_code", new String[0]) == null, "unknown code");
        check(PrepareErrors.message("dump_changed", new String[] {"x"}) == null, "too few arguments");
        check(PrepareErrors.message("step_killed", new String[] {"rewrite", "two", "2.7"}) == null, "a count that is no number");
        check(PrepareErrors.message("step_killed", new String[] {"compile", "2", "2.7"}) == null, "a step this build does not have");
        check(PrepareErrors.message("dump_space", new String[] {"2.0", "/x", "free"}) == null, "a size that is no number");
        check(PrepareErrors.message((String[]) null) == null && PrepareErrors.message(new String[0]) == null, "nothing stored");
        try {
            PrepareErrors.coded("dump_changed", "only one");
            check(false, "coded refuses another number of arguments");
        } catch (IllegalArgumentException e) {
            n++;
        }
        for (String s : new String[] {"identify", "rewrite", "split", "shaders", "pack"}) check(PrepareErrors.stepInSentence(s) != null, "step " + s);

        // In another language: the code's text and the status line in Turkish (scratch texts), job.json's error still English
        File scratch = Files.createTempDirectory("prepare-errors").toFile();
        File en = new File(scratch, "values"), trd = new File(scratch, "values-tr");
        check(en.mkdirs() && trd.mkdirs(), "scratch res");
        for (String f : new String[] {"strings_prepare.xml", "strings_settings.xml"})
            Files.copy(new File(System.getProperty("nx.tr.res"), "values/" + f).toPath(), new File(en, f).toPath());
        Files.write(new File(trd, "strings_prepare.xml").toPath(), ("<resources><string name=\"prepare_error_dump_changed\">döküm değişti: artık %1$s %2$s</string>"
                + "<string name=\"prepare_status_failed\">%1$s sırasında durdu: %2$s. Devam etmek için %3$s düğmesine dokunun.</string>"
                + "<string name=\"prepare_step_identify_in_sentence\">döküm denetimi</string><string name=\"prepare_button_retry\">Yeniden dene</string>"
                + "<string name=\"prepare_dump_space\">Hazırlık için dökümün yanında %1$.1f GB boş alan gerekir; %2$s içinde %3$.2f GB boş.</string></resources>")
                .getBytes(StandardCharsets.UTF_8));
        Tr.Backend before = Tr.backend();
        Tr.install(new Tr.Xml(scratch, Locale.forLanguageTag("tr")));
        try {
            String[] changed = PrepareErrors.coded("dump_changed", "0400486024DEC000", "65536");
            Msg m = PrepareErrors.message(changed);
            same(m.text(), "döküm değişti: artık 0400486024DEC000 65536", "Turkish text");
            same(m.english(), "the dump changed: it is now 0400486024DEC000 65536", "English error");
            same(new Msg("prepare_status_failed", identify, m, retry).text(), "döküm denetimi sırasında durdu: döküm değişti: artık 0400486024DEC000 65536. Devam etmek için Yeniden dene düğmesine dokunun.",
                    "Turkish status line");
            same(PrepareErrors.message(PrepareErrors.coded("dump_space", 2.0, probe, 1.5)).text(), "Hazırlık için dökümün yanında 2,0 GB boş alan gerekir; " + probe + " içinde 1,50 GB boş.",
                    "Turkish numbers");
        } finally {
            Tr.install(before);
        }
    }
}
