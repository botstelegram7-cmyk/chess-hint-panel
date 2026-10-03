# Architecture

```
┌───────────────────────────── app process ─────────────────────────────┐
│                                                                       │
│  MainActivity ──► OverlayService ──► WindowManager (TYPE_APPLICATION_OVERLAY)
│  SettingsActivity      │              ├── OverlayView   (hints, arrows, chip)
│                        │              ├── BubbleView    (draggable ♞)
│                        │              ├── PanelView     (tile menu)
│                        │              ├── CalibrationView / BoardEditorView
│                        │              └── MarkerPreviewView (Settings only)
│                        │
│                        ├── ScreenGrab   MediaProjection → VirtualDisplay → ImageReader
│                        ├── Vision       board detection + square classification
│                        ├── Track        legal-move search that keeps types exact
│                        └── UciEngine    JNI → libstockfish.so (Stockfish 11)
└───────────────────────────────────────────────────────────────────────┘
```

## Screen → position

1. **Detect.** A coarse search on a downscaled copy scores candidate board rectangles by
   (a) grid-line gradient contrast at the 7 internal lines and the perimeter and
   (b) colour alternation (neighbour squares differ, diagonal squares match). The best few
   candidates are re-aligned at ~600 px with a hill-climb over x, y and pitch.

2. **Classify.** For every square the reader compares the middle region with the square's
   **own four corner patches**, so it is independent of the board theme. Features:
   *ink fraction* (share of pixels that differ from those corners), *eroded ink* (the piece
   **body** — an outline ring or a move dot disappears under a 2–4 px erosion) and *maximum
   colour contrast*. A square counts as occupied when the eroded body survives and there is a
   strongly contrasting pixel, which is exactly what separates a piece from a highlight.
   Piece **colour** is the mean luminance of the surviving body.

3. **Track.** The reader deliberately does **not** guess piece *types*. The tracker keeps the
   real position, starts from the standard position (or a position the user confirms once in
   the editor), and after every screen change searches for the **shortest legal move sequence**
   that reproduces the observed colour pattern. Only moves that touch a still-different square
   are tried, so 1–3 ply searches stay instant. If the raw reading cannot be explained, the
   least certain squares are flipped (one or two at a time) until an explanation exists —
   this repairs partial misreads automatically and is what keeps the arrow on the right piece.

## Engine

* `tools/build-stockfish-android.sh` fetches official **Stockfish 11** and compiles it with the
  NDK into `libstockfish.so` (all three ABIs, stripped, ~1 MB each).
* `native/jni_bridge.cpp` wires the official `UCI::loop()` to two custom stream buffers:
  Java pushes commands in, engine lines come back through a JNI callback.
  Because it is a normal shared library loaded with `System.loadLibrary()`, no `exec()` is
  needed — that is why it works on Android 10+ with the strictest policies.
* The app always asks for **the player's side to move** (the FEN is written with that side to
  move), so the hint is never the opponent's idea. Strength can be limited with Stockfish's
  own `UCI_LimitStrength` / `UCI_Elo` options.

## Failure handling

* `CrashGuard` installs a default uncaught-exception handler, guards posted UI work and every
  canvas draw, and writes a short log (with device + Android version) that the Diagnostics
  card shows and copies.
* Screen-capture problems never kill the panel: it reports the real reason, keeps the bubble
  alive and offers *Retry screen reading*.
* Memory: engine hash 16 MB, screenshots processed at ≤640 px on the board, decoded bitmaps
  recycled, `largeHeap` enabled as a safety net.

## Build without Gradle

`tools/build-apk.sh` runs `aapt2 compile/link` → `javac` (against `android.jar`) → `d8`, and
`tools/package-apk.sh` adds the `.dex` + native libs, `zipalign`s and signs with
`apksigner`. The CI workflow does the same on every push.
