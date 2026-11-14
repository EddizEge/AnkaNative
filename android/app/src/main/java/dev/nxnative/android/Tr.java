package dev.nxnative.android;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The launcher's texts for classes without a Context (docs/i18n.md). Every text lives in the app's string resources (res/values/strings_&lt;area&gt;.xml, English,
 * and the same files under values-tr, values-pt-rBR and values-b+es+419); a class that has a Context calls getString(R.string.x) itself, a class without Android
 * (BuildCheck, Pack, Profile, SaveStore... the JVM tests compile them on their own) calls {@link #s} / {@link #p} with the resource's name. No Android classes here.
 * <p>
 * The texts come from a {@link Backend}: in the app, {@code TrAndroid} (the app's Resources, installed by {@code App} in every process before anything runs); on the
 * JVM, {@link Xml}, which reads the same XML files from the folder the system property {@value #RES_PROPERTY} names (dm/tools/tests pass
 * android/app/src/main/res), so the JVM tests see the English the app shows. {@link #english} renders in English whatever the user's language: logs, the readiness
 * report and anything pasted into an issue stay English (and parseable).
 * <p>
 * Formatting is Android's: with arguments the text goes through String.format (positional %1$s, %2$d; a literal percent sign is %%), without arguments it is returned
 * as written (a lone % stays %). A key that does not exist throws MissingResourceException: test_android_i18n.py checks every key in the Java exists. That test is
 * the guard; a shipped (non-debuggable) app wraps its backend in {@link Lenient} (App), so a key or a format the test missed costs the user an English text, not a
 * crash. Debuggable builds and the JVM throw.
 */
final class Tr {
    /** The system property that names the res folder (the one holding values/) for the JVM backend */
    static final String RES_PROPERTY = "nx.tr.res";
    /** The JVM backend's user language (a BCP 47 tag, as tr, pt-BR, es-419); English when unset. For tests of a translation on the JVM */
    static final String LOCALE_PROPERTY = "nx.tr.locale";

    /** Where texts come from. locale null: the user's language (the app's current one); otherwise that language, falling back to English per text */
    interface Backend {
        /** The string resource name, formatted with args (none: as written); MissingResourceException when there is no such string */
        String string(Locale locale, String key, Object... args);

        /** The plurals resource name, the item for the count n, formatted with args (n is not added: pass it as an argument when the text shows it) */
        String plural(Locale locale, String key, int n, Object... args);
    }

    private static volatile Backend backend;

    private Tr() {}

    /** The app's backend (App.attachBaseContext) or a test's; replaces any earlier one */
    static void install(Backend b) { backend = b; }

    /** The backend a build installs: as it is when debuggable (a missing key or a bad format throws, loud for developers), else wrapped in {@link Lenient} */
    static Backend forBuild(Backend b, boolean debuggable, Consumer<String> log) { return debuggable ? b : new Lenient(b, log); }

    static Backend backend() {
        Backend b = backend;
        if (b != null) return b;
        synchronized (Tr.class) {
            if (backend == null) {
                String res = System.getProperty(RES_PROPERTY);
                if (res == null || res.isEmpty())
                    throw new IllegalStateException("Tr has no backend: the app installs TrAndroid (App); on the JVM pass -D" + RES_PROPERTY + "=<android/app/src/main/res>");
                String tag = System.getProperty(LOCALE_PROPERTY, "");
                backend = new Xml(new File(res), tag.isEmpty() ? null : Locale.forLanguageTag(tag));
            }
            return backend;
        }
    }

    /** A string in the user's language (a Msg among args renders in it too) */
    static String s(String key, Object... args) { return backend().string(null, key, rendered(null, args)); }

    /** A plural in the user's language: the item for n, formatted with args */
    static String p(String key, int n, Object... args) { return backend().plural(null, key, n, rendered(null, args)); }

    /** A string in English, whatever the user's language: logs, reports, anything parsed back */
    static String english(String key, Object... args) { return backend().string(Locale.ENGLISH, key, rendered(Locale.ENGLISH, args)); }

    /** A plural in English */
    static String englishP(String key, int n, Object... args) { return backend().plural(Locale.ENGLISH, key, n, rendered(Locale.ENGLISH, args)); }

    /** args with each Msg rendered in the text's language, as Msg.render does for its own: formatted as it is, a Msg would be its toString, English, inside a
     *  translated text. A Context's getString does not do this: pass msg.text() there */
    private static Object[] rendered(Locale locale, Object[] args) {
        if (args == null) return null;
        Object[] a = args;
        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof Msg)) continue;
            if (a == args) a = args.clone();
            a[i] = ((Msg) args[i]).render(locale);
        }
        return a;
    }

    /** Android's formatting: as written without arguments, String.format with them (in the given locale; ROOT when null) */
    static String format(Locale locale, String text, Object... args) {
        return args == null || args.length == 0 ? text : String.format(locale == null ? Locale.ROOT : locale, text, args);
    }

    /**
     * A shipped build's backend (docs/i18n.md "A missing key in a shipped build"): a text that fails (no such key, a format that does not fit its arguments, any
     * error of the backend) is not worth a crash of the user's screen. It writes one error line naming the key and the error (once per key, language and kind in a
     * process), then returns the key's English, formatted; if that fails too, the English as written; if the key has no English at all, the key itself.
     * test_android_i18n.py, not this, keeps such texts out of the app; the JVM tests and debuggable builds use the bare backend, which throws.
     */
    static final class Lenient implements Backend {
        private final Backend inner;
        private final Consumer<String> log;
        private final Set<String> said = ConcurrentHashMap.newKeySet();

        Lenient(Backend inner, Consumer<String> log) {
            this.inner = inner;
            this.log = log;
        }

        @Override public String string(Locale locale, String key, Object... args) {
            try {
                return inner.string(locale, key, args);
            } catch (RuntimeException e) {
                report("string", locale, key, e);
                try { return inner.string(Locale.ENGLISH, key, args); } catch (RuntimeException e2) { /* the English as written, below */ }
                try { return inner.string(Locale.ENGLISH, key); } catch (RuntimeException e3) { return key; }
            }
        }

        @Override public String plural(Locale locale, String key, int n, Object... args) {
            try {
                return inner.plural(locale, key, n, args);
            } catch (RuntimeException e) {
                report("plurals", locale, key, e);
                try { return inner.plural(Locale.ENGLISH, key, n, args); } catch (RuntimeException e2) { /* the English as written, below */ }
                try { return inner.plural(Locale.ENGLISH, key, n); } catch (RuntimeException e3) { return key; }
            }
        }

        private void report(String type, Locale locale, String key, RuntimeException e) {
            String lang = locale == null ? "user" : locale.toLanguageTag();
            if (said.add(type + "/" + key + "/" + lang))
                log.accept("[RED] text " + type + " " + key + " (" + lang + "): " + e + "; shown in English (docs/i18n.md)");
        }
    }

    /**
     * The JVM backend: the app's res/values*&#47;strings*.xml as aapt2 reads them (escapes, quotes, white space), for the JVM tests. English is values/; another
     * language is its folder (values-tr, values-pt-rBR, values-b+es+419) with English for each text it lacks. Plurals pick "one" for n == 1 and "other" otherwise
     * (English's rule; the app uses each language's own rules through Android).
     */
    static final class Xml implements Backend {
        private final File res;
        private final Locale user;
        private final Map<String, Map<String, String>> strings = new HashMap<>();
        private final Map<String, Map<String, Map<String, String>>> plurals = new HashMap<>();

        /** res: the folder holding values/; user: the language s() and p() render in (null: English) */
        Xml(File res, Locale user) {
            this.res = res;
            this.user = user;
            if (!new File(res, "values").isDirectory()) throw new IllegalStateException("Tr.Xml: no values folder in " + res);
        }

        @Override public String string(Locale locale, String key, Object... args) {
            Locale l = locale == null ? user : locale;
            for (String dir : folders(l)) {
                String t = load(dir).get(key);
                if (t != null) return format(l, t, args);
            }
            throw new MissingResourceException("no string " + key + " in " + res + "/values", "string", key);
        }

        @Override public String plural(Locale locale, String key, int n, Object... args) {
            Locale l = locale == null ? user : locale;
            for (String dir : folders(l)) {
                Map<String, String> items = pluralsOf(dir).get(key);
                if (items == null) continue;
                String t = n == 1 && items.containsKey("one") ? items.get("one") : items.get("other");
                if (t == null) throw new MissingResourceException("plurals " + key + " in " + dir + " has no other item", "plurals", key);
                return format(l, t, args);
            }
            throw new MissingResourceException("no plurals " + key + " in " + res + "/values", "plurals", key);
        }

        /** The folders a language reads, most specific first, values/ last */
        static List<String> folders(Locale l) {
            List<String> out = new ArrayList<>();
            if (l != null && !l.getLanguage().isEmpty() && !l.getLanguage().equals("en")) {
                String lang = l.getLanguage(), region = l.getCountry();
                if (!region.isEmpty()) {
                    out.add("values-b+" + lang + "+" + region);
                    if (region.length() == 2) out.add("values-" + lang + "-r" + region);
                }
                out.add("values-" + lang);
            }
            out.add("values");
            return out;
        }

        /** A folder's plurals, read under the lock that load() fills the map under */
        private synchronized Map<String, Map<String, String>> pluralsOf(String dir) {
            load(dir);
            return plurals.get(dir);
        }

        private synchronized Map<String, String> load(String dir) {
            Map<String, String> m = strings.get(dir);
            if (m != null) return m;
            m = new LinkedHashMap<>();
            Map<String, Map<String, String>> pl = new LinkedHashMap<>();
            File[] files = new File(res, dir).listFiles((d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
            if (files != null) {
                java.util.Arrays.sort(files);
                for (File f : files) read(f, m, pl);
            }
            strings.put(dir, m);
            plurals.put(dir, pl);
            return m;
        }

        private static void read(File f, Map<String, String> strings, Map<String, Map<String, String>> plurals) {
            org.w3c.dom.Document doc;
            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                doc = dbf.newDocumentBuilder().parse(f);
            } catch (Exception e) {
                throw new IllegalStateException("Tr.Xml: cannot read " + f + ": " + e.getMessage(), e);
            }
            NodeList kids = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i).getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) kids.item(i);
                String name = e.getAttribute("name");
                if (e.getTagName().equals("string")) strings.put(name, unescape(text(e, f)));
                else if (e.getTagName().equals("plurals")) {
                    Map<String, String> items = new LinkedHashMap<>();
                    NodeList it = e.getElementsByTagName("item");
                    for (int k = 0; k < it.getLength(); k++) items.put(((Element) it.item(k)).getAttribute("quantity"), unescape(text((Element) it.item(k), f)));
                    plurals.put(name, items);
                }
            }
        }

        /** An element's text; markup inside a string is not used in this app (docs/i18n.md), so an element child is an error */
        private static String text(Element e, File f) {
            NodeList k = e.getChildNodes();
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < k.getLength(); i++) {
                Node n = k.item(i);
                if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) s.append(n.getNodeValue());
                else if (n.getNodeType() == Node.ELEMENT_NODE) throw new IllegalStateException("Tr.Xml: markup in " + e.getAttribute("name") + " (" + f + ")");
            }
            return s.toString();
        }

        /**
         * A resource string as aapt2 compiles it: outside double quotes, runs of white space become one space and leading/trailing space goes; double quotes are
         * dropped (inside them white space stays); backslash escapes \n \t \' \" \\ \@ \? and &#92;uXXXX
         */
        static String unescape(String raw) {
            StringBuilder out = new StringBuilder();
            boolean quoted = false, space = false;
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '\\' && i + 1 < raw.length()) {
                    if (space && out.length() > 0) out.append(' ');
                    space = false;
                    char n = raw.charAt(++i);
                    switch (n) {
                        case 'n': out.append('\n'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            if (i + 4 < raw.length()) { out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16)); i += 4; }
                            else out.append('u');
                            break;
                        default: out.append(n);   // \' \" \\ \@ \? and any other: the character itself
                    }
                } else if (c == '"') {
                    quoted = !quoted;
                } else if (!quoted && Character.isWhitespace(c)) {
                    space = true;
                } else {
                    if (space && out.length() > 0) out.append(' ');
                    space = false;
                    out.append(c);
                }
            }
            return out.toString();
        }
    }
}
