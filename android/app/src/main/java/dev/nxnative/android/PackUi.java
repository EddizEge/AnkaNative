package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * A game's Export pack… and Import pack… (its Manage sheet), over PackZip. Export writes the game's pack as one stored zip (anka-pack-&lt;id&gt;-&lt;version&gt;.zip)
 * into a folder the user picks; Import takes such a zip, or a pack folder, checks it (its title is this game's, its code fits the game build in the game folder:
 * PackCheck.blocking, a shader library is in it) and puts it into the app's packs/&lt;id&gt; (AppStorage), keeping the pack that was there until the new one is in.
 * The game's card then uses it (a picked pack folder is let go). An older library goes through the usual restamp or shader update at Play. Both refuse while the
 * game runs; Play waits while one works (busy).
 */
final class PackUi {
    static final int PICK_EXPORT = 40, PICK_IMPORT_ZIP = 41, PICK_IMPORT_DIR = 42;
    private static final Set<String> busy = new HashSet<>();
    private static Game pending;

    private PackUi() {}

    /** Whether an export or import of this game's pack is working (MainActivity.launch waits) */
    static synchronized boolean busy(String titleId) { return busy.contains(titleId); }

    private static synchronized boolean take(String titleId) { return busy.add(titleId); }

    private static synchronized void give(String titleId) { busy.remove(titleId); }

    private static boolean ready(MainActivity a, Game g) {
        if (a.needAccess()) return false;
        if (busy(g.titleId)) { a.error(a.getString(R.string.packs_ui_busy, g.name)); return false; }
        if (SaveUi.gameRunning(a, g)) { a.error(a.getString(R.string.packs_ui_running, g.name, a.getString(R.string.packs_label_stop_game))); return false; }
        return true;
    }

    static void exportPick(MainActivity a, Game g) {
        if (!ready(a, g)) return;
        try {
            Pack.read(new File(g.packDir));
        } catch (IOException e) {
            a.error(a.getString(R.string.packs_ui_export_none, e.getLocalizedMessage()));
            return;
        }
        pending = g;
        a.pickFolder(PICK_EXPORT, a.getString(R.string.packs_ui_export_pick));
    }

