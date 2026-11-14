package dev.nxnative.android;

/**
 * A game and this app's Prepare job, which must not work at the same time (the phone test of 2026-11-14: a game started beside a running Prepare or shader
 * update ran a 12 GB phone out of memory, and about 30 s after the game was in, lmkd ended the job's process and the user's background apps). While a game
 * runs the job waits, paused with its own reason "game", and goes on by itself when the game's process is gone, as the battery and heat waits do; a job the
 * user paused stays paused. Plain Java over job.json's state and reason and what the service knows of itself, checked on the JVM by GameGateJvmTest
 * (dm/tools/tests/test_android_game_gate.py). Its callers: GameActivity (PrepareGate, in the game's process, before the host starts), PackService (ACTION_GAME
 * and its wait) and Home's Preparing card (Ondevice: a job left waiting for a game).
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
     * hand, which stays a pause: the worker ends and the service with it. state, reason: the job's (the worker's, else the remembered job.json's)
     */
    static Act onGame(boolean worker, boolean ranTool, boolean stopping, String state, String reason) {
        if (!worker) return pauseFirst(state, reason) ? Act.WAIT : Act.NOTHING;
        if (stopping) return Act.NOTHING;
        return ranTool ? Act.STOP_AND_END : Act.WAIT;
    }

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
}
