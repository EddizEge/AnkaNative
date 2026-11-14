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
import java.util.UUID;

/**
 * The play intent for emulator frontends (FRONTENDS.md), reached by the exported alias <application ID>.Launch (and .Open for file managers): an intent of any
 * action whose data is the game (a .nup, or a game folder) by a file:// or content:// URI, or the extras {@code game} (a path, or a URI as a string) or
 * {@code title_id}. Draws nothing of its own.
 * <p>
 * It can only start a game the user already has on Home, and only with that card's own game and pack paths: a card named by its title ID, or the card that holds
 * the file the frontend sent (by any alias or link), or else the card of the same title and game build as that file (another copy of it). Such a game starts
 * at once when it is ready to play (the checks of Play, ready), and when it stops the frontend comes back (GameActivity's "frontend" extra). Anything else goes to Home, which asks first (MainActivity.fromFrontend): a card that needs something (a pack, Prepare, a
 * shader update, a pack that does not fit) asks before Play's steps run, and a file that is no card asks before Add game's own steps add it. So another app can
 * never add or replace a card, pick a pack, start Prepare or a shader update, touch a driver, a setting, env.txt or a save, or run a pack the user did not add.
 * It returns nothing to the caller (no result, no URI grant is passed on), reads nothing through the caller's URI (a URI only names a path, which must be on
 * shared storage: Frontend.sharedPath), and never forwards the caller's intent. The adb control extras stay MainActivity's, behind Prefs.adbControl.
 */
public final class FrontendLaunch extends Activity {
    // ---- What FrontendLaunch hands Home (MainActivity.fromFrontend): kept in this process and named by a one-time token in the intent, so no other app can
    // send Home a game to ask about by an extra of its own (MainActivity is exported) ----
    private static String handToken, handPath;
    private static boolean handCard;

    /** A frontend's game for Home: path is the card's game path (card) or the new file's; the token for MainActivity's "frontend_token" extra */
    private static synchronized String handOff(String path, boolean card) {
        handToken = UUID.randomUUID().toString();
        handPath = path;
        handCard = card;
        return handToken;
    }

    /** The game handed to Home under this token, once: { path, "card" or "new" }; null for any other token (or none) */
    static synchronized String[] take(String token) {
        if (token == null || handToken == null || !handToken.equals(token)) return null;
        String[] r = {handPath, handCard ? "card" : "new"};
        handToken = null;
        handPath = null;
        return r;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) { finish(); return; }   // recreated after it handed the game on
        Intent i = getIntent();
        android.util.Log.i("nxnative", "frontend launch from " + getReferrer() + ": action " + i.getAction() + ", data " + (i.getData() != null) + ", extras "
                + (i.getExtras() != null ? i.getExtras().keySet() : "none"));
        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Turn on All files access for " + getString(R.string.app_name) + ", then start the game again.", Toast.LENGTH_LONG).show();
            home(null);
            return;
        }
        String tid = Frontend.titleId(i.getStringExtra("title_id"));
        if (tid != null) { byCard(null, tid); return; }
        String path = pathOf(i);
        if (path == null) {
            fail(i.getData() != null || Frontend.contentUri(i.getStringExtra("game"))
                    ? "The frontend sent a link that does not name a file on this phone's storage by a path the app can read. Set the frontend to send the file's path, or its storage (SAF) URI from the phone's own storage."
                    : i.getStringExtra("game") != null
                    ? "The frontend sent \"" + Frontend.shown(i.getStringExtra("game")) + "\", which is not a file on this phone's shared storage."
                    : "The frontend sent no game: send the .nup (or the game folder) as the intent's data, or the extra \"game\" with its path.");
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
     * The card the request names (Frontend.target: by title ID, else by its file, else by its title and build: sameBuild), started when it is ready; a card that is not ready, and a file that is no card,
     * go to Home, which asks first
     */
    private void byCard(String path, String tid) {
        File file = path != null ? new File(path) : null;
        if (file != null) {
            if (Nup.wrongCase(file)) { fail(file.getName() + ": rename it so that its name ends in \".nup\" (lower case); the game and the tools read only that name."); return; }
            if (!file.exists()) { fail(file + " is not there."); return; }
            if (!file.canRead()) { fail(file + " cannot be read. Check that All files access is on."); return; }
        }
        new Thread(() -> {
            List<Game> list = Game.load(this);
            List<String> dirs = new ArrayList<>(), ids = new ArrayList<>();
            for (Game g : list) { dirs.add(GameList.canon(g.gameDir)); ids.add(g.titleId); }   // the same file by any alias or link (GameList.canon, as Home)
            int at = Frontend.target(dirs, ids, tid, path != null ? GameList.canon(path) : null);
            if (at < 0 && tid == null) at = sameBuild(list, file);
            Game card = at < 0 ? null : list.get(at);
            Game r = card != null && ready(card) ? card : null;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (card == null && tid != null) {
                    fail("No game with title ID " + tid + " is on Home. Add it in " + getString(R.string.app_name) + " first, or send the game's file instead.");
                    return;
                }
                if (r != null) play(r);
                else if (card != null) home(handOff(card.gameDir, true));
                else home(handOff(path, false));   // a file that is no card: Home asks before Add game's steps
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

    /**
     * Whether a card can start now, as Play would start it without asking: a game this APK has, no save backup or restore and no pack export or import working,
     * a pack that reads, is no placeholder, is of this title and of this game build (PackCheck.blocking), has a RomFS for it and this version's shader library.
     * Reads only; false: Home asks (Play's steps)
     */
    private boolean ready(Game card) {
        String id = card.titleId;
        if (Profile.missing(id, card.name) != null || SaveUi.busy(id) || PackUi.busy(id)) return false;
        File game = new File(card.gameDir), packDir = new File(card.packDir);
        Pack p;
        try {
            p = Pack.read(packDir);
            if (p.simulated || !p.titleId.equalsIgnoreCase(id)) return false;
            if (Nup.isNup(game) ? p.romfsPath(game) == null : p.romfs(game) == null) return false;
        } catch (IOException e) {
            return false;
        }
        return PackCheck.blocking(this, p, game) == null && Pack.libraryProblem(packDir, id) == null;
    }

    /** The card's game, with the card's own paths (never the caller's), in GameActivity (singleTask: a game already running stays, and says so) */
    private void play(Game g) {
        startActivity(new Intent(this, GameActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("title_id", g.titleId).putExtra("name", g.name).putExtra("game", g.gameDir).putExtra("pack", g.packDir).putExtra("frontend", true));
        finish();
    }

    /** Home, with the game handed over under token when there is one (MainActivity.fromFrontend asks the user) */
    private void home(String token) {
        Intent h = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (token != null) h.putExtra("frontend_token", token);
        startActivity(h);
        finish();
    }

    private void fail(String why) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Cannot start the game").setMessage(why)
                .setPositiveButton("OK", null).setOnDismissListener(d -> finish()).show();
    }
}
