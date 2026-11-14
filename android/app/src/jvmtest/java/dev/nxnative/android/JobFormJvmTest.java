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

    public static void main(String[] a) {
        check(JobForm.of("running", "", true, false), JobForm.Form.RUNNING, "the worker runs the job");
        // Pause asked: the service writes the job paused (reason user) at once, the worker lives until the tool stops
        check(JobForm.of("paused", "user", true, true), JobForm.Form.STOPPING, "a pause asked for, the tool still stopping");
        check(JobForm.of("running", "", true, true), JobForm.Form.STOPPING, "a pause asked for before the job was written paused");
        check(JobForm.of("paused", "timeout", true, true), JobForm.Form.STOPPING, "Android's daily limit, the tool still stopping");
        // the tool stopped: the worker ended (finished), or the service ends its process (the 20-s fallback, onTimeout: live false)
        check(JobForm.of("paused", "user", false, true), JobForm.Form.PAUSED, "the fallback's notification: paused, Resume");
        check(JobForm.of("paused", "user", false, false), JobForm.Form.PAUSED, "a clean pause");
        check(JobForm.of("paused", "timeout", false, true), JobForm.Form.PAUSED, "onTimeout's notification");
        // the old rule, paused while the worker lives, took these for the battery / heat wait
        check(JobForm.of("paused", "user", true, false), JobForm.Form.PAUSED, "paused by the user while a worker lives is no wait");
        check(JobForm.of("paused", "timeout", true, false), JobForm.Form.PAUSED, "paused by the daily limit while a worker lives is no wait");
        check(JobForm.of("paused", "refused", true, false), JobForm.Form.PAUSED, "refused is no wait");
        check(JobForm.of("paused", "battery", true, false), JobForm.Form.WAITING, "the battery wait");
        check(JobForm.of("paused", "thermal", true, false), JobForm.Form.WAITING, "the heat wait");
        check(JobForm.of("paused", "game", true, false), JobForm.Form.WAITING, "the wait while a game runs (GameGate)");
        check(JobForm.of("paused", "game", false, false), JobForm.Form.PAUSED, "a game's wait left behind by its process: Resume");
        check(JobForm.of("paused", "battery", false, false), JobForm.Form.PAUSED, "a battery wait whose process died");
        check(JobForm.of("running", "", false, false), JobForm.Form.PAUSED, "a running job whose process died");
        check(JobForm.of("failed", "", true, true), JobForm.Form.FAILED, "failed");
        check(JobForm.of("done", "", true, true), JobForm.Form.DONE, "done");
        if (!JobForm.waitsByItself("battery") || !JobForm.waitsByItself("thermal") || JobForm.waitsByItself("user") || JobForm.waitsByItself("timeout")
                || JobForm.waitsByItself("")) { System.out.println("FAIL waitsByItself"); System.exit(1); }
        System.out.println("all ok");
    }
}
