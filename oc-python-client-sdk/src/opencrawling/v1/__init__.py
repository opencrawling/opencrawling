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

"""Platform API v1 resource clients and Pydantic models."""

from opencrawling.v1.connectors import AsyncConnectorsClient, ConnectorsClient
from opencrawling.v1.discovery import AsyncDiscoveryClient, DiscoveryClient
from opencrawling.v1.dynamic_jobs import AsyncDynamicJobsClient, DynamicJobsClient
from opencrawling.v1.executions import AsyncExecutionsClient, ExecutionsClient
from opencrawling.v1.job_runs import AsyncJobRunsClient, JobRunsClient
from opencrawling.v1.jobs import AsyncJobsClient, JobsClient
from opencrawling.v1.migrations import AsyncMigrationsClient, MigrationsClient
from opencrawling.v1.models import (
    ApiIndex,
    CallerInfo,
    ConnectionCheckResponse,
    Connector,
    ConnectorInput,
    CopilotField,
    CopilotRequest,
    CopilotResponse,
    CronValidationResult,
    DiagnosticReport,
    DiscoveryConnectorInfo,
    DiscoveryDryRunRequest,
    DiscoveryDryRunView,
    DiscoveryNodeView,
    DiscoveryTreeView,
    DispatchOutcome,
    DocumentIngestRequest,
    DocumentLedgerEntry,
    DynamicJob,
    DynamicJobAccepted,
    DynamicJobRequest,
    ErrorLogRecord,
    ErrorLogsResponse,
    ExecutionFilter,
    Health,
    Job,
    JobAccess,
    JobAction,
    JobFilter,
    JobInput,
    JobReportSummary,
    JobRun,
    JobRunHistory,
    JobRunStep,
    JobSchedule,
    JobScheduleConfig,
    JobScheduleState,
    JobState,
    JobTraceResponse,
    MetricRecord,
    MisfirePolicy,
    NarrativizationConfig,
    OverlapPolicy,
    QueryResult,
    ScheduleDispatch,
    ScheduleFilter,
    SchedulerOverview,
    SidecarPage,
    SidecarStoreInfo,
    SpanRecord,
    SystemSettings,
    SystemStatus,
    ThroughputMetricsResponse,
    TransportSettings,
    UpcomingExecution,
    VersionInfo,
)
from opencrawling.v1.narrativization import AsyncNarrativizationClient, NarrativizationClient
from opencrawling.v1.operations import AsyncOperationsClient, OperationsClient
from opencrawling.v1.schedules import AsyncSchedulesClient, SchedulesClient
from opencrawling.v1.settings import AsyncSettingsClient, SettingsClient
from opencrawling.v1.sidecars import AsyncSidecarsClient, SidecarsClient
from opencrawling.v1.vespa import AsyncVespaClient, VespaClient

__all__ = [
    "ApiIndex",
    "AsyncConnectorsClient",
    "AsyncDiscoveryClient",
    "AsyncDynamicJobsClient",
    "AsyncExecutionsClient",
    "AsyncJobRunsClient",
    "AsyncJobsClient",
    "AsyncMigrationsClient",
    "AsyncNarrativizationClient",
    "AsyncOperationsClient",
    "AsyncSchedulesClient",
    "AsyncSettingsClient",
    "AsyncSidecarsClient",
    "AsyncVespaClient",
    "CallerInfo",
    "ConnectionCheckResponse",
    "Connector",
    "ConnectorInput",
    "ConnectorsClient",
    "CopilotField",
    "CopilotRequest",
    "CopilotResponse",
    "CronValidationResult",
    "DiagnosticReport",
    "DiscoveryClient",
    "DiscoveryConnectorInfo",
    "DiscoveryDryRunRequest",
    "DiscoveryDryRunView",
    "DiscoveryNodeView",
    "DiscoveryTreeView",
    "DispatchOutcome",
    "DocumentIngestRequest",
    "DocumentLedgerEntry",
    "DynamicJob",
    "DynamicJobAccepted",
    "DynamicJobRequest",
    "DynamicJobsClient",
    "ErrorLogRecord",
    "ErrorLogsResponse",
    "ExecutionFilter",
    "ExecutionsClient",
    "Health",
    "Job",
    "JobAccess",
    "JobAction",
    "JobFilter",
    "JobInput",
    "JobReportSummary",
    "JobRun",
    "JobRunHistory",
    "JobRunStep",
    "JobRunsClient",
    "JobSchedule",
    "JobScheduleConfig",
    "JobScheduleState",
    "JobState",
    "JobTraceResponse",
    "JobsClient",
    "MetricRecord",
    "MigrationsClient",
    "MisfirePolicy",
    "NarrativizationClient",
    "NarrativizationConfig",
    "OperationsClient",
    "OverlapPolicy",
    "QueryResult",
    "ScheduleDispatch",
    "ScheduleFilter",
    "SchedulerOverview",
    "SchedulesClient",
    "SettingsClient",
    "SidecarPage",
    "SidecarStoreInfo",
    "SidecarsClient",
    "SpanRecord",
    "SystemSettings",
    "SystemStatus",
    "ThroughputMetricsResponse",
    "TransportSettings",
    "UpcomingExecution",
    "VersionInfo",
    "VespaClient",
]
