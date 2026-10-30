#!/usr/bin/env bash
# Builds camhwinfo.apk without Gradle.
# Requires: JDK 17+, ANDROID_HOME with platforms/android-34 and build-tools;34.0.0
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_HOME="${ANDROID_HOME:?set ANDROID_HOME}"
BT="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR"; exit 1; }

SRC=app/src/main/java
MANIFEST=app/src/main/AndroidManifest.xml
RES=app/src/main/res
OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "[1/6] aapt2 compile + link (resources + manifest)"
"$BT/aapt2" compile --dir "$RES" -o "$OUT/res.zip"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" \
    --min-sdk-version 16 \
    --target-sdk-version 22 \
    --auto-add-overlay \
    "$OUT/res.zip"

echo "[2/6] javac"
find "$SRC" -name '*.java' > "$OUT/sources.txt"
javac \
    -source 8 -target 8 \
    -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" \
    -nowarn \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "[3/6] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" \
    --release \
    --min-api 16 \
    --lib "$ANDROID_JAR" \
    --output "$OUT" \
    $(cat "$OUT/classes.txt")

echo "[4/6] add classes.dex"
(cd "$OUT" && zip -q base.apk classes.dex)

echo "[5/6] zipalign"
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
if [ ! -f keystore.jks ]; then
    keytool -genkeypair -keystore keystore.jks -storepass android \
        -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign \
    --ks keystore.jks --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/camhwinfo.apk" \
    "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT/camhwinfo.apk" | head -5

echo
echo "APK: $PWD/$OUT/camhwinfo.apk"
ls -la "$OUT/camhwinfo.apk"
