#!/usr/bin/env python3
"""Generate synthetic phone screenshots of chess apps to test the screen reader."""
import os, math, random
from PIL import Image, ImageDraw

OUT = "/home/user/build/test/boards"
os.makedirs(OUT, exist_ok=True)

START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
MID   = "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R"
ENDG  = "8/5k2/8/4P3/8/2K5/8/6r1"
PROMO = "r3k3/1P6/8/8/8/8/6p1/4K2R"

def fen_to_board(fen):
    b = {}
    rows = fen.split('/')
    for r, row in enumerate(rows):
        f = 0
        for ch in row:
            if ch.isdigit():
                f += int(ch)
            else:
                b[(r, f)] = ch          # r=0 is rank 8
                f += 1
    return b

# ---------------------------------------------------------------- piece shapes
def draw_piece(d, cx, cy, s, kind, white):
    fill = (250, 250, 248) if white else (28, 28, 32)
    line = (35, 35, 40) if white else (235, 235, 235)
    lw = max(2, int(s * 0.030))
    h = s * 0.78          # total piece height
    top = cy - h * 0.5
    bot = cy + h * 0.5
    w = s * 0.60

    def ell(box, f=fill):
        d.ellipse(box, fill=f, outline=line, width=lw)

    def poly(pts, f=fill):
        d.polygon(pts, fill=f, outline=line, width=lw)

    def rect(box, f=fill):
        d.rectangle(box, fill=f, outline=line, width=lw)

    if kind == 'p':        # pawn
        bodyw = w * 0.62
        ell([cx - bodyw * 0.30, top + h * 0.06, cx + bodyw * 0.30, top + h * 0.06 + bodyw * 0.60])
        poly([(cx - bodyw * 0.42, bot - h * 0.10), (cx + bodyw * 0.42, bot - h * 0.10),
              (cx + bodyw * 0.28, top + h * 0.34), (cx - bodyw * 0.28, top + h * 0.34)])
        rect([cx - bodyw * 0.62, bot - h * 0.12, cx + bodyw * 0.62, bot])
    elif kind == 'r':      # rook
        rect([cx - w * 0.34, top + h * 0.05, cx + w * 0.34, bot - h * 0.14])
        for k in (-1, 0, 1):
            rect([cx + k * w * 0.32 - w * 0.10, top, cx + k * w * 0.32 + w * 0.10, top + h * 0.16])
        rect([cx - w * 0.46, bot - h * 0.16, cx + w * 0.46, bot])
    elif kind == 'n':      # knight
        poly([(cx - w * 0.34, bot - h * 0.14), (cx + w * 0.30, bot - h * 0.14),
              (cx + w * 0.40, top + h * 0.30), (cx + w * 0.18, top + h * 0.02),
              (cx - w * 0.04, top + h * 0.16), (cx - w * 0.36, top + h * 0.42)])
        rect([cx - w * 0.46, bot - h * 0.16, cx + w * 0.46, bot])
    elif kind == 'b':      # bishop
        ell([cx - w * 0.20, top, cx + w * 0.20, top + h * 0.28])
        poly([(cx - w * 0.34, bot - h * 0.12), (cx + w * 0.34, bot - h * 0.12),
              (cx + w * 0.16, top + h * 0.26), (cx - w * 0.16, top + h * 0.26)])
        rect([cx - w * 0.46, bot - h * 0.14, cx + w * 0.46, bot])
    elif kind == 'q':      # queen
        el = [cx - w * 0.30, top + h * 0.04, cx + w * 0.30, top + h * 0.30]
        ell(el)
        for k in range(-2, 3):
            d.ellipse([cx + k * w * 0.26 - w * 0.09, top - h * 0.06,
                       cx + k * w * 0.26 + w * 0.09, top + h * 0.12], fill=fill, outline=line, width=lw)
        poly([(cx - w * 0.44, bot - h * 0.12), (cx + w * 0.44, bot - h * 0.12),
              (cx + w * 0.20, top + h * 0.28), (cx - w * 0.20, top + h * 0.28)])
        rect([cx - w * 0.50, bot - h * 0.14, cx + w * 0.50, bot])
    elif kind == 'k':      # king
        ell([cx - w * 0.28, top + h * 0.10, cx + w * 0.28, top + h * 0.36])
        rect([cx - w * 0.06, top - h * 0.04, cx + w * 0.06, top + h * 0.16])
        rect([cx - w * 0.16, top + h * 0.02, cx + w * 0.16, top + h * 0.11])
        poly([(cx - w * 0.44, bot - h * 0.12), (cx + w * 0.44, bot - h * 0.12),
              (cx - w * 0.42, bot - h * 0.42), (cx + w * 0.42, bot - h * 0.42)])
        rect([cx - w * 0.50, bot - h * 0.14, cx + w * 0.50, bot])

