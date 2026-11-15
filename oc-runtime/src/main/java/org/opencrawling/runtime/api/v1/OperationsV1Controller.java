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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencrawling.runtime.api.ObservabilityController;
import org.opencrawling.runtime.api.SystemController;
import org.opencrawling.runtime.observability.ObservabilityMcpTools;
import org.opencrawling.runtime.observability.AIOpsDiagnosticService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Operating the runtime: status, throughput and logs, and per-job diagnosis, traces and errors. */
@RestController
@RequestMapping(ApiV1.BASE)
@Tag(name = "Operations", description = "Runtime status, throughput, logs, metrics, and per-job diagnosis, traces and errors")
public class OperationsV1Controller {

    private final SystemController system;
    private final ObservabilityController observability;
    private final JobsV1Controller jobs;

    public OperationsV1Controller(SystemController system, ObservabilityController observability, JobsV1Controller jobs) {
        this.system = system;
        this.observability = observability;
        this.jobs = jobs;
    }

    @Operation(operationId = "getSystemStatus", summary = "Status of the runtime and its dependencies",
            description = "UP or DOWN for each dependency (PostgreSQL, Redis, Ollama, ...) and the overall system state, as the admin UI's status badge shows it.")
    @ApiResponse(responseCode = "200", description = "Each dependency: UP or DOWN, and the overall system state")
    @GetMapping("/system/status")
    public Map<String, String> status() {
        return system.getSystemStatus();
    }

    @Operation(operationId = "getSystemThroughput", summary = "Recent throughput", description = "Documents processed over recent intervals.")
    @ApiResponse(responseCode = "200", description = "Throughput samples")
    @GetMapping("/system/throughput")
    public List<Map<String, Object>> throughput() {
        return system.getThroughput();
    }

    @Operation(operationId = "getSystemLogs", summary = "Recent log lines", description = "The runtime's latest log lines. Admins only.")
    @ApiResponse(responseCode = "200", description = "Log lines, oldest first")
    @GetMapping("/system/logs")
    public List<String> logs() {
        return system.getLogs();
    }

    @Operation(operationId = "getMetrics", summary = "Throughput metrics", description = "Metrics per connector. Admins only.")
    @ApiResponse(responseCode = "200", description = "The metrics")
    @GetMapping("/observability/metrics")
    public ObservabilityMcpTools.ThroughputMetricsResponse metrics(
            @Parameter(description = "Only this connector") @RequestParam(required = false) String connectorId) {
        return body(observability.getThroughputMetrics(connectorId), "Metrics", "");
    }

    @Operation(operationId = "diagnoseJob", summary = "Diagnose a job",
            description = "An AIOps root-cause analysis of the job's recent runs: findings and recommended actions.")
    @ApiResponse(responseCode = "200", description = "The diagnosis")
    @GetMapping("/jobs/{jobId}/diagnosis")
    public AIOpsDiagnosticService.DiagnosticReport diagnosis(@PathVariable String jobId) {
        jobs.find(jobId);
        return body(observability.diagnoseJob(jobId), "Job", jobId);
    }

    @Operation(operationId = "getJobTraces", summary = "A job's traces", description = "OpenTelemetry spans of the job's runs.")
    @ApiResponse(responseCode = "200", description = "The traces")
    @GetMapping("/jobs/{jobId}/traces")
    public ObservabilityMcpTools.JobTraceResponse traces(@PathVariable String jobId) {
        jobs.find(jobId);
        return body(observability.getJobTraces(jobId), "Job", jobId);
    }

    @Operation(operationId = "getJobErrors", summary = "A job's errors", description = "Error log entries of the job's runs.")
    @ApiResponse(responseCode = "200", description = "The errors")
    @GetMapping("/jobs/{jobId}/errors")
    public ObservabilityMcpTools.ErrorLogsResponse errors(
            @PathVariable String jobId,
            @Parameter(description = "all, 1h, 24h, ...") @RequestParam(defaultValue = "all") String timeframe) {
        jobs.find(jobId);
        return body(observability.getErrorLogs(jobId, timeframe), "Job", jobId);
    }

    private static <T> T body(ResponseEntity<T> result, String what, String id) {
        JobsV1Controller.requireOk(result, what, id);
        return result.getBody();
    }
}
