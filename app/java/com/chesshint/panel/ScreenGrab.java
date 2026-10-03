package com.chesshint.panel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Grabs frames of the phone screen (MediaProjection -> VirtualDisplay -> ImageReader).
 *
 * Key guarantees (v1.6):
 *  1) Both ImageReader.setOnImageAvailableListener AND VirtualDisplay use the dedicated
 *     "chesshint-frames" HandlerThread — never the hint worker thread — so grabWait() never
 *     deadlocks the listener callback.
 *  2) Decodes padded rowStride buffers row-by-row so 1080p/720p gralloc buffers without trailing
 *     last-row padding never throw RuntimeException("Buffer not large enough for pixels").
 *  3) Caches the most recent decoded Bitmap (lastBitmap) and closes acquired Images immediately
 *     so static chess boards (where SurfaceFlinger emits no new buffers while nothing moves)
 *     always return a valid frame instead of null.
 */
public class ScreenGrab {

    private final Context ctx;
    private final MediaProjection projection;

    private VirtualDisplay vd;
    private ImageReader reader;
    private HandlerThread grabThread;
    private Handler grabHandler;

    private final Object lock = new Object();
    private final Object readerLock = new Object();

    private Bitmap lastBitmap;
    private long frameSeq = 0;
    private int w, h, dpi;
    private volatile boolean dead = false;
    private volatile String lastError = "";
    private volatile int frames = 0;
    private volatile long lastFrameAt = 0;
    private volatile long lastBlackAt = 0;

    public ScreenGrab(Context ctx, MediaProjection projection, Handler unusedWorker) {
        this.ctx = ctx.getApplicationContext();
        this.projection = projection;
        frameHandler();
    }

