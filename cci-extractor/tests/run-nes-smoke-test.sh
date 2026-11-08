#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p test-build
javac -d test-build app/src/main/java/com/retrotools/cciextractor/NesCodec.java tests/NesCodecTest.java
java -cp test-build com.retrotools.cciextractor.NesCodecTest "$@"
