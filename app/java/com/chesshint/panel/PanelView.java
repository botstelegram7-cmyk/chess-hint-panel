package com.chesshint.panel;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The floating control panel that opens when the ♞ bubble is tapped: two tiles per row, compact. */
public class PanelView extends LinearLayout {

    public interface Listener {
        void onMove();
        void onAuto();
        void onSettings();
        void onFit();
        void onFix();
        void onFlip();
        void onNewGame();
        void onHide();
        void onStop();
    }

    private final Context ctx;
    private final Listener l;
    private TextView moveBtn, autoBtn, sideBtn, headText;

    public PanelView(Context c, Listener l) {
        super(c);
        this.ctx = c;
        this.l = l;
        setOrientation(VERTICAL);
        int pad = Ui.dp(c, 10);
        setPadding(pad, pad, pad, pad);
        setBackground(Ui.round(0xF50C141C, 0xFF2A3B4E, c, 18));

        headText = Ui.text(c, "", 11f, Ui.TEXT_DIM, false);
        headText.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), Ui.dp(c, 8));
        addView(headText);

        moveBtn = Ui.primaryButton(c, "\u265E   SHOW MY MOVE", Ui.ACCENT, 0xFF06210F, v -> l.onMove());
        addView(moveBtn, full());

        autoBtn = tile("AUTO: OFF", 0, v -> l.onAuto());
        LinearLayout r1 = Ui.row(c);
        r1.addView(autoBtn, tileLp());
        r1.addView(tile("SETTINGS", 0, v -> l.onSettings()), tileLp());
        addView(r1, top());

        LinearLayout r2 = Ui.row(c);
        sideBtn = tile("ME: WHITE", 0, v -> l.onFlip());
        r2.addView(sideBtn, tileLp());
        r2.addView(tile("FIT BOARD", 0, v -> l.onFit()), tileLp());
        addView(r2, top());

        LinearLayout r3 = Ui.row(c);
        r3.addView(tile("FIX PIECES", 0, v -> l.onFix()), tileLp());
        r3.addView(tile("NEW GAME", 0, v -> l.onNewGame()), tileLp());
        addView(r3, top());

        LinearLayout r4 = Ui.row(c);
        r4.addView(tile("HIDE MARKS", 0, v -> l.onHide()), tileLp());
        r4.addView(tile("STOP", Ui.DANGER, v -> l.onStop()), tileLp());
        addView(r4, top());
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(ctx, 2);
        return p;
    }

    private LinearLayout.LayoutParams top() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(ctx, 7);
        return p;
    }

    private LinearLayout.LayoutParams tileLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = Ui.dp(ctx, 7);
        return p;
    }

    private TextView tile(String s, int accent, View.OnClickListener click) {
        TextView t = Ui.text(ctx, s, 12f, accent == 0 ? Ui.TEXT : accent, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(0xFF15212C, 0xFF26374A, ctx, 12));
        t.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12));
        t.setOnClickListener(click);
        t.setClickable(true);
        return t;
    }

    public void refresh(Prefs prefs, String headLine) {
        autoBtn.setText(prefs.auto() ? "AUTO: ON \u25CF" : "AUTO: OFF");
        autoBtn.setTextColor(prefs.auto() ? Ui.ACCENT : Ui.TEXT);
        sideBtn.setText(prefs.whiteBottom() ? "ME: WHITE" : "ME: BLACK");
        headText.setText(headLine);
    }
}
