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
        same(Tr.s("prepare_cannot_use", "/p", Msg.plural("readiness_summary_limited", 1, 1)), m.text(), "a Msg passed to Tr.s renders in the user's language");
        same(Tr.english("prepare_cannot_use", "/p", Msg.plural("readiness_summary_limited", 1, 1)), m.english(), "a Msg passed to Tr.english renders in English");
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

        // A shipped build (Tr.forBuild, Tr.Lenient): a missing key or a bad format logs one line and shows the English, or the key; a debuggable build throws
        File trDir = new File(a[0], "res/values-tr");
        check(trDir.mkdirs(), "scratch " + trDir);
        Files.write(new File(dir, "strings_y.xml").toPath(), ("<resources>\n"
                + "<string name=\"y_count\">%1$d left</string>\n"
                + "<string name=\"y_only_en\">English only</string>\n"
                + "<plurals name=\"y_games\"><item quantity=\"one\">%1$d game</item><item quantity=\"other\">%1$d games</item></plurals>\n"
                + "</resources>\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(trDir, "strings_y.xml").toPath(), ("<resources>\n"
                + "<string name=\"y_count\">%1$s kaldı %2$s</string>\n"
                + "<plurals name=\"y_games\"><item quantity=\"other\">%2$d oyun</item></plurals>\n"
                + "</resources>\n").getBytes(StandardCharsets.UTF_8));
        Tr.Xml ytr = new Tr.Xml(new File(a[0], "res"), Locale.forLanguageTag("tr"));
        java.util.List<String> log = new java.util.ArrayList<>();
        Tr.Backend strict = Tr.forBuild(ytr, true, log::add), lenient = Tr.forBuild(ytr, false, log::add);
        check(strict == ytr, "a debuggable build installs the bare backend");
        try { strict.string(null, "y_missing"); check(false, "debuggable: a missing key throws"); } catch (MissingResourceException expected) { /* loud */ }
        try { strict.string(null, "y_count", 3); check(false, "debuggable: a bad format throws"); } catch (java.util.IllegalFormatException expected) { /* loud */ }
        same(lenient.string(null, "y_only_en"), "English only", "shipped: a text without its translation is the English, no error");
        check(log.isEmpty(), "shipped: nothing logged for a working text: " + log);
        same(lenient.string(null, "y_missing"), "y_missing", "shipped: a missing key shows the key");
        check(log.size() == 1 && log.get(0).contains("y_missing") && log.get(0).contains("MissingResourceException"), "shipped: one line naming the key: " + log);
        same(lenient.string(null, "y_missing"), "y_missing", "shipped: the same key again");
        check(log.size() == 1, "shipped: one line per key, not one per use: " + log);
        same(lenient.string(null, "y_count", 3), "3 left", "shipped: a translation whose format fails shows the English");
        check(log.size() == 2 && log.get(1).contains("y_count") && log.get(1).contains("Format"), "shipped: the line names the key and the format error: " + log);
        same(lenient.string(null, "y_count", "x"), "%1$d left", "shipped: English that fails too is shown as written");
        same(lenient.plural(null, "y_games", 2, 2), "2 games", "shipped: a plural whose format fails shows the English");
        same(lenient.plural(null, "y_nope", 2, 2), "y_nope", "shipped: a missing plural shows the key");
        same(lenient.string(Locale.ENGLISH, "y_count", 4), "4 left", "shipped: english() as before");
        Tr.install(lenient);
        same(new Msg("y_count", 5).text(), "5 left", "shipped: a Msg goes through the same guard");
        same(Tr.s("y_missing2"), "y_missing2", "shipped: Tr.s goes through the same guard");
        System.out.println("ok");
    }
}
