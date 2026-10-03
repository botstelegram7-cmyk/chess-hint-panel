package com.chesshint.panel;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * The marks that tell the player which piece to move and where.
 *
 * Everything here is fixed, high contrast colour (never "adaptive") so the marks stay
 * visible on every board theme, and the style can be chosen in Settings:
 *   ARROW  - only the fat arrow
 *   RINGS  - only the two rings (from / to)
 *   BOTH   - rings + arrow
 *   SQUARES- filled square on the piece and on the target
 */
public class Markers {

    public static final int STYLE_BOTH = 0;
    public static final int STYLE_ARROW = 1;
    public static final int STYLE_RINGS = 2;
    public static final int STYLE_SQUARES = 3;

    /** palette[i] = {from colour, to colour} */
    public static final int[][] PALETTE = {
            {0xFF00E676, 0xFFFFD400},   // green / amber   (default)
            {0xFF29B6F6, 0xFFFF4081},   // blue / pink
            {0xFF00E5FF, 0xFFFF9100},   // cyan / orange
            {0xFFFFFFFF, 0xFFFF1744},   // white / red
            {0xFF76FF03, 0xFFD500F9},   // lime / violet
    };

    public static final String[] PALETTE_NAME = {
            "Green / Amber", "Blue / Pink", "Cyan / Orange", "White / Red", "Lime / Violet"
    };

    public static int from(int palette) {
        return PALETTE[clampPal(palette)][0];
    }

    public static int to(int palette) {
        return PALETTE[clampPal(palette)][1];
    }

    private static int clampPal(int p) {
        return Math.max(0, Math.min(PALETTE.length - 1, p));
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * Draws the hint.
     *
     * @param board     board rectangle on screen
     * @param from      index of the piece to move (row*8+col, row 0 = top)
     * @param to        index of the square to move it to
     * @param scale     0.8 small, 1.0 normal, 1.25 large
     */
    public static void drawHint(Canvas c, Rect board, int from, int to, int style,
                                int palette, float dp, float scale, float pulse,
                                Paint fill, Paint stroke, Paint text, Paint textRim,
                                String label, boolean showLabel) {
        if (board == null || from < 0 || to < 0 || from > 63 || to > 63) return;
        float s = board.width() / 8f;
        float px = from % 8, py = from / 8, qx = to % 8, qy = to / 8;
        float fx = board.left + (px + 0.5f) * s, fy = board.top + (py + 0.5f) * s;
        float tx = board.left + (qx + 0.5f) * s, ty = board.top + (qy + 0.5f) * s;
        int colFrom = from(palette), colTo = to(palette);
        float pl = Math.max(0.7f, Math.min(1.15f, pulse));

        // ---------------------------------------------------------- filled squares
        if (style == STYLE_SQUARES) {
            RectF rf = new RectF(board.left + px * s, board.top + py * s, board.left + (px + 1) * s, board.top + (py + 1) * s);
            RectF rt = new RectF(board.left + qx * s, board.top + qy * s, board.left + (qx + 1) * s, board.top + (qy + 1) * s);
            float in = s * 0.03f;
            rf.inset(in, in);
            rt.inset(in, in);

            fill.setStyle(Paint.Style.FILL);
            fill.setColor(withAlpha(colFrom, 0x5A));
            c.drawRoundRect(rf, s * 0.10f, s * 0.10f, fill);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp * 5.5f * scale);
            stroke.setColor(colFrom);
            c.drawRoundRect(rf, s * 0.10f, s * 0.10f, stroke);
            stroke.setStrokeWidth(dp * 1.6f);
            stroke.setColor(0xB3000000);
            c.drawRoundRect(rf, s * 0.10f, s * 0.10f, stroke);

            fill.setColor(withAlpha(colTo, 0x66));
            fill.setStyle(Paint.Style.FILL);
            c.drawRoundRect(rt, s * 0.10f, s * 0.10f, fill);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp * 6.5f * scale * pl);
            stroke.setColor(colTo);
            c.drawRoundRect(rt, s * 0.10f, s * 0.10f, stroke);
            stroke.setStrokeWidth(dp * 1.8f);
            stroke.setColor(0xB3000000);
            c.drawRoundRect(rt, s * 0.10f, s * 0.10f, stroke);
        }

        // ------------------------------------------------------------------ rings
        if (style == STYLE_RINGS || style == STYLE_BOTH) {
            // a piece on the "from" square: circle hugging the piece
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(withAlpha(colFrom, 0x38));
            c.drawCircle(fx, fy, s * 0.49f, fill);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp * 7.5f * scale * pl + s * 0.02f);
            stroke.setColor(colFrom);
            c.drawCircle(fx, fy, s * 0.44f, stroke);
            stroke.setStrokeWidth(dp * 1.7f);
            stroke.setColor(0xCC101418);
            c.drawCircle(fx, fy, s * 0.44f + dp * 4.6f * scale * pl + s * 0.02f, stroke);

