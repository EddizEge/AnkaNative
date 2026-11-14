package dev.nxnative.android;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

/**
 * The app's Application, created first in each of its processes (main, :game, :selftest, :readiness, :pack): installs Tr's backend before any provider, activity
 * or service runs, so classes without a Context can name their texts (Tr, docs/i18n.md). A debuggable build throws on a missing key or a bad format; a shipped one
 * logs it and shows the English (Tr.Lenient).
 */
public final class App extends Application {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        boolean debuggable = (base.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        Tr.install(Tr.forBuild(new TrAndroid(this), debuggable, m -> Log.e("nxnative", m)));
    }
}
