#!/usr/bin/env bash
# Packs dex + native engine libs into the APK, zipaligns, and signs (v1+v2+v3) -> build/out/ChessHintPanel-v<ver>.apk
# Uses a persistent signing keystore configured via environment variables or ~/.signing/signing.env
# so all future APK builds share the same signing identity and install cleanly over existing versions.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
JDK_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
KEYTOOL="$JDK_HOME/bin/keytool"; [ -x "$KEYTOOL" ] || KEYTOOL=keytool
export PATH="$JDK_HOME/bin:$PATH"

# Load local git-ignored signing environment if present
if [ -f "$ROOT/.signing.env" ]; then
  # shellcheck disable=SC1090
  . "$ROOT/.signing.env"
elif [ -f "$HOME/.signing/signing.env" ]; then
  # shellcheck disable=SC1090
  . "$HOME/.signing/signing.env"
fi

SIGN_DIR="${SIGNING_DIR:-$HOME/.signing}"
mkdir -p "$SIGN_DIR"
chmod 700 "$SIGN_DIR" 2>/dev/null || true

KS="${ANDROID_KEYSTORE_PATH:-${KEYSTORE:-$SIGN_DIR/chesshint-release.jks}}"
ALIAS="${ANDROID_KEY_ALIAS:-chesshint}"
STORE_PASS="${ANDROID_KEYSTORE_PASS:-${KEYSTORE_PASS:-}}"
KEY_PASS="${ANDROID_KEY_PASS:-$STORE_PASS}"

# Decode base64 keystore if provided by CI secret (e.g. GitHub Actions)
if [ -n "${ANDROID_KEYSTORE_BASE64:-}" ] && [ ! -f "$KS" ]; then
  printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KS"
  chmod 600 "$KS"
fi

# Generate and persist a random secret in ~/.signing/signing.env if none was provided
if [ -z "$STORE_PASS" ]; then
  ENV_FILE="$SIGN_DIR/signing.env"
  if [ -f "$ENV_FILE" ]; then
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    STORE_PASS="${ANDROID_KEYSTORE_PASS:-}"
    KEY_PASS="${ANDROID_KEY_PASS:-$STORE_PASS}"
  fi
  if [ -z "$STORE_PASS" ]; then
    STORE_PASS="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
    KEY_PASS="$STORE_PASS"
    cat > "$ENV_FILE" <<EOF
export ANDROID_KEYSTORE_PATH="$KS"
export ANDROID_KEY_ALIAS="$ALIAS"
export ANDROID_KEYSTORE_PASS="$STORE_PASS"
export ANDROID_KEY_PASS="$KEY_PASS"
EOF
    chmod 600 "$ENV_FILE"
  fi
fi

OUT="$ROOT/build/out"
LIBS="$ROOT/build/jnilibs"
VER="${VERSION_NAME:-2.2}"
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
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" -dname "CN=Chess Hint Panel, O=Chess Hint, C=IN"
  chmod 600 "$KS"
fi

export STORE_PASS KEY_PASS
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass "env:STORE_PASS" --key-pass "env:KEY_PASS" \
  --min-sdk-version 21 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/ChessHintPanel-v$VER.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/ChessHintPanel-v$VER.apk"
echo "APK -> $OUT/ChessHintPanel-v$VER.apk"
