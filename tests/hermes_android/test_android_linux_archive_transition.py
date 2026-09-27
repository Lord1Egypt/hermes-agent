"""Release archive transitions retain exact package identity, even before publication."""
import hashlib
from io import BytesIO
import zipfile

import pytest

from hermes_android.linux_assets import TermuxPackageRecord
from scripts import prepare_android_linux_assets as assets


def _zip(entries):
    output = BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, payload in entries:
            archive.writestr(name, payload)
    return output.getvalue()


def _record(name, content):
    return TermuxPackageRecord(name=name, version="1", filename=f"pool/{name}.deb",
                              sha256=hashlib.sha256(content).hexdigest(), depends=())


def test_transition_uses_verified_previous_archive_without_swallowing_consumer_errors(monkeypatch):
    old_content = b"existing exact package"
    package = _record("existing", old_content)
    previous = _zip([(package.filename, old_content)])
    calls = []
    lock = {"package_archive": {
        "url": "https://example.invalid/new.zip", "sha256": "0" * 64,
        "fallback": {"url": "https://example.invalid/previous.zip",
                     "sha256": hashlib.sha256(previous).hexdigest()},
    }}

    def download(url):
        calls.append(url)
        return b"wrong primary bytes" if url.endswith("new.zip") else previous

    monkeypatch.setattr(assets, "download_bytes", download)
    with pytest.raises(RuntimeError, match="consumer stopped"):
        with assets.locked_package_archive(lock) as archive:
            assert assets.download_locked_package(package, archive) == old_content
            raise RuntimeError("consumer stopped")
    assert calls == [lock["package_archive"]["url"], lock["package_archive"]["fallback"]["url"]]
    assert archive.fp is None


def test_release_export_reuses_old_pins_fetches_only_new_and_verifies_complete_identity(tmp_path, monkeypatch):
    old = _record("existing", b"old exact bytes")
    new = _record("new", b"new exact bytes")
    previous = _zip([(old.filename, b"old exact bytes")])
    lock = {"package_archive": {"url": "https://example.invalid/previous.zip",
                                "sha256": hashlib.sha256(previous).hexdigest()}}
    fetched = []
    monkeypatch.setattr(assets, "unique_locked_packages", lambda _lock: [old, new])
    monkeypatch.setattr(assets, "download_bytes", lambda _url: previous)

    def download_new(filename, expected_sha256=None):
        fetched.append((filename, expected_sha256))
        assert filename == new.filename
        assert expected_sha256 == new.sha256
        return b"new exact bytes"

    monkeypatch.setattr(assets, "download_termux_main_path", download_new)
    first, second = tmp_path / "one.zip", tmp_path / "two.zip"
    first_hash = assets.build_package_archive(lock, first)
    second_hash = assets.build_package_archive(lock, second)
    assert first_hash == second_hash
    assert first.read_bytes() == second.read_bytes()
    assert fetched == [(new.filename, new.sha256)] * 2
    release_lock = {"package_archive": {"sha256": first_hash}}
    assert assets.verify_package_archive(release_lock, first)["packages"] == 2
    assert not list(tmp_path.glob("*.partial"))

    # Even when the outer hash is updated, package identity and closed inventory
    # must independently reject tampered or additional payloads.
    for entries in [[(old.filename, b"tampered"), (new.filename, b"new exact bytes")],
                    [(old.filename, b"old exact bytes")],
                    [(old.filename, b"old exact bytes"), (new.filename, b"new exact bytes"), ("extra.deb", b"extra")]]:
        payload = _zip(entries)
        candidate = tmp_path / "invalid.zip"
        candidate.write_bytes(payload)
        release_lock["package_archive"]["sha256"] = hashlib.sha256(payload).hexdigest()
        with pytest.raises(ValueError):
            assets.verify_package_archive(release_lock, candidate)


def test_archive_cli_verifies_the_release_invocation_without_extracting_assets(tmp_path, monkeypatch, capsys):
    import json
    import sys

    package = _record("package", b"exact package bytes")
    payload = _zip([(package.filename, b"exact package bytes")])
    archive = tmp_path / "release.zip"
    archive.write_bytes(payload)
    lock = {"package_archive": {"sha256": hashlib.sha256(payload).hexdigest()}}
    lock_file = tmp_path / "lock.json"
    lock_file.write_text(json.dumps(lock), encoding="utf-8")
    extraction = tmp_path / "not-extracted"
    monkeypatch.setattr(assets, "unique_locked_packages", lambda _lock: [package])
    monkeypatch.setattr(sys, "argv", ["prepare_android_linux_assets.py", "--output-dir", str(extraction),
                                     "--lock-file", str(lock_file), "--verify-package-archive", str(archive)])
    assets.main()
    assert json.loads(capsys.readouterr().out)["status"] == "verified"
    assert not extraction.exists()

    # A caller cannot accidentally pass a valid wrapper hash but wrong contents.
    archive.write_bytes(payload + b"changed")
    with pytest.raises(ValueError):
        assets.main()
