# Changelog

All notable changes to the OpenCrawling Python Client SDK (`opencrawling-sdk`) are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
as detailed in [VERSIONING.md](VERSIONING.md).

## [Unreleased]

### Added
- **Governance & Enrichment Hook Helpers (`opencrawling.hooks`, `#408`)**:
  - Constant-time HMAC-SHA256 request signing and verification (`sign_payload`, `build_signed_headers`, `verify_signature`, `HookSignatureError`) matching the `oc-runtime` `HttpGovernanceHook` wire contract (`X-OpenCrawling-Timestamp` + `X-OpenCrawling-Signature: sha256=<hex>`, 32-character minimum secret length, configurable replay window).
  - Typed Pydantic models (`GovernanceRequest`, `Decision.approve()`, `Decision.sanitize()`, `Decision.reject()`, `EnrichmentRequest`, `EnrichmentResponse`) with client-side enforcement of `GovernanceGate` protected metadata keys (`is_protected_metadata_key`, `ProtectedMetadataKeyError`).
  - Optional FastAPI / Starlette ASGI decorators and app factories (`@governance_endpoint`, `@enrichment_endpoint`, `create_governance_app`, `create_enrichment_app`) behind the `opencrawling-sdk[hooks]` extra.
  - Sync and async test harnesses (`SignedHookTestClient`, `AsyncSignedHookTestClient`) for testing hook services without running an OpenCrawling runtime.
- **W3C Trace Context & OpenTelemetry Support (`#409`)**:
  - Explicit `RequestOptions(traceparent=..., tracestate=...)` propagation across sync and async HTTP calls, streaming downloads, and gRPC Push RPCs (`GrpcPushClient` / `AsyncGrpcPushClient`).
  - Automatic W3C `traceparent` / `tracestate` injection when an OpenTelemetry span is active (`opencrawling-sdk[otel]`), degrading to a zero-overhead no-op when `opentelemetry-api` is not installed.
  - Optional `enable_tracing=True` on `OpenCrawlingClient`, `AsyncOpenCrawlingClient`, `GrpcPushClient`, and `AsyncGrpcPushClient` to emit OpenTelemetry `CLIENT` spans with HTTP/gRPC attributes, retry counts, and error status recording.
- **1.0 GA Packaging & Stability (`#410`)**:
  - Promoted PyPI classifier to `Development Status :: 5 - Production/Stable` and added Python 3.14 support.
  - Added [VERSIONING.md](VERSIONING.md) defining SemVer guarantees, deprecation timelines, and runtime compatibility matrix.
  - Added wheel `py.typed` verification to the PyPI release workflow.

### Fixed
- **Language-Neutral gRPC Push Metadata Serialization (`#407`)**:
  - Added `normalize_grpc_metadata()` and wired it into both `GrpcPushDocument.__post_init__` and `GrpcPushDocument.from_ois()`.
  - `bool` values now serialize as `"true"` / `"false"` instead of `"True"` / `"False"`.
  - Scalar sequences (`list`, `tuple`) now serialize as compact RFC 8259 JSON arrays (e.g. `'["alpha","beta"]'`) rather than Python `repr()` strings, paired with automatic expansion in `oc-grpc-push-connector` `OisDocumentTranslator`.
  - `date` / `datetime` values serialize as ISO-8601 (UTC normalized to `Z`); non-finite floats/Decimals raise `ValueError`; unsupported container/object types (`dict`, `bytes`, nested lists) raise `TypeError`.

## [1.0.4] - 2026-04-10

### Added
- Initial public release of `opencrawling-sdk` (Beta) covering Platform API v1 (`/api/v1`) parity across synchronous (`OpenCrawlingClient`) and asynchronous (`AsyncOpenCrawlingClient`) clients.
- RFC 9457 `ApiProblemError`, `Versioned[T]` ETag/Location wrappers, automatic UUIDv4 `Idempotency-Key` generation, and `RetryPolicy` with `Retry-After` support.
- Enterprise OAuth 2.0 providers (`ClientCredentialsTokenProvider`, `RefreshTokenProvider`, RFC 8693 `TokenExchangeProvider`, RFC 8628 `DeviceCodeLogin`).
- OIS Direct Ingestion (`OisDocument`, fail-closed `SecurityConfig`, `PermissionRule`) and optional gRPC Push streaming (`opencrawling-sdk[grpc]`).
- Lakehouse streaming exports and DuckDB query conversions (`opencrawling-sdk[dataframe]`).
