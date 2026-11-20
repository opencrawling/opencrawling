#
# Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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

"""Tests for W3C Trace Context propagation and optional OpenTelemetry spans (#409)."""

from __future__ import annotations

import sys
from collections.abc import Iterable, Iterator
from typing import Any

import httpx
import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import SpanKind, StatusCode

from opencrawling import (
    AsyncGrpcPushClient,
    AsyncOpenCrawlingClient,
    GrpcIngestionStatus,
    GrpcPushClient,
    GrpcPushDocument,
    OpenCrawlingClient,
    RequestOptions,
)
from opencrawling.ingest.grpc_push import (
    _encode_int_field,
    _encode_string_field,
)

SAMPLE_TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
SAMPLE_TRACESTATE = "congo=t61rcWkgMzE"


def _setup_in_memory_tracer() -> tuple[TracerProvider, InMemorySpanExporter]:
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    return provider, exporter


def test_explicit_request_options_traceparent_and_tracestate_sync_http() -> None:
    captured_headers: list[dict[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured_headers.append(dict(request.headers))
        return httpx.Response(200, json={"apiVersion": "v1", "runtimeVersion": "1.0.5"})

    opts = RequestOptions(traceparent=SAMPLE_TRACEPARENT, tracestate=SAMPLE_TRACESTATE)
    with OpenCrawlingClient(
        "http://localhost:8080",
        default_options=opts,
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
    ) as client:
        info = client.version()
        assert info.api_version == "v1"

    assert len(captured_headers) == 1
    assert captured_headers[0]["traceparent"] == SAMPLE_TRACEPARENT
    assert captured_headers[0]["tracestate"] == SAMPLE_TRACESTATE


async def test_explicit_traceparent_async_http_and_without_otel(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setitem(sys.modules, "opentelemetry.trace", None)
    monkeypatch.setitem(sys.modules, "opentelemetry.propagate", None)

    captured_headers: list[dict[str, str]] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        captured_headers.append(dict(request.headers))
        return httpx.Response(200, json={"apiVersion": "v1", "runtimeVersion": "1.0.5"})

    # enable_tracing=True must degrade cleanly to a no-op when opentelemetry-api is absent
    async with AsyncOpenCrawlingClient(
        "http://localhost:8080",
        enable_tracing=True,
        default_options=RequestOptions(traceparent=SAMPLE_TRACEPARENT),
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    ) as client:
        info = await client.version()
        assert info.runtime_version == "1.0.5"

    assert captured_headers[0]["traceparent"] == SAMPLE_TRACEPARENT


def test_active_otel_span_propagates_traceparent_and_emits_client_span(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider, exporter = _setup_in_memory_tracer()
    monkeypatch.setattr(trace, "get_tracer", provider.get_tracer)
    tracer = provider.get_tracer("test.caller")

    captured_headers: list[dict[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured_headers.append(dict(request.headers))
        return httpx.Response(200, json={"apiVersion": "v1", "runtimeVersion": "1.0.5"})

    with (
        OpenCrawlingClient(
            "http://localhost:8080",
            enable_tracing=True,
            http_client=httpx.Client(transport=httpx.MockTransport(handler)),
        ) as client,
        tracer.start_as_current_span("parent-workflow") as parent_span,
    ):
        client.version()
        parent_trace_id = f"{parent_span.get_span_context().trace_id:032x}"

    assert len(captured_headers) == 1
    sent_tp = captured_headers[0]["traceparent"]
    assert sent_tp.startswith(f"00-{parent_trace_id}-")

    finished = exporter.get_finished_spans()
    client_spans = [s for s in finished if s.kind == SpanKind.CLIENT]
    assert len(client_spans) == 1
    span = client_spans[0]
    assert span.name == "HTTP GET"
    assert span.attributes is not None
    assert span.attributes["http.request.method"] == "GET"
    assert span.attributes["http.response.status_code"] == 200
    # Client span ID must match the traceparent sent on the wire
    client_ctx = span.get_span_context()
    client_span_id = f"{client_ctx.span_id:016x}"
    assert client_ctx.trace_flags.sampled is True
    assert sent_tp == f"00-{parent_trace_id}-{client_span_id}-{client_ctx.trace_flags:02x}"


async def test_async_http_client_span_records_5xx_error_status(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider, exporter = _setup_in_memory_tracer()
    monkeypatch.setattr(trace, "get_tracer", provider.get_tracer)

    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"status": "UNHEALTHY"})

    async with AsyncOpenCrawlingClient(
        "http://localhost:8080",
        enable_tracing=True,
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    ) as client:
        health = await client.health()
        assert health.status == "UNHEALTHY"

    client_spans = [s for s in exporter.get_finished_spans() if s.kind == SpanKind.CLIENT]
    assert len(client_spans) == 1
    assert client_spans[0].status.status_code == StatusCode.ERROR
    assert client_spans[0].attributes is not None
    assert client_spans[0].attributes["http.response.status_code"] == 503


def test_grpc_push_propagates_traceparent_and_emits_client_span(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider, exporter = _setup_in_memory_tracer()
    monkeypatch.setattr(trace, "get_tracer", provider.get_tracer)
    tracer = provider.get_tracer("test.grpc.caller")

    push_resp_wire = (
        _encode_string_field(1, "doc-1")
        + _encode_int_field(2, int(GrpcIngestionStatus.ACCEPTED))
        + _encode_string_field(3, "ok")
    )
    recorded_metadata: list[tuple[tuple[str, str], ...]] = []

    class FakeChannel:
        def unary_unary(self, method: str, request_serializer: Any, response_deserializer: Any) -> Any:
            def _call(req_obj: Any, timeout: float = 0.0, metadata: Any = ()) -> Any:
                request_serializer(req_obj)
                recorded_metadata.append(tuple(metadata))
                return response_deserializer(push_resp_wire)

            return _call

    doc = GrpcPushDocument(id="doc-1", text_content="hello")
    with (
        GrpcPushClient(
            "localhost:9096",
            bearer_token="tok",
            enable_tracing=True,
            channel=FakeChannel(),
        ) as client,
        tracer.start_as_current_span("grpc-parent") as parent_span,
    ):
        resp = client.push_document("job-1", doc)
        assert resp.is_accepted
        parent_trace_id = f"{parent_span.get_span_context().trace_id:032x}"

    meta_dict = dict(recorded_metadata[0])
    assert meta_dict["authorization"] == "Bearer tok"
    assert meta_dict["traceparent"].startswith(f"00-{parent_trace_id}-")

    client_spans = [s for s in exporter.get_finished_spans() if s.kind == SpanKind.CLIENT]
    assert len(client_spans) == 1
    assert client_spans[0].name == "opencrawling.push.v1.OisPushService/PushDocument"


async def test_async_grpc_push_explicit_traceparent() -> None:
    push_resp_wire = (
        _encode_string_field(1, "doc-2")
        + _encode_int_field(2, int(GrpcIngestionStatus.ACCEPTED))
    )
    recorded_metadata: list[tuple[tuple[str, str], ...]] = []

    class FakeAsyncChannel:
        def unary_unary(self, method: str, request_serializer: Any, response_deserializer: Any) -> Any:
            async def _call(req_obj: Any, timeout: float = 0.0, metadata: Any = ()) -> Any:
                request_serializer(req_obj)
                recorded_metadata.append(tuple(metadata))
                return response_deserializer(push_resp_wire)

            return _call

        def stream_unary(self, method: str, request_serializer: Any, response_deserializer: Any) -> Any:
            async def _call(req_iter: Iterable[Any], timeout: float = 0.0, metadata: Any = ()) -> Any:
                recorded_metadata.append(tuple(metadata))
                return response_deserializer(b"")

            return _call

        def stream_stream(self, method: str, request_serializer: Any, response_deserializer: Any) -> Any:
            def _call(req_iter: Iterable[Any], timeout: float = 0.0, metadata: Any = ()) -> Iterator[Any]:
                recorded_metadata.append(tuple(metadata))
                return iter(())

            return _call

    doc = GrpcPushDocument(id="doc-2", text_content="async hello")
    async with AsyncGrpcPushClient(
        "localhost:9096",
        traceparent=SAMPLE_TRACEPARENT,
        tracestate=SAMPLE_TRACESTATE,
        channel=FakeAsyncChannel(),
    ) as client:
        resp = await client.push_document("job-2", doc)
        assert resp.is_accepted

    meta_dict = dict(recorded_metadata[0])
    assert meta_dict["traceparent"] == SAMPLE_TRACEPARENT
    assert meta_dict["tracestate"] == SAMPLE_TRACESTATE
