package dev.nxnative.android;

import java.util.Arrays;
import java.util.Locale;

/**
 * A text kept as its resource name and arguments, rendered when shown (docs/i18n.md): for a text used twice, on the screen in the user's language and in a log, a
 * report or anything pasted into an issue in English (a readiness check, a job's failure). {@link #text} is the screen's, {@link #english} the report's; toString
 * is English, so a Msg that falls into a log line or a report by concatenation stays English. A plural Msg ({@link #plural}) picks its item by its count. No
 * Android classes: Android-free classes build these, the JVM tests render them through Tr's XML backend.
 */
final class Msg {
    final String key;
    /** The plural's count; -1 for a plain string */
    final int count;
    private final Object[] args;

    /** A string resource with its arguments (a Msg among them renders in the same language as this one) */
    Msg(String key, Object... args) { this(key, -1, args); }

    private Msg(String key, int count, Object... args) {
        this.key = key;
        this.count = count;
        this.args = args == null ? new Object[0] : args.clone();
    }

    /** A plurals resource for the count n, with its arguments (pass n among them when the text shows it) */
    static Msg plural(String key, int n, Object... args) { return new Msg(key, Math.max(n, 0), args); }

    /** In the user's language */
    String text() { return render(null); }

    /** In English */
    String english() { return render(Locale.ENGLISH); }

    /** In a language: null is the user's, Locale.ENGLISH is the report's */
    String render(Locale locale) {
        Object[] a = new Object[args.length];
        for (int i = 0; i < a.length; i++) a[i] = args[i] instanceof Msg ? ((Msg) args[i]).render(locale) : args[i];
        Tr.Backend b = Tr.backend();
        return count < 0 ? b.string(locale, key, a) : b.plural(locale, key, count, a);
    }

    Object[] args() { return args.clone(); }

    @Override public String toString() { return english(); }

    @Override public boolean equals(Object o) {
        return o instanceof Msg && ((Msg) o).key.equals(key) && ((Msg) o).count == count && Arrays.equals(((Msg) o).args, args);
    }

    @Override public int hashCode() { return key.hashCode() * 31 + count * 7 + Arrays.hashCode(args); }
}
