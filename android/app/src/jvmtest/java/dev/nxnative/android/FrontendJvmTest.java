package dev.nxnative.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * What an emulator frontend sends FrontendLaunch, read by Frontend on the JVM (no Android): file:// URIs, provider URIs that carry a /storage path, the shared
 * storage's aliases, the card a path names, title IDs, the one-time token that hands Home a game; and PlayRule, the decision Play and FrontendLaunch both take.
 * Run by dm/tools/tests/test_android_frontend.py; prints "ok <n>" and exits 0, or the first failure and exits 1.
 */
public final class FrontendJvmTest {
    private static int n;

    static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": want " + want + ", got " + got);
        n++;
    }

    public static void main(String[] args) {
        // file:// URIs: ES-DE's %ROM% as a URI, Daijisho's {file.uri}, escapes, an authority that is not this phone
        eq("/storage/emulated/0/Games/Banana Game.nup", Frontend.fileUri("file:///storage/emulated/0/Games/Banana%20Game.nup"), "escaped space");
        eq("/storage/emulated/0/a+b.nup", Frontend.fileUri("file:///storage/emulated/0/a+b.nup"), "plus stays");
        eq("/storage/emulated/0/é.nup", Frontend.fileUri("file:///storage/emulated/0/%C3%A9.nup"), "UTF-8 escape");
        eq("/storage/1234-ABCD/x.nup", Frontend.fileUri("file:/storage/1234-ABCD/x.nup"), "file: with one slash");
        eq("/sdcard/x.nup", Frontend.fileUri("FILE://localhost/sdcard/x.nup"), "localhost authority, upper-case scheme");
        eq("/sdcard/x.nup", Frontend.fileUri("file:///sdcard/x.nup?y=1#z"), "query and fragment go");
        eq("/sdcard/100%.nup", Frontend.fileUri("file:///sdcard/100%.nup"), "a bare % stays");
        eq(null, Frontend.fileUri("file://otherhost/sdcard/x.nup"), "another host");
        eq(null, Frontend.fileUri("content://x/y"), "not a file URI");
        eq(null, Frontend.fileUri("file:relative.nup"), "relative path");
        eq(null, Frontend.fileUri(null), "null");
        // provider URIs (their decoded path): a FileProvider root path carries the file's path; SAF ids do not
        eq("/storage/emulated/0/ROMs/x.nup", Frontend.embeddedPath("/root/storage/emulated/0/ROMs/x.nup"), "FileProvider root");
        eq("/sdcard/ROMs/x.nup", Frontend.embeddedPath("/files/sdcard/ROMs/x.nup"), "sdcard root");
        eq("/mnt/media_rw/1234-ABCD/x.nup", Frontend.embeddedPath("/ext/mnt/media_rw/1234-ABCD/x.nup"), "media_rw");
        eq(null, Frontend.embeddedPath("/document/primary:ROMs/x.nup"), "SAF document id");
        eq(null, Frontend.embeddedPath(null), "null path");
        // SAF document IDs: the path after the volume, relative (most providers) or the whole path (the HyperOS picker, issue #19)
        String P = "/storage/emulated/0";
        eq(P + "/Switch2/x.nup", Frontend.volumePath(P, "Switch2/x.nup"), "relative to the volume");
        eq(P, Frontend.volumePath(P, ""), "the volume itself");
        eq(P + "/Switch2ROMS/x.nup", Frontend.volumePath(P, "/storage/emulated/0/Switch2ROMS/x.nup"), "whole path");
        eq(P + "/Switch2ROMS/x.nup", Frontend.volumePath(P, "storage/emulated/0/Switch2ROMS/x.nup"), "whole path without its first slash");
        eq("/sdcard/ROMs/x.nup", Frontend.volumePath(P, "/sdcard/ROMs/x.nup"), "whole path by an alias");
        eq("/storage/1234-ABCD/ROMs/x.nup", Frontend.volumePath("/storage/1234-ABCD", "storage/1234-ABCD/ROMs/x.nup"), "SD card, whole path");
        eq("/storage/1234-ABCD/ROMs/x.nup", Frontend.volumePath("/storage/1234-ABCD", "ROMs/x.nup"), "SD card, relative");
        eq(P + "/storage/x.nup", Frontend.volumePath(P, "storage/x.nup"), "a folder named storage stays relative");
        eq(P + "/sdcard/x.nup", Frontend.volumePath(P, "sdcard/x.nup"), "a folder named sdcard stays relative");
        // one name per file
        eq("/storage/emulated/0/ROMs/x.nup", Frontend.normal("/sdcard/ROMs/x.nup"), "/sdcard");
        eq("/storage/emulated/0/ROMs/x.nup", Frontend.normal("/storage/self/primary/ROMs/x.nup"), "self/primary");
        eq("/storage/emulated/0/ROMs/x.nup", Frontend.normal("/mnt/sdcard//ROMs/./x.nup"), "/mnt/sdcard, // and /./");
        eq("/storage/emulated/0", Frontend.normal("/sdcard/"), "the alias itself");
        eq("/storage/emulated/0/Games/dump", Frontend.normal("/storage/emulated/0/Games/dump/"), "trailing slash");
        eq("/sdcardx/x.nup", Frontend.normal("/sdcardx/x.nup"), "a name that only starts like an alias");
        eq("/storage/1234-ABCD/x.nup", Frontend.normal("/storage/1234-ABCD/x.nup"), "an SD card stays");
        eq("/storage/emulated/0/a/b", Frontend.normal("/storage/emulated/0/a/././b"), "/././ all go");
        // the card a path names
        List<String> cards = Arrays.asList("/storage/emulated/0/Games/a.nup", "/storage/emulated/0/Games/b-dump", "/storage/1234-ABCD/c.nup");
        eq(0, Frontend.card(cards, "/sdcard/Games/a.nup"), "alias of the first card");
        eq(1, Frontend.card(cards, "/storage/emulated/0/Games/b-dump/"), "folder with a slash");
        eq(2, Frontend.card(cards, "/storage/1234-ABCD/c.nup"), "SD card");
        eq(-1, Frontend.card(cards, "/storage/emulated/0/Games/A.nup"), "case matters");
        eq(true, Frontend.same("/sdcard/x", "/storage/self/primary/x"), "two aliases");
        eq(false, Frontend.same(null, "/x"), "null");
        // the paths another app may name: shared storage only, no "..", not Android/data or obb, not a volume's root
        eq("/storage/emulated/0/ROMs/x.nup", Frontend.sharedPath("/sdcard/ROMs/x.nup"), "alias becomes the primary name");
        eq("/storage/1234-ABCD/ROMs/x.nup", Frontend.sharedPath("/storage/1234-ABCD/ROMs/x.nup"), "SD card");
        eq("/mnt/media_rw/1234-ABCD/x.nup", Frontend.sharedPath("/mnt/media_rw/1234-ABCD/x.nup"), "media_rw");
        eq("/storage/emulated/0/ROMs/dump", Frontend.sharedPath("/storage/emulated/0/ROMs/./dump/"), "./ and trailing slash");
        eq(null, Frontend.sharedPath("/storage/emulated/0/../../../data/user/0/dev.ankanative.android/files/x.nup"), "traversal into the app's data");
        eq(null, Frontend.sharedPath("/storage/emulated/0/ROMs/../x.nup"), "any .. step");
        eq(null, Frontend.sharedPath("/data/user/0/dev.ankanative.android/files/packs/x"), "the app's own folder");
        eq(null, Frontend.sharedPath("/data/local/tmp/x.nup"), "outside shared storage");
        eq(null, Frontend.sharedPath("/proc/self/fd/3"), "proc");
        eq(null, Frontend.sharedPath("/storage/emulated/0/Android/data/org.evil/files/x.nup"), "Android/data");
        eq(null, Frontend.sharedPath("/sdcard/Android/obb/x/x.nup"), "Android/obb by an alias");
        eq("/storage/emulated/0/Android/media/x.nup", Frontend.sharedPath("/storage/emulated/0/Android/media/x.nup"), "Android/media is shared");
        eq(null, Frontend.sharedPath("/storage/emulated/0/Android/././data/org.x/f.nup"), "Android/data behind /././");
        eq(null, Frontend.sharedPath("/storage/emulated/0/Android/./././data/org.x/f.nup"), "Android/data behind three . steps");
        eq("/storage/emulated/0/ROMs/x.nup", Frontend.sharedPath("/storage/emulated/0/ROMs/././x.nup/."), "repeated . steps go");
        eq(null, Frontend.sharedPath("/storage/emulated/10/Android/data/org.x/f.nup"), "Android/data of another user");
        eq(null, Frontend.sharedPath("/storage/emulated/10/Android/obb/org.x/f.nup"), "Android/obb of another user");
        eq("/storage/emulated/10/ROMs/x.nup", Frontend.sharedPath("/storage/emulated/10/ROMs/x.nup"), "another user's shared storage");
        eq(null, Frontend.sharedPath("/storage/emulated/10"), "another user's root");
        eq(null, Frontend.sharedPath("/storage/1234-ABCD/Android/data/org.x/f.nup"), "an SD card's Android/data");
        eq(null, Frontend.sharedPath("/storage/emulated/0"), "the volume's root");
        eq(null, Frontend.sharedPath("/storage/1234-ABCD"), "an SD card's root");
        eq(null, Frontend.sharedPath("/storage"), "/storage");
        eq(null, Frontend.sharedPath("/storage/emulated/0/a\nb.nup"), "a control character");
        eq(null, Frontend.sharedPath("ROMs/x.nup"), "relative");
        eq(null, Frontend.sharedPath(null), "null");
        // the extra "game": a path or a file:// URI as a string; content:// is parsed as the data
        eq("/sdcard/x.nup", Frontend.extraPath(" /sdcard/x.nup "), "path, trimmed");
        eq("/storage/emulated/0/a b.nup", Frontend.extraPath("file:///storage/emulated/0/a%20b.nup"), "file URI string");
        eq(null, Frontend.extraPath("content://x/y"), "content URI is not a path");
        eq(null, Frontend.extraPath("x.nup"), "relative");
        eq(true, Frontend.contentUri(" Content://com.android.externalstorage.documents/document/primary%3Ax.nup"), "content URI string");
        eq(false, Frontend.contentUri("/sdcard/x.nup"), "a path is no content URI");
        // frontends' own providers: Pegasus's "ext" root is the primary storage, "root" carries the path
        eq("/storage/emulated/0/Download/switch/x.nup", Frontend.providerPath("org.pegasus_frontend.android.files", "/ext/Download/switch/x.nup"), "Pegasus ext");
        eq("/storage/emulated/0/ROMs/x.nup", Frontend.providerPath("org.pegasus_frontend.android.files", "/root/storage/emulated/0/ROMs/x.nup"), "Pegasus root");
        eq(null, Frontend.providerPath("org.evil", "/ext/Download/x.nup"), "ext of an unknown provider");
        eq(null, Frontend.sharedPath(Frontend.providerPath("org.pegasus_frontend.android.files", "/ext/../../../data/x")), "Pegasus ext with ..");
        // which card a request names: title ID first, else the file; a file that is no card names none (Home asks before adding it)
        List<String> ids = Arrays.asList("0400486024DEC000", "040074A01BF12000", "0400486024DEC000");
        eq(0, Frontend.target(cards, ids, "0400486024DEC000", null), "first card of the title");
        eq(1, Frontend.target(cards, ids, "040074a01bf12000", "/storage/1234-ABCD/c.nup"), "title ID wins over the path");
        eq(-1, Frontend.target(cards, ids, "0100000000010000", "/sdcard/Games/a.nup"), "unknown title: none, even when the path is a card");
        eq(2, Frontend.target(cards, ids, null, "/storage/1234-ABCD/c.nup"), "by path");
        eq(-1, Frontend.target(cards, ids, null, "/sdcard/Games/new.nup"), "a new file: no card");
        eq(-1, Frontend.target(cards, ids, null, null), "nothing");
        // text another app sent, as a dialog shows it: no control characters, at most Frontend.SHOWN characters
        eq("a b c", Frontend.shown("a\nb\u0000c"), "control characters become spaces");
        eq("x y", Frontend.shown("x\u2028y"), "line separator");
        String longText = "y".repeat(Frontend.SHOWN + 50);
        eq("y".repeat(Frontend.SHOWN) + "…", Frontend.shown(longText), "long text is cut");
        eq("y".repeat(Frontend.SHOWN), Frontend.shown("y".repeat(Frontend.SHOWN)), "text of the limit stays whole");
        eq(null, Frontend.shown(null), "null");
        // title IDs
        eq("040074A01BF12000", Frontend.titleId("040074a01bf12000"), "lower case");
        eq("040074A01BF12000", Frontend.titleId(" 0x040074A01BF12000 "), "0x and spaces");
        eq(null, Frontend.titleId("040074A01BF1200"), "15 digits");
        eq(null, Frontend.titleId("040074A01BF1200G"), "not hex");
        eq(null, Frontend.titleId(null), "null");
        token();
        play();
        System.out.println("ok " + n);
    }

    /** The one-time token that hands Home a frontend's game (Frontend.handOff / take): only the newest token, once; no other value names a game */
    private static void token() {
        eq(null, Frontend.take(null), "no token, nothing handed");
        String t = Frontend.handOff("/storage/emulated/0/Games/a.nup", true);
        eq(null, Frontend.take(null), "no token");
        eq(null, Frontend.take(""), "an empty token");
        eq(null, Frontend.take(t.toUpperCase(java.util.Locale.ROOT).equals(t) ? t + "x" : t.toUpperCase(java.util.Locale.ROOT)), "a token of another case");
        eq(null, Frontend.take(t.substring(1)), "a part of the token");
        eq(null, Frontend.take("/storage/emulated/0/Games/a.nup"), "the path as the token");
        eq(Arrays.asList("/storage/emulated/0/Games/a.nup", "card"), Arrays.asList(Frontend.take(t)), "the token, once");
        eq(null, Frontend.take(t), "used: never again");
        String old = Frontend.handOff("/storage/emulated/0/Games/b.nup", false), now = Frontend.handOff("/storage/emulated/0/Games/c.nup", false);
        eq(false, old.equals(now), "a new token each hand-off");
        eq(null, Frontend.take(old), "a replaced token is dead");
        eq(Arrays.asList("/storage/emulated/0/Games/c.nup", "new"), Arrays.asList(Frontend.take(now)), "the newest token: a new file");
        eq(null, Frontend.take(now), "the newest, used");
    }

    /** A card's facts as PlayRule reads them; asked records what it asked, in order */
    private static final class Card implements PlayRule.Facts {
        boolean access = true, saveBusy, packBusy, placeholder, playable, legacy;
        Msg noProfile, otherTitle, blocking, library;
        IOException unreadable;
        final List<String> asked = new ArrayList<>();

        @Override public boolean access() { asked.add("access"); return access; }
        @Override public Msg noProfile() { asked.add("profile"); return noProfile; }
        @Override public boolean saveBusy() { asked.add("save"); return saveBusy; }
        @Override public boolean packBusy() { asked.add("packop"); return packBusy; }
        @Override public void readPack() throws IOException { asked.add("read"); if (unreadable != null) throw unreadable; }
        @Override public boolean placeholder() { asked.add("placeholder"); return placeholder; }
        @Override public Msg otherTitle() { asked.add("title"); return otherTitle; }
        @Override public Msg blocking() { asked.add("build"); return blocking; }
        @Override public Msg libraryProblem() { asked.add("library"); return library; }
        @Override public boolean playableLibrary() { asked.add("playable"); return playable; }
        @Override public boolean legacyLibrary() { asked.add("legacy"); return legacy; }
    }

    private static PlayRule.Kind kind(Card c) { return PlayRule.decide(c).kind; }

    /**
     * PlayRule, the one decision of Home's Play (MainActivity.launch) and a frontend's request (FrontendLaunch): which libraries start straight away, and that
     * everything else is one of Play's steps; the busy and access checks come before anything of the pack is read
     */
    private static void play() {
        Msg older = new Msg("x_older"), noLib = new Msg("x_none");
        Card c = new Card();
        eq(PlayRule.Kind.START, kind(c), "this version's library");
        eq(Arrays.asList("access", "profile", "save", "packop", "read", "placeholder", "title", "build", "library"), c.asked, "Play's order");
        c = new Card(); c.library = older; c.playable = true;
        PlayRule.Verdict v = PlayRule.decide(c);
        eq(PlayRule.Kind.START_OLDER, v.kind, "an older library that plays while it updates (the owner's iiSU start, 2026-11-14)");
        eq(older, v.why, "the library's problem is kept for the log");
        c = new Card(); c.library = older; c.legacy = true;
        eq(PlayRule.Kind.START_LEGACY, kind(c), "a library from before versioned updates");
        c = new Card(); c.library = older; c.playable = true; c.legacy = true;
        eq(PlayRule.Kind.START_OLDER, kind(c), "playable wins over legacy, as in Play");
        c = new Card(); c.library = noLib;
        v = PlayRule.decide(c);
        eq(PlayRule.Kind.NO_LIBRARY, v.kind, "no library it can play with");
        eq(noLib, v.why, "why, for Play's dialog");
        c = new Card(); c.access = false;
        eq(PlayRule.Kind.NO_ACCESS, kind(c), "no All files access");
        eq(Arrays.asList("access"), c.asked, "nothing else asked without access");
        c = new Card(); c.noProfile = new Msg("x_profile");
        eq(PlayRule.Kind.NO_PROFILE, kind(c), "a game this APK has no host for");
        c = new Card(); c.saveBusy = true;
        eq(PlayRule.Kind.SAVE_BUSY, kind(c), "a save backup or restore working");
        eq(false, c.asked.contains("read"), "a busy save: the pack is not read");
        c = new Card(); c.packBusy = true;
        eq(PlayRule.Kind.PACK_BUSY, kind(c), "a pack export or import working");
        eq(false, c.asked.contains("read"), "a busy pack: the pack is not read");
        c = new Card(); c.unreadable = new IOException("no pack.tsv");
        v = PlayRule.decide(c);
        eq(PlayRule.Kind.PACK_UNREADABLE, v.kind, "no pack");
        eq("no pack.tsv", v.error.getMessage(), "the read error, for Play's dialog");
        eq(false, c.asked.contains("placeholder"), "nothing of an unread pack is asked");
        c = new Card(); c.placeholder = true; c.library = null;
        eq(PlayRule.Kind.PLACEHOLDER, kind(c), "a placeholder pack, even with a library");
        c = new Card(); c.otherTitle = new Msg("x_title");
        eq(PlayRule.Kind.OTHER_TITLE, kind(c), "a pack of another title");
        c = new Card(); c.blocking = new Msg("x_build"); c.library = older; c.playable = true;
        eq(PlayRule.Kind.PACK_BLOCKED, kind(c), "a pack of another game build, even with a playable library");
        eq(false, c.asked.contains("library"), "a blocked pack: its library is not asked");
        // straight: exactly the three starts
        EnumSet<PlayRule.Kind> straight = EnumSet.noneOf(PlayRule.Kind.class);
        for (PlayRule.Kind k : PlayRule.Kind.values()) if (k.straight()) straight.add(k);
        eq(EnumSet.of(PlayRule.Kind.START, PlayRule.Kind.START_OLDER, PlayRule.Kind.START_LEGACY), straight, "what starts with no dialog");
        // FrontendLaunch's choice (Frontend.route): a card starts from the frontend exactly when Play starts it with no dialog, for every verdict
        for (PlayRule.Kind k : PlayRule.Kind.values()) {
            eq(k.straight() ? Frontend.Route.START : Frontend.Route.PLAY_ON_HOME, Frontend.route(true, false, k), "a card by its file, " + k);
            eq(k.straight() ? Frontend.Route.START : Frontend.Route.PLAY_ON_HOME, Frontend.route(true, true, k), "a card by its title ID, " + k);
        }
        c = new Card(); c.library = older; c.playable = true;
        eq(Frontend.Route.START, Frontend.route(true, false, kind(c)), "the owner's iiSU start (an older library that plays while it updates) starts at once");
        eq(Frontend.Route.ADD_ON_HOME, Frontend.route(false, false, null), "a file that is no card: Home asks before Add game");
        eq(Frontend.Route.NO_CARD, Frontend.route(false, true, null), "a title ID no card has");
    }
}
