#!/usr/bin/env bash
set -euo pipefail
# Builds GPLv2 scetool for an Android ARM64 userspace executable. No game files included.
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to an installed NDK}"
SRC="${1:?pass scetool source directory}"
DEST="${2:?pass destination path}"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TOOLCHAIN/aarch64-linux-android26-clang"
CXX="$TOOLCHAIN/aarch64-linux-android26-clang++"
test -x "$CC"
test -f "$SRC/main.cpp"
mkdir -p "$(dirname "$DEST")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
sources=(aes.c aes_omac.cpp bn.cpp ec.cpp ecdsa.cpp frontend.cpp getopt.cpp keys.cpp list.cpp main.cpp mt19937.cpp np.cpp rvk.cpp sce.cpp self.cpp sha1.c tables.cpp util.cpp spp.cpp)
objs=()
for source in "${sources[@]}"; do
  test -f "$SRC/$source"
  object="$TMP/${source%.*}.o"
  if [[ "$source" == *.c ]]; then
    "$CC" -O2 -fPIE -D_LARGEFILE64_SOURCE -I"$SRC" -c "$SRC/$source" -o "$object"
  else
    "$CXX" -O2 -fPIE -D_LARGEFILE64_SOURCE -I"$SRC" -c "$SRC/$source" -o "$object"
  fi
  objs+=("$object")
done
"$CXX" -fPIE -pie -Wl,-z,max-page-size=16384 "${objs[@]}" -lz -lm -o "$DEST"
file "$DEST"
"$TOOLCHAIN/llvm-readelf" -h "$DEST" | grep -q 'AArch64'
