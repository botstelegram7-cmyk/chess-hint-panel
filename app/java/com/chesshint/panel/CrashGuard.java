package com.chesshint.panel;

import android.content.Context;
import android.os.Build;
import android.os.Looper;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Nothing in this app is allowed to kill itself.
 *
 * Any unexpected exception inside the panel, the reader or the engine is caught, written to
 * a small log file and shown as a short message - the panel keeps running.  The log can be
 * read and copied from the app's Diagnostics card.
 */
public class CrashGuard {

    private static File logFile;
    private static volatile String lastMessage = "";
    private static volatile boolean installed = false;

    public static synchronized void install(Context ctx) {
        if (installed || ctx == null) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        logFile = new File(app.getFilesDir(), "panel-log.txt");
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            record(app, "UNCAUGHT in " + t.getName(), e);
            // Never let a background thread exception terminate the app process
            if (t == Looper.getMainLooper().getThread() && previous != null) {
                previous.uncaughtException(t, e);
            }
        });
    }

    /** catches anything thrown inside a Runnable */
    public static void guard(Context ctx, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            record(ctx, "guarded block", t);
        }
    }

    /** compact one-line breadcrumb for diagnostics (does not set lastMessage) */
    public static synchronized void step(Context ctx, String msg) {
        try {
            String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " [step] " + msg + "\n";
            File f = logFile != null ? logFile : (ctx != null ? new File(ctx.getFilesDir(), "panel-log.txt") : null);
            if (f == null) return;
            if (f.length() > 32000) f.delete();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) { }
    }

    /** logs a plain message (no exception) - used for screen-reading problems */
    public static synchronized void note(Context ctx, String where, String message) {
        try {
            lastMessage = message != null ? message : "";
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "  " + where + "\n" + message + "\ndevice: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "  android " + Build.VERSION.RELEASE + " (api " + Build.VERSION.SDK_INT + ")\n"
                    + "--------------------------------------------\n";
            File f = logFile != null ? logFile : new File(ctx.getFilesDir(), "panel-log.txt");
            if (f.length() > 32000) f.delete();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) { }
    }

    public static synchronized void record(Context ctx, String where, Throwable t) {
        try {
            String summary = where + ": " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " (" + t.getMessage() + ")");
            lastMessage = summary;
            StringWriter sw = new StringWriter();
            sw.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                    .append("  ").append(where).append('\n');
            t.printStackTrace(new PrintWriter(sw));
            sw.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append("  android ").append(Build.VERSION.RELEASE).append(" (api ").append(String.valueOf(Build.VERSION.SDK_INT)).append(")\n");
            sw.append("--------------------------------------------\n");
            String entry = sw.toString();
            File f = logFile != null ? logFile : new File(ctx.getFilesDir(), "panel-log.txt");
            if (f.length() > 32000) f.delete();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write(entry.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
            lastMessage = String.valueOf(t);
        }
    }

    public static String lastProblemSummary() {
        return lastMessage != null ? lastMessage : "";
    }

    public static void clearLastProblem() {
        lastMessage = "";
    }

    /** Returns the full recent log history (up to ~8 KB) so earlier exceptions are never hidden. */
    public static synchronized String lastCrash(Context ctx) {
        try {
            File f = logFile != null ? logFile : new File(ctx.getFilesDir(), "panel-log.txt");
            if (!f.exists()) return "";
            long len = f.length();
            int max = 8000;
            byte[] b = new byte[(int) Math.min(len, max)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            if (len > max) {
                long skip = len - max;
                while (skip > 0) {
                    long s = in.skip(skip);
                    if (s <= 0) break;
                    skip -= s;
                }
            }
            int n = in.read(b);
            in.close();
            if (n <= 0) return "";
            return new String(b, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static synchronized void clear(Context ctx) {
        try {
            File f = logFile != null ? logFile : new File(ctx.getFilesDir(), "panel-log.txt");
            if (f.exists()) f.delete();
        } catch (Throwable ignored) { }
        lastMessage = "";
    }
}
