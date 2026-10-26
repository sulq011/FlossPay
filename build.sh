#!/usr/bin/env bash
# ============================================================
# FlossPay — بناء APK بدون Gradle (aapt2 + javac + d8 + apksigner)
# يعمل في أي بيئة فيها JDK 17 + أدوات Android build-tools:
#   - GitHub Actions (ubuntu-latest: SDK مثبت مسبقًا)
#   - تيرامكس (pkg install openjdk-17 aapt2 d8 apksigner zip zipalign)
#   - أي لينكس مع متغيرات البيئة التالية:
#       ANDROID_HOME            : جذر Android SDK
#       ANDROID_BUILD_TOOLS_DIR : مجلد build-tools (اختياري)
#       ANDROID_PLATFORM_JAR    : مسار android.jar (اختياري)
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"

BT_DIR="${ANDROID_BUILD_TOOLS_DIR:-}"
if [ -z "$BT_DIR" ]; then
  BT_DIR="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
fi
[ -n "$BT_DIR" ] && [ -d "$BT_DIR" ] || { echo "❌ build-tools غير موجودة"; exit 1; }

PLATFORM_JAR="${ANDROID_PLATFORM_JAR:-}"
if [ -z "$PLATFORM_JAR" ]; then
  PLATFORM_JAR="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
fi
[ -f "$PLATFORM_JAR" ] || { echo "❌ android.jar غير موجود"; exit 1; }

AAPT2="$BT_DIR/aapt2"
D8="$BT_DIR/d8"
APKSIGNER="$BT_DIR/apksigner"
ZIPALIGN="$BT_DIR/zipalign"

echo "== أدوات البناء =="
echo "build-tools : $BT_DIR"
echo "android.jar : $PLATFORM_JAR"
javac -version
"$AAPT2" version

rm -rf build
mkdir -p build/gen build/obj build/dex

echo "== 1) aapt2 compile (الموارد) =="
"$AAPT2" compile --dir res -o build/res.zip

echo "== 2) aapt2 link (المانيفست + R.java) =="
"$AAPT2" link \
  -o build/unsigned.apk \
  -I "$PLATFORM_JAR" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 24 \
  --target-sdk-version 33 \
  --version-code 5 \
  --version-name 1.1 \
  --java build/gen \
  build/res.zip

echo "== 3) javac (جافا) =="
find src build/gen -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$PLATFORM_JAR" \
  -d build/obj @build/sources.txt

echo "== 4) d8 (تحويل إلى DEX) =="
find build/obj -name '*.class' > build/classes.txt
"$D8" --release --lib "$PLATFORM_JAR" --output build/dex $(cat build/classes.txt)

echo "== 5) تجميع الحزمة =="
cp build/unsigned.apk build/app.apk
(cd build/dex && zip -q ../app.apk classes.dex)

echo "== 6) zipalign =="
"$ZIPALIGN" -f 4 build/app.apk build/aligned.apk

echo "== 7) التوقيع =="
KS=flosspay.keystore
if [ ! -f "$KS" ]; then
  echo "إنشاء مفتاح توقيع جديد…"
  keytool -genkeypair -keystore "$KS" -alias flosspay \
    -storepass flosspay123 -keypass flosspay123 \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=FlossPay, O=Floss83 Lab, C=SA" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:flosspay123 --key-pass pass:flosspay123 \
  --out FlossPay.apk build/aligned.apk
"$APKSIGNER" verify --print-certs FlossPay.apk | head -2

echo ""
echo "✅ BUILD SUCCESSFUL: FlossPay.apk ($(du -h FlossPay.apk | cut -f1))"
