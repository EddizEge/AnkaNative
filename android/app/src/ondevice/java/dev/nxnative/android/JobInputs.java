package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Whether a job's split and shader library were made from this APK's profile inputs (Profile.shaderInputs: the game's shader tables), which a new app version may
 * change without changing any chain version: Ocarina of Time's tables gained 508 task and mesh shaders between 0.2.2 and the next release, its library version stayed
 * 0dd4341ae6ce. Job.apkChanged does not see such a change, so a job 0.2.2 left paused or failed after its split resumed with the old split, and the shaders step then
 * told the user the dump may be modified. A job records the inputs it was made for (job.json "shader_inputs"); when they are not this APK's, {@link #redoFrom} says
 * which step to run again. No Android classes: the JVM test (android/app/src/jvmtest JobInputsJvmTest, dm/tools/tests/test_android_job_inputs.py) runs it.
 */
final class JobInputs {
    private JobInputs() {}

    /** Job.STEPS' split and shaders steps */
    static final int SPLIT = 2, SHADERS = 3;

    /** The split's file that marks a delta split (shader_split.py DELTA_MARKER): its tables are of the files it scanned, not a whole map */
    static final String DELTA_MARKER = "split_delta.tsv";

    /** The job's status note after this check started its split over (Job.notice) */
    static final String NOTICE = "This app version knows more of the game's shaders, so they are found in your dump again first.";

    /**
     * The first step to run again because the job's outputs were made from other profile inputs, -1 when none. recorded: the inputs the job was made for ("" in a
     * job.json of before the field); current: this APK's (Profile.shaderInputs; "unknown" or null: nothing is decided); split: the job's split/ folder; profileMap:
     * the profile's shader_map.tsv the APK carries; lib: the job's library folder (v-&lt;DMSH_VK_CHAIN&gt;/, made by the shaders step); shadersDone: the shaders step is done.
     * <ul>
     * <li>{@link #SPLIT} when the job has a finished split whose map is not the profile's (made from other tables), or a delta split (it holds only the shaders an
     *     update from other inputs translates), or when its library is of other inputs and its split is not one of these inputs.</li>
     * <li>{@link #SHADERS} when the split is of these inputs and the library the shaders step made is not.</li>
     * </ul>
     * A split not finished yet starts over by itself (shader_split.py's kept results carry a digest of its tools), so it needs nothing here.
     */
    static int redoFrom(String recorded, String current, File split, File profileMap, File lib, boolean shadersDone) {
        if (current == null || !current.matches("[0-9a-f]{12}") || current.equals(recorded)) return -1;
        boolean splitOk = Outputs.complete(split) && !new File(split, DELTA_MARKER).isFile() && sameBytes(new File(split, "shader_map.tsv"), profileMap);
        if (Outputs.complete(split) && !splitOk) return SPLIT;
        if (shadersDone && !current.equals(ShaderLibs.inputsOf(lib))) return splitOk ? SHADERS : SPLIT;
        return -1;
    }

    static boolean sameBytes(File a, File b) {
        try {
            return java.util.Arrays.equals(Files.readAllBytes(a.toPath()), Files.readAllBytes(b.toPath()));
        } catch (IOException e) {
            return false;   // one cannot be read: not shown the same
        }
    }
}
