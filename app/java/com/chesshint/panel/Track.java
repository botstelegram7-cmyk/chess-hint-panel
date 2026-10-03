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
        if (Arrays.equals(cur, observed)) {
            u.ok = true;
            return u;
        }
        u.changed = true;

        // First try exact observation (0 flipped squares) from current side to move
        if (tryTarget(u, cur, observed, observed, 0, false)) return u;

        // Next try exact observation (0 flipped squares) with flipped side to move
        // (handles the case where the opponent moved while pos.side was out of sync)
        if (tryTarget(u, cur, observed, observed, 0, true)) return u;

        // ---- build a list of plausible 1-square corrected readings for genuinely borderline squares
        Integer[] order = new Integer[64];
        for (int i = 0; i < 64; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(conf[a], conf[b]));   // least certain first

        for (int k = 0; k < 6 && k < 64; k++) {
            int i = order[k];
            if (conf[i] >= 0.28f) break;
            for (int v = 0; v < 3; v++) {
                if (v == observed[i]) continue;
                int[] target = set(observed, i, v);
                if (Arrays.equals(cur, target)) {
                    u.ok = true;
                    u.changed = false;
                    u.fixedSquares = 1;
                    return u;
                }
                if (tryTarget(u, cur, observed, target, 1, false)) return u;
                if (tryTarget(u, cur, observed, target, 1, true)) return u;
            }
        }
        u.ok = false;
        return u;
    }

    private boolean tryTarget(Update u, int[] cur, int[] observed, int[] target, int fixed, boolean flipSide) {
        Chess.Pos base = pos.copy();
        if (flipSide) {
            base.side = 3 - base.side;
            base.epSq = -1;
            Chess.sanitize(base);
        }
        int diff = countDiff(cur, target);
        if (diff == 0) return false;
        List<List<Chess.Move>> found = searchFrom(base, target, 1);
        if (fixed == 0 && found.isEmpty()) found = searchFrom(base, target, 2);
        if (fixed == 0 && found.isEmpty() && diff >= 3) found = searchFrom(base, target, 3);
        if (fixed == 0 && found.isEmpty() && diff >= 4 && !flipSide) found = searchFrom(base, target, 4);
        if (found.isEmpty()) return false;

        List<Chess.Move> seq = found.get(0);
        pos = base;
        for (Chess.Move m : seq) { Chess.make(pos, m); history.add(m); }
        Chess.sanitize(pos);
        lastPattern = target;
        u.ok = true;
        u.moves = seq;
        u.ambiguous = found.size() > 1;
        u.fixedSquares = fixed;
        StringBuilder sb = new StringBuilder();
        for (Chess.Move m : seq) sb.append(m.uci()).append(' ');
        u.info = sb.toString().trim();
        lastInfo = u.info;
        return true;
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
    private List<List<Chess.Move>> searchFrom(Chess.Pos startPos, int[] target, int depth) {
        List<List<Chess.Move>> out = new ArrayList<>();
        int[] budget = new int[]{200000};
        rec(startPos.copy(), target, depth, new ArrayList<Chess.Move>(), out, budget);
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
