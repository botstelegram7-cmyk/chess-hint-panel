package com.chesshint.panel;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Small styling kit so every screen looks the same and stays tidy. */
public class Ui {

    public static final int BG = 0xFF0A1119;
    public static final int CARD = 0xFF101A24;
    public static final int CARD_BORDER = 0xFF1E2C3A;
    public static final int CHIP = 0xFF16222E;
    public static final int CHIP_SEL = 0xFF00E676;
    public static final int TEXT = 0xFFE9F1F8;
    public static final int TEXT_DIM = 0xFF8CA3B8;
    public static final int ACCENT = 0xFF00E676;
    public static final int ACCENT2 = 0xFFFFD400;
    public static final int DANGER = 0xFFFF5C6C;

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable round(int fill, int stroke, Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(c, 1.1f)), stroke);
        return g;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(round(CARD, CARD_BORDER, c, 16));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static LinearLayout.LayoutParams lp(Context c, int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpTop(Context c, int h, float topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h == 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : h);
        p.topMargin = dp(c, topDp);
        return p;
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    public static TextView sectionTitle(Context c, String s) {
        TextView t = text(c, s, 11.5f, ACCENT, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(c, 4), 0, 0, dp(c, 8));
        return t;
    }

    public static TextView primaryButton(Context c, String s, int fill, int fg, View.OnClickListener l) {
        TextView t = text(c, s, 15f, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(fill, 0, c, 14));
        t.setPadding(dp(c, 12), dp(c, 15), dp(c, 12), dp(c, 15));
        t.setOnClickListener(l);
        t.setClickable(true);
        return t;
    }

    public static TextView ghostButton(Context c, String s, View.OnClickListener l) {
        TextView t = text(c, s, 13f, TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(CHIP, CARD_BORDER, c, 12));
        t.setPadding(dp(c, 8), dp(c, 12), dp(c, 8), dp(c, 12));
        t.setOnClickListener(l);
        t.setClickable(true);
        return t;
    }

    /** one option inside a segmented row */
    public static TextView chip(Context c, String s, boolean selected, View.OnClickListener l) {
        TextView t = text(c, s, 12.5f, selected ? 0xFF06210F : TEXT, selected);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(selected ? CHIP_SEL : CHIP, selected ? 0 : CARD_BORDER, c, 11));
        t.setPadding(dp(c, 6), dp(c, 11), dp(c, 6), dp(c, 11));
        t.setOnClickListener(l);
        t.setClickable(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = dp(c, 6);
        t.setLayoutParams(p);
        return t;
    }

    public static LinearLayout chipRow(Context c) {
        return row(c);
    }

    public static View switchRow(Context c, String label, String sub, boolean checked,
                                 CompoundButton.OnCheckedChangeListener l) {
        LinearLayout r = row(c);
        r.setPadding(dp(c, 2), dp(c, 9), dp(c, 2), dp(c, 9));

        LinearLayout texts = column(c);
        texts.addView(text(c, label, 14f, TEXT, false));
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 11.5f, TEXT_DIM, false);
            s.setPadding(0, dp(c, 2), 0, 0);
            texts.addView(s);
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        r.addView(texts, tp);

        Switch sw = new Switch(c);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        return r;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0xFF1B2733);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.8f)));
        p.topMargin = dp(c, 10);
        p.bottomMargin = dp(c, 10);
        v.setLayoutParams(p);
        return v;
    }

    public static View space(Context c, float h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }
}
