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

import java.util.Locale;

/**
 * Everything happens here: floating windows, screen reading, game tracking and the engine.
 *
 * Android 14+ note: the media-projection foreground service has to be running BEFORE
 * MediaProjectionManager.getMediaProjection() is called, otherwise the system throws a
 * SecurityException.  That ordering is what used to crash the app when the screen
 * recording permission was accepted.
 */
public class OverlayService extends Service implements
        BubbleView.Listener, PanelView.Listener, CalibrationView.Listener, BoardEditorView.Listener {

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String ACTION_STOP = "chesshint.stop";
    private static final String CHANNEL = "chesshint";
    private static final int NOTIF_ID = 42;

    private static OverlayService INSTANCE;

    private WindowManager wm;
    private Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** run on the UI thread, never let an exception kill the panel */
    private void postSafe(Runnable r) {
        main.post(() -> CrashGuard.guard(this, r));
    }
    private HandlerThread workerThread;
    private Handler worker;

    private MediaProjection projection;
    private ScreenGrab grab;
    private UciEngine engine;
    private Track track;

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
    private String lastProblem = "";
    private boolean windowAdded;
    private int[] lastAutoPattern;
    private String lastStatus;
    private Vibrator vibrator;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sp, key) -> main.post(this::applySettingsNow);

    // ==================================================================== lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = new Prefs(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        workerThread = new HandlerThread("hint-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        engine = new UciEngine();
        createChannel();
        prefs.raw().registerOnSharedPreferenceChangeListener(prefListener);
        worker.post(() -> {
            boolean ok;
            try {
                engine.setBudget(Tune.EngineBudget.hashMb, Tune.EngineBudget.threads);
                ok = engine.start();
            } catch (Throwable t) {
                CrashGuard.record(this, "engine start", t);
                ok = false;
            }
            final boolean started = ok;
            postSafe(() -> setStatus(started ? "Ready" : "Engine error: " + UciEngine.libraryError(),
                    started ? null : "tap STOP then START again"));
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null) {
                if (ACTION_STOP.equals(intent.getAction())) {
                    stopSelf();
                    return START_NOT_STICKY;
                }
                if (intent.hasExtra(EXTRA_DATA)) {
                    startProjection(intent);
                } else if (projection == null) {
                    stopSelf();
                    return START_NOT_STICKY;
                }
            } else if (projection == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            main.postDelayed(this::ensureWindows, 150);
        } catch (Throwable t) {
            toast("Panel error: " + t.getClass().getSimpleName());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { prefs.raw().unregisterOnSharedPreferenceChangeListener(prefListener); } catch (Throwable ignored) { }
        try { removeAllWindows(); } catch (Throwable ignored) { }
        try { if (grab != null) grab.release(); } catch (Throwable ignored) { }
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
        try { if (engine != null) engine.stop(); } catch (Throwable ignored) { }
        try { workerThread.quitSafely(); } catch (Throwable ignored) { }
        INSTANCE = null;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ==================================================================== setup

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Chess Hint Panel", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Chess Hint Panel")
                .setContentText(text)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "STOP", stop).build())
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(text == null ? "Running" : text));
        } catch (Throwable ignored) { }
    }

    /**
     * Takes the screen-reading token and starts capturing.
     *
     * The order is different per Android version and getting it wrong is exactly what made
     * the panel close right after the "start recording" dialog:
     *
     *   API 26-28 : plain foreground service, then getMediaProjection()
     *   API 29+   : getMediaProjection() THROWS SecurityException unless a foreground service
     *               with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION is already running.
     *               Android 14+ asks for the very same order.
     *
     * So: foreground service first, then the projection - and if the platform disagrees we
     * simply try the other way round instead of dying.
     */
    private void startProjection(Intent intent) {
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) { captureProblem("Screen reading data was empty — please try again"); return; }

        // ---- foreground service first (required from Android 10 on)
        boolean foregroundFirst = Build.VERSION.SDK_INT >= 29;
        boolean foregroundDone = false;
        if (foregroundFirst) {
            foregroundDone = goForeground("Starting screen reading…");
        }

        // ---- the projection token
        String firstError = "";
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
        } catch (Throwable t) {
            firstError = describe(t);
            projection = null;
            CrashGuard.record(this, "getMediaProjection (foreground first)", t);
        }

        // ---- fall back to the other order if the platform wanted it that way
        if (projection == null) {
            if (foregroundDone) {
                try { stopForeground(true); } catch (Throwable ignored) { }
                foregroundDone = false;
            }
            try {
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                projection = mpm.getMediaProjection(code, data);
                if (projection != null && !foregroundDone) {
                    foregroundDone = goForeground("Starting screen reading…");
                }
            } catch (Throwable t) {
                CrashGuard.record(this, "getMediaProjection (projection first)", t);
                captureProblem("Screen reading refused: " + describe(t)
                        + (firstError.isEmpty() ? "" : " / " + firstError));
                return;
            }
        }
        if (projection == null) {
            captureProblem("Screen reading permission was not granted");
            return;
        }
        if (!foregroundDone) {
            // API 26-28, or the fallback path: start it now so the panel survives in background
            foregroundDone = goForeground("Ready — tap the ♞ button");
        }

        final MediaProjection p = projection;
        try {
            p.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    postSafe(() -> captureProblem("Screen reading was stopped by the system — press RETRY"));
                }
            }, main);
        } catch (Throwable ignored) { }

        worker.post(() -> {
            try {
                grab = new ScreenGrab(this, p, worker);
                if (!grab.init()) {
                    final String err = grab.lastError();
                    postSafe(() -> {
                        ensureWindows();
                        captureProblem("Screen capture failed" + (err.isEmpty() ? "" : " (" + err + ")"));
                    });
                    return;
                }
                Bitmap first = grab.grabWait(4000);
                if (first == null) {
                    postSafe(() -> {
                        ensureWindows();
                        captureProblem("No picture arriving from the screen yet");
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
                ensureWindows();
                if (prefs.hasRect()) {
                    overlay.board = prefs.boardRect();
                    overlay.invalidate();
                    setStatus("Ready", "open your chess game, then tap ♞");
                } else {
                    autoDetectBoard(true);   // never opens the frame by itself
                }
            });
        });
    }

    /** starts the foreground service, with the media-projection type where the platform has it */
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
            CrashGuard.record(this, "startForeground", t);
            return false;
        }
    }

    /**
     * Keeps the panel alive when screen reading has a problem: the reason is stored, shown in
     * the app's Diagnostics card (so it can be shared) and the bubble stays usable, instead of
     * the app closing itself.
     */
    private void captureProblem(String msg) {
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

    private void fail(String msg) {
        lastStatus = msg;
        toast(msg);
        stopSelf();
    }

    private void ensureWindows() {
        try {
            if (overlay == null) addOverlay();
            if (bubble == null) addBubble();
            if (panel != null) panel.refresh(prefs, configLine());
        } catch (Throwable t) {
            toast("Could not show the panel: " + t.getMessage());
            stopSelf();
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
        windowAdded = true;
    }

    private void addBubble() {
        bubble = new BubbleView(this, this);
        bubble.setAuto(prefs.auto());
        bubble.setScaleFactor(prefs.bubbleScale());
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
    }

    private void removeAllWindows() {
        if (windowAdded && overlay != null) try { wm.removeView(overlay); } catch (Throwable ignored) { }
        if (bubble != null) try { wm.removeView(bubble); } catch (Throwable ignored) { }
        if (panel != null) try { wm.removeView(panel); } catch (Throwable ignored) { }
        if (calibHost != null) try { wm.removeView(calibHost); } catch (Throwable ignored) { }
        if (editorHost != null) try { wm.removeView(editorHost); } catch (Throwable ignored) { }
        overlay = null; bubble = null; panel = null; calibHost = null; editorHost = null;
        calib = null; editor = null; windowAdded = false;
    }

    /** wrapper so a plain View can live in its own window */
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
            try { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
        });
    }

    private static String diagnosticsText(OverlayService s) {
        StringBuilder sb = new StringBuilder();
        ScreenGrab g = s.grab;
        sb.append("screen reading: ").append(g == null ? "not running" : ("active, " + g.frameCount() + " frames"))
                .append(g != null && !g.lastError().isEmpty() ? "  (" + g.lastError() + ")" : "").append('\n');
        sb.append("engine: ").append(s.engine != null && s.engine.isAlive() ? "ready" : "not running")
                .append("  (hash 16 MB)").append('\n');
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
        if (s != null && (s.startsWith("YOUR") || s.startsWith("Ready") || s.startsWith("Waiting"))) updateNotification(s);
    }

    private void finishBusy(String statusText) {
        busy = false;
        if (overlay != null) overlay.setBusy(false);
        setStatus(statusText, null);
    }

    private void buzz() {
        if (!prefs.vibrations() || vibrator == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(28);
        } catch (Throwable ignored) { }
    }

    /** called whenever any setting changes (from the settings screen) */
    private void applySettingsNow() {
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
            try { wm.addView(panel, panelLp); } catch (Throwable t) { panel = null; }
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
        if (bubbleLp == null) return;
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

    // ==================================================================== panel

    @Override public void onMove() { hidePanel(); requestHint(false); }

    @Override public void onAuto() {
        prefs.setAuto(!prefs.auto());
        panel.refresh(prefs, configLine());
        if (bubble != null) bubble.setAuto(prefs.auto());
        if (prefs.auto()) { startAuto(); setStatus("Auto on", "watching for your turn"); }
        else { stopAuto(); setStatus("Auto off", null); }
    }

    @Override public void onSettings() {
        hidePanel();
        try {
            startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast("Could not open settings");
        }
    }

    @Override public void onFit() { hidePanel(); showCalibration(prefs.boardRect()); }
    @Override public void onFix() { hidePanel(); showEditor(); }

    @Override public void onFlip() {
        prefs.setWhiteBottom(!prefs.whiteBottom());
        if (track != null) track.setWhiteBottom(prefs.whiteBottom());
        if (overlay != null) overlay.applyPrefs(prefs);
        if (panel != null) panel.refresh(prefs, configLine());
        setStatus(prefs.whiteBottom() ? "Bottom = WHITE (you)" : "Bottom = BLACK (you)", null);
    }

    @Override public void onNewGame() {
        hidePanel();
        Chess.Pos p = Chess.fromBoard(Board.starting(prefs.whiteBottom()), prefs.whiteBottom(), Chess.WHITE);
        if (track == null) track = new Track(p, prefs.whiteBottom());
        else track.reset(p);
        lastAutoPattern = null;
        if (overlay != null) overlay.clearHint();
        setStatus("New game", "white to move");
    }

    @Override public void onHide() {
        hidePanel();
        if (overlay != null) {
            overlay.clearHint();
            overlay.setStatus(null, null);
        }
    }

    @Override public void onStop() { stopSelf(); }

    private void hidePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (Throwable ignored) { }
            panel = null;
        }
    }

    // ==================================================================== calibration / editor

    private void showCalibration(Rect start) {
        if (calibHost != null) return;
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
        try { wm.addView(calibHost, lp); } catch (Throwable t) { calibHost = null; calib = null; toast("Could not open the frame tool"); }
    }

    private void hideCalibration() {
        if (calibHost != null) {
            try { wm.removeView(calibHost); } catch (Throwable ignored) { }
            calibHost = null; calib = null;
        }
    }

    @Override
    public void onCalibSave(Rect r) {
        if (r.width() < 100) { toast("Frame too small"); return; }
        int side = Math.max(r.width(), r.height());
        Rect sq = new Rect(r.left, r.top, r.left + side, r.top + side);
        prefs.setBoardRect(sq);
        if (overlay != null) { overlay.board = sq; overlay.showFrame = true; overlay.invalidate(); }
        hideCalibration();
        setStatus("Board frame saved", null);
    }

    @Override public void onCalibCancel() { hideCalibration(); }

    @Override public void onCalibAuto() { autoDetectBoard(false); }

    private void autoDetectBoard(boolean quiet) {
        if (grab == null) { toast("Screen reading is not running"); return; }
        setStatus("Looking for the board…", null);
        worker.post(() -> {
            Bitmap b = null;
            Rect r = null;
            try {
                b = grab.grabWait(2500);
                r = b == null ? null : Vision.detect(b);
            } catch (Throwable t) {
                CrashGuard.record(this, "board detection", t);
            }
            if (b != null) b.recycle();
            final Rect found = r;
            postSafe(() -> {
                if (found == null) {
                    setStatus("No chess board on screen", "open your game and tap FIT BOARD");
                    toast("Open your chess app, then tap FIT BOARD");
                } else {
                    prefs.setBoardRect(found);
                    if (overlay != null) { overlay.board = found; overlay.invalidate(); }
                    if (calib != null) { calib.rect.set(found); calib.invalidate(); }
                    setStatus("Board found", null);
                }
            });
        });
    }

    private void showEditor() {
        if (editorHost != null) return;
        Board b = track != null ? Chess.toBoard(track.pos, prefs.whiteBottom()) : Board.starting(prefs.whiteBottom());
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
        try { wm.addView(editorHost, lp); } catch (Throwable t) { editorHost = null; editor = null; toast("Could not open the editor"); }
    }

    private void hideEditor() {
        if (editorHost != null) {
            try { wm.removeView(editorHost); } catch (Throwable ignored) { }
            editorHost = null; editor = null;
        }
    }

    @Override
    public void onEditorApply(Board b, boolean whiteBottom) {
        prefs.setWhiteBottom(whiteBottom);
        if (overlay != null) overlay.applyPrefs(prefs);
        Chess.Pos p = Chess.fromBoard(b, whiteBottom, whiteBottom ? Chess.WHITE : Chess.BLACK);
        if (track == null) track = new Track(p, whiteBottom);
        else track.reset(p);
        lastAutoPattern = null;
        hideEditor();
        String warn = b.validate(whiteBottom);
        if (!warn.isEmpty()) toast("Heads up: " + warn);
        setStatus("Position set", (whiteBottom ? "white" : "black") + " to move");
    }

    @Override public void onEditorCancel() { hideEditor(); }

    @Override public void onEditorFlip() {
        if (editor != null) {
            editor.whiteBottom = !editor.whiteBottom;
            editor.invalidate();
        }
    }

    // ==================================================================== hint

    private void requestHint(final boolean auto) {
        if (busy) return;
        if (grab == null) {
            setStatus("Screen reading is not active", "open the app and press START PANEL");
            toast("Screen reading is not active — open the app and press START PANEL");
            return;
        }
        busy = true;
        if (overlay != null) overlay.setBusy(true);
        setStatus(auto ? "Auto: reading board…" : "Reading board…", null);

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
        {
            Bitmap bmp = null;
            try {
                bmp = auto ? grab.grabWait(1500) : grab.grabStable();
            } catch (Throwable ignored) { }
            if (bmp == null) {
                postSafe(() -> finishBusy("Could not read the screen"));
                return;
            }
            Rect r = prefs.boardRect();
            if (r == null) {
                Rect det = null;
                try { det = Vision.detect(bmp); } catch (Throwable ignored) { }
                if (det == null) {
                    bmp.recycle();
                    postSafe(() -> {
                        finishBusy("No chess board on screen");
                        toast("Open your chess game, then tap FIT BOARD once");
                    });
                    return;
                }
                prefs.setBoardRect(det);
                r = det;
                final Rect rr = det;
                postSafe(() -> { if (overlay != null) { overlay.board = rr; overlay.invalidate(); } });
            }

            Vision.Result res;
            try {
                res = Vision.read(bmp, r);
            } catch (Throwable t) {
                res = new Vision.Result();
            }
            bmp.recycle();
            if (!res.ok) {
                postSafe(() -> finishBusy("Could not read the board"));
                return;
            }

            // ---- keep the game tracker in sync
            if (track == null) {
                Chess.Pos start = Chess.fromBoard(Board.starting(prefs.whiteBottom()), prefs.whiteBottom(), Chess.WHITE);
                Track t = new Track(start, prefs.whiteBottom());
                Track.Update u = t.observe(res.colorPat, res.conf);
                if (u.ok && u.moves.size() <= 4) {
                    track = t;
                } else {
                    Chess.Pos p = Chess.fromBoard(Board.starting(prefs.whiteBottom()), prefs.whiteBottom(), Chess.WHITE);
                    track = new Track(p, prefs.whiteBottom());
                    postSafe(() -> {
                        finishBusy("Set the pieces once, then everything is tracked");
                        showEditor();
                    });
                    return;
                }
            } else {
                Track.Update u = track.observe(res.colorPat, res.conf);
                if (!u.ok && u.changed) {
                    postSafe(() -> finishBusy("Board out of sync — tap FIX PIECES"));
                    return;
                }
            }

            final Track tr = track;
            final int userSide = prefs.whiteBottom() ? Chess.WHITE : Chess.BLACK;
            boolean myTurn = tr.pos.side == userSide;

            if (auto && !myTurn) {
                postSafe(() -> finishBusy("Waiting for the enemy…"));
                return;
            }

            String fen = tr.pos.copy().fen();
            if (!myTurn) {
                Chess.Pos p2 = tr.pos.copy();
                p2.side = userSide;
                fen = p2.fen();
            }
            if (!engine.isAlive()) {
                if (!engine.start()) {
                    final String err = UciEngine.libraryError();
                    postSafe(() -> finishBusy("Engine error: " + err));
                    return;
                }
            }
            String bm = engine.bestMove(fen, prefs.movetime(), prefs.elo());
            String mv = UciEngine.moveOf(bm);
            final int depth = engine.depth, cp = engine.scoreCp, mate = engine.mateIn;

            if (mv == null || mv.length() < 4) {
                postSafe(() -> finishBusy("No move found (game over?)"));
                return;
            }

            final int fromIdx = Board.squareFromName(mv.substring(0, 2), prefs.whiteBottom());
            final int toIdx = Board.squareFromName(mv.substring(2, 4), prefs.whiteBottom());
            if (fromIdx < 0 || toIdx < 0) {
                postSafe(() -> finishBusy("Bad move from the engine"));
                return;
            }
            final String promo = mv.length() > 4 ? mv.substring(4) : "";
            int fsq = Chess.sqFromName(mv.substring(0, 2));
            final int piece = fsq >= 0 ? tr.pos.cb[fsq] : 0;
            final String text = Markers.label(piece, mv.substring(0, 2), mv.substring(2, 4), promo, myTurn);

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
                finishBusy(text);
            });
        }
    }

    // ==================================================================== auto mode

    private boolean autoRunning;

    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            if (!prefs.auto()) { autoRunning = false; return; }
            if (!busy && grab != null) checkAuto();
            main.postDelayed(this, 1000);
        }
    };

    private void startAuto() {
        if (autoRunning) return;
        autoRunning = true;
        main.removeCallbacks(autoTick);
        main.postDelayed(autoTick, 600);
    }

    private void stopAuto() {
        autoRunning = false;
        main.removeCallbacks(autoTick);
    }

    private void checkAuto() {
        busy = true;
        if (overlay != null) overlay.setBusy(true);
        worker.post(() -> {
            Bitmap bmp = null;
            Rect r = prefs.boardRect();
            Vision.Result res = null;
            try {
                bmp = grab.grabWait(1500);
                if (bmp != null && r != null) res = Vision.read(bmp, r);
            } catch (Throwable t) {
                CrashGuard.record(this, "auto read", t);
            }
            if (bmp != null) bmp.recycle();
            if (res == null || !res.ok) {
                postSafe(() -> finishBusy(lastStatus != null ? lastStatus : "Watching…"));
                return;
            }
            int[] pat = res.colorPat;
            boolean stable = lastAutoPattern != null && java.util.Arrays.equals(lastAutoPattern, pat);
            lastAutoPattern = pat.clone();
            if (!stable) {
                postSafe(() -> finishBusy("Watching…"));
                return;
            }
            if (track != null && java.util.Arrays.equals(Chess.pattern(track.pos, prefs.whiteBottom()), pat)) {
                postSafe(() -> finishBusy("Watching…"));
                return;
            }
            busy = false;
            if (overlay != null) overlay.setBusy(false);
            requestHint(true);
        });
    }

    // ==================================================================== static API

    public static boolean isRunning() { return INSTANCE != null; }

    public static boolean hasCapture() { return INSTANCE != null && INSTANCE.grab != null; }

    public static void settingsChanged() {
        if (INSTANCE == null) return;
        INSTANCE.main.post(INSTANCE::applySettingsNow);
    }

    /** stop a dead capture and ask for a fresh permission - used by the RETRY button */
    public static void retryCapture() {
        if (INSTANCE == null) return;
        INSTANCE.main.post(() -> {
            OverlayService s = INSTANCE;
            try {
                if (s.grab != null) { s.grab.release(); s.grab = null; }
                if (s.projection != null) { s.projection.stop(); s.projection = null; }
            } catch (Throwable ignored) { }
            s.lastProblem = "";
            s.stopSelf();
        });
    }

    public static void requestHintNow() {
        if (INSTANCE == null) return;
        INSTANCE.main.post(() -> INSTANCE.requestHint(false));
    }

    public static void requestCalibration() {
        if (INSTANCE == null) return;
        INSTANCE.main.post(() -> INSTANCE.showCalibration(INSTANCE.prefs.boardRect()));
    }

    public static void requestAutoDetect() {
        if (INSTANCE == null) return;
        INSTANCE.main.post(() -> INSTANCE.autoDetectBoard(false));
    }

    public static void requestEditor() {
        if (INSTANCE == null) return;
        INSTANCE.main.post(INSTANCE::showEditor);
    }

    public static void newGame() {
        if (INSTANCE == null) return;
        INSTANCE.main.post(INSTANCE::onNewGame);
    }

    public static String currentFen() {
        return INSTANCE == null || INSTANCE.track == null ? null : INSTANCE.track.pos.fen();
    }

    public static String diagnostics() {
        return INSTANCE == null ? "panel not running" : diagnosticsText(INSTANCE);
    }

    public static String currentInfo() {
        if (INSTANCE == null) return "Panel is not running.";
        OverlayService s = INSTANCE;
        return "Screen capture: " + (s.grab != null ? "yes" : "no") + "\n"
                + "Engine: " + (s.engine != null && s.engine.isAlive() ? "ready" : "starting") + "\n"
                + "Board frame: " + (s.prefs.boardRect() == null ? "not set" : s.prefs.boardRect().toShortString()) + "\n"
                + "Position: " + (s.track == null ? "not tracked" : s.track.pos.fen());
    }

    public static void applyFen(String fen) {
        if (INSTANCE == null) return;
        INSTANCE.main.post(() -> INSTANCE.doApplyFen(fen));
    }

    private void doApplyFen(String fen) {
        try {
            Board b = Board.fromFen(fen, prefs.whiteBottom());
            if (b.validate(prefs.whiteBottom()).contains("king")) { toast("That FEN has no proper kings"); return; }
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
