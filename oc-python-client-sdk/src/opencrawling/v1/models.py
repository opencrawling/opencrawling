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
"""Pydantic v2 data models for OpenCrawling Platform API v1 (``/api/v1``)."""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from importlib import import_module
from typing import Any, ClassVar

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class OpenCrawlingModel(BaseModel):
    """Base Pydantic v2 model with automatic camelCase JSON aliases and forward-compatibility."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )


class LosslessModel(BaseModel):
    """Base model preserving unknown/additional server fields losslessly across round-trips."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="allow",
    )

    def to_payload(self) -> dict[str, Any]:
        """Serialize to camelCase JSON dict retaining all known-set and unknown extra fields."""
        return self.model_dump(mode="json", by_alias=True, exclude_unset=True)


# ---------------------------------------------------------------------------
# Platform Root Models
# ---------------------------------------------------------------------------


class ApiIndex(OpenCrawlingModel):
    """Entry point response from ``GET /api/v1``."""

    name: str = ""
    api_version: str = ""
    links: dict[str, str] = Field(default_factory=dict)
    features: dict[str, bool] = Field(default_factory=dict)
    documentation: dict[str, str] = Field(default_factory=dict)

    def has_feature(self, feature: str) -> bool:
        """Return ``True`` if the named optional feature (e.g. ``discovery``, ``dynamicJobs``) is enabled."""
        return bool(self.features.get(feature, False))


class VersionInfo(OpenCrawlingModel):
    """API and runtime version metadata from ``GET /api/v1/version``."""

    api_version: str = ""
    runtime_version: str | None = None


class Health(OpenCrawlingModel):
    """Runtime and dependency health report from ``GET /api/v1/health``."""

    status: str = "UNKNOWN"
    components: dict[str, str] = Field(default_factory=dict)

    @property
    def is_healthy(self) -> bool:
        return self.status == "HEALTHY"


class CallerInfo(OpenCrawlingModel):
    """Authenticated caller identity and management privileges from ``GET /api/v1/me``."""

    authenticated: bool = False
    principal: str | None = None
    groups: list[str] = Field(default_factory=list)
    tenant: str | None = None
    management_authorization: bool = False
    admin: bool = False
    can_create_jobs: bool = False


# ---------------------------------------------------------------------------
# Jobs & Job Runs
# ---------------------------------------------------------------------------


class JobAction(StrEnum):
    """Permitted actions on a job when management authorization is active."""

    READ = "READ"
    EXECUTE = "EXECUTE"
    MANAGE = "MANAGE"


class JobAccess(OpenCrawlingModel):
    """Access control block attached to a pipeline job."""

    owner: str | None = None
    tenant_id: str | None = None
    allowed_users: list[str] = Field(default_factory=list)
    allowed_groups: list[str] = Field(default_factory=list)
    viewer_users: list[str] = Field(default_factory=list)
    viewer_groups: list[str] = Field(default_factory=list)

    @classmethod
    def for_groups(
        cls,
        allowed_groups: list[str] | None = None,
        viewer_groups: list[str] | None = None,
    ) -> JobAccess:
        return cls(
            allowed_groups=list(allowed_groups) if allowed_groups else [],
            viewer_groups=list(viewer_groups) if viewer_groups else [],
        )


class NarrativizationConfig(OpenCrawlingModel):
    """Auto-Narrativization enrichment settings for a pipeline job."""

    enabled: bool = False
    template: str | None = None
    connector_type: str | None = None


class JobState(OpenCrawlingModel):
    """Current runtime execution state of a pipeline job."""

    status: str = ""
    stage: str | None = None
    documents: int = 0
    last_run: str | None = None


class JobInput(OpenCrawlingModel):
    """Job definition payload for creating or replacing a pipeline job."""

    name: str
    repository_connector: str
    output_connector: str
    path: str
    authority_connector: str | None = None
    transformation_connector: str | None = None
    pipeline_mode: str | None = None
    crawl_spec: dict[str, Any] | None = None
    narrativization: NarrativizationConfig | None = None
    access: JobAccess | None = None


