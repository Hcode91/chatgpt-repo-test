#!/usr/bin/env bash
# Compiles an ACTUAL upstream PS3 runtime archive, then attempts the upstream
# full synthetic guest boot scaffold with NDK clang. No game files included.
# Stores the full logs even if the build has unresolved Android portability issues.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build/integrated"
LOG="$ROOT/build/integrated-logs"
mkdir -p "$BUILD" "$LOG"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME}/ndk/27.2.12479018}"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
if [[ ! -f "$TOOLCHAIN" ]]; then echo "Android NDK toolchain missing: $TOOLCHAIN"; exit 3; fi

set +e
cmake -G Ninja -S "$ROOT/runtime-integration" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DANDROID_STL=c++_shared \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
  >"$LOG/configure.log" 2>&1
CFG=$?
if [[ $CFG -eq 0 ]]; then
  cmake --build "$BUILD" --target ps3recomp_runtime -- -k 10 -j 3 \
    >"$LOG/library.log" 2>&1
  LIB=$?
else
  LIB=98
  echo "CMake configure failed: $CFG" >"$LOG/library.log"
fi
if [[ $CFG -eq 0 && $LIB -eq 0 ]]; then
  cmake --build "$BUILD" --target ps3recomp_boot_smoke -- -k 10 -j 3 \
    >"$LOG/boot.log" 2>&1
  BOOT=$?
else
  BOOT=98
  echo "Boot build skipped: configure=$CFG library=$LIB" >"$LOG/boot.log"
fi
{
  echo "# Android full-runtime integration gate"
  echo
  echo "| Target | Exit code | State |"
  echo "|---|---:|---|"
  for entry in "CMake configure:$CFG" "Upstream runtime static archive:$LIB" "Upstream full boot-smoke linkage:$BOOT"; do
    label="${entry%:*}"; num="${entry##*:}"
    if [[ $num -eq 0 ]]; then state=PASS; else state=BLOCKED; fi
    echo "| $label | $num | $state |"
  done
  echo
  echo "This is an ARM64 cross-build on CI, not an on-device game boot."
  echo "A successful executable link still requires testing, full HLE and a working RSX renderer."
} >"$LOG/summary.md"
echo "=== Runtime configuration ==="
tail -n 25 "$LOG/configure.log"
echo "=== Runtime archive compiler diagnostics ==="
grep -E 'error:|fatal error:|FAILED:|ninja: build stopped|undefined reference|undefined symbol' "$LOG/library.log" | head -n 55 || true
echo "=== Full boot scaffold diagnostics ==="
grep -E 'error:|fatal error:|FAILED:|ninja: build stopped|undefined reference|undefined symbol' "$LOG/boot.log" | head -n 60 || true
cat "$LOG/summary.md"
if [[ $CFG -ne 0 || $LIB -ne 0 || $BOOT -ne 0 ]]; then exit 1; fi
