package dev.nxnative.android;

/**
 * Which form the Prepare notification and the card's state take for a job, from its job.json state and reason and what the service's worker is doing. No Android
 * classes: the JVM test (android/app/src/jvmtest JobFormJvmTest, dm/tools/tests/test_android_job_form.py) runs it.
 * <p>
 * The battery / heat wait (WAITING: ongoing, a Pause action) is decided by the job's reason, not by "paused while the worker lives": a pause the user asked for (or
 * Android's daily limit) also leaves the job paused while the worker still runs, until the tool has stopped, and when the tool does not stop the service ends its
 * process with the job paused. Taken for the wait, such a pause left behind a notification that said "Waiting" with a Pause button and no Resume for the whole
 * pause (the phone test of 2026-11-14; also in 0.2.2).
 */
final class JobForm {
    private JobForm() {}

    enum Form {
        /** the worker runs the job: its step and progress, the time left, Pause */
        RUNNING,
        /** a pause was asked for and the worker's tool has not stopped yet (it stops at the shader or file in hand): no time left, no action */
        STOPPING,
        /** paused by the service's own policy (battery, heat, a game that runs): the worker waits and goes on by itself; ongoing, Pause */
        WAITING,
        /** paused, or a running job whose process died: Resume */
        PAUSED,
        /** Retry */
        FAILED,
        /** Play */
        DONE
    }

    /** Whether a job paused for this reason (job.json "reason") goes on by itself while its service lives (PackService.policy, waitWhileAuto): the battery, the
     *  heat, or a game that runs (GameGate) */
    static boolean waitsByItself(String reason) { return "battery".equals(reason) || "thermal".equals(reason) || "game".equals(reason); }

    /**
     * state, reason: job.json's; worker: the service's worker runs this job and goes on (false when the service is about to end its process, or has none);
     * stopAsked: a pause was asked for (user, timeout); toolRuns: the worker is inside a tool (PackService.inTool), which is what a pause waits for.
     * <p>
     * STOPPING only while both hold: a pause asked for while no tool runs (the worker waits for a game, the battery or the heat, or is between two steps) is
     * paused at once, and once the tool has stopped the job is paused although the worker still lives for a moment (its last job.json write, then
     * PackService.finished). The phone test of 2026-11-15 (rc1): STOPPING was decided from "the worker has not ended yet", so the worker's last write, made
     * after the tool had exited, posted "Pausing" again a few milliseconds before finished() posted "Paused" (both on the main thread, in that order), and
     * the shade kept the "Pausing" post (ongoing, no button) until the job was resumed or forgotten: 8 ms after the split's exit 130, and after a Pause of a
     * job that waited for a game with no tool running. Why the system kept the earlier post is open (PackService.leaveNote); with this rule no post after
     * the tool's end says "Pausing".
     */
    static Form of(String state, String reason, boolean worker, boolean stopAsked, boolean toolRuns) {
        if ("done".equals(state)) return Form.DONE;
        if ("failed".equals(state)) return Form.FAILED;
        if (worker && stopAsked && toolRuns) return Form.STOPPING;
        if (stopAsked) return Form.PAUSED;   // nothing left to stop: the worker ends with the job paused
        if (worker && "running".equals(state)) return Form.RUNNING;
        if (worker && "paused".equals(state) && waitsByItself(reason)) return Form.WAITING;
        return Form.PAUSED;
    }

    /**
     * Whether a start the service gets while its worker still lives is a Resume to keep for when the worker has ended (PackService.onStartCommand, finished).
     * The form above is PAUSED, with Resume on the notification and the card, as soon as no tool runs, but the worker may still be stopping: before its first
     * step (deleting an older build's results, which takes seconds on GB-sized trees after an app update), in a wait (the battery, the heat, a game) or between
     * the tool's end and finished(). A Resume tapped then was dropped (the service only answered startForeground) and the job stayed paused.
     * stopReason: the pause in hand (PackService.stopReason, null: none); user: the start is the user's (not a sticky restart, not the app's automatic one);
     * sameJob: it names the job this worker runs. Only a pause the user asked for: Android's daily limit (timeout) stops the service for good
     */
    static boolean keepsResume(String stopReason, boolean user, boolean sameJob) { return "user".equals(stopReason) && user && sameJob; }

    /** Whether the worker's job, once it has ended, goes on with a Resume kept by keepsResume: it ended paused by that pause (not done, not failed) */
    static boolean resumesAtEnd(String state, String reason) { return "paused".equals(state) && "user".equals(reason); }
}
