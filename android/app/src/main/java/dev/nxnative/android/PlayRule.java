package dev.nxnative.android;

import java.io.IOException;

/**
 * Play's one decision: whether a card's game starts straight away, and if not, which of Play's steps comes first. Both callers take it from here, so they cannot
 * decide differently: Home's Play button (MainActivity.launch) and an emulator frontend's request (FrontendLaunch, which starts the game itself only when the
 * verdict is straight() and else hands the card to Home, which runs launch). Plain Java over the card's facts (Play.facts reads them from the card's folders),
 * checked on the JVM by FrontendJvmTest. The facts are asked in Play's order and only as far as needed: nothing of the pack is read while a save or pack
 * operation of the title is working.
 */
final class PlayRule {
    private PlayRule() {}

    enum Kind {
        NO_ACCESS,        // no All files access: Home asks for it
        NO_PROFILE,       // a game this APK has no host for (why)
        SAVE_BUSY,        // a save backup or restore of the title is working
        PACK_BUSY,        // a pack export or import of the title is working
        PACK_UNREADABLE,  // the pack folder does not read (error)
        PLACEHOLDER,      // a simulated engine's placeholder pack (Anka Native 0.1.0's Prepare): it never starts
        OTHER_TITLE,      // the pack is of another title than the card (why)
        PACK_BLOCKED,     // the pack's code is unfinished, being rewritten, of another game build or of one this APK does not support (why: PackCheck.blockingMsg)
        NO_LIBRARY,       // no shader library the game can play with (why: Pack.libraryProblemMsg); a dump is prepared, another pack is started only by "Start anyway"
        START,            // this version's library: starts
        START_OLDER,      // an older library of the same ABI that plays while the automatic shader update makes this version's (Pack.playableLibrary)
        START_LEGACY;     // a library made before versioned updates: starts with a warning (Pack.legacyLibrary)

        /** Whether Play starts the game with no dialog in front (a toast at most) */
        boolean straight() { return this == START || this == START_OLDER || this == START_LEGACY; }
    }

    /** What the decision reads of a card, in the order it asks; the pack's facts are asked only after readPack returned */
    interface Facts {
        boolean access();
        Msg noProfile();
        boolean saveBusy();
        boolean packBusy();
        void readPack() throws IOException;
        boolean placeholder();
        Msg otherTitle();
        Msg blocking();
        Msg libraryProblem();
        boolean playableLibrary();
        boolean legacyLibrary();
    }

    /** The decision: its kind, the sentence that says why (a Msg, for NO_PROFILE, OTHER_TITLE, PACK_BLOCKED, NO_LIBRARY and START_OLDER / START_LEGACY's library problem), the pack's read error */
    static final class Verdict {
        final Kind kind;
        final Msg why;
        final IOException error;

        Verdict(Kind kind, Msg why, IOException error) { this.kind = kind; this.why = why; this.error = error; }
    }

    static Verdict decide(Facts f) {
        if (!f.access()) return new Verdict(Kind.NO_ACCESS, null, null);
        Msg m = f.noProfile();
        if (m != null) return new Verdict(Kind.NO_PROFILE, m, null);
        if (f.saveBusy()) return new Verdict(Kind.SAVE_BUSY, null, null);
        if (f.packBusy()) return new Verdict(Kind.PACK_BUSY, null, null);
        try {
            f.readPack();
        } catch (IOException e) {
            return new Verdict(Kind.PACK_UNREADABLE, null, e);
        }
        if (f.placeholder()) return new Verdict(Kind.PLACEHOLDER, null, null);
        if ((m = f.otherTitle()) != null) return new Verdict(Kind.OTHER_TITLE, m, null);
        // The pack's code must be of the game build in the folder (else the game crashes at once), and of a build this app supports
        if ((m = f.blocking()) != null) return new Verdict(Kind.PACK_BLOCKED, m, null);
        // The host loads <pack>/shaders/v-<its DMSH_VK_CHAIN>/dmsh_vk.lib: without one it can play, the game runs but draws nothing
        if ((m = f.libraryProblem()) == null) return new Verdict(Kind.START, null, null);
        if (f.playableLibrary()) return new Verdict(Kind.START_OLDER, m, null);   // the older library plays while the update makes this version's (vka_shader.c lib_load)
        if (f.legacyLibrary()) return new Verdict(Kind.START_LEGACY, m, null);    // made before versioned updates: never blocks Play, says so (the host warns in its log too)
        return new Verdict(Kind.NO_LIBRARY, m, null);
    }
}
