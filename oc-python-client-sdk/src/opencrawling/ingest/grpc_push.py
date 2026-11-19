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

"""Optional high-throughput gRPC Push streaming client (``opencrawling-sdk[grpc]``).

Implements the exact Protobuf v3 wire contract of
``opencrawling.push.v1.OisPushService`` (``ois_push_service.proto``) exposed by
``oc-grpc-push-connector`` (:9096) for Unary, Client-Streaming, Bi-Directional
Streaming, and Ping RPCs.
"""

from __future__ import annotations

from collections.abc import AsyncIterable, AsyncIterator, Iterable, Iterator, Mapping
from dataclasses import dataclass, field
from enum import IntEnum
from importlib import import_module
from typing import Any

from opencrawling.ingest.ois import OisDocument

SERVICE_NAME = "opencrawling.push.v1.OisPushService"
METHOD_PUSH_DOCUMENT = f"/{SERVICE_NAME}/PushDocument"
METHOD_STREAM_DOCUMENTS = f"/{SERVICE_NAME}/StreamDocuments"
METHOD_BIDI_STREAM = f"/{SERVICE_NAME}/BiDirectionalStream"
METHOD_PING = f"/{SERVICE_NAME}/Ping"


class GrpcDocumentAction(IntEnum):
    """Lifecycle action matching ``opencrawling.push.v1.DocumentAction``."""

    ACTION_UNSPECIFIED = 0
    UPSERT = 1
    DELETE = 2


class GrpcIngestionStatus(IntEnum):
    """Status codes matching ``opencrawling.push.v1.IngestionStatus``."""

    STATUS_UNSPECIFIED = 0
    ACCEPTED = 1
    QUEUED = 2
    REJECTED_INVALID_PAYLOAD = 3
    REJECTED_JOB_NOT_FOUND = 4
    REJECTED_UNAUTHORIZED = 5
    REJECTED_RATE_LIMITED = 6
    ERROR_INTERNAL = 7


@dataclass(frozen=True, slots=True)
class GrpcSecurityConfig:
    """Zero-Trust security permissions matching ``opencrawling.push.v1.SecurityConfig``."""

    allow_sids: tuple[str, ...] = ()
    deny_sids: tuple[str, ...] = ()
    owner: str = ""
    tenant_id: str = ""
    attributes: Mapping[str, str] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class GrpcPushOptions:
    """Ingestion routing options matching ``opencrawling.push.v1.PushOptions``."""

    bypass_tika_extraction: bool = False
    immediate_flush: bool = False
    routing_key: str = ""


@dataclass(frozen=True, slots=True)
class GrpcPushDocument:
    """OIS document payload matching ``opencrawling.push.v1.OisDocumentPayload``.

    Supply at most one of ``raw_bytes``, ``claim_check_uri``, or ``text_content``.
    """

    id: str
    action: GrpcDocumentAction = GrpcDocumentAction.UPSERT
    mime_type: str = "text/plain"
    timestamp: int = 0
    raw_bytes: bytes | None = None
    claim_check_uri: str | None = None
    text_content: str | None = None
    metadata: Mapping[str, str] = field(default_factory=dict)
    security: GrpcSecurityConfig | None = None
    checksum_sha256: str = ""

    def __post_init__(self) -> None:
        set_count = sum(
            v is not None for v in (self.raw_bytes, self.claim_check_uri, self.text_content)
        )
        if set_count > 1:
            raise ValueError(
                "Only one of raw_bytes, claim_check_uri, or text_content may be set"
            )

    @classmethod
    def from_ois(
        cls,
        document: OisDocument,
        *,
        timestamp: int = 0,
        checksum_sha256: str = "",
    ) -> GrpcPushDocument:
        """Convert an HTTP ``OisDocument`` into a ``GrpcPushDocument``."""
        action = (
            GrpcDocumentAction.DELETE
            if document.action == OisDocument.ACTION_DELETE
            else GrpcDocumentAction.UPSERT
        )
        mime_type = document.content.mime_type if document.content else "text/plain"
        text_content = document.content.text if document.content else None
        metadata = (
            {str(k): str(v) for k, v in document.metadata.items() if v is not None}
            if document.metadata
            else {}
        )
        security: GrpcSecurityConfig | None = None
        if document.security is not None:
            allow_sids: list[str] = []
            deny_sids: list[str] = []
            for rule in document.security.permissions:
                if rule.access.lower() == "deny":
                    deny_sids.append(rule.identity)
                else:
                    allow_sids.append(rule.identity)
            security = GrpcSecurityConfig(
                allow_sids=tuple(allow_sids),
                deny_sids=tuple(deny_sids),
            )
        return cls(
            id=document.id,
            action=action,
            mime_type=mime_type,
            timestamp=timestamp,
            text_content=text_content,
            metadata=metadata,
            security=security,
            checksum_sha256=checksum_sha256,
        )


