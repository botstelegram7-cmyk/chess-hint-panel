#!/usr/bin/env bash
# Packs the dex + native engine libs into the APK, aligns and signs it.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
KS="${KEYSTORE:-$ROOT/build/keystore.jks}"
PASS="${KEYSTORE_PASS:-chesshint}"
OUT="$ROOT/build/out"
VER=1.2
STAGE="$OUT/stage"
rm -rf "$STAGE"; mkdir -p "$STAGE/lib/arm64-v8a" "$STAGE/lib/armeabi-v7a" "$STAGE/lib/x86_64"
cp "$OUT/dex/classes.dex" "$STAGE/"
cp "$ROOT/build/jnilibs/libstockfish.so.arm64-v8a"   "$STAGE/lib/arm64-v8a/libstockfish.so"
cp "$ROOT/build/jnilibs/libstockfish.so.armeabi-v7a" "$STAGE/lib/armeabi-v7a/libstockfish.so"
cp "$ROOT/build/jnilibs/libstockfish.so.x86_64"      "$STAGE/lib/x86_64/libstockfish.so"
chmod 755 "$STAGE"/lib/*/libstockfish.so

rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/ChessHintPanel-v$VER.apk"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$STAGE" && zip -q -r -X "$OUT/unsigned.apk" classes.dex lib )
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias chesshint -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=Chess Hint Panel, O=Chess Hint, C=IN"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/ChessHintPanel-v$VER.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/ChessHintPanel-v$VER.apk"
echo "APK -> $OUT/ChessHintPanel-v$VER.apk"
