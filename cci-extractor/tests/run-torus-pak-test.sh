#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/toruspak && mkdir -p test-build/toruspak
SRC=app/src/main/java/com/retrotools/cciextractor
javac -d test-build/toruspak $SRC/TorusPak.java $SRC/TextureFormats.java $SRC/BtexCodec.java $SRC/Palettes.java $SRC/NdsGraphics.java tests/TorusPakTest.java
java -cp test-build/toruspak TorusPakTest
