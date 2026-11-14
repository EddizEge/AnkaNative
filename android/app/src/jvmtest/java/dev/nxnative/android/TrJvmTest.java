package dev.nxnative.android;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.MissingResourceException;

/**
 * Tr's JVM backend, Msg and LocalizedIOException (docs/i18n.md) on the JVM. Run by dm/tools/tests/test_android_i18n.py with -Dnx.tr.res=&lt;the app's res&gt; and
 * {@code <scratch folder>}: the app's English and Turkish texts of the worked examples, the fallback to English per text, plurals, a Msg inside a Msg, an exception
 * that is English in logs and in the user's language on the screen, and aapt2's escapes on a file of its own. Prints "ok" and exits 0, or the first failure.
 */
public final class TrJvmTest {
    private static void check(boolean ok, String what) {
        if (!ok) { System.out.println("FAIL " + what); System.exit(1); }
    }

    private static void same(String got, String want, String what) { check(want.equals(got), what + ": got [" + got + "], want [" + want + "]"); }

    public static void main(String[] a) throws Exception {
        File res = new File(System.getProperty(Tr.RES_PROPERTY));
        // The default backend: English (values/), as the JVM tests of the launcher's classes see it
        same(Tr.s("home_access_title"), "Allow file access", "plain string");
        same(Tr.s("prepare_cannot_use", "/x/pack", "it is gone"), "Cannot use /x/pack: it is gone", "positional arguments");
        same(Tr.p("readiness_summary_limited", 1, 1), "1 game runs with less here.", "plural one");
        same(Tr.p("readiness_summary_limited", 3, 3), "3 games run with less here.", "plural other");
        same(Tr.s("settings_profile_update", 458752L), "update 458752", "a long, no grouping");
        same(Tr.s("common_or", Tr.s("common_or", "a", "b"), "c"), "a or b or c", "nested");
        try { Tr.s("no_such_key"); check(false, "a missing key throws"); } catch (MissingResourceException expected) { /* the error the app would see */ }

        // The user's language: Turkish, English for what it lacks; english() stays English
        Tr.Xml tr = new Tr.Xml(res, Locale.forLanguageTag("tr"));
        Tr.install(tr);
        same(Tr.s("home_access_title"), "Dosya erişimine izin ver", "Turkish");
        same(Tr.english("home_access_title"), "Allow file access", "english() under Turkish");
        same(Tr.s("settings_language_tr"), "Türkçe", "untranslatable name from values/");
        same(Tr.p("readiness_summary_limited", 2, 2), "2 oyun burada kısıtlı çalışıyor.", "Turkish plural");
        same(Tr.englishP("readiness_summary_limited", 2, 2), "2 games run with less here.", "englishP under Turkish");
        same(new Tr.Xml(res, Locale.forLanguageTag("pt-BR")).string(null, "packs_no_pack_tsv", "/p"), "/p não tem pack.tsv", "pt-BR (values-pt-rBR)");
        same(new Tr.Xml(res, Locale.forLanguageTag("es-419")).string(null, "packs_no_pack_tsv", "/p"), "/p no tiene pack.tsv", "es-419 (values-b+es+419)");
        same(new Tr.Xml(res, Locale.forLanguageTag("es-MX")).string(null, "settings_language", new Object[0]), "Language",
                "es-MX has no folder of its own on the JVM (Android resolves it through es-419)");

        // A dual-use message: English in logs (getMessage, toString), the user's language on the screen (getLocalizedMessage)
        LocalizedIOException e = new LocalizedIOException("packs_no_pack_tsv", new File("/sdcard/pack"));
        same(e.getMessage(), "/sdcard/pack has no pack.tsv", "getMessage is English");
        same(e.getLocalizedMessage(), "/sdcard/pack içinde pack.tsv yok", "getLocalizedMessage is the user's");
        same(e.toString(), "dev.nxnative.android.LocalizedIOException: /sdcard/pack has no pack.tsv", "toString is English");
        Msg m = new Msg("prepare_cannot_use", "/p", Msg.plural("readiness_summary_limited", 1, 1));
        same(m.text(), "/p kullanılamıyor: 1 oyun burada kısıtlı çalışıyor.", "a Msg in a Msg, user's language");
        same(m.english(), "Cannot use /p: 1 game runs with less here.", "a Msg in a Msg, English");
        same("" + m, m.english(), "a Msg concatenated is English");
        check(m.equals(new Msg("prepare_cannot_use", "/p", Msg.plural("readiness_summary_limited", 1, 1))), "Msg equals");

        // aapt2's rules on a file of its own: escapes, quotes, white space, %% with and without arguments
        File dir = new File(a[0], "res/values");
        check(dir.mkdirs(), "scratch " + dir);
        Files.write(new File(dir, "strings_x.xml").toPath(), ("<resources>\n"
                + "<string name=\"x_apos\">Shader\\'lar \\\"here\\\"</string>\n"
                + "<string name=\"x_space\">  two\n   lines\\nand\\ttab  </string>\n"
                + "<string name=\"x_quoted\">\"  kept  \" and   not</string>\n"
                + "<string name=\"x_pct\">battery below 20 %</string>\n"
                + "<string name=\"x_pct_args\">%1$d %% done</string>\n"
                + "<string name=\"x_uni\">\\u00e7 \\@ \\?</string>\n"
                + "</resources>\n").getBytes(StandardCharsets.UTF_8));
        Tr.Xml x = new Tr.Xml(new File(a[0], "res"), null);
        same(x.string(null, "x_apos"), "Shader'lar \"here\"", "escaped quotes");
        same(x.string(null, "x_space"), "two lines\nand\ttab", "white space collapsed, escapes kept");
        same(x.string(null, "x_quoted"), "  kept   and not", "inside quotes white space stays");
        same(x.string(null, "x_pct"), "battery below 20 %", "a lone % without arguments stays");
        same(x.string(null, "x_pct_args", 40), "40 % done", "%% with arguments");
        same(x.string(null, "x_uni"), "ç @ ?", "unicode and @ ? escapes");
        System.out.println("ok");
    }
}
