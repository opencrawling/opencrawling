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
import jakarta.servlet.http.HttpServletResponse;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.v1.model.JobRunHistoryResource;
import org.opencrawling.runtime.api.v1.model.JobRunResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Job runs in progress, in this runtime or another that shares the control plane (#315). When a run ends, it becomes an
 * execution with a report; its history stays at {@code /job-runs/{executionId}/history} for the run retention.
 */
@RestController
@RequestMapping(ApiV1.BASE + "/job-runs")
@Tag(name = "Job runs", description = "Runs in progress; finished runs are executions")
public class JobRunsV1Controller {

    private final JobController jobs;

    public JobRunsV1Controller(JobController jobs) {
        this.jobs = jobs;
    }

    @Operation(operationId = "listJobRuns", summary = "List runs in progress",
            description = "The runs in progress that the caller may see, oldest first.")
    @ApiResponse(responseCode = "200", description = "A page of runs")
    @GetMapping
    public Page<JobRunResource> list(
            @Parameter(description = "Only runs of this job") @RequestParam(required = false) String jobId,
            @Parameter(description = "Page size, 1 to 500 (default 50)") @RequestParam(required = false) Integer limit,
            @Parameter(description = "Cursor from page.nextCursor") @RequestParam(required = false) String cursor,
            HttpServletResponse response) {
        List<JobRunResource> runs = jobs.getRuns().stream()
                .filter(run -> jobId == null || jobId.equals(run.jobId()))
                .map(JobRunResource::of)
                .sorted(Comparator.comparing(JobRunResource::startedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(JobRunResource::executionId))
                .toList();
        return Paging.page(runs, limit, cursor, Objects.toString(jobId, ""), response, JobRunResource::executionId);
    }

    @Operation(operationId = "getJobRun", summary = "Get a run in progress",
            description = "404 when no such run is in progress; a finished run's report is at /api/v1/executions/{executionId}.")
    @ApiResponse(responseCode = "200", description = "The run")
    @GetMapping("/{executionId}")
    public JobRunResource get(@PathVariable String executionId) {
        return find(executionId);
    }

    @Operation(operationId = "stopJobRun", summary = "Stop a run in progress",
            description = "Asks this run to stop before its next document. Only this run: if it has ended, the answer is 404, and a newer run of the same job is not stopped.")
    @ApiResponse(responseCode = "202", description = "Stop requested")
    @PostMapping("/{executionId}:stop")
    public ResponseEntity<JobRunResource> stop(@PathVariable String executionId) {
        JobRunResource run = find(executionId);
        // Stops exactly this run: if it ended and a new run of the job started meanwhile, the new run keeps going.
        JobsV1Controller.requireOk(jobs.stopRun(run.jobId(), run.executionId()), "Run", executionId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(run.stopping());
    }

    @Operation(operationId = "pauseJobRun", summary = "Pause a run in progress",
            description = "The run holds before its next document until :resume or :stop, in whichever runtime it runs (another runtime "
                    + "notices within its heartbeat interval). Only this run: if it has ended, the answer is 404.")
    @ApiResponse(responseCode = "202", description = "Pause requested")
    @PostMapping("/{executionId}:pause")
    public ResponseEntity<JobRunResource> pause(@PathVariable String executionId) {
        JobRunResource run = find(executionId);
        JobsV1Controller.requireOk(jobs.pauseRun(run.jobId(), run.executionId()), "Run", executionId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(find(executionId));
    }

    @Operation(operationId = "resumeJobRun", summary = "Resume a paused run",
            description = "The run goes on from the document where it held; a running run keeps running. 404 if it has ended.")
    @ApiResponse(responseCode = "202", description = "Resume requested")
    @PostMapping("/{executionId}:resume")
    public ResponseEntity<JobRunResource> resume(@PathVariable String executionId) {
        JobRunResource run = find(executionId);
        JobsV1Controller.requireOk(jobs.resumeRun(run.jobId(), run.executionId()), "Run", executionId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(find(executionId));
    }

    @Operation(operationId = "getJobRunHistory", summary = "Get a run's history",
            description = "A run in progress or ended, with every change of its state (started, paused, resumed, stop requested, "
                    + "and how it ended), readable after a restart with the jdbc control-plane store. Kept for "
                    + "opencrawling.control-plane.run-retention (90 days by default).")
    @ApiResponse(responseCode = "200", description = "The run and its history")
    @GetMapping("/{executionId}/history")
    public JobRunHistoryResource history(@PathVariable String executionId) {
        return jobs.runHistory(executionId).map(JobRunHistoryResource::of)
                .orElseThrow(() -> ApiProblem.notFound("Run", executionId));
    }

    private JobRunResource find(String executionId) {
        return jobs.getRuns().stream()
                .filter(run -> executionId.equals(run.executionId()))
                .map(JobRunResource::of)
                .findFirst()
                .orElseThrow(() -> ApiProblem.notFound("Run in progress", executionId)
                        .with("execution", ApiV1.BASE + "/executions/" + executionId));
    }
}
