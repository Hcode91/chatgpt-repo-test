#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/ps1 && mkdir -p test-build/ps1
SRC=app/src/main/java/com/retrotools/cciextractor
javac -d test-build/ps1 $SRC/Ps1Disc.java $SRC/Ps1Assets.java $SRC/GameAudio.java $SRC/CtrArchives.java tests/Ps1Test.java
java -cp test-build/ps1 Ps1Test tests/fixtures/ps1
