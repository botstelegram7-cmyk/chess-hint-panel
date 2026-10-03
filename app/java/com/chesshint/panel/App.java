package com.chesshint.panel;

import android.app.Application;
import android.content.Context;

/**
 * Installed before anything else, so even a crash during start-up is caught, written to the
 * log file and visible in the app's Diagnostics card instead of silently closing the app.
 */
public class App extends Application {

    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        CrashGuard.install(this);
        try {
            Tune.apply();
        } catch (Throwable t) {
            CrashGuard.record(this, "startup tuning", t);
        }
    }

    public static Context ctx() { return appContext; }
}
