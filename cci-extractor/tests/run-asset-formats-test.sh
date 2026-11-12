#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/assetformats && mkdir -p test-build/assetformats
SRC=app/src/main/java/com/retrotools/cciextractor
javac -d test-build/assetformats $SRC/AssetExpander.java $SRC/CtrArchives.java $SRC/CtrTextures.java $SRC/GameAudio.java \
  $SRC/NdsMedia.java $SRC/NitroLz.java $SRC/BtexCodec.java tests/AssetFormatsTest.java
java -cp test-build/assetformats AssetFormatsTest
