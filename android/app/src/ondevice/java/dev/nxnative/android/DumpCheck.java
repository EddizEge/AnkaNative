package dev.nxnative.android;

import android.os.StatFs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether a picked folder is a dump this APK can prepare: a Switch 2 dump folder (scripts/pack_dump.py's layout: base/, updates/&lt;version&gt;/, dlc/) or a .nup
 * (NUP.md), with the base alone, an update alone or both (DumpLayout.find), of one of the APK's games (Profile) with one of its profile's builds (game.tsv build
 * rows, Profile.buildMap), checked by the NSO build IDs of the version's modules; then the free space beside it. Reads a few headers only; a refusal says why in
 * a sentence the user can act on.
 */
final class DumpCheck {
    /** Free space the job needs beyond the dump (ONDEVICE.md "Storage": a peak of about 1.5 GB, with the library briefly twice) */
    static final long NEED_BYTES = 2048L << 20;

    static final class Result {
        File dump, work;
        String titleId;
        /** Why the dump cannot be prepared, in English (job.json's error, logs), null when it can; errorMsg: the same for the screen; code: DumpCheck's own
         *  refusal as PrepareErrors stores it in job.json ({code, argument...}), null for DumpLayout's */
        String error;
        Msg errorMsg;
        String[] code;
        long version;               // the update's version (updates/<version>)
        long build = -1;            // the profile's build (its build row's version) the version's code is: another number when the dump keeps that build under another version
        Map<String, String> buildIds = new LinkedHashMap<>();   // module → its NSO build ID (32 hex digits)
        int dlc;
        long free;

        boolean ok() { return error == null; }

        /** error for the screen, in the user's language (DumpCheck's own refusals and DumpLayout's) */
        String errorText() { return errorMsg != null ? errorMsg.text() : error; }
    }

    private DumpCheck() {}

    /**
     * A path inside one version of a dump, as the tools take it: a folder's &lt;dump&gt;/updates/&lt;version&gt;/&lt;sub&gt; (base/ for 0), a .nup's
     * &lt;dump&gt;.nup:update/&lt;version&gt;/&lt;sub&gt; (base for 0; NUP.md "Naming a package"); the base's when the version has no such part of its own (DumpLayout)
     */
    static String versionPath(File dump, long version, String sub) { return DumpLayout.path(dump, version, sub); }

    /** The work folder a dump's job uses: <dump>/../<title ID>-work (the Mac's layout; the pack goes to its android-pack) */
    static File workOf(File dump, String titleId) {
        File parent = dump.getAbsoluteFile().getParentFile();
        return new File(parent != null ? parent : dump, titleId + "-work");
    }

    /**
     * The dump, its work folder and the free space there (r.free). The free space is not checked here: whether the pick needs it depends on the job it takes
     * (JobPick.needsFree, then requireFree), which needs this result's work folder and version first
     */
    static Result check(File dump) {
        Result r = new Result();
        r.dump = dump;
        DumpLayout.Found f = DumpLayout.find(dump);
        r.titleId = f.titleId;
        if (f.error != null) {
            r.error = f.error;
            r.errorMsg = f.errorMsg;
            return r;
        }
        r.version = f.version;
        r.build = f.build;
        for (Map.Entry<String, String> e : f.buildIds.entrySet()) r.buildIds.put(e.getKey(), e.getValue());
        r.dlc = f.dlc;
        coded(r, space(r));
        return r;
    }

    /**
     * A new job's free-space check (JobPick.needsFree): NEED_BYTES free in the work folder, else r is refused (dump_space). A job that is resumed has written
     * most of what it needs already and is not held to it
     */
    static void requireFree(Result r) {
        if (r.ok() && r.free < NEED_BYTES) coded(r, PrepareErrors.coded("dump_space", NEED_BYTES / 1073741824.0, r.work, r.free / 1073741824.0));
    }

    private static void coded(Result r, String[] code) {
        r.code = code;
        r.errorMsg = PrepareErrors.message(r.code);
        r.error = r.errorMsg == null ? null : r.errorMsg.english();
    }

    /**
     * The work folder beside the dump, its free space (r.free; checked by requireFree, for a new job) and its ondevice/ folder (a folder dump and a .nup
     * alike); null when they fit, else why not (PrepareErrors' {code, argument...}). The dump's layout and builds were checked before (DumpLayout.find: the
     * base, an update, or both; the version whose modules are a profile build)
     */
    private static String[] space(Result r) {
        File dump = r.dump;
        r.work = workOf(dump, r.titleId);
        File probe = r.work.isDirectory() ? r.work : r.work.getParentFile();
        try {
            r.free = new StatFs(probe.getPath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return PrepareErrors.coded("dump_free_unknown", probe);
        }
        File od = new File(r.work, "ondevice");
        if (!od.isDirectory() && !od.mkdirs()) return PrepareErrors.coded("dump_not_writable", od);
        return null;
    }

    /** An NSO's module (build) ID, 32 bytes at 0x40, as 64 lower-case hex digits */
    static String nsoBuildId(File nso) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(nso, "r")) {
            byte[] h = new byte[0x60];
            f.readFully(h);
            return nsoBuildId(h, nso.getName());
        }
    }

    /** The same, of an NSO's first 0x60 bytes (a .nup's file: Nup.read) */
    static String nsoBuildId(byte[] h, String name) throws IOException { return BuildCheck.nsoBuildId(h, name); }
}
