package com.chesshint.panel;

import java.util.ArrayList;
import java.util.List;

/**
 * A chess board.
 *
 * Squares are stored in SCREEN order: index = row*8 + col  where
 *   row 0 = top row of the board as drawn on the phone screen,
 *   col 0 = left column.
 * So index 0 is the top-left square and index 63 the bottom-right square,
 * no matter if the viewer plays white or black.
 *
 * Piece codes: 0 = empty, 1..6 = white P N B R Q K, 9..14 = black p n b r q k
 */
public class Board {

    public static final int EMPTY = 0;
    public static final String GLYPHS = "\u2659\u2658\u2657\u2656\u2655\u2654\u265F\u265E\u265D\u265C\u265B\u265A";

    public byte[] s = new byte[64];

    public Board copy() {
        Board b = new Board();
        System.arraycopy(s, 0, b.s, 0, 64);
        return b;
    }

    public boolean same(Board o) {
        if (o == null) return false;
        for (int i = 0; i < 64; i++) if (s[i] != o.s[i]) return false;
        return true;
    }

    public int at(int i) { return i >= 0 && i < 64 ? s[i] : 0; }

    public static boolean isWhite(int p) { return p >= 1 && p <= 6; }
    public static boolean isBlack(int p) { return p >= 9; }

    public static char pieceChar(int p) {
        if (p == 0) return '.';
        return "PNBRQKpnbrqk".charAt(p < 8 ? p - 1 : p - 3);
    }

    /** unicode chess glyph for a piece code ("" when empty) */
    public static String glyph(int p) {
        if (p == 0) return "";
        return String.valueOf(GLYPHS.charAt(p < 8 ? p - 1 : p - 3));
    }

    public static int fromChar(char c) {
        int i = "PNBRQKpnbrqk".indexOf(c);
        if (i < 0) return 0;
        return i < 6 ? i + 1 : i + 3;
    }

    // ---------------------------------------------------------------- geometry

    public static int indexOf(int fileIdx, int rankIdx, boolean whiteBottom) {
        int c = whiteBottom ? fileIdx : 7 - fileIdx;
        int r = whiteBottom ? 7 - rankIdx : rankIdx;
        return r * 8 + c;
    }

    public static int fileIdxOf(int idx, boolean whiteBottom) {
        int c = idx % 8;
        return whiteBottom ? c : 7 - c;
    }

    public static int rankIdxOf(int idx, boolean whiteBottom) {
        int r = idx / 8;
        return whiteBottom ? 7 - r : r;
    }

    public static String squareName(int idx, boolean whiteBottom) {
        int f = fileIdxOf(idx, whiteBottom), r = rankIdxOf(idx, whiteBottom);
        return "" + (char) ('a' + f) + (char) ('1' + r);
    }

    public static int squareFromName(String name, boolean whiteBottom) {
        if (name == null || name.length() < 2) return -1;
        int f = name.charAt(0) - 'a';
        int r = name.charAt(1) - '1';
        if (f < 0 || f > 7 || r < 0 || r > 7) return -1;
        return indexOf(f, r, whiteBottom);
    }

    // ------------------------------------------------------------------- FEN

    public String toFen(char turn, boolean whiteBottom) {
        StringBuilder sb = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                int p = s[indexOf(f, r, whiteBottom)];
                if (p == 0) empty++;
                else {
                    if (empty > 0) { sb.append(empty); empty = 0; }
                    sb.append(pieceChar(p));
                }
            }
            if (empty > 0) sb.append(empty);
            if (r > 0) sb.append('/');
        }
        sb.append(' ').append(turn).append(' ');
        sb.append(castling(whiteBottom)).append(" - 0 1");
        return sb.toString();
    }

    /**
     * Castling rights are inferred straight from the board picture:
     * a right only exists while king and rook still stand on their original squares.
     */
    public String castling(boolean whiteBottom) {
        StringBuilder sb = new StringBuilder();
        if (at(indexOf(4, 0, whiteBottom)) == 6) {
            if (at(indexOf(7, 0, whiteBottom)) == 4) sb.append('K');
            if (at(indexOf(0, 0, whiteBottom)) == 4) sb.append('Q');
        }
        if (at(indexOf(4, 7, whiteBottom)) == 14) {
            if (at(indexOf(7, 7, whiteBottom)) == 12) sb.append('k');
            if (at(indexOf(0, 7, whiteBottom)) == 12) sb.append('q');
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    public static Board fromFen(String fen, boolean whiteBottom) {
        Board b = new Board();
        if (fen == null) return b;
        String[] parts = fen.trim().split("\\s+");
        String rows = parts[0];
        int r = 7, f = 0;
        for (char c : rows.toCharArray()) {
            if (c == '/') { r--; f = 0; }
            else if (c >= '1' && c <= '8') { f += c - '0'; }
            else {
                if (f < 8 && r >= 0) b.s[indexOf(f, r, whiteBottom)] = (byte) fromChar(c);
                f++;
            }
        }
        return b;
    }

    public static Board starting(boolean whiteBottom) {
        return fromFen(START_FEN, whiteBottom);
    }

    // --------------------------------------------------------------- helpers

    public int countWhite() { int n = 0; for (int i = 0; i < 64; i++) if (isWhite(s[i])) n++; return n; }

    public int countBlack() { int n = 0; for (int i = 0; i < 64; i++) if (isBlack(s[i])) n++; return n; }

    public int count(int piece) { int n = 0; for (int i = 0; i < 64; i++) if (s[i] == piece) n++; return n; }

    public List<Integer> diffIndices(Board prev) {
        List<Integer> out = new ArrayList<>();
        if (prev == null) return out;
        for (int i = 0; i < 64; i++) if (prev.s[i] != s[i]) out.add(i);
        return out;
    }

    /**
     * Works out which colour just made a move, by looking at what changed between two
     * consecutive reads: the side that owns the newly appeared piece must have moved.
     *
     * @return 1 = white moved, 2 = black moved, 0 = unknown
     */
    public static int inferMover(Board prev, Board now) {
        if (prev == null || now == null) return 0;
        int w = 0, b = 0;
        for (int i = 0; i < 64; i++) {
            if (prev.s[i] == now.s[i]) continue;
            int nw = now.s[i], pw = prev.s[i];
            if (nw != EMPTY) { if (isWhite(nw)) w++; else b++; }
            else if (pw != EMPTY) { if (isWhite(pw)) w++; else b++; }
        }
        if (w == 0 && b == 0) return 0;
        return w >= b ? 1 : 2;
    }

    /** Sanity check, returns "" when the position looks fine */
    public String validate(boolean whiteBottom) {
        if (count(6) != 1) return "White king problem (" + count(6) + ")";
        if (count(14) != 1) return "Black king problem (" + count(14) + ")";
        if (countWhite() > 16) return "Too many white pieces (" + countWhite() + ")";
        if (countBlack() > 16) return "Too many black pieces (" + countBlack() + ")";
        if (count(1) > 8 || count(9) > 8) return "Too many pawns";
        return "";
    }

    /** cheap string signature used to detect board changes */
    public String signature() {
        StringBuilder sb = new StringBuilder(64);
        for (byte p : s) sb.append((char) ('0' + (p & 15)));
        return sb.toString();
    }
}
