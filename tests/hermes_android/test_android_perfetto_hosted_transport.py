"""A hosted upload accepts only a closed, hash-bound private trace transport."""
from __future__ import annotations

import copy
import hashlib
import io
import stat
import zipfile
from pathlib import Path

import pytest

from scripts import android_perfetto_release_artifact as release


def _source_and_zip(mutation: str = "none"):
    source = {"tag": "v0.13.159", "artifact_name": "trace-fixture", "trace_file_count": 2, "traces": []}
    files = {"phone-compact.traces/one.perfetto-trace": b"phone trace fixture",
             "tablet.traces/two.perfetto-trace": b"tablet trace fixture"}
    for name, data in files.items():
        source["traces"].append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    source["trace_bytes"] = sum(len(data) for data in files.values())
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for index, (name, data) in enumerate(files.items()):
            if mutation == "missing" and index == 0:
                continue
            entry = zipfile.ZipInfo(name)
            if mutation == "link" and index == 0:
                entry.create_system = 3
                entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            if mutation == "tampered" and index == 0:
                data = b"x" * len(data)
            if mutation == "oversized" and index == 0:
                data += b"unexpected"
            archive.writestr(entry, data)
        if mutation in {"extra", "traversal", "duplicate", "directory"}:
            name = {"extra": "credentials.txt", "traversal": "../escape", "duplicate": next(iter(files)),
                    "directory": "empty/"}[mutation]
            archive.writestr(name, b"unexpected")
    return source, output.getvalue()


@pytest.mark.parametrize("mutation", ["none", "tampered", "oversized", "missing", "link", "extra", "traversal", "duplicate", "directory", "archive_hash"])
def test_transport_extracts_only_exact_trace_inventory_without_publishing_partial_output(tmp_path, mutation):
    source, payload = _source_and_zip(mutation)
    work = tmp_path / "transport-case"
    work.mkdir()
    archive = work / "input.zip"
    archive.write_bytes(payload)
    digest = hashlib.sha256(payload).hexdigest() if mutation != "archive_hash" else "0" * 64
    destination = work / "verified"
    if mutation != "none":
        with pytest.raises(release.Error):
            release.extract_transport(source, archive, digest, destination)
        assert not destination.exists()
        assert list(work.iterdir()) == [archive]
    else:
        release.extract_transport(source, archive, digest, destination)
        release.verify_traces(source, destination)
        with pytest.raises(release.Error, match="already exists"):
            release.extract_transport(source, archive, digest, destination)


@pytest.mark.parametrize("mutation", ["none", "published", "published_date", "source", "tag", "extra_asset", "asset_id", "name", "hash", "size", "state"])
def test_transport_authority_does_not_replace_final_artifact_or_release_authority(mutation):
    source, payload = _source_and_zip()
    digest = hashlib.sha256(payload).hexdigest()
    asset = {"id": 23, "name": source["artifact_name"] + ".zip", "state": "uploaded",
             "digest": "sha256:" + digest, "size": len(payload)}
    draft = {"id": 17, "draft": True, "published_at": None, "target_commitish": "a" * 40,
             "tag_name": "agent-traces-v0.13.159-" + "b" * 32, "assets": [asset]}
    changes = {
        "published": (draft, "draft", False), "published_date": (draft, "published_at", "2026-09-28T00:00:00Z"),
        "source": (draft, "target_commitish", "c" * 40), "tag": (draft, "tag_name", "v0.13.159"),
        "extra_asset": (draft, "assets", [asset, copy.deepcopy(asset)]), "asset_id": (asset, "id", 24),
        "name": (asset, "name", "another.zip"), "hash": (asset, "digest", "sha256:" + "d" * 64),
        "size": (asset, "size", 2**31), "state": (asset, "state", "starter"),
    }
    if mutation in changes:
        target, key, value = changes[mutation]
        target[key] = value
        with pytest.raises(release.Error):
            release.validate_transport_metadata(source, draft, 23, digest, "a" * 40)
    else:
        assert release.validate_transport_metadata(source, draft, 23, digest, "a" * 40) == asset
