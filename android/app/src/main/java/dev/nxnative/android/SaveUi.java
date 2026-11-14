package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A game's Back up saves / Restore saves… / Import save… (its Manage sheet), over SaveStore. The save folder is the one the game's next start uses (Folders.save:
 * the app's saves/&lt;id&gt;, the old default beside the game until it is copied in, or the picked one), the backups go to &lt;Switch2&gt;/save-backups/&lt;id&gt;/
 * on shared storage, so they outlive an uninstall. Each refuses while a game runs (the
 * :game process is alive): the host may be writing the save. While one works, Play waits for it (busy). Nothing here deletes a backup.
 */
final class SaveUi {
    static final int PICK_IMPORT = 30;
    private static final Set<String> busy = new HashSet<>();
    private static Game importFor;

    private SaveUi() {}

    /** Whether a save operation of this game is running (MainActivity.launch waits) */
    static synchronized boolean busy(String titleId) { return busy.contains(titleId); }

    private static synchronized boolean take(String titleId) { return busy.add(titleId); }

    private static synchronized void give(String titleId) { busy.remove(titleId); }

    /**
     * Whether the game of this save runs now: its process holds the save's lock (GameActivity takes &lt;save&gt;.lock before the save is used and keeps it while the
     * process lives, and the kernel lets it go when the process ends, also after a crash). A :game process left idle, or another game's, does not count
     */
    static boolean gameRunning(Context c, Game g) { return SaveStore.inUse(Folders.save(c, g)); }

    static String stamp() { return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()); }

    static File backups(Game g) { return Prefs.backupsDir(new File(g.gameDir), g.titleId); }

    /** running: the sentence that the game is running, with the step it names (saves_running_backup, _restore, _import) */
    private static boolean ready(MainActivity a, Game g, int running) {
        if (a.needAccess()) return false;
        if (busy(g.titleId)) { a.error(a.getString(R.string.saves_busy, g.name)); return false; }
        if (gameRunning(a, g)) { a.error(a.getString(running, g.name, SidePanel.stopLabel().text())); return false; }
        return true;
    }

    /** Runs work off the main thread with a wait dialog; done gets null or the error */
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
        }, "nx-save").start();
    }

    interface Work { Object run() throws IOException; }
    interface Done { void done(Object result, String error); }

    static void backup(MainActivity a, Game g) {
        if (!ready(a, g, R.string.saves_running_backup)) return;
        File save = Folders.save(a, g);
        boolean any = false;
        for (File p : SaveStore.parts(save).values()) { String[] l = p.list(); if (l != null && l.length > 0) any = true; }
        if (!any) { a.error(a.getString(R.string.saves_backup_nothing, g.name, save)); return; }
        File to = backups(g);
        run(a, g, a.getString(R.string.saves_backup_wait, g.name), () -> SaveStore.backup(save, to, stamp()), (r, err) -> {
            if (err != null) { a.error(a.getString(R.string.saves_backup_failed, err, save)); return; }
            SaveStore.Backup b = (SaveStore.Backup) r;
            String body = a.getResources().getQuantityString(b.device ? R.plurals.saves_backup_done_body_device : R.plurals.saves_backup_done_body, b.files, b.files,
                    SaveStore.size(b.bytes), b.dir);
            new AlertDialog.Builder(a).setTitle(R.string.saves_backup_done_title).setMessage(body).setPositiveButton(android.R.string.ok, null).show();
        });
    }

    static void restoreList(MainActivity a, Game g) {
        if (!ready(a, g, R.string.saves_running_restore)) return;
        File to = backups(g);
        List<SaveStore.Backup> l = SaveStore.list(to);
        if (l.isEmpty()) { a.error(a.getString(R.string.saves_restore_none, g.name, to, HomeLabels.backUpSaves().text())); return; }
        String[] labels = new String[l.size()];
        for (int i = 0; i < labels.length; i++) {
            SaveStore.Backup b = l.get(i);
            String label = a.getResources().getQuantityString(b.device ? R.plurals.saves_restore_item_device : R.plurals.saves_restore_item, b.files, SaveStore.when(b.name),
                    b.files, SaveStore.size(b.bytes));
            labels[i] = b.source.isEmpty() ? label
                    : a.getString(R.string.saves_restore_item_from, label, new File(b.source).getParentFile().getName() + "/" + new File(b.source).getName());
        }
        new AlertDialog.Builder(a).setTitle(a.getString(R.string.saves_restore_list_title, g.name)).setItems(labels, (d, w) -> confirm(a, g, l.get(w).dir, SaveStore.when(l.get(w).name)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    static void importPick(MainActivity a, Game g) {
        if (!ready(a, g, R.string.saves_running_import)) return;
        importFor = g;
        a.pickFolder(PICK_IMPORT, a.getString(R.string.saves_import_pick, g.titleId));
    }

    /** MainActivity.onActivityResult: the folder picked for Import save… */
    static boolean activityResult(MainActivity a, int request, File dir) {
        if (request != PICK_IMPORT) return false;
        Game g = importFor;
        importFor = null;
        if (g != null) confirm(a, g, dir, a.getString(R.string.saves_import_source, dir));
        return true;
    }

    /** what: the source in words (a backup's time, or "the folder …"), in the user's language */
    private static void confirm(MainActivity a, Game g, File from, String what) {
        File save = Folders.save(a, g);
        Msg why = SaveStore.refuseSourceMsg(from, save);
        if (why != null) { a.error(a.getString(R.string.saves_not_restored_refused, why.text())); return; }
        String st = stamp();
        Ui.confirmDanger(a, a.getString(R.string.saves_restore_confirm_title, g.name),
                a.getString(R.string.saves_restore_confirm_body, save, what, new File(backups(g), st + SaveStore.BEFORE_RESTORE), HomeLabels.restoreSaves().text()),
                a.getString(R.string.saves_restore_button), () -> {
                    if (!ready(a, g, R.string.saves_running_restore)) return;
                    run(a, g, a.getString(R.string.saves_restore_wait, g.name), () -> SaveStore.restore(from, save, backups(g), st), (r, err) -> {
                        if (err != null) {
                            a.error(a.getString(SaveStore.pending(save) ? R.string.saves_restore_failed_pending : R.string.saves_restore_failed, err, save));
                            return;
                        }
                        SaveStore.Restored x = (SaveStore.Restored) r;
                        boolean dev = x.parts.contains(SaveStore.DEVICE);
                        int n = x.now.files;
                        String size = SaveStore.size(x.now.bytes);
                        String text = x.before == null
                                ? a.getResources().getQuantityString(dev ? R.plurals.saves_restored_device : R.plurals.saves_restored, n, n, size)
                                : a.getResources().getQuantityString(dev ? R.plurals.saves_restored_device_before : R.plurals.saves_restored_before, n, n, size, x.before.dir);
                        Toast.makeText(a, text, Toast.LENGTH_LONG).show();
                    });
                });
    }
}
