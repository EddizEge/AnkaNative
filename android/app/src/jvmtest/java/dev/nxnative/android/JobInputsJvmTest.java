package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * JobInputs on the JVM: which step a job made from other profile inputs runs again. The case it was made for: a Prepare job that 0.2.2 left paused after its
 * split, whose split/ lacks Ocarina of Time's 508 task and mesh shaders, resumed by an app with the new tables. {@code <empty folder>}: prints "ok" lines, exits 1 on
 * the first failed check. Run by dm/tools/tests/test_android_job_inputs.py.
 */
public final class JobInputsJvmTest {
    private static void check(boolean ok, String what) {
        if (!ok) { System.out.println("FAIL " + what); System.exit(1); }
        System.out.println("ok " + what);
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /** A finished split (Outputs: its marker last) with this map */
    private static void split(File split, String map) throws IOException {
        Outputs.begin(split);
        write(new File(split, "pkg/vs_shader.bin"), "vs");
        write(new File(split, "shader_map.tsv"), map);
        Outputs.finish(split);
    }

    private static final String OLD = "4610dec2c950", NEW = "a6b20a286c27";
    private static final String MAP_OLD = "package\tindex\tstage\tdm_ctl_sha16\tsource\nvs_shader.bin\t0\tvertex\t0000000000000001\t/Shader/a\n";
    private static final String MAP_NEW = MAP_OLD + "ms_shader.bin\t0\tmesh\t0000000000000002\t/Shader/b\n";

    public static void main(String[] a) throws Exception {
        File root = new File(a[0]), split = new File(root, "split"), lib = new File(root, "lib/v-0dd4341ae6ce"), profileMap = new File(root, "profile/shader_map.tsv");
        write(profileMap, MAP_NEW);

        // 0.2.2's job.json has no shader_inputs; its split was made from the old tables
        split(split, MAP_OLD);
        check(JobInputs.redoFrom("", NEW, split, profileMap, lib, false) == JobInputs.SPLIT, "a paused job of before the field, its split of the old tables: split again");
        check(JobInputs.redoFrom(OLD, NEW, split, profileMap, lib, false) == JobInputs.SPLIT, "a job made for the old inputs: split again");
        check(JobInputs.redoFrom(NEW, NEW, split, profileMap, lib, false) == -1, "a job made for these inputs: nothing (its split is checked against the map later)");
        check(JobInputs.redoFrom("", "unknown", split, profileMap, lib, false) == -1, "an APK that does not know the inputs decides nothing");

        // the split already of the new tables (a job of before the field that split with a build of these tables): kept
        split(split, MAP_NEW);
        check(JobInputs.redoFrom("", NEW, split, profileMap, lib, false) == -1, "a split of these tables is kept");
        // and the library the shaders step made from the old inputs: built again from the kept split
        write(new File(lib, "inputs.tsv"), "game\t0400486024DEC000\ninputs\t" + OLD + "\n");
        check(JobInputs.redoFrom("", NEW, split, profileMap, lib, true) == JobInputs.SHADERS, "a library of the old inputs: the shaders step again, the split kept");
        write(new File(lib, "inputs.tsv"), "game\t0400486024DEC000\ninputs\t" + NEW + "\n");
        check(JobInputs.redoFrom("", NEW, split, profileMap, lib, true) == -1, "a library of these inputs: nothing");

        // a delta split (its marker): it holds only the shaders an update from other inputs translates
        write(new File(split, JobInputs.DELTA_MARKER), "# delta\n");
        check(JobInputs.redoFrom(OLD, NEW, split, profileMap, lib, false) == JobInputs.SPLIT, "a delta split made for other inputs: split again");
        check(JobInputs.redoFrom(NEW, NEW, split, profileMap, lib, false) == -1, "a delta split made for these inputs: kept");

        // no finished split (a split stopped part way: its kept scan results carry a digest of the tools, so it starts over by itself)
        Outputs.delete(split);
        check(JobInputs.redoFrom("", NEW, split, profileMap, lib, false) == -1, "no split yet: nothing to redo");
        // an adopted job (the code redone, the pack's library kept) whose pack library is of the old inputs: the shaders from the dump, split first
        File packLib = new File(root, "pack/shaders/v-0dd4341ae6ce");
        write(new File(packLib, "inputs.tsv"), "inputs\t" + OLD + "\n");
        check(JobInputs.redoFrom("", NEW, split, profileMap, packLib, true) == JobInputs.SPLIT, "the kept pack library of the old inputs, no split: split and shaders again");
        check(JobInputs.redoFrom("", NEW, split, profileMap, new File(root, "missing"), true) == JobInputs.SPLIT, "a library that is not there: split and shaders again");

        // a profile map that cannot be read is no proof of the split
        split(split, MAP_NEW);
        check(JobInputs.redoFrom("", NEW, split, new File(root, "nomap.tsv"), lib, false) == JobInputs.SPLIT, "an unreadable profile map: split again");
        System.out.println("all ok");
    }
}
