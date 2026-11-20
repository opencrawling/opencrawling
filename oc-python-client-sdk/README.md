# OpenCrawling Python Client SDK (`opencrawling-sdk`)

Official Python 3.11+ client SDK for the **OpenCrawling Platform API v1 (`/api/v1`)**, **Open Ingestion Standard (OIS) Direct Ingestion Gateway**, **gRPC Push Streaming (`oc-grpc-push-connector`)**, and **Enterprise OAuth 2.0 / RFC 8693 Multi-Agent Identity**.

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Python](https://img.shields.io/badge/Python-3.11%2B-3776AB.svg)](https://www.python.org/)
[![Typed](https://img.shields.io/badge/Typing-PEP%20561%20Strict-brightgreen.svg)](src/opencrawling/py.typed)

---

## Features

- **100% Platform API v1 (`/api/v1`) Parity**: Both synchronous (`OpenCrawlingClient`) and asynchronous (`AsyncOpenCrawlingClient`) clients over `httpx`, covering all 80 operations across `jobs`, `schedules`, `job_runs`, `runs` (job runs and dynamic jobs together), `executions`, `connectors`, `settings`, `operations`, `migrations`, `vespa`, `narrativization`, `dynamic_jobs`, `discovery`, and `sidecars`.
- **Built-In REST Guarantees**:
  - `Versioned[T]` wrappers exposing HTTP `ETag` and `Location` headers for safe optimistic concurrency (`RequestOptions(if_match=...)`) and conditional reads (`If-None-Match` -> `304 Not Modified`).
  - Automatic UUIDv4 `Idempotency-Key` generation on every `POST` (excluding multipart uploads).
  - `RetryPolicy` retrying network errors, `429`, `502`, `503`, `504`, and `409 idempotency-in-progress` with exponential backoff + jitter while honoring `Retry-After` (delta-seconds and HTTP-date).
  - Structured RFC 9457 `ApiProblemError` exposing `code`, `status`, `detail`, `request_id`, field-level `errors`, `current_etag`, and `retry_after`.
  - Cursor-based lazy pagination (`SyncPageIterator[T]` and `AsyncPageIterator[T]`).
  - Lossless `SystemSettings` and `TransportSettings` models (`extra="allow"`) preserving unknown server fields across read-modify-replace cycles.
- **Enterprise OAuth 2.0 & Multi-Agent Identity (`opencrawling.auth`)**:
  - Static Bearer tokens & API keys (`X-API-Key`).
  - `ClientCredentialsTokenProvider` (RFC 6749 §4.4), `RefreshTokenProvider` (RFC 6749 §6), **RFC 8693 `TokenExchangeProvider`** (for AI agents acting on behalf of users with `act.sub`), and **RFC 8628 `DeviceCodeLogin`**.
  - Pre-expiry token caching (30s margin), automatic single `401` refresh-and-retry, and strict secret redaction in `repr`, logs, and error messages.
- **OIS Direct Ingestion & Optional gRPC Push (`opencrawling.ingest`)**:
  - Typed `OisDocument.upsert(...)` / `.delete(...)` builders with fail-closed `SecurityConfig` and `PermissionRule` helpers targeting `POST /api/v1/ingest/ois/{jobId}`.
  - Optional `GrpcPushClient` / `AsyncGrpcPushClient` (`opencrawling-sdk[grpc]`) supporting Unary, Client-Streaming, and Bi-Directional Streaming against `oc-grpc-push-connector`.
- **Governance & Enrichment Hook Services (`opencrawling.hooks`, `opencrawling-sdk[hooks]`)**:
  - Constant-time HMAC-SHA256 signature verification (`verify_signature`, `sign_payload`) matching `oc-runtime` `HttpGovernanceHook` (`X-OpenCrawling-Timestamp` + `X-OpenCrawling-Signature`), typed `GovernanceRequest` / `Decision` (`approve`, `sanitize`, `reject`) models enforcing `GovernanceGate` protected metadata keys, FastAPI/Starlette `@governance_endpoint` decorator, and `SignedHookTestClient`.
- **W3C Trace Context & OpenTelemetry (`opencrawling-sdk[otel]`)**:
  - Automatic W3C `traceparent` / `tracestate` propagation on sync/async HTTP and gRPC Push calls when an OpenTelemetry span is active, explicit `RequestOptions(traceparent=..., tracestate=...)`, and optional `enable_tracing=True` client spans.
- **Lakehouse & Data Science Ergonomics (`opencrawling-sdk[dataframe]`)**:
  - Constant-memory streaming export (`client.executions.export_to(...)`) for Parquet, CSV, Avro, and JSON reports.
  - DuckDB analytical queries (`client.executions.query(...)`) with `.to_dicts()`, `.to_pyarrow()`, `.to_pandas()`, and `.to_polars()`.

---

## Installation

```bash
pip install opencrawling-sdk
```

Optional extras:

```bash
pip install "opencrawling-sdk[http2]"      # HTTP/2 support via h2
pip install "opencrawling-sdk[grpc]"       # High-throughput gRPC Push streaming
pip install "opencrawling-sdk[dataframe]"  # PyArrow, Pandas, and Polars conversions
pip install "opencrawling-sdk[hooks]"      # Starlette/FastAPI governance & enrichment hook helpers
pip install "opencrawling-sdk[otel]"       # OpenTelemetry W3C traceparent propagation & client spans
```

---

## Quick Start

### Synchronous Platform API v1 with ETag Concurrency & Lakehouse Queries

```python
from opencrawling import OpenCrawlingClient, RequestOptions

with OpenCrawlingClient(
    base_url="http://localhost:8080",
    client_credentials={
        "token_endpoint": "https://idp.example.com/realms/opencrawling/protocol/openid-connect/token",
        "client_id": "opencrawling-automation",
        "client_secret": "...",
    },
) as client:
    # 1. Read a job along with its ETag
    versioned_job = client.jobs.get("1")
    print(f"Job '{versioned_job.data.name}' (ETag: {versioned_job.etag})")

    # 2. Patch conditionally with If-Match (raises ApiProblemError on 412)
    client.jobs.patch(
        "1",
        {"path": "/Sites/finance/documentLibrary"},
        options=RequestOptions(if_match=versioned_job.etag),
    )

    # 3. Start a job run (automatic Idempotency-Key) and wait for completion
    started = client.jobs.start("1")
    completed = client.job_runs.wait_for_completion(started.data.execution_id, timeout=300.0)

    # 4. Stream the Parquet audit report to disk and run a DuckDB analytical query
    client.executions.export_to(completed.execution_id, "parquet", "./audit.parquet")
    summary = client.executions.query(
        "SELECT status, COUNT(*) AS docs FROM read_parquet('mode=RAG/**/*.parquet') GROUP BY status"
    )
    print(summary.to_dicts())
```

### Asynchronous AI Agent Integration (RFC 8693 Token Exchange)

```python
import asyncio
from opencrawling import AsyncOpenCrawlingClient, DiscoveryDryRunRequest, DocumentIngestRequest
from opencrawling.auth import TokenExchangeProvider

async def agent_workflow(user_subject_token: str) -> None:
    delegated_auth = TokenExchangeProvider(
        token_endpoint="https://idp.example.com/realms/opencrawling/protocol/openid-connect/token",
        client_id="research-agent",
        client_secret="...",
        subject_token=user_subject_token,
    )

    async with AsyncOpenCrawlingClient("http://localhost:8080", token_provider=delegated_auth) as client:
        # 1. Preview matching documents under the user's source ACLs
        preview = await client.discovery.dry_run(
            "SharePoint_Prod",
            DiscoveryDryRunRequest(scope="/Drives/Legal", sample_size=25),
        )
        print(f"Estimated readable documents: {preview.match_count} ({preview.count_accuracy})")

        # 2. Trigger single-document ingestion on demand
        accepted = await client.dynamic_jobs.ingest_document(
            DocumentIngestRequest.upsert(
                connector="SharePoint_Prod",
                document="01BYE5RZ6QN3ZWBTUFOFD3GSPGOHDJD36K",
            )
        )
        print(f"Dynamic ingestion triggered: {accepted.job_id} ({accepted.state})")

        # 3. Iterate authorized OIS metadata sidecars lazily
        async for sidecar in client.sidecars.iter("S3_Migration_Sink", prefix="legal/2026/"):
            print(sidecar.get("id"))

asyncio.run(agent_workflow("eyJ..."))
```

### Direct OIS Document Push (`POST /api/v1/ingest/ois/{jobId}`)

```python
from opencrawling import OpenCrawlingClient
from opencrawling.ingest import OisContent, OisDocument, PermissionRule, SecurityConfig

with OpenCrawlingClient("http://localhost:8080", bearer_token="ois-ingest-secret-token") as client:
    doc = OisDocument.upsert(
        id="custom://crm/accounts/ACME-2026-Q4",
        content=OisContent.markdown("# ACME Corp Q4 Renewal\nAnnual contract value: $480,000."),
        metadata={"account_id": "ACME-001", "region": "EMEA"},
        security=SecurityConfig.fail_closed([
            PermissionRule.allow_group("sales-emea"),
            PermissionRule.deny_user("contractor@example.com"),
        ]),
    )
    receipt = client.ingest.push_ois("crm-rag-pipeline", doc)
    print(f"Accepted={receipt.is_accepted} ({receipt.status}) for {receipt.document_id}")
```

### Governance Hook Service (`opencrawling.hooks`)

```python
from opencrawling.hooks import Decision, GovernanceRequest, create_governance_app

SECRET = "a-shared-secret-at-least-32-characters-long"

async def redact_pii(req: GovernanceRequest) -> Decision:
    if req.text and "SSN:" in req.text:
        return Decision.sanitize(
            text=req.text.replace("SSN:", "[REDACTED]:"),
            metadata={"pii_redacted": "true"},
            audit={"rule": "ssn-mask"},
        )
    return Decision.approve()

app = create_governance_app(redact_pii, secret=SECRET, path="/governance")
```

### Distributed Tracing (`opencrawling-sdk[otel]`)

```python
from opencrawling import OpenCrawlingClient, RequestOptions

# Automatically injects W3C traceparent/tracestate from the active OpenTelemetry span,
# and optionally emits CLIENT spans when enable_tracing=True:
with OpenCrawlingClient("http://localhost:8080", enable_tracing=True) as client:
    version = client.version()

# Or supply an explicit W3C traceparent without installing OpenTelemetry:
opts = RequestOptions(traceparent="00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
```

---

## Versioning & Changelog

- [CHANGELOG.md](CHANGELOG.md) — Keep a Changelog history of all SDK releases.
- [VERSIONING.md](VERSIONING.md) — SemVer stability guarantees, deprecation policy, and compatibility matrix.

---

## Development & Quality Gates

```bash
pip install -e ".[dev,http2,hooks,otel]"
ruff check src tests
mypy --strict src/opencrawling
pytest
```

Live runtime integration tests:

```bash
OPENCRAWLING_BASE_URL=http://localhost:8080 pytest -m live tests/it/
```

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
