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

"""Tests for OIS Direct Ingestion, fail-closed SecurityConfig, optional gRPC Push, and QueryResult."""

from __future__ import annotations

import json
import sys
from collections.abc import Iterable, Iterator
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from opencrawling import (
    AsyncGrpcPushClient,
    AsyncOpenCrawlingClient,
    GrpcDocumentAction,
    GrpcIngestionStatus,
    GrpcPushClient,
    GrpcPushDocument,
    GrpcPushOptions,
    GrpcPushRequest,
    GrpcSecurityConfig,
    OisContent,
    OisDocument,
    OisSource,
    OpenCrawlingClient,
    PermissionRule,
    QueryResult,
    RetryPolicy,
    SecurityConfig,
)
from opencrawling.ingest.grpc_push import (
    METHOD_BIDI_STREAM,
    METHOD_PING,
    METHOD_PUSH_DOCUMENT,
    METHOD_STREAM_DOCUMENTS,
    _encode_bytes_field,
    _encode_int_field,
    _encode_string_field,
    decode_ingestion_ack,
    decode_ping_response,
    decode_push_response,
    decode_stream_summary,
    encode_ping_request,
    encode_push_request,
)


def test_ois_document_fail_closed_defaults_and_validation() -> None:
    sec = SecurityConfig()
    assert sec.inheritance_enabled is False
    assert sec.evaluation_policy == "FAIL_CLOSED"
    assert sec.permissions == []

    doc = OisDocument.upsert(
        id="custom://crm/accounts/ACME-2026",
        content=OisContent.markdown("# ACME Corp"),
        metadata={"region": "EMEA", "tier": "enterprise"},
        security=SecurityConfig.fail_closed(
            [
                PermissionRule.allow_group("sales-emea"),
                PermissionRule.allow_user("alice@example.com"),
                PermissionRule.allow_role("auditor"),
                PermissionRule.deny_user("contractor@example.com"),
                PermissionRule.deny_group("external"),
                PermissionRule.deny_role("guest"),
            ]
        ),
        source=OisSource.of("crm", "https://crm.example.com"),
    )
    payload = doc.to_payload()
    assert payload["id"] == "custom://crm/accounts/ACME-2026"
    assert payload["action"] == "UPSERT"
    assert payload["content"] == {"mimeType": "text/markdown", "text": "# ACME Corp"}
    assert payload["security"]["inheritanceEnabled"] is False
    assert payload["security"]["evaluationPolicy"] == "FAIL_CLOSED"
    assert payload["security"]["permissions"][0] == {
        "identity": "sales-emea",
        "identityType": "group",
        "access": "read",
    }
    assert payload["security"]["permissions"][3] == {
        "identity": "contractor@example.com",
        "identityType": "user",
        "access": "deny",
    }

    tombstone = OisDocument.delete(id="custom://crm/accounts/ACME-2026")
    assert tombstone.to_payload() == {"id": "custom://crm/accounts/ACME-2026", "action": "DELETE"}

    # UPSERT without security must be rejected (fail-closed!)
    with pytest.raises(ValidationError, match="security is required"):
        OisDocument(id="doc-1", action="UPSERT", content=OisContent.plain("hi"), security=None)

    # UPSERT without content must be rejected
    with pytest.raises(ValidationError, match=r"content\.text is required"):
        OisDocument(id="doc-1", action="UPSERT", content=None, security=SecurityConfig.none())

    # Blank permission identity rejected
    with pytest.raises(ValidationError):
        PermissionRule.allow_user("   ")


def test_ois_http_push_with_scoped_token_and_retry_without_idempotency_key() -> None:
    captured: list[tuple[str, str | None, str | None, dict[str, Any]]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(
            (
                request.url.path,
                request.headers.get("authorization"),
                request.headers.get("idempotency-key"),
                json.loads(request.content.decode("utf-8")),
            )
        )
        if len(captured) == 1:
            return httpx.Response(503, text="kafka temporarily unavailable")
        return httpx.Response(202)

    client = OpenCrawlingClient(
        "http://localhost:8080",
        bearer_token="mgmt-token",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
        retry_policy=RetryPolicy(max_attempts=2, base_delay=0.0, max_delay=1.0, jitter=False),
    )
    scoped_ingest = client.ingest.with_token("ois-secret-token")
    doc = OisDocument.upsert(
        id="doc-42",
        content=OisContent.plain("Quarterly report"),
        security=SecurityConfig.fail_closed([PermissionRule.allow_group("finance")]),
    )
    receipt = scoped_ingest.push_ois("crm-rag-pipeline", doc)
    assert receipt.is_accepted is True
    assert receipt.status == 202
    assert receipt.job_id == "crm-rag-pipeline"
    assert receipt.document_id == "doc-42"
    assert len(captured) == 2
    assert captured[0][0] == "/api/v1/ingest/ois/crm-rag-pipeline"
    assert captured[0][1] == "Bearer ois-secret-token"
    assert captured[0][2] is None  # No Idempotency-Key attached to OIS direct ingestion


async def test_async_ois_ingest_upsert_and_delete() -> None:
    actions: list[str] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content.decode("utf-8"))
        actions.append(body["action"])
        return httpx.Response(202)

    async with AsyncOpenCrawlingClient(
        "http://localhost:8080",
        bearer_token="ois-tok",
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    ) as client:
        r1 = await client.ingest.upsert(
            "job-1",
            "doc-1",
            OisContent.html("<p>Hello</p>"),
            security=SecurityConfig.none(),
        )
        r2 = await client.ingest.delete("job-1", "doc-1")
    assert r1.is_accepted and r2.is_accepted
    assert actions == ["UPSERT", "DELETE"]


