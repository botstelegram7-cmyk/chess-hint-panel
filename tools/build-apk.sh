#!/usr/bin/env bash
# Compiles the Java sources and links resources -> build/out/classes.dex + base.apk
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
AJAR="${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}"
JDK_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
OUT="$ROOT/build/out"
APP="$ROOT/app"
DJAVAC="$JDK_HOME/bin/javac"; [ -x "$DJAVAC" ] || DJAVAC=javac
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== resources =="
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJAR" --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 34 --version-code 3 --version-name 1.2 \
  --auto-add-overlay "$OUT/res.zip"

echo "== java =="
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$DJAVAC" -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 -classpath "$AJAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== dex =="
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
"$BT/d8" --release --lib "$AJAR" --min-api 26 --output "$OUT/dex" @"$OUT/classlist.txt"
echo "OK -> $OUT/base.apk + $OUT/dex/classes.dex"
