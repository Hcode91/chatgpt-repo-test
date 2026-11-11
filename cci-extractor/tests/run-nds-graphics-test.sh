#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p test-build
javac -d test-build app/src/main/java/com/retrotools/cciextractor/NdsGraphics.java app/src/main/java/com/retrotools/cciextractor/Palettes.java tests/NdsGraphicsTest.java
java -cp test-build NdsGraphicsTest
