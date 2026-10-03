#!/bin/bash
set -e
APP=/home/user/build/app
BT=/home/user/.cache/work/sdk/build-tools/34.0.0
AJAR=/home/user/.cache/work/sdk/platforms/android-34/android.jar
JDK=/home/user/.cache/work/jdk
OUT=/home/user/build/out
export JAVA_HOME=$JDK
export PATH=$JDK/bin:$PATH
rm -rf $OUT/gen $OUT/classes $OUT/res.zip $OUT/dex
mkdir -p $OUT/gen $OUT/classes $OUT/dex

echo "== aapt2 compile resources =="
$BT/aapt2 compile --dir $APP/res -o $OUT/res.zip

echo "== aapt2 link =="
$BT/aapt2 link -o $OUT/base.apk -I $AJAR --manifest $APP/AndroidManifest.xml \
  --java $OUT/gen --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 4 --version-name 1.3 --auto-add-overlay $OUT/res.zip

echo "== javac =="
find $APP/java $OUT/gen -name '*.java' > $OUT/sources.txt
$JDK/bin/javac -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 -classpath $AJAR -d $OUT/classes @$OUT/sources.txt
if [ $? -ne 0 ]; then echo "JAVAC FAILED"; exit 1; fi
ls $OUT/classes/com/chesshint/panel/ | head -30

echo "== d8 =="
find $OUT/classes -name '*.class' > $OUT/classlist.txt
$BT/d8 --release --lib $AJAR --min-api 26 --output $OUT/dex @$OUT/classlist.txt
ls -la $OUT/dex
echo "BUILD OK"
