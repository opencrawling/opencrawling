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
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.v1.model.JobRunStepResource;
import org.opencrawling.runtime.api.v1.model.RunHistoryResource;
import org.opencrawling.runtime.api.v1.model.RunResource;
import org.opencrawling.runtime.dynamicjob.DynamicJobException;
import org.opencrawling.runtime.dynamicjob.DynamicJobService;
import org.opencrawling.runtime.orchestrator.RunStore;
import org.opencrawling.runtime.security.CallerResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Every run, whatever started it (#316): job runs and dynamic jobs, listed and followed through one API. Each run is
 * visible as its own API shows it: a job run to those who may read its job, a dynamic job to its owner and the dynamic
 * jobs' admin groups. Starting stays with each kind's own API ({@code /jobs/{id}:start}, {@code /dynamic-jobs}), and so
 * do approvals ({@code /dynamic-jobs/{jobId}:approve}).
 */
@RestController
@RequestMapping(ApiV1.BASE + "/runs")
@Tag(name = "Runs", description = "Every run, whatever started it: job runs and dynamic jobs")
public class RunsV1Controller {

    static final Set<String> KINDS = Set.of(RunResource.JOB, RunResource.DYNAMIC_FOLDER, RunResource.DYNAMIC_DOCUMENT);
    /** The states of the one run state machine ({@link org.opencrawling.runtime.orchestrator.RunStore.RunState}). */
    static final Set<String> STATES = java.util.Arrays.stream(org.opencrawling.runtime.orchestrator.RunStore.RunState.values())
            .map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final JobController jobs;
    private final ObjectProvider<DynamicJobService> dynamicJobs;
    private final ObjectProvider<CallerResolver> callers;

    public RunsV1Controller(JobController jobs, ObjectProvider<DynamicJobService> dynamicJobs,
                            ObjectProvider<CallerResolver> callers) {
        this.jobs = jobs;
        this.dynamicJobs = dynamicJobs;
        this.callers = callers;
    }

    @Operation(operationId = "listRuns", summary = "List runs of every kind",
            description = "Job runs and dynamic jobs the caller may see, active and ended while they are kept, newest first. "
                    + "Filter by kind (JOB, DYNAMIC_FOLDER, DYNAMIC_DOCUMENT), state or job.")
    @ApiResponse(responseCode = "200", description = "A page of runs")
    @GetMapping
    public Page<RunResource> list(
            @Parameter(description = "Only runs of this kind: JOB, DYNAMIC_FOLDER or DYNAMIC_DOCUMENT") @RequestParam(required = false) String kind,
            @Parameter(description = "Only runs in this state, such as RUNNING or PENDING_APPROVAL") @RequestParam(required = false) String state,
            @Parameter(description = "Only runs of this job or dynamic job") @RequestParam(required = false) String jobId,
            @Parameter(description = "Page size, 1 to 500 (default 50)") @RequestParam(required = false) Integer limit,
            @Parameter(description = "Cursor from page.nextCursor") @RequestParam(required = false) String cursor,
            HttpServletResponse response) {
        String wantedKind = checked("kind", kind, KINDS);
        String wantedState = checked("state", state, STATES);
        List<RunResource> runs = new ArrayList<>();
        if (wantedKind == null || RunResource.JOB.equals(wantedKind)) {
            jobs.keptRuns(jobId).forEach(run -> runs.add(RunResource.of(run, jobs.jobName(run.jobId()))));
        }
        if (wantedKind == null || !RunResource.JOB.equals(wantedKind)) {
            dynamic().forEach(job -> {
                if (jobId == null || jobId.equals(job.jobId())) {
                    runs.add(RunResource.of(job, dynamicJobs.getObject().runtimeOf(job.executionId())));
                }
            });
        }
        List<RunResource> page = runs.stream()
                .filter(run -> wantedKind == null || wantedKind.equals(run.kind()))
                .filter(run -> wantedState == null || wantedState.equals(run.state()))
                .sorted(Comparator.comparing(RunResource::createdAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
                        .thenComparing(RunResource::runId))
                .toList();
        String query = String.join("|", String.valueOf(wantedKind), String.valueOf(wantedState), String.valueOf(jobId));
        return Paging.page(page, limit, cursor, query, response, RunResource::runId);
    }

    @Operation(operationId = "getRun", summary = "Get a run of any kind",
            description = "A job run or a dynamic job by its run id (its execution id), active or ended, while it is kept.")
    @ApiResponse(responseCode = "200", description = "The run")
    @GetMapping("/{runId}")
    public RunResource get(@PathVariable String runId) {
        return find(runId);
    }

    @Operation(operationId = "getRunHistory", summary = "Get a run's history",
            description = "The run and every change of its state, in order (started, paused, resumed, stop requested, and how it "
                    + "ended). Empty while a dynamic job waits for approval.")
    @ApiResponse(responseCode = "200", description = "The run and its history")
    @GetMapping("/{runId}/history")
    public RunHistoryResource history(@PathVariable String runId) {
        RunResource run = find(runId);
        List<RunStore.RunStep> steps = RunResource.JOB.equals(run.kind())
                ? jobs.runHistory(runId).map(JobController.RunHistory::steps).orElse(List.of())
                : dynamicJobs.getObject().history(caller(), run.jobId());
        return new RunHistoryResource(run, steps.stream().map(JobRunStepResource::of).toList());
    }

    @Operation(operationId = "stopRun", summary = "Stop a run of any kind",
            description = "Asks a running or paused run to stop before its next document, in whichever runtime it runs. A dynamic job "
                    + "waiting for approval is cancelled. 409 job-not-running when it has ended.")
    @ApiResponse(responseCode = "202", description = "Stop requested")
    @PostMapping("/{runId}:stop")
    public ResponseEntity<RunResource> stop(@PathVariable String runId) {
        RunResource run = find(runId);
        if (RunResource.JOB.equals(run.kind())) {
            requireActive(run);
            JobsV1Controller.requireOk(jobs.stopRun(run.jobId(), runId), "Run", runId);
        } else {
            if (!run.active() && !"PENDING_APPROVAL".equals(run.state())) {
                throw notRunning(run);
            }
            dynamicJobs.getObject().stop(caller(), run.jobId());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(find(runId));
    }

    @Operation(operationId = "pauseRun", summary = "Pause a run of any kind",
            description = "The run holds before its next document until :resume or :stop, in whichever runtime it runs. 409 "
                    + "job-not-running when it is not running.")
    @ApiResponse(responseCode = "202", description = "Pause requested")
    @PostMapping("/{runId}:pause")
    public ResponseEntity<RunResource> pause(@PathVariable String runId) {
        return change(runId, true);
    }

    @Operation(operationId = "resumeRun", summary = "Resume a paused run of any kind",
            description = "The run goes on from the document where it held; a running run keeps running. 409 job-not-running when it "
                    + "is not running or paused.")
    @ApiResponse(responseCode = "202", description = "Resume requested")
    @PostMapping("/{runId}:resume")
    public ResponseEntity<RunResource> resume(@PathVariable String runId) {
        return change(runId, false);
    }

    private ResponseEntity<RunResource> change(String runId, boolean pause) {
        RunResource run = find(runId);
        requireActive(run);
        if (RunResource.JOB.equals(run.kind())) {
            JobsV1Controller.requireOk(pause ? jobs.pauseRun(run.jobId(), runId) : jobs.resumeRun(run.jobId(), runId), "Run", runId);
        } else if (pause) {
            dynamicJobs.getObject().pause(caller(), run.jobId());
        } else {
            dynamicJobs.getObject().resume(caller(), run.jobId());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(find(runId));
    }

    /** The run, if the caller may see it: a job run first, then a dynamic job. */
    private RunResource find(String runId) {
        Optional<RunResource> jobRun = jobs.keptRuns(null).stream().filter(run -> runId.equals(run.executionId())).findFirst()
                .map(run -> RunResource.of(run, jobs.jobName(run.jobId())));
        if (jobRun.isPresent()) {
            return jobRun.get();
        }
        DynamicJobService service = dynamicJobs.getIfAvailable();
        Caller caller = caller();
        if (service != null && caller != null) {
            try {
                Optional<DynamicJobService.JobStatus> job = service.byExecution(caller, runId);
                if (job.isPresent()) {
                    return RunResource.of(job.get(), service.runtimeOf(runId));
                }
            } catch (DynamicJobException e) {
                // A caller who may not use dynamic jobs sees none of them.
            }
        }
        throw ApiProblem.notFound("Run", runId);
    }

    /** The dynamic jobs the caller may see; none when dynamic jobs are off or the caller may not use them. */
    private List<DynamicJobService.JobStatus> dynamic() {
        DynamicJobService service = dynamicJobs.getIfAvailable();
        Caller caller = caller();
        if (service == null || caller == null) {
            return List.of();
        }
        try {
            return service.list(caller);
        } catch (DynamicJobException e) {
            return List.of();
        }
    }

    private Caller caller() {
        CallerResolver resolver = callers.getIfAvailable();
        return resolver == null ? null : resolver.verifiedCaller().orElse(null);
    }

    private static void requireActive(RunResource run) {
        if (!run.active()) {
            throw notRunning(run);
        }
    }

    private static ApiProblem notRunning(RunResource run) {
        return ApiProblem.conflict("job-not-running", "Run '" + run.runId() + "' is " + run.state() + ", not running.");
    }

    private static String checked(String field, String value, Set<String> allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String upper = value.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw ApiProblem.invalid(field, "must be one of " + String.join(", ", allowed.stream().sorted().toList()));
        }
        return upper;
    }
}