class Job(OpenCrawlingModel):
    """Pipeline job resource returned by Platform API v1."""

    id: str
    name: str
    repository_connector: str
    output_connector: str
    authority_connector: str | None = None
    transformation_connector: str | None = None
    path: str
    pipeline_mode: str | None = None
    crawl_spec: dict[str, Any] | None = None
    narrativization: NarrativizationConfig | None = None
    access: JobAccess | None = None
    state: JobState | None = None
    permissions: list[JobAction] = Field(default_factory=list)

    def to_input(self) -> JobInput:
        """Convert this job resource into a ``JobInput`` suitable for ``replace``."""
        return JobInput(
            name=self.name,
            repository_connector=self.repository_connector,
            output_connector=self.output_connector,
            authority_connector=self.authority_connector,
            transformation_connector=self.transformation_connector,
            path=self.path,
            pipeline_mode=self.pipeline_mode,
            crawl_spec=self.crawl_spec,
            narrativization=self.narrativization,
            access=self.access,
        )


class JobFilter(OpenCrawlingModel):
    """Optional filters for listing jobs (``GET /api/v1/jobs``)."""

    status: str | None = None
    pipeline_mode: str | None = None
    repository_connector: str | None = None
    output_connector: str | None = None
    q: str | None = None
    sort: str | None = None


class JobRun(OpenCrawlingModel):
    """Active job run representation (``/api/v1/job-runs``)."""

    execution_id: str
    job_id: str
    started_at: datetime | None = None
    stop_requested: bool = False
    state: str | None = None
    runtime_id: str | None = None


class JobRunStep(OpenCrawlingModel):
    """Single state transition recorded in a job run's history."""

    seq: int
    event: str
    at: datetime | None = None
    runtime_id: str | None = None
    detail: str | None = None


TERMINAL_RUN_STATES: frozenset[str] = frozenset({"COMPLETED", "STOPPED", "FAILED", "INTERRUPTED"})


class JobRunHistory(OpenCrawlingModel):
    """In-progress or finished job run along with ordered state transition history."""

    execution_id: str
    job_id: str
    state: str | None = None
    started_at: datetime | None = None
    ended_at: datetime | None = None
    runtime_id: str | None = None
    steps: list[JobRunStep] = Field(default_factory=list)

    @property
    def is_terminal(self) -> bool:
        return self.ended_at is not None or (self.state in TERMINAL_RUN_STATES)


# ---------------------------------------------------------------------------
# Executions & Lakehouse Audit Reporting
# ---------------------------------------------------------------------------


class ExecutionFilter(OpenCrawlingModel):
    """Optional filters for listing executions (``GET /api/v1/executions``)."""

    job_id: str | None = None
    status: str | None = None
    pipeline_mode: str | None = None
    sort: str | None = None


class JobReportSummary(OpenCrawlingModel):
    """Executive summary of a finished crawl execution."""

    job_id: str
    execution_id: str
    job_name: str | None = None
    pipeline_mode: str | None = None
    repository_connector: str | None = None
    output_connector: str | None = None
    status: str | None = None
    start_time: datetime | None = None
    end_time: datetime | None = None
    duration_ms: int = 0
    total_documents: int = 0
    successful_documents: int = 0
    failed_documents: int = 0
    skipped_documents: int = 0
    total_chunks: int | None = None
    total_tokens: int | None = None
    estimated_embedding_cost_usd: float | None = None
    embedding_model: str | None = None
    total_binary_bytes: int | None = None
    total_reclaimed_bytes: int | None = None
    safe_pruned_count: int | None = None
    verified_checksum_count: int | None = None
    report_path_parquet: str | None = None
    report_path_csv: str | None = None
    report_path_json: str | None = None


class DocumentLedgerEntry(OpenCrawlingModel):
    """Granular document-level execution audit ledger record."""

    job_id: str | None = None
    execution_id: str | None = None
    pipeline_mode: str | None = None
    document_id: str | None = None
    source_uri: str | None = None
    connector_source: str | None = None
    connector_target: str | None = None
    status: str | None = None
    duration_ms: int = 0
    timestamp_utc: datetime | None = None
    error_code: str | None = None
    error_message: str | None = None
    rag_chunk_count: int | None = None
    rag_token_count: int | None = None
    rag_embedding_model: str | None = None
    rag_embedding_dimensions: int | None = None
    rag_vector_table: str | None = None
    mig_binary_size_bytes: int | None = None
    mig_sha256_checksum: str | None = None
    mig_target_bucket: str | None = None
    mig_target_key: str | None = None
    mig_sidecar_written: bool | None = None
    mig_callback_http_status: int | None = None
    mig_safe_pruned: bool | None = None
    mig_reclaimed_bytes: int | None = None


