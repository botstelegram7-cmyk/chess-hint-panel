package com.chesshint.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Full screen, touch transparent drawing surface.
 *
 * Deliberately clean: no top bar, no app name, nothing that blocks the game.
 * All it draws is the hint (arrow / rings / squares - chosen in Settings), optional thin
 * side labels and a small status chip near the bottom that fades away by itself.
 */
public class OverlayView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    public Rect board;
    public boolean whiteBottom = true;

    // settings, filled from Prefs
    public int style = Markers.STYLE_BOTH;
    public int palette = 0;
    public float scale = 1f;
    public boolean showLabel = true;
    public boolean showInfo = true;
    public boolean showSides = true;
    public boolean showFrame = false;
    public boolean showChip = true;

    // current hint
    private int fromIdx = -1, toIdx = -1;
    private String label, info;
    private long hintAt;
    /** 0.8..1.0 breathing factor for the marks */
    public static float pulseVal = 1f;

    // status chip
    private String chipText, chipSub;
    private long chipAt;
    private boolean busy;
    private Runnable onChipTap;

    public OverlayView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        textRim.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textRim.setTextAlign(Paint.Align.CENTER);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void applyPrefs(Prefs p) {
        style = p.markerStyle();
        palette = p.palette();
        scale = p.markerScale();
        showLabel = p.showLabel();
        showInfo = p.showInfo();
        showSides = p.showSideLabels();
        showFrame = p.showFrame();
        showChip = p.showChip();
        whiteBottom = p.whiteBottom();
        invalidate();
    }

    public void setHint(int from, int to, String label, String info) {
        fromIdx = from;
        toIdx = to;
        this.label = label;
        this.info = info;
        hintAt = System.currentTimeMillis();
        invalidate();
    }

    public void clearHint() {
        fromIdx = toIdx = -1;
        label = info = null;
        invalidate();
    }

    public boolean hasHint() { return fromIdx >= 0 && toIdx >= 0; }

    /** short lived message near the bottom of the screen */
    public void setStatus(String s, String sub) {
        chipText = s;
        chipSub = sub;
        chipAt = System.currentTimeMillis();
        invalidate();
    }

    public void setBusy(boolean b) {
        busy = b;
        chipAt = System.currentTimeMillis();
        invalidate();
    }

    private float sq() { return board == null ? 0 : board.width() / 8f; }

    // ====================================================================== draw

    @Override
    protected void onDraw(Canvas c) {
        try {
            drawAll(c);
        } catch (Throwable t) {
            CrashGuard.record(getContext(), "overlay draw", t);
        }
    }

    private void drawAll(Canvas c) {
        super.onDraw(c);
        long now = System.currentTimeMillis();

        if (board != null && showFrame) drawFrame(c);

        if (hasHint()) {
            long age = now - hintAt;
            boolean pulsing = age < 6000;
            if (pulsing) {
                double ph = age / 1000.0 * 2.6;
                pulseVal = 0.84f + 0.16f * (float) (0.5 + 0.5 * Math.sin(ph));
                postInvalidateOnAnimation();
            } else pulseVal = 0.85f;
            Markers.drawHint(c, board, fromIdx, toIdx, style, palette, dp, scale, pulseVal,
                    fill, stroke, text, textRim, label, showLabel);
            if (showInfo && info != null && !info.isEmpty()) drawInfo(c, info);
        }

        if (board != null && showSides) drawSideLabels(c);
        if (showChip && chipText != null) drawChip(c, now);
    }

    private void drawFrame(Canvas c) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp * 1.4f);
        stroke.setColor(0x8822D3EE);
        c.drawRect(board.left, board.top, board.right, board.bottom, stroke);
        stroke.setStrokeWidth(dp * 0.8f);
        stroke.setColor(0x2A22D3EE);
        stroke.setPathEffect(new DashPathEffect(new float[]{dp * 4, dp * 4}, 0));
        float s = sq();
        for (int k = 1; k < 8; k++) {
            c.drawLine(board.left + k * s, board.top, board.left + k * s, board.bottom, stroke);
            c.drawLine(board.left, board.top + k * s, board.right, board.top + k * s, stroke);
        }
        stroke.setPathEffect(null);
    }

    /** thin, unobtrusive "ENEMY / YOU" text just outside the board */
    private void drawSideLabels(Canvas c) {
        float size = Math.max(dp * 9.5f, Math.min(dp * 12f, sq() * 0.26f));
        text.setTextSize(size);
        float above = board.top - dp * 7f;
        float below = board.bottom + dp * 12f;
        if (above > size * 1.4f) {
            text.setColor(0xFF9FB4C9);
            c.drawText(whiteBottom ? "ENEMY  ▲" : "ENEMY  ▼", board.centerX(), above, text);
        }
        if (below < getHeight() - size) {
            text.setColor(Markers.to(palette));
            c.drawText(whiteBottom ? "YOU (WHITE)  ▼" : "YOU (BLACK)  ▲", board.centerX(), below, text);
        }
    }

    /** engine line, right under the board - small and out of the way */
    private void drawInfo(Canvas c, String s) {
        float size = Math.max(dp * 10f, Math.min(dp * 12f, sq() * 0.24f));
        text.setTextSize(size);
        float y = board.bottom + dp * 26f;
        if (y > getHeight() - dp * 8) y = board.bottom - dp * 14f;
        textRim.setTextSize(size);
        textRim.setStyle(Paint.Style.STROKE);
        textRim.setStrokeWidth(size * 0.19f);
        textRim.setColor(0xCC000000);
        c.drawText(s, board.centerX(), y, textRim);
        text.setColor(0xFFBFE8D2);
        text.setStyle(Paint.Style.FILL);
        c.drawText(s, board.centerX(), y, text);
    }

    /** small pill near the bottom: never covers the board, fades on its own */
    private void drawChip(Canvas c, long now) {
        long age = now - chipAt;
        boolean live = busy || age < 2600;
        if (!live) return;
        float alpha = 1f;
        if (!busy && age > 1900) alpha = Math.max(0f, 1f - (age - 1900) / 700f);
        if (alpha < 0.02f) return;
        if (busy || age < 1900) postInvalidateOnAnimation();

        float size = dp * 12.5f;
        text.setTextSize(size);
        float w = text.measureText(chipText) + dp * 44;
        float h = size * 2.2f;
        float cx = getWidth() / 2f;
        float cy = getHeight() - dp * 54;
        RectF r = new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF0C141C);
        fill.setAlpha((int) (0xE6 * alpha));
        c.drawRoundRect(r, h / 2, h / 2, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp * 1.3f);
        stroke.setColor(busy ? Markers.to(palette) : 0xFF31465C);
        stroke.setAlpha((int) (255 * alpha));
        c.drawRoundRect(r, h / 2, h / 2, stroke);

        if (busy) {
            double ph = (now % 1000) / 1000.0;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Markers.to(palette));
            fill.setAlpha((int) (255 * alpha));
            float rr = dp * 4.2f * (0.6f + 0.4f * (float) Math.sin(ph * Math.PI * 2));
            c.drawCircle(r.left + dp * 15, cy, rr, fill);
        } else {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Markers.from(palette));
            fill.setAlpha((int) (255 * alpha));
            c.drawCircle(r.left + dp * 15, cy, dp * 4f, fill);
        }

        text.setColor(0xFFFFFFFF);
        text.setAlpha((int) (255 * alpha));
        text.setStyle(Paint.Style.FILL);
        c.drawText(chipText, cx + dp * 10, cy - (text.descent() + text.ascent()) / 2f, text);
    }
}
