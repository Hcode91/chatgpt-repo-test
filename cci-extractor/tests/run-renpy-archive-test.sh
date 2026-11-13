#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/renpy && mkdir -p test-build/renpy
javac -d test-build/renpy app/src/main/java/com/retrotools/cciextractor/RenpyArchive.java tests/RenpyArchiveTest.java
java -cp test-build/renpy RenpyArchiveTest tests/fixtures/rpa
