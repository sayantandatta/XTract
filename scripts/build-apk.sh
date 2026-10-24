#!/usr/bin/env bash
# Builds dist/XTract.apk without Gradle or the Android SDK manager.
#
# Requirements: JDK 17+, and the Debian/Ubuntu packages
#   aapt dalvik-exchange zipalign apksigner
# (sudo apt-get install aapt dalvik-exchange zipalign apksigner)
#
# The API 34 android.jar is downloaded once into build/ unless ANDROID_JAR points at one,
# e.g. ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
set -euo pipefail
cd "$(dirname "$0")/.."

APP=app/src/main
OUT=build
DIST=dist
KEYSTORE=${KEYSTORE:-keystore/debug.keystore}
# Keep in sync with app/build.gradle.
PACKAGE=com.xtract.app MIN_SDK=29 TARGET_SDK=34 VERSION_CODE=1 VERSION_NAME=1.0
ANDROID_JAR_URL=https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar
ANDROID_JAR_SHA256=6cea1df3efb77103ac3e2beb9bf4718964b0e0869ab16d39d29d5cbae1c147ad

for tool in aapt dalvik-exchange zipalign apksigner javac keytool; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done

mkdir -p "$OUT" "$DIST"
if [[ -z "${ANDROID_JAR:-}" ]]; then
  ANDROID_JAR=$OUT/android-34.jar
  if [[ ! -f $ANDROID_JAR ]]; then
    echo "Downloading android-34 platform jar…"
    curl -fsSL -o "$ANDROID_JAR.tmp" "$ANDROID_JAR_URL"
    echo "$ANDROID_JAR_SHA256  $ANDROID_JAR.tmp" | sha256sum -c - >/dev/null
    mv "$ANDROID_JAR.tmp" "$ANDROID_JAR"
  fi
fi

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/apk"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/apk"

echo "[1/5] Compiling resources"
# The source manifest is Gradle-style (no package attribute); aapt needs one.
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
aapt package -f -m \
  -M "$OUT/AndroidManifest.xml" -S "$APP/res" -A "$APP/assets" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  -I "$ANDROID_JAR" -J "$OUT/gen" \
  -F "$OUT/apk/unsigned.apk"

echo "[2/5] Compiling Java"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find "$APP/java" "$OUT/gen" -name '*.java')

echo "[3/5] Dexing"
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/apk/classes.dex" "$OUT/classes"

echo "[4/5] Packaging"
(cd "$OUT/apk" && aapt add -f unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"

echo "[5/5] Signing"
if [[ ! -f $KEYSTORE ]]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=XTract Debug,O=XTract,C=US" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$DIST/XTract.apk" "$OUT/apk/aligned.apk"
apksigner verify "$DIST/XTract.apk"
rm -f "$DIST/XTract.apk.idsig"

echo "Built $DIST/XTract.apk ($(du -h "$DIST/XTract.apk" | cut -f1))"