@dataclass(frozen=True, slots=True)
class GrpcPushRequest:
    """Wrapper matching ``opencrawling.push.v1.PushDocumentRequest``."""

    job_id: str
    document: GrpcPushDocument
    options: GrpcPushOptions | None = None


@dataclass(frozen=True, slots=True)
class GrpcPushResponse:
    """Unary push outcome matching ``opencrawling.push.v1.PushDocumentResponse``."""

    document_id: str = ""
    status: GrpcIngestionStatus = GrpcIngestionStatus.STATUS_UNSPECIFIED
    message: str = ""
    processed_at: int = 0

    @property
    def is_accepted(self) -> bool:
        return self.status in {GrpcIngestionStatus.ACCEPTED, GrpcIngestionStatus.QUEUED}


@dataclass(frozen=True, slots=True)
class GrpcPushFailure:
    """Per-document failure inside ``StreamSummaryResponse``."""

    document_id: str = ""
    error_code: str = ""
    reason: str = ""


@dataclass(frozen=True, slots=True)
class GrpcStreamSummary:
    """Client-streaming summary matching ``opencrawling.push.v1.StreamSummaryResponse``."""

    total_received: int = 0
    total_accepted: int = 0
    total_rejected: int = 0
    duration_ms: int = 0
    failures: tuple[GrpcPushFailure, ...] = ()


@dataclass(frozen=True, slots=True)
class GrpcIngestionAck:
    """Bi-directional streaming acknowledgment matching ``opencrawling.push.v1.IngestionAck``."""

    document_id: str = ""
    status: GrpcIngestionStatus = GrpcIngestionStatus.STATUS_UNSPECIFIED
    error_message: str = ""
    ack_timestamp: int = 0

    @property
    def is_accepted(self) -> bool:
        return self.status in {GrpcIngestionStatus.ACCEPTED, GrpcIngestionStatus.QUEUED}


@dataclass(frozen=True, slots=True)
class GrpcPingResponse:
    """Diagnostic ping response matching ``opencrawling.push.v1.PingResponse``."""

    status: str = ""
    server_time: int = 0
    version: str = ""


# ---------------------------------------------------------------------------
# Proto3 Wire Format Codec (zero codegen fragility across protobuf versions)
# ---------------------------------------------------------------------------


def _encode_varint(value: int) -> bytes:
    if value < 0:
        value += 1 << 64
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            break
    return bytes(out)


def _decode_varint(buf: memoryview, pos: int) -> tuple[int, int]:
    shift = 0
    result = 0
    while pos < len(buf):
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            return result, pos
        shift += 7
        if shift >= 70:
            raise ValueError("Malformed protobuf varint")
    raise ValueError("Truncated protobuf varint")


def _encode_tag(field_number: int, wire_type: int) -> bytes:
    return _encode_varint((field_number << 3) | wire_type)


def _encode_bytes_field(field_number: int, value: bytes) -> bytes:
    return _encode_tag(field_number, 2) + _encode_varint(len(value)) + value


def _encode_string_field(field_number: int, value: str) -> bytes:
    encoded = value.encode("utf-8")
    return _encode_bytes_field(field_number, encoded)


def _encode_int_field(field_number: int, value: int) -> bytes:
    return _encode_tag(field_number, 0) + _encode_varint(value)


