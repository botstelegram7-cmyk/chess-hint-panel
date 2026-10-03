#!/usr/bin/env bash
# compiles the Java sources + resources -> build/out/base.apk and build/out/dex/classes.dex
# works from any checkout location; override with ANDROID_HOME / ANDROID_BUILD_TOOLS / ANDROID_JAR / JAVA_HOME
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
AJAR="${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}"
JDK_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
OUT="$ROOT/build/out"
APP="$ROOT/app"
VER_CODE="${VERSION_CODE:-6}"
VER_NAME="${VERSION_NAME:-1.5}"
JAVAC="$JDK_HOME/bin/javac"; [ -x "$JAVAC" ] || JAVAC=javac
KEYTOOL="$JDK_HOME/bin/keytool"; [ -x "$KEYTOOL" ] || KEYTOOL=keytool
rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== resources =="
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJAR" --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VER_CODE" --version-name "$VER_NAME" --auto-add-overlay "$OUT/res.zip"

echo "== java =="
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 -classpath "$AJAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== dex =="
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
"$BT/d8" --release --lib "$AJAR" --min-api 26 --output "$OUT/dex" @"$OUT/classlist.txt"
echo "OK -> $OUT/base.apk + $OUT/dex/classes.dex"