def test_grpc_push_wire_codec_and_mock_channel_rpcs() -> None:
    ois_doc = OisDocument.upsert(
        id="grpc-doc-1",
        content=OisContent.markdown("# Title"),
        metadata={"dept": "legal"},
        security=SecurityConfig.fail_closed(
            [PermissionRule.allow_group("legal"), PermissionRule.deny_user("bob")]
        ),
    )
    grpc_doc = GrpcPushDocument.from_ois(ois_doc, timestamp=1700000000000, checksum_sha256="abc123")
    assert grpc_doc.action == GrpcDocumentAction.UPSERT
    assert grpc_doc.security is not None
    assert grpc_doc.security.allow_sids == ("legal",)
    assert grpc_doc.security.deny_sids == ("bob",)

    req = GrpcPushRequest(
        job_id="1",
        document=grpc_doc,
        options=GrpcPushOptions(bypass_tika_extraction=True, immediate_flush=True, routing_key="k1"),
    )
    wire = encode_push_request(req)
    assert b"grpc-doc-1" in wire
    assert b"abc123" in wire
    assert encode_ping_request("py-sdk") == b"\n\x06py-sdk"

    # Build wire-encoded server responses matching ois_push_service.proto field numbers
    push_resp_wire = (
        _encode_string_field(1, "grpc-doc-1")
        + _encode_int_field(2, int(GrpcIngestionStatus.ACCEPTED))
        + _encode_string_field(3, "Published")
        + _encode_int_field(4, 1700000000123)
    )
    decoded_push = decode_push_response(push_resp_wire)
    assert decoded_push.is_accepted
    assert decoded_push.document_id == "grpc-doc-1"
    assert decoded_push.processed_at == 1700000000123

    failure_wire = (
        _encode_string_field(1, "doc-bad")
        + _encode_string_field(2, "REJECTED_INVALID_PAYLOAD")
        + _encode_string_field(3, "empty")
    )
    summary_wire = (
        _encode_int_field(1, 3)
        + _encode_int_field(2, 2)
        + _encode_int_field(3, 1)
        + _encode_int_field(4, 15)
        + _encode_bytes_field(5, failure_wire)
    )
    summary = decode_stream_summary(summary_wire)
    assert summary.total_received == 3
    assert summary.total_accepted == 2
    assert summary.total_rejected == 1
    assert summary.failures[0].document_id == "doc-bad"

    ack_wire = (
        _encode_string_field(1, "grpc-doc-1")
        + _encode_int_field(2, int(GrpcIngestionStatus.QUEUED))
        + _encode_int_field(4, 1700000000999)
    )
    ack = decode_ingestion_ack(ack_wire)
    assert ack.is_accepted
    assert ack.status == GrpcIngestionStatus.QUEUED

    ping_wire = (
        _encode_string_field(1, "UP")
        + _encode_int_field(2, 1700000001000)
        + _encode_string_field(3, "1.0.5")
    )
    ping = decode_ping_response(ping_wire)
    assert ping.status == "UP" and ping.version == "1.0.5"

    # Exercise GrpcPushClient with a fake channel (works without grpcio installed)
    recorded_rpcs: list[tuple[str, Any]] = []

    class FakeChannel:
        def unary_unary(self, method: str, request_serializer: Any, response_deserializer: Any) -> Any:
            def _call(req_obj: Any, timeout: float = 0.0, metadata: Any = ()) -> Any:
                raw = request_serializer(req_obj)
                recorded_rpcs.append((method, metadata))
                assert isinstance(raw, bytes)
                if method == METHOD_PING:
                    return response_deserializer(ping_wire)
                return response_deserializer(push_resp_wire)

            return _call

        def stream_unary(self, method: str, request_serializer: Any, response_deserializer: Any) -> Any:
            def _call(req_iter: Iterable[Any], timeout: float = 0.0, metadata: Any = ()) -> Any:
                items = [request_serializer(item) for item in req_iter]
                recorded_rpcs.append((method, (len(items), metadata)))
                return response_deserializer(summary_wire)

            return _call

        def stream_stream(self, method: str, request_serializer: Any, response_deserializer: Any) -> Any:
            def _call(req_iter: Iterable[Any], timeout: float = 0.0, metadata: Any = ()) -> Iterator[Any]:
                items = [request_serializer(item) for item in req_iter]
                recorded_rpcs.append((method, (len(items), metadata)))
                for _ in items:
                    yield response_deserializer(ack_wire)

            return _call

    with GrpcPushClient("localhost:9096", bearer_token="grpc-secret", channel=FakeChannel()) as grpc_client:
        assert "grpc-secret" not in repr(grpc_client)
        assert grpc_client.ping().status == "UP"
        resp = grpc_client.push_document("1", ois_doc)
        assert resp.is_accepted
        stream_res = grpc_client.stream_documents([req, req])
        assert stream_res.total_accepted == 2
        acks = list(grpc_client.bi_directional_stream([req]))
        assert len(acks) == 1 and acks[0].is_accepted

    assert [m for m, _ in recorded_rpcs] == [
        METHOD_PING,
        METHOD_PUSH_DOCUMENT,
        METHOD_STREAM_DOCUMENTS,
        METHOD_BIDI_STREAM,
    ]
    assert recorded_rpcs[1][1] == (("authorization", "Bearer grpc-secret"),)


