/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.runtime.api.v1;

import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.reporting.JobReportSummary;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.runtime.api.ConnectorController;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.JobVisibility;
import org.opencrawling.runtime.api.McfMigrationController;
import org.opencrawling.runtime.api.ObservabilityController;
import org.opencrawling.runtime.api.ReportController;
import org.opencrawling.runtime.api.SystemController;
import org.opencrawling.runtime.api.TransportSettingsController;
import org.opencrawling.runtime.api.VespaInsightsController;
import org.opencrawling.runtime.api.copilot.NarrativizationCopilotController;
import org.opencrawling.runtime.api.copilot.TemplateGenerationCopilot;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.observability.AIOpsDiagnosticService;
import org.opencrawling.runtime.observability.ObservabilityMcpTools;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.reporting.JobReportService;
import org.opencrawling.runtime.security.AuthController;
import org.opencrawling.runtime.security.ManagementAuthorization;
import org.opencrawling.runtime.security.ManagementAuthorizationWebConfig;
import org.opencrawling.runtime.service.ConnectorCheckerService;
import org.opencrawling.runtime.service.VespaInsightsService;
import org.opencrawling.runtime.transport.InternalTransportManager;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Platform API with the real legacy controllers behind it, for HTTP tests: the infrastructure the controllers
 * reach (orchestrator, report store, transport, Vespa, copilot) is mocked.
 */
final class PlatformApiTestSupport {

    private PlatformApiTestSupport() {}

    static final String EXECUTION_ID = "exec-v1test01";

    /**
     * {@code @TestComponent} keeps this configuration out of component scanning: the runtime's
     * {@code @SpringBootApplication} scans {@code org.opencrawling}, and this class is not a test class (which Spring
     * Boot's test filter skips), so every {@code @SpringBootTest} of the full application (the JUnit ITs) would
     * otherwise also get its mocked beans, such as a second {@code AIOpsDiagnosticService}, and fail to start. Tests
     * that want it name it in {@code @SpringBootTest(classes = ...)}.
     */
    @TestComponent
    @Configuration
    @ImportAutoConfiguration({
            org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration.class,
            org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration.class,
            org.springdoc.core.configuration.SpringDocConfiguration.class,
            org.springdoc.core.properties.SpringDocConfigProperties.class,
            org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
            org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration.class
    })
    @Import({JobController.class, ConnectorController.class, ManagementAuthorization.class, JobVisibility.class,
            ManagementAuthorizationWebConfig.class, AuthController.class, SystemController.class, ReportController.class,
            ObservabilityController.class, TransportSettingsController.class, McfMigrationController.class,
            VespaInsightsController.class, NarrativizationCopilotController.class,
            ApiV1Configuration.class, ApiV1OpenApiConfiguration.class,
            org.opencrawling.runtime.controlplane.ControlPlaneConfiguration.class,
            org.opencrawling.runtime.scheduler.JobSchedulerEngine.class,
            PlatformV1Controller.class, JobsV1Controller.class, SchedulesV1Controller.class, JobRunsV1Controller.class, RunsV1Controller.class, ExecutionsV1Controller.class,
            ConnectorsV1Controller.class, SettingsV1Controller.class, OperationsV1Controller.class, ToolsV1Controller.class})
    static class PlatformApi {

        @Bean
        JobOrchestrator jobOrchestrator() {
            return mock(JobOrchestrator.class);
        }

        @Bean
        ConnectorFactory connectorFactory() {
            ConnectorFactory factory = mock(ConnectorFactory.class);
            when(factory.repository(any())).thenReturn(mock(RepositoryConnector.class));
            when(factory.output(any(), any())).thenReturn(mock(OutputConnector.class));
            return factory;
        }

