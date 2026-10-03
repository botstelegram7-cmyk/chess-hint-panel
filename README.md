<div align="center">

<img src="docs/screenshots/shot-1-arrow-rings.png" width="330" alt="Chess Hint Panel showing a knight move hint">

# ♞ Chess Hint Panel

**A floating, on-screen chess coach for Android.**
It reads the board from *any* chess app with the screen-recording API, thinks with a
built-in Stockfish 11 engine, and draws a fat arrow that tells you **which piece to move
and where** — right on top of the game.

[![Build APK](https://github.com/botstelegram7-cmyk/chess-hint-panel/actions/workflows/build.yml/badge.svg)](../../actions/workflows/build.yml)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84)
![Engine](https://img.shields.io/badge/engine-Stockfish%2011-blue)
![Offline](https://img.shields.io/badge/internet-not%20required-informational)
![License](https://img.shields.io/badge/license-MIT%20%2B%20GPLv3%20engine-yellow)

**[⬇ Download the APK](../../releases) · [Install guide](docs/INSTALL.md) · [Build it yourself](docs/BUILDING.md) · [How it works](docs/ARCHITECTURE.md)**

</div>

---

## ✨ What it does

| | |
|---|---|
| 🎯 **Tells you the move** | A bright arrow + ring on the piece to move, a ring on the target square, and a badge like `YOUR MOVE ♘ g1 → f3`. |
| 🧠 **Real engine** | Stockfish 11 compiled natively for ARM64 / ARMv7 / x86_64 — superhuman strength, **fully offline**. |
| 👁 **Works over any app** | chess.com, Lichess, Chess.com Play, offline games… it reads the screen like your eyes do — no rooting, no hooks, no accounts. |
| 🙋 **Only *your* move** | The bottom half of the board is *you*, the top is the enemy. While the opponent thinks it stays quiet instead of revealing their idea. |
| 🎨 **You choose the marks** | Arrow + rings, arrow only, rings only, or filled squares · 5 colour sets · 3 sizes · optional text, engine line and labels. |
| ⚙️ **Auto mode** | Watches the screen and hints you automatically on every one of your turns. |
| 🔒 **Private** | No internet permission. Nothing is uploaded, no analytics, no account. |
| 🛡 **Never crashes** | Every layer is guarded and the app keeps a crash log you can read and copy from the Diagnostics card. |

<div align="center">
<img src="docs/screenshots/shot-2-arrow-only.png" width="240">
<img src="docs/screenshots/shot-3-squares.png" width="240">
</div>

## 📥 Install (30 seconds)

1. Download **`apk/ChessHintPanel-v1.2.apk`** (or the file in [Releases](../../releases)).
2. Tap it → Android asks about installing from this source → **Allow** → **Install**.
3. Open the app and follow the two steps it shows:
   * **ALLOW "DISPLAY OVER OTHER APPS"** — lets the panel float above your chess app.
   * **START PANEL** → Android asks *"Start recording or casting?"* → **YES**.
4. A small **♞ HINT** button appears. Open your chess game and tap it → **SHOW MY MOVE**.

> Your pieces must be at the **bottom** of the board. If not, tap ♞ → **FLIP** (or set
> *My side* in Settings).

## 🎛 Settings at a glance

| Group | Options |
|---|---|
| **Marks** | style (arrow+rings / arrow / rings / squares) · colours (5 sets) · size (S/M/L) · move text · engine line · labels · board frame · status pill |
| **Play** | my side (white/black at bottom) · strength (MAX, 2850, 2400, 1800, 1400) · thinking time (0.5–5 s) · auto hints · vibrate |
| **Board** | fit frame · auto detect · fix pieces · new game · bubble size |

Every change has a **live preview** in Settings and is applied to the running panel instantly.

## 🧩 How it works (short version)

```
MediaProjection ──► ScreenGrab ──► Vision (detect board, classify 64 squares)
                                        │
                                        ▼
                          Track  — finds the legal move(s) that explain
                                   the screen, so piece TYPES stay exact
                                        │
                                        ▼
                             Stockfish 11 (JNI, in-process)
                                        │
                                        ▼
                        Markers ──► OverlayView (TYPE_APPLICATION_OVERLAY)
```

* **Vision** finds the board by grid-line contrast + checkerboard alternation, then compares
  each square with **its own four corners** — so any board theme works and highlighted or
  dotted squares are correctly read as *empty*.
* **Track** starts from a known position and, after every screen change, searches for the
  shortest legal move sequence that produces exactly what was seen. Types therefore never
  drift; small misreads are auto-corrected by flipping the least certain squares.
* **Stockfish** is compiled into `libstockfish.so` and loaded with `System.loadLibrary()` —
  no `exec()`, so it runs on every Android version including 10+ with the strictest policies.
* The engine is asked **only for your side's move** (the FEN is given with your side to move).

Details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## 🏗 Build it yourself

```bash
# 1. engine (needs the Android NDK) - fetches Stockfish 11 and builds all ABIs
tools/build-stockfish-android.sh         # -> build/jnilibs/libstockfish.so.*

# 2. app (needs JDK 17 + Android SDK 34)
tools/build-apk.sh                       # -> build/out/classes.dex + base.apk
tools/package-apk.sh                     # -> build/out/ChessHintPanel-v1.2.apk (signed)
```
No Gradle required — the scripts drive `aapt2`, `javac`, `d8`, `zipalign` and `apksigner`
directly, and there is a ready-made [GitHub Actions workflow](.github/workflows/build.yml)
that builds the APK on every push.

## 🧪 Tests

The engine plumbing, the move generator and the screen reader are tested on a desktop JVM:

```bash
tools/build-stockfish-host.sh                          # Linux build of the same library
cd tools/tests && javac -d classes -sourcepath ../../app/java:. CoreTest.java VisionTest.java
java -Djava.library.path=../../build/host -cp classes:../../app/java CoreTest
```

* **Move generator** — perft counts compared against Stockfish itself (startpos depth 4 =
  197 281, kiwipete depth 3 = 97 862, …) → all match.
* **Tracker** — follows a full opening from screen patterns only, catches 4 plies at once,
  repairs noisy reads.
* **Vision** — synthetic screenshots of 6 themes (light, dark, blue, busy background,
  highlighted squares with move dots) → board found within a few pixels, all 64 squares read.
* **Engine** — mate-in-1, promotions, Elo-limited mode through the JNI bridge.

## 📱 Requirements

* Android **8.0+** (API 26) · arm64-v8a, armeabi-v7a or x86_64
* ~1.4 MB install size · no internet permission

## ⚖️ About "8000 Elo"

No chess engine reaches 8000 Elo. The strongest engines ever measured sit around **3600**,
and Stockfish at full strength **is** that ceiling — so the panel ships with **MAX**, the
strongest that physically exists, and the strength selector is there to *weaken* it
(2850 → 1400) when you want realistic practice instead of a demolished opponent.

## 🤝 Contributing
Issues and PRs are welcome. Useful things to help with: more board-theme test images,
piece-type classification, tablet/landscape tuning.

## 📄 License

* App source in this repository: **MIT** (see [LICENSE](LICENSE)).
* The bundled engine is **[Stockfish 11](https://github.com/official-stockfish/Stockfish)**, licensed **GPLv3**.
  It is compiled from unmodified official sources by `native/jni_bridge.cpp` +
  `tools/build-stockfish-android.sh`; the corresponding sources are fetched by that script.
  If you redistribute the APK, keep the Stockfish licence and offer its source.
* Chess glyphs are rendered with the system font — no font files are bundled.

<div align="center"><sub>Made for players who want to learn and win, offline.</sub></div>
