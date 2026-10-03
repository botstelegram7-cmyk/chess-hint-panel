# Settings — everything you can change

Open the gear icon on the home screen, or ♞ → **SETTINGS** from the floating panel.
The board at the top of the screen is a **live preview**: it always shows exactly what your
marks will look like on your own board.

## Marks

| Setting | Values | Notes |
|---|---|---|
| **Style** | Arrow + rings · Arrow only · Rings only · Squares | *Arrow + rings* is the default: green ring on the piece you must move, amber ring on the target, amber arrow between them. |
| **Colours** | Green/Amber · Blue/Pink · Cyan/Orange · White/Red · Lime/Violet | Fixed, high-contrast colours — never "adaptive", so they stay visible on light, dark, green or blue boards. |
| **Size** | Small · Normal · Large | Scales rings, arrow and the text badge together. |
| **Move text on the board** | on/off | The badge: `YOUR MOVE ♘ g1 → f3`. |
| **Engine line** | on/off | `depth 22   +0.34` printed just under the board. |
| **Enemy / You labels** | on/off | Thin `ENEMY ▲` / `YOU (WHITE) ▼` labels outside the board. |
| **Board frame + grid** | on/off | Draws the detected board rectangle and its 8×8 grid — handy while calibrating. |
| **Status messages** | on/off | The small pill near the bottom that shows "Reading board… / YOUR MOVE …". |

## Play

| Setting | Values | Notes |
|---|---|---|
| **My side** | white at the bottom / black at the bottom | The bottom half is always *you*; the engine is only asked for your move. |
| **Engine strength** | MAX · 2850 · 2400 · 1800 · 1400 | MAX is full Stockfish (≈3400+). The others use Stockfish's own Elo limiter for fair practice. |
| **Thinking time per hint** | 0.5 s · 1 s · 1.5 s · 3 s · 5 s | Longer = slightly stronger move, slower answer. 1.5 s is the default. |
| **Auto hints** | on/off | Captures a frame every second and hints you automatically on your turn; silent while the enemy thinks. |
| **Vibrate on new hint** | on/off | A short buzz when a new hint appears. |

## Board

| Button | What it does |
|---|---|
| **Fit frame** | Shows the draggable yellow frame — corners resize, middle moves, empty area starts a new frame. Use it when auto-detection is off or the theme is unusual. |
| **Auto detect** | Searches the current screen for a chess board and sets the frame. |
| **Fix pieces** | Opens the board editor: tap a square to cycle empty → ♙♘♗♖♕♔ (white) → ♟♞♝♜♛♚ (black) → empty. Use it once when you join a game mid-way. |
| **New game** | Resets the tracked position to the standard starting position. |
| **Bubble size** | Small / Normal / Large — the size of the floating ♞ button. |

## Tips

* **The arrow is on the wrong squares** → the frame drifted. Tap **Fit frame**.
* **"Board out of sync"** → a move happened while the screen was off. **Fix pieces**, or **New game**.
* **Hints feel slow** → *Thinking time* → 0.5 s (still far stronger than any human).
* **You want a clean screen** → turn off *engine line*, *labels* and *status messages*; only the arrow stays.