def _encode_map_entry(field_number: int, key: str, value: str) -> bytes:
    entry = bytearray()
    if key:
        entry.extend(_encode_string_field(1, key))
    if value:
        entry.extend(_encode_string_field(2, value))
    return _encode_bytes_field(field_number, bytes(entry))


def _iter_fields(data: bytes) -> Iterator[tuple[int, int, int | bytes]]:
    view = memoryview(data)
    pos = 0
    length = len(view)
    while pos < length:
        tag, pos = _decode_varint(view, pos)
        field_number = tag >> 3
        wire_type = tag & 0x07
        if wire_type == 0:
            val, pos = _decode_varint(view, pos)
            yield field_number, wire_type, val
        elif wire_type == 2:
            size, pos = _decode_varint(view, pos)
            if pos + size > length:
                raise ValueError("Truncated length-delimited protobuf field")
            chunk = bytes(view[pos : pos + size])
            pos += size
            yield field_number, wire_type, chunk
        elif wire_type == 1:
            pos += 8
        elif wire_type == 5:
            pos += 4
        else:
            raise ValueError(f"Unsupported protobuf wire type: {wire_type}")


def _to_signed_int64(val: int) -> int:
    return val - (1 << 64) if val >= (1 << 63) else val


def _encode_security_config(sec: GrpcSecurityConfig) -> bytes:
    out = bytearray()
    for sid in sec.allow_sids:
        out.extend(_encode_string_field(1, sid))
    for sid in sec.deny_sids:
        out.extend(_encode_string_field(2, sid))
    if sec.owner:
        out.extend(_encode_string_field(3, sec.owner))
    if sec.tenant_id:
        out.extend(_encode_string_field(4, sec.tenant_id))
    for k, v in sec.attributes.items():
        out.extend(_encode_map_entry(5, k, v))
    return bytes(out)


def _encode_push_options(opts: GrpcPushOptions) -> bytes:
    out = bytearray()
    if opts.bypass_tika_extraction:
        out.extend(_encode_int_field(1, 1))
    if opts.immediate_flush:
        out.extend(_encode_int_field(2, 1))
    if opts.routing_key:
        out.extend(_encode_string_field(3, opts.routing_key))
    return bytes(out)


def _encode_ois_document_payload(doc: GrpcPushDocument) -> bytes:
    out = bytearray()
    if doc.id:
        out.extend(_encode_string_field(1, doc.id))
    if doc.action != GrpcDocumentAction.ACTION_UNSPECIFIED:
        out.extend(_encode_int_field(2, int(doc.action)))
    if doc.mime_type:
        out.extend(_encode_string_field(3, doc.mime_type))
    if doc.timestamp:
        out.extend(_encode_int_field(4, doc.timestamp))
    if doc.raw_bytes is not None:
        out.extend(_encode_bytes_field(5, doc.raw_bytes))
    elif doc.claim_check_uri is not None:
        out.extend(_encode_string_field(6, doc.claim_check_uri))
    elif doc.text_content is not None:
        out.extend(_encode_string_field(7, doc.text_content))
    for k, v in doc.metadata.items():
        out.extend(_encode_map_entry(8, k, v))
    if doc.security is not None:
        encoded_sec = _encode_security_config(doc.security)
        if encoded_sec:
            out.extend(_encode_bytes_field(9, encoded_sec))
    if doc.checksum_sha256:
        out.extend(_encode_string_field(10, doc.checksum_sha256))
    return bytes(out)


def encode_push_request(request: GrpcPushRequest) -> bytes:
    """Serialize ``GrpcPushRequest`` to proto3 wire bytes for ``PushDocumentRequest``."""
    out = bytearray()
    if request.job_id:
        out.extend(_encode_string_field(1, request.job_id))
    encoded_doc = _encode_ois_document_payload(request.document)
    if encoded_doc:
        out.extend(_encode_bytes_field(2, encoded_doc))
    if request.options is not None:
        encoded_opts = _encode_push_options(request.options)
        if encoded_opts:
            out.extend(_encode_bytes_field(3, encoded_opts))
    return bytes(out)


