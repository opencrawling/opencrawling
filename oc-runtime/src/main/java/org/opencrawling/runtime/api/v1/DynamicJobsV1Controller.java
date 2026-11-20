/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.opencrawling.runtime.dynamicjob.DynamicJobController;
import org.opencrawling.runtime.dynamicjob.DynamicJobProperties;
import org.opencrawling.runtime.dynamicjob.DynamicJobService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dynamic jobs: ingest a folder or one document of a registered connector without an admin creating a job, for a
 * verified caller (typically an agent). Enabled with {@code opencrawling.jobs.dynamic.enabled}.
 */
@RestController
@RequestMapping(ApiV1.BASE)
@ConditionalOnProperty(name = DynamicJobProperties.PREFIX + ".enabled", havingValue = "true")
@Tag(name = "Dynamic jobs", description = "On-demand ingestion of a folder or document by a verified caller")
public class DynamicJobsV1Controller {

    private final DynamicJobController dynamicJobs;

    public DynamicJobsV1Controller(DynamicJobController dynamicJobs) {
        this.dynamicJobs = dynamicJobs;
    }

    @Operation(operationId = "startDynamicJob", summary = "Start a dynamic job",
            description = "Ingests a folder of a registered connector, within the connector's scope and the caller's read access. "
                    + "validateOnly=true checks the request without starting. Send an Idempotency-Key so that a retry returns the same job.")
    @ApiResponse(responseCode = "202", description = "Started", headers = @Header(name = "Location", description = "URL of the dynamic job"))
    @ApiResponse(responseCode = "200", description = "validateOnly: the validation result")
    @PostMapping("/dynamic-jobs")
    public ResponseEntity<?> start(@RequestBody(required = false) DynamicJobController.DynamicJobBody body,
                                   @RequestHeader(value = ApiV1.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                   @RequestHeader(value = ApiV1.CORRELATION_ID, required = false) String correlationId) {
        return relocate(dynamicJobs.start(body, idempotencyKey, correlationId));
    }

    @Operation(operationId = "ingestDynamicDocument", summary = "Ingest or delete one document",
            description = "Upserts or deletes a single document of a registered connector for the caller.")
    @ApiResponse(responseCode = "202", description = "Accepted")
    @PostMapping("/dynamic-jobs:ingest-document")
    public ResponseEntity<?> document(@RequestBody(required = false) DynamicJobController.DocumentBody body,
                                      @RequestHeader(value = ApiV1.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                      @RequestHeader(value = ApiV1.CORRELATION_ID, required = false) String correlationId) {
        return relocate(dynamicJobs.document(body, idempotencyKey, correlationId));
    }

    @Operation(operationId = "listDynamicJobs", summary = "List dynamic jobs", description = "The caller's dynamic jobs (all of them for admins).")
    @ApiResponse(responseCode = "200", description = "A page of dynamic jobs")
    @GetMapping("/dynamic-jobs")
    public Page<DynamicJobService.JobStatus> list(
            @Parameter(description = "Page size, 1 to 500 (default 50)") @RequestParam(required = false) Integer limit,
            @Parameter(description = "Cursor from page.nextCursor") @RequestParam(required = false) String cursor,
            HttpServletResponse response) {
        // Newest first, the id deciding between jobs created at the same instant: one order across requests.
        List<DynamicJobService.JobStatus> jobs = dynamicJobs.list().stream()
                .sorted(java.util.Comparator.comparing(DynamicJobService.JobStatus::createdAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.<java.time.Instant>reverseOrder()))
                        .thenComparing(DynamicJobService.JobStatus::jobId))
                .toList();
        return Paging.page(jobs, limit, cursor, "", response, DynamicJobService.JobStatus::jobId);
    }

    @Operation(operationId = "getDynamicJob", summary = "Get a dynamic job",
            description = "The dynamic job's state, what it crawls, who started it and, once it has ended, how many documents it processed.")
    @ApiResponse(responseCode = "200", description = "The dynamic job's status")
    @GetMapping("/dynamic-jobs/{jobId}")
    public DynamicJobService.JobStatus get(@PathVariable String jobId) {
        return dynamicJobs.status(jobId);
    }

    @Operation(operationId = "stopDynamicJob", summary = "Stop a dynamic job",
            description = "Asks a running dynamic job to stop before its next document. Its owner or an admin may stop it.")
    @ApiResponse(responseCode = "202", description = "Stop requested")
    @PostMapping("/dynamic-jobs/{jobId}:stop")
    public ResponseEntity<DynamicJobService.JobStatus> stop(@PathVariable String jobId) {
        return dynamicJobs.stop(jobId);
    }

    @Operation(operationId = "approveDynamicJob", summary = "Approve a dynamic job",
            description = "For jobs waiting for approval (opencrawling.jobs.dynamic.approval). Admins only.")
    @ApiResponse(responseCode = "200", description = "Approved; the job starts")
    @PostMapping("/dynamic-jobs/{jobId}:approve")
    public DynamicJobService.JobStatus approve(@PathVariable String jobId) {
        return dynamicJobs.approve(jobId);
    }

    @Operation(operationId = "rejectDynamicJob", summary = "Reject a dynamic job",
            description = "Rejects a dynamic job waiting for approval, with an optional reason recorded on the job. Admins only.")
    @ApiResponse(responseCode = "200", description = "Rejected")
    @PostMapping("/dynamic-jobs/{jobId}:reject")
    public DynamicJobService.JobStatus reject(@PathVariable String jobId,
                                              @RequestBody(required = false) DynamicJobController.RejectBody body) {
        return dynamicJobs.reject(jobId, body);
    }

    /** The legacy answer, with its Location and status URL pointing at the v1 resource. */
    private static ResponseEntity<?> relocate(ResponseEntity<?> result) {
        if (result.getBody() instanceof DynamicJobService.Accepted accepted && accepted.jobId() != null) {
            java.net.URI location = ApiV1.location("dynamic-jobs", accepted.jobId());
            return ResponseEntity.status(result.getStatusCode())
                    .location(location)
                    .body(new DynamicJobService.Accepted(accepted.jobId(), accepted.executionId(),
                            location.toString(), accepted.state(), accepted.replayed()));
        }
        return result;
    }
}
