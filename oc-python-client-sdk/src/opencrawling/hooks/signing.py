#
# Copyright © 2026 the original author or authors (piergiorgio@apache.org)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""HMAC-SHA256 request signing and verification matching OpenCrawling ``HttpGovernanceHook``.

Wire contract (``oc-runtime`` ``HttpGovernanceHook.java``):
- ``X-OpenCrawling-Timestamp``: Unix epoch seconds as an ASCII decimal integer string.
- ``X-OpenCrawling-Signature``: ``sha256=<lowercase hex HMAC-SHA256(secret, timestamp + "." + body)>``.
"""

from __future__ import annotations

import hashlib
import hmac
import time
from collections.abc import Mapping

from opencrawling.errors import OpenCrawlingError

SIGNATURE_HEADER = "X-OpenCrawling-Signature"
TIMESTAMP_HEADER = "X-OpenCrawling-Timestamp"
SIGNATURE_PREFIX = "sha256="
MIN_SECRET_LENGTH = 32
DEFAULT_MAX_SKEW_SECONDS = 300


class HookSignatureError(OpenCrawlingError):
    """Raised when an inbound OpenCrawling hook request fails HMAC-SHA256 or replay validation."""

    def __init__(self, message: str, *, reason: str = "invalid_signature") -> None:
        super().__init__(message)
        self.reason = reason


def _to_bytes(body: bytes | bytearray | memoryview | str) -> bytes:
    if isinstance(body, str):
        return body.encode("utf-8")
    return bytes(body)


def _validate_secret(secret: str, *, enforce_min_length: bool) -> bytes:
    if not secret or not secret.strip():
        raise HookSignatureError("Hook shared secret must not be empty", reason="invalid_secret")
    if enforce_min_length and len(secret) < MIN_SECRET_LENGTH:
        raise HookSignatureError(
            f"Hook shared secret must be at least {MIN_SECRET_LENGTH} characters",
            reason="weak_secret",
        )
    return secret.encode("utf-8")


def _lookup_header(headers: Mapping[str, str], name: str) -> str | None:
    direct = headers.get(name)
    if direct is not None:
        return direct
    target = name.lower()
    for key, value in headers.items():
        if key.lower() == target:
            return value
    return None


def sign_payload(
    body: bytes | bytearray | memoryview | str,
    secret: str,
    *,
    timestamp: int | str,
    enforce_min_length: bool = True,
) -> str:
    """Compute ``sha256=<hex>`` HMAC-SHA256 over ``f"{timestamp}." + body``.

    Produces the exact same digest as ``HttpGovernanceHook.sign(secret, timestamp, body)``
    in ``oc-runtime``.
    """
    key_bytes = _validate_secret(secret, enforce_min_length=enforce_min_length)
    ts_str = str(timestamp).strip()
    if not ts_str:
        raise HookSignatureError("Timestamp must not be empty", reason="missing_timestamp")
    mac = hmac.new(key_bytes, digestmod=hashlib.sha256)
    mac.update(ts_str.encode("utf-8"))
    mac.update(b".")
    mac.update(_to_bytes(body))
    return f"{SIGNATURE_PREFIX}{mac.hexdigest()}"


def build_signed_headers(
    body: bytes | bytearray | memoryview | str,
    secret: str,
    *,
    timestamp: int | None = None,
    enforce_min_length: bool = True,
) -> dict[str, str]:
    """Return ``X-OpenCrawling-Timestamp`` and ``X-OpenCrawling-Signature`` headers for ``body``."""
    ts = int(time.time()) if timestamp is None else int(timestamp)
    signature = sign_payload(body, secret, timestamp=ts, enforce_min_length=enforce_min_length)
    return {
        TIMESTAMP_HEADER: str(ts),
        SIGNATURE_HEADER: signature,
    }


def verify_signature(
    body: bytes | bytearray | memoryview | str,
    secret: str,
    *,
    headers: Mapping[str, str] | None = None,
    signature: str | None = None,
    timestamp: str | int | None = None,
    max_skew_seconds: int = DEFAULT_MAX_SKEW_SECONDS,
    now: float | None = None,
    enforce_min_length: bool = True,
) -> int:
    """Verify an OpenCrawling hook signature in constant time and enforce the replay window.

    Supply either ``headers`` (e.g. FastAPI/Starlette ``request.headers``) or explicit
    ``signature`` and ``timestamp`` values. Returns the verified Unix epoch timestamp (seconds)
    on success, or raises :class:`HookSignatureError`.
    """
    _validate_secret(secret, enforce_min_length=enforce_min_length)

    sig_value = signature
    ts_value: str | int | None = timestamp
    if headers is not None:
        if sig_value is None:
            sig_value = _lookup_header(headers, SIGNATURE_HEADER)
        if ts_value is None:
            ts_value = _lookup_header(headers, TIMESTAMP_HEADER)

    if ts_value is None or not str(ts_value).strip():
        raise HookSignatureError(
            f"Missing {TIMESTAMP_HEADER} header",
            reason="missing_timestamp",
        )
    if sig_value is None or not sig_value.strip():
        raise HookSignatureError(
            f"Missing {SIGNATURE_HEADER} header",
            reason="missing_signature",
        )

    ts_raw = str(ts_value).strip()
    try:
        ts_int = int(ts_raw)
    except ValueError as exc:
        raise HookSignatureError(
            f"Invalid {TIMESTAMP_HEADER} value: expected Unix epoch seconds",
            reason="invalid_timestamp",
        ) from exc

    if max_skew_seconds > 0:
        current_time = time.time() if now is None else now
        if abs(current_time - ts_int) > max_skew_seconds:
            raise HookSignatureError(
                f"Request timestamp outside allowed {max_skew_seconds}s replay window",
                reason="timestamp_expired",
            )

    actual_sig = sig_value.strip()
    if not actual_sig.startswith(SIGNATURE_PREFIX):
        raise HookSignatureError(
            f"Malformed {SIGNATURE_HEADER} header: expected '{SIGNATURE_PREFIX}' prefix",
            reason="malformed_signature",
        )

    expected_sig = sign_payload(
        body,
        secret,
        timestamp=ts_raw,
        enforce_min_length=enforce_min_length,
    )
    if not hmac.compare_digest(expected_sig.encode("ascii"), actual_sig.encode("utf-8")):
        raise HookSignatureError(
            "HMAC-SHA256 signature verification failed",
            reason="signature_mismatch",
        )
    return ts_int
