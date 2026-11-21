#!/usr/bin/env python3
"""Prepare the PRIVATE BCES00220 SPU programs, policy, jobs and plugin lift.

Follows the real inFAMOUSreborn installer/infamous.toml pipeline using
platform-independent ps3recomp Python tools. No game files go into GitHub.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

EXPECTED_SHA = "095d851432f3b55179cd725b46838ffedb3e5eb9f73889e52b6f11f3d3f494fd"

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(2 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def invoke(*args: str, env=None) -> None:
    print("[EU SPU]", " ".join(args), flush=True)
    subprocess.run(list(args), env=env, check=True)

def prepare(elf: Path, checkout: Path, private: Path,
            usrdir: Path | None = None) -> dict:
    elf = elf.expanduser().resolve(strict=True)
    checkout = checkout.expanduser().resolve(strict=True)
    private = private.expanduser().resolve()
    if private == checkout or checkout in private.parents:
        raise ValueError("SPU output must be OUTSIDE the upstream Git checkout")
    if sha256(elf) != EXPECTED_SHA:
        raise ValueError("Wrong EBOOT build: must match European BCES00220 SHA-256")
    tools = checkout / "ps3recomp" / "tools"
    needed = ("extract_spu_images.py", "wrap_spu_elf.py", "find_spu_functions.py",
              "build_spu_workloads.py", "spu_regcache.py", "extract_jobmods.py",
              "lift_jobmods.py", "plug_lift.py", "plug_obs.json")
    for filename in needed:
        if not (tools / filename).is_file():
            raise ValueError("Missing upstream tool: " + filename)

    images = private / "spu-images"
    lifted = private / "spu"
    images.mkdir(parents=True, exist_ok=True)
    lifted.mkdir(parents=True, exist_ok=True)
    py = sys.executable

    # Real SPU ELFs: preserve exact section tails for FNV image identities.
    invoke(py, str(tools / "extract_spu_images.py"), str(elf),
           "--output", str(images))
    embedded = sorted(images.glob("spu_[0-9][0-9][0-9][0-9]_at_*.elf"))
    if len(embedded) != 8:
        raise ValueError(f"Expected 8 embedded SPU ELFs, found {len(embedded)}")

    # Exact game-specific WWS policy carve from upstream infamous.toml.
    with elf.open("rb") as f:
        f.seek(0x68CD80)
        raw = f.read(0x2D80)
    if len(raw) != 0x2D80:
        raise ValueError("Truncated WWS policy blob")
    policy_raw = images / "pm_wwsjob.raw"
    policy_elf = images / "pm_wwsjob.elf"
    policy_raw.write_bytes(raw)
    invoke(py, str(tools / "wrap_spu_elf.py"), str(policy_raw),
           "--base", "0xA00", "--entry", "0x2BF0", "--out", str(policy_elf))
    invoke(py, str(tools / "find_spu_functions.py"), str(policy_elf),
           "--out", str(images / "pm_wwsjob.functions.json"))

    workloads = lifted / "spu_workloads.c"
    invoke(py, str(tools / "build_spu_workloads.py"),
           "--images", str(images), "--lifted", str(lifted),
           "--out", str(workloads), "--register-fn",
           "infamous_spu_register_all", "--title", "inFamous", "--relift")

    job_count = 0
    if usrdir is not None:
        usrdir = usrdir.expanduser().resolve(strict=True)
        if not any((usrdir / n).is_file() for n in ("patch.sdat", "data.sdat")):
            raise ValueError("USRDIR lacks patch.sdat / data.sdat")
        job_dir = lifted / "jobmods"
        invoke(py, str(tools / "extract_jobmods.py"), "--usrdir",
               str(usrdir), "--out", str(job_dir))
        jobs = sorted(job_dir.glob("jobmod_*.bin"))
        if not jobs:
            raise ValueError("No WWS job modules extracted")
        env = dict(os.environ)
        extra_map = checkout / "infamous-recomp" / "spu" / "jobmods_extra_map.json"
        if extra_map.is_file():
            env["JOBMOD_EXTRA_MAP"] = str(extra_map)
        invoke(py, str(tools / "lift_jobmods.py"),
               "--jobmods", str(job_dir), "--out", str(lifted),
               "--register", str(lifted / "jobmods_register.c"),
               "--symbol-prefix", "infamous", env=env)
        job_count = len(jobs)

    invoke(py, str(tools / "spu_regcache.py"), str(lifted))
    invoke(py, str(tools / "plug_lift.py"), str(elf),
           str(lifted / "wwsplug"), str(tools / "plug_obs.json"))

    units = sorted(lifted.glob("*/spu_recomp.c"))
    if len(units) < 9 or not workloads.is_file():
        raise ValueError(f"SPU registration incomplete: {len(units)} lifted sources")
    report = {
        "title_id": "BCES00220",
        "eboot_sha256": EXPECTED_SHA,
        "embedded_spu_images": len(embedded),
        "extracted_spu_images": [
            {"filename": p.name, "bytes": p.stat().st_size, "sha256": sha256(p)}
            for p in embedded],
        "spu_lifted_translation_units": len(units),
        "wws_job_modules": job_count,
        "wws_jobs_require_full_usrdir": usrdir is None,
        "spu_registry_generated": True,
        "wws_plugins_lifted": True,
        "proprietary_game_code_must_remain_private": True,
        "android_boot_verified": False,
    }
    (private / "spu-build-report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("[EU SPU] Finished", len(units), "SPU units and", job_count,
          "job modules", flush=True)
    return report

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--elf", type=Path, required=True)
    p.add_argument("--checkout", type=Path, required=True)
    p.add_argument("--private-out", type=Path, required=True)
    p.add_argument("--usrdir", type=Path)
    args = p.parse_args()
    try:
        prepare(args.elf, args.checkout, args.private_out, args.usrdir)
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        print("SPU preparation failed:", exc, file=sys.stderr)
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