def decode_push_response(data: bytes) -> GrpcPushResponse:
    """Deserialize ``PushDocumentResponse`` proto3 wire bytes."""
    document_id = ""
    status = GrpcIngestionStatus.STATUS_UNSPECIFIED
    message = ""
    processed_at = 0
    for field_num, wire_type, val in _iter_fields(data):
        if field_num == 1 and wire_type == 2 and isinstance(val, bytes):
            document_id = val.decode("utf-8")
        elif field_num == 2 and wire_type == 0 and isinstance(val, int):
            status = GrpcIngestionStatus(val) if val in GrpcIngestionStatus._value2member_map_ else GrpcIngestionStatus.STATUS_UNSPECIFIED
        elif field_num == 3 and wire_type == 2 and isinstance(val, bytes):
            message = val.decode("utf-8")
        elif field_num == 4 and wire_type == 0 and isinstance(val, int):
            processed_at = _to_signed_int64(val)
    return GrpcPushResponse(
        document_id=document_id,
        status=status,
        message=message,
        processed_at=processed_at,
    )


def _decode_push_failure(data: bytes) -> GrpcPushFailure:
    document_id = ""
    error_code = ""
    reason = ""
    for field_num, wire_type, val in _iter_fields(data):
        if field_num == 1 and wire_type == 2 and isinstance(val, bytes):
            document_id = val.decode("utf-8")
        elif field_num == 2 and wire_type == 2 and isinstance(val, bytes):
            error_code = val.decode("utf-8")
        elif field_num == 3 and wire_type == 2 and isinstance(val, bytes):
            reason = val.decode("utf-8")
    return GrpcPushFailure(document_id=document_id, error_code=error_code, reason=reason)


def decode_stream_summary(data: bytes) -> GrpcStreamSummary:
    """Deserialize ``StreamSummaryResponse`` proto3 wire bytes."""
    total_received = 0
    total_accepted = 0
    total_rejected = 0
    duration_ms = 0
    failures: list[GrpcPushFailure] = []
    for field_num, wire_type, val in _iter_fields(data):
        if field_num == 1 and wire_type == 0 and isinstance(val, int):
            total_received = _to_signed_int64(val)
        elif field_num == 2 and wire_type == 0 and isinstance(val, int):
            total_accepted = _to_signed_int64(val)
        elif field_num == 3 and wire_type == 0 and isinstance(val, int):
            total_rejected = _to_signed_int64(val)
        elif field_num == 4 and wire_type == 0 and isinstance(val, int):
            duration_ms = _to_signed_int64(val)
        elif field_num == 5 and wire_type == 2 and isinstance(val, bytes):
            failures.append(_decode_push_failure(val))
    return GrpcStreamSummary(
        total_received=total_received,
        total_accepted=total_accepted,
        total_rejected=total_rejected,
        duration_ms=duration_ms,
        failures=tuple(failures),
    )


def decode_ingestion_ack(data: bytes) -> GrpcIngestionAck:
    """Deserialize ``IngestionAck`` proto3 wire bytes."""
    document_id = ""
    status = GrpcIngestionStatus.STATUS_UNSPECIFIED
    error_message = ""
    ack_timestamp = 0
    for field_num, wire_type, val in _iter_fields(data):
        if field_num == 1 and wire_type == 2 and isinstance(val, bytes):
            document_id = val.decode("utf-8")
        elif field_num == 2 and wire_type == 0 and isinstance(val, int):
            status = GrpcIngestionStatus(val) if val in GrpcIngestionStatus._value2member_map_ else GrpcIngestionStatus.STATUS_UNSPECIFIED
        elif field_num == 3 and wire_type == 2 and isinstance(val, bytes):
            error_message = val.decode("utf-8")
        elif field_num == 4 and wire_type == 0 and isinstance(val, int):
            ack_timestamp = _to_signed_int64(val)
    return GrpcIngestionAck(
        document_id=document_id,
        status=status,
        error_message=error_message,
        ack_timestamp=ack_timestamp,
    )


