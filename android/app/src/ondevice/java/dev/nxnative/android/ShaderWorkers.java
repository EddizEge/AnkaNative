package dev.nxnative.android;

/**
 * How the shaders step uses the phone's cores (android/SHADERS.md#build-speed). vk_android_build.py runs in the app's process, which may not fork, so its J threads
 * share one Python GIL and the Python half of each shader (the SPIR-V passes and reflection, about 60 % of its time) ran on one core: 3-5 shaders a second. With
 * DMSH_VK_INTERPS it runs that half in subinterpreters, each with its own GIL, one per core the job may use, as far as the phone's memory allows: each worker holds
 * its own copy of the tool's tables and the shader in hand (measured on the PC, 4,588 shaders: about 90 MB a worker at the peak; a full Ocarina of Time build with 6 peaked at 2.3 GB, the 818 MB library mapped for its check included).
 * The split step's scan (shader_split.py, DM_SPLIT_INTERPS) takes the same count: it ran in one interpreter for the same reason.
 * The translator (NativeAOT .NET in this process) spends much of its time in its garbage collector, which stops every thread that allocates; a larger first
 * generation (DOTNET_GCgen0size, hexadecimal bytes as .NET reads it) makes it collect less often: 26 % less translator time at 64 MiB, 43 % at 256 MiB alone on
 * the PC. In the whole step (4,588 shaders, PC) 64 MiB made 4 workers 16 % faster and 6 workers no faster, and 256 MiB gained nothing over 64 for 60 MB more: 64 MiB
 * on every phone. The translator runs one call at a time (DMSH_VK_XLATE_J, unchanged).
 * Measured on the PC in the phone's form only, not yet run on a phone (android/SHADERS.md, The phone's form): where subinterpreters cannot be set up, each tool
 * prints a "subinterpreters: not usable here" line (PackService copies it to logcat) and runs as before; env.txt can set both counts to 1.
 * No Android classes: dm/tools/tests/test_android_shader_workers.py runs it on the JVM.
 */
final class ShaderWorkers {
    /** What the reported memory (ActivityManager totalMem, GiB) must leave to Android and other apps before the workers get any: an 8 GB phone reports about 7.3 */
    static final double RESERVE_GIB = 5.0;
    /** Memory budgeted per subinterpreter worker, GiB: its average plus room for the largest shaders */
    static final double PER_WORKER_GIB = 0.5;
    /** The translator's first generation: 64 MiB */
    static final String GEN0 = "4000000";

    private ShaderWorkers() {}

    /** The subinterpreter workers for a job allowed {@code workers} cores (at least 1) on a phone reporting {@code totalGib}: 1 means the tool's threads as before */
    static int interps(int workers, double totalGib) {
        int byMem = (int) Math.floor((totalGib - RESERVE_GIB) / PER_WORKER_GIB);
        return Math.max(1, Math.min(workers, byMem));
    }

    /** DOTNET_GCgen0size for the translator */
    static String gen0() { return GEN0; }
}
