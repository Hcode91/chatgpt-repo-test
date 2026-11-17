#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/unreal && mkdir -p test-build/unreal
SRC=app/src/main/java/com/retrotools/cciextractor
javac -d test-build/unreal $SRC/UnrealPak.java $SRC/UnrealAssets.java $SRC/UnrealObb.java $SRC/UnrealModels.java $SRC/UnityPaint.java $SRC/UnityBundle.java $SRC/UnityTextures.java \
  $SRC/UnityObb.java $SRC/UnityModels.java $SRC/TextureFormats.java $SRC/BtexCodec.java $SRC/Palettes.java $SRC/NdsGraphics.java tests/UnrealTest.java
java -cp test-build/unreal com.retrotools.cciextractor.UnrealTest tests/fixtures/unreal