def encode_ping_request(client_id: str) -> bytes:
    return _encode_string_field(1, client_id) if client_id else b""


def decode_ping_response(data: bytes) -> GrpcPingResponse:
    status = ""
    server_time = 0
    version = ""
    for field_num, wire_type, val in _iter_fields(data):
        if field_num == 1 and wire_type == 2 and isinstance(val, bytes):
            status = val.decode("utf-8")
        elif field_num == 2 and wire_type == 0 and isinstance(val, int):
            server_time = _to_signed_int64(val)
        elif field_num == 3 and wire_type == 2 and isinstance(val, bytes):
            version = val.decode("utf-8")
    return GrpcPingResponse(status=status, server_time=server_time, version=version)


def _require_grpc() -> Any:
    try:
        return import_module("grpc")
    except ImportError as exc:
        raise ImportError(
            "gRPC Push streaming requires the optional 'grpc' extra. "
            "Install with: pip install 'opencrawling-sdk[grpc]'"
        ) from exc


class GrpcPushClient:
    """Synchronous gRPC Push client for ``oc-grpc-push-connector`` (:9096).

    Supports Unary (``push_document``), Client-Streaming (``stream_documents``),
    Bi-Directional Streaming (``bi_directional_stream``), and ``ping`` RPCs.
    """

    def __init__(
        self,
        target: str = "localhost:9096",
        *,
        bearer_token: str | None = None,
        tls: bool = False,
        root_certificates: bytes | None = None,
        timeout: float = 30.0,
        channel: Any | None = None,
    ) -> None:
        self._target = target
        self._bearer_token = bearer_token.strip() if bearer_token and bearer_token.strip() else None
        self._timeout = timeout
        if channel is not None:
            self._channel = channel
            self._owns_channel = False
        else:
            grpc = _require_grpc()
            if tls:
                creds = grpc.ssl_channel_credentials(root_certificates=root_certificates)
                self._channel = grpc.secure_channel(target, creds)
            else:
                self._channel = grpc.insecure_channel(target)
            self._owns_channel = True

    def _metadata(self) -> tuple[tuple[str, str], ...]:
        if self._bearer_token:
            return (("authorization", f"Bearer {self._bearer_token}"),)
        return ()

    def ping(self, client_id: str = "opencrawling-python-sdk") -> GrpcPingResponse:
        rpc = self._channel.unary_unary(
            METHOD_PING,
            request_serializer=encode_ping_request,
            response_deserializer=decode_ping_response,
        )
        result: GrpcPingResponse = rpc(client_id, timeout=self._timeout)
        return result

    def push_document(
        self,
        job_id: str,
        document: GrpcPushDocument | OisDocument,
        *,
        options: GrpcPushOptions | None = None,
    ) -> GrpcPushResponse:
        grpc_doc = GrpcPushDocument.from_ois(document) if isinstance(document, OisDocument) else document
        req = GrpcPushRequest(job_id=job_id, document=grpc_doc, options=options)
        rpc = self._channel.unary_unary(
            METHOD_PUSH_DOCUMENT,
            request_serializer=encode_push_request,
            response_deserializer=decode_push_response,
        )
        result: GrpcPushResponse = rpc(req, timeout=self._timeout, metadata=self._metadata())
        return result

    def stream_documents(self, requests: Iterable[GrpcPushRequest]) -> GrpcStreamSummary:
        """Client-Streaming RPC: stream many ``GrpcPushRequest`` messages and receive a summary."""
        rpc = self._channel.stream_unary(
            METHOD_STREAM_DOCUMENTS,
            request_serializer=encode_push_request,
            response_deserializer=decode_stream_summary,
        )
        result: GrpcStreamSummary = rpc(iter(requests), timeout=self._timeout, metadata=self._metadata())
        return result

    def bi_directional_stream(
        self,
        requests: Iterable[GrpcPushRequest],
    ) -> Iterator[GrpcIngestionAck]:
        """Bi-Directional Streaming RPC: stream requests and yield per-document ``GrpcIngestionAck``s."""
        rpc = self._channel.stream_stream(
            METHOD_BIDI_STREAM,
            request_serializer=encode_push_request,
            response_deserializer=decode_ingestion_ack,
        )
        responses: Iterator[GrpcIngestionAck] = rpc(
            iter(requests),
            timeout=self._timeout,
            metadata=self._metadata(),
        )
        yield from responses

    def close(self) -> None:
        if self._owns_channel:
            self._channel.close()

    def __enter__(self) -> GrpcPushClient:
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"GrpcPushClient(target={self._target!r}, authenticated={self._bearer_token is not None!r})"


