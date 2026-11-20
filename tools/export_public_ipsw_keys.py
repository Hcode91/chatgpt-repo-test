#!/usr/bin/env python3
"""Export all published firmware keys from a local Legacy-iOS-Kit-Keys checkout.

Source: https://github.com/LukeZGD/Legacy-iOS-Kit-Keys
This is a snapshot of PUBLICLY DOCUMENTED keys, not a claim of all Apple keys.
No arbitrary key generation or decryption is performed.
"""
import argparse
import collections
import csv
import json
import pathlib
import re
import time
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone

HEX = re.compile(r"^[0-9a-fA-F]+$")
SUPPORTED_V02 = {
    "iPhone1,1", "iPhone1,2", "iPhone2,1",
    "iPhone3,1", "iPhone3,2", "iPhone3,3", "iPhone4,1",
    "iPod1,1", "iPod2,1", "iPod3,1", "iPod4,1"
}
FIELDNAMES = [
    "model", "ios_version", "build", "codename", "component",
    "filename", "key", "iv", "kbag", "key_format", "source_url"
]
USER_AGENT = "IPSW-Toolkit-public-key-catalogue/1.0 (GitHub research archive)"

def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

def normalise(value):
    return re.sub(r"\\s+", "", str(value or "")).lower()

def collect(src):
    records = []
    discovered, invalid, missing, badfiles = 0, 0, 0, []
    for file in sorted(src.glob("**/index.html")):
        path = file.relative_to(src).parts
        if len(path) != 3 or not path[0].startswith(("iPhone", "iPod", "iPad", "AppleTV")):
            continue
        discovered += 1
        try:
            obj = json.loads(file.read_text(encoding="utf-8"))
            model = str(obj.get("identifier") or path[0])
            build = str(obj.get("buildid") or path[1])
            code = str(obj.get("codename") or "")
            for entry in obj.get("keys", []):
                if not isinstance(entry, dict):
                    continue
                key = normalise(entry.get("key"))
                if not key or key in ("unknown", "notencrypted", "not_encrypted", "n/a"):
                    missing += 1
                    continue
                if not HEX.fullmatch(key) or len(key) not in (32, 48, 64, 72):
                    invalid += 1
                    continue
                iv = normalise(entry.get("iv"))
                kb = normalise(entry.get("kbag"))
                comp = str(entry.get("image") or "")
                filename = str(entry.get("filename") or "")
                is_root = comp.replace(" ", "").lower() in ("rootfs", "rootfilesystem")
                kind = "vfdecrypt_rootfs" if is_root and len(key) == 72 else (
                    "component_aes_key_iv" if iv else "component_key"
                )
                records.append({
                    "model": model, "ios_version": "", "build": build,
                    "codename": code, "component": comp, "filename": filename,
                    "key": key, "iv": iv, "kbag": kb, "key_format": kind,
                    "source_url": "https://github.com/LukeZGD/Legacy-iOS-Kit-Keys/blob/master/"
                                + urllib.parse.quote(str(file.relative_to(src)).replace(chr(92), "/"), safe="/,")
                })
        except (OSError, ValueError, TypeError) as ex:
            badfiles.append({"path": str(file.relative_to(src)), "error": str(ex)})
    records.sort(key=lambda r: (r["model"], r["build"], r["component"], r["filename"], r["key"]))
    unique = {}
    for row in records:
        identity = tuple(row.get(field) for field in (
            "model", "build", "component", "filename", "key", "iv"
        ))
        unique[identity] = row
    return list(unique.values()), {
        "key_build_files_examined": discovered, "entries_without_published_key": missing,
        "invalid_or_unrecognised_key_entries": invalid, "unreadable_files": badfiles
    }

