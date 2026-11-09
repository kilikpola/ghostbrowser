#!/usr/bin/env bash
# Builds a signed Ghostbrowser APK with the Android SDK tools directly (no Gradle needed).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/android-34/android.jar"
[ -f "$JAR" ] || JAR="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
VERSION_CODE=1
VERSION_NAME=1.0

rm -rf build && mkdir -p build/gen build/classes build/dex

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets \
    --min-sdk-version 14 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java build/gen -o build/unsigned.apk build/res.zip

javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')

"$BT/d8" --release --min-api 14 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)

"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

KS=ghostbrowser-release.keystore
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass ghostbrowser-store -keypass ghostbrowser-store \
        -alias ghostbrowser -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Ghostbrowser" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:ghostbrowser-store --ks-key-alias ghostbrowser \
    --min-sdk-version 14 --out build/Ghostbrowser.apk build/aligned.apk
"$BT/apksigner" verify build/Ghostbrowser.apk
echo "Built $(pwd)/build/Ghostbrowser.apk ($(du -h build/Ghostbrowser.apk | cut -f1))"
