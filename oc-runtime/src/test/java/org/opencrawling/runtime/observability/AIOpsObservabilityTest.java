/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.runtime.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AIOpsObservabilityTest {

    private TelemetryTraceStore traceStore;
    private ObservabilityMcpTools mcpTools;
    private AIOpsDiagnosticService diagnosticService;

    @BeforeEach
    void setUp() {
        traceStore = new TelemetryTraceStore();
        mcpTools = new ObservabilityMcpTools(traceStore);
        diagnosticService = new AIOpsDiagnosticService(mcpTools);
    }

    @Test
    @DisplayName("TelemetryTraceStore should record and retrieve correlated spans and error logs")
    void testTelemetryTraceStore() {
        TelemetryTraceStore.SpanRecord span = new TelemetryTraceStore.SpanRecord(
                "span-100",
                "trace-100",
                "job-test-1",
                "Embedding",
                "EmbeddingConsumer",
                System.currentTimeMillis() - 500,
                250,
                "SUCCESS",
                null,
                Map.of("engine", "ollama")
        );

        traceStore.recordSpan(span);
        traceStore.recordError("job-test-1", "ERROR", "EmbeddingConsumer", "Vector DB timeout", "Stack trace details");

        assertEquals(1, traceStore.getSpansForJob("job-test-1").size());
        assertEquals(1, traceStore.getErrorsForJob("job-test-1").size());
        assertEquals("Vector DB timeout", traceStore.getErrorsForJob("job-test-1").get(0).message());
    }

    @Test
    @DisplayName("ObservabilityMcpTools should expose OTel job traces, error logs, and metrics")
    void testObservabilityMcpTools() {
        ObservabilityMcpTools.JobTraceResponse traces = mcpTools.fetchJobTraces("job-test-2");
        assertNotNull(traces);
        assertEquals("job-test-2", traces.jobId());
        assertTrue(traces.totalSpans() > 0);

        ObservabilityMcpTools.ErrorLogsResponse errors = mcpTools.getErrorLogs("job-test-2", "all");
        assertNotNull(errors);

        ObservabilityMcpTools.ThroughputMetricsResponse metrics = mcpTools.queryThroughputMetrics("Alfresco_Connector");
        assertNotNull(metrics);
        assertTrue(metrics.averageThroughputDocsPerSec() > 0);
    }

    @Test
    @DisplayName("AIOpsDiagnosticService should generate Root Cause Analysis (RCA) report for failed job")
    void testAIOpsDiagnosticServiceFailedJob() {
        AIOpsDiagnosticService.DiagnosticReport report = diagnosticService.diagnoseJob(
                "job-test-failed",
                "Alfresco_Failed_Job",
                "Error",
                "Failed"
        );

        assertNotNull(report);
        assertEquals("FAILED", report.status());
        assertNotNull(report.rootCauseAnalysis());
        assertTrue(report.rootCauseAnalysis().contains("Root Cause Analysis"));
        assertFalse(report.bottleneckInsights().isEmpty());
        assertFalse(report.recommendedActions().isEmpty());
    }

    @Test
    @DisplayName("AIOpsDiagnosticService should generate Healthy report for completed job")
    void testAIOpsDiagnosticServiceHealthyJob() {
        AIOpsDiagnosticService.DiagnosticReport report = diagnosticService.diagnoseJob(
                "job-test-healthy",
                "Wiki_Healthy_Job",
                "Finished",
                "Completed"
        );

        assertNotNull(report);
        assertEquals("HEALTHY", report.status());
        assertTrue(report.rootCauseAnalysis().contains("executed without errors"));
    }
}
