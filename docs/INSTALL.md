# Install & first run

## 1. Install the APK
1. Copy `apk/ChessHintPanel-v1.2.apk` to the phone (or open the link in the browser).
2. Tap the file. Android will warn about installing from an unknown source —
   choose **Settings → Allow from this source**, then **Install**.

## 2. Give the two permissions
| Step | What to tap | What it is for |
|---|---|---|
| 1 | **ALLOW "DISPLAY OVER OTHER APPS"** → switch the toggle **ON** | lets the panel float above your chess app |
| 2 | **START PANEL** → *"Start recording or casting?"* → **YES** (whole screen) | lets the panel see the board. It is a standard Android API; nothing leaves the phone. |

A small **♞ HINT** button appears — drag it wherever you like.

## 3. First hint
1. Open your chess game. **Your pieces must be at the bottom** (use ♞ → FLIP if not, or set
   *My side* in Settings).
2. Tap **♞ → SHOW MY MOVE** (or from the app: *Show my move*).
3. The first time, if the panel cannot find the board automatically:
   * tap **FIT BOARD FRAME** and drag the yellow frame exactly onto the board, **SAVE**,
   * if the pieces look wrong, tap **FIX POSITION** and correct them once.
4. From then on the panel follows the game by itself — turn **AUTO** on for a hint on every
   one of your turns.

## Troubleshooting

| Symptom | Fix |
|---|---|
| *"Screen reading failed"* | Tap **Retry screen reading** in the app. On Xiaomi/Huawei also allow "Display pop-up windows while running in background" and disable battery optimisation for the app. |
| *"No chess board on screen"* | The board is not visible or the app draws it at an unusual size — open the game and tap **FIT BOARD FRAME** once. |
| Arrow on the wrong squares | Board frame drifted → **FIT BOARD FRAME**. |
| *"Board out of sync"* | A move was missed while the screen was off → **FIX PIECES**, or **NEW GAME** at the start. |
| Nothing appears over the chess app | The overlay permission is off (Step 1). |
| Panel stops by itself | Settings → Apps → Chess Hint Panel → Battery → **Unrestricted**. |
| Hints too slow | Settings → *Thinking time* → 0.5 s (still far stronger than any human). |
| Anything else | App → **DIAGNOSTICS → Copy** and open an issue with that text. |
