package dev.nxnative.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

/**
 * Play for one card, shared by Home's Play button (MainActivity.launch) and an emulator frontend's request (FrontendLaunch): check reads the card's facts and
 * takes PlayRule's decision; start does what Play does when that decision is straight (the automatic shader update of a dump's older library, the legacy
 * library's warning, the card marked played last, GameActivity with the card's own paths). The dialogs of the other verdicts are Home's (MainActivity.launch).
 */
final class Play {
    private Play() {}

    /** PlayRule's decision for the card (reads its pack folder and game files: off the main thread where it can be) */
    static PlayRule.Verdict check(Context c, Game g) { return PlayRule.decide(facts(c, g)); }

    /** The card's facts as PlayRule asks them, read from its own folders */
    static PlayRule.Facts facts(Context c, Game g) {
        File gd = new File(g.gameDir), pd = new File(g.packDir);
        return new PlayRule.Facts() {
            private Pack p;

            @Override public boolean access() { return Environment.isExternalStorageManager(); }
            @Override public Msg noProfile() { return Profile.missingMsg(g.titleId, g.name); }
            @Override public boolean saveBusy() { return SaveUi.busy(g.titleId); }
            @Override public boolean packBusy() { return PackUi.busy(g.titleId); }
            @Override public void readPack() throws IOException { p = Pack.read(pd); }
            @Override public boolean placeholder() { return p.simulated; }
            @Override public Msg otherTitle() {
                if (p.titleId.equalsIgnoreCase(g.titleId)) return null;
                return Nup.isNup(gd) ? new Msg("home_pack_other_title_nup", p.titleId, g.titleId) : new Msg("home_pack_other_title_folder", p.titleId, g.titleId);
            }
            @Override public Msg blocking() { return PackCheck.blockingMsg(c, p, gd); }
            @Override public Msg libraryProblem() { return Pack.libraryProblemMsg(pd, g.titleId); }
            @Override public boolean playableLibrary() { return Pack.playableLibrary(pd, g.titleId, Ondevice.isDump(gd)) != null; }
            @Override public boolean legacyLibrary() { return Pack.legacyLibrary(pd, g.titleId) != null; }
        };
    }

    /**
     * The game of a straight verdict (PlayRule.Kind.straight), on the main thread: an older or legacy library of a dump starts the automatic shader update
     * (Ondevice.autoUpdate: only that card's pack, only when the profile's delta manifest knows the library), a legacy library says so in a toast, then go.
     * frontend: a frontend asked for it, so the frontend comes back when the game stops (GameActivity.backToFrontend)
     */
    static void start(Activity a, Game g, PlayRule.Kind k, boolean frontend) {
        if (!k.straight()) throw new IllegalArgumentException("Play.start with " + k + ": Play's own steps come first");   // i18n-keep: a caller's bug, never shown
        File gd = new File(g.gameDir);
        if (k != PlayRule.Kind.START && Ondevice.isDump(gd)) Ondevice.autoUpdate(gd, new File(g.packDir), g.titleId);
        if (k == PlayRule.Kind.START_LEGACY) Toast.makeText(a.getApplicationContext(), ShaderLibs.legacyWarning(), Toast.LENGTH_LONG).show();
        go(a, g, frontend);
    }

    /** GameActivity with the card's own paths (never a caller's); also Play's "Start anyway". singleTask: a game already running stays, and says so */
    static void go(Activity a, Game g, boolean frontend) {
        Game.touch(a, g);   // the entry played last is the one kept if the list ever holds this game twice
        Intent i = new Intent(a, GameActivity.class).putExtra("title_id", g.titleId).putExtra("name", g.name).putExtra("game", g.gameDir).putExtra("pack", g.packDir);
        if (frontend) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("frontend", true);
        a.startActivity(i);
    }
}
