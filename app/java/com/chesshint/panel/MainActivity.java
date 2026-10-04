package com.chesshint.panel;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Home screen. Compact top navigation bar (no app title clutter), permission status card,
 * primary START/STOP action, quick board tools, and live diagnostics.
 */
public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIF = 1002;

    private Prefs prefs;
    private TextView statusDot, statusText, statusSub, actionBtn, summary, diagText, problemText;
    private LinearLayout problemCard;
    private boolean problemDismissed;
    private LinearLayout overlayRow, captureRow;
    private boolean captureGranted;

    @Override
    protected void onCreate(Bundle b) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(b);
        CrashGuard.install(this);
        prefs = new Prefs(this);
        try {
            setContentView(build());
        } catch (Throwable t) {
            CrashGuard.record(this, "home screen", t);
            setContentView(new TextView(this));
        }
        askNotifications();
    }

    @Override
    protected void onResume() {
        super.onResume();
        starting = false;
        try { OverlayService.cleanStrays(this); } catch (Throwable ignored) { }
        captureGranted = OverlayService.isRunning() && OverlayService.hasCapture();
        refresh();
    }

    // ------------------------------------------------------------------ UI

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 14);
        root.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 24));
        sv.addView(root);

        // ---- compact top navigation bar (no application name, zero wasted space)
        LinearLayout bar = Ui.row(this);
        TextView engineBadge = Ui.text(this, "\u265E  Stockfish 11  •  v2.2", 12f, Ui.TEXT_DIM, true);
        engineBadge.setBackground(Ui.round(Ui.CHIP, Ui.CARD_BORDER, this, 10));
        engineBadge.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        bar.addView(engineBadge);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView gear = Ui.text(this, "\u2699  Settings", 12.5f, Ui.TEXT, true);
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(Ui.round(Ui.CHIP, Ui.CARD_BORDER, this, 10));
        gear.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(gear);
        root.addView(bar, Ui.lpTop(this, 0, 2));

        // ---- status card
        LinearLayout card = Ui.card(this);
        LinearLayout head = Ui.row(this);
        statusDot = Ui.text(this, "\u25CF", 15f, Ui.DANGER, true);
        statusDot.setPadding(0, 0, Ui.dp(this, 8), 0);
        head.addView(statusDot);
        LinearLayout st = Ui.column(this);
        statusText = Ui.text(this, "Panel is stopped", 15f, Ui.TEXT, true);
        statusSub = Ui.text(this, "", 11.5f, Ui.TEXT_DIM, false);
        st.addView(statusText);
        st.addView(statusSub);
        head.addView(st, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(head);

        actionBtn = Ui.primaryButton(this, "START PANEL", Ui.ACCENT, 0xFF06210F, v -> onAction());
        card.addView(actionBtn, Ui.lpTop(this, 0, 14));

        card.addView(Ui.divider(this));
        overlayRow = permRow(card, "Display over other apps", "lets the panel float above your chess app");
        overlayRow.setOnClickListener(v -> openOverlaySettings());
        captureRow = permRow(card, "Screen reading", "lets the panel see the board (offline, nothing is uploaded)");
        captureRow.setOnClickListener(v -> {
            if (!OverlayService.hasCapture()) retryScreenReading();
            else toast("Screen reading is active");
        });

        root.addView(card, Ui.lpTop(this, 0, 10));

        // ---- problem card (only visible when something went wrong)
        problemCard = Ui.card(this);
        problemCard.setBackground(Ui.round(0xFF2A1620, 0xFF6B2B33, this, 16));
        problemCard.addView(Ui.sectionTitle2(this, "PROBLEM", 0xFFFF8A99));
        problemText = Ui.text(this, "", 12.5f, 0xFFFFD5DA, false);
        problemText.setLineSpacing(Ui.dp(this, 3), 1f);
        problemCard.addView(problemText, Ui.lpTop(this, 0, 4));
        LinearLayout pr = Ui.row(this);
        pr.addView(Ui.primaryButton(this, "RETRY SCREEN READING", 0xFFFF5C6C, 0xFF2A0810, v -> retryScreenReading()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        problemCard.addView(pr, Ui.lpTop(this, 0, 10));
        LinearLayout pr2 = Ui.row(this);
        pr2.addView(Ui.ghostButton(this, "Share log", v -> shareLog()), weightLeft());
        pr2.addView(Ui.ghostButton(this, "Dismiss", v -> {
            problemDismissed = true;
            refresh();
        }), weightRight());
        problemCard.addView(pr2, Ui.lpTop(this, 0, 8));
        problemCard.setVisibility(View.GONE);
        root.addView(problemCard, Ui.lpTop(this, 0, 12));

        // ---- shortcuts
        LinearLayout quick = Ui.card(this);
        quick.addView(Ui.sectionTitle(this, "SHORTCUTS"));
        LinearLayout r1 = Ui.row(this);
        r1.addView(Ui.ghostButton(this, "Fit board", v -> {
            OverlayService.requestCalibration();
            toast("Drag the yellow frame over the board");
        }), weightLeft());
        r1.addView(Ui.ghostButton(this, "Fix pieces", v -> {
            OverlayService.requestEditor();
            toast("Tap squares to correct the pieces");
        }), weightRight());
        root.addView(quick, Ui.lpTop(this, 0, 12));
        quick.addView(r1, Ui.lpTop(this, 0, 4));

        LinearLayout r2 = Ui.row(this);
        r2.addView(Ui.ghostButton(this, "New game", v -> {
            OverlayService.newGame();
            toast("Position reset to the standard start");
        }), weightLeft());
        r2.addView(Ui.ghostButton(this, "Show my move", v -> {
            OverlayService.requestHintNow();
            toast("Reading the board…");
        }), weightRight());
        quick.addView(r2, Ui.lpTop(this, 0, 8));

        // ---- current settings summary (tap = settings)
        LinearLayout sum = Ui.card(this);
        LinearLayout sumHead = Ui.row(this);
        sumHead.addView(Ui.sectionTitle(this, "CURRENT SETUP"));
        sum.addView(sumHead);
        summary = Ui.text(this, "", 12.5f, Ui.TEXT, false);
        summary.setLineSpacing(Ui.dp(this, 3), 1f);
        sum.addView(summary);
        sum.addView(Ui.text(this, "Tap to change →", 12f, Ui.ACCENT, true), Ui.lpTop(this, 0, 10));
        sum.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        root.addView(sum, Ui.lpTop(this, 0, 12));

        // ---- diagnostics
        LinearLayout diag = Ui.card(this);
        diag.addView(Ui.sectionTitle(this, "DIAGNOSTICS"));
        diagText = Ui.text(this, "", 11.5f, Ui.TEXT_DIM, false);
        diagText.setTypeface(android.graphics.Typeface.MONOSPACE);
        diagText.setTextIsSelectable(true);
        diag.addView(diagText, Ui.lpTop(this, 0, 4));
        LinearLayout dr = Ui.row(this);
        dr.addView(Ui.ghostButton(this, "Refresh", v -> refresh()), weightLeft());
        dr.addView(Ui.ghostButton(this, "Copy", v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("chesshint", diagText.getText()));
            toast("Diagnostics copied");
        }), weightRight());
        diag.addView(dr, Ui.lpTop(this, 0, 6));
        LinearLayout dr2 = Ui.row(this);
        dr2.addView(Ui.ghostButton(this, "Close floating icon", v -> {
            OverlayService.stopEverything(this);
            refresh();
            toast("Floating icons removed");
        }), weightLeft());
        dr2.addView(Ui.ghostButton(this, "Retry screen reading", v -> retryScreenReading()), weightRight());
        diag.addView(dr2, Ui.lpTop(this, 0, 8));
        LinearLayout dr3 = Ui.row(this);
        dr3.addView(Ui.ghostButton(this, "Clear log", v -> {
            CrashGuard.clear(this);
            refresh();
        }), weightLeft());
        dr3.addView(Ui.ghostButton(this, "Hide ♞ button", v -> {
            OverlayService.hideBubbleOnly();
            toast("Floating ♞ hidden");
        }), weightRight());
        diag.addView(dr3, Ui.lpTop(this, 0, 8));
        LinearLayout dr4 = Ui.row(this);
        dr4.addView(Ui.primaryButton(this, "TEST SCREEN READING", Ui.ACCENT2, 0xFF06121F, v -> {
            OverlayService.selfTest();
            toast("Testing one picture...");
            new Handler(Looper.getMainLooper()).postDelayed(this::refresh, 1200);
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        diag.addView(dr4, Ui.lpTop(this, 0, 8));
        root.addView(diag, Ui.lpTop(this, 0, 12));

        // ---- how to (short)
        LinearLayout how = Ui.card(this);
        how.addView(Ui.sectionTitle(this, "HOW TO USE"));
        how.addView(Ui.text(this, "1.  Allow both permissions above, then press START PANEL.\n"
                + "2.  Open your chess game — your pieces must be at the BOTTOM (use ME: WHITE/BLACK in the ♞ panel if not).\n"
                + "3.  Tap the floating ♞ → SHOW MY MOVE (or keep AUTO: ON). Select any piece on the board to see its legal moves.\n"
                + "4.  Tap the red ✕ on the ♞ bubble (or ✖ STOP & CLOSE inside the panel) to close it anytime.",
                12.5f, Ui.TEXT_DIM, false), Ui.lpTop(this, 0, 6));
        root.addView(how, Ui.lpTop(this, 0, 12));

        return sv;
    }

    private LinearLayout.LayoutParams weightLeft() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = Ui.dp(this, 5);
        return p;
    }

    private LinearLayout.LayoutParams weightRight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.leftMargin = Ui.dp(this, 5);
        return p;
    }

    private LinearLayout permRow(LinearLayout parent, String title, String sub) {
        LinearLayout r = Ui.row(this);
        r.setPadding(Ui.dp(this, 2), Ui.dp(this, 9), Ui.dp(this, 2), Ui.dp(this, 9));
        TextView mark = Ui.text(this, "\u2716", 15f, Ui.DANGER, true);
        mark.setPadding(0, 0, Ui.dp(this, 10), 0);
        LinearLayout texts = Ui.column(this);
        texts.addView(Ui.text(this, title, 13.5f, Ui.TEXT, false));
        texts.addView(Ui.text(this, sub, 11f, Ui.TEXT_DIM, false));
        r.addView(mark);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = Ui.text(this, "\u203A", 20f, Ui.TEXT_DIM, false);
        r.addView(arrow);
        parent.addView(r);
        r.setTag(mark);
        return r;
    }

    // ------------------------------------------------------------------ logic

    private boolean starting;

    private void onAction() {
        if (starting || OverlayService.isStarting()) { toast("Already starting..."); return; }
        if (OverlayService.isRunning()) {
            OverlayService.stopEverything(this);
            refresh();
            toast("Panel stopped — floating ♞ removed");
            return;
        }
        requestScreenCapture();
    }

    private void retryScreenReading() {
        if (starting || OverlayService.isStarting()) { toast("Already starting..."); return; }
        problemDismissed = false;
        OverlayService.retryCapture();
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            toast("First allow \"Display over other apps\"");
            openOverlaySettings();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            starting = true;
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Throwable t) {
            starting = false;
            toast("This device refused screen reading: " + t.getMessage());
        }
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF); } catch (Throwable ignored) { }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        starting = false;
        if (res == RESULT_OK && data != null) {
            problemDismissed = false;
            CrashGuard.clearLastProblem();
            CrashGuard.step(this, "onActivityResult RESULT_OK");
            OverlayService.markStarting(res, data);
            Intent i = new Intent(this, OverlayService.class);
            i.putExtra(OverlayService.EXTRA_CODE, res);
            i.putExtra(OverlayService.EXTRA_DATA, data);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
                captureGranted = true;
                toast("Starting panel…");
            } catch (Throwable t) {
                try {
                    startService(i);
                    captureGranted = true;
                    toast("Starting panel…");
                } catch (Throwable t2) {
                    OverlayService.clearStarting();
                    CrashGuard.record(this, "startForegroundService", t2);
                    toast("Could not start the panel: " + t2.getMessage());
                }
            }
            Handler h = new Handler(Looper.getMainLooper());
            h.postDelayed(this::refresh, 500);
            h.postDelayed(this::refresh, 1500);
        } else {
            OverlayService.clearStarting();
            toast("Screen reading was cancelled");
            refresh();
        }
    }

    private void refresh() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean isStarting = OverlayService.isStarting();
        boolean running = OverlayService.isRunning();
        captureGranted = (running && OverlayService.hasCapture()) || isStarting;

        TextView om = (TextView) overlayRow.getTag();
        om.setText(overlay ? "\u2714" : "\u2716");
        om.setTextColor(overlay ? Ui.ACCENT : Ui.DANGER);
        TextView cm = (TextView) captureRow.getTag();
        cm.setText(captureGranted ? "\u2714" : "\u2716");
        cm.setTextColor(captureGranted ? Ui.ACCENT : Ui.DANGER);

        statusDot.setTextColor((running || isStarting) ? Ui.ACCENT : Ui.DANGER);
        statusText.setText(running ? "Panel is running" : (isStarting ? "Starting panel…" : "Panel is stopped"));
        int frames = OverlayService.framesSeen();
        statusSub.setText(running
                ? (OverlayService.hasCapture()
                    ? (frames > 0 ? ("screen reading OK (" + frames + " pictures) — tap the ♞ bubble")
                                  : "waiting for the first picture…")
                    : "waiting for screen reading…")
                : (isStarting ? "starting screen reading…" : "allow both permissions, then press START PANEL"));
        actionBtn.setText((running || isStarting) ? "STOP PANEL" : "START PANEL");
        actionBtn.setBackground(Ui.round((running || isStarting) ? 0xFF2A1620 : Ui.ACCENT, 0, this, 14));
        actionBtn.setTextColor((running || isStarting) ? 0xFFFF7B8A : 0xFF06210F);

        if (diagText != null) {
            String info = OverlayService.diagnostics();
            diagText.setText(info);
        }

        String problem = OverlayService.lastProblem();
        if ((problem == null || problem.isEmpty()) && !running && !isStarting) {
            problem = CrashGuard.lastProblemSummary();
        }
        boolean show = problem != null && !problem.isEmpty() && !problemDismissed;
        problemCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) problemText.setText(problem);

        String styleName = new String[]{"Arrow + rings", "Arrow only", "Rings only", "Squares"}[Math.min(3, prefs.markerStyle())];
        String strength = prefs.elo() <= 0 ? "MAX (superhuman)" : prefs.elo() + " elo";
        String fen = OverlayService.currentFen();
        summary.setText("Marks: " + styleName + "  •  " + Markers.PALETTE_NAME[prefs.palette()] + "\n"
                + "Strength: " + strength + "  •  " + (prefs.movetime() / 1000f) + "s per hint\n"
                + "Me: " + (prefs.whiteBottom() ? "white at the bottom" : "black at the bottom")
                + "  •  Auto: " + (prefs.auto() ? "on" : "off") + "\n"
                + (fen == null ? "Position: not tracked yet" : "Position: " + fen));
    }

    private void shareLog() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("v2.2\n");
            sb.append("android ").append(android.os.Build.VERSION.RELEASE)
              .append(" (api ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
            sb.append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append("\n\n");
            String p = OverlayService.lastProblem();
            if (p != null && !p.isEmpty()) sb.append("problem: ").append(p).append("\n\n");
            sb.append(OverlayService.diagnostics()).append("\n\n");
            String log = CrashGuard.lastCrash(this);
            if (log != null && !log.isEmpty()) sb.append("log:\n").append(log);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "Diagnostic log");
            i.putExtra(Intent.EXTRA_TEXT, sb.toString());
            startActivity(Intent.createChooser(i, "Share log"));
        } catch (Throwable t) {
            toast("Could not open the share sheet");
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
