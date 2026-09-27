"""Behavioral regressions for the HTTP stack embedded in the Android Python APK.

No remote requests: both versions exercise the real request/decoder code with
bounded in-memory messages. Source pins and built-APK inventory are checked separately.
"""
import gc
import gzip
import hashlib
import tracemalloc

import httpx2
import pytest


@pytest.mark.parametrize("metadata", [
    ("safe.txt", b"safe payload", "text/plain\r\nX-Untrusted: injected"),
    ("safe.txt", b"safe payload", "text/plain", {"X-Name\r\nX-Other": "value"}),
    ("safe.txt", b"safe payload", "text/plain", {"X-Name": "value\r\nX-Other: injected"}),
])
def test_multipart_rejects_control_characters_before_serialization(metadata):
    with pytest.raises(ValueError):
        httpx2.Request("POST", "https://example.invalid/upload", files={"file": metadata}).read()


@pytest.mark.parametrize("body", [{"content": b"bounded"}, {"json": {"bounded": True}}, {"data": {"bounded": "yes"}}])
def test_outbound_http_message_has_one_framing_authority(body):
    request = httpx2.Request("POST", "https://example.invalid/", headers={"Transfer-Encoding": "chunked"}, **body)
    assert request.headers["Transfer-Encoding"] == "chunked"
    assert "Content-Length" not in request.headers
    assert request.read()


def test_streaming_compressed_content_has_bounded_intermediate_allocation():
    def measure(size):
        payload = b"a" * size
        expected = hashlib.sha256(payload).hexdigest()
        compressed = gzip.compress(payload)
        del payload
        gc.collect()
        response = httpx2.Response(200, headers={"Content-Encoding": "gzip"}, stream=httpx2.ByteStream(compressed))
        digest = hashlib.sha256()
        received = 0
        largest = 0
        tracemalloc.start()
        try:
            for chunk in response.iter_bytes():
                received += len(chunk)
                largest = max(largest, len(chunk))
                digest.update(chunk)
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
            response.close()
        assert received == size and digest.hexdigest() == expected
        # A stream must yield before materializing even the smaller complete response.
        assert largest < 8 * 1024 * 1024
        return peak

    small_peak = measure(8 * 1024 * 1024)
    large_peak = measure(16 * 1024 * 1024)
    # Test bounded growth, not an arbitrary two-buffer assumption: HTTPX2's 1 MiB
    # decoder can have several bounded chunks alive at once. Doubling the decoded
    # body must not double transient storage, and neither may hold the whole body.
    assert small_peak < 8 * 1024 * 1024
    assert large_peak <= small_peak + 1024 * 1024, (small_peak, large_peak)
