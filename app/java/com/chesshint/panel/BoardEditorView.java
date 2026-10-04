package com.chesshint.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Tap the squares to correct the position the reader got wrong.
 * A tap cycles: empty -> white P N B R Q K -> black p n b r q k -> empty.
 */
public class BoardEditorView extends View {

    public interface Listener {
        void onEditorApply(Board b, boolean whiteBottom);
        void onEditorCancel();
        void onEditorFlip();
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final Listener l;

    public Rect rect = new Rect();
    public Board board = new Board();
    public boolean whiteBottom = true;
    private final RectF btnApply = new RectF(), btnClear = new RectF(), btnCancel = new RectF(), btnFlip = new RectF();
    private final int[] screenLoc = new int[2];
    private long lastTap;
    private int lastIdx = -1;

    private static final int[] CYCLE = {0, 1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14};

    public BoardEditorView(Context c, Listener l) {
        super(c);
        this.l = l;
        dp = c.getResources().getDisplayMetrics().density;
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setTextAlign(Paint.Align.CENTER);
    }

    private int d(float v) { return Math.round(v * dp); }

    public void setBoard(Board b, Rect r, boolean wb) {
        board = b.copy();
        rect = new Rect(r);
        whiteBottom = wb;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float bw = d(112), bh = d(46);
        float y = h - bh - d(18), cx = w / 2f;
        btnCancel.set(cx - bw * 1.5f - d(6), y, cx - bw * 0.5f - d(6), y + bh);
        btnApply.set(cx - bw * 0.5f + d(6), y, cx + bw * 0.5f + d(6), y + bh);
        btnClear.set(cx + bw * 0.5f + d(12), y, cx + bw * 1.5f + d(12), y + bh);
        btnFlip.set(cx - bw * 0.5f, y - bh - d(10), cx + bw * 0.5f, y - d(10));
    }

    @Override
    protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xC0101018);
        c.drawRect(0, 0, W, H, p);

        getLocationOnScreen(screenLoc);
        c.save();
        c.translate(-screenLoc[0], -screenLoc[1]);

        float s = rect.width() / 8f;
        p.setColor(0xFFFFFFFF);
        c.drawRect(rect, p);
        for (int r = 0; r < 8; r++)
            for (int f = 0; f < 8; f++) {
                boolean light = ((r + f) & 1) == 0;
                p.setColor(light ? 0xFFE9E2D0 : 0xFF9C7A54);
                c.drawRect(rect.left + f * s, rect.top + r * s, rect.left + (f + 1) * s, rect.top + (r + 1) * s, p);
            }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp * 2f);
        p.setColor(0xFF2A3B4D);
        c.drawRect(rect, p);

        for (int i = 0; i < 64; i++) {
            int piece = board.s[i];
            if (piece == 0) continue;
            float cx = rect.left + (i % 8 + 0.5f) * s, cy = rect.top + (i / 8 + 0.5f) * s;
            drawPiece(c, cx, cy, s * 0.86f, piece);
        }

        t.setTextSize(dp * 15);
        t.setColor(0xFFFFFFFF);
        c.drawText("Tap a square to change the piece", rect.centerX(), rect.top - d(46), t);
        t.setTextSize(dp * 12);
        t.setColor(0xFFB8CAD9);
        c.drawText("\u25CF empty \u2192 \u2659 \u2658 \u2657 \u2656 \u2655 \u2654 (white) \u2192 \u265F \u265E \u265D \u265C \u265B \u265A (black) \u2192 \u25CB", rect.centerX(), rect.top - d(24), t);

        c.restore();

        drawBtn(c, btnFlip, "BOTTOM = " + (whiteBottom ? "WHITE" : "BLACK"), 0xFF1B2C3E, 0xFF7FD4FF);
        drawBtn(c, btnCancel, "\u2715 CANCEL", 0xFF3A1620, 0xFFFF9AA5);
        drawBtn(c, btnApply, "\u2714 USE THIS POSITION", 0xFF00E676, 0xFF062015);
        drawBtn(c, btnClear, "\u232B CLEAR", 0xFF232F3E, 0xFFC8D6E5);
    }

    private void drawPiece(Canvas c, float cx, float cy, float size, int piece) {
        String g = Board.glyph(piece);
        boolean white = Board.isWhite(piece);
        t.setTextSize(size);
        float baseline = cy - (t.descent() + t.ascent()) / 2f;
        t.setStyle(Paint.Style.STROKE);
        t.setStrokeWidth(size * 0.14f);
        t.setColor(white ? 0xFF101820 : 0xFFE8EFF6);
        c.drawText(g, cx, baseline, t);
        t.setStyle(Paint.Style.FILL);
        t.setColor(white ? 0xFFFFFFFF : 0xFF131C26);
        c.drawText(g, cx, baseline, t);
    }

    private void drawBtn(Canvas c, RectF r, String label, int bg, int fg) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(bg);
        c.drawRoundRect(r, d(12), d(12), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp * 1.4f);
        p.setColor(0x66FFFFFF);
        c.drawRoundRect(r, d(12), d(12), p);
        t.setStyle(Paint.Style.FILL);
        t.setTextSize(dp * 13.5f);
        t.setColor(fg);
        c.drawText(label, r.centerX(), r.centerY() - (t.descent() + t.ascent()) / 2f, t);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (btnFlip.contains(x, y)) { l.onEditorFlip(); return true; }
            if (btnCancel.contains(x, y)) { l.onEditorCancel(); return true; }
            if (btnApply.contains(x, y)) { l.onEditorApply(board, whiteBottom); return true; }
            if (btnClear.contains(x, y)) {
                for (int i = 0; i < 64; i++) board.s[i] = 0;
                invalidate();
                return true;
            }
            getLocationOnScreen(screenLoc);
            float sx = x + screenLoc[0], sy = y + screenLoc[1];
            float s = rect.width() / 8f;
            int f = (int) ((sx - rect.left) / s), r = (int) ((sy - rect.top) / s);
            if (f >= 0 && f < 8 && r >= 0 && r < 8) {
                int idx = r * 8 + f;
                if (idx == lastIdx && System.currentTimeMillis() - lastTap < 700) return true;
                lastIdx = idx; lastTap = System.currentTimeMillis();
                int cur = board.s[idx];
                int pos = 0;
                for (int k = 0; k < CYCLE.length; k++) if (CYCLE[k] == cur) { pos = k; break; }
                board.s[idx] = (byte) CYCLE[(pos + 1) % CYCLE.length];
                invalidate();
                return true;
            }
        }
        return true;
    }
}
