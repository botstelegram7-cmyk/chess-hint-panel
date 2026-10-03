#!/bin/bash
set -e
BT=/home/user/.cache/work/sdk/build-tools/34.0.0
JDK=/home/user/.cache/work/jdk
export PATH=$JDK/bin:$PATH
OUT=/home/user/build/out
STAGE=$OUT/stage
mkdir -p $STAGE/lib/arm64-v8a $STAGE/lib/armeabi-v7a $STAGE/lib/x86_64
cp $OUT/dex/classes.dex $STAGE/
cp /home/user/build/jnilibs/libstockfish.so.arm64-v8a   $STAGE/lib/arm64-v8a/libstockfish.so
cp /home/user/build/jnilibs/libstockfish.so.armeabi-v7a $STAGE/lib/armeabi-v7a/libstockfish.so
cp /home/user/build/jnilibs/libstockfish.so.x86_64      $STAGE/lib/x86_64/libstockfish.so
chmod 755 $STAGE/lib/*/libstockfish.so

rm -f $OUT/unsigned.apk $OUT/aligned.apk $OUT/ChessHintPanel-v1.3.apk
cp $OUT/base.apk $OUT/unsigned.apk
( cd $STAGE && zip -q -r -X $OUT/unsigned.apk classes.dex lib )
$BT/zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk

KS=/home/user/build/keystore.jks
if [ ! -f $KS ]; then
  keytool -genkeypair -keystore $KS -alias chesshint -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass chesshint -keypass chesshint -dname "CN=Chess Hint Panel, O=Chess Hint, C=IN" 2>/dev/null
fi
$BT/apksigner sign --ks $KS --ks-pass pass:chesshint --key-pass pass:chesshint \
   --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
   --out $OUT/ChessHintPanel-v1.3.apk $OUT/aligned.apk
$BT/apksigner verify --print-certs $OUT/ChessHintPanel-v1.3.apk
echo "---- contents ----"
unzip -l $OUT/ChessHintPanel-v1.3.apk
ls -la $OUT/ChessHintPanel-v1.3.apk
