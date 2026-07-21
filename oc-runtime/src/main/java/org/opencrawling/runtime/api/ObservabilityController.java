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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for AI-Powered Observability (AIOps), OpenTelemetry trace queries,
 * error log analysis, and performance metrics.
 */
@RestController
@RequestMapping("/api/observability")
@CrossOrigin(origins = "*")
public class ObservabilityController {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityController.class);
    private final AIOpsDiagnosticService diagnosticService;
    private final ObservabilityMcpTools mcpTools;
    private final TelemetryTraceStore traceStore;
    private final JobController jobController;

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

    @GetMapping("/diagnose/{jobId}")
    public ResponseEntity<AIOpsDiagnosticService.DiagnosticReport> diagnoseJob(@PathVariable String jobId) {
        log.info("Request received to run AI Diagnosis for Job ID: {}", jobId);
        
        JobController.JobDTO job = jobController.getAllJobs().stream()
                .filter(j -> j.id().equals(jobId))
                .findFirst()
                .orElse(new JobController.JobDTO(jobId, "Job #" + jobId, "FileSystem", "PGVector", "", "/data", "Finished", "Completed", 120, "N/A", "Ollama_Embedding_Default"));

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

    @GetMapping("/traces/{jobId}")
    public ResponseEntity<ObservabilityMcpTools.JobTraceResponse> getJobTraces(@PathVariable String jobId) {
        return ResponseEntity.ok(mcpTools.fetchJobTraces(jobId));
    }

    @GetMapping("/errors/{jobId}")
    public ResponseEntity<ObservabilityMcpTools.ErrorLogsResponse> getErrorLogs(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "all") String timeframe) {
        return ResponseEntity.ok(mcpTools.getErrorLogs(jobId, timeframe));
    }

    @GetMapping("/metrics")
    public ResponseEntity<ObservabilityMcpTools.ThroughputMetricsResponse> getThroughputMetrics(
            @RequestParam(required = false) String connectorId) {
        return ResponseEntity.ok(mcpTools.queryThroughputMetrics(connectorId));
    }
}
