#!/usr/bin/env python3
"""Generate an independently distributable ELF64/PowerPC test executable.

Contains seven clean-room PPC64 machine instructions:
    addi r3,r3,7; mulli r3,r3,6; addi r3,r3,-1;\n    li r4,64; li r10,2; srw r9,r4,r10; blr
This tests recompiling real PPU instruction bytes for Android ARM64.
No Sony program code or assets appear in this project.
"""
from pathlib import Path
import argparse
import json
import struct

TEXT_BASE = 0x10000
OPD_BASE = 0x20000
WORDS = [
    (14 << 26) | (3 << 21) | (3 << 16) | 7,        # addi r3,r3,7
    (7 << 26) | (3 << 21) | (3 << 16) | 6,         # mulli r3,r3,6
    (14 << 26) | (3 << 21) | (3 << 16) | 0xffff,   # addi r3,r3,-1
    (14 << 26) | (4 << 21) | 64,                   # li r4,64
    (14 << 26) | (10 << 21) | 2,                   # li r10,2
    0x7C895430,                                   # srw r9,r4,r10 -> 16
    0x4E800020,                                  # blr
]

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", required=True)
    args = p.parse_args()
    directory = Path(args.out)
    directory.mkdir(parents=True, exist_ok=True)
    text = struct.pack(">" + "I" * len(WORDS), *WORDS)
    (directory / "guest.bin").write_bytes(text)
    (directory / "functions.json").write_text(
        json.dumps([{"start":hex(TEXT_BASE), "end":hex(TEXT_BASE + len(text))}]),
        encoding="utf-8"
    )

    elf = bytearray(0x2008)
    elf[:16] = b"\x7fELF\x02\x02\x01" + b"\x00" * 9  # ELF64 big-endian
    struct.pack_into(">HHIQQQIHHHHHH", elf, 16,
                     2, 21, 1, OPD_BASE, 64, 0,
                     0, 64, 56, 2, 0, 0, 0)
    struct.pack_into(">IIQQQQQQ", elf, 64, 1, 5, 0x1000,
                     TEXT_BASE, TEXT_BASE, len(text), len(text), 0x1000)
    struct.pack_into(">IIQQQQQQ", elf, 64 + 56, 1, 6, 0x2000,
                     OPD_BASE, OPD_BASE, 8, 8, 0x1000)
    elf[0x1000:0x1000 + len(text)] = text
    struct.pack_into(">II", elf, 0x2000, TEXT_BASE, 0)  # entry OPD: code+TOC
    (directory / "smoke.elf").write_bytes(elf)
    print("Generated clean-room PPC64 guest ELF, OPD, and function metadata")
    print("Expected r3: ((5+7)*6)-1 = 71")

if __name__ == "__main__":
    main()
