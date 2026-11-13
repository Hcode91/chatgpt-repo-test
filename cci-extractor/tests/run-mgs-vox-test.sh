#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/mgsvox && mkdir -p test-build/mgsvox
SRC=app/src/main/java/com/retrotools/cciextractor
javac -d test-build/mgsvox $SRC/MgsVox.java $SRC/GameAudio.java $SRC/CtrArchives.java tests/MgsVoxTest.java
java -cp test-build/mgsvox MgsVoxTest
