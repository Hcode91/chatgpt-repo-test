#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/mgsstage && mkdir -p test-build/mgsstage
SRC=app/src/main/java/com/retrotools/cciextractor
javac -d test-build/mgsstage $SRC/MgsStage.java tests/MgsStageTest.java
java -cp test-build/mgsstage MgsStageTest