class QueryResult(OpenCrawlingModel):
    """Result of a read-only DuckDB analytical SQL query (``POST /api/v1/executions:query``)."""

    columns: list[str] = Field(default_factory=list)
    rows: list[list[Any]] = Field(default_factory=list)

    def to_dicts(self) -> list[dict[str, Any]]:
        """Return rows as a list of ``{column: value}`` dictionaries."""
        return [dict(zip(self.columns, row, strict=False)) for row in self.rows]

    def to_pyarrow(self) -> Any:
        """Convert query results to a ``pyarrow.Table`` (requires ``opencrawling-sdk[dataframe]``)."""
        try:
            pa = import_module("pyarrow")
        except ImportError as exc:
            raise ImportError(
                "pyarrow is required for to_pyarrow(). Install with: pip install 'opencrawling-sdk[dataframe]'"
            ) from exc
        column_data = {
            col: [row[idx] if idx < len(row) else None for row in self.rows]
            for idx, col in enumerate(self.columns)
        }
        return pa.table(column_data)

    def to_pandas(self) -> Any:
        """Convert query results to a ``pandas.DataFrame`` (requires ``opencrawling-sdk[dataframe]``)."""
        try:
            pd = import_module("pandas")
        except ImportError as exc:
            raise ImportError(
                "pandas is required for to_pandas(). Install with: pip install 'opencrawling-sdk[dataframe]'"
            ) from exc
        return pd.DataFrame(self.rows, columns=self.columns)

    def to_polars(self) -> Any:
        """Convert query results to a ``polars.DataFrame`` (requires ``opencrawling-sdk[dataframe]``)."""
        try:
            pl = import_module("polars")
        except ImportError as exc:
            raise ImportError(
                "polars is required for to_polars(). Install with: pip install 'opencrawling-sdk[dataframe]'"
            ) from exc
        column_data = {
            col: [row[idx] if idx < len(row) else None for row in self.rows]
            for idx, col in enumerate(self.columns)
        }
        return pl.DataFrame(column_data)


# ---------------------------------------------------------------------------
# Connectors
# ---------------------------------------------------------------------------


class ConnectorInput(OpenCrawlingModel):
    """Connector definition payload for creating, replacing, or checking a connector."""

    name: str | None = None
    description: str | None = None
    type: str
    class_name: str
    max_connections: int | None = None
    configuration: dict[str, str] | None = None


class Connector(OpenCrawlingModel):
    """Configured connector returned by Platform API v1."""

    MASK: ClassVar[str] = "********"

    name: str
    description: str | None = None
    type: str
    class_name: str
    max_connections: int | None = None
    configuration: dict[str, str] | None = None

    def to_input(self) -> ConnectorInput:
        """Convert this connector resource into a ``ConnectorInput`` for ``replace``."""
        return ConnectorInput(
            name=self.name,
            description=self.description,
            type=self.type,
            class_name=self.class_name,
            max_connections=self.max_connections,
            configuration=dict(self.configuration) if self.configuration is not None else None,
        )


class ConnectionCheckResponse(OpenCrawlingModel):
    """Result of a live connector connectivity/authentication probe."""

    success: bool
    message: str | None = None
    details: str | None = None


# ---------------------------------------------------------------------------
# Settings (Lossless Round-Trip) & Operations / Observability
# ---------------------------------------------------------------------------


class SystemSettings(LosslessModel):
    """Runtime crawling & extraction settings (``/api/v1/settings/system``).

    Configured with ``extra="allow"`` so any unknown or future server fields survive a
    read-modify-replace cycle intact without being reset to defaults.
    """

    embedding_provider: str | None = None
    ollama_base_url: str | None = None
    ollama_model: str | None = None
    vector_dimensions: int | None = None
    chunker_type: str | None = None
    chunk_size: int | None = None
    claim_check_store: str | None = None
    tika_engine: str | None = None
    tika_fork_enabled: bool | None = None
    tika_timeout_ms: int | None = None

    def with_updates(self, **updates: Any) -> SystemSettings:
        """Return a copy with the specified known or extra fields updated, preserving all others."""
        payload = self.to_payload()
        for key, value in updates.items():
            if key in self.__class__.model_fields:
                alias = self.__class__.model_fields[key].alias or key
                payload[alias] = value
            else:
                payload[key] = value
        return SystemSettings.model_validate(payload)


