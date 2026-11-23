#!/usr/bin/env bash
# Builds VR-ADB-Executor.apk without Gradle.
# Needs (Ubuntu): apt install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23 default-jdk
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/gen"

if [ ! -f app/res/drawable/ic_launcher.png ]; then
  java tools/MakeIcon.java app/res/drawable/ic_launcher.png
fi

aapt package -f -m -J "$OUT/gen" -M app/AndroidManifest.xml -S app/res -I "$ANDROID_JAR"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" $(find app/src "$OUT/gen" -name '*.java')
dalvik-exchange --dex --output="$OUT/classes.dex" "$OUT/classes"

aapt package -f -M app/AndroidManifest.xml -S app/res -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS=${KEYSTORE:-debug.keystore}
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v4-signing-enabled false --out VR-ADB-Executor.apk "$OUT/aligned.apk"
apksigner verify VR-ADB-Executor.apk
echo "Built $(pwd)/VR-ADB-Executor.apk"
