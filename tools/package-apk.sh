#!/usr/bin/env bash
# packs dex + the native engine libs into the APK, aligns, signs (v1+v2+v3) -> build/out/ChessHintPanel-v<ver>.apk
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
JDK_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
KEYTOOL="$JDK_HOME/bin/keytool"; [ -x "$KEYTOOL" ] || KEYTOOL=keytool
# make sure apksigner/keytool run on the same JDK (avoids "Algorithm HmacPBESHA256 not available")
export PATH="$JDK_HOME/bin:$PATH"
KS="${KEYSTORE:-$ROOT/build/keystore.jks}"
PASS="${KEYSTORE_PASS:-chesshint}"
OUT="$ROOT/build/out"
LIBS="$ROOT/build/jnilibs"
VER="${VERSION_NAME:-1.6}"
STAGE="$OUT/stage"
[ -f "$OUT/dex/classes.dex" ] || { echo "run tools/build-apk.sh first"; exit 1; }
[ -f "$LIBS/libstockfish.so.arm64-v8a" ] || { echo "run tools/build-stockfish-android.sh first"; exit 1; }
rm -rf "$STAGE"; mkdir -p "$STAGE/lib/arm64-v8a" "$STAGE/lib/armeabi-v7a" "$STAGE/lib/x86_64"
cp "$OUT/dex/classes.dex" "$STAGE/"
cp "$LIBS/libstockfish.so.arm64-v8a"   "$STAGE/lib/arm64-v8a/libstockfish.so"
cp "$LIBS/libstockfish.so.armeabi-v7a" "$STAGE/lib/armeabi-v7a/libstockfish.so"
cp "$LIBS/libstockfish.so.x86_64"      "$STAGE/lib/x86_64/libstockfish.so"
chmod 755 "$STAGE"/lib/*/libstockfish.so

rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/ChessHintPanel-v$VER.apk"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$STAGE" && zip -q -r -X "$OUT/unsigned.apk" classes.dex lib )
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias chesshint -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=Chess Hint Panel, O=Chess Hint, C=IN"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/ChessHintPanel-v$VER.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/ChessHintPanel-v$VER.apk"
echo "APK -> $OUT/ChessHintPanel-v$VER.apk"