class TransportSettings(LosslessModel):
    """Internal gRPC/REST transport settings (``/api/v1/settings/transport``).

    Configured with ``extra="allow"`` for lossless round-tripping of all server fields.
    """

    grpc_enabled: bool | None = None
    grpc_host: str | None = None
    grpc_port: int | None = None
    tls_enabled: bool | None = None
    rest_fallback_enabled: bool | None = None

    def with_updates(self, **updates: Any) -> TransportSettings:
        """Return a copy with the specified known or extra fields updated, preserving all others."""
        payload = self.to_payload()
        for key, value in updates.items():
            if key in self.__class__.model_fields:
                alias = self.__class__.model_fields[key].alias or key
                payload[alias] = value
            else:
                payload[key] = value
        return TransportSettings.model_validate(payload)


class SystemStatus(LosslessModel):
    """Status of runtime dependencies from ``GET /api/v1/system/status``."""

    postgres: str | None = None
    redis: str | None = None
    ollama: str | None = None
    system: str | None = None


class MetricRecord(OpenCrawlingModel):
    """Single connector performance metric entry."""

    connector_id: str | None = None
    metric_name: str | None = None
    value: float = 0.0
    timestamp: Any = None


class ThroughputMetricsResponse(OpenCrawlingModel):
    """Throughput, latency, and virtual-thread telemetry per connector."""

    connector_id: str | None = None
    average_throughput_docs_per_sec: float = 0.0
    p95_latency_millis: float = 0.0
    active_virtual_threads: float = 0.0
    raw_metrics: list[MetricRecord] = Field(default_factory=list)


class ErrorLogRecord(OpenCrawlingModel):
    """Job observability error log entry."""

    job_id: str | None = None
    timestamp: Any = None
    level: str | None = None
    component: str | None = None
    message: str | None = None
    stack_trace: str | None = None


class ErrorLogsResponse(OpenCrawlingModel):
    """Error log collection for a job."""

    job_id: str
    error_count: int = 0
    errors: list[ErrorLogRecord] = Field(default_factory=list)


class SpanRecord(OpenCrawlingModel):
    """OpenTelemetry execution trace span."""

    span_id: str
    trace_id: str
    job_id: str | None = None
    stage: str | None = None
    component: str | None = None
    start_time_millis: int = 0
    duration_millis: int = 0
    status: str | None = None
    error_message: str | None = None
    attributes: dict[str, str] = Field(default_factory=dict)


class JobTraceResponse(OpenCrawlingModel):
    """OpenTelemetry spans and per-stage timing breakdown for a job."""

    job_id: str
    total_spans: int = 0
    total_duration_millis: int = 0
    overall_status: str | None = None
    spans: list[SpanRecord] = Field(default_factory=list)
    stage_duration_breakdown_millis: dict[str, int] = Field(default_factory=dict)


class DiagnosticReport(OpenCrawlingModel):
    """AI-powered Root Cause Analysis report for a job."""

    job_id: str
    job_name: str | None = None
    timestamp: str | None = None
    status: str | None = None
    summary: str | None = None
    root_cause_analysis: str | None = None
    stage_timing_millis: dict[str, int] = Field(default_factory=dict)
    bottleneck_insights: list[str] = Field(default_factory=list)
    recommended_actions: list[str] = Field(default_factory=list)
    error_logs: list[ErrorLogRecord] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Narrativization Copilot
# ---------------------------------------------------------------------------


class CopilotField(OpenCrawlingModel):
    """Metadata field descriptor passed to the Auto-Narrativization Copilot."""

    name: str
    type: str
    description: str | None = None


class CopilotRequest(OpenCrawlingModel):
    """Request payload for ``POST /api/v1/narrativization:generate-template``."""

    connector_type: str
    fields: list[CopilotField] = Field(default_factory=list)


class CopilotResponse(OpenCrawlingModel):
    """Generated Mustache template and sample mock data from the Narrativization Copilot."""

    template: str = ""
    mock_data: dict[str, Any] = Field(default_factory=dict)


