package com.chesshint.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.View;

/** Small live preview of the marker style, used inside Settings. */
public class MarkerPreviewView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    private int style = Markers.STYLE_BOTH;
    private int palette = 0;
    private float scale = 1f;
    private boolean showLabel = true;

    public MarkerPreviewView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        textRim.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textRim.setTextAlign(Paint.Align.CENTER);
    }

    public void set(int style, int palette, float scale, boolean showLabel) {
        this.style = style;
        this.palette = palette;
        this.scale = scale;
        this.showLabel = showLabel;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, Math.round(dp * 168));
    }

    @Override
    protected void onDraw(Canvas c) {
        int side = Math.min(getWidth() - Math.round(dp * 24), Math.round(dp * 150));
        int left = (getWidth() - side) / 2;
        int top = Math.round(dp * 8);
        Rect board = new Rect(left, top, left + side, top + side);
        float s = side / 8f;

        // board
        fill.setStyle(Paint.Style.FILL);
        c.drawRect(board, fill);
        for (int r = 0; r < 8; r++)
            for (int f = 0; f < 8; f++) {
                fill.setColor(((r + f) & 1) == 0 ? 0xFFEDE6D6 : 0xFFB08A64);
                c.drawRect(board.left + f * s, board.top + r * s, board.left + (f + 1) * s, board.top + (r + 1) * s, fill);
            }
        // a few sample pieces so the preview looks like a real board (white at the bottom)
        drawPiece(c, board.left + 6.5f * s, board.top + 7.5f * s, s, 2);      // knight on g1
        drawPiece(c, board.left + 3.5f * s, board.top + 6.5f * s, s, 1);      // pawn on d2
        drawPiece(c, board.left + 4.5f * s, board.top + 0.5f * s, s, 2);      // black knight e8

        // the hint itself: knight g1 -> f3   (screen index = row*8 + col)
        int g1 = 7 * 8 + 6, f3 = 5 * 8 + 5;
        Markers.drawHint(c, board, g1, f3, style, palette, dp, scale, 1f,
                fill, stroke, text, textRim,
                Markers.label(2, "g1", "f3", "", true), showLabel);
    }

    /** quick white/black pawn+knight glyphs (0 = draw nothing) */
    private void drawPiece(Canvas c, float cx, float cy, float s, int kind) {
        if (kind == 0) return;
        String g = kind == 1 ? "\u2659" : "\u2658";
        text.setTextSize(s * 0.86f);
        float baseline = cy - (text.descent() + text.ascent()) / 2f;
        textRim.setTextSize(s * 0.86f);
        textRim.setStyle(Paint.Style.STROKE);
        textRim.setStrokeWidth(s * 0.12f);
        textRim.setColor(0xFF101820);
        c.drawText(g, cx, baseline, textRim);
        text.setColor(0xFFFFFFFF);
        text.setStyle(Paint.Style.FILL);
        c.drawText(g, cx, baseline, text);
    }
}
