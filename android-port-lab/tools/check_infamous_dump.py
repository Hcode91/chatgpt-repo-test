#!/usr/bin/env python3
"""Check the required inFAMOUS BCES00220 user-supplied disc dump locally.

No data upload, no execution and no attempts to bypass disc encryption.
Use a *decrypted EBOOT.elf* produced from your own compatible PS3 dump.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys

EXPECTED = "095d851432f3b55179cd725b46838ffedb3e5eb9f73889e52b6f11f3d3f494fd"
REGION = "BCES00220"

def verify(path):
    root = Path(path).expanduser()
    if root.is_dir():
        if (root / "PS3_GAME" / "USRDIR").is_dir():
            root = root / "PS3_GAME" / "USRDIR"
        elif (root / "USRDIR").is_dir():
            root = root / "USRDIR"
        elf = root / "EBOOT.elf"
    else:
        elf = root
        root = root.parent

    result = {"title_expected": REGION, "file": str(elf),
              "present": elf.is_file(), "valid_elf64_ppc": False,
              "sha256_expected": EXPECTED, "sha256_matches": False,
              "ready_for_game_specific_recompilation": False,
              "steps": []}
    if not elf.is_file():
        binfile = root / "EBOOT.BIN"
        if binfile.is_file():
            result["steps"].append(
                "Found EBOOT.BIN. The upstream installer uses RPCS3 to decrypt a "
                "lawfully obtained copy into EBOOT.elf; this tool cannot do that.")
        else:
            result["steps"].append("Provide the decrypted PS3_GAME/USRDIR/EBOOT.elf "
                                   "from the European BCES00220 game dump.")
        return result
    h = hashlib.sha256()
    with elf.open("rb") as f:
        header = f.read(64)
        result["valid_elf64_ppc"] = (
            len(header) >= 20 and header[:7] == b"\x7fELF\x02\x02\x01"
            and int.from_bytes(header[18:20], "big") == 21)
        h.update(header)
        for block in iter(lambda: f.read(4 * 1024 * 1024), b""):
            h.update(block)
    result["sha256_actual"] = h.hexdigest()
    result["size_bytes"] = elf.stat().st_size
    result["sha256_matches"] = result["sha256_actual"] == EXPECTED
    result["ready_for_game_specific_recompilation"] = (
        result["valid_elf64_ppc"] and result["sha256_matches"])
    if not result["valid_elf64_ppc"]:
        result["steps"].append(
            "This file is not a decrypted big-endian ELF64 PowerPC executable.")
    elif not result["sha256_matches"]:
        result["steps"].append(
            "This executable does not match the exact BCES00220 SHA-256 from the "
            "upstream installer. Do not compile it as this release.")
    else:
        result["steps"].append(
            "Fingerprint matches the supported game. Run the upstream PPU and SPU "
            "generation pipeline locally, then build against the Android runtime. "
            "Game data must not be committed to the public GitHub repository.")
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("game_folder_or_elf")
    parser.add_argument("--json", action="store_true")
    options = parser.parse_args()
    data = verify(options.game_folder_or_elf)
    if options.json:
        print(json.dumps(data, indent=2))
    else:
        print(f"Game release expected: {data['title_expected']}")
        print(f"Executable: {data['file']}")
        print(f"Decrypted PPC64 ELF: {data['valid_elf64_ppc']}")
        print(f"Supported SHA256 matches: {data['sha256_matches']}")
        print(f"Ready to recompile: {data['ready_for_game_specific_recompilation']}")
        for step in data["steps"]: print(f"Next: {step}")
    return 0 if data["ready_for_game_specific_recompilation"] else 2

if __name__ == "__main__":
    sys.exit(main())
