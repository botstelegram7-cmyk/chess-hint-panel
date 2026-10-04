package com.chesshint.panel;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen -> chess board.
 *
 *  1) detect()  finds the board rectangle on the screenshot (grid lines + checkerboard search)
 *  2) read()    decides for every square: empty / white piece / black piece.
 *
 * The reader never uses fixed colours.  Every square is compared against its OWN four
 * corners, so it works with any board theme - and it is immune to highlighted squares:
 * a highlighted (or dotted) square stays uniform, so it is correctly reported as EMPTY.
 *
 * The core works on plain int[] pixels so the exact same code can be unit tested on a PC.
 */
public class Vision {

    public static class Result {
        public int[] colorPat = new int[64];    // 0 empty, 1 white piece, 2 black piece
        public float[] conf = new float[64];    // certainty per square (0..1)
        public int[] centerRgb = new int[64];
        public float[] inkFrac = new float[64];
        public float[] pieceHeight = new float[64];
        public float[] asym = new float[64];
        public float[][] bands = new float[64][8];
        public float[] score = new float[64];
        public float[] dmax = new float[64];
        public float[] inkLum = new float[64];
        public boolean[] highlighted = new boolean[64];
        public float[] highlightScore = new float[64];
        public float threshold;
        public float lmid;
        public boolean ok;
        public String note = "";
    }

    // =====================================================================
    //  android wrappers
    // =====================================================================

    /**
     * Finds the board on a screenshot.
     * Scales proportionally (preserving exact 1:1 aspect ratio) to <= 720 px width so
     * memory stays small and full-resolution alignment never distorts vertical coordinates.
     */
    public static Rect detect(Bitmap full) {
        if (full == null) return null;
        int W0 = full.getWidth(), H0 = full.getHeight();
        if (W0 < 64 || H0 < 64) return null;

        int tw = Math.min(W0, 720);
        int th = Math.max(64, Math.round(H0 * (tw / (float) W0)));
        Bitmap work = null;
        try {
            work = (tw == W0 && th == H0) ? full : Bitmap.createScaledBitmap(full, tw, th, true);
            int[] px = new int[tw * th];
            work.getPixels(px, 0, tw, 0, 0, tw, th);
            Rect r = detect(px, tw, th);
            if (r == null) return null;
            if (tw == W0 && th == H0) return r;
            float kx = W0 / (float) tw, ky = H0 / (float) th;
            int left = Math.max(0, Math.round(r.left * kx));
            int top = Math.max(0, Math.round(r.top * ky));
            int side = Math.min(Math.round(r.width() * kx), Math.min(W0 - left, H0 - top));
            return new Rect(left, top, left + side, top + side);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (work != null && work != full) work.recycle();
        }
    }

    /**
     * Reads a board straight from a screenshot.
     * First verifies and snaps `rect` to the exact 8x8 grid lines on `full` (so if the board
     * shifted due to a bot chat bubble, layout change, or stale calibration, `rect` is updated
     * in place to 0px error before reading the 64 squares).
     */
    public static Result read(Bitmap full, Rect rect) {
        Result res = new Result();
        if (full == null || rect == null || rect.width() < 48) return res;
        int W0 = full.getWidth(), H0 = full.getHeight();
        Rect c = new Rect(Math.max(0, rect.left), Math.max(0, rect.top),
                Math.min(W0, rect.right), Math.min(H0, rect.bottom));
        if (c.width() < 48 || c.height() < 48) return res;

        // Re-align `c` on `full` if the board shifted vertically/horizontally or needs 1px grid snap
        try {
            int padY = Math.min(Math.max(16, c.height() / 5), H0);
            int stripTop = Math.max(0, c.top - padY);
            int stripBot = Math.min(H0, c.bottom + padY);
            int stripH = stripBot - stripTop;
            if (stripH >= c.width() && W0 <= 1440) {
                int[] stripPx = new int[W0 * stripH];
                full.getPixels(stripPx, 0, W0, 0, stripTop, W0, stripH);
                Rect localRect = new Rect(c.left, c.top - stripTop, c.right, c.bottom - stripTop);
                Rect snapped = snapOrRealign(stripPx, W0, stripH, localRect);
                if (snapped != null) {
                    c.set(snapped.left, snapped.top + stripTop, snapped.right, snapped.bottom + stripTop);
                    rect.set(c);
                }
            }
        } catch (Throwable ignored) { }

        Bitmap crop = null;
        boolean owned = false;
        int[] px = null;
        try {
            int side0 = Math.min(c.width(), c.height());
            crop = Bitmap.createBitmap(full, c.left, c.top, side0, side0);
            owned = crop != full;
            int target = 720;
            if (side0 > target) {
                Bitmap scaled = Bitmap.createScaledBitmap(crop, target, target, true);
                if (scaled != crop) {
                    if (owned) crop.recycle();
                    crop = scaled;
                    owned = true;
                }
            }
            int s = Math.min(crop.getWidth(), crop.getHeight());
            px = new int[s * s];
            crop.getPixels(px, 0, s, 0, 0, s, s);
            res = read(px, s, s, new Rect(0, 0, s, s));
        } catch (Throwable t) {
            res = new Result();
        } finally {
            if (owned && crop != null) crop.recycle();
            px = null;
        }
        return res;
    }

    // =====================================================================
    //  1) BOARD DETECTION
    // =====================================================================

    public static Rect detect(int[] pxFull, int W0, int H0) {
        if (pxFull == null || W0 < 64 || H0 < 64) return null;

        float sc0 = Math.min(1f, 416f / W0);
        int W = Math.max(80, (Math.round(W0 * sc0) / 8) * 8);
        float sc = W / (float) W0;
        int H = Math.max(80, Math.round(H0 * sc));
        int[] px;
        if (W == W0 && H == H0) px = pxFull;
        else {
            px = new int[W * H];
            for (int y = 0; y < H; y++) {
                int sy = Math.min(H0 - 1, (int) (y / sc));
                for (int x = 0; x < W; x++) {
                    int sx = Math.min(W0 - 1, (int) (x / sc));
                    px[y * W + x] = pxFull[sy * W0 + sx];
                }
            }
        }

        float[] lum = new float[W * H];
        for (int i = 0; i < px.length; i++) lum[i] = lumOf(px[i]);

        float[] mag = new float[W * H];
        for (int y = 1; y < H - 1; y++)
            for (int x = 1; x < W - 1; x++) {
                int i = y * W + x;
                mag[i] = (Math.abs(lum[i + 1] - lum[i - 1]) + Math.abs(lum[i + W] - lum[i - W])) * 0.5f;
            }

        float[] colP = new float[W], rowP = new float[H];
        for (int x = 0; x < W; x++) {
            float a = 0;
            for (int y = 0; y < H; y++) a += mag[y * W + x];
            colP[x] = a / H;
        }
        for (int y = 0; y < H; y++) {
            float a = 0;
            int o = y * W;
            for (int x = 0; x < W; x++) a += mag[o + x];
            rowP[y] = a / W;
        }
        float[] colS = box3(colP), rowS = box3(rowP);

        int minS = Math.max(4, Math.round(W * 0.30f / 8f));
        int maxS = Math.max(minS + 1, W / 8);
        List<Cand> cands = new ArrayList<>();
        int step = W > 320 ? 2 : 1;
        for (int s = minS; s <= maxS; s++) {
            int side = 8 * s;
            if (side > W || side > H) continue;
            for (int x0 = 0; x0 + side <= W; x0 += step) {
                for (int y0 = 0; y0 + side <= H; y0 += step) {
                    float ls = 0, ms = 0;
                    int lCnt = 0;
                    for (int k = 0; k <= 8; k++) {
                        int xx = x0 + k * s;
                        if (xx <= 1 || xx >= W - 2) continue;
                        ls += colS[xx];
                        lCnt++;
                    }
                    for (int k = 0; k < 8; k++) ms += colS[Math.min(W - 1, x0 + (int) ((k + 0.5f) * s))];
                    ls /= Math.max(1, lCnt); ms /= 8f;
                    float lr = 0, mr = 0;
                    int rCnt = 0;
                    for (int k = 0; k <= 8; k++) {
                        int yy = y0 + k * s;
                        if (yy <= 1 || yy >= H - 2) continue;
                        lr += rowS[yy];
                        rCnt++;
                    }
                    for (int k = 0; k < 8; k++) mr += rowS[Math.min(H - 1, y0 + (int) ((k + 0.5f) * s))];
                    lr /= Math.max(1, rCnt); mr /= 8f;
                    float base = (ls + lr) / 2f, mid = (ms + mr) / 2f;
                    float contrast = (base - mid) / (base + 0.002f);
                    if (contrast > 0.05f) cands.add(new Cand(s, x0, y0, contrast));
                }
            }
        }
        if (cands.isEmpty()) return null;
        cands.sort((a, b) -> Float.compare(b.score, a.score));

        // ---- narrow down with a fast "does this look like a checkerboard" colour test
        int n = Math.min(cands.size(), 700);
        float maxCon = Math.max(1e-4f, cands.get(0).score);
        Candidate[] top = new Candidate[n];
        for (int i = 0; i < n; i++) {
            Cand c = cands.get(i);
            float alt = colorAlt(px, W, H, c.s, c.x, c.y);
            top[i] = new Candidate(c.x, c.y, c.s, 0.40f * (c.score / maxCon) + 0.60f * alt, alt);
        }
        java.util.Arrays.sort(top, (a, b) -> Float.compare(b.score, a.score));

        // ---- take the best few up to full resolution and hill climb on the real pixels
        Rect bestRect = null;
        float bestScore = -1;
        int tries = Math.min(12, top.length);
        for (int i = 0; i < tries; i++) {
            Candidate c = top[i];
            int fx = Math.round(c.x / sc), fy = Math.round(c.y / sc), fs = Math.round(8 * c.s / sc);
            if (fs < 64) continue;
            if (fx <= 6 && fx + fs >= W0 - 6) { fx = 0; fs = W0; }
            Rect r = new Rect(fx, fy, fx + fs, fy + fs);
            r = alignBoard(pxFull, W0, H0, r);
            float alt = colorAltFull(pxFull, W0, H0, r);
            if (alt < 0.24f) continue;                     // no checkerboard -> not a board
            float sc2 = frameScore(pxFull, W0, H0, r) + 0.12f * (c.score / maxCon);
            if (sc2 > bestScore) { bestScore = sc2; bestRect = r; }
        }
        if (bestRect == null || bestScore < 0.42f) return null;   // nothing board-like here
        return bestRect;
    }

