#!/usr/bin/env bash
# Linux/macOS build of the exact same JNI library - used by the desktop tests.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${SF_WORK_DIR:-$ROOT/build/sf}"
SRC="$WORK/Stockfish-sf_11/src"
OUT="$ROOT/build/host"; mkdir -p "$OUT"
if [ ! -d "$SRC" ]; then
  mkdir -p "$WORK"
  curl -sL -o "$WORK/sf11.tar.gz" https://github.com/official-stockfish/Stockfish/archive/refs/tags/sf_11.tar.gz
  tar xzf "$WORK/sf11.tar.gz" -C "$WORK"
fi
JH="${JAVA_HOME:-}"; INC=""; [ -n "$JH" ] && INC="-I$JH/include -I$JH/include/linux"
SRCS="benchmark bitbase bitboard endgame evaluate material misc movegen movepick pawns position psqt search thread timeman tt uci ucioption syzygy/tbprobe"
FLAGS="-std=c++11 -O1 -DNDEBUG -fPIC -w -I$SRC $INC"
MK="$OUT/obj"; mkdir -p "$MK"
for s in $SRCS; do echo "$MK/$(basename "$s").o"; done > "$MK/list.txt"
for s in $SRCS; do g++ $FLAGS -c "$SRC/$s.cpp" -o "$MK/$(basename "$s").o"; done
g++ $FLAGS -c "$ROOT/native/jni_bridge.cpp" -o "$MK/jni_bridge.o"
OBJS=""; for s in $SRCS; do OBJS="$OBJS $MK/$(basename "$s").o"; done
g++ -shared $FLAGS $OBJS "$MK/jni_bridge.o" -o "$OUT/libstockfish.so"
ls -la "$OUT/libstockfish.so"
