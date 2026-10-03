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
        public float[][] bands = new float[64][8];
        public float[] score = new float[64];
        public float[] dmax = new float[64];
        public float[] inkLum = new float[64];
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
     * Works on a downscaled copy so a 1080x2340 screen never needs a 10 MB pixel array
     * (that used to run low-RAM phones out of memory while the engine was also running).
     */
    public static Rect detect(Bitmap full) {
        if (full == null) return null;
        int W0 = full.getWidth(), H0 = full.getHeight();
        if (W0 < 64 || H0 < 64) return null;

        // ---- stage 1: coarse search on a small copy
        int tw = Math.min(W0, 480);
        int th = Math.max(64, Math.round(H0 * (tw / (float) W0)));
        Bitmap small = null;
        Rect coarse = null;
        int[] px = null;
        try {
            small = Bitmap.createScaledBitmap(full, tw, th, true);
            px = new int[tw * th];
            small.getPixels(px, 0, tw, 0, 0, tw, th);
            Rect r = detect(px, tw, th);
            if (r != null) {
                float k = W0 / (float) tw;
                coarse = new Rect(Math.round(r.left * k), Math.round(r.top * k),
                        Math.round(r.right * k), Math.round(r.bottom * k));
            }
        } catch (Throwable ignored) {
            coarse = null;
        } finally {
            if (small != null && small != full) small.recycle();
            px = null;
        }
        if (coarse == null) return null;

        // ---- stage 2: precise alignment on a mid resolution crop of the real screenshot
        return refineOnBitmap(full, coarse);
    }

    /** re-aligns a rough board rectangle at a workable resolution (~600 px) */
    private static Rect refineOnBitmap(Bitmap full, Rect rough) {
        int side = rough.width();
        if (side < 64) return rough;
        int pad = Math.max(6, side / 12);
        int cx = Math.max(0, rough.left - pad), cy = Math.max(0, rough.top - pad);
        int cw = Math.min(full.getWidth() - cx, side + 2 * pad);
        int ch = Math.min(full.getHeight() - cy, side + 2 * pad);
        if (cw < 64 || ch < 64) return rough;

        Bitmap crop = null;
        int[] px = null;
        try {
            crop = Bitmap.createBitmap(full, cx, cy, cw, ch);
            int target = 600;
            if (crop.getWidth() > target) {
                Bitmap sc = Bitmap.createScaledBitmap(crop, target, target, true);
                if (sc != crop) {
                    crop.recycle();
                    crop = sc;
                }
            }
            int w = crop.getWidth(), h = crop.getHeight();
            px = new int[w * h];
            crop.getPixels(px, 0, w, 0, 0, w, h);
            float k = w / (float) cw;
            Rect seed = new Rect(Math.round((rough.left - cx) * k), Math.round((rough.top - cy) * k),
                    Math.round((rough.left - cx) * k) + Math.round(side * k),
                    Math.round((rough.top - cy) * k) + Math.round(side * k));
            clampRect(seed, w, h);
            Rect aligned = alignBoard(px, w, h, seed, Math.max(3, seed.width() / 8));
            float back = cw / (float) w;
            Rect out = new Rect(cx + Math.round(aligned.left * back), cy + Math.round(aligned.top * back),
                    cx + Math.round(aligned.right * back), cy + Math.round(aligned.bottom * back));
            if (out.left < 0) out.offset(-out.left, 0);
            if (out.top < 0) out.offset(0, -out.top);
            int s2 = Math.min(out.width(), Math.min(full.getWidth() - out.left, full.getHeight() - out.top));
            return new Rect(out.left, out.top, out.left + s2, out.top + s2);
        } catch (Throwable t) {
            return rough;
        } finally {
            if (crop != null && crop != full) crop.recycle();
            px = null;
        }
    }

    private static void clampRect(Rect r, int w, int h) {
        if (r.left < 0) r.offset(-r.left, 0);
        if (r.top < 0) r.offset(0, -r.top);
        if (r.right > w) r.offset(w - r.right, 0);
        if (r.bottom > h) r.offset(0, h - r.bottom);
    }

    /**
     * Reads a board straight from a screenshot.
     * Crops the board area and works at ~640 px, so the memory footprint stays small.
     */
    public static Result read(Bitmap full, Rect rect) {
        Result res = new Result();
        if (full == null || rect == null || rect.width() < 48) return res;
        Rect c = new Rect(Math.max(0, rect.left), Math.max(0, rect.top),
                Math.min(full.getWidth(), rect.right), Math.min(full.getHeight(), rect.bottom));
        if (c.width() < 48 || c.height() < 48) return res;

        Bitmap crop = null;
        boolean owned = false;
        int[] px = null;
        try {
            crop = Bitmap.createBitmap(full, c.left, c.top, c.width(), c.height());
            owned = crop != full;
            int side = Math.min(crop.getWidth(), crop.getHeight());
            int target = 640;
            if (side > target) {
                float sc = target / (float) side;
                int nw = Math.max(64, Math.round(crop.getWidth() * sc));
                int nh = Math.max(64, Math.round(crop.getHeight() * sc));
                Bitmap scaled = Bitmap.createScaledBitmap(crop, nw, nh, true);
                if (scaled != crop) {
                    if (owned) crop.recycle();
                    crop = scaled;
                    owned = true;
                }
            }
            int w = crop.getWidth(), h = crop.getHeight();
            px = new int[w * h];
            crop.getPixels(px, 0, w, 0, 0, w, h);
            Rect frame = new Rect(0, 0, Math.min(w, h), Math.min(w, h));
            frame = alignBoard(px, w, h, frame, Math.max(2, frame.width() / 26));
            res = read(px, w, h, frame);
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

        float sc = Math.min(1f, 420f / W0);
        int W = Math.max(80, Math.round(W0 * sc));
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
                    for (int k = 0; k <= 8; k++) ls += colS[Math.min(W - 1, x0 + Math.min(W - 1, k * s))];
                    for (int k = 0; k < 8; k++) ms += colS[Math.min(W - 1, x0 + (int) ((k + 0.5f) * s))];
                    ls /= 9f; ms /= 8f;
                    float lr = 0, mr = 0;
                    for (int k = 0; k <= 8; k++) lr += rowS[Math.min(H - 1, y0 + Math.min(H - 1, k * s))];
                    for (int k = 0; k < 8; k++) mr += rowS[Math.min(H - 1, y0 + (int) ((k + 0.5f) * s))];
                    lr /= 9f; mr /= 8f;
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
            top[i] = new Candidate(c.x, c.y, c.s, 0.45f * (c.score / maxCon) + 0.55f * alt, alt);
        }
        java.util.Arrays.sort(top, (a, b) -> Float.compare(b.score, a.score));

        // ---- take the best few up to full resolution and hill climb on the real pixels
        Rect bestRect = null;
        float bestScore = -1;
        int tries = Math.min(10, top.length);
        for (int i = 0; i < tries; i++) {
            Candidate c = top[i];
            int fx = Math.round(c.x / sc), fy = Math.round(c.y / sc), fs = Math.round(8 * c.s / sc);
            if (fs < 64) continue;
            Rect r = new Rect(fx, fy, fx + fs, fy + fs);
            r = alignBoard(pxFull, W0, H0, r);
            float alt = colorAltFull(pxFull, W0, H0, r);
            if (alt < 0.24f) continue;                     // no checkerboard -> not a board
            float sc2 = frameScore(pxFull, W0, H0, r) + 0.15f * (c.score / maxCon);
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
        float sq = side / 8f;
        float[] pair = new float[5];
        float total = 0; int cnt = 0;
        for (int line = 1; line <= 7; line++) {
            int lx = x0 + Math.round(line * sq);
            for (int row = 0; row < 8; row++) {
                for (int k = 0; k < 5; k++) {
                    int yy = clampI(y0 + Math.round(row * sq + sq * (0.16f + 0.17f * k)), 0, H - 1);
                    int xl = clampI(lx - Math.max(2, Math.round(sq * 0.14f)), 0, W - 1);
                    int xr = clampI(lx + Math.max(2, Math.round(sq * 0.14f)), 0, W - 1);
                    pair[k] = colDist(px[yy * W + xl], px[yy * W + xr]);
                }
                total += med(pair, 5); cnt++;
            }
        }
        return cnt > 0 ? total / cnt : 0;
    }

    private static float hLines(int[] px, int W, int H, int x0, int y0, int side) {
        float sq = side / 8f;
        float[] pair = new float[5];
        float total = 0; int cnt = 0;
        for (int line = 1; line <= 7; line++) {
            int ly = y0 + Math.round(line * sq);
            for (int col = 0; col < 8; col++) {
                for (int k = 0; k < 5; k++) {
                    int xx = clampI(x0 + Math.round(col * sq + sq * (0.16f + 0.17f * k)), 0, W - 1);
                    int yu = clampI(ly - Math.max(2, Math.round(sq * 0.14f)), 0, H - 1);
                    int yd = clampI(ly + Math.max(2, Math.round(sq * 0.14f)), 0, H - 1);
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
        int d = Math.max(2, Math.round(sq * 0.16f));
        float[] v = new float[8];
        float tot = 0; int n = 0;
        for (int row = 0; row < 8; row++) {
            int yy = clampI(y0 + Math.round((row + 0.5f) * sq), 0, H - 1);
            v[row] = colDist(px[yy * W + clampI(x0 - d, 0, W - 1)], px[yy * W + clampI(x0 + d, 0, W - 1)]);
        }
        tot += med(v, 8); n++;
        for (int row = 0; row < 8; row++) {
            int yy = clampI(y0 + Math.round((row + 0.5f) * sq), 0, H - 1);
            v[row] = colDist(px[yy * W + clampI(x0 + side - d, 0, W - 1)], px[yy * W + clampI(x0 + side + d, 0, W - 1)]);
        }
        tot += med(v, 8); n++;
        for (int col = 0; col < 8; col++) {
            int xx = clampI(x0 + Math.round((col + 0.5f) * sq), 0, W - 1);
            v[col] = colDist(px[clampI(y0 - d, 0, H - 1) * W + xx], px[clampI(y0 + d, 0, H - 1) * W + xx]);
        }
        tot += med(v, 8); n++;
        for (int col = 0; col < 8; col++) {
            int xx = clampI(x0 + Math.round((col + 0.5f) * sq), 0, W - 1);
            v[col] = colDist(px[clampI(y0 + side - d, 0, H - 1) * W + xx], px[clampI(y0 + side + d, 0, H - 1) * W + xx]);
        }
        tot += med(v, 8); n++;
        return tot / n;
    }

    /** 1D search over one axis using the grid-line contrast */
    private static int[] axisSearch(int[] px, int W, int H, boolean vertical, int x0, int y0, int side, int range) {
        int bestV = vertical ? x0 : y0;
        float bestS = -1;
        int step = Math.max(1, range / 4);
        for (int s = step; s >= 1; s /= 2) {
            int bs = bestV;
            for (int o = -2 * s; o <= 2 * s; o += s) {
                int v = bestV + o;
                int xx = vertical ? v : x0, yy = vertical ? y0 : v;
                if (xx < 0 || yy < 0 || xx + side > W || yy + side > H) continue;
                float sc = (vertical ? vLines(px, W, H, xx, yy, side) : hLines(px, W, H, xx, yy, side))
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
            int[] a = axisSearch(px, W, H, true, x, y, side, range);
            x = a[0]; y = a[1];
            a = axisSearch(px, W, H, false, x, y, side, range);
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
                    float sc = vLines(px, W, H, x, y, ns) + hLines(px, W, H, x, y, ns);
                    if (sc > bestSc) { bestSc = sc; bestS = ns; }
                }
                if (bs == bestS && s == 1) break;
            }
            side = bestS;
        }
        return new Rect(x, y, x + side, y + side);
    }

    /** final quality of a frame: line contrast + board edge + colour alternation */
    public static float frameScore(int[] px, int W, int H, Rect r) {
        if (r == null || r.left < 0 || r.top < 0 || r.right > W || r.bottom > H || r.width() < 64) return 0;
        float lines = (vLines(px, W, H, r.left, r.top, r.width()) + hLines(px, W, H, r.left, r.top, r.width())) / 2f;
        float per = perimeter(px, W, H, r.left, r.top, r.width());
        float alt = colorAltFull(px, W, H, r);
        return 0.46f * lines * 3.2f + 0.24f * per + 0.30f * alt;
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

    private static float colorAltFull(int[] px, int W, int H, Rect r) {
        int side = r.width();
        if (side < 64 || r.left < 0 || r.top < 0 || r.right > W || r.bottom > H) return 0;
        float[][] m = new float[64][3];
        for (int rr = 0; rr < 8; rr++)
            for (int ff = 0; ff < 8; ff++) {
                int x0 = r.left + Math.round(ff * side / 8f), y0 = r.top + Math.round(rr * side / 8f);
                int sq = side / 8;
                int step = Math.max(1, sq / 8);
                long sr = 0, sg = 0, sb = 0; int cnt = 0;
                for (int y = y0 + sq / 4; y < y0 + sq * 3 / 4; y += step)
                    for (int x = x0 + sq / 4; x < x0 + sq * 3 / 4; x += step) {
                        if (x < 0 || y < 0 || x >= W || y >= H) continue;
                        int c = px[y * W + x];
                        sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; cnt++;
                    }
                cnt = Math.max(1, cnt);
                m[rr * 8 + ff][0] = sr / cnt; m[rr * 8 + ff][1] = sg / cnt; m[rr * 8 + ff][2] = sb / cnt;
            }
        float nb = 0; int nbc = 0; float dg = 0; int dgc = 0;
        for (int rr = 0; rr < 8; rr++)
            for (int ff = 0; ff < 8; ff++) {
                float[] a = m[rr * 8 + ff];
                if (ff < 7) { nb += dist(a, m[rr * 8 + ff + 1]); nbc++; }
                if (rr < 7) { nb += dist(a, m[(rr + 1) * 8 + ff]); nbc++; }
                if (rr < 7 && ff < 7) {
                    dg += dist(a, m[(rr + 1) * 8 + ff + 1]); dgc++;
                    dg += dist(m[rr * 8 + ff + 1], m[(rr + 1) * 8 + ff]); dgc++;
                }
            }
        return ratio(nb / Math.max(1, nbc), dg / Math.max(1, dgc));
    }

    private static float dist(float[] a, float[] b) {
        return (Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2])) / 765f;
    }

    private static float ratio(float nb, float dg) {
        float v = (nb - dg) / (nb + dg + 0.004f);
        if (v < 0) v = 0;
        return Math.min(1f, v * 2.2f);
    }

    /** colour alternation of a candidate grid on the downscaled image */
    private static float colorAlt(int[] px, int W, int H, int s, int x0, int y0) {
        float[][] m = new float[64][3];
        int step = Math.max(1, s / 8);
        for (int r = 0; r < 8; r++)
            for (int f = 0; f < 8; f++) {
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
                m[r * 8 + f][0] = sr / cnt; m[r * 8 + f][1] = sg / cnt; m[r * 8 + f][2] = sb / cnt;
            }
        float nb = 0; int nbc = 0; float dg = 0; int dgc = 0;
        for (int r = 0; r < 8; r++)
            for (int f = 0; f < 8; f++) {
                float[] a = m[r * 8 + f];
                if (f < 7) { nb += dist(a, m[r * 8 + f + 1]); nbc++; }
                if (r < 7) { nb += dist(a, m[(r + 1) * 8 + f]); nbc++; }
                if (r < 7 && f < 7) {
                    dg += dist(a, m[(r + 1) * 8 + f + 1]); dgc++;
                    dg += dist(m[r * 8 + f + 1], m[(r + 1) * 8 + f]); dgc++;
                }
            }
        return ratio(nb / Math.max(1, nbc), dg / Math.max(1, dgc));
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
        int[] parity = new int[64];

        for (int r = 0; r < 8; r++)
            for (int f = 0; f < 8; f++) {
                int idx = r * 8 + f;
                parity[idx] = (r + f) & 1;
                int x0 = Math.round(f * sqw), y0 = Math.round(r * sqh);
                int x1 = Math.round((f + 1) * sqw), y1 = Math.round((r + 1) * sqh);
                int wS = Math.max(1, x1 - x0), hS = Math.max(1, y1 - y0);

                // --- the square's own background = mean of four corner patches
                int pw = Math.max(2, Math.round(wS * 0.12f));
                int in = Math.max(1, Math.round(wS * 0.06f));
                long cr = 0, cg = 0, cb = 0;
                int cn = 0;
                for (int cy = 0; cy < 2; cy++)
                    for (int cx = 0; cx < 2; cx++) {
                        int bx = cx == 0 ? x0 + in : x1 - in - pw;
                        int by = cy == 0 ? y0 + in : y1 - in - pw;
                        for (int y = by; y < by + pw; y++)
                            for (int x = bx; x < bx + pw; x++) {
                                if (x < 0 || y < 0 || x >= bw || y >= bh) continue;
                                int c = px[(rect.top + y) * w + rect.left + x];
                                cr += (c >> 16) & 255; cg += (c >> 8) & 255; cb += c & 255;
                                cn++;
                            }
                    }
                cn = Math.max(1, cn);
                int corR = (int) (cr / cn), corG = (int) (cg / cn), corB = (int) (cb / cn);
                cornerRgb[idx] = (corR << 16) | (corG << 8) | corB;

                // --- middle region: the piece body
                int mx0 = x0 + Math.round(wS * 0.20f), mx1 = x1 - Math.round(wS * 0.20f);
                int my0 = y0 + Math.round(hS * 0.20f), my1 = y1 - Math.round(hS * 0.20f);
                int sx0 = Math.max(0, mx0), sy0 = Math.max(0, my0);
                int sx1 = Math.min(bw, mx1), sy1 = Math.min(bh, my1);
                int bwI = sx1 - sx0, bhI = sy1 - sy0;
                if (bwI < 3 || bhI < 3) { bwI = Math.max(1, bwI); bhI = Math.max(1, bhI); }
                boolean[] mask = new boolean[Math.max(1, bwI * bhI)];
                float[] lumI = new float[Math.max(1, bwI * bhI)];
                int[] bandCnt = new int[8], bandTot = new int[8];
                int tot = 0, ink = 0;
                float dmax = 0;
                long sr = 0, sg = 0, sb = 0, suml = 0, suml2 = 0;
                int mid = 0;
                for (int y = sy0; y < sy1; y++) {
                    int band = Math.min(7, Math.max(0, (int) ((y - y0) / (float) hS * 8)));
                    for (int x = sx0; x < sx1; x++) {
                        int c = px[(rect.top + y) * w + rect.left + x];
                        int rr = (c >> 16) & 255, gg = (c >> 8) & 255, bb = c & 255;
                        float d = (Math.abs(rr - corR) + Math.abs(gg - corG) + Math.abs(bb - corB)) / 765f;
                        float l = (0.299f * rr + 0.587f * gg + 0.114f * bb) / 255f;
                        int bi = (y - sy0) * bwI + (x - sx0);
                        if (bi < lumI.length) {
                            lumI[bi] = l;
                            if (d > 0.058f) { mask[bi] = true; ink++; }
                        }
                        if (d > dmax) dmax = d;
                        bandTot[band]++;
                        tot++;
                        sr += rr; sg += gg; sb += bb; suml += l; suml2 += l * l; mid++;
                    }
                }
                if (mid == 0) mid = 1;
                int cR = (int) (sr / mid), cG = (int) (sg / mid), cB = (int) (sb / mid);
                centreRgb[idx] = (cR << 16) | (cG << 8) | cB;
                float meanL = (float) (suml / mid);
                centreLum[idx] = meanL;
                float std = (float) Math.sqrt(Math.max(0, suml2 / mid - meanL * meanL));
                f2[idx] = std;

                // erode the ink mask several pixels deep: the outline ring, thin markers and
                // dots disappear, only the piece BODY stays -> that gives the piece colour.
                int er = Math.max(2, Math.min(4, Math.round(sqw * 0.030f)));
                boolean[] cur = mask;
                boolean[] tmp = new boolean[mask.length];
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
                for (int i = 0; i < cur.length; i++)
                    if (cur[i]) { ero++; bodyAcc += lumI[i]; }
                float inkF = tot > 0 ? ink / (float) tot : 0f;
                float erodeF = tot > 0 ? ero / (float) tot : 0f;
                inkFrac[idx] = Math.max(inkF, erodeF * 2.2f);                  // for the type guess
                f3[idx] = 0.5f * inkF + 0.5f * Math.min(1f, erodeF * 2.5f);    // occupancy feature
                dmaxArr[idx] = dmax;

                float bodyLum = meanL;
                if (ero >= 3) bodyLum = (float) (bodyAcc / ero);
                else if (ink > 3) {
                    // no interior survived - fall back to the mean of all ink pixels
                    double a = 0; int n = 0;
                    for (int k = 0; k < mask.length; k++) if (mask[k]) { a += lumI[k]; n++; }
                    if (n > 0) bodyLum = (float) (a / n);
                }
                inkLum[idx] = bodyLum;
                for (int y = sy0; y < sy1; y++) {
                    int band = Math.min(7, Math.max(0, (int) ((y - y0) / (float) hS * 8)));
                    for (int x = sx0; x < sx1; x++) {
                        int c = px[(rect.top + y) * w + rect.left + x];
                        int rr = (c >> 16) & 255, gg = (c >> 8) & 255, bb = c & 255;
                        float d = (Math.abs(rr - corR) + Math.abs(gg - corG) + Math.abs(bb - corB)) / 765f;
                        if (d > 0.058f) bandCnt[band]++;
                    }
                }
                for (int b = 0; b < 8; b++) bands[idx][b] = bandTot[b] > 0 ? bandCnt[b] / (float) bandTot[b] : 0f;

                f2[idx] = std;

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

        float thr = otsu(score);
        int occ = 0;
        for (int i = 0; i < 64; i++) if (score[i] > thr) occ++;
        if (occ > 32) {                       // a chess board never has more than 32 pieces
            float[] tmp = score.clone();
            java.util.Arrays.sort(tmp);
            thr = tmp[64 - 32];
            occ = 0;
            for (int i = 0; i < 64; i++) if (score[i] > thr) occ++;
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

        for (int i = 0; i < 64; i++) {
            // a piece covers a real part of the square AND contains a strongly contrasting pixel;
            // that is what separates a piece from a soft highlight or a move dot.
            boolean occupied = score[i] > thr && dmaxArr[i] > 0.26f;
            res.colorPat[i] = occupied ? (inkLum[i] > lmid ? 1 : 2) : 0;
            res.conf[i] = Math.abs(score[i] - thr) / Math.max(1e-4f, Math.max(thr, 0.25f));
            res.centerRgb[i] = centreRgb[i];
            res.inkLum[i] = inkLum[i];
            res.inkFrac[i] = inkFrac[i];
            res.bands[i] = bands[i];
            res.score[i] = score[i];
            res.dmax[i] = dmaxArr[i];
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
            float[] v = new float[occ];
            for (int i = 0; i < 64; i++) if (res.colorPat[i] != 0) { ids.add(i); }
            for (int k = 0; k < ids.size(); k++) v[k] = inkLum[ids.get(k)];
            float t2 = otsu(v);
            for (int i : ids) res.colorPat[i] = inkLum[i] > t2 ? 1 : 2;
        }
        for (int i = 0; i < 64; i++) if (res.colorPat[i] == 0) res.conf[i] = Math.max(res.conf[i], 0.01f);

        res.ok = true;
        res.note = "occ=" + occ;
        return res;
    }

    // =====================================================================
    //  3) rough piece type guess (used to seed the editor / mid-game start)
    // =====================================================================

    public static int guessType(Result res, int idx, boolean isWhite) {
        float ink = res.inkFrac[idx];
        float[] b = res.bands[idx];
        float top = (b[0] + b[1]) / 2f, bot = (b[6] + b[7]) / 2f, midb = (b[3] + b[4]) / 2f;
        float type;
        if (ink < 0.20f) type = 1;                        // pawn
        else if (ink < 0.32f) {
            if (top < bot * 0.72f) type = 3;              // bishop
            else if (midb > top * 1.15f) type = 2;        // knight
            else type = 3;
        } else if (ink < 0.44f) {
            if (top > bot * 0.85f) type = 4;              // rook
            else type = 2;
        } else {
            type = (top > 0.25f && b[0] < 0.18f) ? 6 : 5; // king : queen
        }
        return isWhite ? (int) type : (int) type + 8;
    }

    /**
     * Builds a valid Board from a screen Result when joining a game mid-way.
     * Pieces still on their starting squares keep their starting identity; moved pieces are
     * inferred from shape features while guaranteeing exactly one white king and one black king.
     */
    public static Board guessBoard(Result res, boolean whiteBottom) {
        Board out = new Board();
        if (res == null || !res.ok) return Board.starting(whiteBottom);
        Board home = Board.starting(whiteBottom);
        boolean hasWK = false, hasBK = false;

        // Pass 1: keep pieces on their original home squares if the colour matches
        for (int i = 0; i < 64; i++) {
            int col = res.colorPat[i];
            if (col == 0) continue;
            int hp = home.s[i];
            if (col == 1 && Board.isWhite(hp)) {
                out.s[i] = (byte) hp;
                if (hp == 6) hasWK = true;
            } else if (col == 2 && Board.isBlack(hp)) {
                out.s[i] = (byte) hp;
                if (hp == 14) hasBK = true;
            }
        }

        // Check common castled king squares (g1/c1 for white, g8/c8 for black)
        if (!hasWK) {
            int g1 = Board.squareFromName("g1", whiteBottom);
            int c1 = Board.squareFromName("c1", whiteBottom);
            if (g1 >= 0 && res.colorPat[g1] == 1) { out.s[g1] = 6; hasWK = true; }
            else if (c1 >= 0 && res.colorPat[c1] == 1) { out.s[c1] = 6; hasWK = true; }
        }
        if (!hasBK) {
            int g8 = Board.squareFromName("g8", whiteBottom);
            int c8 = Board.squareFromName("c8", whiteBottom);
            if (g8 >= 0 && res.colorPat[g8] == 2) { out.s[g8] = 14; hasBK = true; }
            else if (c8 >= 0 && res.colorPat[c8] == 2) { out.s[c8] = 14; hasBK = true; }
        }

        // Pass 2: assign moved pieces
        for (int i = 0; i < 64; i++) {
            int col = res.colorPat[i];
            if (col == 0 || out.s[i] != 0) continue;
            boolean isW = (col == 1);
            int rank = Board.rankIdxOf(i, whiteBottom);
            int g = guessType(res, i, isW);
            int base = isW ? g : g - 8;
            if (base == 6) base = 5; // kings handled separately
            if (base == 1 && (rank == 0 || rank == 7)) base = 3; // no pawns on 1st/8th rank
            out.s[i] = (byte) (isW ? base : base + 8);
        }

        // Ensure exactly one white king and one black king
        if (!hasWK) {
            int best = -1;
            float bestInk = -1f;
            for (int i = 0; i < 64; i++) {
                if (res.colorPat[i] == 1 && res.inkFrac[i] > bestInk) {
                    bestInk = res.inkFrac[i];
                    best = i;
                }
            }
            if (best >= 0) out.s[best] = 6;
            else out.s[Board.squareFromName("e1", whiteBottom)] = 6;
        }
        if (!hasBK) {
            int best = -1;
            float bestInk = -1f;
            for (int i = 0; i < 64; i++) {
                if (res.colorPat[i] == 2 && res.inkFrac[i] > bestInk) {
                    bestInk = res.inkFrac[i];
                    best = i;
                }
            }
            if (best >= 0) out.s[best] = 14;
            else out.s[Board.squareFromName("e8", whiteBottom)] = 14;
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
