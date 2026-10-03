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
import android.os.HandlerThread;
import android.os.Looper;
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
    /** the ImageReader callback gets its OWN thread - it must never wait for the hint worker */
    private HandlerThread grabThread;
    private Handler grabHandler;
    private Image pending;
    private final Object lock = new Object();
    private int w, h, dpi;
    private boolean dead = false;
    private String lastError = "";
    private int frames;
    private volatile long lastFrameAt;
    private volatile long lastBlackAt;

    public ScreenGrab(Context ctx, MediaProjection projection, Handler unusedWorker) {
        this.ctx = ctx.getApplicationContext();
        this.projection = projection;
        this.handler = unusedWorker;      // kept for compatibility, not used for the frames
    }

    /**
     * Frames must be drained on a thread that is never blocked by board reading or the engine.
     * Sharing the hint worker here was the reason screen reading looked "dead": the listener
     * could not run while grabWait() was sleeping on that very thread.
     */
    private Handler frameHandler() {
        if (grabHandler == null) {
            grabThread = new HandlerThread("chesshint-frames");
            grabThread.start();
            grabHandler = new Handler(grabThread.getLooper());
        }
        return grabHandler;
    }

    /** ms since the last frame arrived, or -1 if none yet */
    public long msSinceLastFrame() {
        long t = lastFrameAt;
        return t == 0 ? -1 : System.currentTimeMillis() - t;
    }

    /** true when the frames we get are a flat, mostly dark surface (protected content / dead display) */
    public boolean lastFrameLookedBlank() {
        return lastBlackAt != 0 && lastBlackAt >= lastFrameAt;
    }

    /** quick check used on the first frame: is the picture uniform? */
    private void checkBlank(Bitmap b) {
        try {
            int n = 12, sum = 0, sumSq = 0, count = 0, min = 255, max = 0;
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++) {
                    int px = Math.min(b.getWidth() - 1, (x + 1) * b.getWidth() / (n + 1));
                    int py = Math.min(b.getHeight() - 1, (y + 1) * b.getHeight() / (n + 1));
                    int c = b.getPixel(px, py);
                    int lum = (((c >> 16) & 255) * 30 + ((c >> 8) & 255) * 59 + (c & 255) * 11) / 100;
                    sum += lum; sumSq += lum * lum; count++;
                    if (lum < min) min = lum;
                    if (lum > max) max = lum;
                }
            double mean = sum / (double) count;
            double var = sumSq / (double) count - mean * mean;
            if (max - min < 10 && var < 12) lastBlackAt = System.currentTimeMillis();
        } catch (Throwable ignored) { }
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
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
                reader.setOnImageAvailableListener(r -> {
                    try {
                        Image im = r.acquireLatestImage();
                        if (im != null) {
                            synchronized (lock) {
                                if (pending != null) pending.close();
                                pending = im;
                                lastFrameAt = System.currentTimeMillis();
                            }
                        }
                    } catch (Throwable ignored) { }
                }, handler);
                vd = projection.createVirtualDisplay("chesshint", w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, frameHandler());
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
            checkBlank(bmp);
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
        try { if (grabHandler != null) grabHandler.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
        try { if (grabThread != null) grabThread.quitSafely(); } catch (Throwable ignored) { }
        grabHandler = null; grabThread = null;
    }
}
