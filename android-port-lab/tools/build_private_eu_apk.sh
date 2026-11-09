#!/usr/bin/env bash
# PRIVATE game-code build: verified user-supplied BCES00220 ELF only.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${EU_ELF:?Set EU_ELF to private BCES00220 EBOOT.elf}"
: "${EU_PRIVATE_OUT:?Set EU_PRIVATE_OUT to an external private workspace}"
: "${ANDROID_HOME:?Android SDK required}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
CHECKOUT="${EU_UPSTREAM:-$ROOT/upstream}"
PRIVATE="$(realpath -m "$EU_PRIVATE_OUT")"
case "$PRIVATE/" in "$ROOT/"*) echo "ERROR: private game code must be outside public repo" >&2; exit 2;; esac
test -f "$EU_ELF"
test -f "$CHECKOUT/installer/infamous.toml" || {
  echo "ERROR: clone inFAMOUSreborn into $CHECKOUT (or set EU_UPSTREAM)" >&2; exit 2;
}
test -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
python3 "$ROOT/tools/prepare_eu_game_lift.py" \
  --elf "$EU_ELF" --checkout "$CHECKOUT" \
  --private-out "$PRIVATE" --jobs "${EU_PPU_JOBS:-2}"
test -f "$ROOT/third_party/SDL2-src/CMakeLists.txt" || {
  echo "ERROR: checkout SDL2 source, see README_EU_PRIVATE_BOOT.md" >&2; exit 2;
}
test -f "$ROOT/third_party/libucontext/arch/aarch64/getcontext.S" || {
  echo "ERROR: checkout libucontext, see README_EU_PRIVATE_BOOT.md" >&2; exit 2;
}
# Apply the same tested Android portability changes that public CI uses.
# This edits only the local upstream clone, never the source repository.
if [[ ! -e "$ROOT/third_party/SDL2" ]]; then
  ln -s SDL2-src/include "$ROOT/third_party/SDL2"
fi
python3 "$ROOT/tools/patch_android_upstream.py" "$CHECKOUT/ps3recomp"
BUILD="$PRIVATE/arm64-build"
cmake -S "$ROOT/runtime-integration" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release \
  -DINFAMOUS_PRIVATE_RECOMP="$PRIVATE/recompiled"
cmake --build "$BUILD" --target infamous_eu_private_boot --parallel "${EU_BUILD_JOBS:-2}"
BIN="$BUILD/libps3eu.so"
test -s "$BIN"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$BIN" | grep -q AArch64
# The public App JNI library still needs its separate harmless smoke lift.
# That smoke code is not used by the REAL European boot executable.
mkdir -p "$ROOT/generated" "$ROOT/app/src/main/assets"
python3 "$ROOT/tools/generate_ppu_smoke.py" --out "$ROOT/generated"
python3 "$CHECKOUT/ps3recomp/tools/ppu_lifter.py" \
  "$ROOT/generated/smoke.elf" --functions "$ROOT/generated/functions.json" \
  --output "$ROOT/generated" --jobs 1 --single-file
cp "$ROOT/generated/smoke.elf" "$ROOT/app/src/main/assets/smoke.elf"
APK_LIB="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$APK_LIB"
# The Android boot executable uses NDK libc++_shared.so.
LIBCPP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
test -s "$LIBCPP"
if [[ ! -e "$APK_LIB/libc++_shared.so" ]]; then cp "$LIBCPP" "$APK_LIB/libc++_shared.so"; fi
if [[ -e "$APK_LIB/libps3eu.so" ]]; then
  echo "ERROR: pre-existing private library, refusing overwrite" >&2; exit 2;
fi
cleanup() { rm -f "$APK_LIB/libps3eu.so"; }
trap cleanup EXIT
cp "$BIN" "$APK_LIB/libps3eu.so"
(cd "$ROOT" && gradle --no-daemon assembleDebug)
OUT="$PRIVATE/infamous-eu-private-boot.apk"
cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$OUT"
printf 'PRIVATE EU GAME BOOT APK: %s\n' "$OUT"
printf 'Proprietary-derived code remains in: %s\n' "$PRIVATE"
printf 'This is an attempted boot, not evidence of successful gameplay.\n'