    static void importPick(MainActivity a, Game g) {
        if (!ready(a, g)) return;
        new AlertDialog.Builder(a).setTitle(a.getString(R.string.packs_ui_import_title, g.name))
                .setItems(new String[] {a.getString(R.string.packs_ui_import_zip, a.getString(R.string.packs_label_export)), a.getString(R.string.packs_ui_import_folder)}, (d, w) -> {
                    pending = g;
                    if (w == 1) { a.pickFolder(PICK_IMPORT_DIR, a.getString(R.string.packs_ui_import_pick_folder)); return; }
                    Toast.makeText(a, a.getString(R.string.packs_ui_import_pick_zip), Toast.LENGTH_LONG).show();
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
                    a.startActivityForResult(i, PICK_IMPORT_ZIP);
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /**
     * adb, test app only (android/TESTING.md "Packs in app storage"): am start -n dev.nxnative.android/.MainActivity --es pack_title &lt;title ID&gt;
     * [--es pack_game &lt;game folder or .nup&gt;] (--es pack_export &lt;folder&gt; | --es pack_import &lt;.zip or pack folder&gt;) runs Export pack… / Import pack…
     * of the listed game of that title (the entry of that game path, else the first) without the pickers, with the same checks and dialogs
     */
    static void fromIntent(MainActivity a, Intent i) {
        String id = i.getStringExtra("pack_title"), gp = i.getStringExtra("pack_game"), ex = i.getStringExtra("pack_export"), im = i.getStringExtra("pack_import");
        if (BuildConfig.NX_PLAY || id == null || ex == null && im == null) return;
        for (Game g : Game.load(a)) {
            if (!g.titleId.equals(id) || gp != null && !g.gameDir.equals(gp)) continue;
            if (ex != null) exportRun(a, g, new File(ex));
            else importRun(a, g, new File(im));
            return;
        }
        a.error(a.getString(R.string.packs_ui_no_game, id));
    }

    /** MainActivity.onActivityResult, before a folder's path is read: the .zip picked for Import pack… */
    static boolean documentResult(MainActivity a, int request, Uri doc) {
        if (request != PICK_IMPORT_ZIP) return false;
        Game g = pending;
        pending = null;
        if (g == null) return true;
        String p = PathUtil.documentToPath(doc);
        if (p == null) { a.error(a.getString(R.string.packs_ui_not_readable, doc)); return true; }
        importRun(a, g, new File(p));
        return true;
    }

    /** MainActivity.onActivityResult: the folder picked for Export pack… or Import pack… */
    static boolean activityResult(MainActivity a, int request, File dir) {
        if (request != PICK_EXPORT && request != PICK_IMPORT_DIR) return false;
        Game g = pending;
        pending = null;
        if (g == null) return true;
        if (request == PICK_EXPORT) exportRun(a, g, dir);
        else importRun(a, g, dir);
        return true;
    }

    private static long size(File f) {
        if (!f.isDirectory()) return f.length();
        long n = 0;
        File[] l = f.listFiles();
        if (l != null) for (File x : l) n += size(x);
        return n;
    }

    private static void exportRun(MainActivity a, Game g, File dir) {
        if (!ready(a, g)) return;
        File pack = new File(g.packDir);
        run(a, g, a.getString(R.string.packs_ui_exporting, g.name), () -> {
            long need = size(pack);
            if (dir.isDirectory() && dir.getUsableSpace() < need + (64L << 20))
                throw new IOException(a.getString(R.string.packs_ui_export_space, SaveStore.size(need), dir, SaveStore.size(dir.getUsableSpace())));
            return PackZip.export(pack, dir);
        }, (r, err) -> {
            if (err != null) { a.error(a.getString(R.string.packs_ui_export_failed, err, pack)); return; }
            File z = (File) r;
            new AlertDialog.Builder(a).setTitle(R.string.packs_ui_exported_title)
                    .setMessage(a.getString(R.string.packs_ui_exported, z, SaveStore.size(z.length()), a.getString(R.string.packs_label_import))).setPositiveButton(android.R.string.ok, null).show();
        });
    }

    private static void importRun(MainActivity a, Game g, File source) {
        if (!ready(a, g)) return;
        File packs = AppStorage.packs(a), target = AppStorage.pack(a, g.titleId);
        run(a, g, a.getString(R.string.packs_ui_importing, g.name), () -> {
            long need = size(source);
            if (packs.getParentFile() != null && packs.getParentFile().getUsableSpace() < need + (256L << 20))
                throw new IOException(a.getString(R.string.packs_ui_import_space, SaveStore.size(need), SaveStore.size(packs.getParentFile().getUsableSpace())));
            File remembered = Job.remembered(a);
            if (remembered != null && remembered.isFile()) {
                Job j = Job.read(remembered);
                if (SaveStore.same(j.pack(), target) && !"done".equals(j.state()))
                    throw new LocalizedIOException("packs_ui_import_job", target, j.statusMsg(false), new Msg("packs_label_manage"));
            }
            Pack p = PackZip.stage(source, packs, g.titleId);
            try {
                Msg bad = PackCheck.blockingMsg(a, p, new File(g.gameDir));
                if (bad != null) throw new LocalizedIOException(new Msg("packs_as_is", bad), null);
                if (SaveUi.gameRunning(a, g)) throw new IOException(a.getString(R.string.packs_ui_started_meanwhile, g.name));
                PackZip.swapIn(p.dir, target);
            } finally {
                if (p.dir.exists()) SaveStore.deleteTree(p.dir);
            }
            return target;
        }, (r, err) -> {
            if (err != null) { a.error(target.isDirectory() ? a.getString(R.string.packs_ui_import_failed_kept, err, target) : a.getString(R.string.packs_ui_import_failed_none, err)); return; }
            String old = Folders.pickedPack(a, g.titleId);
            Folders.pickedPack(a, g.titleId, null);   // the card plays the imported pack; a picked folder would win over it at the next Add game
            if (old != null && old.equals(Prefs.folder(a, "pack"))) Prefs.folder(a, "pack", null);
            Folders.setPack(a, g.titleId, target);
            Msg lp = Pack.libraryProblemMsg(target, g.titleId);
            String shaders = lp == null ? a.getString(R.string.packs_ui_library_current, BuildConfig.NX_DMSH_VK_CHAIN)
                    : Pack.playableLibrary(target, g.titleId) != null || Pack.legacyLibrary(target, g.titleId) != null
                    ? a.getString(R.string.packs_ui_library_older) : a.getString(R.string.packs_ui_library_problem, lp.text());
            new AlertDialog.Builder(a).setTitle(R.string.packs_ui_imported_title)
                    .setMessage(old != null ? a.getString(R.string.packs_ui_imported_old, target, shaders, old) : a.getString(R.string.packs_ui_imported, target, shaders))
                    .setPositiveButton(android.R.string.ok, null).show();
        });
    }

    interface Work { Object run() throws IOException; }
    interface Done { void done(Object result, String error); }

    private static void run(MainActivity a, Game g, String wait, Work work, Done done) {
        if (!take(g.titleId)) return;
        AlertDialog w = new AlertDialog.Builder(a).setMessage(wait).setCancelable(false).show();
        new Thread(() -> {
            String err = null;
            Object r = null;
            try { r = work.run(); } catch (IOException | RuntimeException e) { err = e.getLocalizedMessage(); }
            String fe = err;
            Object fr = r;
            a.runOnUiThread(() -> {
                give(g.titleId);
                w.dismiss();
                done.done(fr, fe);
            });
        }, "nx-pack-zip").start();
    }
}
