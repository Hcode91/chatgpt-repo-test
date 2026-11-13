#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf test-build/ps3trophy && mkdir -p test-build/ps3trophy
javac -d test-build/ps3trophy app/src/main/java/com/retrotools/cciextractor/Ps3Trophy.java tests/Ps3TrophyTest.java
java -cp test-build/ps3trophy Ps3TrophyTest
javac -d test-build/ps3trophy app/src/main/java/com/retrotools/cciextractor/Ps3TrophyProgress.java tests/Ps3TrophyProgressTest.java
java -cp test-build/ps3trophy Ps3TrophyProgressTest
javac -d test-build/ps3trophy app/src/main/java/com/retrotools/cciextractor/Ps3TrophyMod.java app/src/main/java/com/retrotools/cciextractor/Ps3Trophy.java app/src/main/java/com/retrotools/cciextractor/Ps3TrophyProgress.java tests/Ps3TrophyModTest.java
java -cp test-build/ps3trophy Ps3TrophyModTest
