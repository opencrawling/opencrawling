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

"""Optional W3C Trace Context (``traceparent`` / ``tracestate``) and OpenTelemetry client span helpers (#409).

When ``opentelemetry-api`` (optional extra ``opencrawling-sdk[otel]``) is not installed, all
OpenTelemetry hooks degrade silently to zero-overhead no-ops while explicit
``RequestOptions(traceparent=..., tracestate=...)`` propagation continues to work.
"""

from __future__ import annotations

from collections.abc import Iterator, Mapping, Sequence
from contextlib import contextmanager
from importlib import import_module
from typing import Any

from opencrawling._version import __version__

TRACEPARENT_HEADER = "traceparent"
TRACESTATE_HEADER = "tracestate"
_TRACER_NAME = "opencrawling.sdk"


def _load_otel() -> tuple[Any, Any] | None:
    """Import ``(opentelemetry.trace, opentelemetry.propagate)`` if installed, else ``None``."""
    try:
        trace_mod = import_module("opentelemetry.trace")
        propagate_mod = import_module("opentelemetry.propagate")
        return trace_mod, propagate_mod
    except ImportError:
        return None


def _has_header_case_insensitive(headers: Mapping[str, str], target: str) -> bool:
    lower = target.lower()
    return any(k.lower() == lower for k in headers)


def inject_trace_headers(
    headers: dict[str, str],
    *,
    traceparent: str | None = None,
    tracestate: str | None = None,
) -> None:
    """Inject W3C ``traceparent`` and ``tracestate`` into an outbound HTTP headers dict.

    Precedence:
    1. Headers already present in ``headers`` (e.g. via ``extra_headers``) are never overwritten.
    2. Explicit ``traceparent`` / ``tracestate`` arguments (from ``RequestOptions``).
    3. Active OpenTelemetry span context via ``opentelemetry.propagate.inject`` (when installed).
    """
    if traceparent and traceparent.strip():
        if not _has_header_case_insensitive(headers, TRACEPARENT_HEADER):
            headers[TRACEPARENT_HEADER] = traceparent.strip()
        if tracestate and tracestate.strip() and not _has_header_case_insensitive(headers, TRACESTATE_HEADER):
            headers[TRACESTATE_HEADER] = tracestate.strip()
        return

    if tracestate and tracestate.strip() and not _has_header_case_insensitive(headers, TRACESTATE_HEADER):
        headers[TRACESTATE_HEADER] = tracestate.strip()

    if _has_header_case_insensitive(headers, TRACEPARENT_HEADER):
        return

    otel = _load_otel()
    if otel is None:
        return
    trace_mod, propagate_mod = otel
    span = trace_mod.get_current_span()
    if span is None:
        return
    ctx = span.get_span_context()
    if ctx is None or not ctx.is_valid:
        return

    carrier: dict[str, str] = {}
    propagate_mod.inject(carrier)
    for key, value in carrier.items():
        if value and not _has_header_case_insensitive(headers, key):
            headers[key] = value


def inject_grpc_metadata(
    metadata: Sequence[tuple[str, str]],
    *,
    traceparent: str | None = None,
    tracestate: str | None = None,
) -> tuple[tuple[str, str], ...]:
    """Return gRPC metadata tuples enriched with lowercase W3C ``traceparent`` / ``tracestate``."""
    entries = list(metadata)
    existing_keys = {k.lower() for k, _ in entries}
    carrier: dict[str, str] = {}
    inject_trace_headers(carrier, traceparent=traceparent, tracestate=tracestate)
    for key, value in carrier.items():
        lower_key = key.lower()
        if lower_key not in existing_keys and value:
            entries.append((lower_key, value))
            existing_keys.add(lower_key)
    return tuple(entries)


@contextmanager
def start_client_span(
    enabled: bool,
    name: str,
    attributes: Mapping[str, Any] | None = None,
) -> Iterator[Any | None]:
    """Optionally start an OpenTelemetry ``CLIENT`` span around an SDK call.

    Yields the active span when ``enabled=True`` and ``opentelemetry-api`` is installed;
    otherwise yields ``None`` with zero overhead.
    """
    if not enabled:
        yield None
        return
    otel = _load_otel()
    if otel is None:
        yield None
        return
    trace_mod, _ = otel
    tracer = trace_mod.get_tracer(_TRACER_NAME, __version__)
    span_kind = getattr(trace_mod.SpanKind, "CLIENT", None)
    clean_attrs = {k: v for k, v in (attributes or {}).items() if v is not None}
    with tracer.start_as_current_span(name, kind=span_kind, attributes=clean_attrs) as span:
        yield span


def record_span_error(span: Any | None, exc: BaseException) -> None:
    """Record an exception and set ERROR status on an OpenTelemetry span if present."""
    if span is None:
        return
    otel = _load_otel()
    if otel is None:
        return
    trace_mod, _ = otel
    span.record_exception(exc)
    status_code = getattr(trace_mod, "StatusCode", None)
    if status_code is not None:
        span.set_status(status_code.ERROR, str(exc))


def set_span_http_status(span: Any | None, status_code: int, *, retries: int = 0) -> None:
    """Annotate an OpenTelemetry client span with HTTP status code and retry count."""
    if span is None:
        return
    span.set_attribute("http.response.status_code", status_code)
    if retries > 0:
        span.set_attribute("opencrawling.retries", retries)
    if status_code >= 500:
        otel = _load_otel()
        if otel is not None:
            trace_mod, _ = otel
            sc = getattr(trace_mod, "StatusCode", None)
            if sc is not None:
                span.set_status(sc.ERROR, f"HTTP {status_code}")
