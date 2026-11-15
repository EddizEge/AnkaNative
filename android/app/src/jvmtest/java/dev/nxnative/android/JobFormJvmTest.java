package dev.nxnative.android;

/**
 * JobForm on the JVM: the form the Prepare notification and the card take. The case it was made for: a pause the user asked for while the shaders step's tool
 * had not stopped, which the service ended with its process after 20 s, left the battery / heat "Waiting" notification (a Pause button, no Resume) for the whole
 * pause, and for those 20 s the card and the notification went on saying running with a time left. Prints "ok" lines, exits 1 on the first failed check. Run by
 * dm/tools/tests/test_android_job_form.py.
 */
public final class JobFormJvmTest {
    private static void check(JobForm.Form got, JobForm.Form want, String what) {
        if (got != want) { System.out.println("FAIL " + what + ": " + got + ", want " + want); System.exit(1); }
        System.out.println("ok " + what);
    }

    private static void same(boolean got, boolean want, String what) {
        if (got != want) { System.out.println("FAIL " + what + ": " + got + ", want " + want); System.exit(1); }
        System.out.println("ok " + what);
    }

    public static void main(String[] a) {
        check(JobForm.of("running", "", true, false, false), JobForm.Form.RUNNING, "the worker runs the job");
        // Pause asked: the service writes the job paused (reason user) at once, the worker lives until the tool stops
        check(JobForm.of("paused", "user", true, true, true), JobForm.Form.STOPPING, "a pause asked for, the tool still stopping");
        check(JobForm.of("running", "", true, true, true), JobForm.Form.STOPPING, "a pause asked for before the job was written paused");
        check(JobForm.of("paused", "timeout", true, true, true), JobForm.Form.STOPPING, "Android's daily limit, the tool still stopping");
        // the tool stopped: the worker ended (finished), or the service ends its process (the 20-s fallback, onTimeout: live false)
        check(JobForm.of("paused", "user", false, true, true), JobForm.Form.PAUSED, "the fallback's notification: paused, Resume");
        check(JobForm.of("paused", "user", false, false, false), JobForm.Form.PAUSED, "a clean pause");
        check(JobForm.of("paused", "timeout", false, true, true), JobForm.Form.PAUSED, "onTimeout's notification");
        // the old rule, paused while the worker lives, took these for the battery / heat wait
        check(JobForm.of("paused", "user", true, false, false), JobForm.Form.PAUSED, "paused by the user while a worker lives is no wait");
        check(JobForm.of("paused", "timeout", true, false, false), JobForm.Form.PAUSED, "paused by the daily limit while a worker lives is no wait");
        check(JobForm.of("paused", "refused", true, false, false), JobForm.Form.PAUSED, "refused is no wait");
        check(JobForm.of("paused", "battery", true, false, false), JobForm.Form.WAITING, "the battery wait");
        check(JobForm.of("paused", "thermal", true, false, false), JobForm.Form.WAITING, "the heat wait");
        check(JobForm.of("paused", "game", true, false, false), JobForm.Form.WAITING, "the wait while a game runs (GameGate)");
        check(JobForm.of("paused", "game", false, false, false), JobForm.Form.PAUSED, "a game's wait left behind by its process: Resume");
        check(JobForm.of("paused", "battery", false, false, false), JobForm.Form.PAUSED, "a battery wait whose process died");
        check(JobForm.of("running", "", false, false, false), JobForm.Form.PAUSED, "a running job whose process died");
        check(JobForm.of("failed", "", true, true, true), JobForm.Form.FAILED, "failed");
        check(JobForm.of("done", "", true, true, true), JobForm.Form.DONE, "done");
        // the phone test of 2026-11-15 (rc1): "Pausing" posted after the stop had finished stayed in the shade. Once the tool has stopped (or when none ran) the
        // job is paused although the worker still lives for its last job.json write: Resume, not "Pausing"
        check(JobForm.of("paused", "user", true, true, false), JobForm.Form.PAUSED, "the tool exited (130), the worker's last write: paused, not pausing");
        check(JobForm.of("paused", "user", true, true, true), JobForm.Form.STOPPING, "the tool still stopping: pausing");
        check(JobForm.of("running", "", true, true, false), JobForm.Form.PAUSED, "a pause asked for between two steps: paused at once");
        check(JobForm.of("paused", "timeout", true, true, false), JobForm.Form.PAUSED, "the daily limit with no tool running");
        check(JobForm.of("paused", "game", true, false, false), JobForm.Form.WAITING, "waiting for a game, no pause asked");
        check(JobForm.of("paused", "user", true, false, false), JobForm.Form.PAUSED, "a Pause of a job that waited for a game (no tool ran): paused at once");
        // the rc1-fixes review: once PAUSED shows Resume while the worker still stops, a Resume tapped then is kept and taken when the worker ends
        same(JobForm.keepsResume("user", true, true), true, "Resume tapped while the Pause's worker still stops: kept");
        same(JobForm.keepsResume(null, true, true), false, "a start while the worker runs and no pause is asked: nothing to keep");
        same(JobForm.keepsResume("timeout", true, true), false, "Android's daily limit: the service stops for good");
        same(JobForm.keepsResume("user", false, true), false, "the app's automatic start or a sticky restart is no Resume");
        same(JobForm.keepsResume("user", true, false), false, "a start for another job");
        same(JobForm.resumesAtEnd("paused", "user"), true, "the worker ended paused by that Pause: the job goes on");
        same(JobForm.resumesAtEnd("done", ""), false, "the job finished before the Pause took: nothing to resume");
        same(JobForm.resumesAtEnd("failed", ""), false, "a failed job is retried only by Retry");
        same(JobForm.resumesAtEnd("paused", "timeout"), false, "the daily limit");
        if (!JobForm.waitsByItself("battery") || !JobForm.waitsByItself("thermal") || JobForm.waitsByItself("user") || JobForm.waitsByItself("timeout")
                || JobForm.waitsByItself("")) { System.out.println("FAIL waitsByItself"); System.exit(1); }
        System.out.println("all ok");
    }
}
