package com.chesshint.panel;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Home screen. Deliberately compact: a one line top bar, the two permissions, one big
 * START/STOP button and a few shortcuts. Everything else lives in Settings.
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
        captureGranted = OverlayService.isRunning() && OverlayService.hasCapture();
        refresh();
    }

    // ------------------------------------------------------------------ UI

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 14);
        root.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 26));
        sv.addView(root);

        // ---- one line top bar (no wasted space)
        LinearLayout bar = Ui.row(this);
        TextView icon = Ui.text(this, "\u265E", 21f, Ui.ACCENT2, true);
        icon.setPadding(0, 0, Ui.dp(this, 8), 0);
        bar.addView(icon);
        LinearLayout titles = Ui.column(this);
        titles.addView(Ui.text(this, "Chess Hint Panel", 15.5f, Ui.TEXT, true));
        titles.addView(Ui.text(this, "v1.1  •  Stockfish inside", 10.5f, Ui.TEXT_DIM, false));
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView gear = Ui.text(this, "\u2699", 22f, Ui.TEXT, false);
        gear.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(gear);
        root.addView(bar, Ui.lpTop(this, 0, 6));

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
        captureRow.setOnClickListener(v -> { if (!OverlayService.isRunning()) onAction(); });

        root.addView(card, Ui.lpTop(this, 0, 12));

        // ---- problem card (only visible when something went wrong)
        problemCard = Ui.card(this);
        problemCard.setBackground(Ui.round(0xFF2A1620, 0xFF6B2B33, this, 16));
        problemCard.addView(Ui.sectionTitle2(this, "PROBLEM", 0xFFFF8A99));
        problemText = Ui.text(this, "", 12.5f, 0xFFFFD5DA, false);
        problemText.setLineSpacing(Ui.dp(this, 3), 1f);
        problemCard.addView(problemText, Ui.lpTop(this, 0, 4));
        LinearLayout pr = Ui.row(this);
        pr.addView(Ui.primaryButton(this, "RETRY SCREEN READING", 0xFFFF5C6C, 0xFF2A0810, v -> {
            OverlayService.retryCapture();
            new android.os.Handler().postDelayed(this::onAction, 900);
        }), weight());
        problemCard.addView(pr, Ui.lpTop(this, 0, 10));
        LinearLayout pr2 = Ui.row(this);
        pr2.addView(Ui.ghostButton(this, "Share log", v -> shareLog()), weight());
        pr2.addView(Ui.ghostButton(this, "Dismiss", v -> {
            problemDismissed = true;
            refresh();
        }), weight());
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
        }), weight());
        r1.addView(Ui.ghostButton(this, "Fix pieces", v -> {
            OverlayService.requestEditor();
            toast("Tap squares to correct the pieces");
        }), weight());
        root.addView(quick, Ui.lpTop(this, 0, 12));
        quick.addView(r1, Ui.lpTop(this, 0, 4));

        LinearLayout r2 = Ui.row(this);
        r2.addView(Ui.ghostButton(this, "New game", v -> {
            OverlayService.newGame();
            toast("Position reset to the standard start");
        }), weight());
        r2.addView(Ui.ghostButton(this, "Show my move", v -> {
            OverlayService.requestHintNow();
            toast("Reading the board…");
        }), weight());
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
        dr.addView(Ui.ghostButton(this, "Refresh", v -> refresh()), weight());
        dr.addView(Ui.ghostButton(this, "Copy", v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("chesshint", diagText.getText()));
            toast("Diagnostics copied");
        }), weight());
        diag.addView(dr, Ui.lpTop(this, 0, 6));
        LinearLayout dr2 = Ui.row(this);
        dr2.addView(Ui.ghostButton(this, "Retry screen reading", v -> onAction()), weight());
        dr2.addView(Ui.ghostButton(this, "Clear log", v -> {
            CrashGuard.clear(this);
            refresh();
        }), weight());
        diag.addView(dr2, Ui.lpTop(this, 0, 8));
        root.addView(diag, Ui.lpTop(this, 0, 12));

        // ---- how to (short)
        LinearLayout how = Ui.card(this);
        how.addView(Ui.sectionTitle(this, "HOW TO USE"));
        how.addView(Ui.text(this, "1.  Allow both permissions above, then press START PANEL.\n"
                + "2.  Open your chess game — your pieces must be at the BOTTOM (use FLIP in the ♞ panel if not).\n"
                + "3.  Tap the floating ♞ → SHOW MY MOVE.  The arrow tells you exactly which piece to move and where.\n"
                + "4.  Turn AUTO on for a hint on every one of your turns.  While the enemy is thinking it stays quiet.",
                12.5f, Ui.TEXT_DIM, false), Ui.lpTop(this, 0, 6));
        root.addView(how, Ui.lpTop(this, 0, 12));

        return sv;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = Ui.dp(this, 8);
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

    private void onAction() {
        if (OverlayService.isRunning()) {
            stopService(new Intent(this, OverlayService.class));
            new android.os.Handler().postDelayed(this::refresh, 350);
            toast("Panel stopped");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("First allow \"Display over other apps\"");
            openOverlaySettings();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Throwable t) {
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
        if (res == RESULT_OK && data != null) {
            Intent i = new Intent(this, OverlayService.class);
            i.putExtra(OverlayService.EXTRA_CODE, res);
            i.putExtra(OverlayService.EXTRA_DATA, data);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
                captureGranted = true;
                toast("Starting panel…");
            } catch (Throwable t) {
                toast("Could not start the panel: " + t.getMessage());
            }
            new android.os.Handler().postDelayed(this::refresh, 1200);
        } else {
            toast("Screen reading was cancelled");
        }
    }

    private void refresh() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean running = OverlayService.isRunning();
        captureGranted = running && OverlayService.hasCapture();

        TextView om = (TextView) overlayRow.getTag();
        om.setText(overlay ? "\u2714" : "\u2716");
        om.setTextColor(overlay ? Ui.ACCENT : Ui.DANGER);
        TextView cm = (TextView) captureRow.getTag();
        cm.setText(captureGranted ? "\u2714" : "\u2716");
        cm.setTextColor(captureGranted ? Ui.ACCENT : Ui.DANGER);

        statusDot.setTextColor(running ? Ui.ACCENT : Ui.DANGER);
        statusText.setText(running ? "Panel is running" : "Panel is stopped");
        statusSub.setText(running
                ? (OverlayService.hasCapture() ? "tap the ♞ bubble in your chess app" : "waiting for screen reading…")
                : "allow both permissions, then press START PANEL");
        actionBtn.setText(running ? "STOP PANEL" : "START PANEL");
        actionBtn.setBackground(Ui.round(running ? 0xFF2A1620 : Ui.ACCENT, 0, this, 14));
        actionBtn.setTextColor(running ? 0xFFFF7B8A : 0xFF06210F);

        // shortcuts need the panel running
        if (diagText != null) {
            String info = OverlayService.diagnostics();
            diagText.setText(info);
        }

        // ---- problem card
        String problem = OverlayService.lastProblem();
        if (problem == null || problem.isEmpty()) problem = firstLine(CrashGuard.lastCrash(this));
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

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s.trim() : s.substring(0, i).trim();
    }

    /** opens the Android share sheet with the full log - so a problem can be reported easily */
    private void shareLog() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Chess Hint Panel ").append("1.3").append("\n");
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
            i.putExtra(Intent.EXTRA_SUBJECT, "Chess Hint Panel log");
            i.putExtra(Intent.EXTRA_TEXT, sb.toString());
            startActivity(Intent.createChooser(i, "Share log"));
        } catch (Throwable t) {
            toast("Could not open the share sheet");
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
