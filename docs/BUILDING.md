# Building

## Requirements
* JDK 17
* Android SDK: build-tools 34.0.0 + `platforms;android-34`
* Android NDK r26/r27 (only to build the engine)
* Python 3 + Pillow (only for the synthetic test screenshots)

## 1. Engine
```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r27c
tools/build-stockfish-android.sh      # downloads Stockfish 11, builds arm64/armv7/x86_64
```
Output: `build/jnilibs/libstockfish.so.<abi>`

## 2. App
```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0
export ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
tools/build-apk.sh       # aapt2 + javac + d8
tools/package-apk.sh     # adds libs, aligns, signs (creates a keystore on first run)
```
Output: `build/out/ChessHintPanel-<version>.apk`

## 3. Tests (desktop JVM)
```bash
tools/build-stockfish-host.sh                 # Linux build of the same JNI library
cd tools/tests
javac -d classes -sourcepath ../../app/java:. CoreTest.java VisionTest.java
java -Djava.library.path=../../build/host -cp classes:../../app/java CoreTest
java -cp classes:../../app/java VisionTest   # needs the synthetic images, see gen_boards.py
```

## CI
`.github/workflows/build.yml` builds the APK and uploads it as a workflow artifact on every
push to `main` and on every tag (tags also get a GitHub Release draft).
