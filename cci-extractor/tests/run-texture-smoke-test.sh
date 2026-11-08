#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p test-build
javac -d test-build app/src/main/java/com/retrotools/cciextractor/BtexCodec.java tests/BtexCodecTest.java
java -cp test-build BtexCodecTest
