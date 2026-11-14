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

    @Configuration
    @ImportAutoConfiguration({
            org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration.class,
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
            PlatformV1Controller.class, JobsV1Controller.class, JobRunsV1Controller.class, ExecutionsV1Controller.class,
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
}
