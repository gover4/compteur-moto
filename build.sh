#!/usr/bin/env bash
# Builds a signed APK without Gradle. Usage: bash build.sh
set -euo pipefail
cd "$(dirname "$0")"

# Android SDK + JDK folder: $ANDROID_TOOLS, or a local file .tools (never committed)
TOOLS="${ANDROID_TOOLS:-$(cat .tools 2>/dev/null || true)}"
[ -n "$TOOLS" ] || { echo "Set ANDROID_TOOLS or write the tools folder in .tools"; exit 1; }
export JAVA_HOME="$TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$TOOLS/sdk/build-tools/34.0.0"
JAR="$TOOLS/sdk/platforms/android-34/android.jar"

rm -rf out && mkdir -p out/res out/gen out/classes out/dex

"$BT/aapt2.exe" compile --dir res -o out/res
"$BT/aapt2.exe" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml \
  -A assets --java out/gen out/res/*.flat

javac -nowarn --release 11 -cp "$JAR" -d out/classes \
  $(find src out/gen -name '*.java')

"$BT/d8.bat" --release --min-api 24 --lib "$JAR" --output out/dex \
  $(find out/classes -name '*.class')

cp out/base.apk out/unsigned.apk
# append dex without touching existing entries (resources.arsc must stay uncompressed)
python -I -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED).write(sys.argv[2],'classes.dex')" out/unsigned.apk out/dex/classes.dex
"$BT/zipalign.exe" -f -p 4 out/unsigned.apk out/aligned.apk

# keystore password: local file .kspass (never committed)
KS_PASS="$(cat .kspass 2>/dev/null || true)"
[ -n "$KS_PASS" ] || { echo "Missing .kspass (keystore password)"; exit 1; }
if [ ! -f release.keystore ]; then
  keytool -genkeypair -keystore release.keystore -alias symjet -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=Compteur Moto"
fi
"$BT/apksigner.bat" sign --ks release.keystore --ks-pass "pass:$KS_PASS" \
  --out Compteur-Moto.apk out/aligned.apk
"$BT/apksigner.bat" verify Compteur-Moto.apk
echo "Built $(pwd)/Compteur-Moto.apk"
