package dev.nxnative.android;

/**
 * GameGate on the JVM: a game and this app's Prepare job, which must not work at the same time. The case it was made for (the phone test of 2026-11-14): a game
 * started beside a running Prepare or shader update (from a frontend, or Play during the automatic update) ran a 12 GB phone out of memory, and about 30 s
 * after the game was in, lmkd ended the job's process and the user's background apps; the game card said "Ready · updating shaders" when no update was
 * queued. Prints "ok" lines, exits 1 on the first failed check. Run by dm/tools/tests/test_android_game_gate.py.
 */
public final class GameGateJvmTest {
    private static void check(boolean ok, String what) {
        if (!ok) { System.out.println("FAIL " + what); System.exit(1); }
        System.out.println("ok " + what);
    }

    private static <T> void same(T got, T want, String what) { check(got == want, what + " (" + got + ", want " + want + ")"); }

    public static void main(String[] a) {
        // Before the game starts: whether it asks the job to pause first
        check(GameGate.pauseFirst("running", ""), "a running job pauses first");
        check(GameGate.pauseFirst("paused", "battery") && GameGate.pauseFirst("paused", "thermal"), "a job waiting for the battery or the heat pauses first (it would go on beside the game)");
        check(GameGate.pauseFirst("paused", "game"), "a job waiting for another game: asked again (the game that ended may not be this one)");
        for (String r : new String[] {"user", "timeout", "refused", "apk", ""})
            check(!GameGate.pauseFirst("paused", r), "a job paused for good (" + r + ") stays as it is: nothing to pause");
        for (String st : new String[] {"done", "failed", "pending", ""}) check(!GameGate.pauseFirst(st, ""), "no job works (" + st + "): the game starts at once");
        check(JobForm.waitsByItself("game"), "a wait for a game goes on by itself, as the battery and heat waits do");

        // The service, asked by the game (ACTION_GAME)
        same(GameGate.onGame(true, true, false, "running", ""), GameGate.Act.STOP_AND_END, "the translation runs: its tool stops and the process ends (it keeps ~0.55 GB)");
        same(GameGate.onGame(true, true, false, "paused", "battery"), GameGate.Act.STOP_AND_END, "a worker waiting for the battery after a tool ran: the process ends too");
        same(GameGate.onGame(true, false, false, "running", ""), GameGate.Act.WAIT, "a worker that has run no tool here (a new process): it waits as it is");
        same(GameGate.onGame(true, true, true, "paused", "user"), GameGate.Act.NOTHING, "a Pause in hand stays a pause: the worker ends by itself");
        same(GameGate.onGame(false, false, false, "running", ""), GameGate.Act.WAIT, "a running job whose process died (no worker): a new worker waits for the game, then resumes it");
        same(GameGate.onGame(false, false, false, "paused", "game"), GameGate.Act.WAIT, "the process ended for the game, the game starts the service again: it waits");
        same(GameGate.onGame(false, false, false, "paused", "user"), GameGate.Act.NOTHING, "a job the user paused stays paused");
        same(GameGate.onGame(false, false, false, "", ""), GameGate.Act.NOTHING, "no job");

        // When the game may start
        check(GameGate.ready(false, false, true), "no worker: the game starts");
        check(GameGate.ready(true, true, false), "the worker waits for the game in a process that ran no tool: the game starts");
        check(!GameGate.ready(true, true, true), "the worker waits but a tool ran here: not yet (the process ends first)");
        check(!GameGate.ready(true, false, false), "the worker still works (the tool stopping): not yet");
        check(GameGate.endProcess(true, true), "a tool ran and the game asked: the waiting worker ends the process");
        check(!GameGate.endProcess(true, false), "a game seen only by the watch (it did not ask, so it would not start the service again): the worker waits as it is");
        check(!GameGate.endProcess(false, true), "no tool ran: it waits as it is");

        // Home, after the game: a job whose waiting service died
        check(GameGate.resumeAfterGame("paused", "game", false, false), "paused for a game, no worker, no game: Home resumes it");
        check(!GameGate.resumeAfterGame("paused", "game", false, true), "the game still runs: it waits");
        check(!GameGate.resumeAfterGame("paused", "game", true, false), "its worker runs: it goes on by itself");
        check(!GameGate.resumeAfterGame("paused", "user", false, false), "paused by the user: stays paused");

        // The game card of a pack whose older library plays while the update makes this version's
        same(GameGate.card(false, null, null, false), GameGate.Line.OLDER, "no update of this pack queued or running: not \"updating shaders\" (the phone test's line)");
        same(GameGate.card(true, null, null, false), GameGate.Line.UPDATING, "the card's check just started its update");
        same(GameGate.card(false, "running", "", true), GameGate.Line.UPDATING, "its update runs");
        same(GameGate.card(false, "pending", "", false), GameGate.Line.UPDATING, "its update is written and starting");
        same(GameGate.card(false, "running", "", false), GameGate.Line.PAUSED, "running in job.json, its process dead: not updating");
        same(GameGate.card(false, "paused", "game", true), GameGate.Line.GAME, "waiting while a game runs");
        same(GameGate.card(false, "paused", "game", false), GameGate.Line.GAME, "left waiting for a game (Home resumes it once the game is gone)");
        same(GameGate.card(false, "paused", "thermal", true), GameGate.Line.UPDATING, "waiting for the heat: it goes on by itself");
        same(GameGate.card(false, "paused", "battery", false), GameGate.Line.PAUSED, "a battery wait whose process died");
        same(GameGate.card(false, "paused", "user", false), GameGate.Line.PAUSED, "paused by the user");
        same(GameGate.card(false, "failed", "", false), GameGate.Line.OLDER, "its update failed: older shaders");
        same(GameGate.card(true, "paused", "user", false), GameGate.Line.UPDATING, "a paused update the card's check resumed just now");
        System.out.println("all ok");
    }
}
