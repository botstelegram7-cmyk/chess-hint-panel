import com.chesshint.panel.Vision;

import java.io.RandomAccessFile;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import android.graphics.Rect;

/** Loads the synthetic screenshots and checks board detection + square reading. */
public class VisionTest {

    static int pass = 0, fail = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (ok) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what + "   got " + got + "  want " + want); }
    }

    public static void main(String[] a) throws Exception {
        File dir = new File("/home/user/build/test/boards");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".txt"));
        java.util.Arrays.sort(files);
        for (File meta : files) {
            String name = meta.getName().replace(".txt", "");
            String[] lines = new String(Files.readAllBytes(Paths.get(meta.getPath()))).split("\n");
            String[] hdr = lines[0].trim().split("\\s+");
            int w = Integer.parseInt(hdr[0]), h = Integer.parseInt(hdr[1]);
            int bx = Integer.parseInt(hdr[2]), by = Integer.parseInt(hdr[3]), bs = Integer.parseInt(hdr[4]);
            String expected = lines.length > 1 ? lines[1].trim() : "";
            String fen = lines.length > 2 ? lines[2].trim() : "";
            boolean negative = fen.equals(".");

            byte[] raw = Files.readAllBytes(Paths.get(dir.getPath() + "/" + name + ".raw"));
            if (raw.length < w * h * 3) { System.out.println("skip " + name); continue; }
            int[] px = new int[w * h];
            for (int i = 0; i < w * h; i++) {
                int r = raw[i * 3] & 255, g = raw[i * 3 + 1] & 255, b = raw[i * 3 + 2] & 255;
                px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }

            System.out.println("---- " + name + "  (" + w + "x" + h + ")");
            Rect det = Vision.detect(px, w, h);
            if (negative) {
                check("no board found on a board-less screen", det == null, true);
                continue;
            }
            check("board detected", det != null, true);
            if (det == null) continue;
            System.out.println("        detected " + det.toShortString() + "  truth (" + bx + "," + by + "," + bs + ")");
            int dx = Math.abs(det.left - bx), dy = Math.abs(det.top - by), ds = Math.abs(det.width() - bs);
            check("detected rect within 12 px", (dx <= 12 && dy <= 12 && ds <= 14), true);

            Rect truth = new Rect(bx, by, bx + bs, by + bs);
            Vision.Result res = Vision.read(px, w, h, det);
            check("read ok", res.ok, true);
            // ---- end to end: can the game tracker recover from a bad square?
            if (name.equals("chesscom_start")) {
                com.chesshint.panel.Chess.Pos start =
                        com.chesshint.panel.Chess.fromBoard(com.chesshint.panel.Board.starting(true), true, 1);
                com.chesshint.panel.Track tr = new com.chesshint.panel.Track(start, true);
                com.chesshint.panel.Track.Update u = tr.observe(res.colorPat, res.conf);
                check("tracker explains a slightly wrong read", u.ok && u.fixedSquares <= 2, true);
                check("tracker keeps the correct position",
                        u.ok && tr.pos.fen().startsWith(com.chesshint.panel.Board.START_FEN.split(" ")[0]), true);
            }

            StringBuilder got = new StringBuilder();
            for (int i = 0; i < 64; i++) got.append(res.colorPat[i]);
            if (got.toString().equals(expected)) {
                check("all 64 squares correct", true, true);
            } else {
                fail++;
                System.out.println("  FAIL  squares differ:");
                StringBuilder want = new StringBuilder(expected);
                for (int r = 0; r < 8; r++) {
                    StringBuilder g = new StringBuilder(), e = new StringBuilder();
                    for (int f = 0; f < 8; f++) {
                        int i = r * 8 + f;
                        char gc = got.charAt(i), ec = want.charAt(i);
                        g.append(gc == ec ? '.' : gc);
                        e.append(gc == ec ? '.' : ec);
                    }
                    System.out.println("        row " + r + "  got: " + g + "   want: " + e);
                }
                // which squares are wrong
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 64; i++) if (got.charAt(i) != want.charAt(i))
                    sb.append(ChessName(i)).append("(").append(got.charAt(i)).append("->").append(want.charAt(i)).append(") ");
                System.out.println("        wrong: " + sb);
                System.out.printf("        lmid=%.3f thr=%.3f%n", res.lmid, res.threshold);
                for (int i = 0; i < 64; i++) if (got.charAt(i) != want.charAt(i))
                    System.out.printf("        %s inkFrac=%.3f inkLum=%.3f dmax=%.3f score=%.3f%n",
                        ChessName(i), res.inkFrac[i], res.inkLum[i], res.dmax[i], res.score[i]);
            }
        }
        System.out.println("\n================  " + pass + " passed, " + fail + " failed  ================");
    }

    static String ChessName(int idx) {
        int r = idx / 8, f = idx % 8;
        return "" + (char) ('a' + f) + (8 - r);
    }
}
