package com.chesshint.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Full-screen, touch-transparent drawing surface.
 *
 * Automatically compensates for any window offset via getLocationOnScreen() so
 * board coordinates from ScreenGrab/Vision map 1:1 to exact physical screen pixels
 * regardless of display cutouts, status bars, or navigation bars.
 */
public class OverlayView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final int[] screenLoc = new int[2];

    public Rect board;
    public boolean whiteBottom = true;

    // settings, filled from Prefs
    public int style = Markers.STYLE_ARROW;
    public int palette = 0;
    public float scale = 1f;
    public boolean showLabel = false;
    public boolean showInfo = false;
    public boolean showSides = false;
    public boolean showFrame = false;
    public boolean showChip = true;

    // current hint & selected-piece legal moves
    private int fromIdx = -1, toIdx = -1;
    private int[] validTargets = null;
    private boolean[] captureFlags = null;
    private boolean pieceSelected = false;
    private String label, info;
    private long hintAt;
    /** 0.8..1.0 breathing factor for the marks */
    public static float pulseVal = 1f;

    // status chip
    private String chipText, chipSub;
    private long chipAt;
    private boolean busy;

    // capture helper: temporarily hides board marks during screen read + nudges SurfaceFlinger
    private volatile boolean captureClean = false;
    private int nudgeTick = 0;

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
        setHint(from, to, null, null, false, label, info);
    }

    public void setHint(int from, int to, int[] validTargets, boolean[] captureFlags,
                        boolean pieceSelected, String label, String info) {
        this.fromIdx = from;
        this.toIdx = to;
        this.validTargets = validTargets;
        this.captureFlags = captureFlags;
        this.pieceSelected = pieceSelected;
        this.label = label;
        this.info = info;
        this.hintAt = System.currentTimeMillis();
        this.captureClean = false;
        invalidate();
    }

    /** Indicates that the selected piece on `from` has no legal moves in the current position. */
    public void setBlockedPiece(int from, String msg) {
        this.fromIdx = from;
        this.toIdx = -1;
        this.validTargets = null;
        this.captureFlags = null;
        this.pieceSelected = true;
        this.label = null;
        this.info = null;
        this.hintAt = System.currentTimeMillis();
        this.captureClean = false;
        if (msg != null && !msg.isEmpty()) setStatus(msg, null);
        invalidate();
    }

    public void clearHint() {
        fromIdx = toIdx = -1;
        validTargets = null;
        captureFlags = null;
        pieceSelected = false;
        label = info = null;
        invalidate();
    }

    public boolean hasHint() { return fromIdx >= 0; }
    public int getFromIdx() { return fromIdx; }
    public int getToIdx() { return toIdx; }
    public boolean isPieceSelected() { return pieceSelected; }

    /** Temporarily hides board marks while capturing a screenshot so marks never pollute Vision.read(). */
    public void setCaptureClean(boolean clean) {
        captureClean = clean;
        nudgeTick++;
        invalidate();
    }

    /** Alternates a 1x1 alpha=1 pixel at (0,0) so SurfaceFlinger emits a VirtualDisplay frame even on a static screen. */
    public void nudgeFrame() {
        nudgeTick++;
        invalidate();
    }

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

        // 1x1 imperceptible pixel at (0,0) that changes alpha between 1/255 and 2/255 when nudged,
        // forcing Android's display compositor to push a fresh frame to VirtualDisplay.
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(((nudgeTick & 1) == 0) ? 0x01000000 : 0x02000000);
        c.drawRect(0, 0, 1, 1, fill);

        if (captureClean) return;

        long now = System.currentTimeMillis();

        // Align canvas origin with raw physical screen coordinates (compensates for any notch/status bar inset)
        screenLoc[0] = 0;
        screenLoc[1] = 0;
        try { getLocationOnScreen(screenLoc); } catch (Throwable ignored) { }

        c.save();
        if (screenLoc[0] != 0 || screenLoc[1] != 0) {
            c.translate(-screenLoc[0], -screenLoc[1]);
        }

        if (board != null && showFrame) drawFrame(c);

        if (hasHint() && board != null) {
            long age = now - hintAt;
            float entrance = 1f;
            if (age < 170) {
                float t = Math.max(0f, age / 170f);
                entrance = 1f - (1f - t) * (1f - t);
                postInvalidateOnAnimation();
            }
            boolean pulsing = age < 6000;
            if (pulsing) {
                double ph = age / 1000.0 * 2.6;
                pulseVal = 0.86f + 0.14f * (float) (0.5 + 0.5 * Math.sin(ph));
                postInvalidateOnAnimation();
            } else {
                pulseVal = 0.92f;
            }
            Markers.drawHint(c, board, fromIdx, toIdx, validTargets, captureFlags, pieceSelected,
                    style, palette, dp, scale, pulseVal, entrance,
                    fill, stroke, text, textRim, label, showLabel);
            if (showInfo && info != null && !info.isEmpty()) drawInfo(c, info);
        }

        if (board != null && showSides) drawSideLabels(c);
        c.restore();

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
        float sw = board.width() / 8f, sh = board.height() / 8f;
        for (int k = 1; k < 8; k++) {
            c.drawLine(board.left + k * sw, board.top, board.left + k * sw, board.bottom, stroke);
            c.drawLine(board.left, board.top + k * sh, board.right, board.top + k * sh, stroke);
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
            c.drawText(whiteBottom ? "OPPONENT  \u25B2" : "OPPONENT  \u25BC", board.centerX(), above, text);
        }
        if (below < getHeight() + screenLoc[1] - size) {
            text.setColor(Markers.to(palette));
            c.drawText(whiteBottom ? "YOU (WHITE)  \u25BC" : "YOU (BLACK)  \u25B2", board.centerX(), below, text);
        }
    }

    /** engine line, right under the board - small and out of the way */
    private void drawInfo(Canvas c, String s) {
        float size = Math.max(dp * 10f, Math.min(dp * 12f, sq() * 0.24f));
        text.setTextSize(size);
        float y = board.bottom + dp * 26f;
        if (y > getHeight() + screenLoc[1] - dp * 8) y = board.bottom - dp * 14f;
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
        fill.setAlpha((int) (0xE8 * alpha));
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
