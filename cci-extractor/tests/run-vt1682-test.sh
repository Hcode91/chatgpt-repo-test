#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/vt1682 && mkdir -p test-build/vt1682
SRC=app/src/main/java/com/retrotools/cciextractor
javac -d test-build/vt1682 $SRC/Vt1682.java tests/Vt1682Test.java
java -cp test-build/vt1682 Vt1682Test
