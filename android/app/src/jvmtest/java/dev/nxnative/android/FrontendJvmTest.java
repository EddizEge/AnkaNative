package dev.nxnative.android;

import java.util.Arrays;
import java.util.List;

/**
 * What an emulator frontend sends FrontendLaunch, read by Frontend on the JVM (no Android): file:// URIs, provider URIs that carry a /storage path, the shared
 * storage's aliases, the card a path names, title IDs. Run by dm/tools/tests/test_android_frontend.py; prints "ok <n>" and exits 0, or the first failure and exits 1.
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
        // title IDs
        eq("040074A01BF12000", Frontend.titleId("040074a01bf12000"), "lower case");
        eq("040074A01BF12000", Frontend.titleId(" 0x040074A01BF12000 "), "0x and spaces");
        eq(null, Frontend.titleId("040074A01BF1200"), "15 digits");
        eq(null, Frontend.titleId("040074A01BF1200G"), "not hex");
        eq(null, Frontend.titleId(null), "null");
        System.out.println("ok " + n);
    }
}
