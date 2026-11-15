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
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.opencrawling.runtime.api.ConnectorController;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.JobController.JobDTO;
import org.opencrawling.runtime.api.JobController.SaveOutcome;
import org.opencrawling.runtime.api.JobController.StartOutcome;
import org.opencrawling.runtime.api.v1.model.JobInput;
import org.opencrawling.runtime.api.v1.model.JobResource;
import org.opencrawling.runtime.api.v1.model.JobRunResource;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.security.ManagementAuthorization.JobAction;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pipeline jobs: create, read, replace, patch, delete, and the custom methods {@code :start}, {@code :stop} and
 * {@code :pause}. Validation, authorization and audit are those of {@link JobController}, which these endpoints call.
 */
@RestController
@RequestMapping(ApiV1.BASE + "/jobs")
@Tag(name = "Jobs", description = "Pipeline jobs: definitions, state, and starting and stopping runs")
public class JobsV1Controller {

    private final JobController jobs;
    private final ConnectorController connectors;
    private final JsonMapper json;
    private final Validator validator;

    public JobsV1Controller(JobController jobs, ConnectorController connectors, JsonMapper json,
                            ObjectProvider<Validator> validator) {
        this.jobs = jobs;
        this.connectors = connectors;
        this.json = json;
        this.validator = validator.getIfAvailable(() -> Validation.buildDefaultValidatorFactory().getValidator());
    }

