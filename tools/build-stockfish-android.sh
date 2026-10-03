#!/usr/bin/env bash
# Compiles official Stockfish 11 into libstockfish.so for Android (arm64, armv7, x86_64).
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$ANDROID_HOME/ndk-bundle}}"
[ -d "$NDK" ] || { echo "Set ANDROID_NDK_HOME to your NDK (r26/r27)"; exit 1; }
HOST_TAG=linux-x86_64; [ "$(uname)" = "Darwin" ] && HOST_TAG=darwin-x86_64
NDKBIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"

WORK="${SF_WORK_DIR:-$ROOT/build/sf}"
SRC="$WORK/Stockfish-sf_11/src"
OUT="$ROOT/build/jnilibs"
mkdir -p "$WORK" "$OUT"

if [ ! -d "$SRC" ]; then
  echo "== fetching Stockfish 11 sources =="
  curl -sL -o "$WORK/sf11.tar.gz" https://github.com/official-stockfish/Stockfish/archive/refs/tags/sf_11.tar.gz
  tar xzf "$WORK/sf11.tar.gz" -C "$WORK"
fi

SRCS="benchmark bitbase bitboard endgame evaluate material misc movegen movepick pawns position psqt search thread timeman tt uci ucioption syzygy/tbprobe"
COMMON="-std=c++11 -O3 -DNDEBUG -fno-exceptions -fno-rtti -fPIC -Wall -Wno-unused-result -I$SRC"

build_abi () {
  NAME=$1; CXX=$2; EXTRA=$3
  MK="$(mktemp -d)"
  echo "== $NAME =="
  for s in $SRCS; do
    echo "$NDKBIN/$CXX $COMMON $EXTRA -c $SRC/$s.cpp -o $MK/$(basename "$s").o"
  done > "$MK/cmds.txt"
  echo "$NDKBIN/$CXX $COMMON $EXTRA -c $ROOT/native/jni_bridge.cpp -o $MK/jni_bridge.o" >> "$MK/cmds.txt"
  xargs -P "$(nproc 2>/dev/null || echo 2)" -I{} bash -c '{}' < "$MK/cmds.txt"
  OBJS=""; for s in $SRCS; do OBJS="$OBJS $MK/$(basename "$s").o"; done
  $NDKBIN/$CXX -shared -Wl,-soname,libstockfish.so $COMMON $EXTRA $OBJS "$MK/jni_bridge.o" -llog -o "$OUT/libstockfish.so.$NAME"
  $NDKBIN/llvm-strip --strip-unneeded "$OUT/libstockfish.so.$NAME"
  rm -rf "$MK"
  ls -la "$OUT/libstockfish.so.$NAME"
}

build_abi arm64-v8a   aarch64-linux-android24-clang++    "-DIS_64BIT"
build_abi armeabi-v7a armv7a-linux-androideabi24-clang++ "-march=armv7-a -mfpu=neon -mfloat-abi=softfp -mthumb"
build_abi x86_64      x86_64-linux-android24-clang++     "-DIS_64BIT -msse2 -mpopcnt -DUSE_SSE2 -DUSE_POPCNT"
echo "done -> $OUT"
