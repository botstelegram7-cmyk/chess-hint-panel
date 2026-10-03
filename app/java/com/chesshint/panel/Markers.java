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

    /** palette[i] = {from colour, to/arrow colour} */
    public static final int[][] PALETTE = {
            {0xFF76C424, 0xFF76C424},   // Chess.com green (default — matches user screenshot)
            {0xFF29B6F6, 0xFF29B6F6},   // Sky blue
            {0xFFFF9100, 0xFFFF9100},   // Orange
            {0xFFFFD400, 0xFFFFD400},   // Gold
            {0xFFFF4081, 0xFFFF4081},   // Pink
    };

    public static final String[] PALETTE_NAME = {
            "Green", "Blue", "Orange", "Gold", "Pink"
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
            int fCol = from % 8, fRow = from / 8, tCol = to % 8, tRow = to / 8;
            int df = Math.abs(fCol - tCol), dr = Math.abs(fRow - tRow);
            boolean knight = (df == 1 && dr == 2) || (df == 2 && dr == 1);

            float hw = Math.max(dp * 4.5f, s * 0.135f * scale);
            float hhw = Math.max(hw * 2.1f, s * 0.33f * scale);
            float hl = Math.max(hhw * 1.15f, s * 0.44f * scale);
            Path arrow = new Path();

            if (knight) {
                float kx, ky;
                if (dr == 2) { kx = fx; ky = ty; }
                else { kx = tx; ky = fy; }
                float d1x = kx - fx, d1y = ky - fy;
                float l1 = (float) Math.hypot(d1x, d1y);
                float d2x = tx - kx, d2y = ty - ky;
                float l2 = (float) Math.hypot(d2x, d2y);
                if (l1 > 1f && l2 > 1f) {
                    float u1x = d1x / l1, u1y = d1y / l1;
                    float u2x = d2x / l2, u2y = d2y / l2;
                    float tipX = tx + u2x * (s * 0.12f);
                    float tipY = ty + u2y * (s * 0.12f);
                    float neckX = tipX - u2x * hl;
                    float neckY = tipY - u2y * hl;

                    arrow.moveTo(fx + hw * u2x, fy + hw * u2y);
                    arrow.lineTo(kx - hw * u1x + hw * u2x, ky - hw * u1y + hw * u2y);
                    arrow.lineTo(neckX - hw * u1x, neckY - hw * u1y);
                    arrow.lineTo(neckX - hhw * u1x, neckY - hhw * u1y);
                    arrow.lineTo(tipX, tipY);
                    arrow.lineTo(neckX + hhw * u1x, neckY + hhw * u1y);
                    arrow.lineTo(neckX + hw * u1x, neckY + hw * u1y);
                    arrow.lineTo(kx + hw * u1x - hw * u2x, ky + hw * u1y - hw * u2y);
                    arrow.lineTo(fx - hw * u2x, fy - hw * u2y);
                    arrow.close();
                }
            } else {
                float dx = tx - fx, dy = ty - fy;
                float len = (float) Math.hypot(dx, dy);
                if (len > 1f) {
                    float ux = dx / len, uy = dy / len;
                    float nx = -uy, ny = ux;
                    float tipX = tx + ux * (s * 0.10f);
                    float tipY = ty + uy * (s * 0.10f);
                    float neckX = tipX - ux * hl;
                    float neckY = tipY - uy * hl;

                    arrow.moveTo(fx + nx * hw, fy + ny * hw);
                    arrow.lineTo(neckX + nx * hw, neckY + ny * hw);
                    arrow.lineTo(neckX + nx * hhw, neckY + ny * hhw);
                    arrow.lineTo(tipX, tipY);
                    arrow.lineTo(neckX - nx * hhw, neckY - ny * hhw);
                    arrow.lineTo(neckX - nx * hw, neckY - ny * hw);
                    arrow.lineTo(fx - nx * hw, fy - ny * hw);
                    arrow.close();
                }
            }

            if (!arrow.isEmpty()) {
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(withAlpha(colTo, 0xCC));
                c.drawPath(arrow, fill);

                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeJoin(Paint.Join.ROUND);
                stroke.setStrokeCap(Paint.Cap.ROUND);
                stroke.setStrokeWidth(Math.max(1.5f, dp * 1.1f));
                stroke.setColor(withAlpha(0xFF1A3306, 0x55));
                c.drawPath(arrow, stroke);
            }
        }

        // ------------------------------------------------------------------ badge (disabled by default; never writes piece names)
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
        textRim.setTextSize(badgeSize);
        textRim.setStyle(Paint.Style.STROKE);
        textRim.setStrokeWidth(badgeSize * 0.16f);
        textRim.setColor(0xFF000000);
        c.drawText(label, mx, baseline, textRim);
        text.setColor(0xFFFFFFFF);
        text.setStyle(Paint.Style.FILL);
        c.drawText(label, mx, baseline, text);
    }

    /** Coordinate-only text (never writes piece names or piece symbols) */
    public static String label(int piece, String from, String to, String promo, boolean mine) {
        StringBuilder sb = new StringBuilder();
        sb.append(from).append(" \u2192 ").append(to);
        if (promo != null && !promo.isEmpty()) sb.append(" = ").append(promo.toUpperCase());
        return sb.toString();
    }
}
