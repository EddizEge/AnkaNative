package dev.nxnative.android;

import java.util.regex.Pattern;

/**
 * The packer's error codes (docs/i18n.md "Errors of the packer"), read from a step's log so a failure the user can fix shows in their language. No Android classes
 * (JobErrorsJvmTest compiles it on its own). Just before a failure's {@code [RED]} line, scripts/pack_android.py and scripts/pack_dump.py print one line
 * <pre>
 * error: &lt;code&gt;\t&lt;argument&gt;\t&lt;argument&gt;...
 * </pre>
 * a code of lower-case words joined by {@code _}, then each argument after a tab (a path, a title ID, a count: never a sentence, never empty; the packer writes
 * {@code -} for an empty one, so a line PackService trims keeps every argument). {@link #parse} reads such a line, {@link #message} gives the code's text (key
 * {@code errors_<code>}) with those arguments, or null for a code this build does not know or a line with another number of arguments (a packer of another
 * version): the caller then shows the English {@code [RED]} sentence, job.json's {@code error}, as before.
 * <p>
 * The codes, with their arguments (the same list as dm/tools/pack_errors.py CODES; dm/tools/tests/test_android_joberrors.py compares them):
 * <pre>
 * dump_incomplete       folder                  the dump lacks its code (exefs with main, main.npdm) or its RomFS
 * module_missing        exefs folder, names     a code module of the game's profile is not in the dump (names: space-separated)
 * version_missing       version                 the update version asked for is not in the dump
 * wrong_title           folder, found, base     a version's program ID is not the base game's (16 hex digits each)
 * not_base_title        where, title ID         the dump's program ID is not a base game's title ID
 * no_profile            title ID                the app has no profile for the game
 * shaders_other_build   missing, total          the dump's shaders lack some of the profile's shader_map.tsv: another game build, or incomplete
 * unreadable_file       path                    a file of the dump cannot be read or is not what its name says (NPDM, NSO, NUP)
 * no_space              path (or tool)          no space left on the device
 * out_of_memory         tool                    a tool stopped for want of memory (a MemoryError, or killed with SIGKILL by the out-of-memory killer)
 * translator_crashed    (none)                  the shader translator crashed (no tool prints it yet: pack_errors.py says why)
 * </pre>
 * PackService reads each step's log through a {@link Watch}, stores the code and arguments of the line right before the failure's last {@code [RED]} line in
 * job.json ({@code error_code}, {@code error_args}) beside the English {@code error}, and Job shows {@link #message} when it is not null (Job.errorMsg).
 */
final class JobErrors {
    /** The codes; KEYS and ARGS at the same index */
    static final String[] CODES = {
        "dump_incomplete", "module_missing", "version_missing", "wrong_title", "not_base_title", "no_profile", "shaders_other_build", "unreadable_file", "no_space",
        "out_of_memory", "translator_crashed",
    };
    /** Each code's text: errors_ and the code */
    static final String[] KEYS = {
        "errors_dump_incomplete", "errors_module_missing", "errors_version_missing", "errors_wrong_title", "errors_not_base_title", "errors_no_profile",
        "errors_shaders_other_build", "errors_unreadable_file", "errors_no_space", "errors_out_of_memory", "errors_translator_crashed",
    };
    /** Each code's number of arguments */
    static final int[] ARGS = {1, 2, 1, 3, 2, 1, 2, 1, 1, 1, 0};

    /** The line's start; the packer's protocol, never shown */
    private static final String PREFIX = "error: ";   // i18n-keep: the packer's line format, parsed, not shown
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");

    private JobErrors() {}

    /** {code, argument...} of an error: line (a trailing CR or LF is dropped), else null */
    static String[] parse(String line) {
        if (line == null) return null;
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) end--;
        if (!line.startsWith(PREFIX) || end < PREFIX.length()) return null;
        String[] p = line.substring(PREFIX.length(), end).split("\t", -1);
        return CODE.matcher(p[0]).matches() ? p : null;
    }

    /**
     * The error: line that goes with a step's failure, read from its log line by line: the code of the line right before the last {@code [RED]} line (the
     * sentence job.json's error carries), null when that [RED] line had none. Any other line between them (a progress line) breaks the pair; blank lines do not.
     */
    static final class Watch {
        private String[] pending;
        private volatile String[] code;   // read by the step's thread after the log's reader thread wrote it

        /** One line of the log (PackService trims them) */
        void line(String line) {
            String[] p = parse(line);
            if (p != null) { pending = p; return; }
            if (line.startsWith("[RED] ")) { code = pending; pending = null; return; }
            if (!line.trim().isEmpty()) pending = null;
        }

        /** {code, argument...} of the last [RED] line, or null */
        String[] code() { return code == null ? null : code.clone(); }
    }

    /** The code's text with its arguments, in the user's language on the screen and English in a log (a Msg); null for an unknown code or a wrong number of arguments */
    static Msg message(String code, String[] args) {
        if (code == null) return null;
        String[] a = args == null ? new String[0] : args;
        for (int i = 0; i < CODES.length; i++) {
            if (!CODES[i].equals(code)) continue;
            return a.length == ARGS[i] ? new Msg(KEYS[i], (Object[]) a.clone()) : null;
        }
        return null;
    }
}
