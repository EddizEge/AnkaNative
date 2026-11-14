package dev.nxnative.android;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;

/**
 * Tr's backend in the app: the texts from the app's string resources, looked up by name (docs/i18n.md). App installs it in every process (main, :game, :selftest,
 * :readiness, :pack) before any of the app's code runs. The user's language is the app's resources' (Android 13+: the per-app language, which Android applies to
 * every process of the app), or on Android 12 the one Settings › Language stored (Lang.override); English and any other language come from a configuration
 * context of that language, so Android falls back to values/ per text as it does for getString.
 */
final class TrAndroid implements Tr.Backend {
    private final Context app;
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<Locale, Resources> byLocale = new HashMap<>();

    TrAndroid(Context app) { this.app = app; }

    @Override public String string(Locale locale, String key, Object... args) {
        Resources r = resources(locale);
        return args == null || args.length == 0 ? r.getString(id(key, "string")) : r.getString(id(key, "string"), args);
    }

    @Override public String plural(Locale locale, String key, int n, Object... args) {
        Resources r = resources(locale);
        return args == null || args.length == 0 ? r.getQuantityString(id(key, "plurals"), n) : r.getQuantityString(id(key, "plurals"), n, args);
    }

    private int id(String key, String type) {
        String k = type + "/" + key;
        synchronized (ids) {
            Integer id = ids.get(k);
            if (id == null) {
                id = app.getResources().getIdentifier(key, type, app.getPackageName());
                if (id == 0) throw new MissingResourceException("no " + type + " resource " + key, type, key);
                ids.put(k, id);
            }
            return id;
        }
    }

    /** The resources of a language: null is the user's */
    private Resources resources(Locale locale) {
        if (locale == null) locale = Lang.override(app);
        if (locale == null) return app.getResources();
        synchronized (byLocale) {
            Resources r = byLocale.get(locale);
            if (r == null) {
                Configuration c = new Configuration(app.getResources().getConfiguration());
                c.setLocales(new LocaleList(locale));
                r = app.createConfigurationContext(c).getResources();
                byLocale.put(locale, r);
            }
            return r;
        }
    }
}
