package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * The app's language (docs/i18n.md): Settings › Language, System default or one of the languages the app has texts in.
 * <p>
 * Android 13+: Android's per-app language (LocaleManager; the same choice as the system's Settings › Apps › Anka Native › Language, which lists the languages of
 * res/resources.properties and the values-* folders through the generated localeConfig). Android keeps it and applies it to every process of the app; the
 * activities without "locale" in their configChanges are made again in the new language.
 * <p>
 * Android 12 (API 31, 32) has no per-app language and the app uses no AndroidX (whose AppCompatDelegate.setApplicationLocales would do the same there): the choice
 * is a file of the app's own (files/app_locale.txt, one BCP 47 tag), and each activity and the Prepare service wrap their context in it ({@link #wrap}, in their
 * attachBaseContext), as does Tr's backend (TrAndroid, through {@link #override}). A process already running (a game, a Prepare job) keeps its language until it
 * starts again.
 */
final class Lang {
    /** The choices, in the row's order: "" is the system's language, then English (values/, resources.properties' en-US) and the translations */
    static final String[] TAGS = {"", "en-US", "tr", "pt-BR", "es-419"};
    /** Their names: System default in the user's language, each language in its own (strings_settings.xml) */
    private static final int[] NAMES = {R.string.settings_language_system, R.string.settings_language_en, R.string.settings_language_tr, R.string.settings_language_pt_br,
            R.string.settings_language_es_419};
    private static final String FILE = "app_locale.txt";

    private static volatile String stored;   // Android 12: the file's tag, read once per process ("" none) and set by set()

    private Lang() {}

    /** The chosen language's tag, "" when the system's is used */
    static String chosen(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList l = c.getSystemService(LocaleManager.class).getApplicationLocales();
            return l.isEmpty() ? "" : l.get(0).toLanguageTag();
        }
        return stored(c);
    }

    /** Android 12: the language the app's own choice sets over the system's, null when there is none (and on Android 13+, where Android applies it) */
    static Locale override(Context c) {
        if (Build.VERSION.SDK_INT >= 33) return null;
        String t = stored(c);
        return t.isEmpty() ? null : Locale.forLanguageTag(t);
    }

    private static String stored(Context c) {
        String s = stored;
        if (s != null) return s;
        File f = new File(c.getFilesDir(), FILE);
        try {
            s = f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            android.util.Log.w("nxlaunch", "Language: cannot read " + f + " (" + e.getMessage() + "), the system's language is used");
            s = "";
        }
        stored = s;
        return s;
    }

    /** A context in the chosen language (Android 12 only: on 13+, and with no choice, the context itself). Each activity and the service call it in attachBaseContext */
    static Context wrap(Context base) {
        Locale l = override(base);
        if (l == null) return base;
        Locale.setDefault(l);   // formatting with Locale.getDefault() follows the app's language, as Android 13+ does for a per-app language
        Configuration o = new Configuration();   // only the language: every other field follows the activity's own configuration
        o.setLocales(new LocaleList(l));
        return base.createConfigurationContext(o);
    }

    /** The row's label (Settings › Language), so the home screen that draws the row never reads a settings key */
    static String title(Context c) { return c.getString(R.string.settings_language); }

    /** The row's value: the chosen language's name */
    static String label(Context c) {
        String t = chosen(c);
        for (int i = 0; i < TAGS.length; i++) if (TAGS[i].equalsIgnoreCase(t)) return c.getString(NAMES[i]);
        return Locale.forLanguageTag(t).getDisplayName(Locale.forLanguageTag(t));   // a language picked in the system's settings that this list lacks
    }

    /** Settings › Language: pick one; the screen is made again in it */
    static void dialog(Activity a) {
        String t = chosen(a);
        int checked = -1;
        String[] names = new String[TAGS.length];
        for (int i = 0; i < TAGS.length; i++) {
            names[i] = a.getString(NAMES[i]);
            if (TAGS[i].equalsIgnoreCase(t)) checked = i;
        }
        new AlertDialog.Builder(a).setTitle(R.string.settings_language).setSingleChoiceItems(names, checked, (d, w) -> {
            d.dismiss();
            if (TAGS[w].equalsIgnoreCase(chosen(a))) return;
            try {
                set(a, TAGS[w]);
            } catch (IOException e) {
                new AlertDialog.Builder(a).setMessage(a.getString(R.string.settings_language_failed, e.getLocalizedMessage())).setPositiveButton(android.R.string.ok, null).show();
            }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Sets the app's language ("" the system's). Android 13+ makes the activities again itself; on Android 12 this one is made again here */
    static void set(Activity a, String tag) throws IOException {
        if (Build.VERSION.SDK_INT >= 33) {
            a.getSystemService(LocaleManager.class).setApplicationLocales(tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag));
            return;
        }
        File f = new File(a.getFilesDir(), FILE);
        if (tag.isEmpty()) Files.deleteIfExists(f.toPath());
        else Files.write(f.toPath(), (tag + "\n").getBytes(StandardCharsets.UTF_8));
        stored = tag;
        Locale.setDefault(tag.isEmpty() ? Resources.getSystem().getConfiguration().getLocales().get(0) : Locale.forLanguageTag(tag));
        a.recreate();
    }
}
