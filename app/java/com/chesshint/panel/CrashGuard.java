package com.chesshint.panel;

import android.content.Context;
import android.os.Build;

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

    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        logFile = new File(app.getFilesDir(), "panel-log.txt");
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            record(app, "UNCAUGHT in " + t.getName(), e);
            if (previous != null) previous.uncaughtException(t, e);
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

    /** logs a plain message (no exception) - used for screen-reading problems */
    public static void note(Context ctx, String where, String message) {
        try {
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "  " + where + "\n" + message + "\ndevice: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "  android " + Build.VERSION.RELEASE + " (api " + Build.VERSION.SDK_INT + ")\n"
                    + "--------------------------------------------\n";
            File f = logFile != null ? logFile : new File(ctx.getFilesDir(), "panel-log.txt");
            if (f.length() > 24000) f.delete();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) { }
    }

    public static void record(Context ctx, String where, Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            sw.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                    .append("  ").append(where).append('\n');
            t.printStackTrace(new PrintWriter(sw));
            sw.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append("  android ").append(Build.VERSION.RELEASE).append(" (api ").append(String.valueOf(Build.VERSION.SDK_INT)).append(")\n");
            sw.append("--------------------------------------------\n");
            lastMessage = sw.toString();
            File f = logFile != null ? logFile : new File(ctx.getFilesDir(), "panel-log.txt");
            // keep the log small: only the newest 24 KB
            if (f.length() > 24000) f.delete();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write(lastMessage.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
            lastMessage = String.valueOf(t);
        }
    }

    public static String lastCrash(Context ctx) {
        try {
            File f = logFile != null ? logFile : new File(ctx.getFilesDir(), "panel-log.txt");
            if (!f.exists()) return "";
            byte[] b = new byte[(int) Math.min(f.length(), 24000)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            if (n <= 0) return "";
            String all = new String(b, 0, n, "UTF-8");
            // only the last entry
            int cut = all.lastIndexOf("--------------------------------------------");
            if (cut > 0) {
                int prev = all.lastIndexOf("--------------------------------------------", cut - 1);
                if (prev >= 0) all = all.substring(prev + 45);
            }
            return all.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void clear(Context ctx) {
        try {
            File f = logFile != null ? logFile : new File(ctx.getFilesDir(), "panel-log.txt");
            if (f.exists()) f.delete();
        } catch (Throwable ignored) { }
        lastMessage = "";
    }
}
