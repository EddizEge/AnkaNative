package dev.nxnative.android;

/**
 * A game and this app's Prepare job, which must not work at the same time (the phone test of 2026-11-14: a game started beside a running Prepare or shader
 * update ran a 12 GB phone out of memory, and about 30 s after the game was in, lmkd ended the job's process and the user's background apps). While a game
 * runs the job waits, paused with its own reason "game", and goes on by itself when the game's process is gone, as the battery and heat waits do; a job the
 * user paused stays paused. Plain Java over job.json's state and reason and what the service knows of itself, checked on the JVM by GameGateJvmTest
 * (dm/tools/tests/test_android_game_gate.py). Its callers: GameActivity (PrepareGate, in the game's process, before the host starts), PackService (ACTION_GAME
 * and its wait), Home's Preparing card (Ondevice: a job left waiting for a game) and Home's game cards (MainActivity.check).
 * <p>
 * Why the job's process ends instead of waiting beside the game: after the shaders step's tool has run, the process keeps what the tool left (the translator's
 * heap, Python's arenas): about 0.55 GB on the phone (the pack step's samples) and 0.56 GB on a computer in the phone's form right after a stopped
 * translation, against the 1.1-1.4 GB the game alone leaves free on a 12 GB phone; the kills came at 0.83-1.24 GB. A new process that has run no tool waits
 * for the game with tens of MB (android/README.md, "A game and Prepare").
 */
final class GameGate {
    private GameGate() {}

    /** The reason (job.json "reason") of a job waiting while a game runs */
    static final String REASON = "game";

    /** Whether a game about to start must first have the job paused: the job may be working (running, or waiting by itself for the battery, the heat or a game) */
    static boolean pauseFirst(String state, String reason) {
        return "running".equals(state) || "paused".equals(state) && JobForm.waitsByItself(reason);
    }

    /**
     * Whether a game about to start asks the job's service first (PrepareGate.hold): the job may be working (pauseFirst), or it is paused by the user or by
     * Android's daily limit while the process that ran it still runs. Such a pause is written into job.json at once (PackService.requestStop), before its tool
     * has stopped, which takes up to 20 s; the game used to start at once on that job.json, beside the tool still running, and without asking, so the process
     * the tool ran in was not ended for the game either (the review of work/rc1-fixes). Asked, the service answers once its worker has ended (PackService.game:
     * NOTHING, then game_ready), and a process that ran a tool ends then. jobProcessRuns: a :pack process with the job's pid runs (any :pack process when
     * job.json has no pid)
     */
    static boolean holds(String state, String reason, boolean jobProcessRuns) {
        return pauseFirst(state, reason) || jobProcessRuns && "paused".equals(state) && ("user".equals(reason) || "timeout".equals(reason));
    }

    /** What the service does when a game is about to start (ACTION_GAME) */
    enum Act {
        /** nothing works or will: the game starts at once */
        NOTHING,
        /** the job waits for the game in this process (it has run no tool: little memory), starting a worker for it when there is none */
        WAIT,
        /** the job's tool stops and then this process ends (it has run a tool and keeps what it left); the game starts the service again, which then waits */
        STOP_AND_END
    }

    /**
     * worker: the service's worker runs a job; ranTool: a tool has run in this process; stopping: a pause the user asked for (or Android's daily limit) is in
     * hand, which stays a pause: the worker ends and the service with it. state, reason: the job's (the worker's, else the remembered job.json's); exit: with
     * no worker, the ApplicationExitInfo reason of the job's last process (JobDeath.EXIT_UNKNOWN when none).
     * <p>
     * With no worker, a job that says running is one whose process died. The game's start resumes it (a new worker waits for the game, then goes on) only
     * where Home's open would (JobDeath.after): not after a stop the user asked for (Force stop, or a swipe from Recents the phone records as such), which
     * leaves it for the user's Resume. A job waiting by itself (the battery, the heat, a game) was written so by the service and waits again
     */
    static Act onGame(boolean worker, boolean ranTool, boolean stopping, String state, String reason, int exit) {
        if (!worker) {
            if ("running".equals(state)) return JobDeath.after(state, reason, false, exit, false) == JobDeath.Act.RESUME ? Act.WAIT : Act.NOTHING;
            return pauseFirst(state, reason) ? Act.WAIT : Act.NOTHING;
        }
        if (stopping) return Act.NOTHING;
        return ranTool ? Act.STOP_AND_END : Act.WAIT;
    }

    /**
     * The reason a job is written paused with when its tool did not stop within 20 s for a game (PackService.gamePause's fallback ends the process): a pause the
     * user asked for meanwhile (stopReason: user, timeout) stays that pause, else the job waits for the game. That fallback was posted before the Pause's own,
     * so it fires first, and it wrote "game" over the user's Pause: the job then went on by itself after the game
     */
    static String fallbackReason(String stopReason) { return stopReason != null ? stopReason : REASON; }

    /**
     * Whether the game may start now (the service's "game_ready"): no worker, or one that waits for the game in a process that has run no tool. worker: the
     * service's worker runs a job; waiting: it waits for the game (PackService.waitWhileAuto, reason "game")
     */
    static boolean ready(boolean worker, boolean waiting, boolean ranTool) { return !worker || waiting && !ranTool; }

    /** Whether the worker, waiting for a game, ends its process: it has run a tool, and the game asked (ACTION_GAME), so the game starts the service again */
    static boolean endProcess(boolean ranTool, boolean gameAsked) { return ranTool && gameAsked; }

    /**
     * Whether Home resumes a job left paused for a game by itself (the service's process died while the game ran, or the game could not start it again): no game
     * runs, and no worker of the service runs it
     */
    static boolean resumeAfterGame(String state, String reason, boolean worker, boolean gameRuns) {
        return "paused".equals(state) && REASON.equals(reason) && !worker && !gameRuns;
    }

    /** The game card's state for a pack whose older library plays while the shader update makes this version's */
    enum Line {
        /** "Ready · updating shaders": the update of this card's pack runs, waits for the battery or the heat, or was just started */
        UPDATING,
        /** "Ready · shader update paused while a game runs" */
        GAME,
        /** "Ready · shader update paused": paused by the user, Android's daily limit or a start Android refused, or its process died (Resume on the Preparing card) */
        PAUSED,
        /** "Ready · older shaders": no update of this card's pack is queued or running (none started, one written that waits for Start, or it stopped with an error) */
        OLDER
    }

    /**
     * queued: the card's check started the update just now (Ondevice.autoUpdate); state, reason: the Prepare job's when it is this card's pack's, else null;
     * worker: the service's worker runs that job (a job.json that says running or waiting with no worker is a process that died)
     */
    static Line card(boolean queued, String state, String reason, boolean worker) {
        if (queued) return Line.UPDATING;   // started or resumed just now (Ondevice.autoUpdate); the card is made again as the job goes on (Ondevice show)
        if (state != null) {
            if ("pending".equals(state)) return worker ? Line.UPDATING : Line.OLDER;   // with no worker: written, waiting for the user's Start (Prepare again)
            if ("running".equals(state)) return worker ? Line.UPDATING : Line.PAUSED;
            if ("paused".equals(state)) {
                if (REASON.equals(reason)) return Line.GAME;   // with no worker too: Home resumes it once no game runs (resumeAfterGame)
                return JobForm.waitsByItself(reason) && worker ? Line.UPDATING : Line.PAUSED;
            }
        }
        return Line.OLDER;
    }
}
