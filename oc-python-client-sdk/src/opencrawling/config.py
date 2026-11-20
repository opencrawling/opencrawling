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

"""Client configuration, per-request header options, and retry policies."""

from __future__ import annotations

import random
import time
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime

RETRYABLE_STATUSES: frozenset[int] = frozenset({429, 502, 503, 504})


def parse_retry_after(header_value: str | None, *, now: float | None = None) -> float | None:
    """Parse an HTTP ``Retry-After`` header (integer seconds or HTTP-date) into delay seconds.

    Returns ``None`` if the header is absent or malformed.
    """
    if header_value is None:
        return None
    stripped = header_value.strip()
    if not stripped:
        return None
    try:
        seconds = float(stripped)
        if seconds != seconds:  # NaN check
            return None
        return max(0.0, seconds)
    except ValueError:
        pass
    try:
        dt = parsedate_to_datetime(stripped)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=UTC)
        current_ts = time.time() if now is None else now
        current_dt = datetime.fromtimestamp(current_ts, tz=UTC)
        delta = (dt - current_dt).total_seconds()
        return max(0.0, delta)
    except (TypeError, ValueError, OverflowError):
        return None


@dataclass(frozen=True, slots=True)
class RetryPolicy:
    """Automatic retry behavior for transient transport errors and retryable HTTP responses.

    Retries safe/repeatable requests after network failures, HTTP ``429``, ``502``, ``503``,
    ``504``, and ``409`` with problem code ``idempotency-in-progress``. Waits grow exponentially
    with jitter and honor server ``Retry-After`` headers (both delta-seconds and HTTP-date).
    If ``Retry-After`` requests a wait longer than ``max_delay``, retrying stops early.

    Attributes:
        max_attempts: Total attempts including the initial try (``1`` disables retries).
        base_delay: Initial backoff delay in seconds.
        max_delay: Maximum backoff delay (and maximum accepted ``Retry-After``) in seconds.
        jitter: Whether to apply half-to-full random jitter to exponential backoff delays.
    """

    max_attempts: int = 3
    base_delay: float = 0.2
    max_delay: float = 10.0
    jitter: bool = True

    def __post_init__(self) -> None:
        if self.max_attempts < 1:
            raise ValueError("max_attempts must be at least 1")
        if self.base_delay < 0:
            raise ValueError("base_delay must be non-negative")
        if self.max_delay < 0:
            raise ValueError("max_delay must be non-negative")

    @classmethod
    def defaults(cls) -> RetryPolicy:
        """Default retry policy: 3 attempts starting at 200 ms, capped at 10 s."""
        return cls(max_attempts=3, base_delay=0.2, max_delay=10.0, jitter=True)

    @classmethod
    def none(cls) -> RetryPolicy:
        """Disable automatic retries (single attempt)."""
        return cls(max_attempts=1, base_delay=0.0, max_delay=0.0, jitter=False)

    def compute_delay(
        self,
        attempt: int,
        retry_after: str | None = None,
        *,
        now: float | None = None,
    ) -> float | None:
        """Compute wait duration in seconds before ``attempt + 1``.

        Returns ``None`` when the server's ``Retry-After`` exceeds ``max_delay``, signaling
        that the caller should surface the response instead of retrying prematurely.
        """
        parsed_retry_after = parse_retry_after(retry_after, now=now)
        if parsed_retry_after is not None:
            if parsed_retry_after > self.max_delay:
                return None
            return parsed_retry_after

        exponent = min(max(attempt - 1, 0), 20)
        capped = min(self.base_delay * (1 << exponent), self.max_delay)
        if not self.jitter or capped <= 0:
            return capped
        half = capped / 2.0
        return half + random.uniform(0.0, half)


