#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 1 ]]; then echo 'Usage: tests/run-smoke-test.sh /path/to/decrypted.cci'; exit 2; fi
cd "$(dirname "$0")/.."
mkdir -p test-build
javac -d test-build app/src/main/java/com/retrotools/cciextractor/CciArchive.java tests/SmokeTest.java
java -cp test-build SmokeTest "$1"
