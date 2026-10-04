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
    private boolean showLabel = false;

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
        setMeasuredDimension(width, Math.round(dp * 172));
    }

    @Override
    protected void onDraw(Canvas c) {
        int side = Math.min(getWidth() - Math.round(dp * 24), Math.round(dp * 154));
        int left = (getWidth() - side) / 2;
        int top = Math.round(dp * 9);
        Rect board = new Rect(left, top, left + side, top + side);
        float s = side / 8f;

        fill.setStyle(Paint.Style.FILL);
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                fill.setColor(((r + f) & 1) == 0 ? 0xFFEEEED2 : 0xFF769656);
                c.drawRect(board.left + f * s, board.top + r * s, board.left + (f + 1) * s, board.top + (r + 1) * s, fill);
            }
        }
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp * 1.5f);
        stroke.setColor(0xFF26374A);
        c.drawRect(board, stroke);

        // Sample pieces (White at bottom)
        drawPiece(c, board.left + 6.5f * s, board.top + 7.5f * s, s, 2, true);   // White Knight on g1
        drawPiece(c, board.left + 4.5f * s, board.top + 6.5f * s, s, 1, true);   // White Pawn on e2
        drawPiece(c, board.left + 3.5f * s, board.top + 6.5f * s, s, 1, true);   // White Pawn on d2
        drawPiece(c, board.left + 4.5f * s, board.top + 3.5f * s, s, 1, false);  // Black Pawn on e5

        int g1 = 7 * 8 + 6, f3 = 5 * 8 + 5, h3 = 5 * 8 + 7;
        int[] validTargets = new int[]{f3, h3};
        boolean[] captureFlags = new boolean[]{false, false};
        Markers.drawHint(c, board, g1, f3, validTargets, captureFlags, true,
                style, palette, dp, scale, 1f, 1f,
                fill, stroke, text, textRim,
                Markers.label(2, "g1", "f3", "", true), showLabel);
    }

    private void drawPiece(Canvas c, float cx, float cy, float s, int kind, boolean white) {
        if (kind == 0) return;
        String g = kind == 1 ? (white ? "\u2659" : "\u265F") : (white ? "\u2658" : "\u265E");
        text.setTextSize(s * 0.84f);
        float baseline = cy - (text.descent() + text.ascent()) / 2f;
        textRim.setTextSize(s * 0.84f);
        textRim.setStyle(Paint.Style.STROKE);
        textRim.setStrokeWidth(s * 0.12f);
        textRim.setColor(white ? 0xFF101820 : 0xFFE8EFF6);
        c.drawText(g, cx, baseline, textRim);
        text.setColor(white ? 0xFFFFFFFF : 0xFF1C2630);
        text.setStyle(Paint.Style.FILL);
        c.drawText(g, cx, baseline, text);
    }
}
