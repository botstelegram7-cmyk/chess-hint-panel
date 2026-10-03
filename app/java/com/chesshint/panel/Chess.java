package com.chesshint.panel;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, self contained chess move generator.
 *
 * It is used to follow the game that is being watched on screen:
 * the app starts from a known position (standard start, a FEN, or a position the
 * user fixed by hand) and after every screen change looks for the legal move(s)
 * that produce exactly what was seen.  That way pawns, knights, bishops, rooks,
 * queens and kings stay 100% correct even though the screen reader itself can
 * only see "empty / white piece / black piece".
 *
 * Board array here is in normal chess order: index 0 = a8 ... 63 = h1.
 */
public class Chess {

    public static final int WHITE = 1, BLACK = 2;

    public static class Move {
        public int from, to;
        public int promo;          // 0 or piece code 2..5 (knight..queen)
        public boolean ep, castle;

        public Move(int from, int to) { this.from = from; this.to = to; }

        /** uci string (in chess coordinates) */
        public String uci() {
            return sq(from) + sq(to) + (promo > 0 ? String.valueOf(" PNBRQK".charAt(promo)).toLowerCase() : "");
        }

        @Override public String toString() { return uci(); }
    }

    public static String sq(int i) {
        return "" + (char) ('a' + (i % 8)) + (char) ('1' + (7 - i / 8));
    }

    public static int sqFromName(String s) {
        if (s == null || s.length() < 2) return -1;
        int f = s.charAt(0) - 'a', r = s.charAt(1) - '1';
        if (f < 0 || f > 7 || r < 0 || r > 7) return -1;
        return (7 - r) * 8 + f;
    }

    /** position = board + side to move + en passant + castling rights */
    public static class Pos {
        public int[] cb = new int[64];
        public int side = WHITE;
        public int epSq = -1;
        public String castling = "-";

        public Pos copy() {
            Pos p = new Pos();
            System.arraycopy(cb, 0, p.cb, 0, 64);
            p.side = side; p.epSq = epSq; p.castling = castling;
            return p;
        }

        public String fen() {
            Pos clean = this.copy();
            sanitize(clean);
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < 8; r++) {
                int e = 0;
                for (int f = 0; f < 8; f++) {
                    int p = clean.cb[r * 8 + f];
                    if (p == 0) e++;
                    else {
                        if (e > 0) { sb.append(e); e = 0; }
                        sb.append(Board.pieceChar(p));
                    }
                }
                if (e > 0) sb.append(e);
                if (r < 7) sb.append('/');
            }
            sb.append(clean.side == WHITE ? " w " : " b ");
            sb.append(clean.castling == null || clean.castling.isEmpty() ? "-" : clean.castling);
            sb.append(' ').append(clean.epSq >= 0 ? sq(clean.epSq) : "-");
            sb.append(" 0 1");
            return sb.toString();
        }

