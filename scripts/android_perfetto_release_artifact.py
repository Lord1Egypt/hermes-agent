#!/usr/bin/env python3
"""Bind new, never-tracked release traces to one verified Actions artifact.

The historical archive helper owns old Git-backed traces. This producer keeps
new trace bytes external, and requires a successful independent cloud round trip
before issuing a receipt. The normal release validator still checks trace data.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import sys
import stat
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

if __package__:
    from . import android_perfetto_artifacts as archive
else:
    import android_perfetto_artifacts as archive


SOURCE_SCHEMA = "hermes-android-perfetto-release-source-v1"
RECEIPT_SCHEMA = "hermes-android-perfetto-release-receipt-v1"
WORKFLOW_PATH = ".github/workflows/android-perfetto-release.yml"
PROFILES = ("phone-compact", "tablet")
Error = archive.PerfettoArtifactError


def metadata_path(root: Path, tag: str, name: str) -> Path:
    if not archive.TAG_RE.fullmatch(tag):
        raise Error("Release tag is not canonical")
    return root / "android/release-evidence/perfetto-artifacts" / tag / name


def _performance_source(root: Path, tag: str) -> dict[str, Any]:
    metadata_path(root, tag, "source.json")
    bindings = archive._trace_bindings_from_performance(root, tag)
    performance = root / "android/release-evidence" / tag / "performance"
    identities = []
    records = []
    for profile in PROFILES:
        payload = archive._json_object(performance / f"{profile}.json")
        identities.append((payload.get("release_source_digest"), payload.get("evidence_run_id")))
        if (
            payload.get("version_name") != tag.removeprefix("v")
            or payload.get("profile") != profile
            or payload.get("schema") != "hermes-android-performance-evidence-v2"
        ):
            raise Error(f"{profile} performance evidence has another release/profile identity")
        if not 5 <= len(payload["traces"]) <= 20:
            raise Error(f"{profile} requires 5 to 20 measured iterations")
    if identities[0] != identities[1]:
        raise Error("Phone and tablet evidence have different source/run identities")
    digest, run_id = identities[0]
    if not isinstance(digest, str) or not archive.HEX_64_RE.fullmatch(digest):
        raise Error("Performance source digest is invalid")
    if not isinstance(run_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{15,79}", run_id):
        raise Error("Performance evidence run ID is invalid")
    prefix = PurePosixPath("android/release-evidence") / tag / "performance"
    for path, record in sorted(bindings.items()):
        records.append({**record, "path": PurePosixPath(path).relative_to(prefix).as_posix()})
    return {
        "schema": SOURCE_SCHEMA,
        "repository": archive.DEFAULT_REPOSITORY,
        "tag": tag,
        "source_digest": digest,
        "evidence_run_id": run_id,
        "artifact_name": f"hermes-android-perfetto-{tag}-{digest}",
        "trace_file_count": len(records),
        "trace_bytes": sum(record["bytes"] for record in records),
        "traces": records,
    }


def verify_source(root: Path, tag: str, expected_digest: str | None = None) -> dict[str, Any]:
    source = archive._json_object(metadata_path(root, tag, "source.json"))
    if source != _performance_source(root, tag):
        raise Error("Source manifest differs from the measured performance bindings")
    if expected_digest is not None and source["source_digest"] != expected_digest:
        raise Error("Trace source differs from the release source digest")
    return source


def verify_traces(source: dict[str, Any], trace_root: Path) -> None:
    # Refuse links before resolving anything: the upload must contain only the
    # closed, public trace inventory, never a linked workspace or hidden file.
    if trace_root.is_symlink() or not trace_root.is_dir():
        raise Error("Trace root is missing or linked")
    expected = {record["path"] for record in source["traces"]}
    allowed_directories = {f"{profile}.traces" for profile in PROFILES}
    observed = set()
    for path in trace_root.rglob("*"):
        relative = path.relative_to(trace_root).as_posix()
        if path.is_symlink():
            raise Error(f"Trace archive contains a link: {relative}")
        if path.is_dir() and relative in allowed_directories:
            continue
        if not path.is_file() or relative not in expected:
            raise Error(f"Trace archive contains an unexpected path: {relative}")
        observed.add(relative)
    if observed != expected:
        raise Error(f"Trace archive is missing files: {sorted(expected - observed)}")
    for record in source["traces"]:
        archive._validate_trace_file(trace_root / record["path"], record)


def create_source(root: Path, tag: str, trace_root: Path) -> dict[str, Any]:
    source = _performance_source(root, tag)
    verify_traces(source, trace_root)
    tracked = archive._tracked_trace_paths(root)
    if any(path.startswith(f"android/release-evidence/{tag}/") for path in tracked):
        raise Error("New release traces must not be tracked in Git")
    archive._write_json(metadata_path(root, tag, "source.json"), source)
    return source



def validate_transport_metadata(source: dict[str, Any], release: dict[str, Any],
                                asset_id: int, expected_sha256: str,
                                expected_commit: str) -> dict[str, Any]:
    """A private draft is transport only, never release or trace-acceptance authority."""
    _positive_id(asset_id, "Transport asset ID")
    if not archive.HEX_64_RE.fullmatch(expected_sha256) or not archive.HEX_40_RE.fullmatch(expected_commit):
        raise Error("Transport digest or commit is invalid")
    prefix = "agent-traces-" + source["tag"] + "-"
    if (release.get("draft") is not True or release.get("published_at") is not None
            or release.get("target_commitish") != expected_commit
            or not re.fullmatch(re.escape(prefix) + r"[0-9a-f]{32}", release.get("tag_name", ""))):
        raise Error("Trace transport must be an unpublished, source-bound draft")
    assets = release.get("assets", [])
    if len(assets) != 1 or assets[0].get("id") != asset_id:
        raise Error("Draft transport must contain exactly the requested trace asset")
    asset = assets[0]
    if (asset.get("name") != source["artifact_name"] + ".zip" or asset.get("state") != "uploaded"
            or asset.get("digest") != "sha256:" + expected_sha256
            or type(asset.get("size")) is not int
            or not 0 < asset["size"] <= min(1024**3, source["trace_bytes"] + 1024**2)):
        raise Error("Trace transport asset identity, digest or size is invalid")
    return asset


def extract_transport(source: dict[str, Any], archive_path: Path, expected_sha256: str,
                      trace_root: Path) -> None:
    """Closed ZIP inventory and individual trace hashes precede final publication."""
    if archive_path.is_symlink() or trace_root.exists():
        raise Error("Transport paths are linked or the destination already exists")
    if not archive.HEX_64_RE.fullmatch(expected_sha256) or archive._sha256_file(archive_path) != expected_sha256:
        raise Error("Transport archive hash differs")
    expected = {record["path"]: record for record in source["traces"]}
    with zipfile.ZipFile(archive_path) as compressed:
        entries = compressed.infolist()
        if len(entries) != len(expected) or {entry.filename for entry in entries} != set(expected):
            raise Error("Transport archive has missing, duplicate or extra paths")
        for entry in entries:
            mode = entry.external_attr >> 16
            record = expected[entry.filename]
            if (entry.is_dir() or entry.flag_bits & 1
                    or stat.S_IFMT(mode) not in (0, stat.S_IFREG)
                    or entry.file_size != record["bytes"]):
                raise Error("Transport entry type or size differs from the measured trace")
        trace_root.parent.mkdir(parents=True, exist_ok=True)
        # Publish the output only after every entry validates, so failed downloads
        # cannot leave a directory that a later step mistakes for accepted input.
        with tempfile.TemporaryDirectory(prefix="trace-transport-", dir=trace_root.parent) as temporary:
            staging = Path(temporary) / "verified"
            staging.mkdir()
            for entry in entries:
                destination = staging / entry.filename
                if destination.resolve().parent.parent != staging.resolve():
                    raise Error("Transport entry escapes the two-profile directory layout")
                destination.parent.mkdir(exist_ok=True)
                with compressed.open(entry) as incoming, destination.open("xb") as output:
                    remaining = entry.file_size
                    while remaining:
                        block = incoming.read(min(1024**2, remaining))
                        if not block:
                            raise Error("Truncated transport entry")
                        output.write(block)
                        remaining -= len(block)
                    if incoming.read(1):
                        raise Error("Transport entry exceeds its declared size")
            verify_traces(source, staging)
            staging.rename(trace_root)


def download_transport(root: Path, tag: str, release_id: int, asset_id: int,
                       digest: str, expected_commit: str, trace_root: Path) -> dict[str, Any]:
    source = verify_source(root, tag)
    _positive_id(release_id, "Transport release ID")
    release = archive._gh_json((f"repos/{source['repository']}/releases/{release_id}",), cwd=root)
    if release.get("id") != release_id:
        raise Error("GitHub returned a different transport release")
    asset = validate_transport_metadata(source, release, asset_id, digest, expected_commit)
    with tempfile.TemporaryDirectory(prefix="trace-download-") as temporary:
        archive_path = Path(temporary) / "traces.zip"
        # GitHub CLI handles authenticated draft downloads and redirects. The
        # token stays in its inherited environment, never argv or persisted logs.
        with archive_path.open("xb") as output:
            subprocess.run(["gh", "api", f"repos/{source['repository']}/releases/assets/{asset_id}",
                            "-H", "Accept: application/octet-stream"], cwd=root, stdout=output,
                           stderr=subprocess.PIPE, timeout=300, check=True)
        if archive_path.stat().st_size != asset["size"]:
            raise Error("Downloaded transport size differs")
        extract_transport(source, archive_path, digest, trace_root)
    return {"status": "verified", "transport_release_id": release_id, "transport_asset_id": asset_id,
            "archive_sha256": digest, "trace_count": source["trace_file_count"],
            "release_published": False, "independent_artifact_verification_pending": True}


def _positive_id(value: Any, context: str) -> int:
    if type(value) is not int or value <= 0:
        raise Error(f"{context} must be a positive integer")
    return value


def receipt_from_api(
    source: dict[str, Any], manifest_sha: str, run: dict[str, Any],
    artifact: dict[str, Any], *, now: datetime,
) -> dict[str, Any]:
    """Validate authority before persisting any API data as release metadata."""
    repository = source["repository"]
    run_id = _positive_id(run.get("id"), "Workflow run ID")
    attempt = _positive_id(run.get("run_attempt"), "Workflow run attempt")
    head = run.get("head_sha")
    if (
        run.get("path") != WORKFLOW_PATH
        or run.get("event") != "workflow_dispatch"
        or run.get("status") != "completed"
        or run.get("conclusion") != "success"
        or run.get("repository", {}).get("full_name") != repository
        or run.get("head_repository", {}).get("full_name") != repository
        or not isinstance(head, str) or not archive.HEX_40_RE.fullmatch(head)
    ):
        raise Error("Upload and independent round-trip workflow is not a successful trusted dispatch")
    artifact_id = _positive_id(artifact.get("id"), "Artifact ID")
    size = _positive_id(artifact.get("size_in_bytes"), "Archive bytes")
    digest = artifact.get("digest")
    if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise Error("Artifact archive digest is invalid")
    parent = artifact.get("workflow_run", {})
    if (
        artifact.get("name") != source["artifact_name"]
        or parent.get("id") != run_id or parent.get("head_sha") != head
        or parent.get("repository_id") != run["repository"].get("id")
        or parent.get("head_repository_id") != run["repository"].get("id")
    ):
        raise Error("Artifact does not belong to the exact source and workflow run")
    created = archive._parse_utc(artifact.get("created_at"), "Artifact created_at")
    expires = archive._parse_utc(artifact.get("expires_at"), "Artifact expires_at")
    archive._require_ninety_day_retention(created, expires, "Release traces")
    if artifact.get("expired") is not False or not created <= now < expires:
        raise Error("Artifact is expired or has invalid creation time")
    return {
        "schema": RECEIPT_SCHEMA,
        "repository": repository,
        "tag": source["tag"],
        "source_manifest_sha256": manifest_sha,
        "workflow_head_sha": head,
        "workflow_run_id": run_id,
        "workflow_run_attempt": attempt,
        "workflow_url": f"https://github.com/{repository}/actions/runs/{run_id}",
        "artifact_id": artifact_id,
        "artifact_name": artifact["name"],
        "artifact_digest": digest,
        "artifact_archive_bytes": size,
        "artifact_created_at": artifact["created_at"],
        "artifact_expires_at": artifact["expires_at"],
        "retention_days": archive.RETENTION_DAYS,
    }


def live_receipt(root: Path, tag: str, run_id: int, artifact_id: int) -> dict[str, Any]:
    source = verify_source(root, tag)
    _positive_id(run_id, "Workflow run ID")
    _positive_id(artifact_id, "Artifact ID")
    repository = source["repository"]
    run = archive._gh_json((f"repos/{repository}/actions/runs/{run_id}",), cwd=root)
    artifact = archive._gh_json((f"repos/{repository}/actions/artifacts/{artifact_id}",), cwd=root)
    manifest = metadata_path(root, tag, "source.json")
    receipt = receipt_from_api(
        source, archive._sha256_file(manifest), run, artifact, now=datetime.now(timezone.utc)
    )
    if receipt["workflow_run_id"] != run_id or receipt["artifact_id"] != artifact_id:
        raise Error("GitHub returned a different run or artifact ID")
    # Bind the upload's checked-out manifest and verifier to the release tree.
    # Evidence-only commits may follow without changing these source bytes.
    for path in (
        manifest.relative_to(root).as_posix(), WORKFLOW_PATH,
        "scripts/android_perfetto_release_artifact.py", "scripts/android_perfetto_artifacts.py",
    ):
        response = archive._gh_json(
            (f"repos/{repository}/contents/{path}?ref={receipt['workflow_head_sha']}",), cwd=root
        )
        if response.get("encoding") != "base64" or response.get("type") != "file":
            raise Error(f"Upload source is not an ordinary GitHub file: {path}")
        try:
            remote_bytes = base64.b64decode(response["content"].replace("\n", ""), validate=True)
        except (KeyError, ValueError, TypeError) as exc:
            raise Error(f"Upload source content is invalid: {path}") from exc
        if hashlib.sha256(remote_bytes).hexdigest() != archive._sha256_file(root / path):
            raise Error(f"Upload source differs from release source: {path}")
    return receipt


def verify_receipt(root: Path, tag: str) -> dict[str, Any]:
    stored = archive._json_object(metadata_path(root, tag, "receipt.json"))
    live = live_receipt(root, tag, stored.get("workflow_run_id"), stored.get("artifact_id"))
    if stored != live:
        raise Error("Committed receipt differs from current GitHub Actions authority")
    return live


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("create-source", "verify-source", "verify-traces", "create-receipt", "verify-receipt", "download-transport"))
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--tag", required=True)
    parser.add_argument("--trace-root", type=Path)
    parser.add_argument("--expected-source-digest")
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--artifact-id", type=int)
    parser.add_argument("--transport-release-id", type=int)
    parser.add_argument("--transport-asset-id", type=int)
    parser.add_argument("--transport-sha256")
    parser.add_argument("--expected-commit")
    args = parser.parse_args()
    root = args.repo_root.resolve()
    try:
        if args.command == "download-transport":
            if not all((args.transport_release_id, args.transport_asset_id, args.transport_sha256,
                        args.expected_commit, args.trace_root)):
                parser.error("download-transport requires draft release/asset/hash, commit and output path")
            result = download_transport(root, args.tag, args.transport_release_id, args.transport_asset_id,
                                        args.transport_sha256, args.expected_commit, args.trace_root)
        elif args.command == "create-source":
            if args.trace_root is None:
                parser.error("create-source requires --trace-root")
            result = create_source(root, args.tag, args.trace_root)
        elif args.command == "create-receipt":
            result = live_receipt(root, args.tag, args.run_id, args.artifact_id)
            archive._write_json(metadata_path(root, args.tag, "receipt.json"), result)
        else:
            result = verify_source(root, args.tag, args.expected_source_digest)
            if args.command == "verify-traces":
                if args.trace_root is None:
                    parser.error("verify-traces requires --trace-root")
                verify_traces(result, args.trace_root)
            elif args.command == "verify-receipt":
                result = verify_receipt(root, args.tag)
        print(json.dumps(result, sort_keys=True))
    except (Error, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
