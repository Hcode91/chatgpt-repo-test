#!/usr/bin/env python3
"""Prepare a PRIVATE BCES00220 PPU lift for an Android ARM64 game boot build.

This script NEVER uploads game files or generated code. Run on a machine that
has the user's lawful game dump and a local inFAMOUSreborn checkout. The
generated sources and build products must stay outside the public repository.
The inFAMOUSreborn installer manifest is the source of truth for the release.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tomllib

EU_TITLE_ID = "BCES00220"
EU_ELF_SHA256 = "095d851432f3b55179cd725b46838ffedb3e5eb9f73889e52b6f11f3d3f494fd"


class PreparationError(Exception):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def resolve_elf(value: Path) -> Path:
    candidate = value.expanduser().resolve(strict=True)
    if candidate.is_file():
        return candidate
    for suffix in ("PS3_GAME/USRDIR/EBOOT.elf", "USRDIR/EBOOT.elf", "EBOOT.elf"):
        p = candidate / suffix
        if p.is_file():
            return p
    raise PreparationError("No EBOOT.elf inside the supplied game folder")


def inspect_elf(path: Path) -> dict:
    with path.open("rb") as stream:
        hdr = stream.read(64)
    if (len(hdr) != 64 or hdr[:7] != b"\x7fELF\x02\x02\x01"
            or int.from_bytes(hdr[18:20], "big") != 21):
        raise PreparationError("Not a decrypted PS3 PowerPC64 big-endian ELF")
    observed = sha256(path)
    if observed != EU_ELF_SHA256:
        raise PreparationError(
            f"Wrong EBOOT.elf fingerprint: {observed}; "
            "only exact European BCES00220 build is supported")
    return {"title_id": EU_TITLE_ID, "size": path.stat().st_size,
            "sha256": observed,
            "entry_opd": hex(int.from_bytes(hdr[24:32], "big"))}


def validate_checkout(checkout: Path) -> tuple[Path, dict]:
    manifest = checkout / "installer" / "infamous.toml"
    toolkit = checkout / "ps3recomp" / "tools"
    if not manifest.is_file() or not (toolkit / "ppu_loader.py").is_file() or not (toolkit / "ppu_lifter.py").is_file():
        raise PreparationError("Expected an inFAMOUSreborn checkout with installer/infamous.toml and ps3recomp/tools")
    with manifest.open("rb") as f:
        data = tomllib.load(f)
    game = data.get("game", {})
    if (EU_TITLE_ID not in game.get("title_ids", []) or
            EU_ELF_SHA256 not in game.get("eboot_sha256", [])):
        raise PreparationError("Upstream manifest does not match the verified European release")
    return toolkit, data


def command(cmd: list[str], dry_run: bool) -> None:
    print("COMMAND:", " ".join(cmd), flush=True)
    if not dry_run:
        subprocess.run(cmd, check=True)


def prepare(elf: Path, checkout: Path, destination: Path, jobs: int,
            dry_run: bool) -> None:
    inspection = inspect_elf(elf)
    tools, _ = validate_checkout(checkout)
    output = destination / "recompiled"
    metadata = destination / "metadata"
    if output.is_relative_to(checkout) or metadata.is_relative_to(checkout):
        raise PreparationError(
            "Refusing to generate game code under the upstream GitHub checkout. "
            "Use a private directory OUTSIDE the repository.")
    print("Verified game:", inspection["title_id"], inspection["sha256"], flush=True)
    print("Private output:", destination, flush=True)
    args = [sys.executable, str(tools / "ppu_loader.py"), str(elf),
            "--output", str(metadata)]
    lift = [sys.executable, str(tools / "ppu_lifter.py"), str(elf),
            "--functions", str(metadata / "EBOOT.functions.json"),
            "--hle-stubs", str(metadata / "EBOOT.imports.json"),
            "--output", str(output), "--jobs", str(jobs)]
    if not dry_run:
        destination.mkdir(parents=True, exist_ok=True)
        metadata.mkdir(parents=True, exist_ok=True)
        output.mkdir(parents=True, exist_ok=True)
    command(args, dry_run)
    command(lift, dry_run)
    if dry_run:
        return
    header = output / "ppu_recomp.h"
    sources = sorted(output.glob("ppu_recomp*.cpp")) + sorted(output.glob("ppu_recomp*.c"))
    if not header.is_file() or not sources:
        raise PreparationError("Lifter ran but did not produce header and native sources")
    manifest = {
        "game": inspection,
        "ppu_header": header.name,
        "ppu_translation_units": len(sources),
        "toolkit": str(tools.resolve()),
        "generated_code_is_private": True,
        "ready_for_arm64_cross_compile": True,
        "spu_lift_complete": False,
        "important": "This is the actual game's PPU code. DO NOT push into a public repository or upload as a public CI artifact.",
    }
    (destination / "private-build-manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("PPU lift generated:", len(sources), "translation units")
    print("SPU stage NOT YET built; PPU boot may stop when SPU work starts.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elf", type=Path, required=True,
                        help="Game folder or exact decrypted EBOOT.elf")
    parser.add_argument("--checkout", type=Path, required=True,
                        help="Local inFAMOUSreborn checkout")
    parser.add_argument("--private-out", type=Path, required=True,
                        help="Private output OUTSIDE the Git repository")
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--dry-run", action="store_true",
                        help="Verify ELF + manifest and show full commands only")
    args = parser.parse_args()
    try:
        elf = resolve_elf(args.elf)
        checkout = args.checkout.expanduser().resolve(strict=True)
        out = args.private_out.expanduser().resolve()
        if not (1 <= args.jobs <= 16):
            raise PreparationError("--jobs must be 1..16")
        prepare(elf, checkout, out, args.jobs, args.dry_run)
    except (OSError, ValueError, PreparationError, subprocess.CalledProcessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