        @Bean
        JobRunRegistry jobRunRegistry() {
            return new JobRunRegistry(0);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        ConnectorCheckerService connectorCheckerService() {
            ConnectorCheckerService checker = mock(ConnectorCheckerService.class);
            when(checker.check(any())).thenReturn(new ConnectorCheckerService.ConnectionCheckResult(true, "Reachable", null));
            return checker;
        }

        @Bean
        JobReportService jobReportService() {
            JobReportService reports = mock(JobReportService.class);
            JobReportSummary summary = summary("job-a", EXECUTION_ID, "SUCCESS", Instant.parse("2026-11-01T10:00:00Z"));
            JobReportSummary older = summary("job-b", "exec-v1test00", "FAILED", Instant.parse("2026-10-01T10:00:00Z"));
            when(reports.getAllSummaries()).thenReturn(List.of(older, summary));
            when(reports.getSummary(EXECUTION_ID)).thenReturn(Optional.of(summary));
            when(reports.getSummary("exec-v1test00")).thenReturn(Optional.of(older));
            when(reports.getDocumentEntries(eq(EXECUTION_ID), anyString(), any(), anyInt(), anyInt()))
                    .thenAnswer(invocation -> new JobReportService.PaginatedLedgerResponse(List.of(), 120,
                            invocation.getArgument(3), invocation.getArgument(4)));
            when(reports.getExportFile(anyString(), anyString())).thenReturn(Optional.empty());
            return reports;
        }

        @Bean
        AIOpsDiagnosticService aiOpsDiagnosticService() {
            return mock(AIOpsDiagnosticService.class);
        }

        @Bean
        ObservabilityMcpTools observabilityMcpTools() {
            return mock(ObservabilityMcpTools.class);
        }

        @Bean
        TelemetryTraceStore telemetryTraceStore() {
            return mock(TelemetryTraceStore.class);
        }

        @Bean
        InternalTransportManager internalTransportManager() {
            return mock(InternalTransportManager.class);
        }

        @Bean
        VespaInsightsService vespaInsightsService() {
            return mock(VespaInsightsService.class);
        }

        @Bean
        TemplateGenerationCopilot templateGenerationCopilot() {
            return mock(TemplateGenerationCopilot.class);
        }
    }

    static JobReportSummary summary(String jobId, String executionId, String status, Instant start) {
        return new JobReportSummary(jobId, executionId, "Job " + jobId, PipelineMode.RAG, "FileSystem_Local",
                "PGVector_Output", status, start, start.plusSeconds(60), 60_000, 10, 9, 1, 0, 20L, 2000L, 0.0,
                "mxbai-embed-large", null, null, null, null, null, null, null, null);
    }

