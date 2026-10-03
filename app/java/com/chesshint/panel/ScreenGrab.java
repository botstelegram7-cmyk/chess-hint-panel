package com.chesshint.panel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/** Grabs frames of the phone screen (MediaProjection -> VirtualDisplay -> ImageReader). */
public class ScreenGrab {

    private final Context ctx;
    private final MediaProjection projection;
    private final Handler handler;

    private VirtualDisplay vd;
    private ImageReader reader;
    private Image pending;
    private final Object lock = new Object();
    private int w, h, dpi;
    private boolean dead = false;
    private String lastError = "";
    private int frames;

    public ScreenGrab(Context ctx, MediaProjection projection, Handler handler) {
        this.ctx = ctx.getApplicationContext();
        this.projection = projection;
        this.handler = handler;
    }

    @SuppressWarnings("deprecation")
    private Point realSize() {
        Point p = new Point();
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            DisplayMetrics dm = new DisplayMetrics();
            d.getRealMetrics(dm);
            p.set(dm.widthPixels, dm.heightPixels);
            p.x = Math.max(p.x, dm.heightPixels);
            p.y = Math.max(p.y, dm.widthPixels);
            // use the smaller/larger pair: the virtual display must always match the screen
            int a = dm.widthPixels, b = dm.heightPixels;
            p.set(Math.min(a, b), Math.max(a, b));
        } catch (Throwable ignored) { }
        if (p.x == 0) {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int a = dm.widthPixels, b = dm.heightPixels;
            p.set(Math.min(a, b), Math.max(a, b));
        }
        return p;
    }

    public String lastError() { return lastError; }

    /** starts the virtual display; retries a couple of times because some devices need a moment */
    public synchronized boolean init() {
        if (dead) return false;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Point p = realSize();
                DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                w = p.x; h = p.y; dpi = dm.densityDpi;
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
                reader.setOnImageAvailableListener(r -> {
                    try {
                        Image im = r.acquireLatestImage();
                        if (im != null) {
                            synchronized (lock) {
                                if (pending != null) pending.close();
                                pending = im;
                            }
                        }
                    } catch (Throwable ignored) { }
                }, handler);
                vd = projection.createVirtualDisplay("chesshint", w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
                if (vd != null) {
                    lastError = "";
                    return true;
                }
                lastError = "virtual display was refused";
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            }
            releaseDisplay();
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        }
        return false;
    }

    /** recreate the virtual display when the screen rotated / changed size */
    public synchronized void refresh() {
        Point p = realSize();
        if (p.x != w || p.y != h) {
            releaseDisplay();
            init();
        }
    }

    private synchronized void releaseDisplay() {
        try { if (vd != null) vd.release(); } catch (Throwable ignored) { }
        vd = null;
        try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
        reader = null;
    }

    /** latest frame as a bitmap, already turned into the display's current orientation */
    public Bitmap grab() {
        Image im = null;
        synchronized (lock) {
            if (pending != null) { im = pending; pending = null; }
        }
        if (im == null) return null;
        Bitmap bmp = null;
        try {
            Image.Plane plane = im.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int width = im.getWidth(), height = im.getHeight();
            int bmpW = rowStride / pixelStride;
            Bitmap raw = Bitmap.createBitmap(bmpW, height, Bitmap.Config.ARGB_8888);
            buf.rewind();
            raw.copyPixelsFromBuffer(buf);
            if (bmpW != width) {
                bmp = Bitmap.createBitmap(raw, 0, 0, width, height);
                raw.recycle();
            } else {
                bmp = raw;
            }
            frames++;
        } catch (Throwable t) {
            return null;
        } finally {
            try { im.close(); } catch (Throwable ignored) { }
        }

        // rotate when the frame does not match the current display orientation
        Point p = realSize();
        if (bmp.getWidth() == p.y && bmp.getHeight() == p.x && p.x != p.y) {
            Matrix m = new Matrix();
            m.postRotate(90);
            try {
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                bmp.recycle();
                return r;
            } catch (Throwable ignored) { }
        }
        return bmp;
    }

    /** wait for a frame (max ms) */
    public Bitmap grabWait(long ms) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < ms) {
            Bitmap b = grab();
            if (b != null) return b;
            try { Thread.sleep(60); } catch (InterruptedException ignored) { }
        }
        return null;
    }

    /** grab two identical pictures in a row -> the board is not animating any more */
    public Bitmap grabStable() {
        Bitmap prev = null;
        int[] prevSmall = null;
        for (int i = 0; i < 8; i++) {
            Bitmap b = grabWait(1200);
            if (b == null) return null;
            int[] small = thumb(b, 24);
            if (prevSmall != null && sameThumb(prevSmall, small)) {
                if (prev != null) prev.recycle();
                return b;
            }
            if (prev != null) prev.recycle();
            prev = b;
            prevSmall = small;
            try { Thread.sleep(110); } catch (InterruptedException ignored) { }
        }
        return prev;
    }

    private static int[] thumb(Bitmap b, int n) {
        int[] out = new int[n * n];
        float sx = b.getWidth() / (float) n, sy = b.getHeight() / (float) n;
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) {
                int px = Math.min(b.getWidth() - 1, (int) ((x + 0.5f) * sx));
                int py = Math.min(b.getHeight() - 1, (int) ((y + 0.5f) * sy));
                out[y * n + x] = b.getPixel(px, py);
            }
        return out;
    }

    private static boolean sameThumb(int[] a, int[] b) {
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            int ca = a[i], cb = b[i];
            int d = Math.abs(((ca >> 16) & 255) - ((cb >> 16) & 255))
                    + Math.abs(((ca >> 8) & 255) - ((cb >> 8) & 255))
                    + Math.abs((ca & 255) - (cb & 255));
            if (d > 26) diff++;
        }
        return diff <= 1;   // allow one noisy sample
    }

    public int frameCount() { return frames; }

    public void release() {
        dead = true;
        synchronized (lock) {
            if (pending != null) { pending.close(); pending = null; }
        }
        releaseDisplay();
    }
}