# ---------------------------------------------------------------------------
# Dynamic Jobs
# ---------------------------------------------------------------------------


class DynamicJobRequest(OpenCrawlingModel):
    """Request payload for ``POST /api/v1/dynamic-jobs``."""

    connector: str
    start: str
    name: str | None = None
    validate_only: bool | None = None
    chunking: dict[str, Any] | None = None


class DocumentIngestRequest(OpenCrawlingModel):
    """Request payload for ``POST /api/v1/dynamic-jobs:ingest-document``."""

    connector: str
    document: str
    action: str | None = None
    chunking: dict[str, Any] | None = None

    @classmethod
    def upsert(
        cls,
        connector: str,
        document: str,
        *,
        chunking: dict[str, Any] | None = None,
    ) -> DocumentIngestRequest:
        return cls(connector=connector, document=document, action="UPSERT", chunking=chunking)

    @classmethod
    def delete(cls, connector: str, document: str) -> DocumentIngestRequest:
        return cls(connector=connector, document=document, action="DELETE")


class DynamicJobAccepted(OpenCrawlingModel):
    """Outcome of starting a dynamic job, validating it, or ingesting/deleting one document."""

    job_id: str | None = None
    execution_id: str | None = None
    status_url: str | None = None
    state: str | None = None
    replayed: bool = False
    valid: bool | None = None
    connector: str | None = None
    start: dict[str, Any] | None = None
    document: str | None = None
    note: str | None = None


class DynamicJob(OpenCrawlingModel):
    """Status and lifecycle metadata of a dynamic job."""

    job_id: str
    execution_id: str | None = None
    name: str | None = None
    connector: str | None = None
    kind: str | None = None
    start: dict[str, Any] | None = None
    state: str | None = None
    stop_requested: bool = False
    created_at: datetime | None = None
    finished_at: datetime | None = None
    documents: int | None = None
    owner: str | None = None
    trigger_source: str | None = None
    approved_by: str | None = None
    note: str | None = None
    chunking: dict[str, Any] | None = None


# ---------------------------------------------------------------------------
# Discovery
# ---------------------------------------------------------------------------


class DiscoveryConnectorInfo(OpenCrawlingModel):
    """Repository connector eligible for pre-crawl discovery."""

    name: str
    description: str | None = None


class DiscoveryNodeView(OpenCrawlingModel):
    """Folder or document node discovered in a repository tree."""

    id: str | None = None
    source_id: str | None = None
    parent_id: str | None = None
    name: str | None = None
    path: str | None = None
    type: str | None = None
    depth: int = 0
    mime_type: str | None = None
    size_bytes: int | None = None
    last_modified: datetime | None = None


class DiscoveryTreeView(OpenCrawlingModel):
    """Breadth-first repository tree discovery response."""

    connector: str
    root: str | None = None
    nodes: list[DiscoveryNodeView] = Field(default_factory=list)
    hidden_count: int | None = None
    truncated: bool = False
    truncation_reason: str | None = None


class DiscoveryDryRunRequest(OpenCrawlingModel):
    """Request body for ``POST /api/v1/discovery/connectors/{connector}:dry-run``."""

    scope: str | None = None
    sample_size: int | None = None
    max_scan: int | None = None


class DiscoveryDryRunView(OpenCrawlingModel):
    """Pre-crawl match estimation and sample preview."""

    connector: str
    match_count: int | None = None
    count_accuracy: str | None = None
    samples: list[DiscoveryNodeView] = Field(default_factory=list)
    hidden_count: int | None = None
    truncated: bool = False
    truncation_reason: str | None = None
    note: str | None = None


# ---------------------------------------------------------------------------
# OIS Sidecars
# ---------------------------------------------------------------------------


class SidecarStoreInfo(OpenCrawlingModel):
    """Registered OIS sidecar store descriptor (``GET /api/v1/sidecar-stores``)."""

    name: str
    description: str | None = None
    kind: str | None = None


class SidecarPage(OpenCrawlingModel):
    """One page of authorized OIS sidecar documents."""

    connector: str | None = None
    prefix: str | None = None
    items: list[dict[str, Any]] = Field(default_factory=list)
    cursor: str | None = None
    next_cursor: str | None = None
    truncated: bool = False

    @property
    def has_next(self) -> bool:
        return bool(self.next_cursor and self.next_cursor.strip())