@dataclass(frozen=True, slots=True)
class RequestOptions:
    """Optional per-request HTTP headers for Platform API v1 calls.

    Immutable; use ``with_*`` helpers or instantiate directly.

    Attributes:
        idempotency_key: Sent as ``Idempotency-Key`` on ``POST`` requests (excluding uploads).
        correlation_id: Sent as ``X-Correlation-Id``; recorded in execution ledgers.
        request_id: Sent as ``X-Request-Id``; echoed in responses and RFC 9457 problem details.
        if_match: Sent as ``If-Match`` for optimistic concurrency (``412`` on mismatch).
        if_none_match: Sent as ``If-None-Match`` for conditional reads (``304 Not Modified``).
        traceparent: Explicit W3C ``traceparent`` header for distributed tracing without OpenTelemetry.
        tracestate: Explicit W3C ``tracestate`` header paired with ``traceparent``.
        extra_headers: Additional custom HTTP headers merged onto the request.
    """

    idempotency_key: str | None = None
    correlation_id: str | None = None
    request_id: str | None = None
    if_match: str | None = None
    if_none_match: str | None = None
    traceparent: str | None = None
    tracestate: str | None = None
    extra_headers: Mapping[str, str] = field(default_factory=dict)

    @classmethod
    def none(cls) -> RequestOptions:
        """Empty options instance."""
        return cls()

    @classmethod
    def idempotent(cls, idempotency_key: str) -> RequestOptions:
        """Options with an explicit ``Idempotency-Key``."""
        return cls(idempotency_key=idempotency_key)

    @classmethod
    def matching(cls, etag: str) -> RequestOptions:
        """Options with an ``If-Match`` ETag precondition."""
        return cls(if_match=etag)

    def with_idempotency_key(self, key: str | None) -> RequestOptions:
        return RequestOptions(
            idempotency_key=key,
            correlation_id=self.correlation_id,
            request_id=self.request_id,
            if_match=self.if_match,
            if_none_match=self.if_none_match,
            traceparent=self.traceparent,
            tracestate=self.tracestate,
            extra_headers=dict(self.extra_headers),
        )

    def with_correlation_id(self, correlation_id: str | None) -> RequestOptions:
        return RequestOptions(
            idempotency_key=self.idempotency_key,
            correlation_id=correlation_id,
            request_id=self.request_id,
            if_match=self.if_match,
            if_none_match=self.if_none_match,
            traceparent=self.traceparent,
            tracestate=self.tracestate,
            extra_headers=dict(self.extra_headers),
        )

    def with_request_id(self, request_id: str | None) -> RequestOptions:
        return RequestOptions(
            idempotency_key=self.idempotency_key,
            correlation_id=self.correlation_id,
            request_id=request_id,
            if_match=self.if_match,
            if_none_match=self.if_none_match,
            traceparent=self.traceparent,
            tracestate=self.tracestate,
            extra_headers=dict(self.extra_headers),
        )

    def with_if_match(self, etag: str | None) -> RequestOptions:
        return RequestOptions(
            idempotency_key=self.idempotency_key,
            correlation_id=self.correlation_id,
            request_id=self.request_id,
            if_match=etag,
            if_none_match=self.if_none_match,
            traceparent=self.traceparent,
            tracestate=self.tracestate,
            extra_headers=dict(self.extra_headers),
        )

    def with_if_none_match(self, etag: str | None) -> RequestOptions:
        return RequestOptions(
            idempotency_key=self.idempotency_key,
            correlation_id=self.correlation_id,
            request_id=self.request_id,
            if_match=self.if_match,
            if_none_match=etag,
            traceparent=self.traceparent,
            tracestate=self.tracestate,
            extra_headers=dict(self.extra_headers),
        )

    def with_traceparent(self, traceparent: str | None, tracestate: str | None = None) -> RequestOptions:
        return RequestOptions(
            idempotency_key=self.idempotency_key,
            correlation_id=self.correlation_id,
            request_id=self.request_id,
            if_match=self.if_match,
            if_none_match=self.if_none_match,
            traceparent=traceparent,
            tracestate=tracestate if tracestate is not None else self.tracestate,
            extra_headers=dict(self.extra_headers),
        )

    def with_tracestate(self, tracestate: str | None) -> RequestOptions:
        return RequestOptions(
            idempotency_key=self.idempotency_key,
            correlation_id=self.correlation_id,
            request_id=self.request_id,
            if_match=self.if_match,
            if_none_match=self.if_none_match,
            traceparent=self.traceparent,
            tracestate=tracestate,
            extra_headers=dict(self.extra_headers),
        )

    def with_extra_headers(self, headers: Mapping[str, str]) -> RequestOptions:
        merged = dict(self.extra_headers)
        merged.update(headers)
        return RequestOptions(
            idempotency_key=self.idempotency_key,
            correlation_id=self.correlation_id,
            request_id=self.request_id,
            if_match=self.if_match,
            if_none_match=self.if_none_match,
            traceparent=self.traceparent,
            tracestate=self.tracestate,
            extra_headers=merged,
        )

    def as_defaults(self) -> RequestOptions:
        """Strip single-operation options (``idempotency_key``, ``if_match``, ``if_none_match``)
        that must not be inherited across unrelated requests.
        """
        return RequestOptions(
            correlation_id=self.correlation_id,
            request_id=self.request_id,
            traceparent=self.traceparent,
            tracestate=self.tracestate,
            extra_headers=dict(self.extra_headers),
        )

    @staticmethod
    def merge(base: RequestOptions | None, overrides: RequestOptions | None) -> RequestOptions:
        """Merge ``base`` and ``overrides`` where non-``None`` values in ``overrides`` win."""
        b = base or RequestOptions()
        o = overrides or RequestOptions()
        merged_headers = dict(b.extra_headers)
        merged_headers.update(o.extra_headers)
        return RequestOptions(
            idempotency_key=o.idempotency_key if o.idempotency_key is not None else b.idempotency_key,
            correlation_id=o.correlation_id if o.correlation_id is not None else b.correlation_id,
            request_id=o.request_id if o.request_id is not None else b.request_id,
            if_match=o.if_match if o.if_match is not None else b.if_match,
            if_none_match=o.if_none_match if o.if_none_match is not None else b.if_none_match,
            traceparent=o.traceparent if o.traceparent is not None else b.traceparent,
            tracestate=o.tracestate if o.tracestate is not None else b.tracestate,
            extra_headers=merged_headers,
        )
