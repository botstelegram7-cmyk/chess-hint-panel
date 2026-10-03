package com.chesshint.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** The little chess button that floats over the game.  Tap = open the control panel, drag = move it. */
public class BubbleView extends View {

    public interface Listener {
        void onBubbleTap();
        void onBubbleLongPress();
        void onBubbleMove(float dx, float dy);
        void onBubbleDrop();
        void onBubbleClose();
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private Listener listener;


    private float lastX, lastY, downX, downY;
    private boolean moved, longFired;
    private boolean auto;
    /** the little ✕ in the corner: one tap and the whole panel is gone */
    private boolean closeButton;
    private final Runnable longPress = () -> {
        longFired = true;
        listener.onBubbleLongPress();
    };

    public BubbleView(Context c, Listener l) {
        super(c);
        listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    private float scaleFactor = 1f;

    public void setAuto(boolean a) { auto = a; invalidate(); }

    public void setCloseButton(boolean b) { closeButton = b; invalidate(); }

    /** the ✕ hotspot, in view coordinates */
    private float closeCx() { return getWidth() - dp * 5.5f; }
    private float closeCy() { return dp * 5.5f; }
    private float closeR() { return dp * 9.5f; }

    public void setScaleFactor(float f) {
        scaleFactor = Math.max(0.7f, Math.min(1.4f, f));
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Math.round(dp * 62 * scaleFactor);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float r = w / 2f;

        // glow
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x3300E676);
        c.drawCircle(r, r, r, p);

        p.setColor(0xF00B1B2A);
        c.drawCircle(r, r, r * 0.86f, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp * 2.4f);
        p.setColor(auto ? 0xFF00E676 : 0xFFFFD400);
        c.drawCircle(r, r, r * 0.86f, p);

        t.setColor(0xFFFFFFFF);
        t.setTextSize(r * 1.05f);
        c.drawText("\u265E", r, r + r * 0.36f, t);

        t.setTextSize(dp * 9f);
        t.setColor(auto ? 0xFF00E676 : 0xFFFFD400);
        c.drawText("HINT", r, h - dp * 6f, t);

        if (closeButton) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xF2FF5C6C);
            c.drawCircle(closeCx(), closeCy(), dp * 7.5f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp * 1.6f);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(closeCx(), closeCy(), dp * 7.5f, p);
            p.setStrokeWidth(dp * 2.2f);
            float a = dp * 3.1f;
            c.drawLine(closeCx() - a, closeCy() - a, closeCx() + a, closeCy() + a, p);
            c.drawLine(closeCx() + a, closeCy() - a, closeCx() - a, closeCy() + a, p);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (closeButton) {
                    float dx = e.getX() - closeCx(), dy = e.getY() - closeCy();
                    if (dx * dx + dy * dy <= closeR() * closeR()) {   // generous hit area
                        removeCallbacks(longPress);
                        listener.onBubbleClose();
                        return true;
                    }
                }
                lastX = e.getRawX();
                lastY = e.getRawY();
                downX = lastX; downY = lastY;
                moved = false;
                longFired = false;
                postDelayed(longPress, 550);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = e.getRawX(), y = e.getRawY();
                float dx = x - lastX, dy = y - lastY;
                if (!moved && (Math.abs(x - downX) > dp * 8 || Math.abs(y - downY) > dp * 8)) {
                    moved = true;
                    removeCallbacks(longPress);
                }
                if (moved) {
                    listener.onBubbleMove(dx, dy);
                    lastX = x;
                    lastY = y;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (moved) listener.onBubbleDrop();
                else if (!longFired) listener.onBubbleTap();
                return true;
        }
        return super.onTouchEvent(e);
    }
}
