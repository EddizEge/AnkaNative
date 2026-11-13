package dev.nxnative.android;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * What an emulator frontend (ES-DE, Daijisho, iiSU, Beacon, Pegasus…) asks FrontendLaunch for, as plain Java (no Android, checked on the JVM by FrontendJvmTest):
 * the game's path from a file:// URI or from a content:// URI that carries a /storage path, the phone's aliases of its shared storage made one, the paths another
 * app may name (shared storage only), the card a request names, and a title ID. FRONTENDS.md says what each frontend sends and what the app does with it.
 */
final class Frontend {
    private Frontend() {}

    /** The shared storage's other names (the same files by every one of them) → the name the host's paths use */
    private static final String PRIMARY = "/storage/emulated/0";
    private static final String[] ALIASES = {"/sdcard", "/mnt/sdcard", "/storage/self/primary", "/mnt/user/0/primary", "/storage/emulated/legacy"};

    /** %XX escapes (UTF-8) decoded; '+' stays (a URI path, not a form). A bad escape is kept as it is */
    static String decode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() &&hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                b.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 3;
            } else {
                byte[] u = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (Character.isHighSurrogate(c) && i + 1 < s.length()) { u = s.substring(i, i + 2).getBytes(StandardCharsets.UTF_8); i++; }
                b.write(u, 0, u.length);
                i++;
            }
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** file:///storage/… (or file:/storage/…) → its path, decoded; null for any other URI or a relative path */
    static String fileUri(String uri) {
        if (uri == null || !uri.regionMatches(true, 0, "file:", 0, 5)) return null;
        String rest = uri.substring(5);
        if (rest.startsWith("//")) {   // file://<authority>/path: only an empty authority or localhost names this phone
            int slash = rest.indexOf('/', 2);
            if (slash < 0) return null;
            String host = rest.substring(2, slash);
            if (!host.isEmpty() && !host.equalsIgnoreCase("localhost")) return null;
            rest = rest.substring(slash);
        }
        int q = indexOfAny(rest, "?#");
        if (q >= 0) rest = rest.substring(0, q);
        String p = decode(rest);
        return p.startsWith("/") ? p : null;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) if (chars.indexOf(s.charAt(i)) >= 0) return i;
        return -1;
    }

    /** A content:// URI's decoded path that carries the file's own path (a FileProvider "root" path: …/root/storage/emulated/0/Games/x.nup) → that path; else null */
    static String embeddedPath(String uriPath) {
        if (uriPath == null) return null;
        for (String root : new String[] {"/storage/", "/sdcard/", "/mnt/media_rw/"}) {
            int at = uriPath.indexOf(root);
            if (at >= 0) return uriPath.substring(at);
        }
        return null;
    }

    /**
     * A SAF document ID's path within its volume (the part after "primary:" or "<volume ID>:") → the file's path under the volume's root. Most providers give
     * it relative to the volume ("Switch2/x.nup"); some (the HyperOS picker, issue #19) give the file's whole path ("/storage/emulated/0/Switch2/x.nup" or
     * "storage/emulated/0/…"), which joined to the root named the storage twice: a path that is already under the root, or under another name of the shared
     * storage, is the file's own path
     */
    static String volumePath(String root, String rel) {
        if (rel == null || rel.isEmpty()) return root;
        String abs = "/" + rel.replaceFirst("^/+", "");
        if (abs.equals(root) || abs.startsWith(root + "/")) return abs;
        String n = normal(abs);
        if (rel.startsWith("/") && normal(root).equals(PRIMARY) && n.startsWith(PRIMARY + "/")) return abs;   // under another name of the shared storage (/sdcard/…)
        return root + abs;
    }

    /** One name per file: the shared storage's aliases become /storage/emulated/0, "//", "/./" and a trailing '/' go */
    static String normal(String p) {
        if (p == null || p.isEmpty()) return p;
        String s = p.replaceAll("/+", "/").replace("/./", "/");
        if (s.endsWith("/.")) s = s.substring(0, s.length() - 2);
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        for (String a : ALIASES) if (s.equals(a) || s.startsWith(a + "/")) return PRIMARY + s.substring(a.length());
        return s;
    }

    /** Whether two paths name the same game (by normal; the cards keep the path the user picked, the frontend may send another alias of it) */
    static boolean same(String a, String b) { return a != null && b != null && normal(a).equals(normal(b)); }

    /** The first of the cards' game paths that names path, or -1 */
    static int card(List<String> gameDirs, String path) {
        if (path == null) return -1;
        for (int i = 0; i < gameDirs.size(); i++) if (same(gameDirs.get(i), path)) return i;
        return -1;
    }

    /**
     * A path another app sent, as FrontendLaunch may act on it: its normal name when it is an absolute path on shared storage (/storage/…, or an alias of it,
     * or /mnt/media_rw/…) with no "." or ".." step and no control character; else null. The app's own folders (/data/…) and Android/data and Android/obb are never
     * named by a frontend: a SAF id or a provider path with ".." in it ("primary:../../data/…") cannot walk out of shared storage
     */
    static String sharedPath(String p) {
        if (p == null || !p.startsWith("/")) return null;
        for (int i = 0; i < p.length(); i++) if (p.charAt(i) < 0x20 || p.charAt(i) == 0x7f) return null;
        for (String step : p.split("/")) if (step.equals("..")) return null;
        String n = normal(p);
        if (!(n.startsWith("/storage/") || n.startsWith("/mnt/media_rw/"))) return null;
        String[] s = n.split("/");   // "", "storage", <volume>[, <user>], …
        int top = n.equals(PRIMARY) || n.startsWith(PRIMARY + "/") || n.startsWith("/mnt/media_rw/") ? 4 : 3;   // the first folder under the volume's root
        if (s.length <= top) return null;   // a volume's root, or /storage itself, is no game
        if (s[top].equalsIgnoreCase("Android") && s.length > top + 1 && (s[top + 1].equalsIgnoreCase("data") || s[top + 1].equalsIgnoreCase("obb"))) return null;
        return n;
    }

    /**
     * The value of the extra "game": an absolute path, or a file:// URI as a string (some frontends only fill extras with their URI placeholder); a content://
     * URI in it is the caller's to parse (FrontendLaunch, as the intent's data). null: neither
     */
    static String extraPath(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.startsWith("/")) return t;
        return fileUri(t);
    }

    /** Whether the extra "game" holds a content:// URI (read as the intent's data) */
    static boolean contentUri(String v) { return v != null && v.trim().regionMatches(true, 0, "content://", 0, 10); }

    /**
     * Frontends' own FileProviders whose URIs name a folder of the shared storage by a root name of their own, not by its path: authority → { root name, the
     * folder }. Pegasus's {file.uri} is content://org.pegasus_frontend.android.files/ext/<path under /storage/emulated/0> (its fileprovider_paths.xml: external-path
     * "ext", root-path "root"; "root" carries the path and is read by embeddedPath)
     */
    private static final String[][] PROVIDER_ROOTS = {{"org.pegasus_frontend.android.files", "ext", PRIMARY}};

    /** A content:// URI's authority and decoded path → the file's path, by a known frontend's provider root (PROVIDER_ROOTS) or a path it carries (embeddedPath) */
    static String providerPath(String authority, String uriPath) {
        if (uriPath == null) return null;
        for (String[] r : PROVIDER_ROOTS) {
            String pre = "/" + r[1] + "/";
            if (r[0].equals(authority) && uriPath.startsWith(pre)) return r[2] + "/" + uriPath.substring(pre.length());
        }
        return embeddedPath(uriPath);
    }

    /**
     * Which card a frontend's request names, as FrontendLaunch decides it: by the title ID when one was sent (the first card of that title), else by the path (the
     * card that holds the same file, by any alias). -1: no card; a path that is no card is a new game, which only Home adds, after the user says so
     */
    static int target(List<String> gameDirs, List<String> titleIds, String titleId, String path) {
        if (titleId != null) {
            for (int i = 0; i < titleIds.size(); i++) if (titleId.equalsIgnoreCase(titleIds.get(i))) return i;
            return -1;
        }
        return card(gameDirs, path);
    }

    /** A title ID as the cards keep it (16 hex digits, upper case), or null */
    static String titleId(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        return t.matches("[0-9A-Fa-f]{16}") ? t.toUpperCase(Locale.ROOT) : null;
    }
}
