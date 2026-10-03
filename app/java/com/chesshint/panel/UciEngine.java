package com.chesshint.panel;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Stockfish, compiled into libstockfish.so and loaded with System.loadLibrary().
 * The native side runs the official UCI loop on its own thread and forwards every
 * output line to onEngineLine(); we push commands in with nativeWrite().
 *
 * This class has no Android dependencies on purpose, so the identical code can be
 * tested on a PC (see the host build used while developing).
 */
public class UciEngine {

    private static boolean libOk;
    private static String libError = "";

    private native void nativeInit();
    private native void nativeWrite(String cmd);

    private final ArrayBlockingQueue<String> raw = new ArrayBlockingQueue<>(4096);
    private final ArrayBlockingQueue<String> best = new ArrayBlockingQueue<>(8);
    private final ArrayBlockingQueue<String> hand = new ArrayBlockingQueue<>(8);
    private final ArrayBlockingQueue<String> echo = new ArrayBlockingQueue<>(512);

    private Thread pump;
    private volatile boolean alive;
    private volatile boolean thinking;

    public volatile int depth;
    public volatile int scoreCp;
    public volatile int mateIn;
    public volatile String lastName = "";

    public static synchronized boolean loadLibrary() {
        if (libOk) return true;
        try {
            System.loadLibrary("stockfish");
            libOk = true;
        } catch (Throwable t) {
            libOk = false;
            libError = String.valueOf(t.getMessage());
        }
        return libOk;
    }

    public static String libraryError() { return libError; }

    /** called from the engine thread inside libstockfish.so */
    public void onEngineLine(String line) {
        if (line == null) return;
        if (!raw.offer(line)) {          // never block the engine thread
            raw.poll();
            raw.offer(line);
        }
    }

    public boolean isAlive() { return alive; }

    /** test helpers: raw engine console access */
    public void clearRaw() { raw.clear(); echo.clear(); }
    public String pollRaw(long ms) throws InterruptedException { return echo.poll(ms, TimeUnit.MILLISECONDS); }
    public boolean isThinking() { return thinking; }

    private int hashMb = 16;
    private int threadsWanted = 0;

    /** memory / thread budget, decided once at start-up by Tune */
    public void setBudget(int hashMb, int threads) {
        this.hashMb = Math.max(8, Math.min(128, hashMb));
        this.threadsWanted = Math.max(0, threads);
    }

    public synchronized boolean start() {
        if (alive) return true;
        if (!loadLibrary()) return false;
        try {
            nativeInit();
        } catch (Throwable t) {
            libError = String.valueOf(t.getMessage());
            return false;
        }
        pump = new Thread(this::pumpLoop, "uci-pump");
        pump.setDaemon(true);
        pump.start();
        alive = true;
        try {
            hand.clear();
            nativeWrite("uci");
            String l = hand.poll(5000, TimeUnit.MILLISECONDS);
            if (l == null) { alive = false; return false; }
            int threads = threadsWanted > 0 ? threadsWanted
                    : Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
            nativeWrite("setoption name Threads value " + threads);
            nativeWrite("setoption name Hash value " + hashMb);
            nativeWrite("setoption name Skill Level value 20");
            nativeWrite("setoption name Ponder value false");
            nativeWrite("isready");
            hand.poll(5000, TimeUnit.MILLISECONDS);
            return true;
        } catch (Throwable t) {
            alive = false;
            return false;
        }
    }

    private void pumpLoop() {
        try {
            while (alive) {
                String line = raw.poll(300, TimeUnit.MILLISECONDS);
                if (line == null) continue;
                if (!echo.offer(line)) { echo.poll(); echo.offer(line); }
                if (line.startsWith("bestmove")) {
                    thinking = false;
                    best.offer(line);
                } else if (line.equals("uciok") || line.equals("readyok")) {
                    hand.offer(line);
                } else if (line.startsWith("info") && line.contains(" depth ")) {
                    parseInfo(line);
                }
            }
        } catch (Throwable ignored) { }
    }

    private void parseInfo(String line) {
        try {
            String[] t = line.split("\\s+");
            for (int i = 0; i < t.length - 1; i++) {
                if (t[i].equals("depth")) depth = parseInt(t[i + 1], depth);
                else if (t[i].equals("cp")) { scoreCp = parseInt(t[i + 1], scoreCp); mateIn = 0; }
                else if (t[i].equals("mate")) mateIn = parseInt(t[i + 1], 0);
                else if (t[i].equals("pv") && i + 1 < t.length) lastName = t[i + 1];
            }
        } catch (Throwable ignored) { }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    public void send(String cmd) {
        if (!alive) return;
        try { nativeWrite(cmd); } catch (Throwable ignored) { }
    }

    /** @param elo -1 = full strength, otherwise UCI_Elo target (1350..2850) */
    public String bestMove(String fen, int moveTimeMs, int elo) {
        if (!alive) return null;
        try {
            best.clear();
            if (elo > 0) {
                send("setoption name UCI_LimitStrength value true");
                send("setoption name UCI_Elo value " + Math.max(1350, Math.min(2850, elo)));
            } else {
                send("setoption name UCI_LimitStrength value false");
            }
            send("position fen " + fen);
            depth = 0; scoreCp = 0; mateIn = 0;
            thinking = true;
            send("go movetime " + moveTimeMs);
            String line = best.poll(moveTimeMs + 15000L, TimeUnit.MILLISECONDS);
            thinking = false;
            return line;
        } catch (Throwable t) {
            return null;
        }
    }

    public static String moveOf(String bestLine) {
        if (bestLine == null) return null;
        String[] t = bestLine.trim().split("\\s+");
        if (t.length >= 2 && t[0].equals("bestmove")) {
            if (t[1].equals("(none)")) return null;
            return t[1];
        }
        return null;
    }

    public synchronized void stop() {
        alive = false;
        try { nativeWrite("quit"); } catch (Throwable ignored) { }
    }
}
