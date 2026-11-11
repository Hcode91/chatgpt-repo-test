#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/converters && mkdir -p test-build/converters
SRC=app/src/main/java/com/retrotools/cciextractor
javac -d test-build/converters $SRC/CiaConverter.java $SRC/TextureFormats.java $SRC/BtexCodec.java \
  $SRC/NdsGraphics.java $SRC/Palettes.java tests/ConvertersTest.java
java -cp test-build/converters ConvertersTest
