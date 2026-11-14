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

        // In another language (made-up translations in a scratch res folder): titleText and messageText translated, rendered when shown; title, message,
        // toString and the GPU line English
        File scratch = Files.createTempDirectory("readiness-i18n").toFile();
        File en = new File(scratch, "values"), trd = new File(scratch, "values-tr");
        check(en.mkdirs() && trd.mkdirs(), "scratch res");
        Files.copy(new File(System.getProperty("nx.tr.res"), "values/strings_readiness.xml").toPath(), new File(en, "strings_readiness.xml").toPath());
        Files.write(new File(trd, "strings_readiness.xml").toPath(), ("<resources><string name=\"readiness_check_status\">TR durum</string>"
                + "<string name=\"readiness_status_bring_up\">TR getiriliyor</string><string name=\"readiness_gpu_error\">TR sürücü (%1$s)</string>"
                + "<string name=\"readiness_gpu_system\">TR %1$s %2$s %3$s %4$s %5$s</string>"
                + "<string name=\"readiness_req_vk_api\">TR Vulkan: TR neden</string></resources>").getBytes(StandardCharsets.UTF_8));
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
            check(api != null && api.titleText().equals("TR Vulkan") && api.title.equals("Vulkan 1.3 GPU driver") && api.messageText().equals(api.message), "a requirement row: title translated, message English");
        } finally {
            Tr.install(before);
        }
    }
}