    // --------------------------------------------------------------- fine alignment

    private static float colDist(int a, int b) {
        return (Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                + Math.abs((a & 255) - (b & 255))) / 765f;
    }

    private static int clampI(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static float med(float[] v, int n) {
        float[] t = new float[n];
        System.arraycopy(v, 0, t, 0, n);
        java.util.Arrays.sort(t);
        return t[n / 2];
    }

    /** contrast across the 7 internal vertical grid lines; sharp only when the frame sits on the board */
    private static float vLines(int[] px, int W, int H, int x0, int y0, int side) {
        return vLines(px, W, H, x0, y0, side, 0.045f);
    }

    private static float vLines(int[] px, int W, int H, int x0, int y0, int side, float dFrac) {
        float sq = side / 8f;
        int d = Math.max(2, Math.round(sq * dFrac));
        float[] pair = new float[5];
        float total = 0; int cnt = 0;
        for (int line = 1; line <= 7; line++) {
            int lx = x0 + Math.round(line * sq);
            for (int row = 0; row < 8; row++) {
                for (int k = 0; k < 5; k++) {
                    int yy = clampI(y0 + Math.round(row * sq + sq * (0.18f + 0.16f * k)), 0, H - 1);
                    int xl = clampI(lx - d, 0, W - 1);
                    int xr = clampI(lx + d, 0, W - 1);
                    pair[k] = colDist(px[yy * W + xl], px[yy * W + xr]);
                }
                total += med(pair, 5); cnt++;
            }
        }
        return cnt > 0 ? total / cnt : 0;
    }

    private static float hLines(int[] px, int W, int H, int x0, int y0, int side) {
        return hLines(px, W, H, x0, y0, side, 0.045f);
    }

    private static float hLines(int[] px, int W, int H, int x0, int y0, int side, float dFrac) {
        float sq = side / 8f;
        int d = Math.max(2, Math.round(sq * dFrac));
        float[] pair = new float[5];
        float total = 0; int cnt = 0;
        for (int line = 1; line <= 7; line++) {
            int ly = y0 + Math.round(line * sq);
            for (int col = 0; col < 8; col++) {
                for (int k = 0; k < 5; k++) {
                    int xx = clampI(x0 + Math.round(col * sq + sq * (0.18f + 0.16f * k)), 0, W - 1);
                    int yu = clampI(ly - d, 0, H - 1);
                    int yd = clampI(ly + d, 0, H - 1);
                    pair[k] = colDist(px[yu * W + xx], px[yd * W + xx]);
                }
                total += med(pair, 5); cnt++;
            }
        }
        return cnt > 0 ? total / cnt : 0;
    }

    /** does the frame border fall on a real edge of the board? */
    private static float perimeter(int[] px, int W, int H, int x0, int y0, int side) {
        float sq = side / 8f;
        int d = Math.max(2, Math.round(sq * 0.10f));
        float[] v = new float[8];
        float tot = 0; int n = 0;
        if (x0 - d >= 0) {
            for (int row = 0; row < 8; row++) {
                int yy = clampI(y0 + Math.round((row + 0.5f) * sq), 0, H - 1);
                v[row] = colDist(px[yy * W + clampI(x0 - d, 0, W - 1)], px[yy * W + clampI(x0 + d, 0, W - 1)]);
            }
            tot += med(v, 8); n++;
        }
        if (x0 + side + d < W) {
            for (int row = 0; row < 8; row++) {
                int yy = clampI(y0 + Math.round((row + 0.5f) * sq), 0, H - 1);
                v[row] = colDist(px[yy * W + clampI(x0 + side - d, 0, W - 1)], px[yy * W + clampI(x0 + side + d, 0, W - 1)]);
            }
            tot += med(v, 8); n++;
        }
        if (y0 - d >= 0) {
            for (int col = 0; col < 8; col++) {
                int xx = clampI(x0 + Math.round((col + 0.5f) * sq), 0, W - 1);
                v[col] = colDist(px[clampI(y0 - d, 0, H - 1) * W + xx], px[clampI(y0 + d, 0, H - 1) * W + xx]);
            }
            tot += med(v, 8); n++;
        }
        if (y0 + side + d < H) {
            for (int col = 0; col < 8; col++) {
                int xx = clampI(x0 + Math.round((col + 0.5f) * sq), 0, W - 1);
                v[col] = colDist(px[clampI(y0 + side - d, 0, H - 1) * W + xx], px[clampI(y0 + side + d, 0, H - 1) * W + xx]);
            }
            tot += med(v, 8); n++;
        }
        return n > 0 ? tot / n : 0f;
    }

    /** 1D search over one axis using the grid-line contrast */
    private static int[] axisSearch(int[] px, int W, int H, boolean vertical, int x0, int y0, int side, int range) {
        return axisSearch(px, W, H, vertical, x0, y0, side, range, 0.12f);
    }

    private static int[] axisSearch(int[] px, int W, int H, boolean vertical, int x0, int y0, int side, int range, float dFrac) {
        int bestV = vertical ? x0 : y0;
        float bestS = -1;
        int step = Math.max(1, range / 4);
        for (int s = step; s >= 1; s /= 2) {
            int bs = bestV;
            for (int o = -2 * s; o <= 2 * s; o += s) {
                int v = bestV + o;
                int xx = vertical ? v : x0, yy = vertical ? y0 : v;
                if (xx < 0 || yy < 0 || xx + side > W || yy + side > H) continue;
                float sc = (vertical ? vLines(px, W, H, xx, yy, side, dFrac) : hLines(px, W, H, xx, yy, side, dFrac))
                        + 0.55f * perimeter(px, W, H, xx, yy, side);
                if (sc > bestS) { bestS = sc; bestV = v; }
            }
            if (bs == bestV && s == 1) break;
        }
        return new int[]{vertical ? bestV : x0, vertical ? y0 : bestV};
    }

    /** put the frame exactly on the board: shift on both axes, then fix the pitch, then repeat */
    private static Rect alignBoard(int[] px, int W, int H, Rect r) {
        return alignBoard(px, W, H, r, Math.max(3, Math.round(r.width() * 0.42f)));
    }

    private static Rect alignBoard(int[] px, int W, int H, Rect r, int range) {
        int x = r.left, y = r.top, side = r.width();
        for (int pass = 0; pass < 2; pass++) {
            float df = (pass == 0) ? 0.13f : 0.045f;
            int rPass = (pass == 0) ? range : Math.max(4, side / 32);
            int[] a = axisSearch(px, W, H, true, x, y, side, rPass, df);
            x = a[0]; y = a[1];
            a = axisSearch(px, W, H, false, x, y, side, rPass, df);
            x = a[0]; y = a[1];
            // pitch
            int bestS = side;
            float bestSc = -1;
            int step = Math.max(1, side / 40);
            for (int s = step; s >= 1; s /= 2) {
                int bs = bestS;
                for (int o = -2 * s; o <= 2 * s; o += s) {
                    int ns = bestS + o;
                    if (ns < 64 || x + ns > W || y + ns > H) continue;
                    float sc = vLines(px, W, H, x, y, ns, df) + hLines(px, W, H, x, y, ns, df);
                    if (sc > bestSc) { bestSc = sc; bestS = ns; }
                }
                if (bs == bestS && s == 1) break;
            }
            side = bestS;
        }
        Rect bestR = new Rect(x, y, x + side, y + side);
        float bestF = frameScore(px, W, H, bestR);

        // Check +/- 1 or 2 square shifts along Y and X (escapes 1-rank periodic local minima!)
        int sq = Math.max(8, Math.round(side / 8f));
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx * sq, ny = y + dy * sq;
                if (nx < 0 || ny < 0 || nx + side > W || ny + side > H) continue;
                int[] ay = axisSearch(px, W, H, false, nx, ny, side, Math.max(4, sq / 4), 0.045f);
                Rect cand = new Rect(nx, ay[1], nx + side, ay[1] + side);
                float f = frameScore(px, W, H, cand);
                if (f > bestF) { bestF = f; bestR = cand; }
            }
        }

