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
"""Tests for HTTP transport resilience, Idempotency-Key, retries, ETags, pagination, and RFC 9457."""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from email.utils import format_datetime
from pathlib import Path

import httpx
import pytest

from opencrawling import (
    ApiProblemError,
    AsyncOpenCrawlingClient,
    ConnectorInput,
    NetworkError,
    OpenCrawlingClient,
    OpenCrawlingError,
    RequestOptions,
    RetryPolicy,
)
from opencrawling._transport import AsyncTransport, SyncTransport
from opencrawling.config import parse_retry_after


def test_auto_idempotency_key_on_post_reused_across_409_in_progress_retry() -> None:
    seen_keys: list[str | None] = []
    sleeps: list[float] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_keys.append(request.headers.get("idempotency-key"))
        if len(seen_keys) == 1:
            return httpx.Response(
                409,
                headers={"Content-Type": "application/problem+json", "Retry-After": "1"},
                json={
                    "type": "https://github.com/opencrawling/opencrawling/wiki/Platform-API#problem-idempotency-in-progress",
                    "title": "Conflict",
                    "status": 409,
                    "detail": "The first request with this Idempotency-Key is still running.",
                },
            )
        return httpx.Response(
            202,
            headers={"Content-Type": "application/json", "Location": "/api/v1/job-runs/exec-42"},
            json={"executionId": "exec-42", "jobId": "1", "state": "RUNNING", "stopRequested": False},
        )

    transport = SyncTransport(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
        retry_policy=RetryPolicy(max_attempts=3, base_delay=0.1, max_delay=5.0, jitter=False),
        sleeper=sleeps.append,
    )
    client = OpenCrawlingClient(_transport=transport)
    started = client.jobs.start("1")
    assert started.data.execution_id == "exec-42"
    assert started.location == "/api/v1/job-runs/exec-42"
    assert len(seen_keys) == 2
    assert seen_keys[0] is not None
    assert seen_keys[0].startswith("sdk-")
    assert seen_keys[0] == seen_keys[1]
    assert sleeps == [1.0]


def test_non_idempotency_409_is_not_retried() -> None:
    calls = 0

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(
            409,
            headers={"Content-Type": "application/problem+json", "X-Request-Id": "req-conflict"},
            json={
                "type": "https://github.com/opencrawling/opencrawling/wiki/Platform-API#problem-job-already-running",
                "title": "Conflict",
                "status": 409,
                "detail": "The job is already running.",
                "executionId": "exec-existing",
            },
        )

    client = OpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
        retry_policy=RetryPolicy(max_attempts=3, base_delay=0.0, max_delay=1.0, jitter=False),
    )
    with pytest.raises(ApiProblemError) as exc_info:
        client.jobs.start("1")
    assert calls == 1
    err = exc_info.value
    assert err.status == 409
    assert err.code == "job-already-running"
    assert err.execution_id == "exec-existing"
    assert err.request_id == "req-conflict"


@pytest.mark.parametrize("status_code", [429, 502, 503, 504])
def test_retries_transient_statuses_and_network_errors(status_code: int) -> None:
    attempts = 0
    sleeps: list[float] = []

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.ConnectError("temporary dns failure")
        if attempts == 2:
            return httpx.Response(status_code, text="temporary overload")
        return httpx.Response(200, json={"apiVersion": "1.0.0", "runtimeVersion": "1.0.5"})

    transport = SyncTransport(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
        retry_policy=RetryPolicy(max_attempts=3, base_delay=0.1, max_delay=5.0, jitter=False),
        sleeper=sleeps.append,
    )
    client = OpenCrawlingClient(_transport=transport)
    ver = client.version()
    assert ver.runtime_version == "1.0.5"
    assert attempts == 3
    assert sleeps == [0.1, 0.2]


def test_retry_after_exceeding_max_delay_stops_early() -> None:
    attempts = 0
    sleeps: list[float] = []

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        return httpx.Response(
            429,
            headers={"Content-Type": "application/problem+json", "Retry-After": "120"},
            json={"type": "https://example.org/problems/rate-limited", "title": "Too Many Requests", "status": 429},
        )

    transport = SyncTransport(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
        retry_policy=RetryPolicy(max_attempts=4, base_delay=0.1, max_delay=10.0, jitter=False),
        sleeper=sleeps.append,
    )
    client = OpenCrawlingClient(_transport=transport)
    with pytest.raises(ApiProblemError) as exc_info:
        client.version()
    assert attempts == 1
    assert sleeps == []
    assert exc_info.value.retry_after == 120.0
    assert exc_info.value.code == "rate-limited"


def test_parse_retry_after_supports_http_date_and_seconds() -> None:
    base_ts = 1_700_000_000.0
    future_dt = datetime.fromtimestamp(base_ts, tz=UTC) + timedelta(seconds=7)
    http_date = format_datetime(future_dt, usegmt=True)
    assert parse_retry_after("5") == 5.0
    assert parse_retry_after(http_date, now=base_ts) == pytest.approx(7.0, abs=1.0)
    assert parse_retry_after("not-a-date") is None


