package dev.nxnative.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A game this APK runs: its profile (dm/games/&lt;title ID&gt;) was compiled into a host of its own, libnxhost_&lt;title ID&gt;.so (build.gradle nxGames,
 * src/main/cpp/CMakeLists.txt), and what the launcher needs of it comes from build.gradle as BuildConfig.NX_PROFILES: one line per game, tab-separated: title ID,
 * name, the shader count Prepare translates, the shader inputs digest, the builds (game.tsv build rows, "version module=build ID …", ';' between builds), and, when the APK has them, the readiness rows (the profile's readiness.tsv: "level|fact|min|what", ';' between rows: Readiness.parseReqs), its status row (playable or bring-up; dm/games/README.md "Readiness rows"), the shader delta rows (vk_delta.tsv's "from" rows: "version=count" or, for this version's library of older profile inputs, "version@inputs=count", ',' between them), its "stamp" rows ("version:old SHA-256:new SHA-256", ';' between them), what Game resolution sets (resolutionEnv), the RomFS checks (romfsChecks), the GPU timestamp mode (gpuTs), the default Game resolution (window), the phone's split times (split: a whole split, and a shader update's) and the Plants and effects levels (detail).
 * A game without a profile here cannot run in this APK (its host has no profile for it): the launcher says so instead of starting it.
 * No Android classes: the JVM test (android/app/src/jvmtest) runs it on the Mac.
 */
final class Profile {
    final String titleId, name, shaderInputs, builds, reqs, status;
    final int shaderTotal;
    String delta = "";   // the shader delta manifest's "from" rows: "version=shaders to translate", ',' between them (ShaderLibs.toTranslate)
    String stamp = "";   // its "stamp" rows: "version:old SHA-256:new SHA-256", ';' between them (ShaderLibs.restamp)
    String resolution = "";   // what Game resolution sets (build.gradle resolutionOf): "window", "scale:<choice>=<scale>/<console|handheld>,…" or "" (nothing: not offered)
    String gpuTs = "submission";   // game.tsv gpu_timestamps (build.gradle gpuTsOf): "segment" or "submission", what the host does without DM_NVN_GPU_TS (Game settings › GPU timing)
    String window = "";   // game.tsv android_window (build.gradle windowOf): the default Game resolution when the player chose none (Prefs.gameWindow), "" for the main screen's
    String resChoices = "";   // game.tsv res_choice rows (build.gradle resChoicesOf): "<DM_RES_LEVEL value>=<label>", ';' between them; "" = no 3D resolution setting
    String split = "";   // game.tsv android_split_minutes (build.gradle splitOf): "lo-hi" or "lo-hi,ulo-uhi", the phone's shader split of this game and of a shader update in minutes, "" when not measured (split(), updateSplit())
    String detail = "";   // game.tsv detail_level rows (build.gradle detailOf): "<id>|<shown|hidden>|<label>", ';' between them; "" = no Plants and effects setting
    String romfsChecks = "";   // game.tsv romfs_check rows (build.gradle romfsChecksOf): "<path>|<line>|<expected or main>", ';' between them (RomfsCheck)

    /** The phone's shader split of this game as {lo, hi} minutes (ShaderLibs.minutes), null when the profile has no android_split_minutes row */
    int[] split() { return range(split.split(",")[0]); }

    /**
     * The phone's split of a shader update as {lo, hi} minutes: it reads only the dump's files that hold the shaders the update translates (shader_split.py
     * DM_SPLIT_DELTA); null when the profile has no such value (the estimate then takes the whole split, split())
     */
    int[] updateSplit() {
        String[] r = split.split(",");
        return r.length > 1 ? range(r[1]) : null;
    }

    private static int[] range(String s) {
        String[] c = s.split("-");
        if (c.length != 2) return null;
        try {
            int lo = Integer.parseInt(c[0].trim()), hi = Integer.parseInt(c[1].trim());
            return lo > 0 && hi >= lo ? new int[] {lo, hi} : null;
        } catch (NumberFormatException e) {
            return null;   // a malformed row is no measurement
        }
    }

    /** Whether Game resolution does something for this game (its profile has window_fn or graphics_scale rows) */
    boolean hasResolution() { return resolution.equals("window") || resolution.startsWith("scale:"); }

    /**
     * The host's environment lines for a Game resolution choice (Prefs.WINDOWS: "1920x1080", "original", "1280x720"): DM_WINDOW_SIZE=&lt;choice&gt; for a game with
     * window_fn rows; DM_GRAPHICS_SCALE and DM_OPMODE from the choice's graphics_scale row for a game that sizes its scenes by the graphics scale (none for a choice
     * without a row: the game's own); none for a game with neither.
     */
    List<String> resolutionEnv(String choice) {
        List<String> env = new ArrayList<>();
        if (resolution.equals("window")) env.add("DM_WINDOW_SIZE=" + choice);
        else if (resolution.startsWith("scale:")) {
            for (String r : resolution.substring(6).split(",")) {
                int eq = r.indexOf('='), sl = r.indexOf('/');
                if (eq <= 0 || sl < eq || !r.substring(0, eq).equals(choice)) continue;
                env.add("DM_GRAPHICS_SCALE=" + r.substring(eq + 1, sl));
                env.add("DM_OPMODE=" + (r.substring(sl + 1).equals("handheld") ? 0 : 1));
                break;
            }
        }
        return env;
    }

    /** Whether Game settings › 3D resolution is offered (the profile's drs_level, with res_choice rows: loader.c drs_level_apply) */
    boolean hasResLevel() { return !resChoices.isEmpty(); }

    /** The 3D resolution choices, in the profile's order: [DM_RES_LEVEL value, label] each; a malformed entry is skipped */
    List<String[]> resLevels() {
        List<String[]> out = new ArrayList<>();
        if (resChoices.isEmpty()) return out;
        for (String c : resChoices.split(";")) {
            int eq = c.indexOf('=');
            if (eq > 0 && eq < c.length() - 1) out.add(new String[] {c.substring(0, eq), c.substring(eq + 1)});
        }
        return out;
    }

    /** The label of a stored 3D resolution value (Prefs.resLevel): "Dynamic (the game's own)" for null, the choice's label, or null for a value this profile no longer offers */
    String resLevelLabel(String value) {
        if (value == null) return Tr.s("settings_profile_res_level_dynamic");
        for (String[] c : resLevels()) if (c[0].equals(value)) return c[1];
        return null;
    }

    /** The host's environment line for a stored 3D resolution value: DM_RES_LEVEL=<value> for one of the profile's choices, none for null (the game's own) or a value it no longer offers */
    List<String> resLevelEnv(String value) {
        List<String> env = new ArrayList<>();
        if (value != null && resLevelLabel(value) != null) env.add("DM_RES_LEVEL=" + value);
        return env;
    }

    /**
     * Plants and effects: the levels Game settings offers, in the profile's order, as [id, label]: its shown detail_level rows (a hidden one, a candidate, is
     * chosen only by a DM_DETAIL line). The first is the default, as the game draws it. A malformed entry is skipped
     */
    List<String[]> detailLevels() {
        List<String[]> out = new ArrayList<>();
        if (detail.isEmpty()) return out;
        for (String c : detail.split(";")) {
            String[] f = c.split("\\|", -1);
            if (f.length == 3 && f[0].matches("[a-z0-9_]{1,31}") && f[1].equals("shown") && !f[2].trim().isEmpty()) out.add(new String[] {f[0], f[2].trim()});
        }
        return out;
    }

    /** Whether Game settings › Plants and effects is offered: the profile has a default level and at least one other to choose */
    boolean hasDetail() { return detailLevels().size() >= 2; }

    /**
     * The label of a stored level (Prefs.detailLevel): the default's for null, a shown level's, or null for a value this profile no longer offers. The levels
     * the texts know (full, reduced, lowest) are in the player's language; another game's are its profile's label
     */
    String detailLabel(String value) {
        List<String[]> ls = detailLevels();
        if (ls.isEmpty()) return null;
        for (int i = 0; i < ls.size(); i++) if (value == null ? i == 0 : ls.get(i)[0].equals(value)) return detailText(ls.get(i)[0], ls.get(i)[1]);
        return null;
    }

    private static String detailText(String id, String label) {
        switch (id) {
            case "full": return Tr.s("settings_game_detail_full");
            case "reduced": return Tr.s("settings_game_detail_reduced");
            case "lowest": return Tr.s("settings_game_detail_lowest");
            default: return label;
        }
    }

    /**
     * The host's environment line for a stored level: DM_DETAIL=&lt;id&gt; for a shown level other than the first; none for null, the first level (as the game draws
     * it: Full sends nothing at all) or a value the profile no longer offers
     */
    List<String> detailEnv(String value) {
        List<String> env = new ArrayList<>();
        List<String[]> ls = detailLevels();
        for (int i = 1; i < ls.size(); i++) if (ls.get(i)[0].equals(value)) env.add("DM_DETAIL=" + value);
        return env;
    }

    /**
     * The folder the host builds the Plants and effects overlay in: beside the game's temporary storage, "&lt;DM_TEMP&gt;_detail" (dm/host/hle_fs.c dm_detail_start;
     * never inside DM_TEMP, which is the game's own temp: mount)
     */
    static java.io.File detailDir(java.io.File temp) { return new java.io.File(temp.getPath() + "_detail"); }

    /** Whether the host gets a level other than Full: the last DM_DETAIL line of the start's environment (env.txt's come later and win) is not empty */
    static boolean detailOn(List<String> env) {
        String v = "";
        for (String kv : env) if (kv.startsWith("DM_DETAIL=")) v = kv.substring(10).trim();
        return !v.isEmpty();
    }

    /**
     * At a start with Full (no DM_DETAIL, or an empty one): the overlay an earlier start built is removed, so no file of an earlier level stays on the phone. Here, in
     * the launcher, so that the host sends and logs nothing for Full. Only at a start: the game this start replaces has ended, and no running game reads it.
     * Returns whether the folder is gone (or never was)
     */
    static boolean dropDetail(java.io.File temp, List<String> env) {
        if (detailOn(env)) return true;
        return deleteTree(detailDir(temp));
    }

    private static boolean deleteTree(java.io.File f) {
        if (!f.exists() && !isLink(f)) return true;
        java.io.File[] in = isLink(f) ? null : f.listFiles();
        boolean ok = true;
        if (in != null) for (java.io.File c : in) ok &= deleteTree(c);
        return f.delete() && ok;
    }

    private static boolean isLink(java.io.File f) { return java.nio.file.Files.isSymbolicLink(f.toPath()); }

    /** Whether any game of this APK has Game resolution (Settings shows its default only then) */
    static boolean anyResolution() { for (Profile p : all()) if (p.hasResolution()) return true; return false; }

    Profile(String titleId, String name, int shaderTotal, String shaderInputs, String builds, String reqs, String status) {
        this.reqs = reqs;
        this.status = status;
        this.titleId = titleId;
        this.name = name;
        this.shaderTotal = shaderTotal;
        this.shaderInputs = shaderInputs;
        this.builds = builds;
    }

    /** The profiles of a BuildConfig.NX_PROFILES table; a malformed line is skipped */
    static List<Profile> parse(String table) {
        List<Profile> out = new ArrayList<>();
        for (String line : table.split("\n")) {
            String[] c = line.split("\t", -1);
            if (c.length < 5 || !c[0].matches("[0-9A-F]{16}")) continue;
            int total;
            try { total = Integer.parseInt(c[2]); } catch (NumberFormatException e) { total = 0; }
            Profile p = new Profile(c[0], c[1].isEmpty() ? c[0] : c[1], total, c[3], c[4], c.length > 5 ? c[5] : "", c.length > 6 ? c[6] : "");
            p.delta = c.length > 7 ? c[7] : "";
            p.stamp = c.length > 8 ? c[8] : "";
            p.resolution = c.length > 9 ? c[9] : "";
            p.romfsChecks = c.length > 10 ? c[10] : "";
            if (c.length > 11 && !c[11].isEmpty()) p.gpuTs = c[11];
            p.window = c.length > 12 ? c[12] : "";
            p.resChoices = c.length > 13 ? c[13] : "";
            p.split = c.length > 14 ? c[14] : "";
            p.detail = c.length > 15 ? c[15] : "";
            out.add(p);
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Profile> all;

    /** The games this APK runs, in build.gradle's order (the first is the default) */
    static synchronized List<Profile> all() {
        if (all == null) all = parse(BuildConfig.NX_PROFILES);
        return all;
    }

    /** This APK's profile of a title ID, null when it has none */
    static Profile of(String titleId) {
        for (Profile p : all()) if (p.titleId.equals(titleId)) return p;
        return null;
    }

    /** The default game (the first): the graphics self-test runs its host */
    static Profile first() { return all().get(0); }

    /** Why this APK cannot run a title, in a sentence (null when it has its profile). English (logs, and the callers of other areas until they take
     *  {@link #missingMsg}); a screen of the settings area takes missingMsg */
    static String missing(String titleId, String what) {
        Msg m = missingMsg(titleId, what);
        return m == null ? null : m.english();
    }

    /** {@link #missing} as a Msg (null when this APK has the title's profile); what: a name, the caller's own Msg ("This dump"), or null for the title ID alone */
    static Msg missingMsg(String titleId, Object what) {
        if (of(titleId) != null) return null;
        // The title IDs this APK runs, so a user with another game sees it is not supported (rather than a broken dump); IDs only, no game names
        List<String> ids = new ArrayList<>();
        for (Profile p : all()) ids.add(p.titleId);
        String runs = String.join(", ", ids);
        return what == null ? new Msg("settings_profile_missing", titleId, runs) : new Msg("settings_profile_missing_named", what, titleId, runs);
    }

    /** A version of a dump in words: 0 is the base game itself (no update installed; Mario Kart World 1.8.0's supported build), any other an update. English,
     *  for a log line, job.json or an English sentence; a screen sentence of another area takes {@link #versionMsg} as an argument (docs/i18n.md) */
    static String versionName(long version) { return versionMsg(version).english(); }

    /** versionName as a Msg: the settings area's text that another area's sentence (BuildCheck, DumpLayout, PackService, Ondevice) passes as an argument, so it
     *  renders in the sentence's language, English in a report and the user's on screen */
    static Msg versionMsg(long version) { return version == 0 ? new Msg("settings_profile_base_game") : new Msg("settings_profile_update", version); }

    /** Which versions of this game the APK supports, as a sentence for a dump it refuses (DumpCheck): its builds, by display version when game.tsv names one.
     *  English; a screen takes {@link #supportedMsg} */
    String supported() { return supportedMsg().english(); }

    /** supported() as a Msg */
    Msg supportedMsg() {
        Msg all = null;
        for (Long v : buildMap().keySet()) all = all == null ? buildMsg(v) : new Msg("common_or", all, buildMsg(v));
        return new Msg("settings_profile_supported", name, titleId, all == null ? "" : all);
    }

    /** One of the profile's builds in words: "1.8.0 (the base game, version 0, main d00e4f399e411d06…)", "update 458752 (main e25c90c1c1366388…)". English (a log
     *  line); a screen takes {@link #buildMsg} */
    String buildName(long v) { return buildMsg(v).english(); }

    /** buildName as a Msg; the module name and build ID ("main d00e…") stay as they are in every language */
    Msg buildMsg(long v) {
        String d = display(v), main = buildMap().getOrDefault(v, Map.of()).get("main");
        String ids = main == null ? "" : "main " + main.substring(0, Math.min(16, main.length())) + "…";   // i18n-keep: the module name and its build ID
        if (d != null) {
            Msg which = v == 0 ? new Msg("settings_profile_base_game_v0") : new Msg("settings_profile_update", v);
            return ids.isEmpty() ? new Msg("settings_profile_build_shown", d, which) : new Msg("settings_profile_build_shown_ids", d, which, ids);
        }
        return ids.isEmpty() ? versionMsg(v) : new Msg("settings_profile_build_ids", versionMsg(v), ids);
    }

    /** The display version game.tsv's build row gives a build ("1.8.0"), null when it gives none */
    String display(long v) {
        for (String b : builds.split(";")) {
            String[] c = b.trim().split(" ");
            if (c.length > 1 && c[0].equals(String.valueOf(v))) for (String t : c) if (t.startsWith("@")) return t.substring(1);
        }
        return null;
    }

    /** The profile's builds: version → module → build ID (the first 16 bytes, lower case, as game.tsv writes them) */
    Map<Long, Map<String, String>> buildMap() {
        Map<Long, Map<String, String>> out = new LinkedHashMap<>();
        for (String b : builds.split(";")) {
            String[] c = b.trim().split(" ");
            if (c.length < 2 || !c[0].matches("\\d+")) continue;
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 1; i < c.length; i++) {
                int eq = c[i].indexOf('=');
                if (eq > 0) m.put(c[i].substring(0, eq), c[i].substring(eq + 1).toLowerCase(Locale.ROOT));
            }
            out.put(Long.parseLong(c[0]), m);
        }
        return out;
    }
}
