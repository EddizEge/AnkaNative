package dev.nxnative.android;

/**
 * Prepare's own failures, the ones no packer prints a code for (docs/i18n.md "Errors of Prepare"): PackService and DumpCheck store a code and its arguments
 * in job.json ({@code error_code}, {@code error_args}, the fields JobErrors' packer codes use) beside the English {@code error}, so Job can build the status
 * line and the notification again in the user's language. A job.json without the fields (an older app's), or with a code or a number of arguments this
 * build does not know, keeps showing its English {@code error}. No Android classes: PrepareErrorsJvmTest compiles it on its own.
 * <pre>
 * step_killed        step, times, GB            Android ended the step twice (most likely for memory)
 * dump_changed       title ID, version          the dump is another title or version than the job's
 * tool_exit          step, exit code, log       a tool stopped without a [RED] line
 * tool_exit_red      step, exit code, red, log  a tool stopped with a [RED] line but no error: line (red: that line, English, as the tool wrote it)
 * compare_failed     detail                     the dump's shader map could not be compared with the app's (detail: the exception's English)
 * shader_map         name, version, file        the dump's shaders are not the ones the app knows for that version
 * dump_free_unknown  folder                     the free space beside the dump cannot be read
 * dump_space         GB needed, folder, GB free not enough free space beside the dump
 * dump_not_writable  folder                     the working folder beside the dump cannot be made
 * </pre>
 * The codes are never the packer's (JobErrors.CODES; the JVM test checks): Job asks this class first, then JobErrors.
 */
final class PrepareErrors {
    /** The codes and their number of arguments, at the same index */
    static final String[] CODES = {"step_killed", "dump_changed", "tool_exit", "tool_exit_red", "compare_failed", "shader_map", "dump_free_unknown", "dump_space",
            "dump_not_writable"};
    static final int[] ARGS = {3, 2, 3, 4, 1, 3, 1, 3, 1};

    private PrepareErrors() {}

    /** {code, argument...} as job.json stores them, each argument as a string; IllegalArgumentException for a code this class does not have or another number of arguments */
    static String[] coded(String code, Object... args) {
        int i = java.util.Arrays.asList(CODES).indexOf(code);
        if (i < 0 || args.length != ARGS[i]) throw new IllegalArgumentException("PrepareErrors: " + code + " with " + args.length + " arguments");   // i18n-keep: a programming error, never shown
        String[] out = new String[args.length + 1];
        out[0] = code;
        for (int k = 0; k < args.length; k++) out[k + 1] = String.valueOf(args[k]);
        return out;
    }

    /** The text of {code, argument...} (coded's form), or null */
    static Msg message(String[] coded) {
        if (coded == null || coded.length == 0) return null;
        return message(coded[0], java.util.Arrays.copyOfRange(coded, 1, coded.length));
    }

    /**
     * The code's text with its arguments, a Msg (the screen renders it in the user's language, job.json's error is its English); null for a code this class does
     * not have, another number of arguments, or an argument that does not parse (a job.json of another version)
     */
    static Msg message(String code, String[] a) {
        int i = java.util.Arrays.asList(CODES).indexOf(code);
        if (i < 0 || a == null || a.length != ARGS[i]) return null;
        try {
            switch (code) {
                case "step_killed": {
                    Msg step = stepInSentence(a[0]);
                    return step == null ? null : new Msg("prepare_error_step_killed", step, Integer.parseInt(a[1]), Double.parseDouble(a[2]), new Msg("prepare_button_retry"));
                }
                case "dump_changed": return new Msg("prepare_error_dump_changed", a[0], a[1]);
                case "tool_exit": return new Msg("prepare_error_tool_exit", a[0], Integer.parseInt(a[1]), a[2]);
                case "tool_exit_red": return new Msg("prepare_error_tool_exit_red", a[0], Integer.parseInt(a[1]), a[2], a[3]);
                case "compare_failed": return new Msg("prepare_error_compare", a[0]);
                case "shader_map": return new Msg("prepare_error_shader_map", a[0], Profile.versionMsg(Long.parseLong(a[1])), a[2], new Msg("prepare_button_retry"));
                case "dump_free_unknown": return new Msg("prepare_dump_free_unknown", a[0]);
                case "dump_space": return new Msg("prepare_dump_space", Double.parseDouble(a[0]), a[1], Double.parseDouble(a[2]));
                case "dump_not_writable": return new Msg("prepare_dump_not_writable", a[0]);
                default: return null;
            }
        } catch (NumberFormatException e) {
            return null;   // an argument of another version's form: the English error stays
        }
    }

    /** Whether the code's text is a whole sentence ending in a full stop (DumpCheck's refusals), which the status line takes as it is, else a phrase that it ends */
    static boolean sentence(String code) { return code.equals("dump_free_unknown") || code.equals("dump_space") || code.equals("dump_not_writable"); }

    /** A step (Job.STEPS' name) in words inside a sentence ("Stopped at checking the dump: …"): a resource of its own, never a heading lower-cased; null for a step this
     *  build does not have */
    static Msg stepInSentence(String step) {
        switch (step) {
            case "identify": return new Msg("prepare_step_identify_in_sentence");
            case "rewrite": return new Msg("prepare_step_rewrite_in_sentence");
            case "split": return new Msg("prepare_step_split_in_sentence");
            case "shaders": return new Msg("prepare_step_shaders_in_sentence");
            case "pack": return new Msg("prepare_step_pack_in_sentence");
            default: return null;
        }
    }
}
