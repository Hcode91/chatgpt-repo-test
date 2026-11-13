#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/ps3trophy && mkdir -p test-build/ps3trophy
javac -d test-build/ps3trophy app/src/main/java/com/retrotools/cciextractor/Ps3Trophy.java tests/Ps3TrophyTest.java
java -cp test-build/ps3trophy Ps3TrophyTest
