package dev.nxnative.android;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The readiness check's decisions (android/README.md "Readiness check"): what this phone and its GPU driver report, against what the host needs for every game
 * ({@link #HOST}, the checks vka_dev.c makes) and what the game's profile asks for (its req rows, dm/games/README.md "Keys"). Each requirement is "required" (the game
 * will not run, or will draw nothing, without it) or "degraded" (it runs, with something missing or at lower detail). The facts are "key=value" lines: the host's
 * nxhost_vk_readiness (vka_readiness.c, on the driver the game would load: whatever it reports, a custom driver's too) and the launcher's own (ram_gib, android.sdk).
 * Nothing here knows a vendor or a game: the numbers come from the driver and the profile. No Android classes: the JVM test runs it on the Mac.
 * The screen (ReadinessActivity) and the start-up check (GameActivity) show these; the screen's pass / warning / fail cards with a fix button, Run again and the shareable
 * report are adapted from the author's earlier readiness check.
 */
final class Readiness {
    enum Status { PASS, WARNING, FAIL, INFO }

    /**
     * The host's requirement rows that have a text resource (docs/i18n.md "Readiness requirements"): the row of fact REQ_FACTS[i] reads REQ_KEYS[i], "title: why"
     * in the user's language. A literal table, so test_android_i18n.py checks every key exists. A host row whose fact is not here, and every row of a game's own
     * readiness.tsv, keeps the English it was written with. Add a row's fact and key at the same index, and its English, the same as the HOST row's, to
     * strings_readiness.xml (ReadinessJvmTest checks both). When two HOST rows check the same fact, the later one's table fact is "fact>=min"
     * ("android.sdk>=33"), which a row matches before its bare fact
     */
    static final String[] REQ_FACTS = {"feature.timelineSemaphore", "android.sdk", "vk.instance", "vk.api", "queue.graphicsCompute", "feature.dynamicRendering",
            "ext.VK_KHR_swapchain", "ext.VK_KHR_map_memory2", "ext.VK_EXT_map_memory_placed", "feature.memoryMapPlaced", "feature.descriptorBindingSampledImageUpdateAfterBind",
            "feature.descriptorBindingPartiallyBound", "feature.runtimeDescriptorArray", "android.sdk>=33"};
    // The two placed-memory rows have the same text, so they share one key
    static final String[] REQ_KEYS = {"readiness_req_feature_timeline_semaphore", "readiness_req_android_sdk", "readiness_req_vk_instance", "readiness_req_vk_api",
            "readiness_req_queue_graphics_compute", "readiness_req_feature_dynamic_rendering", "readiness_req_ext_vk_khr_swapchain", "readiness_req_ext_vk_khr_map_memory2",
            "readiness_req_ext_vk_ext_map_memory_placed", "readiness_req_ext_vk_ext_map_memory_placed", "readiness_req_feature_descriptor_binding_sampled_image_update_after_bind",
            "readiness_req_feature_descriptor_binding_partially_bound", "readiness_req_feature_runtime_descriptor_array", "readiness_req_android_sdk_33"};

    /** One requirement: the fact (a key of the facts) must be at least min (a number, a dotted version, or 0x hex). what: "title: why" */
    static final class Req {
        final boolean required;
        /** title and why: English, for the report and the log; the screen's are {@link #shownTitle} and {@link #shownWhy} */
        final String fact, min, title, why;
        /** The row's index in REQ_KEYS, or -1: no resource (a game's row, or a host row the table does not know) */
        final int res;

        /** A game's row (Readiness.parseReqs): its text stays the English of its readiness.tsv */
        Req(boolean required, String fact, String min, String what) { this(required, fact, min, what, false); }

        /** host: one of HOST's rows, whose text is REQ_KEYS's when the table knows its fact */
        Req(boolean required, String fact, String min, String what, boolean host) {
            this.required = required;
            this.fact = fact;
            this.min = min;
            int c = what.indexOf(": ");
            this.title = c > 0 ? what.substring(0, c) : what;
            this.why = c > 0 ? what.substring(c + 2) : "";
            this.res = host ? resIndex(fact, min) : -1;
        }

        /** The row of REQ_FACTS for a host row: "fact>=min" when the table has it, else the bare fact, else -1 */
        private static int resIndex(String fact, String min) {
            List<String> facts = java.util.Arrays.asList(REQ_FACTS);
            int i = facts.indexOf(fact + ">=" + min);
            return i >= 0 ? i : facts.indexOf(fact);
        }

        /** "title: why" in English: the resource's English when the row has one (the same text as HOST's), else what it was built with */
        String english() { return res < 0 ? title + (why.isEmpty() ? "" : ": " + why) : Tr.english(REQ_KEYS[res]); }

        /** "title: why" in the user's language; English for a row without a resource */
        String shown() { return res < 0 ? title + (why.isEmpty() ? "" : ": " + why) : Tr.s(REQ_KEYS[res]); }

        String shownTitle() { String w = shown(); int c = w.indexOf(": "); return c > 0 ? w.substring(0, c) : w; }

        String shownWhy() { String w = shown(); int c = w.indexOf(": "); return c > 0 ? w.substring(c + 2) : ""; }
    }

    /**
     * One result. title and message are English: the report (toString), the log line (summary) and anything pasted into an issue. A screen shows
     * {@link #titleText} and {@link #messageText}, the user's language (docs/i18n.md: the readiness area owns these texts; another area's screen passes them as
     * arguments of its own sentences and never reads a readiness_ key).
     */
    static final class Check {
        final String title, message, fix, fact;
        final Status status;
        /** The screen's title and message in the user's language; null: the same as title / message */
        private final String titleText, messageText;
        /** The texts the screen renders when it shows them (null: none), so a check renders in the language of the moment it is shown, and only what is shown:
         *  a Msg check's title and message, a requirement row's title (Req.shownTitle) */
        private final Msg titleMsg, messageMsg;
        private final Req req;
        /** A requirement row's screen message (docs/i18n.md "Readiness requirements"): its key (an index of REQ_MSG_KEYS, -1 for any other check) and its
         *  arguments after the row's why, which reqMsg() puts first, in the language of the moment (Req.shownWhy), for the keys that take one */
        private final int reqKey;
        private final Object[] reqArgs;

        Check(String title, Status status, String message, String fix) { this(title, status, message, fix, null); }

        /** fact: the requirement's fact key (Req.fact) when the check is one, else a fixed key of what it is about ("error", "unchecked", "status"), so a caller can
         *  tell what failed without matching titles (titles are translated); null for the screen's own cards */
        Check(String title, Status status, String message, String fix, String fact) { this(title, null, status, message, null, fix, fact); }

        /** titleText, messageText: the screen's texts in the user's language (null: the English ones) */
        Check(String title, String titleText, Status status, String message, String messageText, String fix, String fact) {
            this(title, titleText, null, null, status, message, messageText, null, fix, fact);
        }

        /** A check whose title and message are texts (docs/i18n.md): title and message are their English (the report), titleText() and messageText() the user's language */
        Check(Msg title, Status status, Msg message, String fix) { this(title, status, message, fix, null); }

        Check(Msg title, Status status, Msg message, String fix, String fact) {
            this(title.english(), null, title, null, status, message.english(), null, message, fix, fact);
        }

        /**
         * A requirement row's check (docs/i18n.md "Readiness requirements"): its title is the row's (English; the screen's is r.shownTitle()), message the report's
         * English sentence as it always was; the screen's message is REQ_MSG_KEYS[key] with the row's why first (r.shownWhy(), none for a key that takes none),
         * then args
         */
        Check(Req r, Status status, String message, int key, Object[] args, String fix, String fact) {
            this(r.title, null, null, r, status, message, null, null, key, args, fix, fact);
        }

        private Check(String title, String titleText, Msg titleMsg, Req req, Status status, String message, String messageText, Msg messageMsg, String fix, String fact) {
            this(title, titleText, titleMsg, req, status, message, messageText, messageMsg, -1, null, fix, fact);
        }

        private Check(String title, String titleText, Msg titleMsg, Req req, Status status, String message, String messageText, Msg messageMsg, int reqKey, Object[] reqArgs,
                      String fix, String fact) {
            this.reqKey = reqKey;
            this.reqArgs = reqArgs == null ? null : reqArgs.clone();
            this.fact = fact;
            this.title = title;
            this.titleText = titleText;
            this.titleMsg = titleMsg;
            this.req = req;
            this.status = status;
            this.message = message;
            this.messageText = messageText;
            this.messageMsg = messageMsg;
            this.fix = fix;
        }

        /** The title on a screen, in the user's language */
        String titleText() { return titleMsg != null ? titleMsg.text() : req != null ? req.shownTitle() : titleText != null ? titleText : title; }

        /** The message on a screen, in the user's language */
        String messageText() {
            if (reqKey >= 0) return reqMsg().text();
            return messageMsg != null ? messageMsg.text() : messageText != null ? messageText : message;
        }

        /** A requirement row's screen message as a Msg, with the row's why in the language of the moment (English for a game's own row); null for another check */
        Msg reqMsg() {
            if (reqKey < 0) return null;
            if (!REQ_MSG_WHY[reqKey]) return new Msg(REQ_MSG_KEYS[reqKey], reqArgs);
            Object[] a = new Object[reqArgs.length + 1];
            a[0] = req.shownWhy();
            System.arraycopy(reqArgs, 0, a, 1, reqArgs.length);
            return new Msg(REQ_MSG_KEYS[reqKey], a);
        }

        @Override public String toString() { return "[" + status + "] " + title + ": " + message; }
    }

    /** What the host needs whatever the game: vka_dev.c stops without the first ones (Vulkan 1.3, the queue, timeline semaphores, dynamic rendering), and without
     *  placed memory maps, update-after-bind texture tables or a swapchain it runs but draws or shows nothing (its [RED] lines say so) */
    static final List<Req> HOST = Collections.unmodifiableList(List.of(
            new Req(true, "android.sdk", "31", "Android 12 or newer: the app is built for it", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            // Android 12 and 12L (API 31, 32) install but nobody has run a game on them yet (android/README.md "Android versions"): a warning until a phone run
            // confirms them. Below 31 the required row fails and this one is left out
            new Req(false, "android.sdk", "33", "Android 13 or newer: Android 12 and 12L are untested; the game may not start or may draw wrongly there", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            new Req(true, "vk.instance", "1.1", "Vulkan 1.1 loader or newer: Android 12's loader reports 1.1 and 13's 1.3; the replay's Vulkan 1.3 is the GPU driver's (the next row)", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            new Req(true, "vk.api", "1.3", "Vulkan 1.3 GPU driver: the graphics replay is written for Vulkan 1.3", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            new Req(true, "queue.graphicsCompute", "1", "A graphics and compute queue: everything is submitted to one such queue", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            new Req(true, "feature.timelineSemaphore", "1", "Timeline semaphores: the game's fences are built on them", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            new Req(true, "feature.dynamicRendering", "1", "Dynamic rendering: every render pass is recorded with it", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            new Req(true, "ext.VK_KHR_swapchain", "1", "Swapchain: without it no picture reaches the screen", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            new Req(true, "ext.VK_KHR_map_memory2", "1", "Memory map 2: placed memory maps need it", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            new Req(true, "ext.VK_EXT_map_memory_placed", "1", "Placed memory maps: the game's GPU memory is mapped at the game's own addresses; without them nothing is drawn", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            new Req(true, "feature.memoryMapPlaced", "1", "Placed memory maps: the game's GPU memory is mapped at the game's own addresses; without them nothing is drawn", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            new Req(true, "feature.descriptorBindingSampledImageUpdateAfterBind", "1", "Texture tables (update after bind): the game's texture pools live in them", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            new Req(true, "feature.descriptorBindingPartiallyBound", "1", "Texture tables (partially bound): the game's texture pools live in them", true),   // i18n-keep: the report's English; the screen reads REQ_KEYS
            new Req(true, "feature.runtimeDescriptorArray", "1", "Texture tables (runtime arrays): the game's texture pools live in them", true)));   // i18n-keep: the report's English; the screen reads REQ_KEYS

    /** A profile's req rows as build.gradle hands them over (Profile.reqs): "level|fact|min|what" rows, ';' between them; a malformed row is skipped */
    static List<Req> parseReqs(String table) {
        List<Req> out = new ArrayList<>();
        if (table == null) return out;
        for (String row : table.split(";")) {
            String[] c = row.split("\\|", -1);
            if (c.length < 4 || !(c[0].equals("required") || c[0].equals("degraded")) || c[1].isEmpty() || c[2].isEmpty()) continue;
            out.add(new Req(c[0].equals("required"), c[1].trim(), c[2].trim(), c[3].trim().isEmpty() ? c[1].trim() : c[3].trim()));
        }
        return out;
    }

    /** "key=value" lines (the first '=' splits) */
    static Map<String, String> facts(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String l : text.split("\n")) {
            int eq = l.indexOf('=');
            if (eq > 0) out.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
        }
        return out;
    }

    /** a compared with b: numbers, 0x hex, or dotted versions part by part (1.3 &lt; 1.3.204 &lt; 1.4); a value that is no number reads as 0 */
    static int compare(String a, String b) {
        String[] x = a.trim().split("\\."), y = b.trim().split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            double p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static double num(String s) {
        try {
            return s.startsWith("0x") || s.startsWith("0X") ? Long.parseLong(s.substring(2), 16) : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The fact "unchecked" (nobody answered the GPU check) is report data, English: its value says why. The screen does not show that value inside its translated
     * sentences: {@link #uncheckedReason} picks a text by the reason's code. The launcher writes two of the values itself, so their code is known: {@link #UNCHECKED_NO_HOST}
     * and {@link #uncheckedTimeout}'s; any other value is a detail of the host's loader (Native.load's English message, as written)
     */
    static final String UNCHECKED_NO_HOST = "this build's host cannot report the GPU's features";   // i18n-keep: a fact of the report; the screen reads readiness_unchecked_no_host
    private static final Pattern UNCHECKED_TIMEOUT = Pattern.compile("the GPU check did not answer in (\\d+) s");   // i18n-keep: a fact of the report, parsed

    /** The fact line "unchecked=…" of a GPU check that did not answer in time (GameActivity), English for the report */
    static String uncheckedTimeout(long seconds) { return "unchecked=the GPU check did not answer in " + seconds + " s"; }   // i18n-keep: a fact of the report; the screen reads readiness_unchecked_timeout

    /** The reason's code of an "unchecked" fact value: no_host, timeout, or other (a detail of the host's loader) */
    static String uncheckedCode(String value) {
        if (UNCHECKED_NO_HOST.equals(value)) return "no_host";
        return value != null && UNCHECKED_TIMEOUT.matcher(value).matches() ? "timeout" : "other";
    }

    /** The reason of an "unchecked" fact value as the screen shows it, chosen by its code: a Msg, or the value as written for a detail of the host's loader */
    static Object uncheckedReason(String value) {
        switch (uncheckedCode(value)) {
            case "no_host": return new Msg("readiness_unchecked_no_host");
            case "timeout": {
                Matcher m = UNCHECKED_TIMEOUT.matcher(value);
                if (m.matches()) return new Msg("readiness_unchecked_timeout", Long.parseLong(m.group(1)));
                return value;
            }
            default: return value;
        }
    }

    /** The facts this game is checked against: the host's Vulkan facts (null: this host cannot report them) and the launcher's */
    static Map<String, String> merge(String vulkan, double ramGib, int sdk) {
        Map<String, String> f = facts(vulkan);
        if (vulkan == null && !f.containsKey("unchecked")) f.put("unchecked", UNCHECKED_NO_HOST);
        f.put("ram_gib", String.format(Locale.ROOT, "%.1f", ramGib));
        f.put("android.sdk", String.valueOf(sdk));
        return f;
    }

    /** Checked: the host's needs, then the game's rows. A degraded row of a fact whose required row failed is left out (one message per missing thing) */
    static List<Check> evaluate(Map<String, String> facts, List<Req> game) {
        List<Check> out = new ArrayList<>();
        // error: the driver answered and failed (no Vulkan library, no instance, no GPU): the game cannot run either. unchecked: nobody answered (the check timed out,
        // or the host cannot report): nothing is known, so nothing is called missing; one warning says the GPU was not checked
        String err = facts.get("error"), unchecked = facts.get("unchecked");
        List<Req> all = new ArrayList<>(HOST);
        all.addAll(game);
        List<String> failed = new ArrayList<>(), seen = new ArrayList<>();
        for (Req r : all) {
            String have = facts.get(r.fact);
            boolean vulkan = !r.fact.equals("ram_gib") && !r.fact.startsWith("android.");
            if (have == null && vulkan && (err != null || unchecked != null)) {   // nothing to compare with: said once
                if (!seen.contains("error")) {
                    seen.add("error");
                    out.add(err != null ? new Check(new Msg("readiness_check_gpu_features"), Status.FAIL, new Msg("readiness_gpu_error", err), "driver", "error")
                            : new Check(new Msg("readiness_check_gpu_features"), Status.WARNING, new Msg("readiness_gpu_unchecked", uncheckedReason(unchecked)), null, "unchecked"));
                }
                continue;
            }
            if (!r.required && failed.contains(r.fact) || failed.contains("title " + r.title)) continue;   // i18n-keep: a marker, not a text
            String key = r.fact + " " + r.min + " " + r.title;
            if (seen.contains(key)) continue;   // the same requirement twice (the host's and a profile's)
            seen.add(key);
            boolean ok = have != null && compare(have, r.min) >= 0;
            // The report's message is the English sentence it always was (its why upper-cased mid-sentence: English only); the screen's is a whole-sentence resource
            // with the row's why after a colon, as written in the row's language (docs/i18n.md "Readiness requirements")
            String shown = have == null ? "not reported" : "reports " + have;   // i18n-keep: the report's English (the screen reads readiness_row_not_reported / _reports)
            Object shownMsg = have == null ? new Msg("readiness_row_not_reported") : new Msg("readiness_row_reports", have);
            if (ok) out.add(new Check(r, Status.PASS, "Present (" + label(r.fact) + " " + have + ", needs " + r.min + ").", REQ_MET,   // i18n-keep: the report's English
                    new Object[] {labelMsg(r.fact), have, r.min}, null, null));
            else {
                if (r.required) { failed.add(r.fact); failed.add("title " + r.title); }   // i18n-keep: a marker, not a text
                String msg = (r.required ? "This device lacks it; this game needs it. " : "Missing: this game runs without it, with less. ")   // i18n-keep: the report's English
                        + (r.why.isEmpty() ? "" : r.why.substring(0, 1).toUpperCase(Locale.ROOT) + r.why.substring(1) + ". ")   // English only: the report
                        + "(" + label(r.fact) + " " + shown + ", needs " + r.min + ")";   // i18n-keep: the report's English
                // A row without a why (a game's row may have none) reads the sentence without one
                int form = r.required ? (r.why.isEmpty() ? REQ_LACKS_BARE : REQ_LACKS) : (r.why.isEmpty() ? REQ_DEGRADED_BARE : REQ_DEGRADED);
                out.add(new Check(r, r.required ? Status.FAIL : Status.WARNING, msg, form, new Object[] {labelMsg(r.fact), shownMsg, r.min},
                        r.fact.equals("ram_gib") ? "memory" : "driver", r.fact));
            }
        }
        return out;
    }

    /**
     * How long ago a moment was ("5 minutes ago"), as a text: the screen shows text(), in the app's language, and the report english() (docs/i18n.md
     * "Numbers on screen"; Android's DateUtils would follow the system's language in both). Minutes, hours, days, then from a week on the day, yyyy-MM-dd
     */
    static Msg ago(long then, long now) {
        long d = Math.max(0, now - then), min = d / 60_000, hours = d / 3_600_000, days = d / 86_400_000;
        if (min < 1) return new Msg("readiness_ago_now");
        if (hours < 1) return Msg.plural("readiness_ago_minutes", (int) min, min);
        if (days < 1) return Msg.plural("readiness_ago_hours", (int) hours, hours);
        if (days < 7) return Msg.plural("readiness_ago_days", (int) days, days);
        return new Msg("readiness_ago_date", new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(then)));
    }

    /** The game's status on Android (its readiness.tsv status row): a game in bring-up gets a warning of its own, whatever the phone has */
    static Check status(String status) {
        if (status == null || status.isEmpty() || status.equals("playable")) return null;
        if (status.equals("bring-up")) return new Check(new Msg("readiness_check_status"), Status.WARNING, new Msg("readiness_status_bring_up"), null, "status");
        Msg title = new Msg("readiness_check_status");
        return new Check(title.english(), null, title, null, Status.INFO, status, null, null, null, "status");   // the message: the row's own word (machine data), as written
    }

    /** Of a game's environment (KEY=VALUE, in start's order), the lines that pick its GPU driver: what the readiness facts must be read on */
    static String[] driverEnv(List<String> env) {
        List<String> out = new ArrayList<>();
        for (String kv : env) if (kv.startsWith("DM_VK_DRIVER=") || kv.startsWith("DM_VK_HOOKDIR=")) out.add(kv);
        return out.toArray(new String[0]);
    }

    /** A requirement row's screen messages (Check.reqMsg): present; missing and required; missing and degraded; each with the row's why as its first argument
     *  (REQ_MSG_WHY) or, for a row that has none, without it. The other arguments: the fact's name (labelMsg), what the phone reports, the minimum */
    static final String[] REQ_MSG_KEYS = {"readiness_row_met", "readiness_row_lacks", "readiness_row_lacks_bare", "readiness_row_degraded", "readiness_row_degraded_bare"};
    private static final boolean[] REQ_MSG_WHY = {false, true, false, true, false};
    private static final int REQ_MET = 0, REQ_LACKS = 1, REQ_LACKS_BARE = 2, REQ_DEGRADED = 3, REQ_DEGRADED_BARE = 4;

    /** A fact's name inside a requirement row's screen message: a text for the two the launcher names in words, else the fact's key and a colon (machine data) */
    private static Object labelMsg(String fact) {
        if (fact.equals("ram_gib")) return new Msg("readiness_row_fact_memory");
        if (fact.equals("android.sdk")) return new Msg("readiness_row_fact_android_sdk");
        return fact + ":";
    }

    /** The same in the report's English */
    private static String label(String fact) {
        if (fact.equals("ram_gib")) return "memory in GiB:";   // i18n-keep: a requirement row's message stays English in phase 1 (docs/i18n.md "Readiness requirements")
        if (fact.equals("android.sdk")) return "Android SDK";   // i18n-keep: a requirement row's message stays English in phase 1 (docs/i18n.md "Readiness requirements")
        return fact + ":";
    }

    static boolean any(List<Check> l, Status s) { for (Check c : l) if (c.status == s) return true; return false; }

    static List<Check> of(List<Check> l, Status s) {
        List<Check> out = new ArrayList<>();
        for (Check c : l) if (c.status == s) out.add(c);
        return out;
    }

    /** The GPU and driver in one line, in English (the report and the log line): {@link #gpuMsg}'s English */
    static String gpu(Map<String, String> f) { return gpuMsg(f).english(); }

    /** The GPU and driver in one line: name, driver name and info, driver ID and version, and a custom driver's name; the screen shows its text(), the report and
     *  the log its english() ({@link #gpu}). The facts' values are the driver's own words, as written */
    static Msg gpuMsg(Map<String, String> f) {
        if (!f.containsKey("gpu.name")) {
            // error: the driver's own words, as written; unchecked: the reason by its code (uncheckedReason)
            Object why = f.containsKey("error") ? f.get("error") : f.containsKey("unchecked") ? uncheckedReason(f.get("unchecked")) : null;
            return why != null ? new Msg("readiness_gpu_not_reported_why", why) : new Msg("readiness_gpu_not_reported");
        }
        String name = f.get("gpu.name"), api = f.getOrDefault("vk.api", "?"), driver = f.getOrDefault("driver.name", "?"), info = f.getOrDefault("driver.info", "");
        String id = f.getOrDefault("driver.id", "?"), version = f.getOrDefault("driver.version", "?"), custom = f.getOrDefault("driver.custom", "");
        if (custom.isEmpty())
            return info.isEmpty() ? new Msg("readiness_gpu_system", name, api, driver, id, version) : new Msg("readiness_gpu_system_info", name, api, driver, info, id, version);
        return info.isEmpty() ? new Msg("readiness_gpu_custom", name, api, driver, id, version, custom)
                : new Msg("readiness_gpu_custom_info", name, api, driver, info, id, version, custom);
    }

    /** The log's one line ("[readiness] ..."): every player's log says what the device has and what the check decided */
    static String summary(String titleId, Map<String, String> f, List<Check> l) {
        StringBuilder s = new StringBuilder("[readiness] ").append(titleId).append(": ");   // i18n-keep: the log line
        s.append(any(l, Status.FAIL) ? "FAIL" : any(l, Status.WARNING) ? "WARN" : "PASS");
        s.append("; ").append(gpu(f)).append("; RAM ").append(f.get("ram_gib")).append(" GiB, Android SDK ").append(f.get("android.sdk"));   // i18n-keep: the log line
        for (Status st : new Status[] {Status.FAIL, Status.WARNING}) {
            List<Check> m = of(l, st);
            if (m.isEmpty()) continue;
            s.append("; ").append(st == Status.FAIL ? "missing (required): " : "missing (degraded): ");   // i18n-keep: the log line
            for (int i = 0; i < m.size(); i++) s.append(i == 0 ? "" : ", ").append(m.get(i).title);
        }
        return s.toString();
    }

    /** The report a player copies or shares: the device, the GPU and driver, the checks, then every fact */
    static String report(String header, Map<String, String> f, List<Check> l) {
        StringBuilder s = new StringBuilder(header).append('\n');
        s.append("GPU: ").append(gpu(f)).append('\n');   // i18n-keep: the report
        s.append("RAM: ").append(f.get("ram_gib")).append(" GiB; Android SDK ").append(f.get("android.sdk")).append('\n');   // i18n-keep: the report
        for (Check c : l) s.append(c).append('\n');
        s.append("-- facts --\n");
        for (Map.Entry<String, String> e : f.entrySet()) s.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        return s.toString();
    }
}