    private synchronized Handler frameHandler() {
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
            if (Build.VERSION.SDK_INT >= 30) {
                Rect b = wm.getMaximumWindowMetrics().getBounds();
                p.set(b.width(), b.height());
            } else {
                Display d = wm.getDefaultDisplay();
                DisplayMetrics dm = new DisplayMetrics();
                d.getRealMetrics(dm);
                p.set(dm.widthPixels, dm.heightPixels);
            }
        } catch (Throwable ignored) { }
        if (p.x <= 0 || p.y <= 0) {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            p.set(dm.widthPixels, dm.heightPixels);
        }
        return p;
    }

    public String lastError() { return lastError; }

    /** Starts the virtual display; uses dedicated frameHandler() for both ImageReader and VirtualDisplay. */
    public synchronized boolean init() {
        if (dead) return false;
        Handler fh = frameHandler();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Point p = realSize();
                DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                w = p.x;
                h = p.y;
                dpi = dm.densityDpi;
                synchronized (readerLock) {
                    reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
                    reader.setOnImageAvailableListener(r -> consumeLatestFromReader(), fh);
                }
                vd = projection.createVirtualDisplay(
                        "chesshint",
                        w,
                        h,
                        dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(),
                        null,
                        fh);
                if (vd != null) {
                    lastError = "";
                    return true;
                }
                lastError = "virtual display was refused";
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
                if (Build.VERSION.SDK_INT >= 34) {
                    releaseDisplay();
                    break;
                }
            }
            releaseDisplay();
            try { Thread.sleep(250); } catch (InterruptedException ignored) { }
        }
        return false;
    }

    /** Resize existing virtual display if screen orientation/size changed (safe on Android 14+). */
    public synchronized void refresh() {
        if (dead) return;
        Point p = realSize();
        if (p.x > 0 && p.y > 0 && (p.x != w || p.y != h)) {
            w = p.x;
            h = p.y;
            try {
                Handler fh = frameHandler();
                ImageReader oldReader;
                ImageReader newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
                newReader.setOnImageAvailableListener(r -> consumeLatestFromReader(), fh);
                synchronized (readerLock) {
                    oldReader = reader;
                    reader = newReader;
                }
                if (vd != null) {
                    vd.resize(w, h, dpi);
                    vd.setSurface(newReader.getSurface());
                }
                if (oldReader != null) {
                    try { oldReader.close(); } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }
        }
    }

    private void consumeLatestFromReader() {
        if (dead) return;
        Image im = null;
        Bitmap decoded = null;
        synchronized (readerLock) {
            if (dead || reader == null) return;
            try {
                im = reader.acquireLatestImage();
            } catch (Throwable ignored) {
                im = null;
            }
            if (im == null) return;
            try {
                decoded = decodeImage(im);
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            } finally {
                try { im.close(); } catch (Throwable ignored) { }
            }
        }
        if (decoded != null) {
            long now = System.currentTimeMillis();
            lastFrameAt = now;
            checkBlank(decoded);
            synchronized (lock) {
                if (dead) {
                    decoded.recycle();
                    return;
                }
                if (lastBitmap != null && lastBitmap != decoded) {
                    lastBitmap.recycle();
                }
                lastBitmap = decoded;
                frames++;
                frameSeq++;
                lock.notifyAll();
            }
        }
    }

    private Bitmap decodeImage(Image im) {
        Image.Plane[] planes = im.getPlanes();
        if (planes == null || planes.length == 0) return null;
        Image.Plane plane = planes[0];
        ByteBuffer buf = plane.getBuffer();
        if (buf == null) return null;
        int width = im.getWidth();
        int height = im.getHeight();
        if (width <= 0 || height <= 0) return null;
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        Bitmap bmp;
        buf.rewind();
        if (pixelStride == 4 && rowStride == width * 4 && buf.remaining() >= width * height * 4) {
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
        } else if (pixelStride == 4 && rowStride >= width * 4) {
            int rowBytesLen = width * 4;
            byte[] rowBuf = new byte[rowBytesLen];
            ByteBuffer tight = ByteBuffer.allocateDirect(width * height * 4);
            int limit = buf.limit();
            for (int y = 0; y < height; y++) {
                int pos = y * rowStride;
                if (pos + rowBytesLen <= limit) {
                    buf.position(pos);
                    buf.get(rowBuf, 0, rowBytesLen);
                    tight.put(rowBuf);
                } else if (pos < limit) {
                    int avail = limit - pos;
                    buf.position(pos);
                    buf.get(rowBuf, 0, avail);
                    Arrays.fill(rowBuf, avail, rowBytesLen, (byte) 0);
                    tight.put(rowBuf);
                }
            }
            tight.rewind();
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(tight);
        } else {
            int[] colors = new int[width * height];
            int limit = buf.limit();
            for (int y = 0; y < height; y++) {
                int rowStart = y * rowStride;
                for (int x = 0; x < width; x++) {
                    int p = rowStart + x * pixelStride;
                    if (p + 3 < limit) {
                        int r = buf.get(p) & 0xFF;
                        int g = buf.get(p + 1) & 0xFF;
                        int b = buf.get(p + 2) & 0xFF;
                        int a = buf.get(p + 3) & 0xFF;
                        colors[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                }
            }
            bmp = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
        }

        Point p = realSize();
        if (bmp.getWidth() == p.y && bmp.getHeight() == p.x && p.x != p.y) {
            Matrix m = new Matrix();
            m.postRotate(90);
            try {
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (r != bmp) bmp.recycle();
                return r;
            } catch (Throwable ignored) { }
        }
        return bmp;
    }

    private synchronized void releaseDisplay() {
        try { if (vd != null) vd.release(); } catch (Throwable ignored) { }
        vd = null;
        synchronized (readerLock) {
            try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
            reader = null;
        }
    }

    /** Returns a copy of the latest frame (caller may safely recycle it), or null if none yet. */
    public Bitmap grab() {
        if (dead) return null;
        refresh();
        consumeLatestFromReader();
        synchronized (lock) {
            if (lastBitmap != null && !lastBitmap.isRecycled()) {
                try {
                    return lastBitmap.copy(Bitmap.Config.ARGB_8888, false);
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    /** Wait up to ms for a frame. */
    public Bitmap grabWait(long ms) {
        return grabWait(ms, null);
    }

    /** Wait up to ms for a frame, optionally nudging SurfaceFlinger so static screens emit a frame. */
    public Bitmap grabWait(long ms, Runnable nudge) {
        long t0 = System.currentTimeMillis();
        long lastNudge = 0;
        while (!dead && System.currentTimeMillis() - t0 < ms) {
            Bitmap b = grab();
            if (b != null) return b;
            long now = System.currentTimeMillis();
            if (nudge != null && now - lastNudge >= 100) {
                lastNudge = now;
                try { nudge.run(); } catch (Throwable ignored) { }
            }
            synchronized (lock) {
                try { lock.wait(45); } catch (InterruptedException ignored) { }
            }
        }
        return grab();
    }

    /**
     * Waits up to ms for a NEW frame arriving after this call starts (e.g. after hiding the panel/marks),
     * falling back to the cached lastBitmap if the screen is completely unchanged.
     */
    public Bitmap grabFresh(long ms, Runnable nudge) {
        if (dead) return null;
        refresh();
        long startSeq;
        synchronized (lock) {
            startSeq = frameSeq;
        }
        if (nudge != null) {
            try { nudge.run(); } catch (Throwable ignored) { }
        }
        long t0 = System.currentTimeMillis();
        long lastNudge = t0;
        while (!dead && System.currentTimeMillis() - t0 < ms) {
            consumeLatestFromReader();
            synchronized (lock) {
                if (frameSeq > startSeq && lastBitmap != null && !lastBitmap.isRecycled()) {
                    try {
                        return lastBitmap.copy(Bitmap.Config.ARGB_8888, false);
                    } catch (Throwable ignored) { }
                }
                try { lock.wait(45); } catch (InterruptedException ignored) { }
            }
            long now = System.currentTimeMillis();
            if (nudge != null && now - lastNudge >= 110) {
                lastNudge = now;
                try { nudge.run(); } catch (Throwable ignored) { }
            }
        }
        return grabWait(150, nudge);
    }

    /** Grab a stable frame (waits for any piece movement animation to settle, never fails on a static screen). */
    public Bitmap grabStable() {
        return grabStable(null);
    }

    public Bitmap grabStable(Runnable nudge) {
        Bitmap prev = grabFresh(900, nudge);
        if (prev == null) return null;
        int[] prevSmall = thumb(prev, 24);
        for (int i = 0; i < 5; i++) {
            long seqBefore;
            synchronized (lock) {
                seqBefore = frameSeq;
            }
            try { Thread.sleep(95); } catch (InterruptedException ignored) { }
            consumeLatestFromReader();
            Bitmap next = null;
            synchronized (lock) {
                if (frameSeq > seqBefore && lastBitmap != null && !lastBitmap.isRecycled()) {
                    try {
                        next = lastBitmap.copy(Bitmap.Config.ARGB_8888, false);
                    } catch (Throwable ignored) { }
                }
            }
            if (next == null) {
                return prev;
            }
            int[] small = thumb(next, 24);
            if (sameThumb(prevSmall, small)) {
                prev.recycle();
                return next;
            }
            prev.recycle();
            prev = next;
            prevSmall = small;
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
        return diff <= 1;
    }

    public int frameCount() { return frames; }

    public void release() {
        dead = true;
        releaseDisplay();
        synchronized (lock) {
            if (lastBitmap != null) {
                try { lastBitmap.recycle(); } catch (Throwable ignored) { }
                lastBitmap = null;
            }
            lock.notifyAll();
        }
        try { if (grabHandler != null) grabHandler.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
        try { if (grabThread != null) grabThread.quitSafely(); } catch (Throwable ignored) { }
        grabHandler = null;
        grabThread = null;
    }
}
