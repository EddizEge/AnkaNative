package dev.nxnative.android;

/**
 * JobDeath on the JVM: what the app does when the Prepare job's process ended without the job ending (HyperOS refuses the sticky restart, the phone test of
 * 2026-11-14). Prints "ok" lines and "all ok", exits 1 on the first failed check. Run by dm/tools/tests/test_android_job_death.py.
 */
public final class JobDeathJvmTest {
    private static void check(boolean ok, String what) {
        if (!ok) { System.out.println("FAIL " + what); System.exit(1); }
        System.out.println("ok " + what);
    }

    private static final int U = JobDeath.EXIT_UNKNOWN, LMK = 3, SIGNALED = 2, USER = JobDeath.EXIT_USER_REQUESTED;

    public static void main(String[] a) {
        JobDeath.Act R = JobDeath.Act.RESUME, N = JobDeath.Act.NOTHING, O = JobDeath.Act.OFFER;
        // the phone test's cases: kill -9, an lmkd kill, the app's own 20-s fallback (the job is written paused/user first), a Force stop
        check(JobDeath.after("running", "", false, SIGNALED, false) == R, "running, kill -9: resumed");
        check(JobDeath.after("running", "", false, LMK, false) == R, "running, a low-memory kill: resumed");
        check(JobDeath.after("running", "", false, U, false) == R, "running, no exit record (a reboot, a record not yet written): resumed");
        check(JobDeath.after("paused", "user", false, SIGNALED, false) == N, "a Pause's fallback ended the process: stays paused");
        check(JobDeath.after("paused", "timeout", false, U, false) == N, "Android's daily limit: stays paused");
        check(JobDeath.after("paused", "refused", false, U, false) == N, "a start Android refused: no loop");
        check(JobDeath.after("running", "", false, USER, false) == O, "Force stop (or removed from Recents where the phone ends it): offered, not resumed");
        check(JobDeath.after("running", "", true, LMK, false) == N, "a worker runs it (it came back): nothing");
        check(JobDeath.after("done", "", false, LMK, false) == N && JobDeath.after("failed", "", false, LMK, false) == N
                && JobDeath.after("pending", "", false, LMK, false) == N, "done, failed, pending: nothing");
        // the policy's waits: the battery and the heat wait again in a new process; a game's wait only once the game is gone
        check(JobDeath.after("paused", "battery", false, LMK, false) == R, "waiting for the battery: started again (it waits there)");
        check(JobDeath.after("paused", "thermal", false, LMK, true) == R, "waiting for the heat, a game runs: started again (it waits for both)");
        check(JobDeath.after("paused", "game", false, LMK, true) == N, "waiting for a game that still runs: the game's end starts it");
        check(JobDeath.after("paused", "game", false, LMK, false) == R, "waiting for a game that has ended: resumed");
        check(JobDeath.after("running", "", false, LMK, true) == R, "running beside a game (its new process waits for the game)");
        // the job alarm (JobAlarm): looked at again only while it waits for a game that still runs and nothing waits with it
        check(JobDeath.checkAgain("paused", "game", false, true), "waiting for a game, its process died, the game runs: checked again (the game may end without its own end)");
        check(!JobDeath.checkAgain("paused", "game", false, false) && JobDeath.after("paused", "game", false, LMK, false) == R, "the game has gone: resumed now, no later check");
        check(!JobDeath.checkAgain("paused", "game", true, true), "a worker waits with it: the service moves the alarm on itself");
        check(!JobDeath.checkAgain("running", "", false, true) && !JobDeath.checkAgain("paused", "user", false, true), "anything else: decided now");
        // the automatic start's check, some seconds later
        check(JobDeath.notBack("running", "", false), "still running in job.json, no worker: told");
        check(JobDeath.notBack("paused", "refused", false), "Android refused the service the foreground: told");
        check(!JobDeath.notBack("running", "", true), "its worker runs: not told");
        check(!JobDeath.notBack("paused", "user", false), "paused by the user meanwhile: not told");
        check(!JobDeath.notBack("done", "", false), "done: not told");
        // one automatic start at a time
        check(!JobDeath.mayStart(5_000, 0) && JobDeath.mayStart(10_000, 0) && JobDeath.mayStart(0, -JobDeath.MIN_GAP_MS), "starts 10 s apart at least");
        // which starts the service takes: an automatic one (or a sticky restart) never resumes a job the user paused
        check(JobDeath.go(false, false, "running", "", false) && !JobDeath.go(false, false, "paused", "user", false), "sticky restart: only a job that should work");
        check(JobDeath.go(true, true, "paused", "battery", false) && !JobDeath.go(true, true, "paused", "user", false) && !JobDeath.go(true, true, "failed", "", false)
                && !JobDeath.go(true, true, "paused", "refused", false), "the app's own start: only a job that should work");
        check(JobDeath.go(true, false, "paused", "user", false) && JobDeath.go(true, false, "failed", "", false) && !JobDeath.go(true, false, "done", "", false)
                && JobDeath.go(true, false, "done", "", true), "the user's start (Resume, Retry, the notification's Resume): any job not done, or another APK's");

        System.out.println("all ok");
    }
}
