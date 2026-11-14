package dev.nxnative.android;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The readiness area's texts on the JVM (docs/i18n.md): the GPU line (Readiness.gpu, now gpuMsg's English) word for word as it was built before its texts moved to
 * strings_readiness.xml, every HOST row reading its own resource, and a check made of Msgs: English in the report (title, message, toString), the user's language
 * on the screen (titleText, messageText), rendered when shown. Run by dm/tools/tests/test_android_readiness_i18n.py. Prints "ok <n>" and exits 0, or the first
 * failure and exits 1.
 */
public final class ReadinessI18nJvmTest {
    private static int n;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    /** The GPU line as Readiness.gpu built it before its texts were resources (2026-11-14): the English the report and the log line must keep */
    static String gpuBefore(Map<String, String> f) {
        if (!f.containsKey("gpu.name")) return "GPU not reported" + (f.containsKey("error") ? " (" + f.get("error") + ")" : f.containsKey("unchecked") ? " (" + f.get("unchecked") + ")" : "");
        StringBuilder s = new StringBuilder(f.get("gpu.name"));
        s.append(", Vulkan ").append(f.getOrDefault("vk.api", "?"));
        s.append("; driver ").append(f.getOrDefault("driver.name", "?"));
        String info = f.getOrDefault("driver.info", "");
        if (!info.isEmpty()) s.append(" (").append(info).append(')');
        s.append(", driver ID ").append(f.getOrDefault("driver.id", "?")).append(", version ").append(f.getOrDefault("driver.version", "?"));
        String custom = f.getOrDefault("driver.custom", "");
        s.append(custom.isEmpty() ? ", the system's driver" : ", custom driver " + custom);
        return s.toString();
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
        // The GPU line: every form (not reported, with an error or a timeout; the system's or a custom driver, with or without driver info; facts missing), and a
        // value with a % in it, which is an argument, never a format
        String[] facts = {
            "", "error=vkCreateInstance returned -9", "unchecked=the GPU check did not answer in 8 s", "error=e\nunchecked=u",
            "gpu.name=Adreno (TM) 750\nvk.api=1.3.284\ndriver.name=Qualcomm Technologies Inc. Adreno Vulkan Driver\ndriver.info=Driver Build: 0762.10\ndriver.id=8\ndriver.version=0x80000000\ndriver.custom=",
            "gpu.name=Adreno (TM) 750\nvk.api=1.4.295\ndriver.name=turnip Mesa driver\ndriver.info=Mesa 25.1 100%\ndriver.id=18\ndriver.version=0x6400000\ndriver.custom=Turnip 25.1",
            "gpu.name=Mali-G715\nvk.api=1.3.0\ndriver.name=Mali\ndriver.id=9\ndriver.version=0x1",
            "gpu.name=X\ndriver.custom=Some driver",
            "gpu.name=X",
        };
        for (String t : facts) {
            Map<String, String> f = Readiness.facts(t);
            check(Readiness.gpu(f).equals(gpuBefore(f)), "gpu [" + Readiness.gpu(f) + "] is [" + gpuBefore(f) + "]");
            check(Readiness.gpuMsg(f).english().equals(gpuBefore(f)) && Readiness.gpuMsg(f).text().equals(gpuBefore(f)), "gpuMsg in English: " + gpuBefore(f));
        }
        Map<String, String> none = new LinkedHashMap<>();
        check(Readiness.gpu(Readiness.merge(null, 15, 36)).equals("GPU not reported (this build's host cannot report the GPU's features)"), "no facts: " + Readiness.gpu(Readiness.merge(null, 15, 36)));
        check(Readiness.gpu(none).equals("GPU not reported"), "nothing at all");

        // Every HOST row has its resource, and two facts with the same text may share a key
        for (Readiness.Req r : Readiness.HOST) check(r.res >= 0, "HOST " + r.fact + " has a row in REQ_FACTS / REQ_KEYS");
        check(new java.util.HashSet<>(List.of(Readiness.REQ_FACTS)).size() == Readiness.REQ_FACTS.length, "REQ_FACTS: each fact once");

        // The checks that are no requirement row: English as before
        List<Readiness.Check> l = Readiness.evaluate(Readiness.merge("error=vkCreateInstance returned -9\n", 15, 36), List.of());
        Readiness.Check gf = l.stream().filter(c -> "error".equals(c.fact)).findFirst().orElse(null);
        check(gf != null && gf.toString().equals("[FAIL] GPU features: The GPU driver could not be used (vkCreateInstance returned -9): the game needs Vulkan 1.3.") && "driver".equals(gf.fix), "GPU features: " + gf);
        l = Readiness.evaluate(Readiness.merge("unchecked=the GPU check did not answer in 8 s\n", 15, 36), List.of());
        gf = l.stream().filter(c -> "unchecked".equals(c.fact)).findFirst().orElse(null);
        check(gf != null && gf.toString().equals("[WARNING] GPU features: Not checked (the GPU check did not answer in 8 s): whether this GPU has what the game needs is not known.") && gf.fix == null, "unchecked: " + gf);
        Readiness.Check st = Readiness.status("bring-up");
        check(st.toString().equals("[WARNING] Status on Android: This game is still being brought up on Android: it may stop or draw wrongly on any phone, this one included.")
                && "status".equals(st.fact), "bring-up: " + st);
        st = Readiness.status("retired");
        check(st.toString().equals("[INFO] Status on Android: retired") && st.titleText().equals("Status on Android") && st.messageText().equals("retired"), "another status: " + st);

        // A requirement row: the report's message is the English it always was; the screen's is a whole-sentence resource with the row's why after a colon,
        // as written (no letter upper-cased), and the row's facts as arguments (docs/i18n.md "Readiness requirements")
        Readiness.Check ts = Readiness.evaluate(Readiness.facts("vk.api=1.3\nfeature.timelineSemaphore=0"), List.of()).stream()
                .filter(x -> "feature.timelineSemaphore".equals(x.fact)).findFirst().orElse(null);
        check(ts != null && ts.message.equals("This device lacks it; this game needs it. The game's fences are built on them. (feature.timelineSemaphore: reports 0, needs 1)"), "report: " + ts);
        check(ts.messageText().equals("This device lacks it; this game needs it: the game's fences are built on them. (feature.timelineSemaphore: reports 0, needs 1)"), "screen: " + ts.messageText());
        Readiness.Check sdk = Readiness.evaluate(Readiness.merge("vk.api=1.3", 11, 31), List.of()).stream()
                .filter(x -> "android.sdk".equals(x.fact) && x.status == Readiness.Status.WARNING).findFirst().orElse(null);
        check(sdk != null && sdk.message.equals("Missing: this game runs without it, with less. Android 12 and 12L are untested; the game may not start or may draw wrongly there. "
                + "(Android SDK: reports 31, needs 33)"), "report, degraded: " + sdk);
        check(sdk.messageText().equals("Missing; this game runs without it, with less: Android 12 and 12L are untested; the game may not start or may draw wrongly there. "
                + "(Android SDK: reports 31, needs 33)"), "screen, degraded: " + sdk.messageText());
        // A game's row without a why: the sentence without one, the same English as the report's
        Readiness.Check ram = Readiness.evaluate(Readiness.merge("vk.api=1.3", 6, 36), Readiness.parseReqs("required|ram_gib|8|")).stream()
                .filter(x -> "ram_gib".equals(x.fact)).findFirst().orElse(null);
        check(ram != null && ram.message.equals("This device lacks it; this game needs it. (memory in GiB: reports 6.0, needs 8)") && ram.messageText().equals(ram.message), "no why: " + ram);
        Readiness.Check ok = Readiness.evaluate(Readiness.facts("vk.api=1.3.284"), List.of()).stream().filter(x -> "Vulkan 1.3 GPU driver".equals(x.title)).findFirst().orElse(null);
        check(ok != null && ok.message.equals("Present (vk.api: 1.3.284, needs 1.3).") && ok.messageText().equals(ok.message), "present: " + ok);
        for (String[] rq : new String[][] {{"required", "lacks"}, {"degraded", "missing"}})   // a game row that names its why: after the colon, as written
            check(Readiness.evaluate(Readiness.facts("vk.api=1.3"), Readiness.parseReqs(rq[0] + "|feature.x|1|X: the water needs it")).stream()
                    .anyMatch(x -> "feature.x".equals(x.fact) && x.messageText().contains("with less: the water needs it. (") == rq[0].equals("degraded")
                            && x.messageText().contains("needs it: the water needs it. (") == rq[0].equals("required")), "a game's row: " + rq[0]);

        // "unchecked": the fact and the report stay English; the screen's reason is chosen by its code
        check(Readiness.uncheckedCode(Readiness.UNCHECKED_NO_HOST).equals("no_host") && Readiness.uncheckedCode("the GPU check did not answer in 8 s").equals("timeout")
                && Readiness.uncheckedCode("The host of 0400486024DEC000 cannot be loaded: dlopen failed").equals("other"), "unchecked codes");
        check(Readiness.uncheckedTimeout(8).equals("unchecked=the GPU check did not answer in 8 s"), "the timeout fact GameActivity writes");
        check(Readiness.facts(Readiness.uncheckedTimeout(12)).get("unchecked").equals("the GPU check did not answer in 12 s")
                && Readiness.uncheckedCode(Readiness.facts(Readiness.uncheckedTimeout(12)).get("unchecked")).equals("timeout"), "its value has the timeout's code");
        check(Readiness.uncheckedReason(Readiness.UNCHECKED_NO_HOST) instanceof Msg && Readiness.uncheckedReason("the GPU check did not answer in 8 s") instanceof Msg
                && "detail".equals(Readiness.uncheckedReason("detail")), "a Msg for the launcher's two, the loader's words as written");
        for (String u : new String[] {Readiness.UNCHECKED_NO_HOST, "the GPU check did not answer in 8 s", "The host of X cannot be loaded: y"}) {
            Readiness.Check c = Readiness.evaluate(Readiness.merge("unchecked=" + u + "\n", 15, 36), List.of()).stream().filter(x -> "unchecked".equals(x.fact)).findFirst().orElse(null);
            String want = "Not checked (" + u + "): whether this GPU has what the game needs is not known.";
            check(c != null && c.message.equals(want) && c.messageText().equals(want), "unchecked in English: " + c);
            check(Readiness.gpuMsg(Readiness.facts("unchecked=" + u)).english().equals("GPU not reported (" + u + ")"), "the GPU line: " + u);
        }

        // Readiness.ago: a relative time from resources (the app's language; Android's DateUtils followed the system's), English in the report
        long now = 1_763_100_000_000L, m = 60_000, h = 60 * m, d = 24 * h;
        String[][] ago = {{"0", "just now"}, {String.valueOf(59_000), "just now"}, {String.valueOf(m), "1 minute ago"}, {String.valueOf(5 * m), "5 minutes ago"},
                {String.valueOf(h), "1 hour ago"}, {String.valueOf(23 * h), "23 hours ago"}, {String.valueOf(d), "1 day ago"}, {String.valueOf(6 * d), "6 days ago"},
                {String.valueOf(-5 * m), "just now"}};
        for (String[] x : ago) check(Readiness.ago(now - Long.parseLong(x[0]), now).english().equals(x[1]), "ago " + x[0] + ": " + Readiness.ago(now - Long.parseLong(x[0]), now).english());
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date(now - 8 * d));
        check(Readiness.ago(now - 8 * d, now).english().equals("on " + day), "a week or more: the day");
        check(new Msg("readiness_last_exit_normal", new Msg("readiness_exit_normal"), Readiness.ago(now - 5 * m, now)).english().equals("The last game ended normally, 5 minutes ago."), "inside the sentence");

