# FAQ

**Does it work with any chess app?**
Yes. It never talks to the chess app; it reads the screen with Android's own screen-capture
API and finds the board like a human eye would. Any theme, any app, online or offline.

**Will it tell me the opponent's move?**
No. The bottom half of the board is treated as *you*; the engine is asked only for your
side's move. While it is the enemy's turn the panel shows "Waiting for the enemy…".

**Why does it ask me to fix the pieces the first time?**
The screen reader can see *that* a square has a piece and *which colour* it is, but not
reliably whether it is a bishop or a pawn. So the panel starts from a known position and
then follows every move exactly — piece types then never drift. If you join a game in the
middle, set the pieces once with FIX POSITION.

**Is 8000 Elo possible?**
No engine on earth reaches 8000. The strongest measured engines are ~3600 and Stockfish at
full strength is exactly that. The panel gives you MAX (superhuman) and lets you *weaken* it
if you want a fair game.

**Does it need internet?**
No. The app has no internet permission at all. The engine is inside the APK.

**Battery?**
Auto mode captures a frame once a second and only thinks when it is your turn. Turn Auto off
(or STOP the panel) when you do not need it.

**Is it allowed by the chess sites?**
Using engine assistance during online games breaks the rules of every chess platform and can
get your account banned. This tool is meant for **analysis, learning, and offline games** —
use it responsibly.

**Where is the crash log?**
App → DIAGNOSTICS → *Copy*.