def test_multipart_upload_omits_idempotency_key_and_does_not_retry(tmp_path: Path) -> None:
    calls = 0
    seen_headers: list[httpx.Headers] = []

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        seen_headers.append(request.headers)
        return httpx.Response(503, text="unavailable")

    zip_file = tmp_path / "pkg.zip"
    zip_file.write_bytes(b"PK\x03\x04test")

    client = OpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
        retry_policy=RetryPolicy(max_attempts=3, base_delay=0.0, max_delay=1.0, jitter=False),
    )
    with pytest.raises(ApiProblemError) as exc_info:
        client.vespa.deploy_custom_schema(zip_file, "http://vespa:19071")
    assert exc_info.value.status == 503
    assert calls == 1
    assert "idempotency-key" not in seen_headers[0]
    assert seen_headers[0].get("content-type", "").startswith("multipart/form-data")


def test_health_returns_unhealthy_on_503_without_retrying_or_raising() -> None:
    calls = 0

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(
            503,
            json={"status": "UNHEALTHY", "components": {"postgres": "UP", "redis": "DOWN"}},
        )

    client = OpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
        retry_policy=RetryPolicy(max_attempts=3, base_delay=0.0, max_delay=1.0, jitter=False),
    )
    health = client.health()
    assert calls == 1
    assert not health.is_healthy
    assert health.status == "UNHEALTHY"
    assert health.components["redis"] == "DOWN"


async def test_async_health_returns_unhealthy_on_503() -> None:
    async def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"status": "UNHEALTHY", "components": {"ollama": "DOWN"}})

    async with AsyncOpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    ) as client:
        health = await client.health()
        assert not health.is_healthy
        assert health.components["ollama"] == "DOWN"


def test_etag_if_none_match_304_and_412_precondition_failed_and_merge_patch_nulls() -> None:
    patch_bodies: list[bytes] = []
    patch_cts: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET" and request.headers.get("if-none-match") == '"v1"':
            return httpx.Response(304, headers={"ETag": '"v1"'})
        if request.method == "PATCH":
            patch_bodies.append(request.content)
            patch_cts.append(request.headers.get("content-type"))
            if request.headers.get("if-match") == '"stale"':
                return httpx.Response(
                    412,
                    headers={"Content-Type": "application/problem+json", "X-Request-Id": "req-412"},
                    json={
                        "type": "https://github.com/opencrawling/opencrawling/wiki/Platform-API#problem-precondition-failed",
                        "title": "Precondition failed",
                        "status": 412,
                        "detail": "Resource changed concurrently.",
                        "currentEtag": '"v2"',
                        "errors": [{"field": "If-Match", "message": "stale etag"}],
                    },
                )
            return httpx.Response(
                200,
                headers={"ETag": '"v3"'},
                json={"id": "1", "name": "Job", "repositoryConnector": "R", "outputConnector": "O", "path": "/new"},
            )
        return httpx.Response(404)

    client = OpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
        retry_policy=RetryPolicy.none(),
    )
    assert client.jobs.get_if_changed("1", '"v1"') is None

    with pytest.raises(ApiProblemError) as exc_info:
        client.jobs.patch("1", {"authorityConnector": None, "path": "/new"}, RequestOptions.matching('"stale"'))
    err = exc_info.value
    assert err.status == 412
    assert err.code == "precondition-failed"
    assert err.current_etag == '"v2"'
    assert err.request_id == "req-412"
    assert len(err.errors) == 1
    assert err.errors[0].field == "If-Match"

    updated = client.jobs.patch("1", {"authorityConnector": None, "path": "/new"}, RequestOptions.matching('"v2"'))
    assert updated.etag == '"v3"'
    assert patch_cts[-1] == "application/merge-patch+json"
    assert json.loads(patch_bodies[-1].decode("utf-8")) == {"authorityConnector": None, "path": "/new"}


def test_with_defaults_propagates_correlation_and_strips_per_call_headers() -> None:
    captured: list[httpx.Headers] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request.headers)
        return httpx.Response(200, json={"apiVersion": "1.0.0"})

    base = OpenCrawlingClient(
        "http://localhost:8080",
        api_key="my-api-key",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
    )
    scoped = base.with_defaults(
        RequestOptions(
            idempotency_key="should-not-leak",
            if_match="should-not-leak",
            correlation_id="corr-99",
            request_id="req-99",
            extra_headers={"X-Tenant": "acme"},
        )
    )
    scoped.version()
    assert captured[0].get("x-api-key") == "my-api-key"
    assert captured[0].get("x-correlation-id") == "corr-99"
    assert captured[0].get("x-request-id") == "req-99"
    assert captured[0].get("x-tenant") == "acme"
    assert "idempotency-key" not in captured[0]
    assert "if-match" not in captured[0]


