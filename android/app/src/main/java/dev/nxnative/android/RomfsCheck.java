package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Whether a dump's RomFS is the data of the version its code is (game.tsv romfs_check rows, Profile.romfsChecks): a RomFS file line that differs between versions,
 * where romfs_marker cannot tell (a file every version has). Donkey Kong Bananza: line 4 of System/RegionLangMask.txt is the build ID of the main its RomFS was made
 * for, so an update folder that holds the base's data (or an unpatched update RomFS) is found before the game boots into the wrong bundle and crashes.
 * Read by Prepare (DumpLayout.find) and by the card and Play (BuildCheck.build), for folders and .nup files in any layout. No Android classes: GameListJvmTest runs it.
 */
final class RomfsCheck {
    private RomfsCheck() {}

    /**
     * null when every check of the profile holds for this RomFS, else a sentence the user can act on (English: DumpLayout's and BuildCheck's callers log it).
     * romfs: a RomFS folder, or a .nup's &lt;file&gt;.nup:&lt;selector&gt;/romfs (NUP.md naming); mainId: the build ID of the version's main (hex, 32 or 64 digits;
     * null: "main" checks are skipped); version: the version the code is (0: the base game)
     */
    static String check(Profile prof, String romfs, String mainId, long version) {
        Msg[] p = problem(prof, romfs, mainId, version);
        return p == null ? null : p[0].english();
    }

    /** {@link #check} as a Msg (the screen's text(), a log's english()), null when every check holds */
    static Msg checkMsg(Profile prof, String romfs, String mainId, long version) {
        Msg[] p = problem(prof, romfs, mainId, version);
        return p == null ? null : p[0];
    }

    /** null when every check holds, else {the sentence, what was found against what was expected (its part in parentheses: BuildCheck's detail)} */
    static Msg[] problem(Profile prof, String romfs, String mainId, long version) {
        if (prof == null || prof.romfsChecks.isEmpty() || romfs == null) return null;
        for (String row : prof.romfsChecks.split(";")) {
            String[] c = row.split("\\|", -1);
            if (c.length != 3) continue;
            int line;
            try { line = Integer.parseInt(c[1]); } catch (NumberFormatException e) { continue; }
            boolean main = c[2].equals("main");
            if (main && mainId == null) continue;
            String text;
            try {
                text = line(romfs, c[0], line);
            } catch (IOException e) {
                text = null;
            }
            boolean ok = text != null && (main ? sameBuild(text, mainId) : text.equals(c[2]));
            if (ok) continue;
            boolean nup = Nup.parse(romfs) != null;
            Msg found = text == null ? new Msg("packs_romfs_found_missing") : new Msg("packs_romfs_found_line", line, text.length() > 48 ? text.substring(0, 48) + "…" : text);
            Msg want = main ? new Msg("packs_romfs_want_main", mainId.substring(0, Math.min(16, mainId.length()))) : new Msg("packs_romfs_want_text", c[2]);
            Msg detail = text == null ? new Msg("packs_romfs_detail_line", c[0], found, want, line) : new Msg("packs_romfs_detail", c[0], found, want);
            Msg sentence = version == 0 ? (nup ? new Msg("packs_romfs_nup_base", detail) : new Msg("packs_romfs_folder_base", detail))
                    : nup ? new Msg("packs_romfs_nup_update", version, detail) : new Msg("packs_romfs_folder_update", version, detail);
            return new Msg[] {sentence, detail};
        }
        return null;
    }

    /** Whether a line names a build ID: either is the start of the other, with 16 hex digits or more */
    static boolean sameBuild(String text, String id) {
        String t = text.trim().toLowerCase(Locale.ROOT), i = id.toLowerCase(Locale.ROOT);
        if (t.length() < 16 || !t.matches("[0-9a-f]+")) return false;
        return i.startsWith(t) || t.startsWith(i);
    }

    /** Line n (from 1, trimmed) of a RomFS file, null when the file has fewer lines; IOException when the file is not there */
    static String line(String romfs, String path, int n) throws IOException {
        byte[] b;
        Nup.Ref r = Nup.parse(romfs);
        if (r != null) {
            Nup nup = Nup.open(new File(r.path));
            Nup.Pkg p = r.selector == null ? nup.newest() : nup.find(r.selector);
            if (p == null) throw new IOException(romfs + ": no such package");   // i18n-keep: check() catches it (the line is "missing"), never shown
            Nup.Node x = nup.lookup(p, (r.sub.isEmpty() ? "romfs" : r.sub) + "/" + path);
            if (x == null || x.dir) throw new IOException(romfs + "/" + path + " is not there");   // i18n-keep: check() catches it (the line is "missing"), never shown
            b = nup.read(x, -1);
        } else {
            File f = new File(romfs, path);
            if (!f.isFile()) throw new IOException(f + " is not there");   // i18n-keep: check() catches it (the line is "missing"), never shown
            b = Files.readAllBytes(f.toPath());
        }
        String[] lines = new String(b, StandardCharsets.UTF_8).split("\r?\n", -1);
        return n <= lines.length ? lines[n - 1].trim() : null;
    }
}
