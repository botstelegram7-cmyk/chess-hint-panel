#!/usr/bin/env python3
"""
Generates the app UI screenshots used in the README.
They are faithful mockups of the real layouts (same colours, spacing and wording as the
Java layouts in app/java/.../Ui.java, MainActivity.java, SettingsActivity.java, PanelView.java).
"""
import math
from PIL import Image, ImageDraw, ImageFont

OUT = "/home/user/repo/chess-hint-panel/docs/screenshots"

BG      = "#0A1119"
CARD    = "#101A24"
BORDER  = "#1E2C3A"
CHIP    = "#16222E"
ACCENT  = "#00E676"
YELLOW  = "#FFD400"
TEXT    = "#E9F1F8"
DIM     = "#8CA3B8"
DANGER  = "#FF5C6C"

def font(sz, bold=True):
    paths = ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
             "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]
    return ImageFont.truetype(paths[0 if bold else 1], sz)

def wrap(d, text, fnt, maxw):
    out, cur = [], ""
    for w in text.split():
        t = (cur + " " + w).strip()
        if d.textlength(t, font=fnt) <= maxw: cur = t
        else: out.append(cur); cur = w
    if cur: out.append(cur)
    return out

def card(d, x, y, w, h, fill=CARD, outline=BORDER, r=22):
    d.rounded_rectangle([x, y, x + w, y + h], r, fill=fill, outline=outline, width=2)

def title(d, x, y, s, colour=ACCENT, size=26):
    d.text((x, y), s, font=font(size), fill=colour)

def body(d, x, y, s, colour=TEXT, size=26, maxw=600, gap=34):
    for ln in wrap(d, s, font(size - 2, False), maxw):
        d.text((x, y), ln, font=font(size - 2, False), fill=colour); y += gap
    return y

def chip(d, x, y, label, selected, w=None, size=24):
    f = font(size, selected)
    tw = d.textlength(label, font=f) + 34
    w = w or tw
    d.rounded_rectangle([x, y, x + w, y + 52], 14,
                        fill=ACCENT if selected else CHIP,
                        outline=ACCENT if selected else BORDER, width=2)
    d.text((x + w / 2, y + 26), label, font=f,
           fill="#06210F" if selected else TEXT, anchor="mm")
    return w

