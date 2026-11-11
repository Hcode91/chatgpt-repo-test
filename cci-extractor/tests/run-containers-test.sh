#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/containers && mkdir -p test-build/containers
SRC=app/src/main/java/com/retrotools/cciextractor
javac -d test-build/containers $SRC/NitroLz.java $SRC/NarcArchive.java $SRC/NitroVfs.java $SRC/NdsArchive.java \
  $SRC/NdsGraphics.java $SRC/Palettes.java $SRC/RetroTiles.java tests/NitroContainersTest.java tests/HandheldTilesTest.java
java -cp test-build/containers NitroContainersTest
java -cp test-build/containers HandheldTilesTest
