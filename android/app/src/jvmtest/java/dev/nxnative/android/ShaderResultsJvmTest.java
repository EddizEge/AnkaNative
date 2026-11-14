package dev.nxnative.android;

import java.io.File;
import java.util.List;

/**
 * ShaderResults.dropFailed on the JVM: {@code <xlate folder>} removes the results whose status is not "ok" and prints one "dropped <key>\t<status>\t<reason>" line per
 * removed result; {@code move <from> <to>}: ShaderResults.move, "moved <n>" and a "problem <text>" line each. Run by dm/tools/tests/test_android_shader_results.py,
 * which writes the folder in vk_android_build.py's result format.
 */
public final class ShaderResultsJvmTest {
    public static void main(String[] a) throws Exception {
        if (a.length == 3 && a[0].equals("move")) {   // move <from> <to>: "moved <n>", then one "problem <text>" line each
            List<String> problems = new java.util.ArrayList<>();
            System.out.println("moved " + ShaderResults.move(new File(a[1]), new File(a[2]), problems));
            for (String p : problems) System.out.println("problem " + p);
            return;
        }
        List<ShaderResults.Failed> l = ShaderResults.dropFailed(new File(a[0]));
        for (ShaderResults.Failed f : l) System.out.println("dropped " + f.key + "\t" + f.status + "\t" + f.reason);
    }
}
