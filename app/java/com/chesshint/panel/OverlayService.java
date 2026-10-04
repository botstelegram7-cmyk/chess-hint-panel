package com.chesshint.panel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything happens here: floating windows, screen reading, game tracking and the engine.
 *
 * Key lifecycle & capture guarantees (v1.7):
 *  - markStarting() prevents MainActivity.onResume() (which runs 1ms after onActivityResult())
 *    from thinking the service is stopped and calling stopService() before startForeground() runs.
 *  - Every path through onStartCommand() satisfies startForeground() so Android 8-15 never throws
 *    ForegroundServiceDidNotStartInTimeException.
 *  - ScreenGrab keeps only a lightweight Image reference in its 60fps listener and decodes on
 *    demand inside grab(), avoiding heap exhaustion right after granting screen capture.
 */
public class OverlayService extends Service implements
        BubbleView.Listener, PanelView.Listener, CalibrationView.Listener, BoardEditorView.Listener {

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String ACTION_STOP = "chesshint.stop";
    private static final String CHANNEL = "chesshint";
    private static final int NOTIF_ID = 42;

    private static volatile OverlayService INSTANCE;

    private static volatile long startingAt = 0;
    private static volatile int pendingCode = 0;
    private static volatile Intent pendingData = null;

    public static void markStarting(int code, Intent data) {
        startingAt = System.currentTimeMillis();
        pendingCode = code;
        pendingData = data;
    }

    public static void clearStarting() {
        startingAt = 0;
        pendingCode = 0;
        pendingData = null;
    }

    public static boolean isStarting() {
        return System.currentTimeMillis() - startingAt < 6000;
    }

    private static final ArrayList<View> LIVE_VIEWS = new ArrayList<>();
    private static WindowManager LIVE_WM;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private volatile boolean destroyed;

    private WindowManager wm;
    private Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private static void registerWindow(View v) {
        if (v == null) return;
        synchronized (LIVE_VIEWS) {
            if (!LIVE_VIEWS.contains(v)) LIVE_VIEWS.add(v);
        }
    }

    private static void unregisterWindow(View v) {
        if (v == null) return;
        synchronized (LIVE_VIEWS) {
            LIVE_VIEWS.remove(v);
        }
    }

    public static int windowCount() {
        synchronized (LIVE_VIEWS) {
            return LIVE_VIEWS.size();
        }
    }

    /** Removes every window this app put on the screen, whoever created it - safe to call anytime. */
    public static void purgeAllWindows() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(OverlayService::purgeAllWindows);
            return;
        }
        List<View> copy;
        synchronized (LIVE_VIEWS) {
            copy = new ArrayList<>(LIVE_VIEWS);
            LIVE_VIEWS.clear();
        }
        WindowManager w = LIVE_WM;
        if (w == null && App.ctx() != null) {
            try { w = (WindowManager) App.ctx().getSystemService(WINDOW_SERVICE); } catch (Throwable ignored) { }
        }
        for (View v : copy) {
            if (v == null) continue;
            try {
                if (w != null) w.removeViewImmediate(v);
            } catch (Throwable t1) {
                try { if (w != null) w.removeView(v); } catch (Throwable ignored) { }
            }
        }
    }

    /** Run on the UI thread; never let an exception kill the panel and never run after stop. */
    private void postSafe(Runnable r) {
        if (destroyed) return;
        main.post(() -> {
            if (destroyed || INSTANCE != OverlayService.this) return;
            CrashGuard.guard(OverlayService.this, r);
        });
    }

    private HandlerThread workerThread;
    private Handler worker;

    private MediaProjection projection;
    private MediaProjection.Callback projCallback;
    private ScreenGrab grab;
    private UciEngine engine;
    private Track track;
    private Vision.Result lastVisionResult;

    private OverlayView overlay;
    private BubbleView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private PanelView panel;
    private WindowManager.LayoutParams panelLp;
    private CalibrationView calib;
    private AndroidView calibHost;
    private BoardEditorView editor;
    private AndroidView editorHost;

    private volatile boolean busy;
    private volatile boolean startingCapture;
    private int lastCode;
    private Intent lastData;
    private int autoRecoveryCount;
    private String lastProblem = "";
    private boolean windowAdded;
    private int[] lastAutoPattern;
    private int[] hintSquareSig;
    private int[] prevPollSig;
    private boolean waitingForOpponent;
    private String lastStatus;
    private Vibrator vibrator;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sp, key) -> postSafe(this::applySettingsNow);

    // ==================================================================== lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        CrashGuard.install(this);
        CrashGuard.step(this, "OverlayService.onCreate");
        if (INSTANCE != null && INSTANCE != this) {
            try { INSTANCE.shutdown(null); } catch (Throwable ignored) { }
        }
        destroyed = false;
        INSTANCE = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        LIVE_WM = wm;
        purgeAllWindows();
        prefs = new Prefs(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        workerThread = new HandlerThread("hint-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        engine = UciEngine.getInstance();
        createChannel();
        goForeground("Starting panel…");
        prefs.raw().registerOnSharedPreferenceChangeListener(prefListener);
        new Thread(() -> {
            if (destroyed) return;
            boolean ok;
            try {
                engine.setBudget(Tune.EngineBudget.hashMb, Tune.EngineBudget.threads);
                ok = engine.start();
                CrashGuard.step(OverlayService.this, "engine.start=" + ok);
            } catch (Throwable t) {
                CrashGuard.record(OverlayService.this, "engine start", t);
                ok = false;
            }
            final boolean started = ok;
            postSafe(() -> {
                if (!started) {
                    setStatus("Engine error: " + UciEngine.libraryError(), "tap STOP then START again");
                }
            });
        }, "engine-init").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null && ACTION_STOP.equals(intent.getAction())) {
                CrashGuard.step(this, "onStartCommand ACTION_STOP");
                releaseCaptureOnly();
                shutdown("Panel closed");
                return START_NOT_STICKY;
            }
            if (destroyed) {
                destroyed = false;
                INSTANCE = this;
            }

            goForeground("Starting screen reading…");

            int code = pendingCode;
            Intent data = pendingData;
            if (intent != null && intent.hasExtra(EXTRA_DATA)) {
                code = intent.getIntExtra(EXTRA_CODE, code);
                try {
                    Intent d = intent.getParcelableExtra(EXTRA_DATA);
                    if (d != null) data = d;
                } catch (Throwable ignored) { }
            }
            clearStarting();

            if (data != null) {
                lastCode = code;
                lastData = new Intent(data);
                autoRecoveryCount = 0;
                startingCapture = true;
                CrashGuard.step(this, "onStartCommand scheduling startProjection");
                ensureWindows();
                final int c = code;
                final Intent d = lastData;
                main.postDelayed(() -> {
                    if (!destroyed && INSTANCE == OverlayService.this) {
                        startProjection(c, d);
                    }
                }, 150);
            } else if (projection == null && !startingCapture) {
                CrashGuard.step(this, "onStartCommand no data & no projection -> shutdown");
                shutdown(null);
                return START_NOT_STICKY;
            } else {
                main.postDelayed(this::ensureWindows, 80);
            }
        } catch (Throwable t) {
            CrashGuard.record(this, "onStartCommand", t);
            toast("Panel error: " + t.getClass().getSimpleName());
            shutdown(null);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        CrashGuard.step(this, "OverlayService.onDestroy");
        destroyed = true;
        startingCapture = false;
        clearStarting();
        try { stopAuto(); } catch (Throwable ignored) { }
        try { main.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
        try { if (worker != null) worker.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
        try { if (prefs != null) prefs.raw().unregisterOnSharedPreferenceChangeListener(prefListener); } catch (Throwable ignored) { }
        try { removeAllWindows(); } catch (Throwable ignored) { }
        try { purgeAllWindows(); } catch (Throwable ignored) { }
        releaseCaptureOnly();
        try { if (engine != null) engine.stop(); } catch (Throwable ignored) { }
        try { if (workerThread != null) workerThread.quitSafely(); } catch (Throwable ignored) { }
        try { stopForeground(true); } catch (Throwable ignored) { }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Throwable ignored) { }
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    private void releaseCaptureOnly() {
        try {
            if (grab != null) {
                grab.release();
                grab = null;
            }
        } catch (Throwable ignored) { }
        try {
            if (projection != null) {
                if (projCallback != null) {
                    try { projection.unregisterCallback(projCallback); } catch (Throwable ignored) { }
                    projCallback = null;
                }
                projection.stop();
                projection = null;
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ==================================================================== setup

    private void createChannel() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Chess Hint Panel", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) { }
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getBroadcast(this, 1,
                new Intent(this, StopReceiver.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Chess Hint Panel")
                .setContentText(text == null ? "Running" : text)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(R.drawable.ic_stat, "STOP", stop).build())
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        if (destroyed) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(text == null ? "Running" : text));
        } catch (Throwable ignored) { }
    }

    private void startProjection(int code, Intent data) {
        if (data == null) {
            startingCapture = false;
            goForeground("Waiting for screen reading…");
            captureProblem("Screen reading data was empty — please try again");
            return;
        }

        // Cleanly release any previous projection/grab (unregistering callback first so it doesn't fire onStop!)
        releaseCaptureOnly();
        lastProblem = "";
        CrashGuard.clearLastProblem();

        // Keep foreground service with MEDIA_PROJECTION type active
        boolean foregroundDone = goForeground("Starting screen reading…");

        String firstError = "";
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            CrashGuard.step(this, "getMediaProjection=" + (projection != null));
        } catch (Throwable t) {
            firstError = describe(t);
            projection = null;
            CrashGuard.record(this, "getMediaProjection", t);
        }

        if (projection == null) {
            startingCapture = false;
            if (!foregroundDone) goForeground("Screen reading problem");
            captureProblem("Screen reading refused: "
                    + (firstError.isEmpty() ? "permission was not granted" : firstError));
            return;
        }

        final MediaProjection p = projection;
        projCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (destroyed || INSTANCE != OverlayService.this || projection != p) return;
                CrashGuard.step(OverlayService.this, "projCallback.onStop fired (recovery=" + autoRecoveryCount + ")");
                postSafe(() -> {
                    if (destroyed || INSTANCE != OverlayService.this || projection != p) return;
                    releaseCaptureOnly();
                    // On Android < 14 (including Android 10 / ColorOS / Realme UI), if the system
                    // stops the first projection grant during the FGS transition, automatically
                    // re-acquire using the saved consent Intent without interrupting the user.
                    if (Build.VERSION.SDK_INT < 34 && lastData != null && autoRecoveryCount < 3) {
                        autoRecoveryCount++;
                        startingCapture = true;
                        CrashGuard.step(OverlayService.this, "auto-recovering MediaProjection #" + autoRecoveryCount);
                        main.postDelayed(() -> {
                            if (!destroyed && INSTANCE == OverlayService.this) {
                                startProjection(lastCode, new Intent(lastData));
                            }
                        }, 300);
                        return;
                    }
                    startingCapture = false;
                    captureProblem("Screen reading was stopped by the system — press RETRY");
                });
            }
        };
        try {
            p.registerCallback(projCallback, main);
        } catch (Throwable ignored) { }

        ensureWindows();

        // Initialize VirtualDisplay immediately on the main thread right after getMediaProjection
        ScreenGrab g = new ScreenGrab(this, p, worker);
        if (!g.init()) {
            startingCapture = false;
            final String err = g.lastError();
            g.release();
            captureProblem("Screen capture failed" + (err.isEmpty() ? "" : " (" + err + ")"));
            return;
        }
        grab = g;
        startingCapture = false;

        final Runnable nudge = () -> main.post(() -> {
            if (!destroyed && overlay != null) overlay.nudgeFrame();
        });

        worker.post(() -> {
            if (destroyed || projection != p || grab != g) return;
            try {
                Bitmap first = g.grabWait(4500, nudge);
                if (first == null) {
                    if (destroyed || projection != p || grab != g) return;
                    final String err = g.lastError();
                    postSafe(() -> {
                        ensureWindows();
                        captureProblem("No picture arriving from the screen yet"
                                + (err.isEmpty() ? "" : " (" + err + ")")
                                + " — press RETRY SCREEN READING");
                    });
                    return;
                }
                CrashGuard.step(this, "first frame OK " + first.getWidth() + "x" + first.getHeight());
                if (g.lastFrameLookedBlank()) {
                    first.recycle();
                    postSafe(() -> {
                        ensureWindows();
                        captureProblem("The picture is coming through empty/black — your chess app may block "
                                + "screen capture (protected content). Try another chess app or board theme");
                    });
                    return;
                }
                first.recycle();
            } catch (OutOfMemoryError oom) {
                CrashGuard.record(this, "capture start (memory)", oom);
                postSafe(() -> captureProblem("Not enough memory — close some apps and press RETRY"));
                return;
            } catch (Throwable t) {
                CrashGuard.record(this, "capture start", t);
                postSafe(() -> captureProblem("Screen capture error: " + describe(t)));
                return;
            }
            postSafe(() -> {
                lastProblem = "";
                CrashGuard.clearLastProblem();
                ensureWindows();
                updateNotification("Ready — tap the ♞ button");
                if (prefs.hasRect()) {
                    overlay.board = prefs.boardRect();
                    overlay.invalidate();
                }
                if (prefs.auto()) startAuto();
                setStatus("Ready", "open your chess game, then tap ♞");
            });
        });
    }

    private boolean goForeground(String text) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(text),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, buildNotification(text));
            }
            return true;
        } catch (Throwable t) {
            CrashGuard.record(this, "startForeground(mediaProjection)", t);
            return goForegroundPlain(text);
        }
    }

    private boolean goForegroundPlain(String text) {
        try {
            startForeground(NOTIF_ID, buildNotification(text));
            return true;
        } catch (Throwable t) {
            CrashGuard.record(this, "startForeground(plain)", t);
            return false;
        }
    }

    private void captureProblem(String msg) {
        if (destroyed || INSTANCE != this) return;
        lastProblem = msg;
        CrashGuard.note(this, "screen reading", msg);
        setStatus(msg, "open the app → RETRY SCREEN READING");
        toast(msg);
        ensureWindows();
    }

    public static String lastProblem() {
        return INSTANCE == null ? "" : (INSTANCE.lastProblem == null ? "" : INSTANCE.lastProblem);
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    private void ensureWindows() {
        if (destroyed || INSTANCE != this) return;
        try {
            if (overlay == null) addOverlay();
            if (bubble == null) addBubble();
            if (panel != null) panel.refresh(prefs, configLine());
        } catch (Throwable t) {
            CrashGuard.record(this, "ensureWindows", t);
            toast("Could not show the panel: " + t.getMessage());
            shutdown(null);
        }
    }

    private String configLine() {
        int elo = prefs.elo();
        String style = new String[]{"arrow + rings", "arrow", "rings", "squares"}[Math.min(3, prefs.markerStyle())];
        return "Stockfish " + (elo <= 0 ? "MAX" : elo + " elo") + "  •  " + (prefs.movetime() / 1000f) + "s  •  "
                + style + "  •  " + (track == null ? "no position yet"
                : (track.pos.side == 1 ? "white" : "black") + " to move");
    }

    private void addOverlay() {
        if (overlay != null) removeWindow(overlay, true);
        synchronized (LIVE_VIEWS) {
            for (int i = LIVE_VIEWS.size() - 1; i >= 0; i--) {
                View v = LIVE_VIEWS.get(i);
                if (v instanceof OverlayView) {
                    LIVE_VIEWS.remove(i);
                    try { wm.removeViewImmediate(v); } catch (Throwable t) { try { wm.removeView(v); } catch (Throwable ignored) { } }
                }
            }
        }
        overlay = new OverlayView(this);
        overlay.applyPrefs(prefs);
        overlay.board = prefs.boardRect();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(overlay, lp);
        registerWindow(overlay);
        windowAdded = true;
    }

    private void addBubble() {
        if (bubble != null) removeWindow(bubble, false);
        synchronized (LIVE_VIEWS) {
            for (int i = LIVE_VIEWS.size() - 1; i >= 0; i--) {
                View v = LIVE_VIEWS.get(i);
                if (v instanceof BubbleView) {
                    LIVE_VIEWS.remove(i);
                    try { wm.removeViewImmediate(v); } catch (Throwable t) { try { wm.removeView(v); } catch (Throwable ignored) { } }
                }
            }
        }
        bubble = new BubbleView(this, this);
        bubble.setAuto(prefs.auto());
        bubble.setScaleFactor(prefs.bubbleScale());
        bubble.setCloseButton(true);
        bubbleLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        Point size = screenSize();
        bubbleLp.x = prefs.bubbleX() >= 0 ? prefs.bubbleX() : size.x - dp(78);
        bubbleLp.y = prefs.bubbleY() >= 0 ? prefs.bubbleY() : (int) (size.y * 0.34f);
        wm.addView(bubble, bubbleLp);
        registerWindow(bubble);
    }

    private void removeAllWindows() {
        removeWindow(overlay, true);
        removeWindow(bubble, false);
        removeWindow(panel, false);
        removeWindow(calibHost, false);
        removeWindow(editorHost, false);
        overlay = null;
        bubble = null;
        panel = null;
        calibHost = null;
        editorHost = null;
        calib = null;
        editor = null;
        windowAdded = false;
    }

    private void removeWindow(View v, boolean checkFlag) {
        if (v == null) return;
        unregisterWindow(v);
        if (checkFlag && !windowAdded) return;
        try {
            wm.removeViewImmediate(v);
        } catch (Throwable t1) {
            try { wm.removeView(v); } catch (Throwable ignored) { }
        }
    }

    /** One-tap close: marks + floating ♞ + panels disappear, notifications are dropped, service ends. */
    private void shutdown(String note) {
        destroyed = true;
        startingCapture = false;
        clearStarting();
        try { stopAuto(); } catch (Throwable ignored) { }
        try { main.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
        try { if (worker != null) worker.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
        if (note != null) {
            try { Toast.makeText(getApplicationContext(), note, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
        }
        try { removeAllWindows(); } catch (Throwable ignored) { }
        try { purgeAllWindows(); } catch (Throwable ignored) { }
        releaseCaptureOnly();
        try { stopForeground(true); } catch (Throwable ignored) { }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Throwable ignored) { }
        if (INSTANCE == this) INSTANCE = null;
        try { stopSelf(); } catch (Throwable ignored) { }
    }

    private static class AndroidView extends android.widget.FrameLayout {
        AndroidView(Context c, View child) {
            super(c);
            addView(child, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    private Point screenSize() {
        Point p = new Point();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Rect b = wm.getMaximumWindowMetrics().getBounds();
                p.set(b.width(), b.height());
            } else {
                Display d = wm.getDefaultDisplay();
                DisplayMetrics dm = new DisplayMetrics();
                d.getRealMetrics(dm);
                p.set(dm.widthPixels, dm.heightPixels);
            }
        } catch (Throwable ignored) { }
        if (p.x == 0) {
            p.set(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        }
        return p;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) {
        final String m = s;
        main.post(() -> {
            try { Toast.makeText(OverlayService.this, m, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
        });
    }

    private static String diagnosticsText(OverlayService s) {
        StringBuilder sb = new StringBuilder();
        ScreenGrab g = s.grab;
        sb.append("screen reading: ").append(g == null ? "not running" : ("active, " + g.frameCount() + " frames"))
                .append(g != null && !g.lastError().isEmpty() ? "  (" + g.lastError() + ")" : "").append('\n');
        if (g != null) {
            long age = g.msSinceLastFrame();
            sb.append("last picture: ").append(age < 0 ? "none yet" : (age / 1000.0) + " s ago")
                    .append(g.lastFrameLookedBlank() ? "  (blank)" : "").append('\n');
        }
        sb.append("floating windows: ").append(windowCount()).append('\n');
        if (!lastTest.isEmpty()) sb.append("screen test: ").append(lastTest).append('\n');
        sb.append("engine: ").append(s.engine != null && s.engine.isAlive() ? "ready" : "not running")
                .append("  (hash ").append(Tune.EngineBudget.hashMb).append(" MB)").append('\n');
        Rect r = s.prefs.boardRect();
        sb.append("board frame: ").append(r == null ? "not set" : r.toShortString()).append('\n');
        sb.append("position: ").append(s.track == null ? "not tracked" : s.track.pos.fen());
        String crash = CrashGuard.lastCrash(s);
        if (!crash.isEmpty()) sb.append("\n\nlast error\n----------\n").append(crash);
        return sb.toString();
    }

    private void setStatus(String s, String sub) {
        lastStatus = s;
        if (overlay != null) {
            overlay.setBusy(busy);
            overlay.setStatus(s, sub);
        }
        if (s != null && (s.startsWith("YOUR") || s.startsWith("Ready") || s.startsWith("Waiting"))) {
            updateNotification(s);
        }
    }

    private void finishBusy(String statusText) {
        busy = false;
        if (overlay != null) {
            overlay.setCaptureClean(false);
            overlay.setBusy(false);
        }
        setStatus(statusText, null);
    }

    private void buzz() {
        if (!prefs.vibrations() || vibrator == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(28);
            }
        } catch (Throwable ignored) { }
    }

    private void applySettingsNow() {
        if (destroyed) return;
        if (overlay != null) overlay.applyPrefs(prefs);
        if (bubble != null) {
            bubble.setAuto(prefs.auto());
            bubble.setScaleFactor(prefs.bubbleScale());
        }
        if (panel != null) {
            panel.refresh(prefs, configLine());
            positionPanel();
        }
        if (prefs.auto() && !autoRunning) startAuto();
        if (!prefs.auto() && autoRunning) stopAuto();
    }

    // ==================================================================== bubble

    @Override
    public void onBubbleTap() {
        if (destroyed) return;
        if (panel == null) {
            panel = new PanelView(this, this);
            panel.refresh(prefs, configLine());
            panelLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            panelLp.gravity = Gravity.TOP | Gravity.START;
            positionPanel();
            try {
                wm.addView(panel, panelLp);
                registerWindow(panel);
            } catch (Throwable t) {
                panel = null;
            }
        } else {
            hidePanel();
        }
    }

    private void positionPanel() {
        if (panelLp == null) return;
        Point s = screenSize();
        int width = dp(238);
        int x = bubbleLp != null ? bubbleLp.x - width - dp(10) : dp(16);
        if (x < dp(6)) x = Math.min(dp(6), Math.max(0, (s.x - width) / 2));
        int y = bubbleLp != null ? Math.min(bubbleLp.y, s.y - dp(430)) : (int) (s.y * 0.2f);
        panelLp.x = Math.max(dp(6), x);
        panelLp.y = Math.max(dp(20), y);
    }

    @Override
    public void onBubbleLongPress() {
        if (overlay == null) return;
        overlay.clearHint();
        overlay.setStatus("Marks hidden", null);
    }

    @Override
    public void onBubbleMove(float dx, float dy) {
        if (bubbleLp == null || bubble == null) return;
        Point s = screenSize();
        bubbleLp.x = (int) Math.max(-dp(8), Math.min(s.x - dp(30), bubbleLp.x + dx));
        bubbleLp.y = (int) Math.max(-dp(8), Math.min(s.y - dp(30), bubbleLp.y + dy));
        try { wm.updateViewLayout(bubble, bubbleLp); } catch (Throwable ignored) { }
        if (panel != null) {
            positionPanel();
            try { wm.updateViewLayout(panel, panelLp); } catch (Throwable ignored) { }
        }
    }

    @Override
    public void onBubbleDrop() {
        if (bubbleLp != null) prefs.setBubble(bubbleLp.x, bubbleLp.y);
    }

    @Override
    public void onBubbleClose() {
        shutdown("Panel closed");
    }

    // ==================================================================== panel

    @Override
    public void onMove() {
        hidePanel();
        requestHint(false);
    }

    @Override
    public void onAuto() {
        prefs.setAuto(!prefs.auto());
        if (panel != null) panel.refresh(prefs, configLine());
        if (bubble != null) bubble.setAuto(prefs.auto());
        if (prefs.auto()) {
            waitingForOpponent = false;
            hintSquareSig = null;
            prevPollSig = null;
            startAuto();
            if (overlay == null || !overlay.hasHint()) {
                requestHint(true);
            }
        } else {
            stopAuto();
            setStatus("Auto off", null);
        }
    }

    @Override
    public void onSettings() {
        hidePanel();
        try {
            startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast("Could not open settings");
        }
    }

    @Override public void onFit() { hidePanel(); showCalibration(prefs.boardRect()); }
    @Override public void onFix() { hidePanel(); showEditor(); }

    @Override
    public void onFlip() {
        prefs.setWhiteBottom(!prefs.whiteBottom());
        track = null;
        lastAutoPattern = null;
        hintSquareSig = null;
        prevPollSig = null;
        waitingForOpponent = false;
        if (overlay != null) {
            overlay.clearHint();
            overlay.applyPrefs(prefs);
        }
        if (panel != null) panel.refresh(prefs, configLine());
        setStatus(prefs.whiteBottom() ? "Bottom = WHITE (you)" : "Bottom = BLACK (you)", null);
    }

    @Override
    public void onNewGame() {
        hidePanel();
        Chess.Pos p = Chess.fromBoard(Board.starting(prefs.whiteBottom()), prefs.whiteBottom(), Chess.WHITE);
        if (track == null) track = new Track(p, prefs.whiteBottom());
        else track.reset(p);
        lastAutoPattern = null;
        hintSquareSig = null;
        prevPollSig = null;
        waitingForOpponent = !prefs.whiteBottom();
        if (overlay != null) overlay.clearHint();
        setStatus("New game", "white to move");
    }

    @Override
    public void onHide() {
        hidePanel();
        if (overlay != null) {
            overlay.clearHint();
            overlay.setStatus(null, null);
        }
    }

    @Override
    public void onHideBubble() {
        hidePanel();
        removeWindow(bubble, false);
        bubble = null;
        setStatus("Floating button hidden", "START PANEL in the app brings it back");
    }

    @Override
    public void onStop() {
        shutdown("Panel closed");
    }

    private void hidePanel() {
        removeWindow(panel, false);
        panel = null;
    }

    // ==================================================================== calibration / editor

    private void showCalibration(Rect start) {
        if (destroyed || calibHost != null) return;
        hidePanel();
        calib = new CalibrationView(this, this);
        if (start != null) calib.rect.set(start);
        calibHost = new AndroidView(this, calib);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(calibHost, lp);
            registerWindow(calibHost);
        } catch (Throwable t) {
            calibHost = null;
            calib = null;
            toast("Could not open the frame tool");
        }
    }

    private void hideCalibration() {
        removeWindow(calibHost, false);
        calibHost = null;
        calib = null;
    }

    @Override
    public void onCalibSave(Rect r) {
        if (r.width() < 100) { toast("Frame too small"); return; }
        int side = Math.max(r.width(), r.height());
        Rect sq = new Rect(r.left, r.top, r.left + side, r.top + side);
        prefs.setBoardRect(sq);
        track = null;
        lastAutoPattern = null;
        hintSquareSig = null;
        prevPollSig = null;
        waitingForOpponent = false;
        if (overlay != null) {
            overlay.clearHint();
            overlay.board = sq;
            overlay.showFrame = prefs.showFrame();
            overlay.invalidate();
        }
        hideCalibration();
        setStatus("Board frame saved", null);
    }

    @Override public void onCalibCancel() { hideCalibration(); }

    @Override public void onCalibAuto() { autoDetectBoard(false); }

    private void autoDetectBoard(boolean quiet) {
        if (grab == null) {
            toast("Screen reading is not running");
            return;
        }
        hidePanel();
        if (overlay != null) overlay.setCaptureClean(true);
        final Runnable nudge = () -> main.post(() -> {
            if (!destroyed && overlay != null) overlay.nudgeFrame();
        });
        worker.post(() -> {
            Bitmap b = null;
            Rect r = null;
            try {
                try { Thread.sleep(90); } catch (InterruptedException ignored) { }
                b = grab.grabFresh(2200, nudge);
                r = b == null ? null : Vision.detect(b);
            } catch (Throwable t) {
                CrashGuard.record(this, "board detection", t);
            }
            if (b != null) b.recycle();
            final Rect found = r;
            postSafe(() -> {
                if (overlay != null) overlay.setCaptureClean(false);
                if (found == null) {
                    setStatus("No chess board on screen", "open your game and tap FIT BOARD");
                    if (!quiet) toast("Open your chess app, then tap FIT BOARD");
                } else {
                    prefs.setBoardRect(found);
                    track = null;
                    lastAutoPattern = null;
                    hintSquareSig = null;
                    prevPollSig = null;
                    waitingForOpponent = false;
                    if (overlay != null) { overlay.clearHint(); overlay.board = found; overlay.invalidate(); }
                    if (calib != null) { calib.rect.set(found); calib.invalidate(); }
                    setStatus("Board found", null);
                }
            });
        });
    }

    private void showEditor() {
        if (destroyed || editorHost != null) return;
        hidePanel();
        Board b;
        if (track != null) {
            b = Chess.toBoard(track.pos, prefs.whiteBottom());
        } else if (lastVisionResult != null && lastVisionResult.ok) {
            b = Vision.guessBoard(lastVisionResult, prefs.whiteBottom());
        } else {
            b = Board.starting(prefs.whiteBottom());
        }
        editor = new BoardEditorView(this, this);
        Rect r = prefs.boardRect();
        if (r == null) {
            Point s = screenSize();
            int side = (int) (Math.min(s.x, s.y) * 0.78f);
            r = new Rect((s.x - side) / 2, dp(70), (s.x + side) / 2, dp(70) + side);
        }
        editor.setBoard(b, r, prefs.whiteBottom());
        editorHost = new AndroidView(this, editor);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(editorHost, lp);
            registerWindow(editorHost);
        } catch (Throwable t) {
            editorHost = null;
            editor = null;
            toast("Could not open the editor");
        }
    }

    private void hideEditor() {
        removeWindow(editorHost, false);
        editorHost = null;
        editor = null;
    }

    @Override
    public void onEditorApply(Board b, boolean whiteBottom) {
        prefs.setWhiteBottom(whiteBottom);
        if (overlay != null) {
            overlay.clearHint();
            overlay.applyPrefs(prefs);
        }
        Chess.Pos p = Chess.fromBoard(b, whiteBottom, whiteBottom ? Chess.WHITE : Chess.BLACK);
        if (track == null) track = new Track(p, whiteBottom);
        else track.reset(p);
        lastAutoPattern = Chess.pattern(p, whiteBottom);
        hintSquareSig = null;
        prevPollSig = null;
        waitingForOpponent = false;
        hideEditor();
        String warn = b.validate(whiteBottom);
        if (!warn.isEmpty()) toast("Heads up: " + warn);
        setStatus("Position set", (whiteBottom ? "white" : "black") + " to move");
    }

    @Override public void onEditorCancel() { hideEditor(); }

    @Override
    public void onEditorFlip() {
        if (editor != null) {
            editor.whiteBottom = !editor.whiteBottom;
            editor.invalidate();
        }
    }

    // ==================================================================== position reconciliation & helpers

    /**
     * Reconciles a clean Vision.Result with our tracked Chess.Pos:
     * 1. Checks if the board is within 0..3 plies of the standard starting position.
     * 2. Otherwise attempts legal move tracking via Track.observe().
     * 3. If any square's occupancy/color in track.pos still disagrees with res.colorPat,
     *    reconciles by preserving known piece types on unmoved squares, transferring the
     *    moving piece type when a single piece moved, and classifying any remaining squares
     *    via Vision.guessBoard(), followed by Chess.sanitize().
     */
    private void syncTrackerWithVision(Vision.Result res, int expectedSide) {
        boolean detectedWb = Vision.detectWhiteBottom(res, prefs.whiteBottom());
        if (detectedWb != prefs.whiteBottom()) {
            prefs.setWhiteBottom(detectedWb);
            track = null;
            lastAutoPattern = null;
        }
        boolean wb = prefs.whiteBottom();
        int targetSide = (expectedSide == Chess.WHITE || expectedSide == Chess.BLACK)
                ? expectedSide : (wb ? Chess.WHITE : Chess.BLACK);

        // 1. Check if the board is at or within 1-2 moves of the starting position
        Chess.Pos startPos = Chess.fromBoard(Board.starting(wb), wb, Chess.WHITE);
        Track fresh = new Track(startPos, wb);
        Track.Update uFresh = fresh.observe(res.colorPat, res.conf);
        if (uFresh.ok && uFresh.moves.size() <= 3
                && java.util.Arrays.equals(Chess.pattern(fresh.pos, wb), res.colorPat)) {
            fresh.pos.side = targetSide;
            Chess.sanitize(fresh.pos);
            track = fresh;
            return;
        }

        // 2. Try legal move tracking from existing track
        Chess.Pos prevPos = track != null ? track.pos.copy() : null;
        if (track != null) {
            track.setWhiteBottom(wb);
            Track.Update u = track.observe(res.colorPat, res.conf);
            if (u.ok && java.util.Arrays.equals(Chess.pattern(track.pos, wb), res.colorPat)) {
                track.pos.side = targetSide;
                Chess.sanitize(track.pos);
                return;
            }
        }

        // 3. Reconcile previous known pieces with fresh Vision.guessBoard(res, wb)
        //    ONLY if prevPos is within 1 move per side of res.colorPat (not a stale starting board!)
        Board guessed = Vision.guessBoard(res, wb);
        if (prevPos != null) {
            Board prevBoard = Chess.toBoard(prevPos, wb);
            int wFrom = -1, wTo = -1, wFromCnt = 0, wToCnt = 0;
            int bFrom = -1, bTo = -1, bFromCnt = 0, bToCnt = 0;
            for (int i = 0; i < 64; i++) {
                int oldC = Board.isWhite(prevBoard.s[i]) ? 1 : (Board.isBlack(prevBoard.s[i]) ? 2 : 0);
                int newC = res.colorPat[i];
                if (oldC == 1 && newC != 1) { wFrom = i; wFromCnt++; }
                if (oldC != 1 && newC == 1) { wTo = i; wToCnt++; }
                if (oldC == 2 && newC != 2) { bFrom = i; bFromCnt++; }
                if (oldC != 2 && newC == 2) { bTo = i; bToCnt++; }
            }
            if (wFromCnt <= 1 && wToCnt <= 1 && bFromCnt <= 1 && bToCnt <= 1) {
                for (int i = 0; i < 64; i++) {
                    int oldC = Board.isWhite(prevBoard.s[i]) ? 1 : (Board.isBlack(prevBoard.s[i]) ? 2 : 0);
                    int newC = res.colorPat[i];
                    if (oldC != 0 && oldC == newC) {
                        byte prevP = prevBoard.s[i];
                        float pH = res.pieceHeight[i];
                        // Do not overwrite if visual piece height strongly contradicts prevP (e.g. Pawn vs tall piece)
                        if (pH > 0.1f && pH < 0.67f) {
                            guessed.s[i] = (byte) (newC == 1 ? 1 : 9);
                        } else if (pH > 0.74f && (prevP == 1 || prevP == 9)) {
                            // keep guessed.s[i] (tall piece)
                        } else {
                            guessed.s[i] = prevP;
                        }
                    }
                }
                if (wFromCnt == 1 && wToCnt == 1 && wFrom >= 0 && wTo >= 0) {
                    byte moved = prevBoard.s[wFrom];
                    int rIdx = Board.rankIdxOf(wTo, wb);
                    if (moved == 1 && (rIdx == 0 || rIdx == 7)) moved = 5;
                    guessed.s[wTo] = moved;
                }
                if (bFromCnt == 1 && bToCnt == 1 && bFrom >= 0 && bTo >= 0) {
                    byte moved = prevBoard.s[bFrom];
                    int rIdx = Board.rankIdxOf(bTo, wb);
                    if (moved == 9 && (rIdx == 0 || rIdx == 7)) moved = 13;
                    guessed.s[bTo] = moved;
                }
            }
        }
        Chess.Pos reconciled = Chess.fromBoard(guessed, wb, targetSide);
        if (track == null) track = new Track(reconciled, wb);
        else track.reset(reconciled);
    }

    /**
     * Strictly verifies that a candidate UCI move is:
     * 1. A legal move in `pos` for `userSide`,
     * 2. Moving a square visually occupied by `userSide` to a square NOT occupied by `userSide`,
     * 3. Visually clear along every intermediate square of any sliding ray (Bishop, Rook, Queen, 2-sq Pawn),
     * 4. Visually consistent with the piece silhouette at `fromIdx` (no L-jumps from symmetric pieces,
     *    no diagonal slides from Rooks, no orthogonal slides from Bishops, no long slides from Pawns).
     */
    private static boolean isVisuallyValidMove(Vision.Result res, Chess.Pos pos, String mv, boolean wb, int userSide) {
        if (res == null || pos == null || mv == null || mv.length() < 4) return false;
        String uci4 = mv.substring(0, 4);
        int fromIdx = Board.squareFromName(uci4.substring(0, 2), wb);
        int toIdx = Board.squareFromName(uci4.substring(2, 4), wb);
        if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return false;

        // 1. Must move user's own piece to a square not occupied by user's own piece
        if (res.colorPat[fromIdx] != userSide || res.colorPat[toIdx] == userSide) return false;

        // 2. Must be in Chess.legal(pos)
        boolean inLegal = false;
        for (Chess.Move lm : Chess.legal(pos)) {
            if (lm.uci().substring(0, 4).equals(uci4)) {
                inLegal = true;
                break;
            }
        }
        if (!inLegal) return false;

        // 3. Visual ray-clearance check on the live screen (res.colorPat)
        int r0 = fromIdx / 8, f0 = fromIdx % 8;
        int r1 = toIdx / 8, f1 = toIdx % 8;
        int dr = r1 - r0, df = f1 - f0;
        int adr = Math.abs(dr), adf = Math.abs(df);
        if ((adr == adf && adr > 1) || ((dr == 0 || df == 0) && (adr + adf > 1))) {
            int sr = Integer.compare(dr, 0), sf = Integer.compare(df, 0);
            int r = r0 + sr, f = f0 + sf;
            while (r != r1 || f != f1) {
                if (res.colorPat[r * 8 + f] != 0) return false; // blocked by a piece on screen!
                r += sr;
                f += sf;
            }
        }

        // 4. Piece-type & silhouette consistency check
        int fsq = Chess.sqFromName(uci4.substring(0, 2));
        int piece = fsq >= 0 ? pos.cb[fsq] : 0;
        int pt = piece & 7; // 1=P, 2=N, 3=B, 4=R, 5=Q, 6=K
        int visType = Vision.guessType(res, fromIdx, userSide == Chess.WHITE) % 8;
        float pH = res.pieceHeight[fromIdx];
        float asym = res.asym[fromIdx];
        float[] nb = res.bands[fromIdx];

        if (adr * adf == 2) {
            // Knight jump: must be a Knight in pos, not a short Pawn, and not a symmetric sliding piece
            if (pt != 2) return false;
            if (pH > 0.1f && pH < 0.68f) return false;
            if (asym < 0.06f && visType != 2) return false;
        } else if (pt == 1) {
            // Pawn move: user is at the bottom of the screen, so pawns always move up (dr < 0)
            if (dr >= 0) return false;
            if (df == 0) {
                if (res.colorPat[toIdx] != 0) return false;
                if (dr == -2 && (r0 != 6 || res.colorPat[(r0 - 1) * 8 + f0] != 0)) return false;
            } else if (adf == 1 && dr == -1) {
                int tsq = Chess.sqFromName(uci4.substring(2, 4));
                if (res.colorPat[toIdx] != (3 - userSide) && tsq != pos.epSq) return false;
            } else {
                return false;
            }
        } else if (adr > 1 || adf > 1) {
            // Multi-square sliding move (Bishop, Rook, Queen) or Castling
            if (pH > 0.1f && pH < 0.675f) return false; // visually a Pawn, cannot slide multiple squares
            if (adr == adf && visType == 4 && nb[0] > 0.54f && asym < 0.07f) return false; // Rook cannot slide diagonally
            if ((dr == 0 || df == 0) && visType == 3 && nb[0] < 0.34f && nb[1] < 0.42f) return false; // Bishop cannot slide orthogonally
        }
        return true;
    }

    // ==================================================================== hint

    private void requestHint(final boolean auto) {
        if (busy || destroyed) return;
        if (grab == null) {
            setStatus("Screen reading is not active", "open the app and press RETRY");
            toast("Screen reading is not active — open the app and press RETRY");
            return;
        }
        busy = true;
        hidePanel();
        if (overlay != null) {
            overlay.setBusy(true);
            overlay.setCaptureClean(true);
        }

        worker.post(() -> {
            try {
                hintWork(auto);
            } catch (OutOfMemoryError oom) {
                CrashGuard.record(this, "hint (out of memory)", oom);
                postSafe(() -> finishBusy("Not enough memory — close some apps and retry"));
            } catch (Throwable t) {
                CrashGuard.record(this, "hint", t);
                final String m = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
                postSafe(() -> finishBusy("Hint error: " + m));
            }
        });
    }

    private void hintWork(final boolean auto) {
        final Runnable nudge = () -> main.post(() -> {
            if (!destroyed && overlay != null) overlay.nudgeFrame();
        });

        try { Thread.sleep(95); } catch (InterruptedException ignored) { }

        Bitmap bmp = null;
        try {
            bmp = auto ? grab.grabFresh(1500, nudge) : grab.grabStable(nudge);
        } catch (Throwable ignored) { }

        postSafe(() -> {
            if (overlay != null) {
                overlay.setCaptureClean(false);
            }
        });

        if (bmp == null) {
            postSafe(() -> finishBusy("Could not read the screen"));
            return;
        }
        Rect r = prefs.boardRect();
        Vision.Result res = null;
        if (r != null) {
            try { res = Vision.read(bmp, r); } catch (Throwable ignored) { res = null; }
        }
        if (r == null || res == null || !res.ok) {
            Rect det = null;
            try { det = Vision.detect(bmp); } catch (Throwable ignored) { }
            if (det != null) {
                prefs.setBoardRect(det);
                r = det;
                final Rect rr = det;
                postSafe(() -> {
                    if (overlay != null) {
                        overlay.board = rr;
                        overlay.invalidate();
                    }
                });
                try { res = Vision.read(bmp, r); } catch (Throwable t) { res = new Vision.Result(); }
            } else if (res == null || !res.ok) {
                bmp.recycle();
                postSafe(() -> {
                    finishBusy("No chess board on screen");
                    if (!auto) toast("Open your chess game, then tap FIT BOARD once");
                });
                return;
            }
        }
        bmp.recycle();
        if (res == null || !res.ok) {
            postSafe(() -> finishBusy("Could not read the board"));
            return;
        }
        lastVisionResult = res;

        syncTrackerWithVision(res, 0);
        final boolean wb = prefs.whiteBottom();
        final int userSide = wb ? Chess.WHITE : Chess.BLACK;
        lastAutoPattern = res.colorPat.clone();
        waitingForOpponent = false;
        hintSquareSig = null;

        Chess.Pos usePos = track.pos.copy();
        usePos.side = userSide;
        Chess.sanitize(usePos);
        String fen = usePos.fen();

        if (!engine.isAlive()) {
            if (!engine.start()) {
                final String err = UciEngine.libraryError();
                postSafe(() -> finishBusy("Engine error: " + err));
                return;
            }
        }
        String bm = engine.bestMove(fen, prefs.movetime(), prefs.elo());
        String mv = UciEngine.moveOf(bm);

        // Strictly verify that the suggested move is 100% legal AND visually clear on the live screen
        if (!isVisuallyValidMove(res, usePos, mv, wb, userSide)) {
            // Re-sync directly from fresh visual piece silhouettes (in case tracker drifted) and re-query Stockfish
            Board freshGuessed = Vision.guessBoard(res, wb);
            usePos = Chess.fromBoard(freshGuessed, wb, userSide);
            Chess.sanitize(usePos);
            track.reset(usePos);
            bm = engine.bestMove(usePos.fen(), Math.max(800, prefs.movetime() / 2), prefs.elo());
            mv = UciEngine.moveOf(bm);
        }

        final int depth = engine.depth, cp = engine.scoreCp, mate = engine.mateIn;

        if (!isVisuallyValidMove(res, usePos, mv, wb, userSide)) {
            mv = null;
            for (Chess.Move lm : Chess.legal(usePos)) {
                String uci = lm.uci();
                if (isVisuallyValidMove(res, usePos, uci, wb, userSide)) {
                    mv = uci;
                    break;
                }
            }
        }

        if (mv == null || mv.length() < 4) {
            postSafe(() -> finishBusy("No legal move found (game over?)"));
            return;
        }

        final int fromIdx = Board.squareFromName(mv.substring(0, 2), wb);
        final int toIdx = Board.squareFromName(mv.substring(2, 4), wb);
        if (fromIdx < 0 || toIdx < 0) {
            postSafe(() -> finishBusy("Bad move from the engine"));
            return;
        }
        final String promo = mv.length() > 4 ? mv.substring(4) : "";
        int fsq = Chess.sqFromName(mv.substring(0, 2));
        final int piece = fsq >= 0 ? usePos.cb[fsq] : 0;
        final String text = Markers.label(piece, mv.substring(0, 2), mv.substring(2, 4), promo, true);

        StringBuilder info = new StringBuilder();
        info.append("depth ").append(depth);
        if (mate != 0) info.append("   mate in ").append(Math.abs(mate));
        else info.append(String.format(Locale.US, "   %+.2f", cp / 100.0));

        postSafe(() -> {
            if (overlay != null) {
                overlay.board = prefs.boardRect();
                overlay.applyPrefs(prefs);
                overlay.setHint(fromIdx, toIdx, text, info.toString());
            }
            buzz();
            finishBusy(null);
        });
    }

    // ==================================================================== auto mode

    private boolean autoRunning;

    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !prefs.auto()) {
                autoRunning = false;
                return;
            }
            if (!busy && grab != null) checkAuto();
            main.postDelayed(this, 650);
        }
    };

    private void startAuto() {
        if (autoRunning || destroyed) return;
        autoRunning = true;
        main.removeCallbacks(autoTick);
        main.postDelayed(autoTick, 450);
    }

    private void stopAuto() {
        autoRunning = false;
        main.removeCallbacks(autoTick);
    }

    /** Computes a 64-square mean-RGB signature for fast visual move detection without hiding the overlay. */
    private static int[] computeSquareSig(Bitmap bmp, Rect board) {
        if (bmp == null || board == null) return null;
        int W = bmp.getWidth(), H = bmp.getHeight();
        int bx = Math.max(0, board.left), by = Math.max(0, board.top);
        int bw = Math.min(W - bx, board.width()), bh = Math.min(H - by, board.height());
        if (bw < 64 || bh < 64) return null;
        int[] px = new int[bw * bh];
        bmp.getPixels(px, 0, bw, bx, by, bw, bh);
        int[] sig = new int[64];
        float sq = bw / 8f;
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                int x0 = Math.max(0, Math.round((f + 0.22f) * sq));
                int x1 = Math.min(bw - 1, Math.round((f + 0.78f) * sq));
                int y0 = Math.max(0, Math.round((r + 0.22f) * sq));
                int y1 = Math.min(bh - 1, Math.round((r + 0.78f) * sq));
                long sr = 0, sg = 0, sb = 0;
                int cnt = 0;
                for (int y = y0; y <= y1; y += 2) {
                    int row = y * bw;
                    for (int x = x0; x <= x1; x += 2) {
                        int c = px[row + x];
                        sr += (c >> 16) & 255;
                        sg += (c >> 8) & 255;
                        sb += c & 255;
                        cnt++;
                    }
                }
                if (cnt > 0) {
                    sig[r * 8 + f] = (((int) (sr / cnt)) << 16) | (((int) (sg / cnt)) << 8) | ((int) (sb / cnt));
                }
            }
        }
        return sig;
    }

    private static int sigDiffCount(int[] a, int[] b, int thrPerChannel) {
        if (a == null || b == null || a.length != 64 || b.length != 64) return 64;
        int limit = thrPerChannel * 3;
        int diff = 0;
        for (int i = 0; i < 64; i++) {
            int ca = a[i], cb = b[i];
            int d = Math.abs(((ca >> 16) & 255) - ((cb >> 16) & 255))
                    + Math.abs(((ca >> 8) & 255) - ((cb >> 8) & 255))
                    + Math.abs((ca & 255) - (cb & 255));
            if (d > limit) diff++;
        }
        return diff;
    }

    /**
     * Determines which side made the most recent move between two clean 64-square color patterns:
     *   0  = no change
     *  -1  = new game / full board reset (>8 squares changed)
     *   userSide = the user moved last (now waiting for opponent)
     *   oppSide  = the opponent moved last (now it is the user's turn!)
     */
    private int whoMovedLast(int[] prev, int[] cur, int userSide) {
        if (prev == null || cur == null || java.util.Arrays.equals(prev, cur)) return 0;
        int oppSide = 3 - userSide;
        int totalDiff = 0;
        int empU = 0, arrU = 0, empO = 0, arrO = 0;
        for (int i = 0; i < 64; i++) {
            if (prev[i] != cur[i]) totalDiff++;
            if (prev[i] == userSide && cur[i] == 0) empU++;
            if (prev[i] != userSide && cur[i] == userSide) arrU++;
            if (prev[i] == oppSide && cur[i] == 0) empO++;
            if (prev[i] != oppSide && cur[i] == oppSide) arrO++;
        }
        if (totalDiff > 8) return -1;

        boolean uMoved = empU >= 1 && arrU >= 1;
        boolean oMoved = empO >= 1 && arrO >= 1;

        if (oMoved && !uMoved) return oppSide;
        if (uMoved && !oMoved) return userSide;
        if (uMoved && oMoved) {
            return waitingForOpponent ? userSide : oppSide;
        }
        if (empU >= 1 && empO >= 1 && arrU == 0 && arrO == 0) {
            // Capture + immediate recapture on the same square
            return waitingForOpponent ? userSide : oppSide;
        }
        if (empO >= 1 || arrO >= 1) return oppSide;
        if (empU >= 1 || arrU >= 1) return userSide;
        return 0;
    }

    private static int countColorInRange(int[] pat, int from, int to, int color) {
        if (pat == null) return 0;
        int cnt = 0;
        for (int i = Math.max(0, from); i < Math.min(pat.length, to); i++) {
            if (pat[i] == color) cnt++;
        }
        return cnt;
    }

    private void checkAuto() {
        if (destroyed || grab == null || busy) return;
        busy = true;
        final Runnable nudge = () -> main.post(() -> {
            if (!destroyed && overlay != null) overlay.nudgeFrame();
        });
        worker.post(() -> {
            Bitmap bmp = null;
            try {
                bmp = grab.grabFresh(900, nudge);
                if (bmp == null) {
                    postSafe(() -> busy = false);
                    return;
                }
                Rect r = prefs.boardRect();
                if (r == null) {
                    Rect det = Vision.detect(bmp);
                    bmp.recycle();
                    bmp = null;
                    if (det != null) {
                        prefs.setBoardRect(det);
                        final Rect rr = det;
                        postSafe(() -> {
                            if (overlay != null) {
                                overlay.board = rr;
                                overlay.invalidate();
                            }
                            busy = false;
                            requestHint(true);
                        });
                    } else {
                        postSafe(() -> busy = false);
                    }
                    return;
                }

                int[] curSig = computeSquareSig(bmp, r);
                if (curSig == null) {
                    bmp.recycle();
                    postSafe(() -> busy = false);
                    return;
                }

                // Wait for any piece-sliding animation to settle between two consecutive ticks
                boolean settled = prevPollSig != null && sigDiffCount(prevPollSig, curSig, 6) == 0;
                prevPollSig = curSig;
                if (!settled) {
                    bmp.recycle();
                    postSafe(() -> busy = false);
                    return;
                }

                boolean hintVisible = overlay != null && overlay.hasHint();
                if (hintVisible) {
                    if (hintSquareSig == null) {
                        hintSquareSig = curSig;
                        bmp.recycle();
                        postSafe(() -> busy = false);
                        return;
                    }
                    // Check if any move happened on the board since the hint arrow was drawn
                    if (sigDiffCount(hintSquareSig, curSig, 12) < 2) {
                        bmp.recycle();
                        postSafe(() -> busy = false);
                        return;
                    }
                    // A piece moved on the board! Clear the old hint arrow and grab a clean frame
                    bmp.recycle();
                    bmp = null;
                    hintSquareSig = null;
                    postSafe(() -> {
                        if (overlay != null) {
                            overlay.clearHint();
                            overlay.setCaptureClean(true);
                        }
                    });
                    try { Thread.sleep(95); } catch (InterruptedException ignored) { }
                    bmp = grab.grabFresh(1200, nudge);
                    postSafe(() -> {
                        if (overlay != null) overlay.setCaptureClean(false);
                    });
                    if (bmp == null) {
                        postSafe(() -> busy = false);
                        return;
                    }
                    prevPollSig = computeSquareSig(bmp, r);
                }

                // Now we have a clean frame with no hint arrow on screen
                Vision.Result res = Vision.read(bmp, r);
                if (res == null || !res.ok) {
                    Rect det = Vision.detect(bmp);
                    if (det != null) {
                        prefs.setBoardRect(det);
                        r = det;
                        res = Vision.read(bmp, r);
                    }
                }
                bmp.recycle();
                bmp = null;

                if (res == null || !res.ok) {
                    postSafe(() -> busy = false);
                    return;
                }
                lastVisionResult = res;

                boolean detectedWb = Vision.detectWhiteBottom(res, prefs.whiteBottom());
                if (detectedWb != prefs.whiteBottom()) {
                    prefs.setWhiteBottom(detectedWb);
                    track = null;
                    lastAutoPattern = null;
                }
                boolean wb = prefs.whiteBottom();
                int userSide = wb ? Chess.WHITE : Chess.BLACK;
                int oppSide = 3 - userSide;

                if (lastAutoPattern == null) {
                    // If user is Black and White hasn't made move 1 yet (all 16 White pieces on rows 0..1), wait for White
                    boolean whiteUnmovedAtTop = !wb && countColorInRange(res.colorPat, 0, 16, Chess.WHITE) == 16
                            && countColorInRange(res.colorPat, 16, 64, Chess.WHITE) == 0;
                    if (whiteUnmovedAtTop) {
                        syncTrackerWithVision(res, Chess.WHITE);
                        lastAutoPattern = res.colorPat.clone();
                        waitingForOpponent = true;
                        postSafe(() -> busy = false);
                        return;
                    }
                    postSafe(() -> {
                        busy = false;
                        requestHint(true);
                    });
                    return;
                }

                int mover = whoMovedLast(lastAutoPattern, res.colorPat, userSide);
                if (mover == 0) {
                    // Board unchanged
                    if (!waitingForOpponent && (overlay == null || !overlay.hasHint())) {
                        postSafe(() -> {
                            busy = false;
                            requestHint(true);
                        });
                    } else {
                        postSafe(() -> busy = false);
                    }
                    return;
                }

                if (mover == -1) {
                    // New game or board reset
                    syncTrackerWithVision(res, Chess.WHITE);
                    wb = prefs.whiteBottom();
                    userSide = wb ? Chess.WHITE : Chess.BLACK;
                    lastAutoPattern = res.colorPat.clone();
                    boolean whiteUnmovedAtTop = !wb && countColorInRange(res.colorPat, 0, 16, Chess.WHITE) == 16
                            && countColorInRange(res.colorPat, 16, 64, Chess.WHITE) == 0;
                    waitingForOpponent = (userSide == Chess.BLACK && whiteUnmovedAtTop);
                    if (!waitingForOpponent) {
                        postSafe(() -> {
                            busy = false;
                            requestHint(true);
                        });
                    } else {
                        postSafe(() -> busy = false);
                    }
                    return;
                }

                if (mover == userSide) {
                    // User just made their move; wait for opponent's reply
                    syncTrackerWithVision(res, oppSide);
                    lastAutoPattern = res.colorPat.clone();
                    waitingForOpponent = true;
                    postSafe(() -> {
                        if (overlay != null) overlay.clearHint();
                        busy = false;
                    });
                    return;
                }

                if (mover == oppSide) {
                    // Opponent just made their move; now it is the user's turn!
                    syncTrackerWithVision(res, userSide);
                    lastAutoPattern = res.colorPat.clone();
                    waitingForOpponent = false;
                    postSafe(() -> {
                        busy = false;
                        requestHint(true);
                    });
                    return;
                }

                postSafe(() -> busy = false);
            } catch (Throwable t) {
                if (bmp != null) bmp.recycle();
                CrashGuard.record(this, "auto read", t);
                postSafe(() -> busy = false);
            }
        });
    }

    // ==================================================================== static API

    public static boolean isRunning() { return INSTANCE != null && !INSTANCE.destroyed; }

    public static boolean hasCapture() {
        return isRunning() && (INSTANCE.grab != null || INSTANCE.startingCapture);
    }

    public static int framesSeen() {
        return !isRunning() || INSTANCE.grab == null ? 0 : INSTANCE.grab.frameCount();
    }

    /** STOP from the app, the notification or the ♞ button: everything the app draws goes away immediately. */
    public static void stopEverything(Context ctx) {
        clearStarting();
        final OverlayService s = INSTANCE;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (s != null) {
                try { s.shutdown("Panel closed"); } catch (Throwable ignored) { }
            }
            purgeAllWindows();
        } else {
            MAIN.post(() -> {
                if (s != null) {
                    try { s.shutdown("Panel closed"); } catch (Throwable ignored) { }
                }
                purgeAllWindows();
            });
        }
        if (ctx != null) {
            try { ctx.stopService(new Intent(ctx, OverlayService.class)); } catch (Throwable ignored) { }
        }
    }

    private static volatile String lastTest = "";

    public static String lastTestResult() { return lastTest; }

    /** Grabs one picture right now and reports what happened - for the TEST button. */
    public static void selfTest() {
        final OverlayService s = INSTANCE;
        if (s == null || s.destroyed) { lastTest = "panel is not running - press START PANEL first"; return; }
        if (s.grab == null) { lastTest = "screen reading was never started - press START PANEL"; return; }
        lastTest = "testing... (one picture)";
        final Runnable nudge = () -> s.main.post(() -> {
            if (!s.destroyed && s.overlay != null) s.overlay.nudgeFrame();
        });
        s.worker.post(() -> {
            String r;
            try {
                long t0 = System.currentTimeMillis();
                Bitmap b = s.grab.grabFresh(3500, nudge);
                if (b == null) {
                    r = "FAILED: no picture after 3.5 s"
                            + (s.grab.lastError().isEmpty() ? "" : " (" + s.grab.lastError() + ")");
                } else {
                    r = "OK: " + b.getWidth() + "x" + b.getHeight() + " picture in "
                            + (System.currentTimeMillis() - t0) + " ms, " + s.grab.frameCount() + " total"
                            + (s.grab.lastFrameLookedBlank() ? " - but it looks blank/protected" : "");
                    b.recycle();
                }
            } catch (Throwable t) { r = "FAILED: " + describe(t); }
            final String fr = r;
            s.postSafe(() -> { lastTest = fr; s.setStatus("Screen test " + fr, null); });
        });
    }

    /** Hides only the floating ♞ button, marks stay visible (START PANEL brings it back). */
    public static void hideBubbleOnly() {
        final OverlayService s = INSTANCE;
        if (s == null || s.destroyed) { MAIN.post(OverlayService::purgeAllWindows); return; }
        MAIN.post(() -> { try { s.onHideBubble(); } catch (Throwable ignored) { } });
    }

    /**
     * Sweeps away stray windows when the app opens, but NEVER calls stopService() during
     * Activity.onResume() (which runs 1ms after onActivityResult() and used to kill the newly
     * started foreground service with ForegroundServiceDidNotStartInTimeException!).
     */
    public static void cleanStrays(Context ctx) {
        if (isRunning() || isStarting()) {
            if (isRunning()) {
                MAIN.post(() -> {
                    OverlayService s = INSTANCE;
                    if (s != null && !s.destroyed) {
                        s.hidePanel();
                        s.hideCalibration();
                        s.hideEditor();
                    }
                });
            }
            return;
        }
        purgeAllWindows();
    }

    public static void settingsChanged() {
        if (INSTANCE == null || INSTANCE.destroyed) return;
        INSTANCE.postSafe(INSTANCE::applySettingsNow);
    }

    /** Releases only the dead capture session in preparation for a fresh permission token. */
    public static void retryCapture() {
        if (INSTANCE == null || INSTANCE.destroyed) return;
        INSTANCE.main.post(() -> {
            OverlayService s = INSTANCE;
            if (s == null || s.destroyed) return;
            s.releaseCaptureOnly();
            s.lastProblem = "";
        });
    }

    public static void requestHintNow() {
        if (INSTANCE == null || INSTANCE.destroyed) return;
        INSTANCE.postSafe(() -> INSTANCE.requestHint(false));
    }

    public static void requestCalibration() {
        if (INSTANCE == null || INSTANCE.destroyed) return;
        INSTANCE.postSafe(() -> INSTANCE.showCalibration(INSTANCE.prefs.boardRect()));
    }

    public static void requestAutoDetect() {
        if (INSTANCE == null || INSTANCE.destroyed) return;
        INSTANCE.postSafe(() -> INSTANCE.autoDetectBoard(false));
    }

    public static void requestEditor() {
        if (INSTANCE == null || INSTANCE.destroyed) return;
        INSTANCE.postSafe(INSTANCE::showEditor);
    }

    public static void newGame() {
        if (INSTANCE == null || INSTANCE.destroyed) return;
        INSTANCE.postSafe(INSTANCE::onNewGame);
    }

    public static String currentFen() {
        return !isRunning() || INSTANCE.track == null ? null : INSTANCE.track.pos.fen();
    }

    public static String diagnostics() {
        return !isRunning() ? "panel not running" : diagnosticsText(INSTANCE);
    }

    public static String currentInfo() {
        if (!isRunning()) return "Panel is not running.";
        OverlayService s = INSTANCE;
        return "Screen capture: " + (s.grab != null ? "yes" : "no") + "\n"
                + "Engine: " + (s.engine != null && s.engine.isAlive() ? "ready" : "starting") + "\n"
                + "Board frame: " + (s.prefs.boardRect() == null ? "not set" : s.prefs.boardRect().toShortString()) + "\n"
                + "Position: " + (s.track == null ? "not tracked" : s.track.pos.fen());
    }

    public static void applyFen(String fen) {
        if (INSTANCE == null || INSTANCE.destroyed) return;
        INSTANCE.postSafe(() -> INSTANCE.doApplyFen(fen));
    }

    private void doApplyFen(String fen) {
        try {
            Board b = Board.fromFen(fen, prefs.whiteBottom());
            if (b.validate(prefs.whiteBottom()).contains("king")) {
                toast("That FEN has no proper kings");
                return;
            }
            String[] parts = fen.trim().split("\\s+");
            int side = (parts.length > 1 && parts[1].toLowerCase().startsWith("b")) ? Chess.BLACK : Chess.WHITE;
            Chess.Pos p = Chess.fromBoard(b, prefs.whiteBottom(), side);
            if (parts.length > 2 && parts[2].length() > 0 && !parts[2].equals("-")) p.castling = parts[2];
            if (track == null) track = new Track(p, prefs.whiteBottom());
            else track.reset(p);
            lastAutoPattern = null;
            setStatus("Position loaded", (side == Chess.WHITE ? "white" : "black") + " to move");
        } catch (Throwable t) {
            toast("Could not read that FEN");
        }
    }
}