            // the square to move to: rounded square ring
            float inset = s * 0.055f;
            RectF rt = new RectF(board.left + qx * s + inset, board.top + qy * s + inset,
                    board.left + (qx + 1) * s - inset, board.top + (qy + 1) * s - inset);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(withAlpha(colTo, 0x40));
            c.drawRoundRect(rt, s * 0.18f, s * 0.18f, fill);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp * 8.5f * scale);
            stroke.setColor(0xE6151A20);
            c.drawRoundRect(rt, s * 0.18f, s * 0.18f, stroke);
            stroke.setStrokeWidth(dp * 6.0f * scale * pl);
            stroke.setColor(colTo);
            c.drawRoundRect(rt, s * 0.18f, s * 0.18f, stroke);
        }

        // ------------------------------------------------------------------ arrow
        if (style == STYLE_ARROW || style == STYLE_BOTH) {
            float dx = tx - fx, dy = ty - fy;
            float len = (float) Math.hypot(dx, dy);
            if (len > 1) {
                float ux = dx / len, uy = dy / len;
                int df = Math.abs(from % 8 - to % 8), dr = Math.abs(from / 8 - to / 8);
                boolean knight = (df == 1 && dr == 2) || (df == 2 && dr == 1);
                float startOff = s * (knight ? 0.16f : 0.34f);
                float endOff = s * 0.44f;
                float sxs = fx + ux * startOff, sys = fy + uy * startOff;
                float exs = tx - ux * endOff, eys = ty - uy * endOff;

                float shaft = Math.max(dp * 7.5f * scale * pl, s * 0.16f);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeCap(Paint.Cap.ROUND);
                stroke.setColor(0xE6000000);
                stroke.setStrokeWidth(shaft + dp * 7.5f * scale);
                c.drawLine(sxs, sys, exs, eys, stroke);
                stroke.setColor(colTo);
                stroke.setStrokeWidth(shaft);
                c.drawLine(sxs, sys, exs, eys, stroke);
                stroke.setColor(0x59FFFFFF);
                stroke.setStrokeWidth(shaft * 0.30f);
                c.drawLine(sxs, sys, exs, eys, stroke);

                float hl = s * 0.50f, hw = s * 0.42f * scale;
                Path head = new Path();
                head.moveTo(exs + ux * hl, eys + uy * hl);
                head.lineTo(exs + uy * hw, eys - ux * hw);
                head.lineTo(exs - uy * hw, eys + ux * hw);
                head.close();
                stroke.setColor(0xE6000000);
                stroke.setStrokeWidth(dp * 7.5f * scale);
                stroke.setStrokeJoin(Paint.Join.ROUND);
                c.drawPath(head, stroke);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(colTo);
                c.drawPath(head, fill);
            }
        }

        // ------------------------------------------------------------------ badge
        if (!showLabel || label == null || label.isEmpty()) return;
        float badgeSize = Math.max(dp * 15f, Math.min(dp * 22f, s * 0.44f * scale));
        text.setTextSize(badgeSize);
        float w = text.measureText(label) + dp * 24f;
        float h = badgeSize * 1.9f;
        float mx = (fx + tx) / 2f, my = (fy + ty) / 2f;
        // keep the badge on the board whenever possible
        mx = Math.max(board.left + w / 2 + dp * 2, Math.min(board.right - w / 2 - dp * 2, mx));
        my = Math.max(board.top + h / 2 + dp * 2, Math.min(board.bottom - h / 2 - dp * 2, my));

        RectF r = new RectF(mx - w / 2, my - h / 2, mx + w / 2, my + h / 2);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xF20B131C);
        c.drawRoundRect(r, h * 0.34f, h * 0.34f, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp * 2.2f);
        stroke.setColor(colTo);
        c.drawRoundRect(r, h * 0.34f, h * 0.34f, stroke);

        float baseline = my - (text.descent() + text.ascent()) / 2f;
        // thin dark rim makes the text readable on any background
        textRim.setTextSize(badgeSize);
        textRim.setStyle(Paint.Style.STROKE);
        textRim.setStrokeWidth(badgeSize * 0.16f);
        textRim.setColor(0xFF000000);
        c.drawText(label, mx, baseline, textRim);
        text.setColor(0xFFFFFFFF);
        text.setStyle(Paint.Style.FILL);
        c.drawText(label, mx, baseline, text);
    }

    /** short "which piece, where" text, e.g.  "MOVE  ♘ g1 → f3" */
    public static String label(int piece, String from, String to, String promo, boolean mine) {
        StringBuilder sb = new StringBuilder();
        sb.append(mine ? "YOUR MOVE  " : "MOVE  ");
        String g = Board.glyph(piece);
        if (!g.isEmpty()) sb.append(g).append(' ');
        sb.append(from).append(" → ").append(to);
        if (promo != null && !promo.isEmpty()) sb.append(" = ").append(promo.toUpperCase());
        return sb.toString();
    }
}
