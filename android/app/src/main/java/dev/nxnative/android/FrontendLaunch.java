package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The play intent for emulator frontends (FRONTENDS.md), reached by the exported alias <application ID>.Launch (and .Open for file managers): an intent of any
 * action whose data is the game (a .nup, or a game folder) by a file:// or content:// URI, or the extras {@code game} (a path, or a URI as a string) or
 * {@code title_id}. Draws nothing of its own.
 * <p>
 * It can only start a game the user already has on Home, and only with that card's own game and pack paths: a card named by its title ID, or the card that holds
 * the file the frontend sent (by any alias or link), or else the card of the same title and game build as that file (another copy of it). Such a card is Play's,
 * as if its button was tapped (Play.check, the decision Home's Play takes too): when Play starts it with no dialog it starts from here at once, and when it stops
 * the frontend comes back (GameActivity's "frontend" extra); else Home opens and runs Play for it (MainActivity.fromFrontend), whose own dialogs are the only
 * questions. A file that is no card goes to Home, which asks before Add game's own steps add it. So another app can never add or replace a card, pick a pack,
 * touch a driver, a setting, env.txt or a save, or run a pack the user did not add; it can start what Play starts without asking (FRONTENDS.md, "What another
 * app can do"). It returns nothing to the caller (no result, no URI grant is passed on), reads nothing through the caller's URI (a URI only names a path, which
 * must be on shared storage: Frontend.sharedPath), and never forwards the caller's intent. The adb control extras stay MainActivity's, behind Prefs.adbControl.
 */
public final class FrontendLaunch extends Activity {
    /** The game handed to Home under this token (Frontend.take: once, and only the newest): { path, "card" or "new" }; null for any other token (or none) */
    static String[] take(String token) { return Frontend.take(token); }

    /** Android 12: the language picked in Settings › Language (Lang.wrap; Android 13+ applies it itself) */
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) { finish(); return; }   // recreated after it handed the game on
        Intent i = getIntent();
        android.util.Log.i("nxnative", "frontend launch from " + getReferrer() + ": action " + i.getAction() + ", data " + (i.getData() != null) + ", extras "
                + (i.getExtras() != null ? i.getExtras().keySet() : "none"));
        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(this, getString(R.string.home_frontend_no_access, getString(R.string.app_name)), Toast.LENGTH_LONG).show();
            home(null);
            return;
        }
        String tid = Frontend.titleId(i.getStringExtra("title_id"));
        if (tid != null) { byCard(null, tid); return; }
        String path = pathOf(i);
        if (path == null) {
            fail(i.getData() != null || Frontend.contentUri(i.getStringExtra("game"))
                    ? getString(R.string.home_frontend_bad_link)
                    : i.getStringExtra("game") != null
                    ? getString(R.string.home_frontend_not_shared, Frontend.shown(i.getStringExtra("game")))
                    : getString(R.string.home_frontend_no_game));
            return;
        }
        byCard(path, null);
    }

    /**
     * The game's path, as shared storage names it (Frontend.sharedPath, also for the path the file system resolves it to): the intent's data (file://, a storage
     * document, a known frontend's provider URI or one that carries a /storage path), else the extra "game" (a path, or such a URI as a string); null when there is
     * none or it is not on shared storage
     */
    private String pathOf(Intent i) {
        Uri u = i.getData();
        String g = i.getStringExtra("game");
        if (u == null && Frontend.contentUri(g)) u = Uri.parse(g.trim());
        String p;
        if (u != null) {
            if ("file".equalsIgnoreCase(u.getScheme())) p = Frontend.fileUri(u.toString());
            else if ("content".equalsIgnoreCase(u.getScheme())) {
                p = PathUtil.documentToPath(u);   // ES-DE's %ROMSAF%: a document of a picked tree
                if (p == null) p = Frontend.providerPath(u.getAuthority(), u.getPath());   // Pegasus's {file.uri}, FileProvider root paths
            } else p = null;
        } else p = Frontend.extraPath(g);
        String s = Frontend.sharedPath(p);
        if (s == null) return null;
        File f = new File(s);
        try {
            if (f.exists() && Frontend.sharedPath(f.getCanonicalPath()) == null) return null;   // a link out of shared storage
        } catch (IOException e) {
            return null;
        }
        return s;
    }

    /**
     * The card the request names (Frontend.target: by title ID, else by its file, else by its title and build: sameBuild), started at once when Play would start
     * it with no dialog (Play.check); any other card goes to Home, which runs Play for it, and a file that is no card goes to Home, which asks first
     */
    private void byCard(String path, String tid) {
        File file = path != null ? new File(path) : null;
        if (file != null) {
            if (Nup.wrongCase(file)) { fail(getString(R.string.home_nup_wrong_case, file.getName())); return; }
            if (!file.exists()) { fail(getString(R.string.home_file_missing, file)); return; }
            if (!file.canRead()) { fail(getString(R.string.home_file_unreadable, file)); return; }
        }
        new Thread(() -> {
            List<Game> list = Game.load(this);
            List<String> dirs = new ArrayList<>(), ids = new ArrayList<>();
            for (Game g : list) { dirs.add(GameList.canon(g.gameDir)); ids.add(g.titleId); }   // the same file by any alias or link (GameList.canon, as Home)
            int at = Frontend.target(dirs, ids, tid, path != null ? GameList.canon(path) : null);
            if (at < 0 && tid == null) at = sameBuild(list, file);
            Game card = at < 0 ? null : list.get(at);
            PlayRule.Verdict v = card != null ? Play.check(this, card) : null;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (card == null && tid != null) {
                    fail(getString(R.string.home_frontend_no_card, tid, getString(R.string.app_name)));
                    return;
                }
                if (v != null && v.kind.straight()) {
                    android.util.Log.i("nxnative", "frontend launch: " + card.titleId + " " + card.gameDir + " starts (" + v.kind + ")");
                    Play.start(this, card, v.kind, true);
                    finish();
                } else if (card != null) {
                    android.util.Log.i("nxnative", "frontend launch: " + card.titleId + " " + card.gameDir + " goes to Play's steps on Home (" + v.kind + ")");
                    home(Frontend.handOff(card.gameDir, true));   // Home runs Play for it, with no question in front
                } else home(Frontend.handOff(path, false));   // a file that is no card: Home asks before Add game's steps
            });
        }, "nx-frontend").start();
    }

    /**
     * The card of the same title and game build as file (GameList.matches with Game.key: another copy of the card's .nup, or the folder it was made from), or -1.
     * Such a card is the game the frontend names; it starts with its own files and pack, and the file is not added. -1 also when the file holds no one game
     */
    private static int sameBuild(List<Game> list, File file) {
        String id;
        try { id = Pack.titleIdOf(file); } catch (IOException e) { return -1; }
        String key = Game.key(file.getPath(), id);
        if (key == null) return -1;
        List<Integer> m = GameList.matches(list, id, file.getPath(), key, Game::key);
        return m.isEmpty() ? -1 : m.get(0);
    }

    /** Home, with the game handed over under token when there is one (MainActivity.fromFrontend: Play for a card, the question before Add game for a new file) */
    private void home(String token) {
        Intent h = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (token != null) h.putExtra("frontend_token", token);
        startActivity(h);
        finish();
    }

    private void fail(String why) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.home_frontend_fail_title).setMessage(why)
                .setPositiveButton(android.R.string.ok, null).setOnDismissListener(d -> finish()).show();
    }
}