# ---------------------------------------------------------------- board drawing
def draw_board(img, rect, fen, light, dark, highlight=(), dots=(), coords=True,
               check_sq=None, dot_color=(0, 0, 0, 90)):
    d = ImageDraw.Draw(img, "RGBA")
    x, y, s = rect
    sq = s / 8.0
    board = fen_to_board(fen)
    for r in range(8):
        for f in range(8):
            xx, yy = x + f * sq, y + r * sq
            col = light if (r + f) % 2 == 0 else dark
            d.rectangle([xx, yy, xx + sq, yy + sq], fill=col)
    for (hf, hr) in highlight:                    # last move highlight
        xx, yy = x + hf * sq, y + hr * sq
        d.rectangle([xx, yy, xx + sq, yy + sq], fill=(255, 240, 120, 110))
    if check_sq:
        hf, hr = check_sq
        xx, yy = x + hf * sq, y + hr * sq
        d.rectangle([xx, yy, xx + sq, yy + sq], fill=(255, 60, 60, 120))
    if coords:
        for f in range(8):
            d.text((x + f * sq + sq * 0.06, y + s - sq * 0.20), chr(ord('a') + f),
                   fill=(70, 70, 70) if light[0] > 150 else (230, 230, 230))
        for r in range(8):
            d.text((x + sq * 0.06, y + r * sq + sq * 0.06), str(8 - r),
                   fill=(70, 70, 70) if light[0] > 150 else (230, 230, 230))
    for (r, f), ch in board.items():
        kind = ch.lower()
        white = ch.isupper()
        cx = x + f * sq + sq / 2
        cy = y + r * sq + sq * 0.52
        draw_piece(d, cx, cy, sq, kind, white)
    for (df, dr) in dots:                          # legal move marker
        cx = x + df * sq + sq / 2
        cy = y + dr * sq + sq / 2
        d.ellipse([cx - sq * 0.16, cy - sq * 0.16, cx + sq * 0.16, cy + sq * 0.16], fill=dot_color)

def chrome(w, h, title, bg=(24, 24, 28), board_top_ratio=None):
    img = Image.new("RGB", (w, h), bg)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, w, int(h * 0.055)], fill=(36, 36, 42))
    d.text((20, 24), title, fill=(220, 220, 220))
    for i in range(3):
        d.text((30, int(h * 0.11) + i * 40), "player name 1200", fill=(150, 150, 160))
    d.rectangle([int(w * 0.06), int(h * 0.93), int(w * 0.94), int(h * 0.97)], fill=(40, 40, 48))
    return img

def expected_pattern(fen):
    b = fen_to_board(fen)
    pat = []
    for r in range(8):
        for f in range(8):
            ch = b.get((r, f))
            pat.append('0' if ch is None else ('1' if ch.isupper() else '2'))
    return ''.join(pat)

def save(name, img, rect, fen, note=""):
    x, y, s = rect
    with open(os.path.join(OUT, name + ".raw"), "wb") as fp:
        fp.write(img.tobytes())
    with open(os.path.join(OUT, name + ".txt"), "w") as fp:
        fp.write("%d %d %d %d %d\n%s\n%s\n" % (img.width, img.height, x, y, s, expected_pattern(fen), fen))
    print("wrote", name, img.size, "board", rect, note)

W, H = 1080, 2340

# 1 - chess.com style, start position, last move highlighted + move dots
img = chrome(W, H, "chess.com - rapid 10+0")
r = (60, 760, 960)
draw_board(img, r, START, (238, 238, 210), (118, 150, 86),
           highlight=[(4, 6), (4, 4)], dots=[(5, 5), (1, 5)])
save("chesscom_start", img, r, START, "green theme, highlights + dots")

# 2 - lichess brown, middlegame
img = chrome(W, H, "lichess.org - game", bg=(30, 28, 26))
r = (90, 700, 900)
draw_board(img, r, MID, (240, 217, 181), (181, 136, 99),
           highlight=[(2, 2), (3, 3)], dots=[(5, 2)])
save("lichess_mid", img, r, MID, "brown theme")

# 3 - dark theme, low contrast black pieces
img = chrome(W, H, "Chess App - dark", bg=(12, 12, 16))
r = (50, 820, 980)
draw_board(img, r, MID, (74, 74, 88), (52, 52, 64), highlight=[(4, 3)])
save("dark_theme", img, r, MID, "dark board, black pieces on dark squares")

# 4 - blue/grey theme endgame, small board
img = chrome(W, H, "Play Chess - endgame", bg=(240, 240, 245))
r = (150, 900, 780)
draw_board(img, r, ENDG, (222, 227, 230), (140, 162, 173), check_sq=(5, 2))
save("blue_endgame", img, r, ENDG, "blue theme, king in check, small board")

# 5 - promotion position, board high on screen
img = chrome(W, H, "My Chess", bg=(18, 26, 34))
r = (30, 300, 1020)
draw_board(img, r, PROMO, (235, 236, 208), (130, 151, 105))
save("promotion", img, r, PROMO, "wide board near the top")

# 6 - negative test: no board at all
img = chrome(W, H, "Chat app")
d = ImageDraw.Draw(img)
for i in range(12):
    d.rectangle([40, 300 + i * 120, 1040, 380 + i * 120], fill=(40, 42, 50))
save("no_board", img, (0, 0, 0), ".", "negative test")

# 7 - board on a busy background (thumbnail strip + board)
img = chrome(W, H, "Chess Arena")
d = ImageDraw.Draw(img)
for i in range(4):
    d.rectangle([40 + i * 250, 150, 250 + i * 250, 330], fill=(60, 70, 90))
r = (140, 900, 800)
draw_board(img, r, MID, (240, 217, 181), (181, 136, 99), dots=[(3, 4)])
save("busy_bg", img, r, MID, "busy background")
print("done")
