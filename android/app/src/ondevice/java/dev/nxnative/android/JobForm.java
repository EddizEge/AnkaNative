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
     * after the tool had exited, posted "Pausing" again a few milliseconds before finished() posted "Paused", and the shade kept "Pausing" (ongoing, no
     * button) until the job was resumed or forgotten: 8 ms after the split's exit 130, and after a Pause of a job that waited for a game with no tool running.
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
}
