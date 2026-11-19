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
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.JobController.JobDTO;
import org.opencrawling.runtime.api.v1.model.JobResource;
import org.opencrawling.runtime.scheduler.CronScheduleParser;
import org.opencrawling.runtime.scheduler.DispatchOutcome;
import org.opencrawling.runtime.scheduler.JobScheduleConfig;
import org.opencrawling.runtime.scheduler.JobScheduleState;
import org.opencrawling.runtime.scheduler.JobSchedulerEngine;
import org.opencrawling.runtime.scheduler.ScheduleDispatchRecord;
import org.opencrawling.runtime.scheduler.ScheduleStore;
import org.opencrawling.runtime.scheduler.ScheduleValidationException;
import org.opencrawling.runtime.scheduler.SchedulerProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Native job scheduling management over Platform API v1 (#197): list schedules, inspect upcoming runs, validate cron
 * expressions, manage per-job schedules with ETags and {@code If-Match}, pause/resume schedules, and read dispatch audits.
 */
@RestController
@RequestMapping(ApiV1.BASE)
@Tag(name = "Schedules", description = "Native cron job schedules, upcoming timeline and dispatch audit history")
public class SchedulesV1Controller {

    private static final int DEFAULT_PREVIEW_COUNT = 5;
    private static final int MAX_TIMELINE_TICKS_PER_JOB = 20;
    private static final Duration MAX_HORIZON = Duration.ofDays(7);

    private final JobController jobs;
    private final ScheduleStore scheduleStore;
    private final SchedulerProperties properties;
    private final JobSchedulerEngine engine;
    private final JsonMapper json;
    private final boolean requireIfMatch;

    @Autowired
    public SchedulesV1Controller(
            JobController jobs,
            ScheduleStore scheduleStore,
            SchedulerProperties properties,
            ObjectProvider<JobSchedulerEngine> engine,
            JsonMapper json,
            @Value("${opencrawling.api.require-if-match:false}") boolean requireIfMatch) {
        this.jobs = jobs;
        this.scheduleStore = scheduleStore;
        this.properties = properties != null ? properties : new SchedulerProperties();
        this.engine = engine != null ? engine.getIfAvailable() : null;
        this.json = json;
        this.requireIfMatch = requireIfMatch;
    }

    @Schema(description = "A job's recurring schedule definition, runtime execution state and upcoming fire timestamps")
    public record JobScheduleResource(
            @Schema(description = "Job identifier", example = "1763038591234") String jobId,
            @Schema(description = "Job name", example = "SharePoint Daily Sync") String jobName,
            @Schema(description = "Schedule configuration") JobScheduleConfig schedule,
            @Schema(description = "Runtime-managed schedule state") JobScheduleState state,
            @Schema(description = "Next upcoming scheduled execution timestamps (up to 5, UTC ISO-8601)") List<Instant> nextFireTimes) {}

    @Schema(description = "Upcoming scheduled execution entry on the scheduler timeline")
    public record UpcomingExecution(
            @Schema(description = "Job identifier") String jobId,
            @Schema(description = "Job name") String jobName,
            @Schema(description = "Configured cron expression") String cron,
            @Schema(description = "Configured IANA timezone") String timezone,
            @Schema(description = "Planned execution timestamp (UTC ISO-8601)") Instant scheduledAt) {}

    @Schema(description = "Aggregate scheduler health, counts and upcoming execution timeline")
    public record SchedulerOverviewResource(
            @Schema(description = "Whether the embedded scheduler engine is active on this runtime replica") boolean schedulerEnabled,
            @Schema(description = "Configured poll interval") String pollInterval,
            @Schema(description = "Default IANA timezone when a schedule omits timezone") String defaultTimezone,
            @Schema(description = "Visible jobs with a schedule configured") int totalScheduledJobs,
            @Schema(description = "Visible schedules currently enabled") int activeSchedules,
            @Schema(description = "Visible schedules currently paused (manual or auto-paused)") int pausedSchedules,
            @Schema(description = "Visible schedules currently auto-paused due to consecutive failures") int autoPausedSchedules,
            @Schema(description = "Dispatches skipped due to overlap, capacity, or missed grace period in the last 24h") int skippedLast24h,
            @Schema(description = "Chronologically ordered upcoming executions within the requested horizon") List<UpcomingExecution> upcoming) {}

    @Schema(description = "Request to validate a cron expression and preview its next fire times")
    public record CronValidationRequest(
            @NotBlank @Size(max = 120) @Schema(description = "5-field POSIX or 6-field Spring cron expression", example = "30 2 1 * *") String cron,
            @Size(max = 64) @Schema(description = "Optional IANA timezone (defaults to UTC)", example = "Europe/Rome") String timezone,
            @Min(1) @Max(20) @Schema(description = "How many upcoming fire timestamps to preview (1..20, default 5)", example = "5") Integer count,
            @Schema(description = "Optional lower bound window timestamp") Instant startAt,
            @Schema(description = "Optional upper bound window timestamp") Instant endAt) {}

    @Schema(description = "Result of validating a cron expression")
    public record CronValidationResource(
            @Schema(description = "Whether the expression and timezone are valid and satisfy min-interval rules") boolean valid,
            @Schema(description = "Trimmed input cron expression") String cron,
            @Schema(description = "Normalized 6-field Spring cron expression (or macro)") String normalizedCron,
            @Schema(description = "Resolved IANA timezone") String timezone,
            @Schema(description = "Human-readable schedule summary") String humanDescription,
            @Schema(description = "Preview of the next N matching fire timestamps") List<Instant> nextFireTimes,
            @Schema(description = "Validation error message when valid is false") String error) {}

    @Operation(operationId = "listSchedules", summary = "List scheduled jobs",
            description = "Returns a filtered, sorted, cursor-paged list of jobs the caller may see that have a schedule configured.")
    @ApiResponse(responseCode = "200", description = "A page of job schedules")
    @GetMapping("/schedules")
    public Page<JobScheduleResource> list(
            @Parameter(description = "Filter by enabled state (true or false)") @RequestParam(required = false) Boolean enabled,
            @Parameter(description = "Filter by last dispatch outcome (STARTED, SKIPPED_OVERLAP, SKIPPED_CAPACITY, MISSED_SKIPPED, REJECTED, AUTO_PAUSED)")
            @RequestParam(required = false) String outcome,
            @Parameter(description = "Only schedules whose job name contains this text (case-insensitive)") @RequestParam(required = false) String q,
            @Parameter(description = "Sort fields, comma-separated, '-' for descending: nextFireAt, name, lastFiredAt", example = "nextFireAt")
            @RequestParam(required = false) String sort,
            @Parameter(description = "Page size, 1 to 500 (default 50)") @RequestParam(required = false) Integer limit,
            @Parameter(description = "Cursor from page.nextCursor") @RequestParam(required = false) String cursor,
            HttpServletResponse response) {
        DispatchOutcome filterOutcome = parseOutcomeFilter(outcome);
        Map<String, JobScheduleState> allStates = scheduleStore.getAllStates();
        Instant now = Instant.now();
        List<JobScheduleResource> items = jobs.getAllJobs().stream()
                .filter(job -> job.schedule() != null)
                .filter(job -> q == null || (job.name() != null && job.name().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))))
                .map(job -> buildResource(job, allStates.get(job.id()), now))
                .filter(res -> enabled == null || (res.schedule() != null && res.schedule().enabled() == enabled))
                .filter(res -> filterOutcome == null || (res.state() != null && res.state().lastOutcome() == filterOutcome))
                .toList();

        items = Paging.sort(items, sort == null ? "nextFireAt" : sort, Map.of(
                "nextFireAt", Comparator.comparing(res -> res.state() != null ? res.state().nextFireAt() : null,
                        Comparator.nullsLast(Comparator.naturalOrder())),
                "name", Paging.byText(JobScheduleResource::jobName),
                "lastFiredAt", Comparator.comparing(res -> res.state() != null ? res.state().lastFiredAt() : null,
                        Comparator.nullsLast(Comparator.naturalOrder()))),
                JobScheduleResource::jobId);

        String query = String.join("|", Objects.toString(enabled, ""), Objects.toString(outcome, ""),
                Objects.toString(q, ""), Objects.toString(sort, ""));
        return Paging.page(items, limit, cursor, query, response, JobScheduleResource::jobId);
    }

    @Operation(operationId = "getSchedulerOverview", summary = "Scheduler overview and upcoming timeline",
            description = "Returns the native scheduler engine status, aggregate counts across visible jobs, and chronologically sorted upcoming executions.")
    @ApiResponse(responseCode = "200", description = "Scheduler overview and upcoming timeline")
    @GetMapping("/schedules/overview")
    public SchedulerOverviewResource overview(
            @Parameter(description = "Look-ahead duration for the upcoming timeline (default 24h, max 7d)", example = "24h")
            @RequestParam(required = false, defaultValue = "24h") String horizon,
            @Parameter(description = "Maximum number of upcoming ticks to return (1..500, default 50)", example = "50")
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        Duration horizonDuration = parseHorizon(horizon);
        int cappedLimit = clampLimit(limit);
        Instant now = Instant.now();
        Instant horizonEnd = now.plus(horizonDuration);
        Map<String, JobScheduleState> allStates = scheduleStore.getAllStates();

        List<JobDTO> visibleJobs = jobs.getAllJobs().stream()
                .filter(job -> job.schedule() != null)
                .toList();

        int active = 0;
        int paused = 0;
        int autoPaused = 0;
        List<UpcomingExecution> upcoming = new ArrayList<>();

        for (JobDTO job : visibleJobs) {
            JobScheduleConfig config = job.schedule();
            JobScheduleState state = allStates.get(job.id());
            if (config.enabled()) {
                active++;
                List<Instant> fireTimes = CronScheduleParser.nextFireTimes(
                        config, properties.getDefaultTimezone(), now.minusSeconds(1), MAX_TIMELINE_TICKS_PER_JOB);
                String tz = config.effectiveTimezone(properties.getDefaultTimezone());
                for (Instant tick : fireTimes) {
                    if (!tick.isBefore(now) && !tick.isAfter(horizonEnd)) {
                        upcoming.add(new UpcomingExecution(job.id(), job.name(), config.cron(), tz, tick));
                    }
                }
            } else {
                paused++;
                if (state != null && state.lastOutcome() == DispatchOutcome.AUTO_PAUSED) {
                    autoPaused++;
                }
            }
        }

        upcoming.sort(Comparator.comparing(UpcomingExecution::scheduledAt).thenComparing(UpcomingExecution::jobId));
        if (upcoming.size() > cappedLimit) {
            upcoming = upcoming.subList(0, cappedLimit);
        }

        Instant cutoff24h = now.minus(Duration.ofHours(24));
        int skipped24h = countVisibleSkippedSince(visibleJobs, cutoff24h);
        boolean activeOnRuntime = engine != null ? engine.isActiveOnThisRuntime() : properties.isEnabled();

        return new SchedulerOverviewResource(
                activeOnRuntime,
                properties.getPollInterval().toString(),
                properties.getDefaultTimezone(),
                visibleJobs.size(),
                active,
                paused,
                autoPaused,
                skipped24h,
                upcoming);
    }

    @Operation(operationId = "validateCron", summary = "Validate a cron expression",
            description = "Validates a 5- or 6-field cron expression and IANA timezone, checks the minimum interval rule, and previews upcoming fire times.")
    @ApiResponse(responseCode = "200", description = "Cron validation result")
    @PostMapping("/schedules:validate-cron")
    public CronValidationResource validateCron(@Valid @RequestBody CronValidationRequest request) {
        int previewCount = request.count() != null ? request.count() : DEFAULT_PREVIEW_COUNT;
        Instant now = Instant.now();
        JobScheduleConfig probe = new JobScheduleConfig(
                request.cron(), request.timezone(), true, null, null, null, request.startAt(), request.endAt(), null);
        try {
            CronScheduleParser.ParsedSchedule parsed = CronScheduleParser.validate(probe, properties, now);
            List<Instant> nextTimes = CronScheduleParser.nextFireTimes(probe, properties.getDefaultTimezone(), now, previewCount);
            String description = CronScheduleParser.describe(parsed.normalizedCron(), parsed.zone());
            return new CronValidationResource(
                    true, probe.cron(), parsed.normalizedCron(), parsed.zone().getId(), description, nextTimes, null);
        } catch (ScheduleValidationException e) {
            String tz = probe.effectiveTimezone(properties.getDefaultTimezone());
            return new CronValidationResource(false, request.cron(), null, tz, null, List.of(),
                    e.getField() + ": " + e.getMessage());
        }
    }

    @Operation(operationId = "getJobSchedule", summary = "Get a job's schedule",
            description = "Retrieves the job's recurring schedule configuration, runtime state and next 5 fire times. Supports If-None-Match.")
    @ApiResponse(responseCode = "200", description = "The job's schedule", headers = @Header(name = "ETag", description = "Schedule version"))
    @ApiResponse(responseCode = "304", description = "Not modified since If-None-Match")
    @GetMapping("/jobs/{id}/schedule")
    public ResponseEntity<JobScheduleResource> get(@PathVariable String id,
                                                   @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        JobDTO job = findVisible(id);
        if (job.schedule() == null) {
            throw ApiProblem.notFound("Schedule for job", id);
        }
        JobScheduleResource resource = buildResource(job, scheduleStore.getState(job.id()).orElse(null), Instant.now());
        String etag = Etags.of(resource, json);
        if (Etags.notModified(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(resource);
    }

    @Operation(operationId = "setJobSchedule", summary = "Set or replace a job's schedule",
            description = "Creates or replaces the recurring schedule configuration of a job. Validates cron, timezone and min-interval, and honors If-Match.")
    @ApiResponse(responseCode = "200", description = "Schedule saved", headers = @Header(name = "ETag", description = "New version"))
    @PutMapping("/jobs/{id}/schedule")
    public ResponseEntity<JobScheduleResource> set(@PathVariable String id,
                                                   @Valid @RequestBody JobScheduleConfig config,
                                                   @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Instant now = Instant.now();
        try {
            CronScheduleParser.validate(config, properties, now);
        } catch (ScheduleValidationException e) {
            throw ApiProblem.invalid(e.getField(), e.getMessage());
        }

        return jobs.locked(() -> {
            JobDTO current = findVisibleForManage(id);
            checkPreconditionOnReplace(current, ifMatch, now);

            JobScheduleState existingState = scheduleStore.getState(current.id()).orElse(null);
            Instant nextFireAt = CronScheduleParser.computeNextFireAt(config, properties.getDefaultTimezone(), now).orElse(null);
            boolean active = config.enabled() && nextFireAt != null;

            JobScheduleState nextState = new JobScheduleState(
                    current.id(),
                    active,
                    nextFireAt,
                    existingState != null ? existingState.lastFiredAt() : null,
                    existingState != null ? existingState.lastExecutionId() : null,
                    null,
                    null,
                    false,
                    0,
                    now);

            jobs.updateScheduleDefinition(current.id(), config);
            scheduleStore.saveState(nextState);

            JobDTO updated = jobs.findJobById(current.id());
            JobScheduleResource resource = buildResource(updated != null ? updated : current.withSchedule(config), nextState, now);
            return ResponseEntity.ok().eTag(Etags.of(resource, json)).body(resource);
        });
    }

    @Operation(operationId = "deleteJobSchedule", summary = "Remove a job's schedule",
            description = "Removes the recurring schedule configuration from a job without deleting the job definition. Honors If-Match.")
    @ApiResponse(responseCode = "204", description = "Schedule removed")
    @DeleteMapping("/jobs/{id}/schedule")
    public ResponseEntity<Void> delete(@PathVariable String id,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Instant now = Instant.now();
        jobs.locked(() -> {
            JobDTO current = findVisibleForManage(id);
            if (current.schedule() == null) {
                throw ApiProblem.notFound("Schedule for job", id);
            }
            JobScheduleResource currentResource = buildResource(current, scheduleStore.getState(current.id()).orElse(null), now);
            String currentEtag = Etags.of(currentResource, json);
            if (requireIfMatch && (ifMatch == null || ifMatch.isBlank())) {
                throw preconditionRequiredProblem();
            }
            Etags.requireMatch(ifMatch, currentEtag);

            jobs.updateScheduleDefinition(current.id(), null);
            scheduleStore.deleteState(current.id());
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "pauseJobSchedule", summary = "Pause a job's schedule",
            description = "Temporarily disables automatic scheduled triggers for the job without discarding its cron or policy settings.")
    @ApiResponse(responseCode = "200", description = "Schedule paused", headers = @Header(name = "ETag", description = "New version"))
    @PostMapping("/jobs/{id}/schedule:pause")
    public ResponseEntity<JobScheduleResource> pause(@PathVariable String id,
                                                     @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return toggleSchedule(id, false, ifMatch);
    }

    @Operation(operationId = "resumeJobSchedule", summary = "Resume a paused schedule",
            description = "Re-enables scheduled triggers for the job, clears any AUTO_PAUSED circuit-breaker state, and schedules the next upcoming fire time.")
    @ApiResponse(responseCode = "200", description = "Schedule resumed", headers = @Header(name = "ETag", description = "New version"))
    @PostMapping("/jobs/{id}/schedule:resume")
    public ResponseEntity<JobScheduleResource> resume(@PathVariable String id,
                                                      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return toggleSchedule(id, true, ifMatch);
    }

    @Operation(operationId = "listJobScheduleDispatches", summary = "List schedule dispatch history",
            description = "Returns the cursor-paged audit log of scheduler dispatch attempts (STARTED, SKIPPED_OVERLAP, SKIPPED_CAPACITY, MISSED_SKIPPED, REJECTED, AUTO_PAUSED) for this job.")
    @ApiResponse(responseCode = "200", description = "A page of schedule dispatch audit records")
    @GetMapping("/jobs/{id}/schedule/dispatches")
    public Page<ScheduleDispatchRecord> dispatches(
            @PathVariable String id,
            @Parameter(description = "Only dispatches with this outcome") @RequestParam(required = false) String outcome,
            @Parameter(description = "Page size, 1 to 500 (default 50)") @RequestParam(required = false) Integer limit,
            @Parameter(description = "Cursor from page.nextCursor") @RequestParam(required = false) String cursor,
            HttpServletResponse response) {
        JobDTO job = findVisible(id);
        DispatchOutcome filterOutcome = parseOutcomeFilter(outcome);
        List<ScheduleDispatchRecord> records = scheduleStore.listDispatches(job.id()).stream()
                .filter(d -> filterOutcome == null || d.outcome() == filterOutcome)
                .toList();
        return Paging.page(records, limit, cursor, job.id() + "|" + Objects.toString(outcome, ""),
                response, ScheduleDispatchRecord::id);
    }

    // ---- helpers ----

    private ResponseEntity<JobScheduleResource> toggleSchedule(String id, boolean nextEnabled, String ifMatch) {
        Instant now = Instant.now();
        return jobs.locked(() -> {
            JobDTO current = findVisibleForManage(id);
            if (current.schedule() == null) {
                throw ApiProblem.notFound("Schedule for job", id);
            }
            JobScheduleState existingState = scheduleStore.getState(current.id()).orElse(null);
            JobScheduleResource currentResource = buildResource(current, existingState, now);
            Etags.requireMatch(ifMatch, Etags.of(currentResource, json));

            JobScheduleConfig nextConfig = current.schedule().withEnabled(nextEnabled);
            Instant nextFireAt = nextEnabled
                    ? CronScheduleParser.computeNextFireAt(nextConfig, properties.getDefaultTimezone(), now).orElse(null)
                    : null;

            JobScheduleState nextState = new JobScheduleState(
                    current.id(),
                    nextEnabled && nextFireAt != null,
                    nextFireAt,
                    existingState != null ? existingState.lastFiredAt() : null,
                    existingState != null ? existingState.lastExecutionId() : null,
                    nextEnabled ? null : (existingState != null ? existingState.lastOutcome() : null),
                    nextEnabled ? null : (existingState != null ? existingState.lastDetail() : null),
                    false,
                    nextEnabled ? 0 : (existingState != null ? existingState.consecutiveFailures() : 0),
                    now);

            jobs.updateScheduleDefinition(current.id(), nextConfig);
            scheduleStore.saveState(nextState);
            JobScheduleResource resource = buildResource(current.withSchedule(nextConfig), nextState, now);
            return ResponseEntity.ok().eTag(Etags.of(resource, json)).body(resource);
        });
    }

    private void checkPreconditionOnReplace(JobDTO current, String ifMatch, Instant now) {
        if (current.schedule() != null) {
            JobScheduleResource currentSchedule = buildResource(
                    current, scheduleStore.getState(current.id()).orElse(null), now);
            String currentEtag = Etags.of(currentSchedule, json);
            if (requireIfMatch && (ifMatch == null || ifMatch.isBlank())) {
                throw preconditionRequiredProblem();
            }
            Etags.requireMatch(ifMatch, currentEtag);
        } else if (ifMatch != null && !ifMatch.isBlank()) {
            JobScheduleState state = scheduleStore.getState(current.id()).orElse(null);
            JobResource jobResource = JobResource.of(current, jobs.getJobPermissions().get(current.id()), state);
            Etags.requireMatch(ifMatch, Etags.of(jobResource, json));
        }
    }

    private JobDTO findVisible(String id) {
        JobDTO job = jobs.findJobById(id);
        if (job == null || !jobs.canRead(job)) {
            throw ApiProblem.notFound("Job", id);
        }
        return job;
    }

    private JobDTO findVisibleForManage(String id) {
        JobDTO job = findVisible(id);
        if (!jobs.canManage(job)) {
            throw ApiProblem.forbidden("Only the job's owner or an admin may manage its schedule.");
        }
        return job;
    }

    private JobScheduleResource buildResource(JobDTO job, JobScheduleState state, Instant now) {
        JobScheduleConfig config = job.schedule();
        List<Instant> nextTimes = config != null && config.enabled()
                ? CronScheduleParser.nextFireTimes(config, properties.getDefaultTimezone(), now, DEFAULT_PREVIEW_COUNT)
                : List.of();
        return new JobScheduleResource(job.id(), job.name(), config, state, nextTimes);
    }

    private int countVisibleSkippedSince(List<JobDTO> visibleJobs, Instant since) {
        List<DispatchOutcome> skipped = List.of(
                DispatchOutcome.SKIPPED_OVERLAP, DispatchOutcome.SKIPPED_CAPACITY, DispatchOutcome.MISSED_SKIPPED);
        if (jobs.allJobsUnfiltered().size() == visibleJobs.size()) {
            return scheduleStore.countDispatchesSince(since, skipped);
        }
        int total = 0;
        for (JobDTO job : visibleJobs) {
            for (ScheduleDispatchRecord d : scheduleStore.listDispatches(job.id())) {
                if (!d.dispatchedAt().isBefore(since) && skipped.contains(d.outcome())) {
                    total++;
                }
            }
        }
        return total;
    }

    private static DispatchOutcome parseOutcomeFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DispatchOutcome.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("outcome",
                    "must be STARTED, SKIPPED_OVERLAP, SKIPPED_CAPACITY, MISSED_SKIPPED, REJECTED, or AUTO_PAUSED");
        }
    }

    private static Duration parseHorizon(String horizon) {
        if (horizon == null || horizon.isBlank()) {
            return Duration.ofHours(24);
        }
        try {
            Duration parsed = DurationStyle.detectAndParse(horizon.strip());
            if (parsed.isZero() || parsed.isNegative() || parsed.compareTo(MAX_HORIZON) > 0) {
                throw ApiProblem.invalid("horizon", "must be positive and at most 7d");
            }
            return parsed;
        } catch (ApiProblem e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApiProblem.invalid("horizon", "must be a duration such as 6h, 24h, or 7d");
        }
    }

    private static int clampLimit(Integer limit) {
        int val = limit != null ? limit : 50;
        if (val < 1 || val > Paging.MAX_LIMIT) {
            throw ApiProblem.invalid("limit", "must be between 1 and " + Paging.MAX_LIMIT);
        }
        return val;
    }

    private static ApiProblem preconditionRequiredProblem() {
        return new ApiProblem(HttpStatus.PRECONDITION_REQUIRED, "precondition-required",
                "Precondition required", "This runtime requires If-Match on changes: read the resource, then send its ETag as If-Match.");
    }
}