        public int king(boolean white) {
            int k = white ? 6 : 14;
            for (int i = 0; i < 64; i++) if (cb[i] == k) return i;
            return -1;
        }
    }

    // ------------------------------------------------------------------ conversion

    public static Pos fromBoard(Board b, boolean whiteBottom, int side) {
        Pos p = new Pos();
        for (int i = 0; i < 64; i++) {
            int f = Board.fileIdxOf(i, whiteBottom), r = Board.rankIdxOf(i, whiteBottom);
            p.cb[(7 - r) * 8 + f] = b.s[i];
        }
        p.side = (side == BLACK) ? BLACK : WHITE;
        p.castling = deriveCastling(p.cb);
        sanitize(p);
        return p;
    }

    /**
     * Ensures that a Pos satisfies all chess & Stockfish C++ invariants while preserving
     * square colour occupancy whenever possible:
     *  - No pawns on rank 1 or rank 8
     *  - Exactly 1 White King and 1 Black King on non-adjacent squares
     *  - The side NOT to move is never in check (prevents Stockfish native SIGSEGV on king capture)
     *  - At most 1 checker on the side to move's king
     */
    public static void sanitize(Pos p) {
        if (p == null || p.cb == null) return;
        int[] cb = p.cb;
        if (p.side != WHITE && p.side != BLACK) p.side = WHITE;

        // 1) No pawns on rank 8 (0..7) or rank 1 (56..63)
        for (int i = 0; i < 8; i++) {
            if (cb[i] == 1) cb[i] = 3;
            else if (cb[i] == 9) cb[i] = 11;
        }
        for (int i = 56; i < 64; i++) {
            if (cb[i] == 1) cb[i] = 3;
            else if (cb[i] == 9) cb[i] = 11;
        }

        // 2) Exactly one White King (6) and one Black King (14)
        int wk = -1, bk = -1;
        int[] prefW = {60, 62, 58, 61, 59, 63, 56};
        for (int sq : prefW) if (cb[sq] == 6) { wk = sq; break; }
        for (int i = 0; i < 64; i++) {
            if (cb[i] == 6) {
                if (wk < 0) wk = i;
                else if (i != wk) cb[i] = 5;
            }
        }
        int[] prefB = {4, 6, 2, 5, 3, 7, 0};
        for (int sq : prefB) if (cb[sq] == 14) { bk = sq; break; }
        for (int i = 0; i < 64; i++) {
            if (cb[i] == 14) {
                if (bk < 0) bk = i;
                else if (i != bk) cb[i] = 13;
            }
        }

        if (wk < 0) {
            for (int sq : prefW) if (sq != bk && Board.isWhite(cb[sq])) { wk = sq; cb[sq] = 6; break; }
            if (wk < 0) for (int i = 63; i >= 0; i--) if (i != bk && Board.isWhite(cb[i])) { wk = i; cb[i] = 6; break; }
            if (wk < 0) {
                int sq = (bk != 60) ? 60 : 62;
                wk = sq; cb[sq] = 6;
            }
        }
        if (bk < 0 || bk == wk) {
            for (int sq : prefB) if (sq != wk && !kingStep(sq, wk) && Board.isBlack(cb[sq])) { bk = sq; cb[sq] = 14; break; }
            if (bk < 0 || bk == wk) {
                for (int i = 0; i < 64; i++) if (i != wk && !kingStep(i, wk) && Board.isBlack(cb[i])) { bk = i; cb[i] = 14; break; }
            }
            if (bk < 0 || bk == wk) {
                for (int i = 0; i < 64; i++) if (i != wk && !kingStep(i, wk) && cb[i] == 0) { bk = i; cb[i] = 14; break; }
            }
            if (bk < 0 || bk == wk) {
                int sq = (wk != 4 && !kingStep(4, wk)) ? 4 : 0;
                bk = sq; cb[sq] = 14;
            }
        }

        // 3) Kings must never be adjacent
        if (kingStep(wk, bk)) {
            for (int i = 0; i < 64; i++) {
                if (i != wk && !kingStep(i, wk) && Board.isBlack(cb[i])) {
                    cb[bk] = cb[i];
                    cb[i] = 14;
                    bk = i;
                    break;
                }
            }
            if (kingStep(wk, bk)) {
                for (int i = 0; i < 64; i++) {
                    if (i != wk && !kingStep(i, wk) && cb[i] == 0) {
                        cb[bk] = 0;
                        cb[i] = 14;
                        bk = i;
                        break;
                    }
                }
            }
        }

        // 4) Side NOT to move must NEVER be in check
        int oppSide = 3 - p.side;
        int oppKing = (oppSide == WHITE) ? wk : bk;
        if (oppKing >= 0 && attacked(cb, oppKing, p.side)) {
            int offset = (p.side == WHITE) ? 0 : 8;
            for (int i = 0; i < 64 && attacked(cb, oppKing, p.side); i++) {
                int pc = cb[i];
                if (pc == 0 || pc == 6 || pc == 14) continue;
                if (Board.isWhite(pc) != (p.side == WHITE)) continue;
                cb[i] = 0;
                boolean stillAttacked = attacked(cb, oppKing, p.side);
                cb[i] = pc;
                if (!stillAttacked) {
                    int[] altTypes = (i >= 8 && i < 56) ? new int[]{1, 2, 3, 4} : new int[]{2, 3, 4};
                    for (int t : altTypes) {
                        cb[i] = offset + t;
                        if (!attacked(cb, oppKing, p.side)) break;
                    }
                } else {
                    // One of several attackers: replace this piece with a non-attacking type
                    int[] altTypes = (i >= 8 && i < 56) ? new int[]{1, 2, 3, 4} : new int[]{2, 3, 4};
                    for (int t : altTypes) {
                        int cand = offset + t;
                        if (!pieceAttacksSquare(cb, i, cand, oppKing)) {
                            cb[i] = cand;
                            break;
                        }
                    }
                }
            }
        }

        // 5) Side to move's King should have at most 1 checker (avoids impossible triple checks)
        int myKing = (p.side == WHITE) ? wk : bk;
        if (myKing >= 0 && attacked(cb, myKing, oppSide)) {
            int offset = (oppSide == WHITE) ? 0 : 8;
            int checkers = 0;
            for (int i = 0; i < 64; i++) {
                int pc = cb[i];
                if (pc == 0 || pc == 6 || pc == 14) continue;
                if (Board.isWhite(pc) != (oppSide == WHITE)) continue;
                if (pieceAttacksSquare(cb, i, pc, myKing)) {
                    checkers++;
                    if (checkers > 1) {
                        int[] altTypes = (i >= 8 && i < 56) ? new int[]{1, 2, 3, 4} : new int[]{2, 3, 4};
                        for (int t : altTypes) {
                            int cand = offset + t;
                            if (!pieceAttacksSquare(cb, i, cand, myKing)) {
                                cb[i] = cand;
                                break;
                            }
                        }
                    }
                }
            }
        }

        p.castling = deriveCastling(cb);
        if (p.epSq >= 0) {
            int r = p.epSq / 8;
            if ((p.side == WHITE && r != 2) || (p.side == BLACK && r != 5) || cb[p.epSq] != 0) {
                p.epSq = -1;
            }
        }
    }

    private static boolean pieceAttacksSquare(int[] cb, int from, int pc, int targetSq) {
        int save = cb[from];
        int[] tmp = new int[64];
        tmp[from] = pc;
        for (int i = 0; i < 64; i++) if (i != from && cb[i] != 0) tmp[i] = (i == targetSq) ? 0 : 1;
        tmp[from] = pc;
        boolean res = attacked(tmp, targetSq, Board.isWhite(pc) ? WHITE : BLACK);
        cb[from] = save;
        return res;
    }

    public static Board toBoard(Pos p, boolean whiteBottom) {
        Board b = new Board();
        for (int ci = 0; ci < 64; ci++) {
            int f = ci % 8, r = 7 - ci / 8;
            b.s[Board.indexOf(f, r, whiteBottom)] = (byte) p.cb[ci];
        }
        return b;
    }

    public static String deriveCastling(int[] cb) {
        StringBuilder sb = new StringBuilder();
        if (cb[60] == 6) {
            if (cb[63] == 4) sb.append('K');
            if (cb[56] == 4) sb.append('Q');
        }
        if (cb[4] == 14) {
            if (cb[7] == 12) sb.append('k');
            if (cb[0] == 12) sb.append('q');
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    // ------------------------------------------------------------------ generation

    private static final int[] KNIGHT_D = {-17, -15, -10, -6, 6, 10, 15, 17};
    private static final int[] KING_D = {-9, -8, -7, -1, 1, 7, 8, 9};
    private static final int[] DIAG_D = {-9, -7, 7, 9};
    private static final int[] ORTH_D = {-8, -1, 1, 8};

    public static List<Move> legal(Pos p) {
        List<Move> out = new ArrayList<>(48);
        for (Move m : pseudo(p)) {
            Pos q = p.copy();
            make(q, m);
            int k = q.king(p.side == WHITE);
            if (k < 0 || !attacked(q.cb, k, 3 - p.side)) out.add(m);
        }
        return out;
    }

    private static List<Move> pseudo(Pos p) {
        List<Move> out = new ArrayList<>(64);
        int[] cb = p.cb;
        boolean white = p.side == WHITE;
        for (int i = 0; i < 64; i++) {
            int pc = cb[i];
            if (pc == 0) continue;
            boolean pcWhite = Board.isWhite(pc);
            if (pcWhite != white) continue;
            int f = i % 8, r = i / 8;
            switch (pcWhite ? pc : pc - 8) {
                case 1: { // pawn
                    int dir = white ? -1 : 1;
                    int startRank = white ? 6 : 1;
                    int promoRank = white ? 0 : 7;
                    int nr = r + dir;
                    if (nr >= 0 && nr < 8) {
                        int to = nr * 8 + f;
                        if (cb[to] == 0) {
                            if (nr == promoRank) addPromos(out, i, to);
                            else out.add(new Move(i, to));
                            if (r == startRank && cb[(r + 2 * dir) * 8 + f] == 0) {
                                out.add(new Move(i, (r + 2 * dir) * 8 + f));
                            }
                        }
                        for (int df = -1; df <= 1; df += 2) {
                            int nf = f + df;
                            if (nf < 0 || nf > 7) continue;
                            int t = nr * 8 + nf;
                            int tgt = cb[t];
                            if (tgt != 0 && Board.isWhite(tgt) != white) {
                                if (nr == promoRank) addPromos(out, i, t);
                                else out.add(new Move(i, t));
                            } else if (tgt == 0 && t == p.epSq) {
                                Move m = new Move(i, t);
                                m.ep = true;
                                out.add(m);
                            }
                        }
                    }
                    break;
                }
                case 2: // knight
                    for (int d : KNIGHT_D) {
                        int t = i + d;
                        if (t < 0 || t > 63) continue;
                        if (!knightStep(i, t)) continue;
                        if (cb[t] == 0 || Board.isWhite(cb[t]) != white) out.add(new Move(i, t));
                    }
                    break;
                case 3: // bishop
                    ray(out, cb, i, white, DIAG_D);
                    break;
                case 4: // rook
                    ray(out, cb, i, white, ORTH_D);
                    break;
                case 5: // queen
                    ray(out, cb, i, white, DIAG_D);
                    ray(out, cb, i, white, ORTH_D);
                    break;
                case 6: { // king
                    for (int d : KING_D) {
                        int t = i + d;
                        if (t < 0 || t > 63) continue;
                        if (!kingStep(i, t)) continue;
                        if (cb[t] == 0 || Board.isWhite(cb[t]) != white) out.add(new Move(i, t));
                    }
                    // castling
                    if (white && i == 60) {
                        if (cb[61] == 0 && cb[62] == 0 && cb[63] == 4 && p.castling.contains("K")
                                && !attacked(cb, 60, BLACK) && !attacked(cb, 61, BLACK)) {
                            Move m = new Move(60, 62); m.castle = true; out.add(m);
                        }
                        if (cb[59] == 0 && cb[58] == 0 && cb[57] == 0 && cb[56] == 4 && p.castling.contains("Q")
                                && !attacked(cb, 60, BLACK) && !attacked(cb, 59, BLACK)) {
                            Move m = new Move(60, 58); m.castle = true; out.add(m);
                        }
                    } else if (!white && i == 4) {
                        if (cb[5] == 0 && cb[6] == 0 && cb[7] == 12 && p.castling.contains("k")
                                && !attacked(cb, 4, WHITE) && !attacked(cb, 5, WHITE)) {
                            Move m = new Move(4, 6); m.castle = true; out.add(m);
                        }
                        if (cb[3] == 0 && cb[2] == 0 && cb[1] == 0 && cb[0] == 12 && p.castling.contains("q")
                                && !attacked(cb, 4, WHITE) && !attacked(cb, 3, WHITE)) {
                            Move m = new Move(4, 2); m.castle = true; out.add(m);
                        }
                    }
                    break;
                }
            }
        }
        return out;
    }

    private static void addPromos(List<Move> out, int from, int to) {
        for (int pc : new int[]{5, 4, 3, 2}) { // Q R B N
            Move m = new Move(from, to);
            m.promo = pc;
            out.add(m);
        }
    }

    private static void ray(List<Move> out, int[] cb, int i, boolean white, int[] dirs) {
        for (int d : dirs) {
            int df, dr;
            if (d == -9) { df = -1; dr = -1; }
            else if (d == 9) { df = 1; dr = 1; }
            else if (d == -7) { df = 1; dr = -1; }
            else if (d == 7) { df = -1; dr = 1; }
            else if (d == -8) { df = 0; dr = -1; }
            else if (d == 8) { df = 0; dr = 1; }
            else if (d == -1) { df = -1; dr = 0; }
            else { df = 1; dr = 0; }
            int nf = i % 8 + df, nr = i / 8 + dr;
            while (nf >= 0 && nf < 8 && nr >= 0 && nr < 8) {
                int t = nr * 8 + nf;
                int tgt = cb[t];
                if (tgt == 0) out.add(new Move(i, t));
                else {
                    if (Board.isWhite(tgt) != white) out.add(new Move(i, t));
                    break;
                }
                nf += df; nr += dr;
            }
        }
    }

    private static boolean knightStep(int a, int b) {
        int df = Math.abs(a % 8 - b % 8), dr = Math.abs(a / 8 - b / 8);
        return (df == 1 && dr == 2) || (df == 2 && dr == 1);
    }

    private static boolean kingStep(int a, int b) {
        int df = Math.abs(a % 8 - b % 8), dr = Math.abs(a / 8 - b / 8);
        return df <= 1 && dr <= 1 && (df + dr) > 0;
    }

    public static boolean attacked(int[] cb, int sq, int by) {
        int f = sq % 8, r = sq / 8;
        // pawns
        int pr = by == WHITE ? r + 1 : r - 1;   // where an attacking pawn would stand
        int pawn = by == WHITE ? 1 : 9;
        if (pr >= 0 && pr < 8) {
            for (int df = -1; df <= 1; df += 2) {
                int nf = f + df;
                if (nf < 0 || nf > 7) continue;
                if (cb[pr * 8 + nf] == pawn) return true;
            }
        }
        // knights
        int kn = by == WHITE ? 2 : 10;
        for (int d : KNIGHT_D) {
            int t = sq + d;
            if (t < 0 || t > 63) continue;
            if (knightStep(sq, t) && cb[t] == kn) return true;
        }
        // king
        int kg = by == WHITE ? 6 : 14;
        for (int d : KING_D) {
            int t = sq + d;
            if (t < 0 || t > 63) continue;
            if (kingStep(sq, t) && cb[t] == kg) return true;
        }
        // sliders
        int bi = by == WHITE ? 3 : 11, ro = by == WHITE ? 4 : 12, qu = by == WHITE ? 5 : 13;
        int[][] dirs = {{1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int[] d : dirs) {
            int nf = f + d[0], nr = r + d[1];
            while (nf >= 0 && nf < 8 && nr >= 0 && nr < 8) {
                int p = cb[nr * 8 + nf];
                if (p != 0) {
                    if (p == bi || p == qu) return true;
                    break;
                }
                nf += d[0]; nr += d[1];
            }
        }
        int[][] dirs2 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs2) {
            int nf = f + d[0], nr = r + d[1];
            while (nf >= 0 && nf < 8 && nr >= 0 && nr < 8) {
                int p = cb[nr * 8 + nf];
                if (p != 0) {
                    if (p == ro || p == qu) return true;
                    break;
                }
                nf += d[0]; nr += d[1];
            }
        }
        return false;
    }

    /** apply a move and update side / en-passant / castling state */
    public static void make(Pos p, Move m) {
        int[] cb = p.cb;
        int pc = cb[m.from];
        int cap = cb[m.to];
        cb[m.to] = pc;
        cb[m.from] = 0;
        if (m.ep) {
            int capSq = p.side == WHITE ? m.to + 8 : m.to - 8;
            cb[capSq] = 0;
        }
        if (m.castle) {
            if (m.to == 62) { cb[61] = cb[63]; cb[63] = 0; }
            else if (m.to == 58) { cb[59] = cb[56]; cb[56] = 0; }
            else if (m.to == 6) { cb[5] = cb[7]; cb[7] = 0; }
            else if (m.to == 2) { cb[3] = cb[0]; cb[0] = 0; }
        }
        int type = Board.isWhite(pc) ? pc : pc - 8;
        if (m.promo > 0) cb[m.to] = (byte) (Board.isWhite(pc) ? m.promo : m.promo + 8);
        // en passant square for the next move
        if (type == 1 && Math.abs(m.to - m.from) == 16) p.epSq = (m.from + m.to) / 2;
        else p.epSq = -1;
        // castling rights
        String c = p.castling;
        if (type == 6) {
            c = Board.isWhite(pc) ? c.replace("K", "").replace("Q", "") : c.replace("k", "").replace("q", "");
        }
        if (m.from == 63 || m.to == 63) c = c.replace("K", "");
        if (m.from == 56 || m.to == 56) c = c.replace("Q", "");
        if (m.from == 7 || m.to == 7) c = c.replace("k", "");
        if (m.from == 0 || m.to == 0) c = c.replace("q", "");
        if (c.isEmpty()) c = "-";
        p.castling = c;
        p.side = 3 - p.side;
    }

    /** colour pattern of a position (0 empty, 1 white, 2 black) in screen order */
    public static int[] pattern(Pos p, boolean whiteBottom) {
        int[] out = new int[64];
        for (int i = 0; i < 64; i++) {
            int f = Board.fileIdxOf(i, whiteBottom), r = Board.rankIdxOf(i, whiteBottom);
            int pc = p.cb[(7 - r) * 8 + f];
            out[i] = pc == 0 ? 0 : (Board.isWhite(pc) ? 1 : 2);
        }
        return out;
    }

    public static boolean inCheck(Pos p) {
        int k = p.king(p.side == WHITE);
        return k >= 0 && attacked(p.cb, k, 3 - p.side);
    }
}
