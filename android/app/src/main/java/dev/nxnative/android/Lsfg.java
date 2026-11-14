package dev.nxnative.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Lossless Scaling frame generation (LSFG): the user's own Lossless.dll and the settings. The DLL is never part of the APK: the launcher looks for it in a few known places on
 * the phone's storage (it has All files access), takes the first it finds, and keeps a copy in the app's private files; the user can also pick it by hand. The host reads
 * the copy (DM_LSFG_DLL) and the settings (DM_LSFG*), and takes changes made while the game runs through Native.frameGenSet.
 */
final class Lsfg {
    private Lsfg() {}

    /** The places looked in, in order; then the app's own folders */
    static final String[] KNOWN = {"/storage/emulated/0/Documents/Emulation/bios/Lossless.dll", "/storage/emulated/0/Switch2/Lossless.dll", "/storage/emulated/0/Download/Lossless.dll"};
    static final int[] MULTS = {2, 3, 4}, TARGETS = {0, 60, 90, 120}, FLOWS = {0, 25, 50, 75, 100};

    static File privateCopy(Context c) { return new File(c.getFilesDir(), "lsfg/Lossless.dll"); }

    private static SharedPreferences prefs(Context c) { return Prefs.of(c); }
    static boolean on(Context c) { return prefs(c).getBoolean("lsfg", false); }
    static int multiplier(Context c) { return prefs(c).getInt("lsfg_mult", 2); }
    static int target(Context c) { return prefs(c).getInt("lsfg_target", 0); }
    static int flow(Context c) { return prefs(c).getInt("lsfg_flow", 75); }

    /** Where the DLL in use came from, in English (the log line); the main screen shows noteText() */
    static String note = "";
    /** The same as a Msg (null until find or install sets it), for the screen: noteText() */
    private static Msg noteMsg;

    private static void note(Msg m) { noteMsg = m; note = m.english(); }

    /** Where the DLL in use came from, in the user's language (the main screen) */
    static String noteText() { return noteMsg != null ? noteMsg.text() : note; }

    /** An exception's text as an argument of a Msg: its own Msg when it has one (each language renders it), else its English message */
    private static Object detail(Exception e) {
        return e instanceof LocalizedIOException && ((LocalizedIOException) e).msg != null ? ((LocalizedIOException) e).msg : e.getMessage();
    }

    private static boolean looksLikeDll(File f) {
        if (!f.isFile() || f.length() < 100_000) return false;
        try (InputStream in = new FileInputStream(f)) { return in.read() == 'M' && in.read() == 'Z'; } catch (IOException | SecurityException e) { return false; }
    }

    private static void copy(InputStream in, File to) throws IOException {
        File dir = to.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new LocalizedIOException("game_cannot_create", dir);
        File tmp = new File(to.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 16]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        if (!tmp.renameTo(to)) throw new LocalizedIOException("game_cannot_replace", to);
    }

    /** The DLL to use: the first known place that has one (copied into private files when the copy differs from it), else an earlier copy. Null = none. */
    static File find(Context c) {
        File copy = privateCopy(c);
        File ext = c.getExternalFilesDir(null);
        String[] where = ext != null ? new String[KNOWN.length + 1] : KNOWN;
        if (ext != null) { System.arraycopy(KNOWN, 0, where, 0, KNOWN.length); where[KNOWN.length] = new File(ext, "Lossless.dll").getPath(); }
        for (String p : where) {
            File f = new File(p);
            if (!looksLikeDll(f)) continue;
            try {
                if (!copy.isFile() || copy.length() != f.length() || copy.lastModified() != f.lastModified()) {
                    try (InputStream in = new FileInputStream(f)) { copy(in, copy); }
                    copy.setLastModified(f.lastModified());
                }
                note(new Msg("game_lsfg_from", p));
                return copy;
            } catch (IOException | SecurityException e) {
                note(new Msg("game_lsfg_not_copied", p, detail(e)));
            }
        }
        if (looksLikeDll(copy)) { note(new Msg("game_lsfg_earlier")); return copy; }
        if (noteMsg == null || !"game_lsfg_not_copied".equals(noteMsg.key)) note(new Msg("game_lsfg_not_found"));
        return null;
    }