def resolve_versions(records, dest):
    cache_path = dest / "version_map.json"
    try:
        cache = json.loads(cache_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        cache = {}
    models = sorted(set(r["model"] for r in records))
    errors = {}
    for i, model in enumerate(models, 1):
        if model in cache:
            continue
        url = "https://api.ipsw.me/v4/device/" + urllib.parse.quote(model, safe=",") + "?type=ipsw"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=25) as response:
                obj = json.load(response)
            builds = {}
            for f in obj.get("firmwares", []) if isinstance(obj, dict) else []:
                b = str(f.get("buildid", "")).upper()
                v = str(f.get("version", ""))
                if b and v:
                    builds[b] = v
            cache[model] = builds
        except Exception as ex:
            errors[model] = str(ex)
        if i % 10 == 0:
            write_json(cache_path, cache)
        time.sleep(0.5)
    write_json(cache_path, cache)
    found = 0
    for r in records:
        version = cache.get(r["model"], {}).get(r["build"].upper(), "")
        if version:
            r["ios_version"] = version
            found += 1
    return {"versions_resolved_entries": found,
            "versions_unresolved_entries": len(records) - found,
            "version_lookup_errors": errors,
            "version_api": "https://api.ipsw.me/v4/device/{identifier}?type=ipsw"}

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--input", required=True, type=pathlib.Path)
    p.add_argument("--output", required=True, type=pathlib.Path)
    p.add_argument("--fetch-versions", action="store_true")
    args = p.parse_args()
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    records, stats = collect(args.input)
    if args.fetch_versions:
        stats.update(resolve_versions(records, out))
    roots = []
    for r in records:
        if (r["key_format"] == "vfdecrypt_rootfs" and r["filename"].lower().endswith(".dmg")
                and r["ios_version"]):
            roots.append({
                "model": r["model"], "version": r["ios_version"], "build": r["build"],
                "filesystem": r["filename"].split("/")[-1], "key": r["key"]
            })
    roots = list({(r["model"], r["build"], r["filesystem"], r["key"]): r
                  for r in roots}.values())
    write_json(out / "ALL_COMPONENT_KEYS.json", {
        "source": "LukeZGD/Legacy-iOS-Kit-Keys (public keys)",
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "limitations": "Archive snapshot; missing unpublished/unknown keys; some iOS versions unresolved.",
        "count": len(records), "keys": records
    })
    with (out / "ALL_COMPONENT_KEYS.csv").open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDNAMES)
        writer.writeheader()
        writer.writerows(records)
    write_json(out / "ALL_ROOTFS_KEYS.json", {"keys": roots})
    comp = [r for r in roots if r["model"] in SUPPORTED_V02]
    chunks = [comp[i:i + 500] for i in range(0, len(comp), 500)]
    for i, chunk in enumerate(chunks, 1):
        write_json(out / "android_v02_import" / f"rootfs_part_{i:03}.json", {"keys": chunk})
    bymodel = collections.Counter(r["model"] for r in records)
    bycomp = collections.Counter(r["component"] for r in records)
    stats.update({
        "total_published_key_records": len(records),
        "rootfs_importable_records_all_models": len(roots),
        "rootfs_importable_records_android_v02": len(comp),
        "android_v02_import_json_files": len(chunks),
        "models_with_published_keys": len(bymodel),
        "by_model": dict(sorted(bymodel.items())),
        "by_component": dict(bycomp.most_common())
    })
    write_json(out / "coverage_and_gaps.json", stats)
    (out / "README.txt").write_text(
        "PUBLIC IPHONE / IPOD / IPAD / APPLE TV FIRMWARE KEYS\n\n"
        "ALL_COMPONENT_KEYS.json / .csv: all valid-looking hexadecimal keys found in the "
        "source archive, with component name, filename, build, IV, KBAG and source URL.\n"
        "ALL_ROOTFS_KEYS.json: legacy VFDecrypt root filesystem keys with known OS version.\n"
        "android_v02_import/*.json: batches of <= 500 keys for models supported by IPSW Toolkit v0.2.\n"
        "coverage_and_gaps.json: counts, unresolved firmware versions and unreadable records.\n\n"
        "SOURCE: https://github.com/LukeZGD/Legacy-iOS-Kit-Keys\n"
        "VERSION LOOKUP: https://api.ipsw.me/v4/device/{identifier}?type=ipsw\n\n"
        "Not every firmware is encrypted; not every key is published; actual decryption not tested.\n"
        "Modern filesystems may not use 72-hex-character VFDecrypt keys.\n"
        "Do not import non-RootFS AES/IV pairs into a legacy VFDecrypt field.\n",
        encoding="utf-8"
    )
    with zipfile.ZipFile(out.parent / "IPSW_PUBLIC_KEYS_CATALOGUE.zip", "w",
                         compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for f in sorted(out.rglob("*")):
            if f.is_file():
                archive.write(f, f.relative_to(out))
    print("KEY RECORDS:", len(records))
    print("ROOTFS with OS versions:", len(roots))
    print("Models:", len(bymodel), "App JSON batches:", len(chunks))
    print("SUMMARY:", json.dumps({k:v for k,v in stats.items()
        if not isinstance(v, (dict, list))}, indent=2))

if __name__ == "__main__":
    main()
