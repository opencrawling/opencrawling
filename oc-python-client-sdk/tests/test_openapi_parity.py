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

"""OpenAPI v1 parity tests verifying 100% coverage of all Platform API v1 operations.

Mirrors ``PlatformApiV1SdkTest`` in ``oc-runtime``: verifies that every operation in
``/v3/api-docs/v1`` (loaded from ``OPENCRAWLING_OPENAPI_URL``, ``OPENCRAWLING_OPENAPI_FILE``,
or parsed directly from the ``*V1Controller.java`` sources in ``oc-runtime``) is exercised
by BOTH ``OpenCrawlingClient`` (sync) and ``AsyncOpenCrawlingClient`` (async).
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any

import httpx

from opencrawling import (
    AsyncOpenCrawlingClient,
    ConnectorInput,
    CopilotField,
    CopilotRequest,
    DiscoveryDryRunRequest,
    DocumentIngestRequest,
    DynamicJobRequest,
    ExecutionFilter,
    JobFilter,
    JobInput,
    JobScheduleConfig,
    OpenCrawlingClient,
    PageRequest,
    RequestOptions,
    RetryPolicy,
    ScheduleFilter,
    SystemSettings,
    TransportSettings,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
CONTROLLERS_DIR = REPO_ROOT / "oc-runtime" / "src" / "main" / "java" / "org" / "opencrawling" / "runtime" / "api" / "v1"

_CLASS_MAPPING_RE = re.compile(
    r'@RequestMapping\(\s*ApiV1\.BASE(?:\s*\+\s*"([^"]*)")?\s*\)'
)
_METHOD_MAPPING_RE = re.compile(
    r"@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?(?:\"([^\"]*)\")?[^)]*\))?"
)


def match_operation_template(template: str, recorded_request: str) -> bool:
    """Mirror ``PlatformApiV1SdkTest.matches(template, request)``."""
    parts = re.split(r"(\{[^}]+\})", template)
    pattern = "".join(r"[^/:]+" if part.startswith("{") and part.endswith("}") else re.escape(part) for part in parts)
    return re.fullmatch(pattern, recorded_request) is not None


def _operations_from_openapi_dict(doc: dict[str, Any]) -> set[str]:
    ops: set[str] = set()
    paths = doc.get("paths", {})
    for path, methods in paths.items():
        if not isinstance(methods, dict):
            continue
        for method in methods:
            upper = method.upper()
            if upper in {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}:
                ops.add(f"{upper} {path}")
    return ops


def _operations_from_v1_controllers(controllers_dir: Path) -> set[str]:
    """Extract every non-hidden ``METHOD /api/v1/...`` route directly from ``*V1Controller.java``."""
    ops: set[str] = set()
    for java_file in sorted(controllers_dir.glob("*V1Controller.java")):
        source = java_file.read_text(encoding="utf-8")
        class_match = _CLASS_MAPPING_RE.search(source)
        if not class_match:
            continue
        base_suffix = class_match.group(1) or ""
        class_base = f"/api/v1{base_suffix}"
        lines = source.splitlines()
        hidden_next = False
        for line in lines:
            stripped = line.strip()
            if "@Operation(" in stripped and "hidden = true" in stripped:
                hidden_next = True
                continue
            mapping = _METHOD_MAPPING_RE.search(stripped)
            if mapping:
                if hidden_next:
                    hidden_next = False
                    continue
                verb = mapping.group(1).upper()
                sub = mapping.group(2) or ""
                full_path = f"{class_base}{sub}"
                ops.add(f"{verb} {full_path}")
            elif stripped.startswith("public "):
                hidden_next = False
    return ops


def load_v1_operations() -> set[str]:
    """Load the authoritative Platform API v1 operation list."""
    openapi_url = os.environ.get("OPENCRAWLING_OPENAPI_URL")
    if openapi_url:
        resp = httpx.get(openapi_url, timeout=15.0)
        resp.raise_for_status()
        return _operations_from_openapi_dict(resp.json())

    openapi_file = os.environ.get("OPENCRAWLING_OPENAPI_FILE")
    if openapi_file:
        doc = json.loads(Path(openapi_file).read_text(encoding="utf-8"))
        return _operations_from_openapi_dict(doc)

    assert CONTROLLERS_DIR.is_dir(), f"Expected V1 controllers directory at {CONTROLLERS_DIR}"
    return _operations_from_v1_controllers(CONTROLLERS_DIR)


def _mock_response_for(request: httpx.Request) -> httpx.Response:
    """Return realistic v1 JSON/binary stubs for every Platform API v1 endpoint."""
    method = request.method.upper()
    path = request.url.raw_path.decode("ascii").split("?", 1)[0]
    headers = {"Content-Type": "application/json", "ETag": '"v1-etag"', "X-Request-Id": "req-parity"}

    if method == "GET" and path == "/api/v1":
        return httpx.Response(200, headers=headers, json={"name": "OpenCrawling Platform API", "apiVersion": "1.0.0", "links": {}, "features": {"discovery": True}, "documentation": {}})
    if method == "GET" and path == "/api/v1/version":
        return httpx.Response(200, headers=headers, json={"apiVersion": "1.0.0", "runtimeVersion": "1.0.5-SNAPSHOT"})
    if method == "GET" and path == "/api/v1/health":
        return httpx.Response(200, headers=headers, json={"status": "HEALTHY", "components": {"postgres": "UP"}})
    if method == "GET" and path == "/api/v1/me":
        return httpx.Response(200, headers=headers, json={"authenticated": True, "principal": "alice", "groups": ["admins"], "admin": True, "canCreateJobs": True})

    # Jobs
    job_payload = {
        "id": "job-1",
        "name": "HR Docs",
        "repositoryConnector": "FileSystem_Local",
        "outputConnector": "PGVector_Output",
        "path": "/data",
        "pipelineMode": "rag",
        "state": {"status": "Ready", "stage": "Idle", "documents": 0},
        "permissions": ["READ", "EXECUTE", "MANAGE"],
    }
    job_run_payload = {
        "executionId": "exec-1",
        "jobId": "job-1",
        "startedAt": "2026-11-19T10:00:00Z",
        "stopRequested": False,
        "state": "RUNNING",
        "runtimeId": "rt-1",
    }
    if path == "/api/v1/jobs" and method == "GET":
        return httpx.Response(200, headers=headers, json={"items": [job_payload], "page": {"limit": 50, "nextCursor": None, "totalItems": 1}})
    if path == "/api/v1/jobs" and method == "POST":
        return httpx.Response(201, headers={**headers, "Location": "/api/v1/jobs/job-1"}, json=job_payload)
    if path == "/api/v1/jobs/job-1" and method in {"GET", "PUT", "PATCH"}:
        return httpx.Response(200, headers=headers, json=job_payload)
    if path == "/api/v1/jobs/job-1" and method == "DELETE":
        return httpx.Response(204, headers={"X-Request-Id": "req-parity"})
    if path == "/api/v1/jobs/job-1:start" and method == "POST":
        return httpx.Response(202, headers={**headers, "Location": "/api/v1/job-runs/exec-1"}, json=job_run_payload)
    if path in {"/api/v1/jobs/job-1:stop", "/api/v1/jobs/job-1:pause", "/api/v1/jobs/job-1:resume"} and method == "POST":
        return httpx.Response(200, headers=headers, json=job_payload)
    if path == "/api/v1/jobs/job-1/diagnosis":
        return httpx.Response(200, headers=headers, json={"jobId": "job-1", "status": "HEALTHY", "summary": "OK"})
    if path == "/api/v1/jobs/job-1/traces":
        return httpx.Response(200, headers=headers, json={"jobId": "job-1", "totalSpans": 0, "totalDurationMillis": 0, "spans": []})
    if path == "/api/v1/jobs/job-1/errors":
        return httpx.Response(200, headers=headers, json={"jobId": "job-1", "errorCount": 0, "errors": []})

    # Job runs
    if path == "/api/v1/job-runs" and method == "GET":
        return httpx.Response(200, headers=headers, json={"items": [job_run_payload], "page": {"limit": 50, "nextCursor": None, "totalItems": 1}})
    if path == "/api/v1/job-runs/exec-1" and method == "GET":
        return httpx.Response(200, headers=headers, json=job_run_payload)
    if path in {"/api/v1/job-runs/exec-1:stop", "/api/v1/job-runs/exec-1:pause", "/api/v1/job-runs/exec-1:resume"}:
        return httpx.Response(202, headers=headers, json=job_run_payload)
    if path == "/api/v1/job-runs/exec-1/history":
        return httpx.Response(200, headers=headers, json={"executionId": "exec-1", "jobId": "job-1", "state": "COMPLETED", "steps": [{"seq": 1, "event": "STARTED"}]})

    # Runs (every kind)
    run_payload = {"runId": "exec-1", "kind": "JOB", "jobId": "job-1", "name": "HR Docs", "state": "RUNNING", "stopRequested": False, "runtimeId": "rt-1", "links": {"self": "/api/v1/runs/exec-1"}}
    if path == "/api/v1/runs" and method == "GET":
        return httpx.Response(200, headers=headers, json={"items": [run_payload], "page": {"limit": 50, "nextCursor": None, "totalItems": 1}})
    if path == "/api/v1/runs/exec-1" and method == "GET":
        return httpx.Response(200, headers=headers, json=run_payload)
    if path in {"/api/v1/runs/exec-1:stop", "/api/v1/runs/exec-1:pause", "/api/v1/runs/exec-1:resume"} and method == "POST":
        return httpx.Response(200, headers=headers, json=run_payload)
    if path == "/api/v1/runs/exec-1/history":
        return httpx.Response(200, headers=headers, json={"run": {**run_payload, "state": "STOPPED", "endedAt": "2026-11-20T10:00:00Z"}, "steps": [{"seq": 1, "event": "STARTED"}, {"seq": 2, "event": "STOPPED"}]})

    # Executions
    exec_summary = {"jobId": "job-1", "executionId": "exec-1", "status": "SUCCESS", "durationMs": 42, "totalDocuments": 1}
    if path in {"/api/v1/executions", "/api/v1/jobs/job-1/executions"} and method == "GET":
        return httpx.Response(200, headers=headers, json={"items": [exec_summary], "page": {"limit": 50, "nextCursor": None, "totalItems": 1}})
    if path == "/api/v1/executions/exec-1" and method == "GET":
        return httpx.Response(200, headers=headers, json=exec_summary)
    if path == "/api/v1/executions/exec-1/documents":
        return httpx.Response(200, headers=headers, json={"items": [{"jobId": "job-1", "executionId": "exec-1", "documentId": "doc-1", "status": "SUCCESS"}], "page": {"limit": 50, "nextCursor": None, "totalItems": 1}})
    if path == "/api/v1/executions/exec-1/export":
        return httpx.Response(200, headers={"Content-Type": "text/csv"}, content=b"document_id,status\ndoc-1,SUCCESS\n")
    if path == "/api/v1/executions:query":
        return httpx.Response(200, headers=headers, json={"columns": ["one"], "rows": [[1]]})

    # Connectors
    conn_payload = {"name": "HR Share", "type": "repository", "className": "org.opencrawling.fs.FsConnector", "configuration": {"basePath": "/data"}}
    if path == "/api/v1/connectors" and method == "GET":
        return httpx.Response(200, headers=headers, json={"items": [conn_payload], "page": {"limit": 50, "nextCursor": None, "totalItems": 1}})
    if path == "/api/v1/connectors" and method == "POST":
        return httpx.Response(201, headers={**headers, "Location": "/api/v1/connectors/HR%20Share"}, json=conn_payload)
    if path == "/api/v1/connectors/HR%20Share" and method in {"GET", "PUT"}:
        return httpx.Response(200, headers=headers, json=conn_payload)
    if path == "/api/v1/connectors/HR%20Share" and method == "DELETE":
        return httpx.Response(204)
    if path in {"/api/v1/connectors/HR%20Share:check", "/api/v1/connectors:check"}:
        return httpx.Response(200, headers=headers, json={"success": True, "message": "OK"})

    # Settings
    if path == "/api/v1/settings/system":
        return httpx.Response(200, headers=headers, json={"chunkSize": 512, "tikaEngine": "tika4"})
    if path == "/api/v1/settings/transport":
        return httpx.Response(200, headers=headers, json={"grpcEnabled": True, "grpcPort": 9095})
    if path == "/api/v1/settings/transport:test-grpc":
        return httpx.Response(200, headers=headers, json={"status": "SUCCESS"})

    # Operations
    if path == "/api/v1/system/status":
        return httpx.Response(200, headers=headers, json={"postgres": "UP", "redis": "UP", "ollama": "UP", "system": "HEALTHY"})
    if path == "/api/v1/system/throughput":
        return httpx.Response(200, headers=headers, json=[{"docsPerSec": 10.0}])
    if path == "/api/v1/system/logs":
        return httpx.Response(200, headers=headers, json=["INFO started"])
    if path == "/api/v1/observability/metrics":
        return httpx.Response(200, headers=headers, json={"connectorId": "FileSystem_Local", "averageThroughputDocsPerSec": 12.5, "p95LatencyMillis": 40.0, "activeVirtualThreads": 4.0, "rawMetrics": []})

    # Tools: Migrations, Vespa, Narrativization
    if path in {"/api/v1/migrations/manifoldcf:plan", "/api/v1/migrations/manifoldcf:apply"}:
        return httpx.Response(200, headers=headers, json={"connections": [], "jobs": []})
    if path == "/api/v1/integrations/vespa/health":
        return httpx.Response(200, headers=headers, json={"healthy": True, "message": "UP"})
    if path == "/api/v1/integrations/vespa/document-counts":
        return httpx.Response(200, headers=headers, json=[{"documentType": "opencrawling", "count": 42}])
    if path in {"/api/v1/integrations/vespa:query", "/api/v1/integrations/vespa:deploy-bundled", "/api/v1/integrations/vespa:deploy-custom"}:
        return httpx.Response(200, headers=headers, json={"success": True})
    if path == "/api/v1/narrativization:generate-template":
        return httpx.Response(200, headers=headers, json={"template": "# {{title}}", "mockData": {"title": "Doc"}})

    # Dynamic jobs
    dj_accepted = {"jobId": "dj-1", "executionId": "exec-dj-1", "statusUrl": "/api/v1/dynamic-jobs/dj-1", "state": "RUNNING", "replayed": False}
    dj_status = {"jobId": "dj-1", "executionId": "exec-dj-1", "connector": "SharePoint", "kind": "FOLDER", "state": "RUNNING"}
    if path in {"/api/v1/dynamic-jobs", "/api/v1/dynamic-jobs:ingest-document"} and method == "POST":
        return httpx.Response(202, headers=headers, json=dj_accepted)
    if path == "/api/v1/dynamic-jobs" and method == "GET":
        return httpx.Response(200, headers=headers, json={"items": [dj_status], "page": {"limit": 50, "nextCursor": None, "totalItems": 1}})
    if path in {"/api/v1/dynamic-jobs/dj-1", "/api/v1/dynamic-jobs/dj-1:stop", "/api/v1/dynamic-jobs/dj-1:approve", "/api/v1/dynamic-jobs/dj-1:reject"}:
        return httpx.Response(200, headers=headers, json=dj_status)

    # Discovery
    if path == "/api/v1/discovery/connectors":
        return httpx.Response(200, headers=headers, json=[{"name": "SharePoint", "description": "SP"}])
    if path == "/api/v1/discovery/connectors/SharePoint/tree":
        return httpx.Response(200, headers=headers, json={"connector": "SharePoint", "nodes": [], "truncated": False})
    if path == "/api/v1/discovery/connectors/SharePoint:dry-run":
        return httpx.Response(200, headers=headers, json={"connector": "SharePoint", "matchCount": 10, "countAccuracy": "EXACT", "samples": [], "truncated": False})

    # Sidecars
    if path == "/api/v1/sidecar-stores":
        return httpx.Response(200, headers=headers, json=[{"name": "Ozone_Out", "description": "Ozone", "kind": "ozone"}])
    if path == "/api/v1/sidecar-stores/Ozone_Out/sidecars":
        return httpx.Response(200, headers=headers, json={"connector": "Ozone_Out", "items": [{"id": "doc-1"}], "nextCursor": None, "truncated": False})

    # Schedules
    schedule_payload = {
        "jobId": "job-1",
        "jobName": "HR Docs",
        "schedule": {"cron": "0 2 * * *", "timezone": "UTC", "enabled": True, "overlapPolicy": "SKIP", "misfirePolicy": "SKIP"},
        "state": {"enabled": True, "nextFireAt": "2026-11-20T02:00:00Z", "coalescePending": False, "consecutiveFailures": 0},
        "nextFireTimes": ["2026-11-20T02:00:00Z"],
    }
    if path == "/api/v1/schedules" and method == "GET":
        return httpx.Response(200, headers=headers, json={"items": [schedule_payload], "page": {"limit": 50, "nextCursor": None, "totalItems": 1}})
    if path == "/api/v1/schedules/overview" and method == "GET":
        return httpx.Response(200, headers=headers, json={"schedulerEnabled": True, "pollInterval": "PT5S", "defaultTimezone": "UTC", "totalScheduledJobs": 1, "activeSchedules": 1, "pausedSchedules": 0, "autoPausedSchedules": 0, "skippedLast24h": 0, "upcoming": []})
    if path == "/api/v1/schedules:validate-cron" and method == "POST":
        return httpx.Response(200, headers=headers, json={"valid": True, "cron": "0 2 * * *", "normalizedCron": "0 0 2 * * *", "timezone": "UTC", "humanDescription": "Every day at 02:00 (UTC)", "nextFireTimes": ["2026-11-20T02:00:00Z"]})
    if path == "/api/v1/jobs/job-1/schedule" and method in {"GET", "PUT"}:
        return httpx.Response(200, headers=headers, json=schedule_payload)
    if path == "/api/v1/jobs/job-1/schedule" and method == "DELETE":
        return httpx.Response(204, headers={"X-Request-Id": "req-parity"})
    if path in {"/api/v1/jobs/job-1/schedule:pause", "/api/v1/jobs/job-1/schedule:resume"} and method == "POST":
        return httpx.Response(200, headers=headers, json=schedule_payload)
    if path == "/api/v1/jobs/job-1/schedule/dispatches" and method == "GET":
        return httpx.Response(200, headers=headers, json={"items": [{"id": "disp-1", "jobId": "job-1", "outcome": "STARTED", "runtimeId": "rt-1"}], "page": {"limit": 50, "nextCursor": None, "totalItems": 1}})

    raise AssertionError(f"Unhandled mock request: {method} {path}")


def test_template_matching_rules() -> None:
    assert match_operation_template("GET /api/v1/jobs", "GET /api/v1/jobs")
    assert match_operation_template("POST /api/v1/jobs/{id}:start", "POST /api/v1/jobs/1763038591234:start")
    assert match_operation_template("GET /api/v1/connectors/{name}", "GET /api/v1/connectors/HR%20Share")
    assert not match_operation_template("POST /api/v1/jobs/{id}:stop", "POST /api/v1/jobs/1:start")
    assert not match_operation_template("GET /api/v1/jobs/{id}", "GET /api/v1/jobs/1/traces")
    assert not match_operation_template("POST /api/v1/connectors/{name}:check", "POST /api/v1/connectors:check")


def test_sync_client_covers_every_platform_api_v1_operation(tmp_path: Path) -> None:
    expected_operations = load_v1_operations()
    assert len(expected_operations) == 80, f"Expected 80 Platform API v1 operations, found {len(expected_operations)}"

    recorded: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        recorded.append(f"{request.method.upper()} {request.url.raw_path.decode('ascii').split('?', 1)[0]}")
        return _mock_response_for(request)

    http_client = httpx.Client(transport=httpx.MockTransport(handler))
    zip_file = tmp_path / "vespa-app.zip"
    zip_file.write_bytes(b"PK\x03\x04")

    with OpenCrawlingClient("http://localhost:8080", http_client=http_client, retry_policy=RetryPolicy.none()) as client:
        # Root (4)
        assert client.index().api_version == "1.0.0"
        assert client.version().api_version == "1.0.0"
        assert client.health().is_healthy
        assert client.me().principal == "alice"

        # Jobs (10 + 3 observability)
        assert len(client.jobs.list(JobFilter(status="Ready"), PageRequest.of_size(10))) == 1
        job_input = JobInput(name="HR Docs", repository_connector="FileSystem_Local", output_connector="PGVector_Output", path="/data")
        created = client.jobs.create(job_input, RequestOptions.idempotent("k1"))
        assert created.location == "/api/v1/jobs/job-1"
        fetched = client.jobs.get("job-1")
        assert fetched.etag == '"v1-etag"'
        client.jobs.replace("job-1", job_input, RequestOptions.matching(fetched.etag or ""))
        client.jobs.patch("job-1", {"path": "/data/v2"}, RequestOptions.matching(fetched.etag or ""))
        client.jobs.delete("job-1", RequestOptions.matching(fetched.etag or ""))
        assert client.jobs.start("job-1").data.execution_id == "exec-1"
        client.jobs.stop("job-1")
        client.jobs.pause("job-1")
        client.jobs.resume("job-1")
        client.jobs.diagnose("job-1")
        client.jobs.traces("job-1")
        client.jobs.errors("job-1", timeframe="24h")

        # Job runs (6)
        assert len(client.job_runs.list("job-1")) == 1
        assert client.job_runs.get("exec-1").execution_id == "exec-1"
        client.job_runs.stop("exec-1")
        client.job_runs.pause("exec-1")
        client.job_runs.resume("exec-1")
        assert client.job_runs.history("exec-1").is_terminal

        # Runs, every kind (6)
        runs = client.runs.list(kind="JOB", state="RUNNING", job_id="job-1")
        assert runs.items[0].run_id == "exec-1" and runs.items[0].kind == "JOB"
        assert client.runs.get("exec-1").runtime_id == "rt-1"
        assert client.runs.pause("exec-1").state == "RUNNING"
        client.runs.resume("exec-1")
        client.runs.stop("exec-1")
        history = client.runs.history("exec-1")
        assert history.is_terminal and [step.event for step in history.steps] == ["STARTED", "STOPPED"]

        # Executions (6)
        assert len(client.executions.list(ExecutionFilter(job_id="job-1"))) == 1
        assert len(client.executions.list_for_job("job-1")) == 1
        assert client.executions.get("exec-1").status == "SUCCESS"
        assert len(client.executions.documents("exec-1")) == 1
        assert b"doc-1" in client.executions.export("exec-1", "csv")
        assert client.executions.query("SELECT 1 AS one").columns == ["one"]

        # Connectors (7)
        assert len(client.connectors.list(type="repository")) == 1
        conn_input = ConnectorInput(name="HR Share", type="repository", class_name="org.opencrawling.fs.FsConnector", configuration={"basePath": "/data"})
        client.connectors.create(conn_input)
        client.connectors.get("HR Share")
        client.connectors.replace("HR Share", conn_input)
        client.connectors.delete("HR Share", force=True)
        assert client.connectors.check("HR Share").success
        assert client.connectors.check_definition(conn_input).success

        # Settings (5)
        sys_settings = client.settings.system()
        client.settings.replace_system(sys_settings.data.with_updates(chunk_size=1024))
        tr_settings = client.settings.transport()
        client.settings.replace_transport(tr_settings.data.with_updates(grpc_port=9095))
        assert client.settings.test_grpc("localhost", 9095)["status"] == "SUCCESS"

        # Operations (4)
        assert client.operations.status().system == "HEALTHY"
        assert len(client.operations.throughput()) == 1
        assert len(client.operations.logs()) == 1
        assert client.operations.metrics("FileSystem_Local").average_throughput_docs_per_sec == 12.5

        # Migrations (2)
        client.migrations.plan_manifoldcf({"mcfUrl": "http://mcf:8345"})
        client.migrations.apply_manifoldcf({"mcfUrl": "http://mcf:8345"})

        # Vespa (5)
        client.vespa.health("http://vespa:8080")
        client.vespa.document_counts("http://vespa:8080")
        client.vespa.query({"endpoint": "http://vespa:8080", "queryText": "test"})
        client.vespa.deploy_bundled_schema("http://vespa:19071")
        client.vespa.deploy_custom_schema(zip_file, "http://vespa:19071")

        # Narrativization (1)
        client.narrativization.generate_template(
            CopilotRequest(connector_type="alfresco", fields=[CopilotField(name="title", type="string")])
        )

        # Dynamic jobs (7)
        client.dynamic_jobs.start(DynamicJobRequest(connector="SharePoint", start="/Legal"))
        client.dynamic_jobs.ingest_document(DocumentIngestRequest.upsert("SharePoint", "doc-1"))
        client.dynamic_jobs.list()
        client.dynamic_jobs.get("dj-1")
        client.dynamic_jobs.stop("dj-1")
        client.dynamic_jobs.approve("dj-1")
        client.dynamic_jobs.reject("dj-1", "out of scope")

        # Discovery (3)
        client.discovery.connectors()
        client.discovery.tree("SharePoint", start="/Legal", depth=2, max_nodes=50, include_documents=True)
        client.discovery.dry_run("SharePoint", DiscoveryDryRunRequest(scope="/Legal", sample_size=5))

        # Sidecars (2)
        assert len(client.sidecars.stores()) == 1
        assert len(client.sidecars.read("Ozone_Out", prefix="hr/").items) == 1

        # Schedules (9)
        assert len(client.schedules.list(ScheduleFilter(enabled=True))) == 1
        assert client.schedules.overview().active_schedules == 1
        assert client.schedules.validate_cron("0 2 * * *").valid
        sched = client.schedules.set("job-1", JobScheduleConfig(cron="0 2 * * *"))
        assert client.schedules.get("job-1").data.job_id == "job-1"
        client.schedules.pause("job-1", RequestOptions.matching(sched.etag or ""))
        client.schedules.resume("job-1", RequestOptions.matching(sched.etag or ""))
        assert len(client.schedules.dispatches("job-1")) == 1
        client.schedules.delete("job-1", RequestOptions.matching(sched.etag or ""))

    uncovered = sorted(
        op for op in expected_operations if not any(match_operation_template(op, req) for req in recorded)
    )
    assert not uncovered, f"Uncovered Platform API v1 operations in sync client: {uncovered}"


async def test_async_client_covers_every_platform_api_v1_operation(tmp_path: Path) -> None:
    expected_operations = load_v1_operations()
    assert len(expected_operations) == 80

    recorded: list[str] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        recorded.append(f"{request.method.upper()} {request.url.raw_path.decode('ascii').split('?', 1)[0]}")
        return _mock_response_for(request)

    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    zip_file = tmp_path / "vespa-app.zip"
    zip_file.write_bytes(b"PK\x03\x04")

    async with AsyncOpenCrawlingClient("http://localhost:8080", http_client=http_client, retry_policy=RetryPolicy.none()) as client:
        # Root (4)
        await client.index()
        await client.version()
        await client.health()
        await client.me()

        # Jobs (13)
        await client.jobs.list()
        job_input = JobInput(name="HR Docs", repository_connector="FileSystem_Local", output_connector="PGVector_Output", path="/data")
        await client.jobs.create(job_input)
        await client.jobs.get("job-1")
        await client.jobs.replace("job-1", job_input)
        await client.jobs.patch("job-1", {"path": "/data/v2"})
        await client.jobs.delete("job-1")
        await client.jobs.start("job-1")
        await client.jobs.stop("job-1")
        await client.jobs.pause("job-1")
        await client.jobs.resume("job-1")
        await client.jobs.diagnose("job-1")
        await client.jobs.traces("job-1")
        await client.jobs.errors("job-1", timeframe="1h")

        # Job runs (6)
        await client.job_runs.list("job-1")
        await client.job_runs.get("exec-1")
        await client.job_runs.stop("exec-1")
        await client.job_runs.pause("exec-1")
        await client.job_runs.resume("exec-1")
        await client.job_runs.history("exec-1")

        # Runs, every kind (6)
        await client.runs.list(kind="DYNAMIC_FOLDER")
        await client.runs.get("exec-1")
        await client.runs.stop("exec-1")
        await client.runs.pause("exec-1")
        await client.runs.resume("exec-1")
        assert (await client.runs.history("exec-1")).is_terminal

        # Executions (6)
        await client.executions.list()
        await client.executions.list_for_job("job-1")
        await client.executions.get("exec-1")
        await client.executions.documents("exec-1")
        await client.executions.export("exec-1", "csv")
        await client.executions.query("SELECT 1")

        # Connectors (7)
        await client.connectors.list()
        conn_input = ConnectorInput(name="HR Share", type="repository", class_name="org.opencrawling.fs.FsConnector")
        await client.connectors.create(conn_input)
        await client.connectors.get("HR Share")
        await client.connectors.replace("HR Share", conn_input)
        await client.connectors.delete("HR Share", force=True)
        await client.connectors.check("HR Share")
        await client.connectors.check_definition(conn_input)

        # Settings (5)
        await client.settings.system()
        await client.settings.replace_system(SystemSettings(chunk_size=512))
        await client.settings.transport()
        await client.settings.replace_transport(TransportSettings(grpc_port=9095))
        await client.settings.test_grpc("localhost", 9095)

        # Operations (4)
        await client.operations.status()
        await client.operations.throughput()
        await client.operations.logs()
        await client.operations.metrics("FileSystem_Local")

        # Migrations (2)
        await client.migrations.plan_manifoldcf({"mcfUrl": "http://mcf:8345"})
        await client.migrations.apply_manifoldcf({"mcfUrl": "http://mcf:8345"})

        # Vespa (5)
        await client.vespa.health("http://vespa:8080")
        await client.vespa.document_counts("http://vespa:8080")
        await client.vespa.query({"endpoint": "http://vespa:8080"})
        await client.vespa.deploy_bundled_schema("http://vespa:19071")
        await client.vespa.deploy_custom_schema(zip_file, "http://vespa:19071")

        # Narrativization (1)
        await client.narrativization.generate_template(CopilotRequest(connector_type="alfresco", fields=[]))

        # Dynamic jobs (7)
        await client.dynamic_jobs.start(DynamicJobRequest(connector="SharePoint", start="/Legal"))
        await client.dynamic_jobs.ingest_document(DocumentIngestRequest.upsert("SharePoint", "doc-1"))
        await client.dynamic_jobs.list()
        await client.dynamic_jobs.get("dj-1")
        await client.dynamic_jobs.stop("dj-1")
        await client.dynamic_jobs.approve("dj-1")
        await client.dynamic_jobs.reject("dj-1", "reason")

        # Discovery (3)
        await client.discovery.connectors()
        await client.discovery.tree("SharePoint")
        await client.discovery.dry_run("SharePoint", DiscoveryDryRunRequest(scope="/Legal"))

        # Sidecars (2)
        await client.sidecars.stores()
        await client.sidecars.read("Ozone_Out")

        # Schedules (9)
        await client.schedules.list()
        await client.schedules.overview()
        await client.schedules.validate_cron("0 2 * * *")
        await client.schedules.set("job-1", JobScheduleConfig(cron="0 2 * * *"))
        await client.schedules.get("job-1")
        await client.schedules.pause("job-1")
        await client.schedules.resume("job-1")
        await client.schedules.dispatches("job-1")
        await client.schedules.delete("job-1")

    uncovered = sorted(
        op for op in expected_operations if not any(match_operation_template(op, req) for req in recorded)
    )
    assert not uncovered, f"Uncovered Platform API v1 operations in async client: {uncovered}"
