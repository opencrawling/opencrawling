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
package org.opencrawling.runtime.dynamicjob;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.security.CallerResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * REST access to dynamic jobs. The caller always comes from the request's validated token: these endpoints need
 * {@code opencrawling.security.identity.mode=jwt} with {@code protect-api}, and never accept an asserted identity.
 */
@RestController
@RequestMapping("/api/jobs/dynamic")
@ConditionalOnProperty(name = DynamicJobProperties.PREFIX + ".enabled", havingValue = "true")
@Tag(name = "Dynamic Jobs", description = "Jobs a caller describes and starts itself, on a registered connector, from a folder it may read")
public class DynamicJobController {

    private final DynamicJobService service;
    private CallerResolver callerResolver = CallerResolver.asserted();

    public DynamicJobController(DynamicJobService service) {
        this.service = service;
    }

    @Autowired(required = false)
    public void setCallerResolver(CallerResolver callerResolver) {
        if (callerResolver != null) {
            this.callerResolver = callerResolver;
        }
    }

    /**
     * A dynamic job request.
     *
     * @param validateOnly check everything and start nothing
     */
    public record DynamicJobBody(String connector, String start, String name, Boolean validateOnly) {}

    @Operation(summary = "Start a dynamic job", description = "Crawls a folder of a registered connector listed for dynamic jobs. "
            + "The folder must be in the connector's scope and readable by the caller; documents keep their source permissions.")
    @ApiResponse(responseCode = "202", description = "Started (or started earlier with the same Idempotency-Key)")
    @ApiResponse(responseCode = "200", description = "With validateOnly: the request is valid; nothing was started")
    @ApiResponse(responseCode = "400", description = "Invalid request, unknown start folder, or a connector that cannot run dynamic jobs")
    @ApiResponse(responseCode = "401", description = "No verified caller")
    @ApiResponse(responseCode = "403", description = "The token names no user or lacks the required scope")
    @ApiResponse(responseCode = "404", description = "No such connector available for dynamic jobs")
    @ApiResponse(responseCode = "409", description = "Idempotency-Key reused for a different job, or the connector is misconfigured")
    @ApiResponse(responseCode = "429", description = "Too many jobs running")
    @PostMapping
    public ResponseEntity<?> start(
            @RequestBody(required = false) DynamicJobBody body,
            @Parameter(description = "Repeating a request with the same key returns the job the first one started")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        Caller caller = caller();
        DynamicJobBody request = body != null ? body : new DynamicJobBody(null, null, null, null);
        DynamicJobService.JobRequest job = new DynamicJobService.JobRequest(request.connector(), request.start(), request.name());
        if (Boolean.TRUE.equals(request.validateOnly())) {
            return ResponseEntity.ok(service.validate(caller, job));
        }
        DynamicJobService.Accepted accepted = service.start(caller, job, idempotencyKey,
                ExecutionProvenance.SOURCE_DYNAMIC_API, correlationId);
        return ResponseEntity.accepted().location(URI.create(accepted.statusUrl())).body(accepted);
    }

    /**
     * A request about one document.
     *
     * @param document the document's repository id (as discovery returns it)
     * @param action   {@code UPSERT} (default) or {@code DELETE}
     */
    public record DocumentBody(String connector, String document, DynamicJobService.DocumentRequest.Action action) {}

    @Operation(summary = "Ingest or remove one document", description = "UPSERT fetches a document of a registered "
            + "connector listed for dynamic jobs, if it is in the connector's scope and readable by the caller, and ingests "
            + "it as a one-document job. DELETE removes a document from the index once the repository no longer has it; "
            + "its answer is the same whether or not anything was removed.")
    @ApiResponse(responseCode = "202", description = "UPSERT: a job was started (see Location). DELETE: accepted")
    @ApiResponse(responseCode = "400", description = "Invalid request, or no such document available to the caller")
    @ApiResponse(responseCode = "403", description = "The token names no user or lacks the document scope")
    @ApiResponse(responseCode = "404", description = "No such connector available for dynamic jobs")
    @ApiResponse(responseCode = "409", description = "DELETE of a document that still exists, or Idempotency-Key reused")
    @PostMapping("/documents")
    public ResponseEntity<?> document(
            @RequestBody(required = false) DocumentBody body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        DocumentBody request = body != null ? body : new DocumentBody(null, null, null);
        Object result = service.document(caller(),
                new DynamicJobService.DocumentRequest(request.connector(), request.document(), request.action()),
                idempotencyKey, ExecutionProvenance.SOURCE_DYNAMIC_API, correlationId);
        if (result instanceof DynamicJobService.Accepted accepted) {
            return ResponseEntity.accepted().location(URI.create(accepted.statusUrl())).body(accepted);
        }
        return ResponseEntity.accepted().body(result);
    }

    @Operation(summary = "List dynamic jobs", description = "The caller's own dynamic jobs (every job for admin groups), newest first.")
    @GetMapping
    public List<DynamicJobService.JobStatus> list() {
        return service.list(caller());
    }

    @Operation(summary = "Get a dynamic job's status")
    @ApiResponse(responseCode = "404", description = "No such job, or not one the caller may see")
    @GetMapping("/{jobId}")
    public DynamicJobService.JobStatus status(@PathVariable String jobId) {
        return service.status(caller(), jobId);
    }

    @Operation(summary = "Stop a dynamic job", description = "The crawl ends at the latest before its next document.")
    @ApiResponse(responseCode = "202", description = "Stop requested")
    @ApiResponse(responseCode = "409", description = "The job is not running")
    @PostMapping("/{jobId}/stop")
    public ResponseEntity<DynamicJobService.JobStatus> stop(@PathVariable String jobId) {
        return ResponseEntity.accepted().body(service.stop(caller(), jobId));
    }

    /** A rejection's reason, shown in the job's note. */
    public record RejectBody(String reason) {}

    @Operation(summary = "Approve a dynamic job", description = "For members of the admin groups, when "
            + "opencrawling.jobs.dynamic.require-approval is on. The job's folder or document is checked again under the "
            + "requester's identity and the job starts under the same id. A job cannot be approved by the user who asked for it.")
    @ApiResponse(responseCode = "200", description = "Approved: started, or FAILED with the reason in its note")
    @ApiResponse(responseCode = "403", description = "Not in the admin groups, or approving one's own job")
    @ApiResponse(responseCode = "409", description = "The job is not waiting for approval")
    @ApiResponse(responseCode = "429", description = "A limit is reached; the job stays pending")
    @PostMapping("/{jobId}/approve")
    public DynamicJobService.JobStatus approve(@PathVariable String jobId) {
        return service.approve(caller(), jobId);
    }

    @Operation(summary = "Reject a dynamic job", description = "For members of the admin groups: the job waiting for approval ends REJECTED.")
    @ApiResponse(responseCode = "200", description = "Rejected")
    @ApiResponse(responseCode = "403", description = "Not in the admin groups")
    @ApiResponse(responseCode = "409", description = "The job is not waiting for approval")
    @PostMapping("/{jobId}/reject")
    public DynamicJobService.JobStatus reject(@PathVariable String jobId, @RequestBody(required = false) RejectBody body) {
        return service.reject(caller(), jobId, body != null ? body.reason() : null);
    }

    @ExceptionHandler(DynamicJobException.class)
    ResponseEntity<Map<String, String>> failed(DynamicJobException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    /** The request's verified caller, or {@code null} (refused by the service) when there is none. */
    private Caller caller() {
        return callerResolver.verifiedCaller().orElse(null);
    }
}