class AsyncGrpcPushClient:
    """Asynchronous gRPC Push client (``grpc.aio``) for ``oc-grpc-push-connector`` (:9096)."""

    def __init__(
        self,
        target: str = "localhost:9096",
        *,
        bearer_token: str | None = None,
        tls: bool = False,
        root_certificates: bytes | None = None,
        timeout: float = 30.0,
        channel: Any | None = None,
    ) -> None:
        self._target = target
        self._bearer_token = bearer_token.strip() if bearer_token and bearer_token.strip() else None
        self._timeout = timeout
        if channel is not None:
            self._channel = channel
            self._owns_channel = False
        else:
            grpc = _require_grpc()
            if tls:
                creds = grpc.ssl_channel_credentials(root_certificates=root_certificates)
                self._channel = grpc.aio.secure_channel(target, creds)
            else:
                self._channel = grpc.aio.insecure_channel(target)
            self._owns_channel = True

    def _metadata(self) -> tuple[tuple[str, str], ...]:
        if self._bearer_token:
            return (("authorization", f"Bearer {self._bearer_token}"),)
        return ()

    async def ping(self, client_id: str = "opencrawling-python-sdk") -> GrpcPingResponse:
        rpc = self._channel.unary_unary(
            METHOD_PING,
            request_serializer=encode_ping_request,
            response_deserializer=decode_ping_response,
        )
        result: GrpcPingResponse = await rpc(client_id, timeout=self._timeout)
        return result

    async def push_document(
        self,
        job_id: str,
        document: GrpcPushDocument | OisDocument,
        *,
        options: GrpcPushOptions | None = None,
    ) -> GrpcPushResponse:
        grpc_doc = GrpcPushDocument.from_ois(document) if isinstance(document, OisDocument) else document
        req = GrpcPushRequest(job_id=job_id, document=grpc_doc, options=options)
        rpc = self._channel.unary_unary(
            METHOD_PUSH_DOCUMENT,
            request_serializer=encode_push_request,
            response_deserializer=decode_push_response,
        )
        result: GrpcPushResponse = await rpc(req, timeout=self._timeout, metadata=self._metadata())
        return result

    async def stream_documents(
        self,
        requests: Iterable[GrpcPushRequest] | AsyncIterable[GrpcPushRequest],
    ) -> GrpcStreamSummary:
        rpc = self._channel.stream_unary(
            METHOD_STREAM_DOCUMENTS,
            request_serializer=encode_push_request,
            response_deserializer=decode_stream_summary,
        )
        result: GrpcStreamSummary = await rpc(requests, timeout=self._timeout, metadata=self._metadata())
        return result

    async def bi_directional_stream(
        self,
        requests: Iterable[GrpcPushRequest] | AsyncIterable[GrpcPushRequest],
    ) -> AsyncIterator[GrpcIngestionAck]:
        rpc = self._channel.stream_stream(
            METHOD_BIDI_STREAM,
            request_serializer=encode_push_request,
            response_deserializer=decode_ingestion_ack,
        )
        call = rpc(requests, timeout=self._timeout, metadata=self._metadata())
        async for ack in call:
            yield ack

    async def aclose(self) -> None:
        if self._owns_channel:
            await self._channel.close()

    async def __aenter__(self) -> AsyncGrpcPushClient:
        return self

    async def __aexit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        await self.aclose()

    def __repr__(self) -> str:
        return f"AsyncGrpcPushClient(target={self._target!r}, authenticated={self._bearer_token is not None!r})"