    /** The manual pick (the fallback when it is in none of the known places): copied into private files. Null = done, else the error, in the user's language (the screen's). */
    static String install(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return c.getString(R.string.game_lsfg_cannot_open, uri);
            copy(in, privateCopy(c));
        } catch (IOException | SecurityException e) { return e.getLocalizedMessage(); }
        if (!looksLikeDll(privateCopy(c))) { privateCopy(c).delete(); return c.getString(R.string.game_lsfg_not_dll); }
        note(new Msg("game_lsfg_by_hand"));
        return null;
    }

    /** The host's switches for a run (env.txt lines come after these and win) */
    static void env(Context c, List<String> env) {
        File dll = find(c);
        if (dll != null) env.add("DM_LSFG_DLL=" + dll.getPath());
        env.add("DM_LSFG=" + (on(c) ? 1 : 0));
        env.add("DM_LSFG_MULT=" + multiplier(c));
        env.add("DM_LSFG_TARGET=" + target(c));
        env.add("DM_LSFG_FLOW=" + flow(c));
    }

    private static RadioGroup choice(Context c, int[] values, int current, String[] labels, java.util.function.IntConsumer pick) {
        RadioGroup g = new RadioGroup(c);
        g.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < values.length; i++) {
            RadioButton b = new RadioButton(c);
            b.setText(labels[i]);
            b.setId(1000 + i);
            g.addView(b);
            if (values[i] == current) b.setChecked(true);
        }
        g.setOnCheckedChangeListener((gr, id) -> pick.accept(values[id - 1000]));
        return g;
    }

    /** The settings rows (main screen dialog and the in-game panel); apply runs after each change (in game: Native.frameGenSet). Returns the status line (null without LSFG in this build) */
    static TextView rows(Context c, LinearLayout box, String status, Runnable apply) {
        TextView head = new TextView(c);
        head.setText(c.getString(R.string.game_lsfg_head));
        head.setPadding(0, 24, 0, 0);
        box.addView(head);
        if (!BuildConfig.NX_LSFG) { TextView t = new TextView(c); t.setText(c.getString(R.string.game_lsfg_not_built)); box.addView(t); return null; }
        CheckBox on = new CheckBox(c);
        on.setText(c.getString(R.string.game_lsfg_on));
        on.setChecked(on(c));
        on.setOnCheckedChangeListener((b, v) -> { prefs(c).edit().putBoolean("lsfg", v).commit(); apply.run(); });
        box.addView(on);
        TextView st = new TextView(c);
        st.setText(status);
        st.setTextSize(12);
        box.addView(st);
        TextView l1 = new TextView(c); l1.setText(c.getString(R.string.game_lsfg_mult)); box.addView(l1);
        box.addView(choice(c, MULTS, multiplier(c), new String[]{"2x", "3x", "4x"}, v -> { prefs(c).edit().putInt("lsfg_mult", v).commit(); apply.run(); }));
        TextView l2 = new TextView(c); l2.setText(c.getString(R.string.game_lsfg_target)); box.addView(l2);
        box.addView(choice(c, TARGETS, target(c), new String[]{c.getString(R.string.game_lsfg_fixed), "60", "90", "120"}, v -> { prefs(c).edit().putInt("lsfg_target", v).commit(); apply.run(); }));
        TextView l3 = new TextView(c); l3.setText(c.getString(R.string.game_lsfg_flow, c.getString(R.string.game_lsfg_auto))); box.addView(l3);
        box.addView(choice(c, FLOWS, flow(c), new String[]{c.getString(R.string.game_lsfg_auto), "25%", "50%", "75%", "100%"}, v -> { prefs(c).edit().putInt("lsfg_flow", v).commit(); apply.run(); }));
        TextView mode = new TextView(c);   // Eden ports only the performance shader set (lossless_dll.h PerformanceShader); the quality set would need its own chain
        mode.setText(c.getString(R.string.game_lsfg_mode));
        mode.setTextSize(12);
        box.addView(mode);
        return st;
    }

    static void push(Context c) { Native.frameGenSet(on(c), multiplier(c), target(c), flow(c)); }
}
