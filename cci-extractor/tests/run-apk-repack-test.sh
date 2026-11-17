#!/usr/bin/env bash
# APK repack + signing, verified with apksig's ApkVerifier. Fetches apksig (Google, Apache 2.0) and checks its hash.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/apk && mkdir -p test-build/apk
APKSIG_VERSION=8.7.3
APKSIG_SHA256=c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89
JAR=test-build/apksig-$APKSIG_VERSION.jar
if [ ! -f "$JAR" ]; then
  curl -fsSL -o "$JAR" "https://dl.google.com/android/maven2/com/android/tools/build/apksig/$APKSIG_VERSION/apksig-$APKSIG_VERSION.jar"
fi
echo "$APKSIG_SHA256  $JAR" | sha256sum -c -
keytool -genkeypair -keystore test-build/apk/test.p12 -storetype PKCS12 -storepass testpass -keypass testpass \
  -alias test -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Test" >/dev/null 2>&1
SRC=app/src/main/java/com/retrotools/cciextractor
javac -cp "$JAR" -d test-build/apk $SRC/ApkRepack.java $SRC/UnityObb.java $SRC/UnityBundle.java $SRC/UnityTextures.java \
  $SRC/UnityModels.java $SRC/TextureFormats.java $SRC/BtexCodec.java $SRC/Palettes.java $SRC/NdsGraphics.java tests/ApkRepackTest.java
java -cp "test-build/apk:$JAR" com.retrotools.cciextractor.ApkRepackTest test-build/apk/test.p12 testpass
