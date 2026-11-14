package dev.nxnative.android;

/**
 * ShaderWorkers on the JVM, driven by dm/tools/tests/test_android_shader_workers.py: for each argument pair {@code <workers> <total GiB>}, one line
 * "&lt;workers&gt; &lt;total GiB&gt; &lt;interps&gt; &lt;gen0&gt;".
 */
public final class ShaderWorkersJvmTest {
    public static void main(String[] a) {
        for (int i = 0; i + 1 < a.length; i += 2) {
            int w = Integer.parseInt(a[i]); double g = Double.parseDouble(a[i + 1]);
            System.out.println(a[i] + " " + a[i + 1] + " " + ShaderWorkers.interps(w, g) + " " + ShaderWorkers.gen0());
        }
    }
}
