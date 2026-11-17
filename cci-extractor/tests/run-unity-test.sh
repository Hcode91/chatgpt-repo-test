#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/unity && mkdir -p test-build/unity
SRC=app/src/main/java/com/retrotools/cciextractor
javac -d test-build/unity $SRC/UnityBundle.java $SRC/UnityTextures.java $SRC/UnityObb.java $SRC/TextureFormats.java $SRC/BtexCodec.java $SRC/Palettes.java $SRC/NdsGraphics.java tests/UnityTest.java
java -cp test-build/unity com.retrotools.cciextractor.UnityTest tests/fixtures/unity