def switch(d, x, y, on, w=88, h=44):
    d.rounded_rectangle([x, y, x + w, y + h], h // 2, fill=ACCENT if on else "#31465C")
    cx = x + (w - h // 2 - 6) if on else x + h // 2 + 6
    d.ellipse([cx - h // 2 + 5, y + 5, cx + h // 2 - 5, y + h - 5], fill="#FFFFFF")

# ---------------------------------------------------------------- home screen
def home(path, running=False, problem=None):
    W, H = 900, 1720
    img = Image.new("RGB", (W, H), BG); d = ImageDraw.Draw(img)
    pad = 30

    # one-line top bar
    d.text((pad, 26), "♞", font=font(40), fill=YELLOW)
    d.text((pad + 52, 22), "Chess Hint Panel", font=font(30), fill=TEXT)
    d.text((pad + 52, 58), "v1.3  •  Stockfish inside", font=font(21, False), fill=DIM)
    d.text((W - pad - 28, 40), "⚙", font=font(40), fill=TEXT)

    # status card
    y = 110
    card(d, pad, y, W - 2 * pad, 400)
    dot = ACCENT if running else DANGER
    d.ellipse([pad + 26, y + 30, pad + 42, y + 46], fill=dot)
    d.text((pad + 60, y + 20), "Panel is running" if running else "Panel is stopped", font=font(30), fill=TEXT)
    d.text((pad + 60, y + 62), "tap the ♞ bubble in your chess app" if running
           else "allow both permissions, then press START PANEL", font=font(22, False), fill=DIM)
    # big button
    bx, by = pad + 20, y + 112
    d.rounded_rectangle([bx, by, W - pad - 20, by + 78], 20, fill="#2A1620" if running else ACCENT)
    d.text((W / 2, by + 39), "STOP PANEL" if running else "START PANEL", font=font(30),
           fill="#FF7B8A" if running else "#06210F", anchor="mm")
    # permission rows
    ry = y + 232
    for label, ok, sub in [("Display over other apps", True, "lets the panel float above your chess app"),
                           ("Screen reading", running, "sees the board, nothing is uploaded")]:
        d.text((pad + 26, ry), "✔" if ok else "✖", font=font(26), fill=ACCENT if ok else DANGER)
        d.text((pad + 68, ry - 4), label, font=font(24), fill=TEXT)
        d.text((pad + 68, ry + 28), sub, font=font(19, False), fill=DIM)
        ry += 74

    y += 424
    # problem card
    if problem:
        card(d, pad, y, W - 2 * pad, 250, fill="#2A1620", outline="#6B2B33")
        title(d, pad + 24, y + 18, "PROBLEM", "#FF8A99")
        yy = body(d, pad + 24, y + 58, problem, "#FFD5DA", 24, W - 2 * pad - 48, 32)
        d.rounded_rectangle([pad + 22, yy + 12, W - pad - 22, yy + 86], 18, fill=DANGER)
        d.text((W / 2, yy + 49), "RETRY SCREEN READING", font=font(26), fill="#2A0810", anchor="mm")
        d.text((pad + 24, yy + 100), "Share log", font=font(23), fill=TEXT)
        d.text((W - pad - 140, yy + 100), "Dismiss", font=font(23), fill=TEXT)
        y += 274

    # shortcuts
    card(d, pad, y, W - 2 * pad, 216)
    title(d, pad + 24, y + 18, "SHORTCUTS")
    for i, (a, b) in enumerate([("Fit board", "Fix pieces"), ("New game", "Show my move")]):
        ry2 = y + 62 + i * 74
        for j, label in enumerate((a, b)):
            cx = pad + 22 + j * ((W - 2 * pad - 52) / 2 + 8)
            cw = (W - 2 * pad - 52) / 2
            d.rounded_rectangle([cx, ry2, cx + cw, ry2 + 62], 16, fill=CHIP, outline=BORDER, width=2)
            d.text((cx + cw / 2, ry2 + 31), label, font=font(24), fill=TEXT, anchor="mm")
    y += 240

    # current setup
    card(d, pad, y, W - 2 * pad, 250)
    title(d, pad + 24, y + 18, "CURRENT SETUP")
    yy = y + 62
    for line in ["Marks: Arrow + rings  •  Green / Amber",
                 "Strength: MAX (superhuman)  •  1.5s per hint",
                 "Me: white at the bottom  •  Auto: off",
                 "Position: r1bqk2r/pppp1ppp/2n2n2/…"]:
        d.text((pad + 24, yy), line, font=font(23, False), fill=TEXT); yy += 38
    d.text((pad + 24, yy + 4), "Tap to change →", font=font(23), fill=ACCENT)
    y += 274

    # diagnostics
    card(d, pad, y, W - 2 * pad, 190)
    title(d, pad + 24, y + 18, "DIAGNOSTICS")
    yy = y + 58
    for line in ["screen reading: active, 128 frames",
                 "engine: ready  (hash 16 MB)",
                 "board frame: [60,760][1020,1720]"]:
        d.text((pad + 24, yy), line, font=font(20, False), fill=DIM); yy += 30
    d.text((pad + 24, yy + 8), "Refresh      Copy      Retry screen reading", font=font(21), fill=TEXT)
    img.save(path); print("saved", path)

# ---------------------------------------------------------------- settings screen
def settings(path):
    W, H = 900, 1720
    img = Image.new("RGB", (W, H), BG); d = ImageDraw.Draw(img)
    pad = 30
    d.text((pad, 30), "‹", font=font(44), fill=TEXT)
    d.text((pad + 46, 34), "Settings", font=font(32), fill=TEXT)

    y = 100
    card(d, pad, y, W - 2 * pad, 1010)
    title(d, pad + 24, y + 18, "HOW THE MOVE IS SHOWN")

    # live preview board (with the hint drawn), centred
    S = 62
    bx = (W - 8 * S) // 2
    by = y + 62
    for r in range(8):
        for f in range(8):
            col = "#EDE6D6" if (r + f) % 2 == 0 else "#B08A64"
            d.rectangle([bx + f * S, by + r * S, bx + (f + 1) * S, by + (r + 1) * S], fill=col)
    # knight g1 -> f3
    gx, gy = bx + 6 * S + S / 2, by + 7 * S + S / 2
    fx, fy = bx + 5 * S + S / 2, by + 5 * S + S / 2
    import math as _m
    dx, dy = fx - gx, fy - gy
    L = _m.hypot(dx, dy); ux, uy = dx / L, dy / L
    sx, sy = gx + ux * S * 0.34, gy + uy * S * 0.34
    ex, ey = fx - ux * S * 0.44, fy - uy * S * 0.44
    d.line([sx, sy, ex, ey], fill="#000000", width=26)
    d.line([sx, sy, ex, ey], fill=YELLOW, width=17)
    hl, hw = S * 0.5, S * 0.42
    hx, hy = ex + ux * hl, ey + uy * hl
    d.polygon([(hx, hy), (ex + uy * hw, ey - ux * hw), (ex - uy * hw, ey + ux * hw)],
              fill="#000000")
    d.polygon([(hx - ux * 5, hy - uy * 5), (ex + uy * hw + ux * 4, ey - ux * hw + uy * 4),
               (ex - uy * hw + ux * 4, ey + ux * hw + uy * 4)], fill=YELLOW)
    d.ellipse([gx - S * 0.46, gy - S * 0.46, gx + S * 0.46, gy + S * 0.46], outline=ACCENT, width=9)
    d.rounded_rectangle([fx - S * 0.46, fy - S * 0.46, fx + S * 0.46, fy + S * 0.46], 13,
                        outline=YELLOW, width=9)
    d.text((gx, gy), "♘", font=font(int(S * 0.86)), fill="#111820", anchor="mm")
    # label badge between the two squares
    lx, ly = (gx + fx) / 2, (gy + fy) / 2
    tw = d.textlength("YOUR MOVE  ♘ g1 → f3", font=font(19)) + 26
    d.rounded_rectangle([lx - tw / 2, ly - 20, lx + tw / 2, ly + 20], 13, fill="#0B131C", outline=YELLOW, width=3)
    d.text((lx, ly), "YOUR MOVE  ♘ g1 → f3", font=font(19), fill="#FFFFFF", anchor="mm")

    d.text((W / 2, by + 8 * S + 30), "Live preview of your board", font=font(21, False), fill=DIM, anchor="mm")

    yy = by + 8 * S + 62
    d.text((pad + 24, yy), "Style", font=font(22, False), fill=DIM)
    x = pad + 24
    for i, lab in enumerate(["Arrow + rings", "Arrow only", "Rings only", "Squares"]):
        tw = d.textlength(lab, font=font(20, i == 0)) + 30
        chip(d, x, yy + 32, lab, i == 0, w=tw, size=20)
        x += tw + 10
    yy += 110
    d.text((pad + 24, yy), "Colours", font=font(22, False), fill=DIM)
    x = pad + 24
    for i, lab in enumerate(["Green / Amber", "Blue / Pink"]):
        tw = d.textlength(lab, font=font(20, i == 0)) + 30
        chip(d, x, yy + 32, lab, i == 0, w=tw, size=20)
        x += tw + 10
    yy += 62
    x = pad + 24
    for i, lab in enumerate(["Cyan / Orange", "White / Red", "Lime / Violet"]):
        tw = d.textlength(lab, font=font(20, False)) + 30
        chip(d, x, yy + 32, lab, False, w=tw, size=20)
        x += tw + 10
    yy += 110
    d.text((pad + 24, yy), "Size", font=font(22, False), fill=DIM)
    x = pad + 24
    for i, lab in enumerate(["Small", "Normal", "Large"]):
        tw = d.textlength(lab, font=font(20, i == 1)) + 30
        chip(d, x, yy + 32, lab, i == 1, w=tw, size=20)
        x += tw + 10

    y += 1040
    card(d, pad, y, W - 2 * pad, 300)
    for i, (lab, sub, on) in enumerate([
            ("Move text on the board", "shows the piece and both squares", True),
            ("Engine line", "depth and score under the board", True),
            ("Enemy / You labels", "thin labels above and below", True)]):
        ry = y + 24 + i * 82
        d.text((pad + 26, ry), lab, font=font(25), fill=TEXT)
        d.text((pad + 26, ry + 30), sub, font=font(19, False), fill=DIM)
        switch(d, W - pad - 120, ry + 6, on)

    y += 330
    card(d, pad, y, W - 2 * pad, 330)
    title(d, pad + 24, y + 18, "PLAY")
    d.text((pad + 24, y + 62), "Engine strength", font=font(22, False), fill=DIM)
    x = pad + 24
    for i, lab in enumerate(["MAX", "2850", "2400", "1800"]):
        w = chip(d, x, y + 92, lab, i == 0, size=20)
        x += w + 12
    d.text((pad + 24, y + 162), "Thinking time per hint", font=font(22, False), fill=DIM)
    x = pad + 24
    for i, lab in enumerate(["0.5s", "1s", "1.5s", "3s", "5s"]):
        w = chip(d, x, y + 192, lab, i == 2, size=20)
        x += w + 12
    d.text((pad + 26, y + 262), "Auto hints", font=font(25), fill=TEXT)
    d.text((pad + 26, y + 292), "hint me automatically on my turn", font=font(19, False), fill=DIM)
    switch(d, W - pad - 120, y + 268, False)
    img.save(path); print("saved", path)

# ---------------------------------------------------------------- floating panel
def panel(path):
    W, H = 900, 1180
    img = Image.new("RGB", (W, H), "#12202E"); d = ImageDraw.Draw(img)
    # faint chessboard behind (the game)
    for r in range(8):
        for f in range(8):
            col = "#769656" if (r + f) % 2 == 0 else "#EEEED2"
            d.rectangle([f * 112, r * 112, (f + 1) * 112, (r + 1) * 112], fill=col)
    d.text((40, 940), "chess app behind the panel", font=font(24, False), fill="#20303C")

    px, py, pw = 120, 150, 560
    d.rounded_rectangle([px, py, px + pw, py + 700], 30, fill="#0C141C", outline="#2A3B4E", width=3)
    d.text((px + 28, py + 22), "Stockfish MAX  •  1.5s  •  arrow + rings", font=font(21, False), fill=DIM)

    d.rounded_rectangle([px + 24, py + 60, px + pw - 24, py + 140], 20, fill=ACCENT)
    d.text((px + pw / 2, py + 100), "♞   SHOW MY MOVE", font=font(30), fill="#06210F", anchor="mm")

    tiles = [("AUTO: OFF", "SETTINGS"), ("ME: WHITE", "FIT BOARD"),
             ("FIX PIECES", "NEW GAME"), ("HIDE MARKS", "STOP")]
    for i, row in enumerate(tiles):
        ry = py + 164 + i * 92
        for j, lab in enumerate(row):
            tx = px + 24 + j * ((pw - 48) / 2 + 8)
            tw = (pw - 48) / 2
            col = DANGER if lab == "STOP" else TEXT
            d.rounded_rectangle([tx, ry, tx + tw, ry + 76], 18, fill="#15212C", outline="#26374A", width=2)
            d.text((tx + tw / 2, ry + 38), lab, font=font(22), fill=col, anchor="mm")
    d.text((px + pw / 2, py + 560), "2 tiles per row — drag the ♞ ball to move it",
           font=font(21, False), fill=DIM, anchor="mm")
    d.ellipse([px + pw - 120, py + 600, px + pw - 20, py + 700], fill="#0B1B2A", outline=YELLOW, width=4)
    d.text((px + pw - 70, py + 646), "♞", font=font(44), fill="#FFFFFF", anchor="mm")
    d.text((px + pw - 70, py + 726), "HINT", font=font(22), fill=YELLOW, anchor="mm")
    img.save(path); print("saved", path)

# ---------------------------------------------------------------- calibration + editor
def calibration(path):
    W, H = 900, 1500
    img = Image.new("RGB", (W, H), "#101820"); d = ImageDraw.Draw(img)
    for r in range(8):
        for f in range(8):
            col = "#769656" if (r + f) % 2 == 0 else "#EEEED2"
            d.rectangle([90 + f * 90, 260 + r * 90, 90 + (f + 1) * 90, 260 + (r + 1) * 90], fill=col)
    d.rectangle([0, 0, W, 260], fill="#000000")
    d.rectangle([0, 980, W, H], fill="#000000")
    bx, by, S = 90, 260, 90
    for k in range(1, 8):
        d.line([bx + k * S, by, bx + k * S, by + 8 * S], fill=(255, 212, 0, 120), width=2)
        d.line([bx, by + k * S, bx + 8 * S, by + k * S], fill=(255, 212, 0, 120), width=2)
    d.rectangle([bx, by, bx + 8 * S, by + 8 * S], outline=YELLOW, width=5)
    for cx, cy in [(bx, by), (bx + 8 * S, by), (bx, by + 8 * S), (bx + 8 * S, by + 8 * S)]:
        d.rectangle([cx - 14, cy - 14, cx + 14, cy + 14], fill=ACCENT)
    d.text((W / 2, 60), "Drag the frame exactly over the chess board", font=font(30), fill="#FFFFFF", anchor="mm")
    d.text((W / 2, 110), "corners = resize  •  middle = move  •  empty area = new frame",
           font=font(23, False), fill="#B8CAD9", anchor="mm")
    for i, (lab, bg, fg) in enumerate([("AUTO DETECT", "#1B2C3E", "#7FD4FF"),
                                       ("✔  SAVE", ACCENT, "#06210F"),
                                       ("✕  CANCEL", "#3A1620", "#FF9AA5")]):
        bx2 = 40 + i * ((W - 80) / 3 + 6)
        bw = (W - 80) / 3
        d.rounded_rectangle([bx2, H - 140, bx2 + bw, H - 70], 18, fill=bg)
        d.text((bx2 + bw / 2, H - 105), lab, font=font(24), fill=fg, anchor="mm")
    img.save(path); print("saved", path)

def editor(path):
    W, H = 900, 1500
    img = Image.new("RGB", (W, H), "#101018"); d = ImageDraw.Draw(img)
    S = 88
    bx, by = (W - 8 * S) // 2, 300
    d.rectangle([bx - 4, by - 4, bx + 8 * S + 4, by + 8 * S + 4], fill="#FFFFFF")
    for r in range(8):
        for f in range(8):
            col = "#E9E2D0" if (r + f) % 2 == 0 else "#9C7A54"
            d.rectangle([bx + f * S, by + r * S, bx + (f + 1) * S, by + (r + 1) * S], fill=col)
    # a plausible middlegame position
    pieces = {(7,7):'r',(7,6):'n',(7,5):'b',(7,4):'q',(7,3):'k',(7,0):'r',
              (6,0):'p',(6,1):'p',(6,2):'p',(6,4):'p',(6,5):'p',(6,6):'p',(6,7):'p',
              (5,2):'n',(5,3):'b',(4,3):'p',(4,4):'P',(5,5):'N',(5,3):'b',
              (3,4):'p',(0,7):'R',(0,6):'N',(0,5):'B',(0,4):'Q',(0,3):'K',(0,0):'R',
              (1,0):'P',(1,1):'P',(1,2):'P',(1,4):'P',(1,5):'P',(1,6):'P',(1,7):'P'}
    for (r, f), ch in pieces.items():
        white = ch.isupper()
        g = {'p':'♟','n':'♞','b':'♝','r':'♜','q':'♛','k':'♚'}[ch.lower()]
        cx, cy = bx + f * S + S / 2, by + r * S + S * 0.53
        d.text((cx, cy), g, font=font(int(S * 0.8)), fill="#FFFFFF" if white else "#131C26",
               anchor="mm", stroke_width=3, stroke_fill="#101820" if white else "#E8EFF6")
    d.text((W / 2, 200), "Tap a square to change the piece", font=font(30), fill="#FFFFFF", anchor="mm")
    d.text((W / 2, 250), "● empty → ♙ ♘ ♗ ♖ ♕ ♔ (white) → ♟ ♞ ♝ ♜ ♛ ♚ (black) → ○",
           font=font(21, False), fill="#B8CAD9", anchor="mm")
    d.rounded_rectangle([W / 2 - 200, by + 8 * S + 60, W / 2 + 200, by + 8 * S + 140], 20, fill=ACCENT)
    d.text((W / 2, by + 8 * S + 100), "✔  USE THIS POSITION", font=font(26), fill="#06210F", anchor="mm")
    img.save(path); print("saved", path)

if __name__ == "__main__":
    home(f"{OUT}/app-1-home.png")
    home(f"{OUT}/app-2-problem.png", running=False,
         problem="Screen reading refused: SecurityException: Media projections require a "
                 "foreground service of type FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION")
    settings(f"{OUT}/app-3-settings.png")
    panel(f"{OUT}/app-4-panel.png")
    calibration(f"{OUT}/app-5-fit-board.png")
    editor(f"{OUT}/app-6-fix-pieces.png")
