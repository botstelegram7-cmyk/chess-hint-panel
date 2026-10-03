package com.chesshint.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** Full screen "put the frame over the board" helper. */
public class CalibrationView extends View {

    public interface Listener {
        void onCalibSave(Rect r);
        void onCalibCancel();
        void onCalibAuto();
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final Listener l;

    public Rect rect = new Rect();
    private final RectF btnAuto = new RectF(), btnSave = new RectF(), btnCancel = new RectF();
    private int mode = 0;                 // 0 none, 1 move, 2 corner, 3 new
    private float downX, downY, grabX, grabY;
    private int corner = 0;

    public CalibrationView(Context c, Listener l) {
        super(c);
        this.l = l;
        dp = c.getResources().getDisplayMetrics().density;
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setTextAlign(Paint.Align.CENTER);
    }

    private int d(float v) { return Math.round(v * dp); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float bw = w / 3.4f, bh = d(46);
        float y = h - bh - d(22);
        float gap = d(8);
        float x = (w - (bw * 3 + gap * 2)) / 2f;
        btnAuto.set(x, y, x + bw, y + bh);
        btnSave.set(x + bw + gap, y, x + 2 * bw + gap, y + bh);
        btnCancel.set(x + 2 * (bw + gap), y, x + 3 * bw + 2 * gap, y + bh);
        if (rect.width() < 40) {
            int s = Math.min(w, h) * 6 / 10;
            rect.set((w - s) / 2, (h - s) / 2 - d(40), (w + s) / 2, (h + s) / 2 - d(40));
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xAA000000);
        c.drawRect(0, 0, W, rect.top, p);
        c.drawRect(0, rect.bottom, W, H, p);
        c.drawRect(0, rect.top, rect.left, rect.bottom, p);
        c.drawRect(rect.right, rect.top, W, rect.bottom, p);

        // grid
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp);
        p.setColor(0x66FFD400);
        float s = rect.width() / 8f;
        for (int k = 1; k < 8; k++) {
            c.drawLine(rect.left + k * s, rect.top, rect.left + k * s, rect.bottom, p);
            c.drawLine(rect.left, rect.top + k * s, rect.right, rect.top + k * s, p);
        }
        p.setStrokeWidth(dp * 2.5f);
        p.setColor(0xFFFFD400);
        c.drawRect(rect.left, rect.top, rect.right, rect.bottom, p);

        // corner handles
        float hs = d(26);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF00E676);
        c.drawRect(rect.left - hs / 2, rect.top - hs / 2, rect.left + hs / 2, rect.top + hs / 2, p);
        c.drawRect(rect.right - hs / 2, rect.top - hs / 2, rect.right + hs / 2, rect.top + hs / 2, p);
        c.drawRect(rect.left - hs / 2, rect.bottom - hs / 2, rect.left + hs / 2, rect.bottom + hs / 2, p);
        c.drawRect(rect.right - hs / 2, rect.bottom - hs / 2, rect.right + hs / 2, rect.bottom + hs / 2, p);

        t.setTextSize(dp * 16);
        t.setColor(0xFFFFFFFF);
        c.drawText("Drag the frame exactly over the chess board", W / 2f, d(46), t);
        t.setTextSize(dp * 12.5f);
        t.setColor(0xFFB8CAD9);
        c.drawText("corners = resize  \u2022  middle = move  \u2022  empty area = new frame", W / 2f, d(68), t);

        drawBtn(c, btnAuto, "AUTO DETECT", 0xFF1B2C3E, 0xFF7FD4FF);
        drawBtn(c, btnSave, "\u2714  SAVE", 0xFF00E676, 0xFF062015);
        drawBtn(c, btnCancel, "\u2715  CANCEL", 0xFF3A1620, 0xFFFF9AA5);
    }

    private void drawBtn(Canvas c, RectF r, String label, int bg, int fg) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(bg);
        c.drawRoundRect(r, d(12), d(12), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp * 1.4f);
        p.setColor(0x66FFFFFF);
        c.drawRoundRect(r, d(12), d(12), p);
        t.setTextSize(dp * 14);
        t.setColor(fg);
        c.drawText(label, r.centerX(), r.centerY() - (t.descent() + t.ascent()) / 2f, t);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y;
                if (btnAuto.contains(x, y)) { mode = 10; return true; }
                if (btnSave.contains(x, y)) { mode = 11; return true; }
                if (btnCancel.contains(x, y)) { mode = 12; return true; }
                float grab = d(46);
                int c = nearestCorner(x, y, grab);
                if (c > 0) {
                    mode = 2; corner = c;
                    grabX = c == 1 || c == 3 ? rect.right : rect.left;
                    grabY = c <= 2 ? rect.bottom : rect.top;
                    return true;
                }
                if (x > rect.left && x < rect.right && y > rect.top && y < rect.bottom) {
                    mode = 1; grabX = x - rect.left; grabY = y - rect.top;
                    return true;
                }
                mode = 3; grabX = x; grabY = y;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == 1) {
                    int nx = Math.round(x - grabX), ny = Math.round(y - grabY);
                    rect.offset(nx - rect.left, ny - rect.top);
                    invalidate();
                } else if (mode == 2) {
                    int nx0 = rect.left, ny0 = rect.top, nx1 = rect.right, ny1 = rect.bottom;
                    if (corner == 1 || corner == 4) nx0 = Math.round(x); else nx1 = Math.round(x);
                    if (corner == 1 || corner == 2) ny0 = Math.round(y); else ny1 = Math.round(y);
                    int w = Math.abs(nx1 - nx0), h = Math.abs(ny1 - ny0);
                    int s = Math.max(Math.max(w, h), d(80));
                    int sx = nx1 > nx0 ? nx0 : nx0 - s + w;
                    int sy = ny1 > ny0 ? ny0 : ny0 - s + h;
                    if (corner == 1) { sx = nx1 - s; sy = ny1 - s; }
                    if (corner == 2) { sx = nx0; sy = ny1 - s; }
                    if (corner == 3) { sx = nx1 - s; sy = ny0; }
                    if (corner == 4) { sx = nx0; sy = ny0; }
                    rect.set(sx, sy, sx + s, sy + s);
                    invalidate();
                } else if (mode == 3) {
                    int s = Math.round(Math.max(Math.abs(x - grabX), Math.abs(y - grabY)));
                    int sx = Math.round(Math.min(x, grabX)), sy = Math.round(Math.min(y, grabY));
                    rect.set(sx, sy, sx + s, sy + s);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mode == 10 && btnAuto.contains(x, y)) l.onCalibAuto();
                else if (mode == 11 && btnSave.contains(x, y)) l.onCalibSave(new Rect(rect));
                else if (mode == 12 && btnCancel.contains(x, y)) l.onCalibCancel();
                mode = 0;
                return true;
        }
        return true;
    }

    private int nearestCorner(float x, float y, float grab) {
        if (Math.hypot(x - rect.left, y - rect.top) < grab) return 1;
        if (Math.hypot(x - rect.right, y - rect.top) < grab) return 3;
        if (Math.hypot(x - rect.left, y - rect.bottom) < grab) return 2;
        if (Math.hypot(x - rect.right, y - rect.bottom) < grab) return 4;
        return 0;
    }
}