    /** A minimal HTTP client for the tests. */
    record Http(int port) {
        HttpResponse<String> send(String method, String path, String json, String... headers) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
            boolean typed = false;
            for (int i = 0; i + 1 < headers.length; i += 2) {
                typed |= headers[i].equalsIgnoreCase("Content-Type");
            }
            if (json != null && !typed) {
                request.header("Content-Type", "application/json");
            }
            for (int i = 0; i + 1 < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            try {
                return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** The Platform API's contract: every operation id with its method and path. */
    static final java.util.Map<String, String> CORE_OPERATIONS = java.util.Map.ofEntries(
            java.util.Map.entry("getApiIndex", "GET /api/v1"),
            java.util.Map.entry("getVersion", "GET /api/v1/version"),
            java.util.Map.entry("getHealth", "GET /api/v1/health"),
            java.util.Map.entry("getCurrentCaller", "GET /api/v1/me"),
            java.util.Map.entry("listJobs", "GET /api/v1/jobs"),
            java.util.Map.entry("createJob", "POST /api/v1/jobs"),
            java.util.Map.entry("getJob", "GET /api/v1/jobs/{id}"),
            java.util.Map.entry("replaceJob", "PUT /api/v1/jobs/{id}"),
            java.util.Map.entry("patchJob", "PATCH /api/v1/jobs/{id}"),
            java.util.Map.entry("deleteJob", "DELETE /api/v1/jobs/{id}"),
            java.util.Map.entry("startJob", "POST /api/v1/jobs/{id}:start"),
            java.util.Map.entry("stopJob", "POST /api/v1/jobs/{id}:stop"),
            java.util.Map.entry("pauseJob", "POST /api/v1/jobs/{id}:pause"),
            java.util.Map.entry("resumeJob", "POST /api/v1/jobs/{id}:resume"),
            java.util.Map.entry("listJobExecutions", "GET /api/v1/jobs/{jobId}/executions"),
            java.util.Map.entry("diagnoseJob", "GET /api/v1/jobs/{jobId}/diagnosis"),
            java.util.Map.entry("getJobTraces", "GET /api/v1/jobs/{jobId}/traces"),
            java.util.Map.entry("getJobErrors", "GET /api/v1/jobs/{jobId}/errors"),
            java.util.Map.entry("getJobSchedule", "GET /api/v1/jobs/{id}/schedule"),
            java.util.Map.entry("setJobSchedule", "PUT /api/v1/jobs/{id}/schedule"),
            java.util.Map.entry("deleteJobSchedule", "DELETE /api/v1/jobs/{id}/schedule"),
            java.util.Map.entry("pauseJobSchedule", "POST /api/v1/jobs/{id}/schedule:pause"),
            java.util.Map.entry("resumeJobSchedule", "POST /api/v1/jobs/{id}/schedule:resume"),
            java.util.Map.entry("listJobScheduleDispatches", "GET /api/v1/jobs/{id}/schedule/dispatches"),
            java.util.Map.entry("listSchedules", "GET /api/v1/schedules"),
            java.util.Map.entry("getSchedulerOverview", "GET /api/v1/schedules/overview"),
            java.util.Map.entry("validateCron", "POST /api/v1/schedules:validate-cron"),
            java.util.Map.entry("listJobRuns", "GET /api/v1/job-runs"),
            java.util.Map.entry("getJobRun", "GET /api/v1/job-runs/{executionId}"),
            java.util.Map.entry("stopJobRun", "POST /api/v1/job-runs/{executionId}:stop"),
            java.util.Map.entry("pauseJobRun", "POST /api/v1/job-runs/{executionId}:pause"),
            java.util.Map.entry("resumeJobRun", "POST /api/v1/job-runs/{executionId}:resume"),
            java.util.Map.entry("getJobRunHistory", "GET /api/v1/job-runs/{executionId}/history"),
            java.util.Map.entry("listRuns", "GET /api/v1/runs"),
            java.util.Map.entry("getRun", "GET /api/v1/runs/{runId}"),
            java.util.Map.entry("getRunHistory", "GET /api/v1/runs/{runId}/history"),
            java.util.Map.entry("stopRun", "POST /api/v1/runs/{runId}:stop"),
            java.util.Map.entry("pauseRun", "POST /api/v1/runs/{runId}:pause"),
            java.util.Map.entry("resumeRun", "POST /api/v1/runs/{runId}:resume"),
            java.util.Map.entry("listExecutions", "GET /api/v1/executions"),
            java.util.Map.entry("getExecution", "GET /api/v1/executions/{executionId}"),
            java.util.Map.entry("listExecutionDocuments", "GET /api/v1/executions/{executionId}/documents"),
            java.util.Map.entry("exportExecution", "GET /api/v1/executions/{executionId}/export"),
            java.util.Map.entry("queryExecutions", "POST /api/v1/executions:query"),
            java.util.Map.entry("listConnectors", "GET /api/v1/connectors"),
            java.util.Map.entry("createConnector", "POST /api/v1/connectors"),
            java.util.Map.entry("getConnector", "GET /api/v1/connectors/{name}"),
            java.util.Map.entry("replaceConnector", "PUT /api/v1/connectors/{name}"),
            java.util.Map.entry("deleteConnector", "DELETE /api/v1/connectors/{name}"),
            java.util.Map.entry("checkConnector", "POST /api/v1/connectors/{name}:check"),
            java.util.Map.entry("checkConnectorDefinition", "POST /api/v1/connectors:check"),
            java.util.Map.entry("getSystemSettings", "GET /api/v1/settings/system"),
            java.util.Map.entry("replaceSystemSettings", "PUT /api/v1/settings/system"),
            java.util.Map.entry("getTransportSettings", "GET /api/v1/settings/transport"),
            java.util.Map.entry("replaceTransportSettings", "PUT /api/v1/settings/transport"),
            java.util.Map.entry("testGrpcTransport", "POST /api/v1/settings/transport:test-grpc"),
            java.util.Map.entry("getSystemStatus", "GET /api/v1/system/status"),
            java.util.Map.entry("getSystemThroughput", "GET /api/v1/system/throughput"),
            java.util.Map.entry("getSystemLogs", "GET /api/v1/system/logs"),
            java.util.Map.entry("getMetrics", "GET /api/v1/observability/metrics"),
            java.util.Map.entry("planManifoldCfMigration", "POST /api/v1/migrations/manifoldcf:plan"),
            java.util.Map.entry("applyManifoldCfMigration", "POST /api/v1/migrations/manifoldcf:apply"),
            java.util.Map.entry("getVespaHealth", "GET /api/v1/integrations/vespa/health"),
            java.util.Map.entry("getVespaDocumentCounts", "GET /api/v1/integrations/vespa/document-counts"),
            java.util.Map.entry("queryVespa", "POST /api/v1/integrations/vespa:query"),
            java.util.Map.entry("deployVespaBundledSchema", "POST /api/v1/integrations/vespa:deploy-bundled"),
            java.util.Map.entry("deployVespaCustomSchema", "POST /api/v1/integrations/vespa:deploy-custom"),
            java.util.Map.entry("generateNarrativizationTemplate", "POST /api/v1/narrativization:generate-template"));

    /** The optional features' operations, present when each feature is on. */
    static final java.util.Map<String, String> FEATURE_OPERATIONS = java.util.Map.ofEntries(
            java.util.Map.entry("startDynamicJob", "POST /api/v1/dynamic-jobs"),
            java.util.Map.entry("ingestDynamicDocument", "POST /api/v1/dynamic-jobs:ingest-document"),
            java.util.Map.entry("listDynamicJobs", "GET /api/v1/dynamic-jobs"),
            java.util.Map.entry("getDynamicJob", "GET /api/v1/dynamic-jobs/{jobId}"),
            java.util.Map.entry("stopDynamicJob", "POST /api/v1/dynamic-jobs/{jobId}:stop"),
            java.util.Map.entry("approveDynamicJob", "POST /api/v1/dynamic-jobs/{jobId}:approve"),
            java.util.Map.entry("rejectDynamicJob", "POST /api/v1/dynamic-jobs/{jobId}:reject"),
            java.util.Map.entry("listDiscoveryConnectors", "GET /api/v1/discovery/connectors"),
            java.util.Map.entry("getDiscoveryTree", "GET /api/v1/discovery/connectors/{connector}/tree"),
            java.util.Map.entry("dryRunDiscovery", "POST /api/v1/discovery/connectors/{connector}:dry-run"),
            java.util.Map.entry("listSidecarStores", "GET /api/v1/sidecar-stores"),
            java.util.Map.entry("listSidecars", "GET /api/v1/sidecar-stores/{connector}/sidecars"));

    /** The operations an OpenAPI document describes, as operation id to "METHOD path". */
    static java.util.Map<String, String> operations(tools.jackson.databind.JsonNode doc) {
        java.util.Map<String, String> operations = new java.util.TreeMap<>();
        doc.get("paths").properties().forEach(path -> path.getValue().properties().forEach(op ->
                operations.put(op.getValue().get("operationId").asString(), op.getKey().toUpperCase() + " " + path.getKey())));
        return operations;
    }
}