        // ReadinessActivity's sentences that name home's Settings rows take them as arguments (HomeLabels): the English as when the words were typed in
        check(new Msg("readiness_driver_picked", HomeLabels.gpuSystem(), HomeLabels.settings(), HomeLabels.gpuDriver()).english().equals("System (Settings › GPU driver)."), "driver picked");
        check(new Msg("readiness_last_exit", new Msg("readiness_exit_crashed"), "5 minutes ago", HomeLabels.settings(), HomeLabels.shareLog()).english()
                .equals("The last game crashed, 5 minutes ago. Settings › Share log sends its log."), "last exit");
        check(new Msg("readiness_last_exit_desc", new Msg("readiness_exit_crashed"), "5 minutes ago", "lmk", HomeLabels.settings(), HomeLabels.shareLog()).english()
                .equals("The last game crashed, 5 minutes ago (lmk). Settings › Share log sends its log."), "last exit with Android's description");

        // In another language (made-up translations in a scratch res folder): titleText and messageText translated, rendered when shown; title, message,
        // toString and the GPU line English
        File scratch = Files.createTempDirectory("readiness-i18n").toFile();
        File en = new File(scratch, "values"), trd = new File(scratch, "values-tr");
        check(en.mkdirs() && trd.mkdirs(), "scratch res");
        Files.copy(new File(System.getProperty("nx.tr.res"), "values/strings_readiness.xml").toPath(), new File(en, "strings_readiness.xml").toPath());
        Files.write(new File(trd, "strings_readiness.xml").toPath(), ("<resources><string name=\"readiness_check_status\">TR durum</string>"
                + "<string name=\"readiness_status_bring_up\">TR getiriliyor</string><string name=\"readiness_gpu_error\">TR sürücü (%1$s)</string>"
                + "<string name=\"readiness_gpu_system\">TR %1$s %2$s %3$s %4$s %5$s</string>"
                + "<string name=\"readiness_req_vk_api\">TR Vulkan: TR neden</string>"
                + "<string name=\"readiness_row_lacks\">TR eksik: %1$s (%2$s %3$s, en az %4$s)</string><string name=\"readiness_row_reports\">TR %1$s bildiriyor</string>"
                + "<string name=\"readiness_row_met\">TR var (%1$s %2$s, en az %3$s).</string>"
                + "<string name=\"readiness_gpu_unchecked\">TR denetlenmedi (%1$s).</string><string name=\"readiness_unchecked_timeout\">TR %1$d sn</string>"
                + "<string name=\"readiness_gpu_not_reported_why\">TR GPU yok (%1$s)</string>"
                + "<plurals name=\"readiness_ago_minutes\"><item quantity=\"one\">%1$d dakika önce</item><item quantity=\"other\">%1$d dakika önce</item></plurals>"
                + "</resources>").getBytes(StandardCharsets.UTF_8));
        Readiness.Check made = Readiness.status("bring-up");   // made in English, shown in Turkish: rendered when shown
        Tr.Backend before = Tr.backend();
        Tr.install(new Tr.Xml(scratch, Locale.forLanguageTag("tr")));
        try {
            check(made.titleText().equals("TR durum") && made.messageText().equals("TR getiriliyor") && made.title.equals("Status on Android"), "rendered when shown: " + made.titleText());
            Readiness.Check c = Readiness.status("bring-up");
            check(c.titleText().equals("TR durum") && c.messageText().equals("TR getiriliyor") && c.toString().startsWith("[WARNING] Status on Android: This game"), "translated on screen, English in the report");
            c = Readiness.evaluate(Readiness.merge("error=boom\n", 15, 36), List.of()).stream().filter(x -> "error".equals(x.fact)).findFirst().orElse(null);
            check(c != null && c.messageText().equals("TR sürücü (boom)") && c.message.startsWith("The GPU driver could not be used (boom)"), "an argument in both languages");
            Map<String, String> f = Readiness.facts("gpu.name=G\nvk.api=1.3\ndriver.name=D\ndriver.id=8\ndriver.version=0x1");
            check(Readiness.gpuMsg(f).text().equals("TR G 1.3 D 8 0x1") && Readiness.gpu(f).equals(gpuBefore(f)), "the GPU line: the screen's translated, gpu() English");
            Readiness.Check api = Readiness.evaluate(Readiness.facts("vk.api=1.3"), List.of()).stream().filter(x -> "Vulkan 1.3 GPU driver".equals(x.title)).findFirst().orElse(null);
            check(api != null && api.titleText().equals("TR Vulkan") && api.title.equals("Vulkan 1.3 GPU driver") && api.messageText().equals("TR var (vk.api: 1.3, en az 1.3).")
                    && api.message.equals("Present (vk.api: 1.3, needs 1.3)."), "a requirement row: title and message translated, the report English: " + api.messageText());
            Readiness.Check low = Readiness.evaluate(Readiness.facts("vk.api=1.1"), List.of()).stream().filter(x -> "vk.api".equals(x.fact)).findFirst().orElse(null);
            check(low != null && low.messageText().equals("TR eksik: TR neden (vk.api: TR 1.1 bildiriyor, en az 1.3)")
                    && low.message.equals("This device lacks it; this game needs it. The graphics replay is written for Vulkan 1.3. (vk.api: reports 1.1, needs 1.3)"),
                    "a failed row: the translated why inside the translated sentence, the report English: " + low.messageText());
            Readiness.Check game = Readiness.evaluate(Readiness.facts("vk.api=1.3"), Readiness.parseReqs("required|feature.x|1|X: the water needs it")).stream()
                    .filter(x -> "feature.x".equals(x.fact)).findFirst().orElse(null);
            check(game != null && game.titleText().equals("X") && game.messageText().equals("TR eksik: the water needs it (feature.x: not reported, en az 1)"),
                    "a game's own row: its English why after the translated sentence's colon: " + game.messageText());
            Readiness.Check un = Readiness.evaluate(Readiness.merge(Readiness.uncheckedTimeout(8), 15, 36), List.of()).stream().filter(x -> "unchecked".equals(x.fact)).findFirst().orElse(null);
            check(un != null && un.messageText().equals("TR denetlenmedi (TR 8 sn).") && un.message.startsWith("Not checked (the GPU check did not answer in 8 s)"), "unchecked: " + un.messageText());
            Msg five = Readiness.ago(now - 5 * m, now);
            check(five.text().equals("5 dakika önce") && five.english().equals("5 minutes ago"), "a relative time: the screen's language, English in the report");
            check(Readiness.gpuMsg(Readiness.facts(Readiness.uncheckedTimeout(8))).text().equals("TR GPU yok (TR 8 sn)")
                    && Readiness.gpu(Readiness.facts(Readiness.uncheckedTimeout(8))).equals("GPU not reported (the GPU check did not answer in 8 s)"), "the GPU line, unchecked");
        } finally {
            Tr.install(before);
        }
    }
}