    @Operation(operationId = "listJobs", summary = "List jobs",
            description = "The jobs the caller may see, filtered, sorted and paged.")
    @ApiResponse(responseCode = "200", description = "A page of jobs")
    @GetMapping
    public Page<JobResource> list(
            @Parameter(description = "Only jobs with this status (Ready, Running, Paused, Finished, Error)") @RequestParam(required = false) String status,
            @Parameter(description = "Only jobs with this pipeline mode (rag, migration)") @RequestParam(required = false) String pipelineMode,
            @Parameter(description = "Only jobs using this repository connector") @RequestParam(required = false) String repositoryConnector,
            @Parameter(description = "Only jobs using this output connector") @RequestParam(required = false) String outputConnector,
            @Parameter(description = "Only jobs whose name contains this text (case-insensitive)") @RequestParam(required = false) String q,
            @Parameter(description = "Sort fields, comma-separated, '-' for descending: id, name, status, lastRun", example = "name") @RequestParam(required = false) String sort,
            @Parameter(description = "Page size, 1 to 500 (default 50)") @RequestParam(required = false) Integer limit,
            @Parameter(description = "Cursor from page.nextCursor") @RequestParam(required = false) String cursor,
            HttpServletResponse response) {
        Map<String, List<JobAction>> permissions = jobs.getJobPermissions();
        List<JobResource> items = jobs.getAllJobs().stream()
                .filter(job -> status == null || status.equalsIgnoreCase(job.status()))
                .filter(job -> pipelineMode == null || pipelineMode.equalsIgnoreCase(String.valueOf(job.pipelineMode())))
                .filter(job -> repositoryConnector == null || repositoryConnector.equalsIgnoreCase(job.repositoryConnector()))
                .filter(job -> outputConnector == null || outputConnector.equalsIgnoreCase(job.outputConnector()))
                .filter(job -> q == null || (job.name() != null && job.name().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))))
                .map(job -> JobResource.of(job, permissions.get(job.id())))
                .toList();
        items = Paging.sort(items, sort == null ? "id" : sort, Map.of(
                "id", Comparator.comparing(JobResource::id, Comparator.nullsLast(Comparator.naturalOrder())),
                "name", Paging.byText(JobResource::name),
                "status", Paging.byText(job -> job.state().status()),
                "lastRun", Paging.byText(job -> job.state().lastRun())));
        String query = String.join("|", Objects.toString(status, ""), Objects.toString(pipelineMode, ""),
                Objects.toString(repositoryConnector, ""), Objects.toString(outputConnector, ""), Objects.toString(q, ""),
                Objects.toString(sort, ""));
        return Paging.page(items, limit, cursor, query, response);
    }

    @Operation(operationId = "createJob", summary = "Create a job",
            description = "Creates a job. With management authorization on, the caller must be a job creator and becomes its owner. "
                    + "Send an Idempotency-Key to make retries safe.")
    @ApiResponse(responseCode = "201", description = "Created", headers = {
            @Header(name = "Location", description = "URL of the new job"), @Header(name = "ETag", description = "Version of the job")})
    @PostMapping
    public ResponseEntity<JobResource> create(@Valid @RequestBody JobInput input,
                                              @Parameter(description = "Makes retries of this request safe") @RequestHeader(value = ApiV1.IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        checkConnectors(input);
        SaveOutcome outcome = jobs.save(input.toJob(null));
        JobResource created = resource(saved(outcome, "new"));
        return ResponseEntity.created(ApiV1.location("jobs", created.id()))
                .eTag(etag(created))
                .body(created);
    }

    @Operation(operationId = "getJob", summary = "Get a job",
            description = "The job's definition, state and the caller's permissions. Supports If-None-Match.")
    @ApiResponse(responseCode = "200", description = "The job", headers = @Header(name = "ETag", description = "Version of the job as returned: its definition, state and the caller's permissions"))
    @ApiResponse(responseCode = "304", description = "Not modified since the ETag in If-None-Match")
    @GetMapping("/{id}")
    public ResponseEntity<JobResource> get(@PathVariable String id,
                                           @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        JobResource job = resource(find(id));
        String etag = etag(job);
        if (Etags.notModified(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(job);
    }

    @Operation(operationId = "replaceJob", summary = "Replace a job's definition",
            description = "Replaces the whole definition. Leave out access to keep the current one. Send If-Match with the ETag you read "
                    + "to avoid overwriting someone else's change (412 if it changed).")
    @ApiResponse(responseCode = "200", description = "Replaced", headers = @Header(name = "ETag", description = "New version"))
    @PutMapping("/{id}")
    public ResponseEntity<JobResource> replace(@PathVariable String id, @Valid @RequestBody JobInput input,
                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        // The check and the write happen under the same lock as every other save, so two changes made from the same
        // version cannot both pass the check (RFC 9110 13.1.1, the lost update).
        synchronized (jobs) {
            Etags.requireMatch(ifMatch, etag(resource(find(id))));
            return update(id, input);
        }
    }

    @Operation(operationId = "patchJob", summary = "Change parts of a job's definition",
            description = "A JSON Merge Patch (RFC 7396) of the definition: members sent replace, null removes optional ones. Supports If-Match.")
    @ApiResponse(responseCode = "200", description = "Changed", headers = @Header(name = "ETag", description = "New version"))
    @PatchMapping(value = "/{id}", consumes = {ApiV1.MERGE_PATCH_JSON, "application/json"})
    public ResponseEntity<JobResource> patch(@PathVariable String id,
                                             @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = ApiV1.MERGE_PATCH_JSON,
                                                     schema = @Schema(implementation = JobInput.class)))
                                             @RequestBody JsonNode patch,
                                             @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        if (patch == null || !patch.isObject()) {
            throw ApiProblem.badRequest("A merge patch is a JSON object.");
        }
        synchronized (jobs) {
            JobDTO current = find(id);
            Etags.requireMatch(ifMatch, etag(resource(current)));
            return patched(id, current, patch);
        }
    }

    private ResponseEntity<JobResource> patched(String id, JobDTO current, JsonNode patch) {
        JsonNode merged = JsonMergePatch.apply(json.valueToTree(JobInput.of(current)), patch);
        JobInput input;
        try {
            input = json.treeToValue(merged, JobInput.class);
        } catch (RuntimeException e) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request",
                    "The patched definition has a value of the wrong type.");
        }
        validate(input);
        return update(id, input);
    }

    @Operation(operationId = "deleteJob", summary = "Delete a job",
            description = "Deletes the job's definition. A run in progress is not stopped; stop it first. Supports If-Match.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        synchronized (jobs) {
            JobDTO current = find(id);
            Etags.requireMatch(ifMatch, etag(resource(current)));
            requireOk(jobs.deleteJob(current.id()), "Job", id);
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "startJob", summary = "Start a run of the job",
            description = "Starts the job asynchronously and answers at once with the run. Follow it at the Location, or the job's state. "
                    + "Send an Idempotency-Key so that a retry does not start a second run.")
    @ApiResponse(responseCode = "202", description = "Started", headers = @Header(name = "Location", description = "URL of the run"))
    @ApiResponse(responseCode = "409", description = "Already running, or its connectors or settings cannot be used",
            content = @Content(mediaType = "application/problem+json"))
    @ApiResponse(responseCode = "429", description = "A cap on runs at the same time is reached",
            content = @Content(mediaType = "application/problem+json"))
    @PostMapping("/{id}:start")
    public ResponseEntity<JobRunResource> start(@PathVariable String id,
                                                @Parameter(description = "Ties the run to the caller's own request or workflow; recorded in the run's ledger")
                                                @RequestHeader(value = ApiV1.CORRELATION_ID, required = false) String correlationId,
                                                @Parameter(description = "Makes retries of this request safe") @RequestHeader(value = ApiV1.IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        JobDTO job = find(id);
        return switch (jobs.start(job.id(), correlationId)) {
            case StartOutcome.Started started -> {
                // The run as the registry has it; a very short run may already be over, so fall back to what we know.
                JobRunRegistry.RunInfo info = jobs.getRun(started.jobId()).getBody();
                JobRunResource run = info != null && started.executionId().equals(info.executionId())
                        ? JobRunResource.of(info)
                        : new JobRunResource(started.executionId(), started.jobId(), Instant.now(), false);
                yield ResponseEntity.accepted()
                        .location(ApiV1.location("job-runs", started.executionId()))
                        .body(run);
            }
            case StartOutcome.NotFound notFound -> throw ApiProblem.notFound("Job", id);
            case StartOutcome.Forbidden forbidden -> throw ApiProblem.forbidden("You may see this job but not run it.");
            case StartOutcome.AlreadyRunning running -> throw ApiProblem.conflict("job-already-running",
                    "The job is already running.").with("executionId", running.executionId());
            case StartOutcome.Rejected rejected -> throw ApiProblem.conflict("job-not-startable",
                    "The job cannot start: " + rejected.reason());
            case StartOutcome.LimitReached limit -> throw ApiProblem.tooManyRequests(limit.perCaller()
                            ? "You already have " + limit.limit() + " runs in progress (opencrawling.jobs.max-concurrent-runs-per-caller)."
                            : limit.limit() + " runs are already in progress (opencrawling.jobs.max-concurrent-runs).")
                    .header(HttpHeaders.RETRY_AFTER, "30");
        };
    }

    @Operation(operationId = "stopJob", summary = "Stop the job's run",
            description = "Asks the run to stop before its next document (202). A job with no run here is marked Finished (200).")
    @ApiResponse(responseCode = "202", description = "Stop requested; the job's state changes when the run has ended")
    @ApiResponse(responseCode = "200", description = "The job had no run; it is marked Finished")
    @PostMapping("/{id}:stop")
    public ResponseEntity<JobResource> stop(@PathVariable String id) {
        JobDTO job = find(id);
        ResponseEntity<Void> result = jobs.stopJob(job.id());
        requireOk(result, "Job", id);
        return ResponseEntity.status(result.getStatusCode()).body(resource(find(id)));
    }

    @Operation(operationId = "pauseJob", summary = "Mark the job as paused",
            description = "Sets the job's status to Paused, as the admin UI does. It does not suspend a run in progress; use :stop for that.")
    @ApiResponse(responseCode = "200", description = "Marked as paused")
    @PostMapping("/{id}:pause")
    public ResponseEntity<JobResource> pause(@PathVariable String id) {
        JobDTO job = find(id);
        requireOk(jobs.pauseJob(job.id()), "Job", id);
        return ResponseEntity.ok(resource(find(id)));
    }

    // ---- helpers ----

    /** The job, if the caller may see it; 404 otherwise, so its existence is not revealed. */
    JobDTO find(String id) {
        JobDTO job = jobs.findJobById(id);
        if (job == null || !jobs.canRead(job)) {
            throw ApiProblem.notFound("Job", id);
        }
        return job;
    }

    private ResponseEntity<JobResource> update(String id, JobInput input) {
        checkConnectors(input);
        JobResource saved = resource(saved(jobs.save(input.toJob(id), true), id));
        return ResponseEntity.ok().eTag(etag(saved)).body(saved);
    }

    private JobDTO saved(SaveOutcome outcome, String id) {
        return switch (outcome) {
            case SaveOutcome.Saved saved -> saved.job();
            case SaveOutcome.Missing missing -> throw ApiProblem.notFound("Job", id);
            case SaveOutcome.NotFound notFound -> throw ApiProblem.notFound("Job", id);
            case SaveOutcome.Forbidden forbidden -> throw ApiProblem.forbidden("new".equals(id)
                    ? "Only admins and members of the job creator groups may create jobs."
                    : "Only the job's owner or an admin may change it.");
            case SaveOutcome.InvalidMode invalid -> throw ApiProblem.invalid("pipelineMode", invalid.reason());
            case SaveOutcome.Invalid invalid -> throw new ApiProblem(HttpStatus.BAD_REQUEST, "job-invalid", "Invalid job",
                    "The repository connector rejects this job: " + invalid.reason());
        };
    }

    /** Every connector the job names must exist and be of the right category. */
    private void checkConnectors(JobInput input) {
        List<ConnectorController.ConnectorDTO> configured = connectors.getAllConnectors();
        List<ApiProblem.FieldError> errors = new ArrayList<>();
        check(configured, "repositoryConnector", input.repositoryConnector(), Set.of("repository"), errors);
        check(configured, "outputConnector", input.outputConnector(), Set.of("output"), errors);
        check(configured, "transformationConnector", input.transformationConnector(), Set.of("transformation"), errors);
        check(configured, "authorityConnector", input.authorityConnector(), Set.of("authority", "repository"), errors);
        if (!errors.isEmpty()) {
            throw ApiProblem.invalid(errors);
        }
    }

    private static void check(List<ConnectorController.ConnectorDTO> configured, String field, String name, Set<String> types,
                              List<ApiProblem.FieldError> errors) {
        if (name == null || name.isBlank()) {
            return;
        }
        ConnectorController.ConnectorDTO connector = configured.stream()
                .filter(c -> c.name() != null && c.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        if (connector == null) {
            errors.add(new ApiProblem.FieldError(field, "no connector named '" + name + "'"));
        } else if (connector.type() == null || !types.contains(connector.type().toLowerCase(Locale.ROOT))) {
            errors.add(new ApiProblem.FieldError(field, "'" + name + "' is a " + connector.type() + " connector, not "
                    + String.join(" or ", types)));
        }
    }

    private void validate(JobInput input) {
        Set<ConstraintViolation<JobInput>> violations = validator.validate(input);
        if (!violations.isEmpty()) {
            throw ApiProblem.invalid(violations.stream()
                    .map(v -> new ApiProblem.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                    .sorted(Comparator.comparing(ApiProblem.FieldError::field))
                    .toList());
        }
    }

    private JobResource resource(JobDTO job) {
        return JobResource.of(job, jobs.getJobPermissions().get(job.id()));
    }

    /**
     * The version of the job as the caller gets it: definition, state and permissions. A validator identifies the
     * representation (RFC 9110 8.8.3), so a run that changes the state changes the tag: a conditional read then gets the
     * new state instead of 304, and a change made from an older read answers 412.
     */
    private String etag(JobResource job) {
        return Etags.of(job, json);
    }

    static void requireOk(ResponseEntity<?> result, String what, String id) {
        int status = result.getStatusCode().value();
        if (status == 404) {
            throw ApiProblem.notFound(what, id);
        }
        if (status == 403) {
            throw ApiProblem.forbidden("You may not do this with this " + what.toLowerCase(Locale.ROOT) + ".");
        }
        if (status >= 400) {
            throw ApiProblem.fromStatus(status, null);
        }
    }
}