async def test_async_grpc_push_client_and_missing_extra_guidance(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setitem(sys.modules, "grpc", None)
    with pytest.raises(ImportError, match="opencrawling-sdk\\[grpc\\]"):
        GrpcPushClient("localhost:9096")
    with pytest.raises(ImportError, match="opencrawling-sdk\\[grpc\\]"):
        AsyncGrpcPushClient("localhost:9096")


def test_grpc_document_rejects_multiple_content_oneof_fields() -> None:
    with pytest.raises(ValueError, match="Only one of"):
        GrpcPushDocument(
            id="doc-x",
            raw_bytes=b"hello",
            text_content="hello",
            security=GrpcSecurityConfig(allow_sids=("u1",)),
        )


def test_query_result_to_dicts_and_optional_dataframe_import_error(monkeypatch: pytest.MonkeyPatch) -> None:
    qr = QueryResult(columns=["status", "docs"], rows=[["SUCCESS", 10], ["FAILED", 1]])
    assert qr.to_dicts() == [{"status": "SUCCESS", "docs": 10}, {"status": "FAILED", "docs": 1}]

    for mod in ("pyarrow", "pandas", "polars"):
        monkeypatch.setitem(sys.modules, mod, None)
    for method in (qr.to_pyarrow, qr.to_pandas, qr.to_polars):
        with pytest.raises(ImportError, match="opencrawling-sdk\\[dataframe\\]"):
            method()


def test_grpc_metadata_normalization_language_neutral_and_validation() -> None:
    from datetime import UTC, date, datetime
    from decimal import Decimal

    from opencrawling import normalize_grpc_metadata

    raw_meta: dict[str, Any] = {
        "title": "Q4 Earnings",
        "published": True,
        "archived": False,
        "page_count": 42,
        "score": 0.95,
        "amount": Decimal("1234.50"),
        "published_date": date(2026, 4, 15),
        "updated_at": datetime(2026, 4, 15, 10, 30, 0, tzinfo=UTC),
        "tags": ["finance", "q4", "public"],
        "flags": (True, False),
        "counts": [1, 2, 3],
        "empty_list": [],
        "omitted": None,
        "bracket_literal": "[DRAFT] Q4 Report",
    }

    normalized = normalize_grpc_metadata(raw_meta)
    assert normalized == {
        "title": "Q4 Earnings",
        "published": "true",
        "archived": "false",
        "page_count": "42",
        "score": "0.95",
        "amount": "1234.50",
        "published_date": "2026-04-15",
        "updated_at": "2026-04-15T10:30:00Z",
        "tags": '["finance","q4","public"]',
        "flags": "[true,false]",
        "counts": "[1,2,3]",
        "empty_list": "[]",
        "bracket_literal": "[DRAFT] Q4 Report",
    }
    assert "omitted" not in normalized

    # Works identically via GrpcPushDocument.from_ois and direct GrpcPushDocument construction
    ois_doc = OisDocument.upsert(
        id="doc-meta-1",
        content=OisContent.plain("hello"),
        metadata={"tags": ["alpha", "beta"], "published": True, "skip": None},
        security=SecurityConfig.none(),
    )
    from_ois = GrpcPushDocument.from_ois(ois_doc)
    assert from_ois.metadata == {"tags": '["alpha","beta"]', "published": "true"}

    direct = GrpcPushDocument(
        id="doc-meta-2",
        text_content="hello",
        metadata={"tags": ["alpha", "beta"], "published": False},
    )
    assert direct.metadata == {"tags": '["alpha","beta"]', "published": "false"}

    # Rejects non-finite floats/Decimals and unsupported container/object types
    with pytest.raises(ValueError, match="Non-finite float"):
        normalize_grpc_metadata({"bad": float("nan")})
    with pytest.raises(ValueError, match="Non-finite float"):
        normalize_grpc_metadata({"bad": float("inf")})
    with pytest.raises(ValueError, match="Non-finite Decimal"):
        normalize_grpc_metadata({"bad": Decimal("NaN")})
    with pytest.raises(TypeError, match="Unsupported gRPC metadata value type"):
        normalize_grpc_metadata({"nested": {"a": 1}})
    with pytest.raises(TypeError, match="Unsupported gRPC metadata value type"):
        normalize_grpc_metadata({"raw": b"bytes"})
    with pytest.raises(TypeError, match="Unsupported element type"):
        normalize_grpc_metadata({"nested_list": [["a"]]})
