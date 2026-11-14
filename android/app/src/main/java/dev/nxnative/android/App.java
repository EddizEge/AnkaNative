package dev.nxnative.android;

import android.app.Application;
import android.content.Context;

/**
 * The app's Application, created first in each of its processes (main, :game, :selftest, :readiness, :pack): installs Tr's backend before any provider, activity
 * or service runs, so classes without a Context can name their texts (Tr, docs/i18n.md).
 */
public final class App extends Application {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Tr.install(new TrAndroid(this));
    }
}
