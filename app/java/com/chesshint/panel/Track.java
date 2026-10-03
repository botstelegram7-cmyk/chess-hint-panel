package com.chesshint.panel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Keeps the position that is being watched in sync with what the reader sees.
 *
 * The reader can only see "empty / white piece / black piece" - the piece TYPES come
 * from this tracker: we start from a known position and after every screen change we
 * search for the short sequence of legal moves that produces exactly the observed
 * picture.  A move is only accepted when it is legal in the current position, so the
 * knights, bishops, rooks, queens and kings stay perfectly correct.
 *
 * Small misreads are tolerated: if the raw observation cannot be explained, the tracker
 * tries flipping the least certain squares (up to 3) and accepts the reading that has an
 * explanation.
 */
public class Track {

    public Chess.Pos pos;
    public boolean whiteBottom;
    public int[] lastPattern = new int[64];
    public String lastInfo = "";
    public List<Chess.Move> history = new ArrayList<>();

    public Track(Chess.Pos pos, boolean whiteBottom) {
        this.pos = pos.copy();
        this.whiteBottom = whiteBottom;
        this.lastPattern = Chess.pattern(pos, whiteBottom);
    }

    public void reset(Chess.Pos p) {
        pos = p.copy();
        history.clear();
        lastPattern = Chess.pattern(pos, whiteBottom);
        lastInfo = "";
    }

    public void setWhiteBottom(boolean wb) {
        whiteBottom = wb;
        lastPattern = Chess.pattern(pos, whiteBottom);
    }

    public static class Update {
        public boolean changed;      // the picture on screen differs from the tracked position
        public boolean ok;           // we found an explanation
        public boolean ambiguous;    // more than one explanation existed
        public List<Chess.Move> moves = new ArrayList<>();
        public int fixedSquares;     // how many uncertain squares had to be flipped
        public String info = "";
    }

    /** feed one observation (colour pattern in screen order) */
    public Update observe(int[] observed, float[] conf) {
        Update u = new Update();
        int[] cur = Chess.pattern(pos, whiteBottom);
        if (Arrays.equals(cur, observed)) return u;
        u.changed = true;

        // ---- build a list of plausible corrected readings (the reader is never perfect)
        List<int[]> variants = new ArrayList<>();
        variants.add(observed);

        Integer[] order = new Integer[64];
        for (int i = 0; i < 64; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(conf[a], conf[b]));   // least certain first

        List<Integer> weak = new ArrayList<>();
        for (int k = 0; k < 10 && k < 64; k++) {
            int i = order[k];
            if (conf[i] < 0.50f) weak.add(i);
        }
        // one square corrected (white / empty / black - whichever was not read)
        for (int i : weak)
            for (int v = 0; v < 3; v++)
                if (v != observed[i]) variants.add(set(observed, i, v));

        // two squares corrected at once (only the most doubtful ones)
        int lim = Math.min(5, weak.size());
        for (int a = 0; a < lim; a++)
            for (int b = a + 1; b < lim; b++) {
                int ia = weak.get(a), ib = weak.get(b);
                for (int va = 0; va < 3; va++) {
                    if (va == observed[ia]) continue;
                    for (int vb = 0; vb < 3; vb++) {
                        if (vb == observed[ib]) continue;
                        variants.add(set(set(observed, ia, va), ib, vb));
                    }
                }
            }

        for (int v = 0; v < variants.size(); v++) {
            int[] target = variants.get(v);
            List<List<Chess.Move>> found = search(target, 1);
            int depth = 1;
            if (found.isEmpty()) { found = search(target, 2); depth = 2; }
            int diff = 0;
            for (int i = 0; i < 64; i++) if (cur[i] != target[i]) diff++;
            if (found.isEmpty() && diff >= 4) { found = search(target, 3); depth = 3; }
            if (found.isEmpty() && diff >= 7) { found = search(target, 4); depth = 4; }
            if (found.isEmpty()) continue;

            List<Chess.Move> seq = found.get(0);
            for (Chess.Move m : seq) { Chess.make(pos, m); history.add(m); }
            lastPattern = target;
            u.ok = true;
            u.moves = seq;
            u.ambiguous = found.size() > 1;
            u.fixedSquares = v == 0 ? 0 : countDiff(observed, target);
            StringBuilder sb = new StringBuilder();
            for (Chess.Move m : seq) sb.append(m.uci()).append(' ');
            u.info = sb.toString().trim();
            lastInfo = u.info;
            return u;
        }
        u.ok = false;
        return u;
    }

    private static int countDiff(int[] a, int[] b) {
        int d = 0;
        for (int i = 0; i < 64; i++) if (a[i] != b[i]) d++;
        return d;
    }

    private static int[] set(int[] a, int idx, int value) {
        int[] b = a.clone();
        b[idx] = value;
        return b;
    }

    /**
     * Looks for the shortest sequence of legal moves that turns the current position into
     * exactly the observed picture.  Only moves that touch a square which still differs are
     * tried, which keeps the search tiny even 3-4 plies deep.
     */
    private List<List<Chess.Move>> search(int[] target, int depth) {
        List<List<Chess.Move>> out = new ArrayList<>();
        int[] budget = new int[]{200000};
        rec(pos.copy(), target, depth, new ArrayList<Chess.Move>(), out, budget);
        return out;
    }

    private int chessIdx(int screenIdx) {
        int f = Board.fileIdxOf(screenIdx, whiteBottom), r = Board.rankIdxOf(screenIdx, whiteBottom);
        return (7 - r) * 8 + f;
    }

    private void rec(Chess.Pos p, int[] target, int depth, List<Chess.Move> acc,
                     List<List<Chess.Move>> out, int[] budget) {
        if (out.size() >= 8 || budget[0] <= 0) return;
        int[] cur = Chess.pattern(p, whiteBottom);
        boolean[] need = new boolean[64];
        int diff = 0;
        for (int i = 0; i < 64; i++)
            if (cur[i] != target[i]) { diff++; need[chessIdx(i)] = true; }
        if (diff == 0) {
            out.add(new ArrayList<>(acc));
            return;
        }
        if (depth == 0) return;
        if (diff > 4 * depth) return;          // impossible: one move changes at most 4 squares
        for (Chess.Move m : Chess.legal(p)) {
            if (!need[m.from] && !need[m.to]) continue;
            budget[0]--;
            if (budget[0] <= 0) return;
            Chess.Pos q = p.copy();
            Chess.make(q, m);
            acc.add(m);
            rec(q, target, depth - 1, acc, out, budget);
            acc.remove(acc.size() - 1);
            if (out.size() >= 8) return;
        }
    }
}
