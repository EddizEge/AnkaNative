package dev.nxnative.android;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * The readiness check's decisions on the JVM (Readiness.java): requirement rows, facts, version compare, the host's needs, required against degraded, the log line and
 * the report. Run by dm/tools/tests/test_android_readiness.py with {@code <facts file> <rows>}: the facts of a phone (the Honor PNM-N49's, as nxhost_vk_readiness wrote
 * them, or made-up ones) and a profile's rows as build.gradle hands them over. Prints "ok <n>" and exits 0, or the first failure and exits 1.
 */
public final class ReadinessJvmTest {
    private static int n;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    static Readiness.Check find(List<Readiness.Check> l, String title) {
        for (Readiness.Check c : l) if (c.title.equals(title)) return c;
        return null;
    }

    public static void main(String[] a) throws Exception {
        try {
            run(a);
        } catch (AssertionError e) {
            System.out.println("FAIL: " + e.getMessage());
            System.exit(1);
        }
        System.out.println("ok " + n);
    }

    static void run(String[] a) throws Exception {
        // Version and number compare
        check(Readiness.compare("1.4.295", "1.3") > 0 && Readiness.compare("1.3", "1.3.0") == 0 && Readiness.compare("1.2.199", "1.3") < 0, "dotted versions");
        check(Readiness.compare("49152", "49152") == 0 && Readiness.compare("32768", "49152") < 0 && Readiness.compare("0x10", "15") > 0, "numbers and hex");
        check(Readiness.compare("14.7", "14") > 0 && Readiness.compare("11.2", "14") < 0 && Readiness.compare("yes", "1") < 0, "decimals; text reads as 0");

        // The lines the check runs with: the driver pick and the driver's own variables (read once per process, at the check's vkCreateInstance), nothing else
        String[] dk = Readiness.driverEnv(List.of("DM_VK_DRIVER=/x/Anka", "TU_DEBUG=noubwc,nolrz", "DM_VK_TEXFB=0", "FD_RD_DUMP=1", "IR3_SHADER_DEBUG=nouboopt", "MESA_SHADER_CACHE_DISABLE=1", "TU_=1", "TUX=1", "DM_VK_HOOKDIR=/h"));
        check(java.util.Arrays.equals(dk, new String[] {"DM_VK_DRIVER=/x/Anka", "TU_DEBUG=noubwc,nolrz", "FD_RD_DUMP=1", "IR3_SHADER_DEBUG=nouboopt", "MESA_SHADER_CACHE_DISABLE=1", "DM_VK_HOOKDIR=/h"}),
              "driverEnv: the driver pick and its own variables " + java.util.Arrays.toString(dk));
        // Rows: malformed ones skipped, title and why split at ": "
        List<Readiness.Req> rows = Readiness.parseReqs("required|ram_gib|14|Memory: needs a lot;bogus|x|1|y;degraded|sparse.format.bc6h|1|Sparse BC6H;required||1|no fact");
        check(rows.size() == 2 && rows.get(0).required && rows.get(0).title.equals("Memory") && rows.get(0).why.equals("needs a lot") && !rows.get(1).required
                && rows.get(1).title.equals("Sparse BC6H") && rows.get(1).why.isEmpty(), "parseReqs");
        check(Readiness.parseReqs(null).isEmpty() && Readiness.parseReqs("").isEmpty(), "no rows");

        // The host rows' text resources (Readiness.REQ_FACTS -> REQ_KEYS, docs/i18n.md): each table fact is a HOST row, its resource's English is that row's text
        // word for word; a host row the table does not know and every game row keep their English
        check(Readiness.REQ_FACTS.length == Readiness.REQ_KEYS.length && Readiness.REQ_FACTS.length > 0, "REQ_FACTS and REQ_KEYS pair up");
        int withRes = 0;
        for (Readiness.Req r : Readiness.HOST) {
            String what = r.title + (r.why.isEmpty() ? "" : ": " + r.why);
            check(r.english().equals(what), "HOST " + r.fact + ": the resource's English [" + r.english() + "] is the row's [" + what + "]");
            check(r.shown().equals(what) && r.shownTitle().equals(r.title) && r.shownWhy().equals(r.why), "HOST " + r.fact + ": shown in English on the JVM");
            if (r.res >= 0) { withRes++; check(Readiness.REQ_FACTS[r.res].equals(r.fact) || Readiness.REQ_FACTS[r.res].equals(r.fact + ">=" + r.min), "HOST " + r.fact + ": its own table row"); }
        }
        // Two HOST rows of one fact (android.sdk 31 and 33): each reads its own key, the later one through "fact>=min"
        java.util.Set<Integer> sdkRows = new java.util.HashSet<>();
        for (Readiness.Req r : Readiness.HOST) if (r.fact.equals("android.sdk")) check(sdkRows.add(r.res) && r.res >= 0, "android.sdk " + r.min + ": a resource of its own");
        check(sdkRows.size() == 2, "both android.sdk rows: " + sdkRows);
        check(withRes == Readiness.REQ_FACTS.length, "every REQ_FACTS fact is a HOST row (" + withRes + " of " + Readiness.REQ_FACTS.length + ")");
        Readiness.Req own = Readiness.parseReqs("required|feature.timelineSemaphore|1|Fences: this game's own words").get(0);
        check(own.res == -1 && own.shown().equals("Fences: this game's own words") && own.shownTitle().equals("Fences"), "a game's row keeps its readiness.tsv English");
        Readiness.Req ts = null;
        for (Readiness.Req r : Readiness.HOST) if (r.fact.equals("feature.timelineSemaphore")) ts = r;
        check(ts != null && ts.res >= 0 && ts.shownTitle().equals("Timeline semaphores"), "the timeline semaphore row reads its resource");
        // In another language (a made-up translation in a scratch res folder): the screen's text is the translation, the report's stays English
        File scratch = Files.createTempDirectory("readiness-tr").toFile();
        File en = new File(scratch, "values"), trd = new File(scratch, "values-tr");
        check(en.mkdirs() && trd.mkdirs(), "scratch res");
        Files.write(new File(en, "strings_readiness.xml").toPath(), ("<resources><string name=\"readiness_req_feature_timeline_semaphore\">" + ts.title + ": "
                + ts.why.replace("'", "\\'") + "</string></resources>").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(trd, "strings_readiness.xml").toPath(),
                "<resources><string name=\"readiness_req_feature_timeline_semaphore\">TR başlık: TR neden</string></resources>".getBytes(StandardCharsets.UTF_8));
        Tr.Backend before = Tr.backend();
        Tr.install(new Tr.Xml(scratch, java.util.Locale.forLanguageTag("tr")));
        try {
            check(ts.shownTitle().equals("TR başlık") && ts.shownWhy().equals("TR neden") && ts.english().equals(ts.title + ": " + ts.why), "translated on screen, English in reports");
            Readiness.Check c = Readiness.evaluate(Readiness.facts("feature.timelineSemaphore=1"), List.of()).stream()
                    .filter(x -> "Timeline semaphores".equals(x.title)).findFirst().orElse(null);
            check(c != null && c.titleText().equals("TR başlık") && c.toString().startsWith("[PASS] Timeline semaphores: "), "a Check: titleText translated, toString English");
        } finally {
            Tr.install(before);
        }

        // A phone with everything: every check passes
        String all = "vk.instance=1.4.0\nvk.api=1.4.295\nqueue.graphicsCompute=1\nfeature.timelineSemaphore=1\nfeature.dynamicRendering=1\next.VK_KHR_swapchain=1\n"
                + "ext.VK_KHR_map_memory2=1\next.VK_EXT_map_memory_placed=1\nfeature.memoryMapPlaced=1\nfeature.descriptorBindingSampledImageUpdateAfterBind=1\n"
                + "feature.descriptorBindingPartiallyBound=1\nfeature.runtimeDescriptorArray=1\nlimit.maxComputeSharedMemorySize=49152\nsparse.format.bc6h=1\n"
                + "gpu.name=Test GPU\ndriver.name=Test driver\ndriver.info=v1 = one\ndriver.id=8\ndriver.version=0x1\ndriver.custom=\n";
        Map<String, String> f = Readiness.merge(all, 14.66, 36);
        check(f.get("driver.info").equals("v1 = one") && f.get("ram_gib").equals("14.7") && f.get("android.sdk").equals("36"), "facts: the first '=' splits; the launcher's own");
        List<Readiness.Check> l = Readiness.evaluate(f, rows);
        check(!Readiness.any(l, Readiness.Status.FAIL) && !Readiness.any(l, Readiness.Status.WARNING), "all present: " + l);
        check(Readiness.summary("0123456789ABCDEF", f, l).startsWith("[readiness] 0123456789ABCDEF: PASS; Test GPU, Vulkan 1.4.295; driver Test driver (v1 = one), driver ID 8"), "summary " + Readiness.summary("0123456789ABCDEF", f, l));

        // Missing: a required row fails, a degraded one warns, a degraded row of a failed fact is left out, a host need fails once per title
        f = Readiness.merge(all.replace("limit.maxComputeSharedMemorySize=49152", "limit.maxComputeSharedMemorySize=32768").replace("sparse.format.bc6h=1", "sparse.format.bc6h=0")
                .replace("ext.VK_EXT_map_memory_placed=1\nfeature.memoryMapPlaced=1\n", ""), 11.2, 36);
        List<Readiness.Req> game = Readiness.parseReqs("required|ram_gib|10|Memory: a;degraded|ram_gib|14|Memory: b;required|limit.maxComputeSharedMemorySize|49152|Compute shared memory: big;"
                + "degraded|sparse.format.bc6h|1|Sparse BC6H: coarser");
        l = Readiness.evaluate(f, game);
        Readiness.Check placed = find(l, "Placed memory maps");
        check(placed != null && placed.status == Readiness.Status.FAIL && placed.message.contains("not reported") && placed.message.startsWith("This device lacks it; this game needs it."), "placed maps fail: " + placed);
        check(Readiness.of(l, Readiness.Status.FAIL).stream().filter(c -> c.title.equals("Placed memory maps")).count() == 1, "one failure per title");
        Readiness.Check cs = find(l, "Compute shared memory");
        check(cs != null && cs.status == Readiness.Status.FAIL && cs.message.contains("reports 32768, needs 49152") && cs.message.contains("Big."), "shared memory fails: " + cs);
        Readiness.Check bc6 = find(l, "Sparse BC6H");
        check(bc6 != null && bc6.status == Readiness.Status.WARNING && bc6.message.startsWith("Missing: this game runs without it"), "degraded warns");
        check(l.stream().filter(c -> c.title.equals("Memory")).map(c -> c.status).collect(java.util.stream.Collectors.toList()).equals(List.of(Readiness.Status.PASS, Readiness.Status.WARNING)), "memory 11.2: required 10 passes, degraded 14 warns");
        String s = Readiness.summary("0123456789ABCDEF", f, l);
        check(s.contains(": FAIL;") && s.contains("missing (required): Placed memory maps, Compute shared memory") && s.contains("missing (degraded): Memory, Sparse BC6H"), "summary " + s);
        l = Readiness.evaluate(Readiness.merge(all, 8.0, 36), Readiness.parseReqs("required|ram_gib|10|Memory: a;degraded|ram_gib|14|Memory: b"));
        check(find(l, "Memory").status == Readiness.Status.FAIL && Readiness.of(l, Readiness.Status.WARNING).isEmpty(), "the degraded row of a failed fact is left out");

        // No Vulkan facts (no answer, or a host that cannot report): one warning, nothing called missing; the launcher's own facts still checked
        l = Readiness.evaluate(Readiness.merge(null, 15, 30), game);
        check(Readiness.of(l, Readiness.Status.FAIL).size() == 1 && find(l, "Android 12 or newer").status == Readiness.Status.FAIL && find(l, "GPU features").status == Readiness.Status.WARNING
                && find(l, "Android 13 or newer") == null, "no facts: " + l);
        l = Readiness.evaluate(Readiness.merge(null, 15, 31), game);
        check(!Readiness.any(l, Readiness.Status.FAIL) && find(l, "Android 12 or newer").status == Readiness.Status.PASS, "Android 12 (SDK 31) passes: " + l);
        Readiness.Check untested = find(l, "Android 13 or newer");
        check(untested != null && untested.status == Readiness.Status.WARNING && untested.message.contains("untested"), "Android 12 (SDK 31) warns it is untested: " + l);
        l = Readiness.evaluate(Readiness.merge(null, 15, 32), game);
        check(find(l, "Android 13 or newer").status == Readiness.Status.WARNING, "Android 12L (SDK 32) warns: " + l);
        l = Readiness.evaluate(Readiness.merge(null, 15, 33), game);
        check(find(l, "Android 13 or newer").status == Readiness.Status.PASS, "Android 13 (SDK 33) passes: " + l);
        // Android 12's Vulkan loader reports 1.1: with a 1.3 driver that passes; a 1.1 driver does not
        l = Readiness.evaluate(Readiness.merge(all.replace("vk.instance=1.4.0", "vk.instance=1.1.0"), 15.0, 31), game);
        check(find(l, "Vulkan 1.1 loader or newer").status == Readiness.Status.PASS && find(l, "Vulkan 1.3 GPU driver").status == Readiness.Status.PASS, "a 1.1 loader with a 1.3 driver: " + l);
        l = Readiness.evaluate(Readiness.merge(all.replace("vk.instance=1.4.0", "vk.instance=1.1.0").replace("vk.api=1.4.295", "vk.api=1.1.128"), 15.0, 31), game);
        check(find(l, "Vulkan 1.3 GPU driver").status == Readiness.Status.FAIL, "a 1.1 driver fails: " + l);
        l = Readiness.evaluate(Readiness.merge("unchecked=the GPU check did not answer in 8 s\n", 15, 36), game);
        check(!Readiness.any(l, Readiness.Status.FAIL) && find(l, "GPU features").message.contains("did not answer"), "a timeout is no failure: " + l);
        // The driver answered and failed: one failure
        l = Readiness.evaluate(Readiness.merge("error=vkCreateInstance returned -9\nvk.instance=1.3.0\n", 15, 36), game);
        check(find(l, "GPU features").status == Readiness.Status.FAIL && find(l, "GPU features").message.contains("vkCreateInstance returned -9")
                && find(l, "Vulkan 1.1 loader or newer").status == Readiness.Status.PASS && Readiness.of(l, Readiness.Status.FAIL).size() == 1, "an error line: " + l);

        // Status and the driver's environment
        check(Readiness.status("playable") == null && Readiness.status("") == null && Readiness.status("bring-up").status == Readiness.Status.WARNING, "status");
        String[] de = Readiness.driverEnv(List.of("DM_SAVE=/x", "DM_VK_DRIVER=/d/turnip", "DM_VK_HOOKDIR=/h", "DM_VK_DRIVER=system"));
        check(de.length == 3 && de[0].equals("DM_VK_DRIVER=/d/turnip") && de[2].equals("DM_VK_DRIVER=system"), "driverEnv keeps the order (the last wins)");

        // The report: header, GPU, the checks, every fact
        String rep = Readiness.report("head", f, Readiness.evaluate(f, game));
        check(rep.startsWith("head\nGPU: Test GPU") && rep.contains("[FAIL] Compute shared memory") && rep.contains("-- facts --\n") && rep.contains("limit.maxComputeSharedMemorySize=32768"), "report");

        // A real phone's facts against a real profile's rows (the test's arguments)
        if (a.length >= 2) {
            Map<String, String> real = Readiness.merge(new String(Files.readAllBytes(new File(a[0]).toPath()), StandardCharsets.UTF_8), 14.66, 36);
            List<Readiness.Req> req = Readiness.parseReqs(a[1]);
            check(!req.isEmpty(), "the profile's rows parse");
            List<Readiness.Check> rl = Readiness.evaluate(real, req);
            System.out.println(Readiness.summary("profile", real, rl));
            for (Readiness.Check c : rl) System.out.println(c);
        }
    }
}
