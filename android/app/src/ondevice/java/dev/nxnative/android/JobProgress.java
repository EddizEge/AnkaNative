package dev.nxnative.android;

/**
 * How a step's progress lines go into job.json's count (Job.stepProgress) and what of it the screen shows. No Android classes: the JVM test
 * (android/app/src/jvmtest JobProgressJvmTest, dm/tools/tests/test_android_job_progress.py) runs it.
 * <p>
 * The phone test of 2026-11-14 found two wrong pictures. A paused split that resumed printed its start's stage lines again ("progress: 0/1 main", then
 * "0/1 delta": main's scan and the older library's hashes, about 39 s on the phone) before its count of the dump's files, and each line replaced the step's
 * count, so the bar fell from 17 % to 6 % (the split's share counted as nothing) while the split went on from the files it had kept. And at the start of a shader
 * update the card said "shader 0 / 42,854": until the shaders step's tool says its own total, the count fell back to the game's shaders, where an update
 * translates a few hundred (584 for Ocarina of Time's 0.2.2 packs).
 */
final class JobProgress {
    private JobProgress() {}

    /**
     * Whether a progress line keeps the step's count as it is instead of replacing it: a stage line of the tool's start (a total of 0 or 1 and a stage, such as
     * the split's "main" and "delta") while the step has a count of its own work (a total above 1, no stage) from a run before. That count stands for work the
     * tool keeps and counts again once its start is over (the split's kept scan results, Job.splitScan); a step started over from nothing has its count cleared
     * first (Job.restartStep). The shaders step's stage lines ("library", "check", "done") carry its whole count and are taken as they come.
     */
    static boolean keepCount(long haveTotal, String haveStage, long d, long t, String stage) {
        boolean marker = t <= 1 && d == 0 && stage != null && !stage.isEmpty();
        return marker && haveTotal > 1 && (haveStage == null || haveStage.isEmpty());
    }

    /**
     * The shaders the shaders step translates, as its tool said them (its progress lines' total: an update's few hundred, or all of the game's), -1 while it has
     * not said yet: then no shader count is shown. total, stage: the step's in job.json
     */
    static long shadersTotal(long total, String stage) { return total > 1 || stage != null && !stage.isEmpty() ? total : -1; }
}
