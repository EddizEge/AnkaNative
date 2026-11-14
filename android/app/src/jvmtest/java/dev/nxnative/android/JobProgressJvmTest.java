package dev.nxnative.android;

/**
 * JobProgress on the JVM: how progress lines go into the job's count. The phone test of 2026-11-14: a resumed split's start dropped the bar from 17 % to 6 %,
 * and a shader update's card said "shader 0 / 42,854" before its tool said it translates 584. Prints "ok" lines and "all ok", exits 1 on the first failed check.
 * Run by dm/tools/tests/test_android_job_progress.py.
 */
public final class JobProgressJvmTest {
    private static void check(boolean ok, String what) {
        if (!ok) { System.out.println("FAIL " + what); System.exit(1); }
        System.out.println("ok " + what);
    }

    public static void main(String[] a) {
        // the resumed split (171,712 of 186,583 kept) prints its start's stage lines again: the count stays
        check(JobProgress.keepCount(186583, "", 0, 1, "main") && JobProgress.keepCount(186583, "", 0, 1, "delta"), "the split's start keeps a kept count");
        check(!JobProgress.keepCount(186583, "", 171712, 186583, ""), "the scan's own count replaces it");
        check(!JobProgress.keepCount(0, "", 0, 1, "main") && !JobProgress.keepCount(1, "identify", 0, 1, "main"), "a first run takes its stage lines");
        check(!JobProgress.keepCount(42854, "translate", 42854, 42854, "library") && !JobProgress.keepCount(42854, "", 42854, 42854, "library"),
                "the shaders step's library stage (its whole count) is taken");
        check(!JobProgress.keepCount(3, "main", 1, 3, "main"), "the rewrite's modules count as they come");
        // the shader count: none before the step says it (an update's 584, not the game's 42,854)
        check(JobProgress.shadersTotal(0, "") == -1 && JobProgress.shadersTotal(1, "") == -1, "no total before the tool says one");
        check(JobProgress.shadersTotal(584, "translate") == 584 && JobProgress.shadersTotal(42854, "library") == 42854, "the tool's total");
        check(JobProgress.shadersTotal(0, "translate") == 0, "a tool that says it translates none");
        System.out.println("all ok");
    }
}