        // Also check full-width board (x = 0, side = W) when board is nearly full width
        if (side >= W * 0.88f && W <= H) {
            int fwSq = Math.max(8, Math.round(W / 8f));
            int baseY = bestR.top;
            for (int dy = -2; dy <= 2; dy++) {
                int ny = clampI(baseY + dy * fwSq, 0, H - W);
                int[] ay = axisSearch(px, W, H, false, 0, ny, W, Math.max(4, fwSq / 3), 0.045f);
                Rect cand = new Rect(0, ay[1], W, ay[1] + W);
                float f = frameScore(px, W, H, cand);
                if (f > bestF) { bestF = f; bestR = cand; }
            }
        }
        return snapToExactGridLines(px, W, H, bestR);
    }

    /**
     * Exact 1-pixel grid-line snapper: refines a coarsely aligned board Rect to 0px error
     * by maximizing the 1-pixel edge step across all 9 horizontal lines (k=0..8) and
     * internal vertical lines (k=1..7), gated by top-strip/bottom-strip checkerboard alternation.
     */
    private static Rect snapToExactGridLines(int[] px, int W, int H, Rect r) {
        if (r == null || r.width() < 64) return r;
        int x0 = r.left, y0 = r.top, s0 = r.width();
        int bestY = y0, bestX = x0, bestS = s0;

        // 1. Snap vertical position y and size s using exact 1-pixel horizontal steps across k=0..8
        float bestYScore = -1f;
        for (int ds = -4; ds <= 4; ds += 2) {
            int s = s0 + ds;
            if (s < 64 || x0 + s > W) continue;
            for (int dy = -5; dy <= 5; dy++) {
                int ny = y0 + dy;
                if (ny < 0 || ny + s > H) continue;
                if (outerStripAlt(px, W, H, x0, ny, s) < 0.024f) continue;
                float sc = exactHLineStep(px, W, H, x0, ny, s);
                if (sc > bestYScore) {
                    bestYScore = sc;
                    bestY = ny;
                    bestS = s;
                }
            }
        }
        // 2. Snap horizontal position x if not already locked to full screen width
        if (!(bestX == 0 && bestS == W)) {
            float bestXScore = -1f;
            for (int dx = -6; dx <= 6; dx++) {
                int nx = x0 + dx;
                if (nx < 0 || nx + bestS > W) continue;
                float sc = exactVLineStep(px, W, H, nx, bestY, bestS);
                if (sc > bestXScore) {
                    bestXScore = sc;
                    bestX = nx;
                }
            }
        }
        return new Rect(bestX, bestY, bestX + bestS, bestY + bestS);
    }

    /**
     * Re-aligns a previously saved board Rect when the board may have shifted vertically
     * (e.g. when a bot chat bubble appears above the board) or horizontally on screen.
     */
    private static Rect snapOrRealign(int[] px, int W, int H, Rect r) {
        if (r == null || r.width() < 64) return r;
        int x0 = clampI(r.left, 0, Math.max(0, W - 64));
        int s0 = Math.min(r.width(), Math.min(W - x0, H));
        if (s0 < 64) return r;
        int y0 = clampI(r.top, 0, H - s0);

        // Search all vertical offsets inside the padded strip using exact 9-line step + outer-strip alternation
        int bestY = y0;
        float bestSc = -1f;
        int maxShift = Math.max(12, s0 / 5);
        for (int dy = -maxShift; dy <= maxShift; dy++) {
            int ny = y0 + dy;
            if (ny < 0 || ny + s0 > H) continue;
            float outAlt = outerStripAlt(px, W, H, x0, ny, s0);
            if (outAlt < 0.025f) continue;
            float hStep = exactHLineStep(px, W, H, x0, ny, s0);
            float sc = hStep * (0.5f + Math.min(0.5f, outAlt * 4f));
            if (sc > bestSc) {
                bestSc = sc;
                bestY = ny;
            }
        }
        Rect aligned = new Rect(x0, bestY, x0 + s0, bestY + s0);
        return snapToExactGridLines(px, W, H, aligned);
    }

    /** Exact 1-pixel vertical color step across all 9 horizontal grid lines k=0..8 */
    private static float exactHLineStep(int[] px, int W, int H, int x0, int y0, int side) {
        float sq = side / 8f;
        float sum = 0f;
        int cnt = 0;
        for (int k = 0; k <= 8; k++) {
            int yk = y0 + Math.round(k * sq);
            if (yk < 2 || yk >= H - 2) continue;
            float lineSum = 0f;
            int lineCnt = 0;
            for (int f = 0; f < 8; f++) {
                int xA = clampI(x0 + Math.round((f + 0.30f) * sq), 0, W - 1);
                int xB = clampI(x0 + Math.round((f + 0.70f) * sq), 0, W - 1);
                float d1 = colDist(px[(yk - 1) * W + xA], px[yk * W + xA])
                         + colDist(px[(yk - 1) * W + xB], px[yk * W + xB]);
                float d2 = colDist(px[(yk - 2) * W + xA], px[(yk + 1) * W + xA])
                         + colDist(px[(yk - 2) * W + xB], px[(yk + 1) * W + xB]);
                // Subtract nearby interior step 4px away so only sharp grid lines peak
                int yIn = clampI(yk + (k < 8 ? 4 : -4), 1, H - 1);
                float dIn = colDist(px[(yIn - 1) * W + xA], px[yIn * W + xA])
                          + colDist(px[(yIn - 1) * W + xB], px[yIn * W + xB]);
                lineSum += Math.max(0f, (d1 + 0.35f * d2) - 0.8f * dIn);
                lineCnt += 2;
            }
            if (lineCnt > 0) {
                float wgt = (k == 0 || k == 8) ? 0.75f : 1.25f;
                sum += wgt * (lineSum / lineCnt);
                cnt++;
            }
        }
        return cnt > 0 ? sum / cnt : 0f;
    }

    /** Exact 1-pixel horizontal color step across the 7 internal vertical grid lines k=1..7 */
    private static float exactVLineStep(int[] px, int W, int H, int x0, int y0, int side) {
        float sq = side / 8f;
        float sum = 0f;
        int cnt = 0;
        for (int k = 1; k <= 7; k++) {
            int xk = x0 + Math.round(k * sq);
            if (xk < 2 || xk >= W - 2) continue;
            float lineSum = 0f;
            int lineCnt = 0;
            for (int r = 0; r < 8; r++) {
                int yA = clampI(y0 + Math.round((r + 0.30f) * sq), 0, H - 1);
                int yB = clampI(y0 + Math.round((r + 0.70f) * sq), 0, H - 1);
                float d1 = colDist(px[yA * W + (xk - 1)], px[yA * W + xk])
                         + colDist(px[yB * W + (xk - 1)], px[yB * W + xk]);
                int xIn = clampI(xk + 4, 1, W - 1);
                float dIn = colDist(px[yA * W + (xIn - 1)], px[yA * W + xIn])
                          + colDist(px[yB * W + (xIn - 1)], px[yB * W + xIn]);
                lineSum += Math.max(0f, d1 - 0.8f * dIn);
                lineCnt += 2;
            }
            if (lineCnt > 0) { sum += lineSum / lineCnt; cnt++; }
        }
        return cnt > 0 ? sum / cnt : 0f;
    }

    /**
     * Verifies that BOTH the top strip of row 0 (y = top + 14% of sq) AND the bottom strip of
     * row 7 (y = bottom - 14% of sq) alternate light/dark across files f=0..7.
     * If `r` is shifted vertically by >= 14% of a square into a UI header/footer, one strip
     * lands in the uniform UI bar and its alternation drops near 0.
     */
    private static float outerStripAlt(int[] px, int W, int H, int x0, int y0, int side) {
        if (side < 64 || x0 < 0 || y0 < 0 || x0 + side > W || y0 + side > H) return 0f;
        float sq = side / 8f;
        int yTop = clampI(y0 + Math.round(0.14f * sq), 0, H - 1);
        int yBot = clampI(y0 + side - Math.round(0.14f * sq), 0, H - 1);
        float topAlt = 0f, botAlt = 0f;
        for (int f = 0; f < 7; f++) {
            int x1 = clampI(x0 + Math.round((f + 0.80f) * sq), 0, W - 1);
            int x2 = clampI(x0 + Math.round((f + 1.80f) * sq), 0, W - 1);
            topAlt += colDist(px[yTop * W + x1], px[yTop * W + x2]);
            botAlt += colDist(px[yBot * W + x1], px[yBot * W + x2]);
        }
        topAlt /= 7f;
        botAlt /= 7f;
        return Math.min(topAlt, botAlt);
    }

    /** final quality of a frame: line contrast + board edge + colour alternation */
    public static float frameScore(int[] px, int W, int H, Rect r) {
        if (r == null || r.left < 0 || r.top < 0 || r.right > W || r.bottom > H || r.width() < 64) return 0;
        float lines = (vLines(px, W, H, r.left, r.top, r.width()) + hLines(px, W, H, r.left, r.top, r.width())) / 2f;
        float per = perimeter(px, W, H, r.left, r.top, r.width());
        float alt = colorAltFull(px, W, H, r);
        return 0.42f * lines * 3.2f + 0.22f * per + 0.36f * alt;
    }

    /** local search: move/scale the frame until the squares alternate best */
    private static int[] climb(int[] px, int W, int H, int x0, int y0, int size) {
        int step = Math.max(2, size / 16);
        float best = colorAltFull(px, W, H, new Rect(x0, y0, x0 + size, y0 + size));
        for (int it = 0; it < 7 && step >= 1; it++) {
            boolean improved = false;
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = x0 + dx * step, ny = y0 + dy * step;
                    if (nx < 0 || ny < 0 || nx + size > W || ny + size > H) continue;
                    float s2 = colorAltFull(px, W, H, new Rect(nx, ny, nx + size, ny + size));
                    if (s2 > best) { best = s2; x0 = nx; y0 = ny; improved = true; }
                }
            for (int dsz = -step; dsz <= step; dsz += step) {
                if (dsz == 0) continue;
                int ns = size + dsz;
                if (ns < 64 || x0 + ns > W || y0 + ns > H) continue;
                float s2 = colorAltFull(px, W, H, new Rect(x0, y0, x0 + ns, y0 + ns));
                if (s2 > best) { best = s2; size = ns; improved = true; }
            }
            if (!improved) step = step / 2;
        }
        return new int[]{x0, y0, size};
    }

    /**
     * Samples the background RGB of a single square [x0..x1, y0..y1] using its 4 corners,
     * picking the closest-matching pair of corners so neither a large central piece nor a
     * corner coordinate label (8..1 / a..h) pollutes the background colour.
     */
    private static void sampleSquareBg(int[] px, int W, int H, int x0, int y0, int x1, int y1, float[] outRgb) {
        int wS = Math.max(2, x1 - x0), hS = Math.max(2, y1 - y0);
        int pw = Math.max(1, Math.round(wS * 0.14f));
        int in = Math.max(1, Math.round(wS * 0.06f));
        float[] cr = new float[4], cg = new float[4], cb = new float[4];
        int k = 0;
        for (int cy = 0; cy < 2; cy++) {
            for (int cx = 0; cx < 2; cx++) {
                int bx = (cx == 0) ? (x0 + in) : Math.max(x0, x1 - in - pw);
                int by = (cy == 0) ? (y0 + in) : Math.max(y0, y1 - in - pw);
                long sr = 0, sg = 0, sb = 0;
                int cnt = 0;
                for (int y = by; y < by + pw; y++) {
                    if (y < 0 || y >= H) continue;
                    int rowOff = y * W;
                    for (int x = bx; x < bx + pw; x++) {
                        if (x < 0 || x >= W) continue;
                        int c = px[rowOff + x];
                        sr += (c >> 16) & 255;
                        sg += (c >> 8) & 255;
                        sb += c & 255;
                        cnt++;
                    }
                }
                if (cnt == 0) cnt = 1;
                cr[k] = sr / (float) cnt;
                cg[k] = sg / (float) cnt;
                cb[k] = sb / (float) cnt;
                k++;
            }
        }
        int bestA = 0, bestB = 1;
        float bestD = Float.MAX_VALUE;
        for (int a = 0; a < 4; a++) {
            for (int b = a + 1; b < 4; b++) {
                float d = Math.abs(cr[a] - cr[b]) + Math.abs(cg[a] - cg[b]) + Math.abs(cb[a] - cb[b]);
                if (d < bestD) { bestD = d; bestA = a; bestB = b; }
            }
        }
        float mr = (cr[bestA] + cr[bestB]) * 0.5f;
        float mg = (cg[bestA] + cg[bestB]) * 0.5f;
        float mb = (cb[bestA] + cb[bestB]) * 0.5f;
        float sumR = cr[bestA] + cr[bestB], sumG = cg[bestA] + cg[bestB], sumB = cb[bestA] + cb[bestB];
        int n = 2;
        for (int i = 0; i < 4; i++) {
            if (i == bestA || i == bestB) continue;
            float d = Math.abs(cr[i] - mr) + Math.abs(cg[i] - mg) + Math.abs(cb[i] - mb);
            if (d < 24f) {
                sumR += cr[i]; sumG += cg[i]; sumB += cb[i];
                n++;
            }
        }
        outRgb[0] = sumR / n;
        outRgb[1] = sumG / n;
        outRgb[2] = sumB / n;
    }

    private static float colorAltFull(int[] px, int W, int H, Rect r) {
        int side = r.width();
        if (side < 64 || r.left < 0 || r.top < 0 || r.right > W || r.bottom > H) return 0;
        float[][] m = new float[64][3];
        for (int rr = 0; rr < 8; rr++)
            for (int ff = 0; ff < 8; ff++) {
                int x0 = r.left + Math.round(ff * side / 8f), y0 = r.top + Math.round(rr * side / 8f);
                int x1 = r.left + Math.round((ff + 1) * side / 8f), y1 = r.top + Math.round((rr + 1) * side / 8f);
                sampleSquareBg(px, W, H, x0, y0, x1, y1, m[rr * 8 + ff]);
            }
        return gridAlternationScore(m);
    }

    private static float gridAlternationScore(float[][] m) {
        float nb = 0; int nbc = 0; float dg = 0; int dgc = 0;
        float[] rowNb = new float[8], colNb = new float[8];
        for (int rr = 0; rr < 8; rr++)
            for (int ff = 0; ff < 8; ff++) {
                float[] a = m[rr * 8 + ff];
                if (ff < 7) {
                    float d = dist(a, m[rr * 8 + ff + 1]);
                    nb += d; nbc++;
                    rowNb[rr] += d / 7f;
                }
                if (rr < 7) {
                    float d = dist(a, m[(rr + 1) * 8 + ff]);
                    nb += d; nbc++;
                    colNb[ff] += d / 7f;
                }
                if (rr < 7 && ff < 7) {
                    dg += dist(a, m[(rr + 1) * 8 + ff + 1]); dgc++;
                    dg += dist(m[rr * 8 + ff + 1], m[(rr + 1) * 8 + ff]); dgc++;
                }
            }
        float meanNb = nb / Math.max(1, nbc);
        float base = ratio(meanNb, dg / Math.max(1, dgc));
        if (base <= 0f) return 0f;
        // Every outer rank (0 and 7) and outer file (0 and 7) on a real 8x8 chessboard alternates!
        float minOuter = Math.min(Math.min(rowNb[0], rowNb[7]), Math.min(colNb[0], colNb[7]));
        float outerPenalty = Math.min(1f, minOuter / Math.max(0.015f, meanNb * 0.55f));
        return base * outerPenalty;
    }

    private static float dist(float[] a, float[] b) {
        return (Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2])) / 765f;
    }

    private static float ratio(float nb, float dg) {
        if (nb < 0.028f) return 0f;
        float v = (nb - dg) / (nb + dg + 0.004f);
        if (v < 0) v = 0;
        return Math.min(1f, v * 2.2f);
    }

    /** colour alternation of a candidate grid on the downscaled image */
    private static float colorAlt(int[] px, int W, int H, int s, int x0, int y0) {
        float[][] mc = new float[64][3];
        float[][] mm = new float[64][3];
        int step = Math.max(1, s / 8);
        for (int r = 0; r < 8; r++)
            for (int f = 0; f < 8; f++) {
                int sx0 = x0 + f * s, sy0 = y0 + r * s;
                sampleSquareBg(px, W, H, sx0, sy0, sx0 + s, sy0 + s, mc[r * 8 + f]);
                int cx = x0 + (int) ((f + 0.5f) * s), cy = y0 + (int) ((r + 0.5f) * s);
                long sr = 0, sg = 0, sb = 0; int cnt = 0;
                for (int y = cy - s / 4; y <= cy + s / 4; y += step) {
                    if (y < 0 || y >= H) continue;
                    for (int x = cx - s / 4; x <= cx + s / 4; x += step) {
                        if (x < 0 || x >= W) continue;
                        int c = px[y * W + x];
                        sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; cnt++;
                    }
                }
                if (cnt == 0) cnt = 1;
                mm[r * 8 + f][0] = sr / (float) cnt;
                mm[r * 8 + f][1] = sg / (float) cnt;
                mm[r * 8 + f][2] = sb / (float) cnt;
            }
        float nb = 0; int nbc = 0; float dg = 0; int dgc = 0;
        for (int r = 0; r < 8; r++)
            for (int f = 0; f < 8; f++) {
                float[] a = mm[r * 8 + f];
                if (f < 7) { nb += dist(a, mm[r * 8 + f + 1]); nbc++; }
                if (r < 7) { nb += dist(a, mm[(r + 1) * 8 + f]); nbc++; }
                if (r < 7 && f < 7) {
                    dg += dist(a, mm[(r + 1) * 8 + f + 1]); dgc++;
                    dg += dist(mm[r * 8 + f + 1], mm[(r + 1) * 8 + f]); dgc++;
                }
            }
        float midAlt = ratio(nb / Math.max(1, nbc), dg / Math.max(1, dgc));
        return Math.max(gridAlternationScore(mc), midAlt);
    }

    private static class Candidate {
        final int x, y, s;
        final float score, alt;
        Candidate(int x, int y, int s, float score, float alt) {
            this.x = x; this.y = y; this.s = s; this.score = score; this.alt = alt;
        }
    }

    private static float[] box3(float[] in) {
        float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) {
            float a = in[i];
            if (i > 0) a += in[i - 1];
            if (i < in.length - 1) a += in[i + 1];
            out[i] = a / 3f;
        }
        return out;
    }

    private static float lumOf(int c) {
        return (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
    }

    private static class Cand {
        final int s, x, y;
        final float score;
        Cand(int s, int x, int y, float score) { this.s = s; this.x = x; this.y = y; this.score = score; }
    }

    // =====================================================================
    //  2) READING (empty / white / black)
    // =====================================================================

    public static Result read(int[] px, int w, int h, Rect rect) {
        Result res = new Result();
        if (px == null || rect == null || rect.width() < 48) return res;
        if (rect.left < 0 || rect.top < 0 || rect.right > w || rect.bottom > h) {
            Rect c = new Rect(Math.max(0, rect.left), Math.max(0, rect.top),
                    Math.min(w, rect.right), Math.min(h, rect.bottom));
            rect = c;
            if (rect.width() < 48) return res;
        }

        float sqw = rect.width() / 8f, sqh = rect.height() / 8f;

        // gradients of the whole board area
        float[] mag = new float[rect.width() * rect.height()];
        float[] lumB = new float[rect.width() * rect.height()];
        int bw = rect.width(), bh = rect.height();
        for (int y = 0; y < bh; y++)
            for (int x = 0; x < bw; x++) {
                int c = px[(rect.top + y) * w + rect.left + x];
                lumB[y * bw + x] = lumOf(c);
            }
        for (int y = 1; y < bh - 1; y++)
            for (int x = 1; x < bw - 1; x++) {
                int i = y * bw + x;
                mag[i] = (Math.abs(lumB[i + 1] - lumB[i - 1]) + Math.abs(lumB[i + bw] - lumB[i - bw])) * 0.5f;
            }

        float[] dmaxArr = new float[64];   // strongest colour difference inside the square
        float[] f1 = new float[64];        // mean gradient
        float[] f2 = new float[64];        // colour structure
        float[] f3 = new float[64];        // ink fraction (pixels unlike the square's own corners)
        float[] inkLum = new float[64];
        int[] cornerRgb = new int[64];
        int[] centreRgb = new int[64];
        float[] centreLum = new float[64];
        float[][] bands = new float[64][8];
        float[] inkFrac = new float[64];
        float[] pieceHeightArr = new float[64];
        float[] asymArr = new float[64];
        int[] parity = new int[64];

        for (int r = 0; r < 8; r++)
            for (int f = 0; f < 8; f++) {
                int idx = r * 8 + f;
                parity[idx] = (r + f) & 1;
                int x0 = Math.round(f * sqw), y0 = Math.round(r * sqh);
                int x1 = Math.round((f + 1) * sqw), y1 = Math.round((r + 1) * sqh);
                int wS = Math.max(1, x1 - x0), hS = Math.max(1, y1 - y0);

                // --- the square's own background = closest-matching corner pair
                float[] bgCorner = new float[3];
                sampleSquareBg(px, w, h, rect.left + x0, rect.top + y0, rect.left + x1, rect.top + y1, bgCorner);
                int corR = Math.round(bgCorner[0]), corG = Math.round(bgCorner[1]), corB = Math.round(bgCorner[2]);
                cornerRgb[idx] = (corR << 16) | (corG << 8) | corB;

                // --- piece body region: x in [22%..78%] excludes corner labels (8..1 / a..h);
                //     y in [4%..94%] captures full piece height from crown tip to base even under +-6px shift
                int mx0 = x0 + Math.round(wS * 0.22f), mx1 = x1 - Math.round(wS * 0.22f);
                int my0 = y0 + Math.round(hS * 0.04f), my1 = y1 - Math.round(hS * 0.06f);
                int sx0 = Math.max(0, mx0), sy0 = Math.max(0, my0);
                int sx1 = Math.min(bw, mx1), sy1 = Math.min(bh, my1);
                int bwI = sx1 - sx0, bhI = sy1 - sy0;
                if (bwI < 3 || bhI < 3) { bwI = Math.max(1, bwI); bhI = Math.max(1, bhI); }
                boolean[] rawMask = new boolean[Math.max(1, bwI * bhI)];
                boolean[] silMask = new boolean[Math.max(1, bwI * bhI)];
                boolean[] filledMask = new boolean[Math.max(1, bwI * bhI)];
                float[] lumI = new float[Math.max(1, bwI * bhI)];

                // Central [20%..80%] vertical window for occupancy scoring (matches empty/dot immunity)
                int cy0 = y0 + Math.round(hS * 0.20f), cy1 = y1 - Math.round(hS * 0.20f);
                int tot = 0, ink = 0;
                float dmax = 0;
                long sr = 0, sg = 0, sb = 0;
                double suml = 0, suml2 = 0;
                int mid = 0;
                for (int y = sy0; y < sy1; y++) {
                    boolean inCoreY = (y >= cy0 && y < cy1);
                    for (int x = sx0; x < sx1; x++) {
                        int c = px[(rect.top + y) * w + rect.left + x];
                        int rr = (c >> 16) & 255, gg = (c >> 8) & 255, bb = c & 255;
                        float d = (Math.abs(rr - corR) + Math.abs(gg - corG) + Math.abs(bb - corB)) / 765f;
                        float l = (0.299f * rr + 0.587f * gg + 0.114f * bb) / 255f;
                        float g = (x > 0 && x < bw - 1 && y > 0 && y < bh - 1) ? mag[y * bw + x] : 0f;
                        int bi = (y - sy0) * bwI + (x - sx0);
                        boolean neonOverlay = (gg > 175 && gg > rr + 45 && gg > bb + 45);
                        if (bi < lumI.length) {
                            lumI[bi] = l;
                            if (!neonOverlay && (d > 0.055f || (d > 0.036f && g > 0.045f))) {
                                rawMask[bi] = true;
                                if (inCoreY) ink++;
                            }
                            if (!neonOverlay && (d > 0.075f || (d > 0.052f && g > 0.065f))) {
                                silMask[bi] = true;
                            }
                        }
                        if (inCoreY) {
                            if (d > dmax) dmax = d;
                            tot++;
                            sr += rr; sg += gg; sb += bb; suml += l; suml2 += l * l; mid++;
                        }
                    }
                }
                if (mid == 0) mid = 1;
                int cR = (int) (sr / mid), cG = (int) (sg / mid), cB = (int) (sb / mid);
                centreRgb[idx] = (cR << 16) | (cG << 8) | cB;
                float meanL = (float) (suml / mid);
                centreLum[idx] = meanL;
                float std = (float) Math.sqrt(Math.max(0, suml2 / mid - meanL * meanL));
                f2[idx] = std;

                // Scanline silhouette fill: include all valid piece rows in the central [12%..90%]
                // of bhI, and extend contiguously into the top 12% / bottom 10% margins so crown tips
                // are captured while any disconnected adjacent-square border at y=0 or y=bhI-1 is ignored.
                int minRowSpan = Math.max(3, Math.round(bwI * 0.14f));
                int[] rowXL = new int[bhI];
                int[] rowXR = new int[bhI];
                boolean[] rowValid = new boolean[bhI];
                int marginTop = Math.max(2, Math.round(bhI * 0.12f));
                int marginBot = Math.min(bhI - 2, Math.round(bhI * 0.90f));
                int pTop = bhI, pBot = -1, pLeft = bwI, pRight = -1;
                for (int y = 0; y < bhI; y++) {
                    int rowOff = y * bwI;
                    int xL = -1, xR = -1;
                    for (int x = 0; x < bwI; x++) {
                        if (silMask[rowOff + x] && ((x > 0 && silMask[rowOff + x - 1]) || (x + 1 < bwI && silMask[rowOff + x + 1]))) {
                            xL = x; break;
                        }
                    }
                    for (int x = bwI - 1; x >= 0; x--) {
                        if (silMask[rowOff + x] && ((x > 0 && silMask[rowOff + x - 1]) || (x + 1 < bwI && silMask[rowOff + x + 1]))) {
                            xR = x; break;
                        }
                    }
                    rowXL[y] = xL;
                    rowXR[y] = xR;
                    if (xL >= 0 && (xR - xL) >= minRowSpan) {
                        rowValid[y] = true;
                        if (y >= marginTop && y <= marginBot) {
                            if (y < pTop) pTop = y;
                            if (y > pBot) pBot = y;
                        }
                    }
                }
                int filledCore = 0;
                if (pBot >= pTop) {
                    for (int y = pTop - 1; y >= 0; y--) {
                        if (rowValid[y]) pTop = y;
                        else if (y > 0 && rowValid[y - 1]) pTop = y - 1;
                        else break;
                    }
                    for (int y = pBot + 1; y < bhI; y++) {
                        if (rowValid[y]) pBot = y;
                        else if (y + 1 < bhI && rowValid[y + 1]) pBot = y + 1;
                        else break;
                    }
                    for (int y = pTop; y <= pBot; y++) {
                        if (!rowValid[y]) continue;
                        int rowOff = y * bwI;
                        int xL = rowXL[y], xR = rowXR[y];
                        if (xL < pLeft) pLeft = xL;
                        if (xR > pRight) pRight = xR;
                        boolean inCoreY = (sy0 + y >= cy0 && sy0 + y < cy1);
                        for (int x = xL; x <= xR; x++) {
                            filledMask[rowOff + x] = true;
                            if (inCoreY) filledCore++;
                        }
                    }
                }

                // Shift-invariant piece height, 8-band profile over [pTop..pBot], and L/R asymmetry
                if (pBot > pTop + 3 && pRight > pLeft + 2) {
                    int ph = pBot - pTop + 1;
                    pieceHeightArr[idx] = ph / (float) hS;
                    for (int b = 0; b < 8; b++) {
                        int by0 = pTop + (b * ph) / 8;
                        int by1 = Math.max(by0 + 1, pTop + ((b + 1) * ph) / 8);
                        int bCnt = 0, bTot = 0;
                        for (int y = by0; y < by1 && y < bhI; y++) {
                            int rowOff = y * bwI;
                            for (int x = 0; x < bwI; x++) {
                                bTot++;
                                if (filledMask[rowOff + x]) bCnt++;
                            }
                        }
                        bands[idx][b] = bTot > 0 ? bCnt / (float) bTot : 0f;
                    }
                    int asymDiff = 0, asymTot = 0;
                    for (int y = pTop; y <= pBot; y++) {
                        int rowOff = y * bwI;
                        for (int x = pLeft; x <= pRight; x++) {
                            asymTot++;
                            int mx = pLeft + pRight - x;
                            if (filledMask[rowOff + x] != filledMask[rowOff + mx]) asymDiff++;
                        }
                    }
                    asymArr[idx] = asymTot > 0 ? asymDiff / (float) asymTot : 0f;
                }

                // Erode the FILLED silhouette mask: outline rings disappear and the true interior
                // body of the piece survives, even when the piece interior matches the square colour.
                int er = Math.max(2, Math.min(4, Math.round(sqw * 0.030f)));
                boolean[] cur = filledMask.clone();
                boolean[] tmp = new boolean[cur.length];
                for (int it = 0; it < er; it++) {
                    java.util.Arrays.fill(tmp, false);
                    for (int y = 1; y < bhI - 1; y++)
                        for (int x = 1; x < bwI - 1; x++) {
                            int bi = y * bwI + x;
                            if (bi + bwI >= cur.length) break;
                            tmp[bi] = cur[bi] && cur[bi - 1] && cur[bi + 1] && cur[bi - bwI] && cur[bi + bwI];
                        }
                    boolean[] sw = cur; cur = tmp; tmp = sw;
                }
                int ero = 0;
                double bodyAcc = 0;
                for (int y = 0; y < bhI; y++) {
                    boolean inCoreY = (sy0 + y >= cy0 && sy0 + y < cy1);
                    int rowOff = y * bwI;
                    for (int x = 0; x < bwI; x++) {
                        if (cur[rowOff + x] && inCoreY) {
                            ero++;
                            bodyAcc += lumI[rowOff + x];
                        }
                    }
                }
                float rawInkF = tot > 0 ? ink / (float) tot : 0f;
                float filledF = tot > 0 ? filledCore / (float) tot : 0f;
                float inkF = Math.max(rawInkF, filledF * 0.85f);
                float erodeF = tot > 0 ? ero / (float) tot : 0f;
                inkFrac[idx] = inkF;
                f3[idx] = 0.5f * inkF + 0.5f * Math.min(1f, erodeF * 2.5f);
                dmaxArr[idx] = dmax;

                float bodyLum = meanL;
                if (ero >= 3) bodyLum = (float) (bodyAcc / ero);
                else if (ink > 3) {
                    double a = 0; int n = 0;
                    for (int k = 0; k < rawMask.length; k++) if (rawMask[k]) { a += lumI[k]; n++; }
                    if (n > 0) bodyLum = (float) (a / n);
                }
                inkLum[idx] = bodyLum;

                float gsum = 0; int gcnt = 0;
                for (int y = y0 + Math.round(hS * 0.14f); y < y1 - Math.round(hS * 0.14f); y++) {
                    if (y < 1 || y >= bh - 1) continue;
                    for (int x = x0 + Math.round(wS * 0.14f); x < x1 - Math.round(wS * 0.14f); x++) {
                        if (x < 1 || x >= bw - 1) continue;
                        gsum += mag[y * bw + x]; gcnt++;
                    }
                }
                f1[idx] = gcnt > 0 ? gsum / gcnt : 0;
            }

        // ---------------- combined evidence
        float m1 = 1e-4f, m2 = 1e-4f, m3 = 1e-4f;
        for (int i = 0; i < 64; i++) {
            m1 = Math.max(m1, f1[i]); m2 = Math.max(m2, f2[i]); m3 = Math.max(m3, f3[i]);
        }
        float[] score = new float[64];
        for (int i = 0; i < 64; i++)
            score[i] = 0.18f * (f1[i] / m1) + 0.14f * (f2[i] / m2) + 0.46f * (f3[i] / m3)
                    + 0.22f * Math.min(1f, dmaxArr[i] * 1.4f);

        float[] sortedScores = score.clone();
        java.util.Arrays.sort(sortedScores);
        // At least 32 squares on a chess board are always empty; sortedScores[26] is a guaranteed empty square
        float emptyFloor = sortedScores[26];
        float peakScore = sortedScores[61];
        float gapThr = emptyFloor + Math.max(0.12f, (peakScore - emptyFloor) * 0.22f);
        float thr = Math.min(otsu(score), gapThr);
        int occ = 0;
        for (int i = 0; i < 64; i++) if (score[i] > thr && dmaxArr[i] > 0.20f) occ++;
        if (occ > 32) {                       // a chess board never has more than 32 pieces
            thr = (sortedScores[31] + sortedScores[32]) * 0.5f;
            occ = 0;
            for (int i = 0; i < 64; i++) if (score[i] > thr && dmaxArr[i] > 0.20f) occ++;
        }

        // ---------------- empty-square colours of both parities (for white/black decision)
        float[] bgLum = new float[2];
        int[] bgRgb = new int[2];
        for (int p = 0; p < 2; p++) {
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < 64; i++) if (parity[i] == p) ids.add(i);
            final float[] sc2 = score;
            ids.sort((a, b) -> Float.compare(sc2[a], sc2[b]));
            int take = Math.max(3, ids.size() / 5);
            long r = 0, g = 0, b = 0;
            float l = 0;
            int n = 0;
            for (int k = 0; k < take && k < ids.size(); k++) {
                int i = ids.get(k);
                int c = cornerRgb[i];
                r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
                l += lumOf(c); n++;
            }
            n = Math.max(1, n);
            bgRgb[p] = ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
            bgLum[p] = l / n;
        }
        float lmid = (bgLum[0] + bgLum[1]) / 2f;

        // Also refine lmid using the occupied pieces' own luminance range when both colours are present
        if (occ >= 4) {
            float minPL = 1f, maxPL = 0f;
            for (int i = 0; i < 64; i++) {
                if (score[i] > thr && dmaxArr[i] > 0.20f) {
                    if (inkLum[i] < minPL) minPL = inkLum[i];
                    if (inkLum[i] > maxPL) maxPL = inkLum[i];
                }
            }
            if (maxPL - minPL > 0.22f) {
                float pMid = (minPL + maxPL) * 0.5f;
                lmid = 0.40f * lmid + 0.60f * pMid;
            }
        }

        // Reject regions that do not have alternating light/dark board squares (e.g. a UI screen or stale rect)
        if (colDist(bgRgb[0], bgRgb[1]) < 0.032f || colorAltFull(px, w, h, rect) < 0.16f
                || outerStripAlt(px, w, h, rect.left, rect.top, rect.width()) < 0.024f) {
            res.ok = false;
            res.note = "not a chess board";
            return res;
        }

        for (int i = 0; i < 64; i++) {
            boolean occupied = score[i] > thr && dmaxArr[i] > 0.20f && inkFrac[i] > 0.08f && pieceHeightArr[i] > 0.36f;
            res.colorPat[i] = occupied ? (inkLum[i] > lmid ? 1 : 2) : 0;
            res.conf[i] = Math.abs(score[i] - thr) / Math.max(1e-4f, Math.max(thr, 0.25f));
            res.centerRgb[i] = centreRgb[i];
            res.inkLum[i] = inkLum[i];
            res.inkFrac[i] = inkFrac[i];
            res.pieceHeight[i] = pieceHeightArr[i];
            res.asym[i] = asymArr[i];
            res.bands[i] = bands[i];
            res.score[i] = score[i];
            res.dmax[i] = dmaxArr[i];
            float hlDist = colDist(cornerRgb[i], bgRgb[parity[i]]);
            res.highlightScore[i] = hlDist;
            res.highlighted[i] = hlDist > 0.075f;
        }
        res.threshold = thr;
        res.lmid = lmid;

        // if one colour vanished completely the read is broken - split the piece luminances instead
        int nw = 0, nb = 0;
        for (int i = 0; i < 64; i++) {
            if (res.colorPat[i] == 1) nw++;
            else if (res.colorPat[i] == 2) nb++;
        }
        if (occ >= 3 && (nw == 0 || nb == 0)) {
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < 64; i++) if (res.colorPat[i] != 0) ids.add(i);
            float[] v = new float[ids.size()];
            for (int k = 0; k < ids.size(); k++) v[k] = inkLum[ids.get(k)];
            float t2 = otsu(v);
            for (int i : ids) res.colorPat[i] = inkLum[i] > t2 ? 1 : 2;
        }
        for (int i = 0; i < 64; i++) if (res.colorPat[i] == 0) res.conf[i] = Math.max(res.conf[i], 0.01f);

        res.ok = true;
        res.note = "occ=" + occ;
        return res;
    }

    /**
     * Automatically detects whether the user's pieces at the bottom of the screen are White (true)
     * or Black (false) by comparing piece colours on the bottom 3 ranks vs top 3 ranks.
     */
    public static boolean detectWhiteBottom(Result res, boolean fallback) {
        if (res == null || !res.ok) return fallback;
        int totalOcc = 0;
        for (int i = 0; i < 64; i++) if (res.colorPat[i] != 0) totalOcc++;
        if (totalOcc < 14) return fallback;
        int whiteBottomScore = 0, blackBottomScore = 0;
        for (int r = 0; r < 8; r++) {
            int weight = (r == 0 || r == 7) ? 2 : 1;
            for (int f = 0; f < 8; f++) {
                int c = res.colorPat[r * 8 + f];
                if (c == 0) continue;
                if (r >= 5) {
                    if (c == 1) whiteBottomScore += weight;
                    else if (c == 2) blackBottomScore += weight;
                } else if (r <= 2) {
                    if (c == 2) whiteBottomScore += weight;
                    else if (c == 1) blackBottomScore += weight;
                }
            }
        }
        if (whiteBottomScore >= blackBottomScore + 8) return true;
        if (blackBottomScore >= whiteBottomScore + 8) return false;
        return fallback;
    }

    // =====================================================================
    //  3) shift-invariant piece type classification
    // =====================================================================

    public static int guessType(Result res, int idx, boolean isWhite) {
        float pH = res.pieceHeight[idx];
        float asym = res.asym[idx];
        float ink = res.inkFrac[idx];
        float[] rawB = res.bands[idx];
        float maxB = 1e-4f;
        for (int b = 0; b < 8; b++) if (rawB[b] > maxB) maxB = rawB[b];
        float[] nb = new float[8];
        for (int b = 0; b < 8; b++) nb[b] = rawB[b] / maxB;

        // Compute board-adaptive pawn height & ink references
        float pawnInkRef = 0.36f;
        int occCnt = 0;
        float[] occInks = new float[64];
        for (int i = 0; i < 64; i++) {
            if (res.colorPat[i] != 0) occInks[occCnt++] = res.inkFrac[i];
        }
        if (occCnt >= 6) {
            java.util.Arrays.sort(occInks, 0, occCnt);
            pawnInkRef = occInks[Math.min(occCnt - 1, occCnt / 4)];
        }

        int type;
        // 1) PAWN: narrow upper/middle silhouette (nb[3] < 0.65 && nb[4] < 0.63 && nb[0] < 0.50) or shortest height
        if (pH < 0.675f || (nb[0] < 0.50f && nb[3] < 0.65f && nb[4] < 0.63f) || (pH < 0.695f && ink <= pawnInkRef * 1.12f)) {
            type = 1; // pawn
        }
        // 2) ROOK: flat wide battlements at top (nb[0] >= 0.48, nb[1..2] wider than straight tower waist nb[3..5])
        else if ((nb[0] > 0.50f && nb[1] > nb[3] + 0.08f && nb[1] > nb[4] + 0.07f)
                || (nb[0] >= 0.46f && nb[1] >= nb[3] - 0.01f && nb[2] > nb[3] + 0.14f && nb[5] < 0.82f)) {
            type = 4; // rook
        }
        // 3) KNIGHT: strong left-right asymmetry (horse head in profile)
        else if (asym > 0.105f) {
            type = 2; // knight
        }
        // 4) KING: narrow cross at top (nb[0] < 0.42, nb[1] < 0.56) jumping sharply to wide crown lobes (nb[2] > 0.80, nb[5] > 0.85)
        else if (nb[0] < 0.42f && nb[1] < 0.56f && nb[2] > 0.80f && (nb[2] - nb[1]) > 0.24f && nb[5] > 0.85f && asym < 0.06f) {
            type = 6; // king
        }
        // 5) BISHOP: pointed mitre tip (nb[0] < 0.36, nb[1] < 0.45, nb[2] < 0.78) with neck narrower than King (nb[5] < 0.84)
        else if (nb[0] < 0.36f && nb[1] < 0.45f && nb[2] < 0.78f && nb[5] < 0.84f) {
            type = 3; // bishop
        }
        // 6) QUEEN: wide multi-pointed crown (nb[0..1] >= 0.42) and very wide body (nb[3..4] > 0.82)
        else if (nb[3] > 0.82f && nb[4] > 0.82f) {
            type = 5; // queen
        } else if (nb[1] > nb[3] + 0.06f) {
            type = 4; // rook fallback
        } else if (asym > 0.105f) {
            type = 2; // knight fallback
        } else {
            type = 3; // bishop fallback
        }
        return isWhite ? type : type + 8;
    }

    /**
     * Builds a valid Board from a screen Result when joining a game mid-way.
     * Home squares only keep their starting identity when guessType agrees with their silhouette,
     * preventing castled Rooks/Kings or moved Bishops/Knights from being mislabelled.
     * Crucially, symmetric sliding pieces (Bishop/Rook/Queen) are NEVER turned into Knights.
     */
    public static Board guessBoard(Result res, boolean whiteBottom) {
        Board out = new Board();
        if (res == null || !res.ok) return Board.starting(whiteBottom);
        Board home = Board.starting(whiteBottom);
        boolean hasWK = false, hasBK = false;

        // Pass 1: keep pieces on their original home squares ONLY if colour and silhouette agree
        for (int i = 0; i < 64; i++) {
            int col = res.colorPat[i];
            if (col == 0) continue;
            int hp = home.s[i];
            if (col == 1 && Board.isWhite(hp)) {
                int gt = guessType(res, i, true);
                // Pawns on rank 2 stay pawns if short; back-rank pieces stay hp if gt matches or both are tall crown pieces
                if (hp == 1 && (gt == 1 || res.pieceHeight[i] < 0.685f)) {
                    out.s[i] = 1;
                } else if (hp > 1 && gt == hp) {
                    out.s[i] = (byte) hp;
                    if (hp == 6) hasWK = true;
                } else if (hp == 6 && res.pieceHeight[i] >= 0.72f && res.asym[i] < 0.13f) {
                    out.s[i] = 6;
                    hasWK = true;
                }
            } else if (col == 2 && Board.isBlack(hp)) {
                int gt = guessType(res, i, false) - 8;
                int hpBase = hp - 8;
                if (hpBase == 1 && (gt == 1 || res.pieceHeight[i] < 0.685f)) {
                    out.s[i] = 9;
                } else if (hpBase > 1 && gt == hpBase) {
                    out.s[i] = (byte) hp;
                    if (hp == 14) hasBK = true;
                } else if (hp == 14 && res.pieceHeight[i] >= 0.72f && res.asym[i] < 0.13f) {
                    out.s[i] = 14;
                    hasBK = true;
                }
            }
        }

        // Check common castled king squares (g1/c1 for white, g8/c8 for black) when king has left e1/e8
        if (!hasWK) {
            int g1 = Board.squareFromName("g1", whiteBottom);
            int c1 = Board.squareFromName("c1", whiteBottom);
            if (g1 >= 0 && res.colorPat[g1] == 1 && out.s[g1] == 0 && res.pieceHeight[g1] >= 0.70f && res.asym[g1] < 0.14f) {
                out.s[g1] = 6; hasWK = true;
            } else if (c1 >= 0 && res.colorPat[c1] == 1 && out.s[c1] == 0 && res.pieceHeight[c1] >= 0.70f && res.asym[c1] < 0.14f) {
                out.s[c1] = 6; hasWK = true;
            }
        }
        if (!hasBK) {
            int g8 = Board.squareFromName("g8", whiteBottom);
            int c8 = Board.squareFromName("c8", whiteBottom);
            if (g8 >= 0 && res.colorPat[g8] == 2 && out.s[g8] == 0 && res.pieceHeight[g8] >= 0.70f && res.asym[g8] < 0.14f) {
                out.s[g8] = 14; hasBK = true;
            } else if (c8 >= 0 && res.colorPat[c8] == 2 && out.s[c8] == 0 && res.pieceHeight[c8] >= 0.70f && res.asym[c8] < 0.14f) {
                out.s[c8] = 14; hasBK = true;
            }
        }

        // Pass 2: assign remaining pieces while respecting standard piece-count limits
        // and NEVER converting a symmetric sliding piece (B/R/Q) into a Knight (N) or a tall piece into a Pawn!
        int[] wCnt = new int[7], bCnt = new int[7];
        int wTot = 0, bTot = 0;
        for (int i = 0; i < 64; i++) {
            int p = out.s[i];
            if (p >= 1 && p <= 6) { wCnt[p]++; wTot++; }
            else if (p >= 9 && p <= 14) { bCnt[p - 8]++; bTot++; }
        }
        int[] maxPiece = new int[]{0, 8, 2, 2, 2, 1, 1};

        for (int i = 0; i < 64; i++) {
            int col = res.colorPat[i];
            if (col == 0 || out.s[i] != 0) continue;
            boolean isW = (col == 1);
            if ((isW ? wTot : bTot) >= 16) continue;
            int[] cnt = isW ? wCnt : bCnt;
            int rank = Board.rankIdxOf(i, whiteBottom);
            int g = guessType(res, i, isW);
            int base = isW ? g : g - 8;
            if (base == 6) {
                if (isW && !hasWK) { out.s[i] = 6; hasWK = true; wCnt[6]++; wTot++; continue; }
                if (!isW && !hasBK) { out.s[i] = 14; hasBK = true; bCnt[6]++; bTot++; continue; }
                base = 5;
            }
            if (base == 1 && (rank == 0 || rank == 7)) base = 3; // no pawns on 1st/8th rank
            if (cnt[base] >= maxPiece[base]) {
                // Choose a safe fallback that preserves movement geometry:
                // - Knights (2) only fall back to Bishop (3) or Pawn (1)
                // - Sliding pieces (3, 4, 5) ONLY fall back to other sliding pieces (3, 4, 5), NEVER to Knight (2)!
                int[] fallbackOrder;
                if (base == 1) {
                    fallbackOrder = new int[]{3, 2, 4, 5};
                } else if (base == 2) {
                    fallbackOrder = (rank == 0 || rank == 7) ? new int[]{3, 4, 5} : new int[]{3, 1, 4, 5};
                } else {
                    fallbackOrder = (rank == 0 || rank == 7) ? new int[]{3, 4, 5} : new int[]{3, 4, 5, 1};
                }
                for (int fb : fallbackOrder) {
                    if (cnt[fb] < maxPiece[fb]) { base = fb; break; }
                }
            }
            cnt[base]++;
            if (isW) wTot++; else bTot++;
            out.s[i] = (byte) (isW ? base : base + 8);
        }

        // Ensure exactly one white king and one black king on distinct squares
        int wkIdx = -1, bkIdx = -1;
        for (int i = 0; i < 64; i++) {
            if (out.s[i] == 6) wkIdx = i;
            else if (out.s[i] == 14) bkIdx = i;
        }
        if (wkIdx < 0) {
            int best = -1;
            float bestInk = -1f;
            for (int i = 0; i < 64; i++) {
                if (i != bkIdx && res.colorPat[i] == 1 && res.inkFrac[i] > bestInk) {
                    bestInk = res.inkFrac[i];
                    best = i;
                }
            }
            if (best < 0) {
                int e1 = Board.squareFromName("e1", whiteBottom);
                best = (e1 != bkIdx) ? e1 : Board.squareFromName("g1", whiteBottom);
            }
            wkIdx = best;
            out.s[wkIdx] = 6;
        }
        if (bkIdx < 0 || bkIdx == wkIdx) {
            int best = -1;
            float bestInk = -1f;
            for (int i = 0; i < 64; i++) {
                if (i != wkIdx && res.colorPat[i] == 2 && res.inkFrac[i] > bestInk) {
                    bestInk = res.inkFrac[i];
                    best = i;
                }
            }
            if (best < 0) {
                int e8 = Board.squareFromName("e8", whiteBottom);
                best = (e8 != wkIdx) ? e8 : Board.squareFromName("g8", whiteBottom);
            }
            bkIdx = best;
            out.s[bkIdx] = 14;
        }
        return out;
    }

    /** Otsu threshold over a small array */
    public static float otsu(float[] v) {
        if (v == null || v.length == 0) return 0f;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float x : v) { min = Math.min(min, x); max = Math.max(max, x); }
        if (max - min < 1e-6f) return max + 0.001f;
        int bins = 64;
        int[] hist = new int[bins];
        for (float x : v) {
            int b = (int) ((x - min) / (max - min) * (bins - 1));
            hist[Math.max(0, Math.min(bins - 1, b))]++;
        }
        int total = v.length;
        float sum = 0;
        for (int i = 0; i < bins; i++) sum += (float) i * hist[i];
        float sumB = 0;
        int wB = 0;
        float best = -1;
        int bestI = bins / 2;
        for (int i = 0; i < bins; i++) {
            wB += hist[i];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;
            sumB += (float) i * hist[i];
            float mB = sumB / wB, mF = (sum - sumB) / wF;
            float between = (float) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) { best = between; bestI = i; }
        }
        return min + (bestI + 0.5f) / bins * (max - min);
    }
}
