#!/usr/bin/env bash
# Builds the native CIA engine for the host and runs it on small synthetic titles (no game data):
#   - a plaintext game CIA must become a valid CCI whose RomFS matches the input files
#   - a DLC CIA must be refused with the "not convertible" result (14) and leave no output
# Encrypted retail CIAs can't be generated here (no keys are used to encrypt), so that path isn't covered.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
BUILD="$ROOT/test-build/native-host"
WORK="$ROOT/test-build/native-work"
mkdir -p "$BUILD"
rm -rf "$WORK" && mkdir -p "$WORK/romfs/data" "$WORK/tmp"
cmake -S native -B "$BUILD" -DCIA3DS_HOST_TEST=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS=-w -DCMAKE_CXX_FLAGS=-w >/dev/null
cmake --build "$BUILD" -j"$(nproc)" > "$BUILD/build.log" 2>&1 || { tail -n 40 "$BUILD/build.log"; exit 1; }
ENGINE="$BUILD/cia3ds"
SEEDDB="$ROOT/app/src/main/assets/seeddb.bin"

cd "$WORK"
echo "hello cci" > romfs/hello.txt
head -c 300000 /dev/urandom > romfs/data/blob.bin
rsf() { # $1 = file, $2 = category
  cat > "$1" <<RSF
BasicInfo:
  Title: "ENGINETEST"
  ProductCode: "CTR-P-TEST"
  ContentType: Application
  Logo: None
TitleInfo:
  UniqueId: 0xff3ff
  Category: $2
Option:
  EnableCrypt: false
  FreeProductCode: true
  MediaFootPadding: false
RomFs:
  RootPath: romfs
RSF
}
rsf game.rsf Application
rsf dlc.rsf AddOnContents
"$ENGINE" makerom -f ncch -rsf game.rsf -target t -o game.cfa >/dev/null 2>&1
"$ENGINE" makerom -f ncch -rsf dlc.rsf -target t -o dlc.cfa >/dev/null 2>&1
"$ENGINE" makerom -f cia -ignoresign -target t -o game.cia -i game.cfa:0:0 >/dev/null 2>&1
"$ENGINE" makerom -f cia -ignoresign -target t -dlc -o dlc.cia -i dlc.cfa:0:0 >/dev/null 2>&1

"$ENGINE" convert game.cia game.cci "$SEEDDB" "$WORK/tmp" 2> game.log || { tail -n 30 game.log; echo "game CIA conversion failed"; exit 1; }
[ "$(dd if=game.cci bs=1 skip=256 count=4 2>/dev/null)" = "NCSD" ] || { echo "output is not an NCSD image"; exit 1; }
"$ENGINE" ctrtool --romfsdir=out_romfs game.cci >/dev/null 2>&1
diff -r romfs out_romfs || { echo "RomFS in the CCI differs from the input"; exit 1; }
echo "native engine: plaintext game CIA -> CCI passed (RomFS identical)"

set +e
"$ENGINE" convert dlc.cia dlc.cci "$SEEDDB" "$WORK/tmp" 2> dlc.log
rc=$?
set -e
[ "$rc" = 14 ] || { tail -n 30 dlc.log; echo "DLC CIA should be refused with 14, got $rc"; exit 1; }
[ ! -s dlc.cci ] || { echo "DLC refusal wrote output data"; exit 1; }
echo "native engine: DLC CIA refused passed"
