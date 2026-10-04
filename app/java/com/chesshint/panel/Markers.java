package com.chesshint.panel;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * Renders move suggestions, selected-piece states, and valid-move indicators on the chessboard.
 *
 * Guarantees:
 *  - Every arrow starts at the exact center (fx, fy) of the source square and ends at the
 *    exact center (tx, ty) of the destination square (including L-shaped Knight paths).
 *  - Every ring and valid-move dot is circular and centered at the exact geometric center
 *    of its square, with radius strictly bounded inside the square so marks never bleed
 *    across square boundaries.
 *  - Supports displaying all valid legal moves for a selected piece (dots on empty target
 *    squares, capture rings on enemy-occupied target squares) as well as a clear blocked
 *    state when a selected piece has no legal moves.
 */
public class Markers {

    public static final int STYLE_BOTH = 0;
    public static final int STYLE_ARROW = 1;
    public static final int STYLE_RINGS = 2;
    public static final int STYLE_SQUARES = 3;

    /** palette[i] = {from colour, to/arrow colour} */
    public static final int[][] PALETTE = {
            {0xFF76C424, 0xFF76C424},   // Chess.com green (default)
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
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    /** Backwards-compatible overload used by Settings preview. */
    public static void drawHint(Canvas c, Rect board, int from, int to, int style,
                                int palette, float dp, float scale, float pulse,
                                Paint fill, Paint stroke, Paint text, Paint textRim,
                                String label, boolean showLabel) {
        drawHint(c, board, from, to, null, null, false, style, palette, dp, scale, pulse, 1f,
                fill, stroke, text, textRim, label, showLabel);
    }

    /**
     * Full hint & valid-move renderer.
     *
     * @param board          exact board rectangle on screen
     * @param from           source square index (0..63)
     * @param to             best destination square index (0..63), or -1 if blocked (noLegalMoves)
     * @param validTargets   all legal destination square indices for the piece on `from` (optional)
     * @param captureFlags   boolean[64] true where a valid target square is a capture (optional)
     * @param pieceSelected  true when the user specifically selected this piece on the board
     * @param entrance       0..1 smooth entrance animation factor
     */
    public static void drawHint(Canvas c, Rect board, int from, int to,
                                int[] validTargets, boolean[] captureFlags, boolean pieceSelected,
                                int style, int palette, float dp, float scale, float pulse, float entrance,
                                Paint fill, Paint stroke, Paint text, Paint textRim,
                                String label, boolean showLabel) {
        if (board == null || from < 0 || from > 63) return;
        float sw = board.width() / 8f;
        float sh = board.height() / 8f;
        float s = Math.min(sw, sh);
        if (s < 8f) return;

        int px = from % 8, py = from / 8;
        float fx = board.left + (px + 0.5f) * sw;
        float fy = board.top + (py + 0.5f) * sh;

        int colFrom = from(palette), colTo = to(palette);
        float pl = Math.max(0.78f, Math.min(1.12f, pulse));
        float ent = Math.max(0.15f, Math.min(1f, entrance));

        // -------------------------------------------------- 0) Blocked piece (no legal moves)
        if (to < 0 || to > 63) {
            int danger = 0xFFFF5C6C;
            float rad = s * 0.37f * ent;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(withAlpha(danger, (int) (0x36 * ent)));
            c.drawCircle(fx, fy, rad, fill);

            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(dp * 3.2f, s * 0.075f) * scale);
            stroke.setColor(withAlpha(danger, (int) (0xEE * ent)));
            c.drawCircle(fx, fy, rad, stroke);
            return;
        }

        int qx = to % 8, qy = to / 8;
        float tx = board.left + (qx + 0.5f) * sw;
        float ty = board.top + (qy + 0.5f) * sh;

        // -------------------------------------------------- 1) Valid-move indicators for selected piece
        if (validTargets != null && validTargets.length > 0 && (pieceSelected || style == STYLE_BOTH || style == STYLE_RINGS)) {
            for (int vIdx : validTargets) {
                if (vIdx < 0 || vIdx > 63 || vIdx == from) continue;
                int vxCol = vIdx % 8, vyRow = vIdx / 8;
                float vx = board.left + (vxCol + 0.5f) * sw;
                float vy = board.top + (vyRow + 0.5f) * sh;
                boolean isCap = captureFlags != null && vIdx < captureFlags.length && captureFlags[vIdx];
                boolean isPrimary = (vIdx == to);

                if (isCap) {
                    float cRad = s * 0.37f * ent;
                    stroke.setStyle(Paint.Style.STROKE);
                    stroke.setStrokeWidth(Math.max(dp * 2.8f, s * 0.068f) * scale);
                    stroke.setColor(withAlpha(colTo, (int) ((isPrimary ? 0xD8 : 0x96) * ent)));
                    c.drawCircle(vx, vy, cRad, stroke);
                } else if (!isPrimary || style == STYLE_RINGS) {
                    float dRad = s * (isPrimary ? 0.17f : 0.135f) * scale * ent;
                    fill.setStyle(Paint.Style.FILL);
                    fill.setColor(withAlpha(colTo, (int) ((isPrimary ? 0xD0 : 0x8C) * ent)));
                    c.drawCircle(vx, vy, dRad, fill);
                }
            }
        }

        // -------------------------------------------------- 2) Filled squares style
        if (style == STYLE_SQUARES) {
            float in = s * 0.04f;
            RectF rf = new RectF(board.left + px * sw + in, board.top + py * sh + in,
                    board.left + (px + 1) * sw - in, board.top + (py + 1) * sh - in);
            RectF rt = new RectF(board.left + qx * sw + in, board.top + qy * sh + in,
                    board.left + (qx + 1) * sw - in, board.top + (qy + 1) * sh - in);

            fill.setStyle(Paint.Style.FILL);
            fill.setColor(withAlpha(colFrom, (int) (0x48 * ent)));
            c.drawRoundRect(rf, s * 0.12f, s * 0.12f, fill);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(dp * 3.2f, s * 0.065f) * scale);
            stroke.setColor(withAlpha(colFrom, (int) (0xE0 * ent)));
            c.drawRoundRect(rf, s * 0.12f, s * 0.12f, stroke);

            fill.setStyle(Paint.Style.FILL);
            fill.setColor(withAlpha(colTo, (int) (0x58 * ent)));
            c.drawRoundRect(rt, s * 0.12f, s * 0.12f, fill);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(dp * 3.8f, s * 0.075f) * scale * pl);
            stroke.setColor(withAlpha(colTo, (int) (0xEE * ent)));
            c.drawRoundRect(rt, s * 0.12f, s * 0.12f, stroke);
        }

        // -------------------------------------------------- 3) Centered circular rings
        if (style == STYLE_RINGS || style == STYLE_BOTH || pieceSelected) {
            float ringRad = s * 0.375f * ent;
            float ringStroke = Math.max(dp * 3.4f, s * 0.075f) * scale * pl;

            // Source square ring (centered strictly at fx, fy)
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(withAlpha(colFrom, (int) (0x26 * ent)));
            c.drawCircle(fx, fy, ringRad, fill);

            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(ringStroke);
            stroke.setColor(withAlpha(colFrom, (int) (0xDC * ent)));
            c.drawCircle(fx, fy, ringRad, stroke);

            if (style == STYLE_RINGS || style == STYLE_BOTH) {
                // Destination square ring (centered strictly at tx, ty)
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(withAlpha(colTo, (int) (0x2E * ent)));
                c.drawCircle(tx, ty, ringRad, fill);

                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(ringStroke);
                stroke.setColor(withAlpha(colTo, (int) (0xEC * ent)));
                c.drawCircle(tx, ty, ringRad, stroke);
            }
        }

        // -------------------------------------------------- 4) Arrow (exact square center -> exact square center)
        if (style == STYLE_ARROW || style == STYLE_BOTH) {
            int df = Math.abs(px - qx), dr = Math.abs(py - qy);
            boolean knight = (df == 1 && dr == 2) || (df == 2 && dr == 1);

            float hw = Math.max(dp * 4.2f, s * 0.130f * scale);
            float hhw = Math.max(hw * 2.10f, s * 0.315f * scale);
            float hl = Math.max(hhw * 1.15f, s * 0.410f * scale);
            Path arrow = new Path();

            if (knight) {
                // L-shaped Knight path: 2 squares first, then 90-degree turn for 1 square,
                // starting at exact center (fx, fy) and ending at exact center (tx, ty)
                float kx, ky;
                if (dr == 2) { kx = fx; ky = ty; }
                else { kx = tx; ky = fy; }
                float d1x = kx - fx, d1y = ky - fy;
                float l1 = (float) Math.hypot(d1x, d1y);
                float d2x = tx - kx, d2y = ty - ky;
                float l2 = (float) Math.hypot(d2x, d2y) * ent;
                if (l1 > 1f && l2 > 1f) {
                    float u1x = d1x / l1, u1y = d1y / l1;
                    float u2x = d2x / (float) Math.hypot(d2x, d2y);
                    float u2y = d2y / (float) Math.hypot(d2x, d2y);
                    // Tip is at the EXACT center (tx, ty) of the destination square
                    float tipX = kx + u2x * l2;
                    float tipY = ky + u2y * l2;
                    float headLen = Math.min(hl, l2 * 0.65f);
                    float neckX = tipX - u2x * headLen;
                    float neckY = tipY - u2y * headLen;

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
                float fullLen = (float) Math.hypot(dx, dy);
                float len = fullLen * ent;
                if (len > 1f) {
                    float ux = dx / fullLen, uy = dy / fullLen;
                    float nx = -uy, ny = ux;
                    // Tip is at the EXACT center (tx, ty) of the destination square
                    float tipX = fx + ux * len;
                    float tipY = fy + uy * len;
                    float headLen = Math.min(hl, len * 0.55f);
                    float neckX = tipX - ux * headLen;
                    float neckY = tipY - uy * headLen;

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
                int alphaFill = (int) (0xCC * ent);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(withAlpha(colTo, alphaFill));
                c.drawPath(arrow, fill);
                // Rounded origin cap at exact center (fx, fy)
                c.drawCircle(fx, fy, hw, fill);

                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeJoin(Paint.Join.ROUND);
                stroke.setStrokeCap(Paint.Cap.ROUND);
                stroke.setStrokeWidth(Math.max(1.5f, dp * 1.1f));
                stroke.setColor(withAlpha(0xFF1A3306, (int) (0x55 * ent)));
                c.drawPath(arrow, stroke);
            }
        }

        // -------------------------------------------------- 5) Optional coordinate badge (never writes piece names)
        if (!showLabel || label == null || label.isEmpty()) return;
        float badgeSize = Math.max(dp * 14f, Math.min(dp * 20f, s * 0.40f * scale));
        text.setTextSize(badgeSize);
        float w = text.measureText(label) + dp * 22f;
        float h = badgeSize * 1.85f;
        float mx = (fx + tx) / 2f, my = (fy + ty) / 2f;
        mx = Math.max(board.left + w / 2 + dp * 2, Math.min(board.right - w / 2 - dp * 2, mx));
        my = Math.max(board.top + h / 2 + dp * 2, Math.min(board.bottom - h / 2 - dp * 2, my));

        RectF r = new RectF(mx - w / 2, my - h / 2, mx + w / 2, my + h / 2);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(withAlpha(0xFF0B131C, (int) (0xF0 * ent)));
        c.drawRoundRect(r, h * 0.36f, h * 0.36f, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp * 2.0f);
        stroke.setColor(withAlpha(colTo, (int) (0xFF * ent)));
        c.drawRoundRect(r, h * 0.36f, h * 0.36f, stroke);

        float baseline = my - (text.descent() + text.ascent()) / 2f;
        textRim.setTextSize(badgeSize);
        textRim.setStyle(Paint.Style.STROKE);
        textRim.setStrokeWidth(badgeSize * 0.15f);
        textRim.setColor(withAlpha(0xFF000000, (int) (0xFF * ent)));
        c.drawText(label, mx, baseline, textRim);
        text.setColor(withAlpha(0xFFFFFFFF, (int) (0xFF * ent)));
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
