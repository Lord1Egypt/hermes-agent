#!/usr/bin/env python3
"""Refresh only official pure-Python wheels in an already verified Android bundle.

Native/bootstrap inputs must be unchanged. Reuse the existing build interpreter
and source helpers; do not create a venv or turn an unverified cache into evidence.
The previous complete bundle is retained before the validated replacement is used.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import urllib.request
import zipfile

if __name__ == "__main__":
    sys.dont_write_bytecode = True
if __package__:
    from . import prepare_android_python_runtime as runtime
else:
    import prepare_android_python_runtime as runtime


def pure_wheel_changes(old_lock: dict, new_lock: dict, old_pins: dict, new_pins: dict) -> tuple[list, list]:
    """Refuse native, bootstrap, ABI, package-set, or same-version content changes."""
    if {k: v for k, v in old_lock.items() if k != "official_wheels"} != {
        k: v for k, v in new_lock.items() if k != "official_wheels"
    } or set(old_pins) != set(new_pins):
        raise ValueError("Only pure-wheel version updates are eligible for refresh")
    changed = {name for name in old_pins if old_pins[name] != new_pins[name]}
    if not changed or changed & runtime.source_built_packages(new_lock):
        raise ValueError("Refresh requires changed official pure-Python packages")
    old = {x["filename"]: x for x in old_lock["official_wheels"]}
    new = {x["filename"]: x for x in new_lock["official_wheels"]}
    removed = [x for name, x in old.items() if name not in new]
    added = [x for name, x in new.items() if name not in old]
    if any(old[name] != new[name] for name in old.keys() & new.keys()):
        raise ValueError("An unchanged wheel version cannot change its locked bytes")
    for entries in (removed, added):
        names = [runtime.canonical_name(x["filename"].split("-")[0]) for x in entries]
        if set(names) != changed or len(names) != len(changed) or any(
            not x["filename"].endswith("-py3-none-any.whl") for x in entries
        ):
            raise ValueError("Native, ABI-specific, or ambiguous wheel changes require a source rebuild")
    return removed, added


def fetch_official_wheel(entry: dict, destination: Path) -> None:
    name, version = entry["filename"].split("-")[:2]
    request = urllib.request.Request(f"https://pypi.org/pypi/{name}/{version}/json",
                                     headers={"User-Agent": "Agent-Android-build/1"})
    with urllib.request.urlopen(request, timeout=60) as response:
        metadata = json.load(response)
    matches = [x for x in metadata["urls"] if x["filename"] == entry["filename"]]
    if len(matches) != 1:
        raise ValueError("Official wheel is absent or ambiguous")
    candidate = matches[0]
    if (candidate["yanked"] or candidate["size"] != entry["bytes"]
            or candidate["digests"]["sha256"] != entry["sha256"]
            or not candidate["url"].startswith("https://files.pythonhosted.org/")):
        raise ValueError("Official wheel metadata does not match the committed lock")
    with urllib.request.urlopen(candidate["url"], timeout=60) as response, destination.open("xb") as target:
        total = 0
        while block := response.read(65536):
            total += len(block)
            if total > entry["bytes"]:
                raise ValueError("Wheel exceeds its locked size")
            target.write(block)
    if total != entry["bytes"] or runtime.digest(destination) != entry["sha256"]:
        raise ValueError("Downloaded wheel checksum/size mismatch")
    with zipfile.ZipFile(destination) as archive:
        wheel_metadata = [n for n in archive.namelist() if n.endswith(".dist-info/WHEEL")]
        if len(wheel_metadata) != 1 or "Root-Is-Purelib: true" not in archive.read(wheel_metadata[0]).decode():
            raise ValueError("Wheel does not declare pure Python")
        if any(n.endswith((".so", ".pyd", ".dll", ".dylib")) for n in archive.namelist()):
            raise ValueError("Native payload cannot use the pure-wheel refresh path")


def refresh(output: Path, work: Path, previous_lock: Path, previous_requirements: Path,
            python: Path, helpers: Path, *, lock_file: Path = runtime.LOCK,
            requirements: Path = runtime.REQUIREMENTS) -> dict:
    output, work = output.absolute(), work.absolute()
    if (output.is_symlink() or not output.is_dir() or work.exists()
            or work.resolve().is_relative_to(output.resolve())
            or output.resolve().is_relative_to(work.resolve())):
        raise ValueError("Use a real existing bundle and a separate unused results directory")
    if not python.is_file() or not (helpers / "audit_wheels.py").is_file():
        raise ValueError("Existing interpreter and genuine source audit helpers are required")
    original = runtime.verify(output, lock_file=previous_lock, requirements=previous_requirements)
    old = runtime.load_lock(previous_lock, previous_requirements)
    new = runtime.load_lock(lock_file, requirements)
    removed, added = pure_wheel_changes(old, new,
        runtime.pins(previous_requirements.read_text(encoding="utf-8")),
        runtime.pins(requirements.read_text(encoding="utf-8")))
    # Source-built native files remain backed by the old verified inventory. Official
    # wheels are additionally checked directly against the committed prior lock.
    for entry in old["official_wheels"]:
        path = output / "wheels" / entry["filename"]
        if not path.is_file() or path.stat().st_size != entry["bytes"] or runtime.digest(path) != entry["sha256"]:
            raise ValueError("Previous official wheel does not match its committed identity")
    work.mkdir(parents=True)
    stage = work / "bundle"
    shutil.copytree(output, stage)
    for entry in removed:
        (stage / "wheels" / entry["filename"]).unlink()
    for entry in added:
        fetch_official_wheel(entry, stage / "wheels" / entry["filename"])
    shutil.copyfile(requirements, stage / "requirements.txt")
    environment = {k: v for k, v in os.environ.items() if not k.endswith(("_TOKEN", "_SECRET", "_PASSWORD", "_API_KEY"))
                   and not k.startswith(("PIP_", "CIBW_"))}
    environment.update(PIP_CONFIG_FILE=os.devnull, PYTHONDONTWRITEBYTECODE="1")
    for abi, platform_abi in runtime.ABIS.items():
        closure = work / ("closure-" + abi)
        runtime.run([python, "-m", "pip", "download", "--no-index", "--only-binary=:all:",
                     "--platform", "android_24_" + platform_abi, "--python-version", "3.13",
                     "--implementation", "cp", "--abi", "cp313", "--find-links", stage / "wheels",
                     "--dest", closure, "-r", requirements], cwd=runtime.ROOT, env=environment, timeout=180)
        runtime.run([python, runtime.ROOT / "scripts/audit_android_python_runtime.py", "--helpers", helpers,
                     "--wheel-dir", closure, "--requirements", requirements, "--abi", abi,
                     "--output", work / ("closure-" + abi + ".json")],
                    cwd=runtime.ROOT, env=environment, timeout=180)
    receipt = dict(original, source_lock_sha256=runtime.digest(lock_file),
                   hermes_requirements_sha256=runtime.digest(requirements), files=runtime.inventory(stage))
    (stage / "consumer.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    runtime.verify(stage, lock_file=lock_file, requirements=requirements)
    # Recheck the old bundle immediately before replacing it. Preserve the previous
    # complete bundle in results, never mutate a consumer receipt to waive validation.
    runtime.verify(output, lock_file=previous_lock, requirements=previous_requirements)
    backup = work / "previous-bundle"
    output.rename(backup)
    try:
        stage.rename(output)
    except OSError:
        backup.rename(output)
        raise
    (work / "refresh.json").write_text(json.dumps({"mode": "verified-pure-wheel-refresh",
        "previous_consumer_sha256": runtime.digest(backup / "consumer.json"),
        "new_consumer_sha256": runtime.digest(output / "consumer.json"),
        "removed": removed, "added": added, "new_environments": 0,
        "native_payload_rebuilt": False, "dependency_closures_verified": list(runtime.ABIS)}, indent=2) + "\n", encoding="utf-8")
    return runtime.verify(output, lock_file=lock_file, requirements=requirements)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("output", "work-dir", "previous-lock", "previous-requirements", "python", "helpers"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    receipt = refresh(args.output, args.work_dir, args.previous_lock, args.previous_requirements,
                      args.python, args.helpers)
    print(json.dumps({"schema": receipt["schema"], "files": len(receipt["files"]), "new_environments": 0}))


if __name__ == "__main__":
    main()
