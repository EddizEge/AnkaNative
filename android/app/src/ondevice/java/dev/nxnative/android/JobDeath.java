package dev.nxnative.android;

/**
 * What the app does when the Prepare job's process (:pack) has ended without the job ending: a low-memory kill, a kill -9, the app's own end of a tool that did
 * not stop. No Android classes: the JVM test (android/app/src/jvmtest JobDeathJvmTest, dm/tools/tests/test_android_job_death.py) runs it.
 * <p>
 * The service returns START_STICKY, but on HyperOS (POCO F8 Pro, Android 16) Android never restarted it: "AutoStartManagerService: MIUILOG- Reject
 * RestartService" after every such end in the phone test of 2026-11-14 (a kill -9, the 20-s fallback, two lmkd kills), and the job stood still, its notification
 * gone, until the app was opened and Resume tapped. So the app starts it again itself where Android lets it:
 * <ul>
 * <li>the app's main process, when it lives, sees the service's process end (JobWatch: a binding of the service) and starts the job again; from the background
 *     Android 12+ refuses a foreground service's start (ForegroundServiceStartNotAllowedException) unless an exemption holds, and then the app posts a
 *     notification that says Prepare stopped, which opens the app and resumes it;</li>
 * <li>Home, when it opens and finds the job's service gone (Ondevice.Panel.afterDeath), starts it again: its activity is in front, which Android allows;</li>
 * <li>a game that ends while the job waits for it starts it again (PrepareGate.afterGame), its activity still in front.</li>
 * </ul>
 * A job the user paused (or Android's daily limit, or a start Android refused) stays paused, and a process ended at the user's request (ApplicationExitInfo's
 * REASON_USER_REQUESTED: Force stop, or removing the app from Recents where the phone ends it then) is offered on Home's card as before, not resumed by itself.
 */
final class JobDeath {
    private JobDeath() {}

    /**
     * ApplicationExitInfo.REASON_USER_REQUESTED: the user asked for the app's end (its documentation's examples: Settings' "Force stop", removing the app from
     * Recents; before Android 14 also an update of the app), and none known (EXIT_UNKNOWN: no record, or the process is not among them)
     */
    static final int EXIT_UNKNOWN = -1, EXIT_USER_REQUESTED = 10;

    enum Act {
        /** nothing to do: the job is done, failed or paused for good, or it waits for a game that still runs (the game's end starts it, PrepareGate.afterGame) */
        NOTHING,
        /** start the job again (it was running, or waiting by itself for the battery, the heat or a game) */
        RESUME,
        /** the user stopped the app: Home's card offers Resume, nothing starts by itself */
        OFFER
    }

    /** Whether the job should be working: running, or paused by the service's own policy (it goes on by itself) */
    static boolean shouldWork(String state, String reason) { return "running".equals(state) || "paused".equals(state) && JobForm.waitsByItself(reason); }

    /**
     * state, reason: job.json's; worker: a service's worker runs the job now (then nothing died); exit: the ended process's ApplicationExitInfo reason
     * (EXIT_UNKNOWN when there is none); gameRuns: a game's process (:game) runs
     */
    static Act after(String state, String reason, boolean worker, int exit, boolean gameRuns) {
        if (worker || !shouldWork(state, reason)) return Act.NOTHING;
        if (GameGate.REASON.equals(reason) && !GameGate.resumeAfterGame(state, reason, false, gameRuns)) return Act.NOTHING;
        if (exit == EXIT_USER_REQUESTED) return Act.OFFER;
        return Act.RESUME;
    }

    /**
     * Whether, some seconds after the app started the job again by itself, it did not come back and the user is to be told (the stopped notification): the job
     * should still be working and no worker runs it, or the service took it and Android refused it the foreground ("refused")
     */
    static boolean notBack(String state, String reason, boolean worker) {
        return !worker && (shouldWork(state, reason) || "paused".equals(state) && "refused".equals(reason));
    }

    /** An automatic start (the main process's watch, Home's open, a game's end) may follow another only after this long: one at a time, never a loop */
    static final long MIN_GAP_MS = 10_000;

    static boolean mayStart(long now, long lastAuto) { return now - lastAuto >= MIN_GAP_MS; }

    /**
     * Whether a start the service is asked for goes on with the job. intent: false for a sticky restart (no intent); auto: the app's own automatic start
     * (JobWatch, Home's open, a game's end), which, like a sticky restart, resumes only a job that should be working; any other start (Start, Resume, Retry, the
     * notification's Resume) goes on with a job that is not done, or one another APK made
     */
    static boolean go(boolean intent, boolean auto, String state, String reason, boolean apkChanged) {
        if (!intent || auto) return shouldWork(state, reason);
        return !"done".equals(state) || apkChanged;
    }
}
