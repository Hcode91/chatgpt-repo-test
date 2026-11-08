#!/usr/bin/env python3
"""Audit actual upstream ps3recomp subsystems against the Android ARM64 NDK.

Compiles the real upstream translation units to object files; no fake or
generated substitute implements the missing PS3 APIs. The generated header
from the clean-room test title is included wherever the PPU scaffold requires
a game-specific ABI. This intentionally does NOT claim linkable/runtime-ready.
"""
import argparse, json, os, pathlib, subprocess, sys, time

ROOT = pathlib.Path(__file__).resolve().parents[1]
UP = ROOT / "upstream" / "ps3recomp"
GEN = ROOT / "generated"

SOURCES = {
    "ppu": [
        "runtime/ppu/ppu_loader.cpp",
        "runtime/ppu/ppu_hle.cpp",
        "runtime/ppu/ppu_fs.cpp",
        "runtime/ppu/ppu_sysprx.cpp",
    ],
    "platform": [
        "runtime/platform/win32_compat.c",
        "runtime/milestone.c",
    ],
    "lv2_threads": [
        "runtime/syscalls/lv2_register.c",
        "runtime/syscalls/sys_ppu_thread.c",
    ],
    "spu": [
        "runtime/spu/spu_raw.c",
        "runtime/spu/spu_channels.c",
    ],
    "rsx": [
        "libs/video/cellGcmSys.c",
        "libs/video/rsx_commands.c",
        "libs/video/rsx_null_backend.c",
        "libs/video/rsx_dispatch.c",
    ],
    "input_audio": [
        "libs/input/cellPad.c",
        "libs/audio/cellAudio.c",
    ],
}
DIRS = [
    GEN, ROOT / "third_party", UP / "include", UP / "runtime", UP / "runtime/ppu",
    UP / "runtime/platform", UP / "runtime/memory",
    UP / "runtime/spu", UP / "runtime/syscalls", UP / "libs/video",
    UP / "libs/input", UP / "libs/audio", UP / "libs/system",
    UP / "libs/filesystem", UP / "libs/network", UP / "libs/spurs",
    UP / "libs/sync", UP / "libs/codec", UP / "libs/font",
    UP / "libs/misc", UP / "libs/hardware",
]

def main():
    arg = argparse.ArgumentParser()
    arg.add_argument("--ndk", required=True)
    arg.add_argument("--out", default=str(ROOT / "build/audit"))
    arg.add_argument("--api", default="26")
    opts = arg.parse_args()
    ndk = pathlib.Path(opts.ndk)
    bin_dir = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    out = pathlib.Path(opts.out)
    out.mkdir(parents=True, exist_ok=True)
    c = bin_dir / f"aarch64-linux-android{opts.api}-clang"
    cxx = bin_dir / f"aarch64-linux-android{opts.api}-clang++"
    if not c.is_file() or not cxx.is_file():
        raise SystemExit(f"No ARM64 NDK compilers found at {bin_dir}")
    if not (GEN / "ppu_recomp.h").is_file():
        raise SystemExit("Generate the clean-room PPU header before audit")

    report = {"android_abi": "arm64-v8a", "api": int(opts.api),
              "compiler": str(cxx), "subsystems": {}, "totals": {},
              "purpose": "compile compatibility only; not game boot or linking"}
    ok, failed = 0, 0
    for category, names in SOURCES.items():
        entries = []
        print(f"\n==== {category.upper()} ====", flush=True)
        for name in names:
            is_cpp = name.endswith(".cpp")
            stem = name.replace("/", "__").replace(".", "_")
            obj = out / (stem + ".o")
            cmd = [str(cxx if is_cpp else c), "-c", "-fPIC", "-O0", "-w",
                   "-fno-strict-aliasing", "-D_GNU_SOURCE", "-pthread",
                   "-std=c++20" if is_cpp else "-std=c17"]
            cmd.extend("-I" + str(d) for d in DIRS)
            cmd += [str(UP / name), "-o", str(obj)]
            t0 = time.monotonic()
            proc = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=180)
            output = (proc.stdout + proc.stderr).strip().splitlines()
            diagnostic = [ln for ln in output if "error:" in ln or "fatal error:" in ln]
            result = {"source": name, "passed": proc.returncode == 0,
                      "seconds": round(time.monotonic() - t0, 2),
                      "errors": diagnostic[:12],
                      "last_output": output[-10:] if proc.returncode else [],
                      "object_bytes": obj.stat().st_size if obj.exists() else 0}
            entries.append(result)
            if proc.returncode == 0:
                ok += 1
                print(f"  PASS {name} ({result['object_bytes']} object bytes)", flush=True)
            else:
                failed += 1
                print(f"  FAIL {name}", flush=True)
                for line in (diagnostic or output[-3:])[:3]:
                    print("    " + line[:240], flush=True)
        report["subsystems"][category] = entries
    report["totals"] = {"object_compiled": ok, "object_failed": failed, "all": ok + failed}
    (out / "audit.json").write_text(json.dumps(report, indent=2), encoding="utf-8")

    md = ["# Android ARM64 runtime cross-compile audit", "",
          "Upstream ps3recomp source compiled directly with NDK clang; this does **not**",
          "constitute a runnable PS3 game or a linked native runtime.", "",
          f"**{ok} / {ok + failed} source files compiled.**", "",
          "| Subsystem | Module | Status | First compiler error |", "|---|---|---|---|"]
    for category, entries in report["subsystems"].items():
        for e in entries:
            err = e["errors"][0] if e["errors"] else ""
            err = err.replace("|", "\\|").replace("`", "'")[:160]
            md.append(f"| {category} | `{e['source']}` | {'PASS' if e['passed'] else 'FAIL'} | {err} |")
    md.extend(["", "## Next integration work", "",
        "1. Fix non-Android APIs and feature gates in platform/HLE modules.",
        "2. Link actual PPU loader and LV2 runtime with generated functions.",
        "3. Integrate Android Vulkan RSX backend, input and audio.",
        "4. Boot upstream full synthetic game before trying a user-owned title.",
        ""])
    (out / "audit.md").write_text("\n".join(md), encoding="utf-8")
    print(f"\nAUDIT TOTAL {ok}/{ok + failed} compiled. Details: {out}/audit.md", flush=True)
    return 0  # record blockers without obstructing debug APK builds

if __name__ == "__main__":
    sys.exit(main())
