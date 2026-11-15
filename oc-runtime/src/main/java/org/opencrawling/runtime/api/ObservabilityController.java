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
package org.opencrawling.runtime.api;

import org.opencrawling.runtime.observability.AIOpsDiagnosticService;
import org.opencrawling.runtime.observability.ObservabilityMcpTools;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST Controller for AI-Powered Observability (AIOps), OpenTelemetry trace queries,
 * error log analysis, and performance metrics.
 */
@Tag(name = "Observability & AIOps", description = "AIOps root-cause diagnostics, OpenTelemetry distributed traces, error logs, and metrics")
@RestController
@RequestMapping("/api/observability")
@CrossOrigin(origins = "*")
public class ObservabilityController {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityController.class);
    private final AIOpsDiagnosticService diagnosticService;
    private final ObservabilityMcpTools mcpTools;
    private final TelemetryTraceStore traceStore;
    private final JobController jobController;

    /** Absent when a test builds the controller itself; then every job can be diagnosed, as with authorization off. */
    @Autowired(required = false)
    private JobVisibility visibility;

    public ObservabilityController(
            AIOpsDiagnosticService diagnosticService,
            ObservabilityMcpTools mcpTools,
            TelemetryTraceStore traceStore,
            JobController jobController) {
        this.diagnosticService = diagnosticService;
        this.mcpTools = mcpTools;
        this.traceStore = traceStore;
        this.jobController = jobController;
    }

    void setVisibility(JobVisibility visibility) {
        this.visibility = visibility;
    }

    @Operation(summary = "Diagnose job with AIOps", description = "Triggers an AI-powered diagnostic analysis on a crawling pipeline job, evaluating traces and logs to provide automated root-cause recommendations.")
    @ApiResponse(responseCode = "200", description = "Diagnostic report successfully generated")
    @GetMapping("/diagnose/{jobId}")
    public ResponseEntity<AIOpsDiagnosticService.DiagnosticReport> diagnoseJob(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String jobId) {
        log.info("Request received to run AI Diagnosis for Job ID: {}", jobId);
        if (visibility != null && !visibility.canReadJob(jobId)) {
            return ResponseEntity.notFound().build();
        }
        
        JobController.JobDTO job = jobController.getAllJobs().stream()
                .filter(j -> j.id().equals(jobId))
                .findFirst()
                .orElse(new JobController.JobDTO(jobId, "Job #" + jobId, "FileSystem", "PGVector", "", "/data", "Finished", "Completed", 120, "N/A", "Ollama_Embedding_Default", null));

        AIOpsDiagnosticService.DiagnosticReport report = diagnosticService.diagnoseJob(
                job.id(),
                job.name(),
                job.status(),
                job.currentStage(),
                job.repositoryConnector(),
                job.outputConnector(),
                job.transformationConnector(),
                job.path(),
                job.documents()
        );

        return ResponseEntity.ok(report);
    }

    @Operation(summary = "Get OpenTelemetry job traces", description = "Retrieves distributed OpenTelemetry spans and trace timelines recorded during the job execution.")
    @ApiResponse(responseCode = "200", description = "OpenTelemetry trace spans for the job")
    @GetMapping("/traces/{jobId}")
    public ResponseEntity<ObservabilityMcpTools.JobTraceResponse> getJobTraces(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String jobId) {
        return ResponseEntity.ok(mcpTools.fetchJobTraces(jobId));
    }

    @Operation(summary = "Get job error logs", description = "Retrieves structured error logs and stack traces recorded during job execution, optionally filtered by timeframe.")
    @ApiResponse(responseCode = "200", description = "Structured error log records")
    @GetMapping("/errors/{jobId}")
    public ResponseEntity<ObservabilityMcpTools.ErrorLogsResponse> getErrorLogs(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String jobId,
            @Parameter(description = "Timeframe filter for error logs (e.g. 'all', '1h', '24h')", example = "all") @RequestParam(defaultValue = "all") String timeframe) {
        return ResponseEntity.ok(mcpTools.getErrorLogs(jobId, timeframe));
    }

    @Operation(summary = "Get throughput and performance metrics", description = "Queries real-time ingestion rate, document processing latency, and connector throughput statistics.")
    @ApiResponse(responseCode = "200", description = "Throughput performance metrics")
    @GetMapping("/metrics")
    public ResponseEntity<ObservabilityMcpTools.ThroughputMetricsResponse> getThroughputMetrics(
            @Parameter(description = "Optional connector ID or name to filter throughput metrics", example = "FileSystem_Local") @RequestParam(required = false) String connectorId) {
        return ResponseEntity.ok(mcpTools.queryThroughputMetrics(connectorId));
    }
}