def test_lazy_cursor_pagination_sync_and_streaming_export(tmp_path: Path) -> None:
    requested_cursors: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/v1/jobs":
            cursor = request.url.params.get("cursor")
            requested_cursors.append(cursor)
            if cursor is None:
                return httpx.Response(
                    200,
                    json={
                        "items": [
                            {"id": "1", "name": "A", "repositoryConnector": "R", "outputConnector": "O", "path": "/a"},
                            {"id": "2", "name": "B", "repositoryConnector": "R", "outputConnector": "O", "path": "/b"},
                        ],
                        "page": {"limit": 2, "nextCursor": "c2", "totalItems": 3},
                    },
                )
            return httpx.Response(
                200,
                json={
                    "items": [
                        {"id": "3", "name": "C", "repositoryConnector": "R", "outputConnector": "O", "path": "/c"},
                    ],
                    "page": {"limit": 2, "nextCursor": None, "totalItems": 3},
                },
            )
        if request.url.path == "/api/v1/executions/exec-1/export":
            return httpx.Response(200, content=b"PAR1-fake-parquet-payload")
        return httpx.Response(404)

    client = OpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
    )
    it = client.jobs.iter(page_size=2)
    assert requested_cursors == []  # Lazy: no network call until iterated
    first = next(it)
    assert first.id == "1"
    assert requested_cursors == [None]
    rest = [j.id for j in it]
    assert rest == ["2", "3"]
    assert requested_cursors == [None, "c2"]

    target = tmp_path / "nested" / "audit.parquet"
    saved = client.executions.export_to("exec-1", "parquet", target)
    assert saved == target
    assert target.read_bytes() == b"PAR1-fake-parquet-payload"
    assert not list(target.parent.glob("*.part"))


async def test_async_cursor_pagination_and_sidecar_iteration(tmp_path: Path) -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/v1/sidecar-stores/S3_Sink/sidecars":
            cursor = request.url.params.get("cursor")
            if cursor is None:
                return httpx.Response(200, json={"connector": "S3_Sink", "items": [{"id": "d1"}], "nextCursor": "cur-2", "truncated": False})
            return httpx.Response(200, json={"connector": "S3_Sink", "items": [{"id": "d2"}], "nextCursor": None, "truncated": False})
        if request.url.path == "/api/v1/executions/exec-9/export":
            return httpx.Response(200, content=b"col1,col2\na,b\n")
        return httpx.Response(404)

    async with AsyncOpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    ) as client:
        docs = [item["id"] async for item in client.sidecars.iter("S3_Sink", prefix="legal/")]
        assert docs == ["d1", "d2"]
        dest = tmp_path / "out.csv"
        await client.executions.export_to("exec-9", "csv", dest)
        assert dest.read_text(encoding="utf-8") == "col1,col2\na,b\n"


def test_connectors_upsert_and_url_encoding_for_spaces() -> None:
    calls: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        raw_path = request.url.raw_path.decode("ascii")
        calls.append((request.method, raw_path))
        if request.method == "POST":
            return httpx.Response(
                409,
                headers={"Content-Type": "application/problem+json"},
                json={"type": "https://example.org/Platform-API#problem-connector-exists", "title": "Conflict", "status": 409},
            )
        if request.method == "PUT" and raw_path == "/api/v1/connectors/My%20SharePoint%20Connector":
            return httpx.Response(
                200,
                headers={"ETag": '"c2"'},
                json={"name": "My SharePoint Connector", "type": "repository", "className": "org.example.SP"},
            )
        return httpx.Response(404)

    client = OpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
    )
    res = client.connectors.upsert(
        ConnectorInput(name="My SharePoint Connector", type="repository", class_name="org.example.SP")
    )
    assert res.etag == '"c2"'
    assert res.data.name == "My SharePoint Connector"
    assert calls == [("POST", "/api/v1/connectors"), ("PUT", "/api/v1/connectors/My%20SharePoint%20Connector")]


def test_job_runs_wait_for_completion_and_timeout() -> None:
    states = ["RUNNING", "PAUSED", "COMPLETED"]

    def handler(_: httpx.Request) -> httpx.Response:
        state = states.pop(0) if states else "COMPLETED"
        return httpx.Response(200, json={"executionId": "exec-7", "jobId": "1", "state": state, "steps": []})

    client = OpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
    )
    history = client.job_runs.wait_for_completion("exec-7", poll_interval=0.001, timeout=5.0)
    assert history.state == "COMPLETED"

    def always_running(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"executionId": "exec-8", "jobId": "1", "state": "RUNNING", "steps": []})

    client_slow = OpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(always_running)),
    )
    with pytest.raises(OpenCrawlingError, match="Timed out"):
        client_slow.job_runs.wait_for_completion("exec-8", poll_interval=0.001, timeout=0.01)


async def test_async_retry_and_network_error_exhaustion() -> None:
    sleeps: list[float] = []

    async def handler(_: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timed out")

    async def fake_sleep(delay: float) -> None:
        sleeps.append(delay)

    transport = AsyncTransport(
        "http://localhost:8080",
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        retry_policy=RetryPolicy(max_attempts=3, base_delay=0.05, max_delay=1.0, jitter=False),
        asleeper=fake_sleep,
    )
    async with AsyncOpenCrawlingClient(_transport=transport) as client:
        with pytest.raises(NetworkError):
            await client.version()
    assert sleeps == [0.05, 0.1]
