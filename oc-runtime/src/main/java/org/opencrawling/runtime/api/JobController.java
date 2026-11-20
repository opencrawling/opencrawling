/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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

import org.springframework.beans.factory.ObjectProvider;
import org.opencrawling.runtime.controlplane.ControlPlaneStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.opencrawling.core.connector.JobContext;
import org.opencrawling.core.connector.RepositoryConnectorFactory;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.connector.ConnectorResolutionException;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.security.CallerResolver;
import org.opencrawling.runtime.security.ManagementAuthorization;
import org.opencrawling.runtime.security.ManagementAuthorization.JobAction;
import org.opencrawling.runtime.security.JwtCallerMapper;
import org.opencrawling.runtime.scheduler.CronScheduleParser;
import org.opencrawling.runtime.scheduler.JobScheduleConfig;
import org.opencrawling.runtime.scheduler.JobScheduleState;
import org.opencrawling.runtime.scheduler.ScheduleStore;
import org.opencrawling.runtime.scheduler.ScheduleValidationException;
import org.opencrawling.runtime.scheduler.SchedulerProperties;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Pipeline & Jobs", description = "Operations for managing, configuring, triggering, and monitoring crawling pipeline jobs")
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final List<JobDTO> defaultJobs;
    private final ControlPlaneStore controlPlaneStore;
    private final JobOrchestrator jobOrchestrator;
    private final ConnectorFactory connectorFactory;
    private final JobRunRegistry runRegistry;
    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private PipelineProperties pipelineProperties;

    @Autowired(required = false)
    private Environment environment;

    @Autowired(required = false)
    private org.opencrawling.runtime.reporting.JobLedgerAccumulator ledgerAccumulator;

    /** Absent outside servlet web apps; then no caller is ever recorded. */
    @Autowired(required = false)
    private CallerResolver callerResolver;

    /** Absent when a test builds the controller itself; then every request is allowed, as with authorization off. */
    @Autowired(required = false)
    private ManagementAuthorization authorization;

    @Autowired(required = false)
    private ScheduleStore scheduleStore;

    @Autowired(required = false)
    private SchedulerProperties schedulerProperties;

    private final Set<String> activeScheduledExecutions = ConcurrentHashMap.newKeySet();
    private volatile Clock scheduleClock = Clock.systemUTC();

    public JobController(JobOrchestrator jobOrchestrator, ConnectorFactory connectorFactory, JobRunRegistry runRegistry,
                         JdbcTemplate jdbcTemplate) {
        this(jobOrchestrator, connectorFactory, runRegistry, jdbcTemplate, ControlPlaneStore.files());
    }

    @Autowired
    public JobController(JobOrchestrator jobOrchestrator, ConnectorFactory connectorFactory, JobRunRegistry runRegistry,
                         JdbcTemplate jdbcTemplate, ObjectProvider<ControlPlaneStore> controlPlaneStore) {
        this(jobOrchestrator, connectorFactory, runRegistry, jdbcTemplate, controlPlaneStore.getIfAvailable(ControlPlaneStore::files));
    }

    /** With the store that keeps the jobs (#315). */
    public JobController(
            JobOrchestrator jobOrchestrator,
            ConnectorFactory connectorFactory,
            JobRunRegistry runRegistry,
            JdbcTemplate jdbcTemplate,
            ControlPlaneStore controlPlaneStore) {
        this.controlPlaneStore = controlPlaneStore;
        this.jobOrchestrator = jobOrchestrator;
        this.connectorFactory = connectorFactory;
        this.runRegistry = runRegistry;
        this.jdbcTemplate = jdbcTemplate;
        
        // Initial defaults
        List<JobDTO> defaults = new ArrayList<>();
        defaults.add(new JobDTO("1", "Default_Job", "FileSystem_Local", "PGVector_Output", "", "/data", "Ready", "Idle", 0, "N/A", "Ollama_Embedding_Default", null));
        
        this.defaultJobs = List.copyOf(defaults);
        controlPlaneStore.loadList("jobs.json", JobDTO.class, defaultJobs); // saves or imports them on the first start
        if (runRegistry != null) {
            runRegistry.onInterrupted(this::runInterrupted);
        }
    }

    /** A run whose runtime stopped renewing its heartbeat: the job no longer runs, so it may not say it does. */
    private void runInterrupted(JobRunRegistry.RunInfo run) {
        locked(() -> {
            JobDTO job = findJobById(run.jobId());
            if (job != null && ("Running".equals(job.status()) || "Paused".equals(job.status()))
                    && runRegistry.run(job.id()).isEmpty()) {
                updateJobStatusAndStage(job.id(), "Error", "Interrupted", job.documents());
            }
            return null;
        });
    }

    /** The saved jobs, read on every call: another runtime that shares the store may have changed them (#315). */
    private List<JobDTO> jobs() {
        return controlPlaneStore.loadList("jobs.json", JobDTO.class, defaultJobs);
    }

    /**
     * Runs {@code work} with no other change of jobs, connectors or settings in between, in this runtime or another
     * that shares the store: what the Platform API holds around a conditional change (check If-Match, then write).
     */
    public <R> R locked(Supplier<R> work) {
        return controlPlaneStore.locked(work);
    }

    @Operation(summary = "List all pipeline jobs", description = "Returns a list of all configured crawling pipeline jobs and their latest execution state.")
    @ApiResponse(responseCode = "200", description = "List of configured jobs successfully retrieved")
    @GetMapping
    public List<JobDTO> getAllJobs() {
        List<JobDTO> jobs = jobs();
        if (!authorizationOn()) {
            return jobs;
        }
        return jobs.stream().filter(job -> allowed(job, JobAction.READ)).toList();
    }

    @Operation(summary = "What the caller may do with each job", description = "For every job the caller may see: READ, EXECUTE (start, stop, pause) and MANAGE (edit, delete). Every job allows all three when management authorization is off.")
    @ApiResponse(responseCode = "200", description = "Allowed actions by job id")
    @GetMapping("/permissions")
    public Map<String, List<JobAction>> getJobPermissions() {
        Map<String, List<JobAction>> permissions = new LinkedHashMap<>();
        for (JobDTO job : jobs()) {
            List<JobAction> allowedActions = Arrays.stream(JobAction.values()).filter(action -> allowed(job, action)).toList();
            if (allowedActions.contains(JobAction.READ)) {
                permissions.put(job.id(), allowedActions);
            }
        }
        return permissions;
    }

    @Operation(summary = "Get pipeline job by ID", description = "Retrieves details and status of a specific crawling job by its identifier.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Job details successfully retrieved"),
        @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<JobDTO> getJob(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String id) {
        JobDTO job = findJobById(id);
        if (job == null || !allowed(job, JobAction.READ)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job);
    }

    /**
     * The job with this exact id, whoever asks: for callers inside the runtime that authenticate on their own (OIS
     * ingest token, gRPC push). Request handlers use {@link #getJob}, which applies the caller's access.
     */
    public JobDTO findJobById(String id) {
        return jobs().stream().filter(j -> j.id().equals(id)).findFirst().orElse(null);
    }

    /** All saved jobs without per-caller filtering: for internal background components such as the scheduler. */
    public List<JobDTO> allJobsUnfiltered() {
        return jobs();
    }

    /** Whether the current caller may see this job, its runs, reports and traces. */
    public boolean canRead(JobDTO job) {
        return job != null && allowed(job, JobAction.READ);
    }

    /** Whether the current caller may modify or delete this job and its schedule. */
    public boolean canManage(JobDTO job) {
        return job != null && allowed(job, JobAction.MANAGE);
    }

    /** Whether the current caller may start, stop or pause this job. */
    public boolean canExecute(JobDTO job) {
        return job != null && allowed(job, JobAction.EXECUTE);
    }

    public boolean consumeScheduledExecution(String executionId) {
        return executionId != null && activeScheduledExecutions.remove(executionId);
    }

    public void setScheduleStore(ScheduleStore scheduleStore, SchedulerProperties schedulerProperties) {
        setScheduleStore(scheduleStore, schedulerProperties, Clock.systemUTC());
    }

    public void setScheduleStore(ScheduleStore scheduleStore, SchedulerProperties schedulerProperties, Clock scheduleClock) {
        this.scheduleStore = scheduleStore;
        this.schedulerProperties = schedulerProperties;
        this.scheduleClock = scheduleClock != null ? scheduleClock : Clock.systemUTC();
    }

    @Operation(summary = "Create or update a pipeline job", description = "Creates a new crawling job or updates an existing job definition.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Job created or updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid job configuration or unrecognized pipeline mode")
    })
    @PostMapping
    public ResponseEntity<?> saveJob(@RequestBody JobDTO job) {
        return switch (save(job)) {
            case SaveOutcome.Saved saved -> ResponseEntity.status(201).build();
            case SaveOutcome.Missing missing -> ResponseEntity.status(201).build(); // unchanged legacy behaviour
            case SaveOutcome.InvalidMode invalid -> ResponseEntity.badRequest().build();
            case SaveOutcome.Invalid invalid -> ResponseEntity.badRequest().body(Map.of("error", invalid.reason()));
            case SaveOutcome.InvalidSchedule invalid -> ResponseEntity.badRequest().body(Map.of("error", invalid.field() + ": " + invalid.reason()));
            case SaveOutcome.Forbidden forbidden -> ResponseEntity.status(403).build();
            case SaveOutcome.NotFound notFound -> ResponseEntity.notFound().build();
        };
    }

    /** The result of {@link #save}: what the REST endpoints (legacy and {@code /api/v1}) turn into a response. */
    public sealed interface SaveOutcome {
        /** Saved; {@code created} when it was a new job. */
        record Saved(JobDTO job, boolean created) implements SaveOutcome {}
        /** An update of an id that does not exist; nothing was saved. */
        record Missing(String id) implements SaveOutcome {}
        record InvalidMode(String reason) implements SaveOutcome {}
        record Invalid(String reason) implements SaveOutcome {}
        record InvalidSchedule(String field, String reason) implements SaveOutcome {}
        record Forbidden() implements SaveOutcome {}
        record NotFound() implements SaveOutcome {}
    }

    /**
     * Creates the job (no id, or id {@code new}) or updates the one with this id, checking the pipeline mode, the
     * repository connector's validation and the caller's access.
     */
    public SaveOutcome save(JobDTO job) {
        return save(job, false);
    }

    /**
     * @param replace on update, take the optional fields (transformation connector, pipeline mode, crawl spec,
     *                narrativization) as sent, so a null clears them; otherwise a null keeps the stored value, as the
     *                admin UI expects
     */
    public SaveOutcome save(JobDTO job, boolean replace) {
        return controlPlaneStore.locked(() -> saveLocked(job, replace));
    }

    private SaveOutcome saveLocked(JobDTO job, boolean replace) {
        log.info("Saving job: {}", job.name());
        List<JobDTO> jobs = jobs();
        try {
            PipelineMode requestedMode = PipelineMode.parseStrict(job.pipelineMode());
            job = job.withPipelineMode(requestedMode != null ? requestedMode.externalName() : null);
        } catch (IllegalArgumentException e) {
            log.warn("Rejecting job '{}': {}", job.name(), e.getMessage());
            return new SaveOutcome.InvalidMode(e.getMessage());
        }
        String validationError = validate(job);
        if (validationError != null) {
            log.warn("Rejecting job '{}': {}", job.name(), validationError);
            return new SaveOutcome.Invalid(validationError);
        }
        JobDTO saved = null;
        JobScheduleConfig previousSchedule = null;
        boolean created = false;
        if (job.id() == null || job.id().isBlank() || job.id().equals("new")) {
            if (authorization != null && !authorization.canCreateJobs()) {
                log.warn("Rejecting job '{}': the caller may not create jobs.", job.name());
                return new SaveOutcome.Forbidden();
            }
            if (job.schedule() != null) {
                try {
                    CronScheduleParser.validate(job.schedule(), effectiveSchedulerProperties(), scheduleClock.instant());
                } catch (ScheduleValidationException e) {
                    return new SaveOutcome.InvalidSchedule(e.getField(), e.getMessage());
                }
            }
            // Timestamp-based id, moved forward while taken: jobs saved in the same millisecond used to share one.
            String newId = newJobId();
            JobDTO newJob = new JobDTO(
                newId,
                job.name(),
                job.repositoryConnector(),
                job.outputConnector(),
                job.authorityConnector(),
                job.path(),
                "Ready",
                "Idle",
                0,
                "N/A",
                job.transformationConnector() != null ? job.transformationConnector() : "Ollama_Embedding_Default",
                job.narrativization(),
                job.pipelineMode(),
                job.crawlSpec(),
                accessForNewJob(job.access()),
                job.schedule()
            );
            jobs.add(newJob);
            saved = newJob;
            created = true;
        } else {
            // Edit/Update existing job
            JobDTO target = findJobById(job.id());
            if (target != null && !allowed(target, JobAction.READ)) {
                return new SaveOutcome.NotFound();
            }
            if (target != null && !allowed(target, JobAction.MANAGE)) {
                log.warn("Rejecting update of job {}: only its owner or an admin may edit it.", target.id());
                return new SaveOutcome.Forbidden();
            }
            for (int i = 0; i < jobs.size(); i++) {
                if (jobs.get(i).id().equals(job.id())) {
                    JobDTO existing = jobs.get(i);
                    previousSchedule = existing.schedule();
                    JobScheduleConfig nextSchedule = job.schedule() != null || replace ? job.schedule() : existing.schedule();
                    if (nextSchedule != null) {
                        try {
                            CronScheduleParser.validate(nextSchedule, effectiveSchedulerProperties(), scheduleClock.instant());
                        } catch (ScheduleValidationException e) {
                            return new SaveOutcome.InvalidSchedule(e.getField(), e.getMessage());
                        }
                    }
                    saved = new JobDTO(
                        job.id(),
                        job.name(),
                        job.repositoryConnector(),
                        job.outputConnector(),
                        job.authorityConnector(),
                        job.path(),
                        job.status() != null ? job.status() : existing.status(),
                        job.currentStage() != null ? job.currentStage() : existing.currentStage(),
                        existing.documents(),
                        existing.lastRun(),
                        job.transformationConnector() != null ? job.transformationConnector()
                                : replace ? "Ollama_Embedding_Default" : existing.transformationConnector(),
                        job.narrativization() != null || replace ? job.narrativization() : existing.narrativization(),
                        job.pipelineMode() != null || replace ? job.pipelineMode() : existing.pipelineMode(),
                        job.crawlSpec() != null || replace ? job.crawlSpec() : existing.crawlSpec(),
                        accessForUpdate(existing.access(), job.access()),
                        nextSchedule
                    );
                    jobs.set(i, saved);
                    break;
                }
            }
        }
        controlPlaneStore.save("jobs.json", jobs);
        if (saved != null) {
            syncScheduleStateLocked(saved.id(), previousSchedule, saved.schedule());
        }
        return saved == null ? new SaveOutcome.Missing(job.id()) : new SaveOutcome.Saved(saved, created);
    }

    @Operation(summary = "Delete a pipeline job", description = "Removes a pipeline job definition by its identifier.")
    @ApiResponse(responseCode = "200", description = "Job successfully deleted")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteJob(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String id) {
        return controlPlaneStore.locked(() -> deleteLocked(id));
    }

    private ResponseEntity<Void> deleteLocked(String id) {
        log.info("Deleting job: {}", id);
        if (authorizationOn()) {
            JobDTO job = findJobById(id);
            if (job == null || !allowed(job, JobAction.READ)) {
                return ResponseEntity.notFound().build();
            }
            if (!allowed(job, JobAction.MANAGE)) {
                log.warn("Rejecting deletion of job {}: only its owner or an admin may delete it.", id);
                return ResponseEntity.status(403).build();
            }
        }
        List<JobDTO> jobs = jobs();
        jobs.removeIf(j -> j.id().equals(id));
        controlPlaneStore.save("jobs.json", jobs);
        if (scheduleStore != null) {
            scheduleStore.deleteState(id);
        }
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Start a pipeline job", description = "Initiates asynchronous crawling and ingestion execution for the specified job on a Java 25 Virtual Thread.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Job start signal accepted and initiated"),
        @ApiResponse(responseCode = "404", description = "Job not found"),
        @ApiResponse(responseCode = "409", description = "Job already running, or its connectors cannot be used"),
        @ApiResponse(responseCode = "429", description = "The cap on runs at the same time (opencrawling.jobs.max-concurrent-runs), or the caller's own cap (opencrawling.jobs.max-concurrent-runs-per-caller), is reached")
    })
    @PostMapping("/{id}/start")
    public ResponseEntity<Void> startJob(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String id,
            @Parameter(description = "Optional id that ties this run to the caller's own request or workflow; recorded in the run's ledger")
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return switch (start(id, correlationId)) {
            case StartOutcome.Started started -> ResponseEntity.ok().build();
            case StartOutcome.NotFound notFound -> ResponseEntity.notFound().build();
            case StartOutcome.Forbidden forbidden -> ResponseEntity.status(403).build();
            case StartOutcome.AlreadyRunning running -> ResponseEntity.status(409).build();
            case StartOutcome.Rejected rejected -> ResponseEntity.status(409).build();
            case StartOutcome.LimitReached limit -> ResponseEntity.status(429).build();
        };
    }

    /** The result of {@link #start}. */
    public sealed interface StartOutcome {
        record Started(String jobId, String executionId) implements StartOutcome {}
        record NotFound() implements StartOutcome {}
        record Forbidden() implements StartOutcome {}
        record AlreadyRunning(String executionId) implements StartOutcome {}
        /** The job's connectors or settings cannot be used; {@code reason} says why. */
        record Rejected(String reason) implements StartOutcome {}
        /** A cap on runs at the same time is reached; {@code perCaller} for the caller's own cap. */
        record LimitReached(int limit, boolean perCaller) implements StartOutcome {}
    }

    /**
     * Starts the job on a virtual thread. Must be called on the request thread: the caller's identity is taken from
     * it for the run's provenance.
     */
    public StartOutcome start(String id, String correlationId) {
        log.info("Starting job {}", id);
        // Taken here, on the request thread: the run's own thread has no security context.
        return startInternal(id, provenance(correlationId), true);
    }

    /**
     * Starts {@code id} on behalf of the native job scheduler (#197) with anonymous {@code SOURCE_SCHEDULER}
     * provenance. Authorization was enforced when the schedule was configured.
     */
    public StartOutcome startScheduled(String id, Instant scheduledFor) {
        log.info("Starting scheduled run for job {} (scheduledFor={})", id, scheduledFor);
        String correlation = scheduledFor != null ? "sched:" + id + ":" + scheduledFor.getEpochSecond() : "sched:" + id;
        return startInternal(id, ExecutionProvenance.anonymous(ExecutionProvenance.SOURCE_SCHEDULER, correlation), false);
    }

    private StartOutcome startInternal(String id, ExecutionProvenance provenance, boolean checkAccess) {
        final String cleanId = id != null ? id.trim() : "";

        // Find the job to get parameters
        List<JobDTO> jobs = jobs();
        JobDTO activeJob = jobs.stream()
            .filter(j -> j.id() != null && j.id().trim().equalsIgnoreCase(cleanId))
            .findFirst()
            .orElse(null);

        if (activeJob == null || (checkAccess && !allowed(activeJob, JobAction.READ))) {
            log.warn("Job with ID '{}' not found in loaded jobs list: {}", cleanId, jobs.stream().map(JobDTO::id).toList());
            return new StartOutcome.NotFound();
        } else if (checkAccess && !allowed(activeJob, JobAction.EXECUTE)) {
            log.warn("Job {} not started: the caller may see it but not run it.", activeJob.id());
            return new StartOutcome.Forbidden();
        } else {
            log.info("Found activeJob: {} [path: {}, outputConnector: {}]", activeJob.name(), activeJob.path(), activeJob.outputConnector());
            String executionId = "exec-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            JobRunRegistry.Reservation reservation;
            switch (runRegistry.reserve(activeJob.id(), executionId, callerKey(provenance))) {
                case JobRunRegistry.AlreadyRunning running -> {
                    log.warn("Job {} is already running [Exec ID: {}]; start rejected.", activeJob.id(), running.run().executionId());
                    return new StartOutcome.AlreadyRunning(running.run().executionId());
                }
                case JobRunRegistry.LimitReached limit -> {
                    log.warn("Job {} not started: {} runs are already in progress (opencrawling.jobs.max-concurrent-runs).", activeJob.id(), limit.limit());
                    return new StartOutcome.LimitReached(limit.limit(), false);
                }
                case JobRunRegistry.CallerLimitReached callerLimit -> {
                    log.warn("Job {} not started: its caller already has {} runs in progress (opencrawling.jobs.max-concurrent-runs-per-caller).", activeJob.id(), callerLimit.limit());
                    return new StartOutcome.LimitReached(callerLimit.limit(), true);
                }
                case JobRunRegistry.Reserved reserved -> reservation = reserved.reservation();
            }
            updateJobStatus(activeJob.id(), "Running");
            final PipelineMode mode = resolvePipelineMode(activeJob);
            RepositoryConnector resolvedConnector;
            OutputConnector resolvedOutputConnector;
            try {
                List<ConnectorController.ConnectorDTO> connectors =
                    controlPlaneStore.loadList("connectors.json", ConnectorController.ConnectorDTO.class, List.of());

                ConnectorController.ConnectorDTO connConfig = connectors.stream()
                    .filter(c -> c.name() != null && c.name().equalsIgnoreCase(activeJob.repositoryConnector()))
                    .findFirst()
                    .orElse(null);
                ConnectorController.ConnectorDTO outConfig = connectors.stream()
                    .filter(c -> c.name() != null && c.name().equalsIgnoreCase(activeJob.outputConnector()))
                    .findFirst()
                    .orElse(null);

                RepositoryConnectorFactory factory = connConfig != null ? findFactory(connConfig.className()) : null;
                if (factory != null) {
                    try {
                        resolvedConnector = factory.create(connConfig.configuration(), jobContext(activeJob));
                    } catch (IllegalArgumentException e) {
                        reservation.release();
                        log.error("Job {} rejected: invalid configuration: {}", activeJob.id(), e.getMessage(), e);
                        updateJobStatusAndStage(activeJob.id(), "Error", "Rejected: " + e.getMessage(), getActualDbDocCount());
                        return new StartOutcome.Rejected("Invalid configuration: " + e.getMessage());
                    }
                } else {
                    resolvedConnector = connectorFactory.repository(connConfig);
                }
                resolvedOutputConnector = connectorFactory.output(outConfig, mode);
            } catch (ConnectorResolutionException e) {
                reservation.release();
                log.error("Job {} rejected: {}", activeJob.id(), e.getMessage(), e);
                updateJobStatusAndStage(activeJob.id(), "Error", "Rejected: " + e.getMessage(), getActualDbDocCount());
                return new StartOutcome.Rejected(e.getMessage());
            }

            // Fail fast: the Apache Ozone and AWS S3 output connectors are dedicated to Migration Mode only
            if ((resolvedOutputConnector instanceof org.opencrawling.ozone.OzoneOutputConnector
                    || resolvedOutputConnector instanceof org.opencrawling.s3.S3OutputConnector) && !mode.isMigration()) {
                reservation.release();
                // Built for this job, it owns clients the run would have released.
                connectorFactory.release(resolvedOutputConnector);
                String connectorName = resolvedOutputConnector.getClass().getSimpleName();
                log.error("Job {} rejected: {} requires pipeline mode 'migration' but job resolved to '{}'", activeJob.id(), connectorName, mode.externalName());
                updateJobStatusAndStage(activeJob.id(), "Error", "Rejected: " + connectorName + " requires migration mode", getActualDbDocCount());
                return new StartOutcome.Rejected(connectorName + " requires pipeline mode 'migration'");
            }

            // With pre-embedding governance on, nothing may be embedded without passing the gate.
            if (jobOrchestrator.bypassesGovernance(resolvedOutputConnector, mode)) {
                reservation.release();
                // A per-job output (Qdrant, Vespa, …) owns clients the run would have released.
                connectorFactory.release(resolvedOutputConnector);
                log.error("Job {} rejected: {}", activeJob.id(), JobOrchestrator.GOVERNANCE_BYPASS);
                updateJobStatusAndStage(activeJob.id(), "Error", "Rejected: governance is on; run RAG jobs on the decoupled pipeline",
                        getActualDbDocCount());
                return new StartOutcome.Rejected("Governance is on: run RAG jobs on the decoupled pipeline");
            }
            
            final RepositoryConnector finalConnector = resolvedConnector;
            final OutputConnector finalOutputConnector = resolvedOutputConnector;
            final JobDTO finalActiveJob = activeJob;
            if (ExecutionProvenance.SOURCE_SCHEDULER.equals(provenance.triggerSource())) {
                activeScheduledExecutions.add(executionId);
            }

            log.info("Launching background Virtual Thread for job {} with OutputConnector: {} (pipeline mode: {})", activeJob.id(), finalOutputConnector.getName(), mode.externalName());
            
            // Execute real crawler inside virtual thread
            reservation.startJob(stopRequested -> {
                boolean succeeded = false;
                try {
                    log.info("Background Virtual Thread running [Exec ID: {}]. Path: {}, OutputConnector: {}", executionId, finalActiveJob.path(), finalOutputConnector.getName());
                    succeeded = jobOrchestrator.runJob(finalConnector, finalOutputConnector, finalActiveJob.path(), finalActiveJob.transformationConnector(), finalActiveJob.id(), finalActiveJob.name(), finalActiveJob.narrativization(), mode, executionId, stopRequested, provenance);
                } catch (Exception e) {
                    log.error("Background Virtual Thread failed: {}", e.getMessage(), e);
                } finally {
                    connectorFactory.release(finalOutputConnector);
                }
                // A stop interrupts this thread; clear it so the status updates below are not cut short.
                Thread.interrupted();
                long docCount = resolveExecutionDocCount(executionId, finalActiveJob.documents());
                if (stopRequested.getAsBoolean()) {
                    log.info("Job {} stopped [Exec ID: {}].", finalActiveJob.id(), executionId);
                    updateJobStatusAndStage(finalActiveJob.id(), "Finished", "Stopped", docCount);
                } else if (succeeded) {
                    log.info("Background Virtual Thread completed successfully!");
                    updateJobStatusAndStage(finalActiveJob.id(), "Finished", "Completed", docCount);
                } else {
                    updateJobStatusAndStage(finalActiveJob.id(), "Error", "Failed", docCount);
                }
                return succeeded;
            });
            return new StartOutcome.Started(activeJob.id(), executionId);
        }
    }

    @Operation(summary = "Stop a pipeline job", description = "Stops a running pipeline job: the crawl ends at the latest before its next document, "
            + "and the job is marked Finished with stage Stopped. A job with no run in this runtime (for example a saved 'Running' status left by a "
            + "restart) is marked Finished.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Stop requested; the job's status changes when its run has ended"),
        @ApiResponse(responseCode = "200", description = "The job had no run here; its status was set to Finished"),
        @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @PostMapping("/{id}/stop")
    public ResponseEntity<Void> stopJob(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String id) {
        log.info("Stopping job {}", id);
        JobDTO job = findJob(id);
        if (job == null || !allowed(job, JobAction.READ)) {
            return ResponseEntity.notFound().build();
        }
        if (!allowed(job, JobAction.EXECUTE)) {
            return ResponseEntity.status(403).build();
        }
        if (runRegistry.stop(job.id())) {
            return ResponseEntity.accepted().build();
        }
        updateJobStatus(id, "Finished");
        return ResponseEntity.ok().build();
    }

    /**
     * Stops one run of a job, with the same access rules as {@link #stopJob}: {@code 202} when the run was asked to
     * stop, {@code 404} when the job is not visible or that run is not running here (it ended, or another run of the
     * job replaced it), {@code 403} when the caller may not run the job.
     */
    public ResponseEntity<Void> stopRun(String jobId, String executionId) {
        JobDTO job = findJob(jobId);
        if (job == null || !allowed(job, JobAction.READ)) {
            return ResponseEntity.notFound().build();
        }
        if (!allowed(job, JobAction.EXECUTE)) {
            return ResponseEntity.status(403).build();
        }
        return runRegistry.stopExecution(job.id(), executionId)
                ? ResponseEntity.accepted().build() : ResponseEntity.notFound().build();
    }

    /**
     * Pauses one run of a job, with the same access rules as {@link #stopRun}: {@code 202} when the run will hold
     * before its next document, {@code 404} when the job is not visible or that run is not running.
     */
    public ResponseEntity<Void> pauseRun(String jobId, String executionId) {
        return changeRun(jobId, executionId, true);
    }

    /** Resumes a paused run of a job, with the same access rules and answers as {@link #pauseRun}. */
    public ResponseEntity<Void> resumeRun(String jobId, String executionId) {
        return changeRun(jobId, executionId, false);
    }

    private ResponseEntity<Void> changeRun(String jobId, String executionId, boolean pause) {
        JobDTO job = findJob(jobId);
        if (job == null || !allowed(job, JobAction.READ)) {
            return dynamicRun(jobId) ? changeDynamicRun(jobId, executionId, pause) : ResponseEntity.notFound().build();
        }
        if (!allowed(job, JobAction.EXECUTE)) {
            return ResponseEntity.status(403).build();
        }
        boolean changed = pause ? runRegistry.pause(job.id(), executionId) : runRegistry.resume(job.id(), executionId);
        if (!changed) {
            return ResponseEntity.notFound().build();
        }
        updateJobStatus(job.id(), pause ? "Paused" : "Running");
        return ResponseEntity.accepted().build();
    }

    /** A run of a job outside the job list (a dynamic job): only admins may act on it here, as {@link #getRuns} lists. */
    private boolean dynamicRun(String jobId) {
        return findJob(jobId) == null && (!authorizationOn() || authorization.isAdmin());
    }

    private ResponseEntity<Void> changeDynamicRun(String jobId, String executionId, boolean pause) {
        boolean changed = pause ? runRegistry.pause(jobId, executionId) : runRegistry.resume(jobId, executionId);
        return changed ? ResponseEntity.accepted().build() : ResponseEntity.notFound().build();
    }

    /**
     * A run, active or ended, while it is kept (#315), with its history; empty when it is unknown or the caller may
     * not see its job.
     */
    public Optional<RunHistory> runHistory(String executionId) {
        return runRegistry.execution(executionId)
                .filter(run -> {
                    JobDTO job = findJob(run.jobId());
                    return job != null ? allowed(job, JobAction.READ) : !authorizationOn() || authorization.isAdmin();
                })
                .map(run -> new RunHistory(run, runRegistry.steps(executionId)));
    }

    /**
     * The runs kept (#316), active and ended, of the jobs the caller may see, newest first; runs of jobs no longer in
     * the list are listed for admins. Dynamic jobs' runs are left out: they are listed with their dynamic job.
     */
    public List<JobRunRegistry.RunInfo> keptRuns(String jobId) {
        Map<String, JobDTO> byId = jobs().stream().filter(job -> job.id() != null)
                .collect(java.util.stream.Collectors.toMap(job -> job.id().trim(), job -> job, (a, b) -> a));
        return runRegistry.list(jobId).stream()
                .filter(run -> !org.opencrawling.runtime.dynamicjob.DynamicJobService.isDynamicJob(run.jobId()))
                .filter(run -> {
                    JobDTO job = run.jobId() == null ? null : byId.get(run.jobId().trim());
                    return job != null ? allowed(job, JobAction.READ) : !authorizationOn() || authorization.isAdmin();
                })
                .toList();
    }

    /** The job's name, or {@code null} when it is not in the list. */
    public String jobName(String jobId) {
        JobDTO job = findJob(jobId);
        return job != null ? job.name() : null;
    }

    /** A run and its history. */
    public record RunHistory(JobRunRegistry.RunInfo run, List<org.opencrawling.runtime.orchestrator.RunStore.RunStep> steps) {}

    @Operation(summary = "List running jobs", description = "The job runs in progress in this runtime, oldest first.")
    @ApiResponse(responseCode = "200", description = "Runs in progress")
    @GetMapping("/runs")
    public List<JobRunRegistry.RunInfo> getRuns() {
        if (!authorizationOn()) {
            return runRegistry.runs();
        }
        // Runs of jobs outside the job list (dynamic jobs) are listed for admins; their callers use the dynamic job API.
        return runRegistry.runs().stream().filter(run -> {
            JobDTO job = findJob(run.jobId());
            return job != null ? allowed(job, JobAction.READ) : authorization.isAdmin();
        }).toList();
    }

    @Operation(summary = "Get a job's run", description = "The job's run in progress in this runtime: execution id, start time and whether a stop was requested.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The job is running"),
        @ApiResponse(responseCode = "404", description = "The job has no run in progress here")
    })
    @GetMapping("/{id}/run")
    public ResponseEntity<JobRunRegistry.RunInfo> getRun(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String id) {
        JobDTO job = findJob(id);
        if (job == null || !allowed(job, JobAction.READ)) {
            return ResponseEntity.notFound().build();
        }
        return runRegistry.run(job.id()).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    private boolean authorizationOn() {
        return authorization != null && authorization.isEnabled();
    }

    private boolean allowed(JobDTO job, JobAction action) {
        return authorization == null || authorization.can(job.access(), action);
    }

    /**
     * The access a new job gets. With authorization on, the caller owns it and it belongs to the caller's tenant; an
     * admin may name another owner or tenant. The allowed users and groups are taken as sent.
     */
    private JobAccess accessForNewJob(JobAccess requested) {
        if (!authorizationOn()) {
            return requested;
        }
        Caller caller = authorization.currentCaller().orElseThrow();
        JobAccess base = requested != null ? requested : new JobAccess(null, null, null, null);
        if (authorization.isAdmin(caller)) {
            return base.withOwnerAndTenant(base.owner() != null ? base.owner() : caller.principal(),
                    base.tenantId() != null ? base.tenantId() : caller.tenant());
        }
        return base.withOwnerAndTenant(caller.principal(), caller.tenant());
    }

    /**
     * The access after an edit. Without an access block in the request it stays as it was. An owner may change the
     * allowed users and groups but not the owner or tenant; an admin may change everything, for example to assign a
     * job created before authorization was on.
     */
    private JobAccess accessForUpdate(JobAccess existing, JobAccess requested) {
        if (requested == null) {
            return existing;
        }
        if (!authorizationOn()) {
            return requested;
        }
        Caller caller = authorization.currentCaller().orElseThrow();
        if (authorization.isAdmin(caller)) {
            return requested.withOwnerAndTenant(
                    requested.owner() != null ? requested.owner() : existing != null ? existing.owner() : null,
                    requested.tenantId() != null ? requested.tenantId() : existing != null ? existing.tenantId() : null);
        }
        return requested.withOwnerAndTenant(existing != null ? existing.owner() : null,
                existing != null ? existing.tenantId() : null);
    }

    private String newJobId() {
        long candidate = System.currentTimeMillis();
        while (findJob(String.valueOf(candidate)) != null) {
            candidate++;
        }
        return String.valueOf(candidate);
    }

    private JobDTO findJob(String id) {
        String cleanId = id != null ? id.trim() : "";
        return jobs().stream()
            .filter(j -> j.id() != null && j.id().trim().equalsIgnoreCase(cleanId))
            .findFirst()
            .orElse(null);
    }

    @Operation(summary = "Pause a pipeline job", description = "Pauses the job's run: it holds before its next document until it is resumed or stopped. A job with no run is marked Paused.")
    @ApiResponse(responseCode = "200", description = "Job pause signal processed")
    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pauseJob(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String id) {
        log.info("Pausing job {}", id);
        if (authorizationOn()) {
            JobDTO job = findJobById(id);
            if (job == null || !allowed(job, JobAction.READ)) {
                return ResponseEntity.notFound().build();
            }
            if (!allowed(job, JobAction.EXECUTE)) {
                return ResponseEntity.status(403).build();
            }
        }
        JobDTO job = findJobById(id);
        Optional<JobRunRegistry.RunInfo> run = job == null ? Optional.empty() : runRegistry.run(job.id());
        if (run.isPresent() && runRegistry.pause(job.id(), run.get().executionId())) {
            log.info("Job {} run [Exec ID: {}] paused: it holds before its next document.", job.id(), run.get().executionId());
        }
        updateJobStatus(id, "Paused");
        return ResponseEntity.ok().build();
    }

    /**
     * Resumes the job's paused run. {@code 200} when it runs again, {@code 409} when the job has no run to resume (it
     * can be started), {@code 404}/{@code 403} as for {@link #pauseJob}.
     */
    public ResponseEntity<Void> resumeJob(String id) {
        JobDTO job = findJobById(id);
        if (job == null || !allowed(job, JobAction.READ)) {
            return ResponseEntity.notFound().build();
        }
        if (!allowed(job, JobAction.EXECUTE)) {
            return ResponseEntity.status(403).build();
        }
        Optional<JobRunRegistry.RunInfo> run = runRegistry.run(job.id());
        if (run.isEmpty() || !runRegistry.resume(job.id(), run.get().executionId())) {
            return ResponseEntity.status(409).build();
        }
        log.info("Job {} run [Exec ID: {}] resumed.", job.id(), run.get().executionId());
        updateJobStatus(job.id(), "Running");
        return ResponseEntity.ok().build();
    }

    /**
     * Resolves the effective pipeline mode for a job: the per-job override wins, otherwise the
     * global {@code opencrawling.pipeline.mode} default applies.
     */
    public PipelineMode resolvePipelineMode(JobDTO job) {
        return resolvePipelineMode(job != null ? job.id() : null, job != null ? job.pipelineMode() : null, pipelineProperties);
    }

    static PipelineMode resolvePipelineMode(String jobId, String jobPipelineMode, PipelineProperties globalProperties) {
        PipelineMode jobMode = null;
        try {
            jobMode = PipelineMode.parseStrict(jobPipelineMode);
        } catch (IllegalArgumentException e) {
            log.warn("Job {} has an invalid pipeline mode '{}', falling back to the global default", jobId, jobPipelineMode);
        }
        if (jobMode != null) {
            return jobMode;
        }
        return globalProperties != null ? globalProperties.getMode() : PipelineMode.RAG;
    }

    // Every change of the job list holds the store's lock (locked), the one the Platform API takes around a conditional
    // change (If-Match), and starts from the saved list, so no writer, here or in another runtime, can revert a saved
    // definition or overwrite another job.
    private void updateJobStatus(String id, String status) {
        controlPlaneStore.locked(() -> {
            updateJobStatusLocked(id, status);
            return null;
        });
    }

    private void updateJobStatusLocked(String id, String status) {
        List<JobDTO> jobs = jobs();
        String cleanId = id != null ? id.trim() : "";
        for (int i = 0; i < jobs.size(); i++) {
            JobDTO job = jobs.get(i);
            if (job.id() != null && job.id().trim().equalsIgnoreCase(cleanId)) {
                String lastRun = status.equals("Running") ? LocalDateTime.now().format(formatter) : job.lastRun();
                long docCount = job.documents();
                String stage = "Idle";
                if (status.equals("Running")) {
                    stage = "Scanning";
                    log.info("Job '{}' [ID: {}] status updated to Running. Stage: Scanning. Root path: {}", job.name(), job.id(), job.path());
                } else if (status.equals("Paused")) {
                    stage = "Paused";
                    log.warn("Job '{}' [ID: {}] status updated to Paused.", job.name(), job.id());
                } else if (status.equals("Finished")) {
                    stage = "Completed";
                    log.info("Job '{}' [ID: {}] status updated to Finished. Stage: Completed.", job.name(), job.id());
                } else if (status.equals("Error")) {
                    stage = "Failed";
                    log.error("Job '{}' [ID: {}] status updated to Error.", job.name(), job.id());
                }
                jobs.set(i, new JobDTO(
                    job.id(),
                    job.name(),
                    job.repositoryConnector(),
                    job.outputConnector(),
                    job.authorityConnector(),
                    job.path(),
                    status,
                    stage,
                    docCount,
                    lastRun,
                    job.transformationConnector(),
                    job.narrativization(),
                    job.pipelineMode(),
                    job.crawlSpec(),
                    job.access(),
                    job.schedule()
                ));
                break;
            }
        }
        controlPlaneStore.save("jobs.json", jobs);
    }

    private void updateJobStatusAndStage(String id, String status, String stage, long docCount) {
        controlPlaneStore.locked(() -> {
            updateJobStatusAndStageLocked(id, status, stage, docCount);
            return null;
        });
    }

    private void updateJobStatusAndStageLocked(String id, String status, String stage, long docCount) {
        List<JobDTO> jobs = jobs();
        for (int i = 0; i < jobs.size(); i++) {
            JobDTO job = jobs.get(i);
            if (job.id().equals(id)) {
                jobs.set(i, new JobDTO(
                    job.id(),
                    job.name(),
                    job.repositoryConnector(),
                    job.outputConnector(),
                    job.authorityConnector(),
                    job.path(),
                    status,
                    stage,
                    docCount,
                    LocalDateTime.now().format(formatter),
                    job.transformationConnector(),
                    job.narrativization(),
                    job.pipelineMode(),
                    job.crawlSpec(),
                    job.access(),
                    job.schedule()
                ));
                break;
            }
        }
        controlPlaneStore.save("jobs.json", jobs);
    }

    public void recordPushExecution(String id, long pushedDocs, boolean success) {
        if (id == null || id.isBlank()) return;
        controlPlaneStore.locked(() -> {
            recordPushExecutionLocked(id, pushedDocs, success);
            return null;
        });
    }

    private void recordPushExecutionLocked(String id, long pushedDocs, boolean success) {
        List<JobDTO> jobs = jobs();
        for (int i = 0; i < jobs.size(); i++) {
            JobDTO job = jobs.get(i);
            if (job.id().equals(id)) {
                long updatedDocs = Math.max(0L, job.documents()) + Math.max(0L, pushedDocs);
                jobs.set(i, new JobDTO(
                    job.id(),
                    job.name(),
                    job.repositoryConnector(),
                    job.outputConnector(),
                    job.authorityConnector(),
                    job.path(),
                    success ? "Finished" : "Error",
                    success ? "Completed" : "Failed",
                    updatedDocs,
                    LocalDateTime.now().format(formatter),
                    job.transformationConnector(),
                    job.narrativization(),
                    job.pipelineMode(),
                    job.crawlSpec(),
                    job.access(),
                    job.schedule()
                ));
                controlPlaneStore.save("jobs.json", jobs);
                break;
            }
        }
    }

    private long resolveExecutionDocCount(String executionId, long previousCount) {
        if (ledgerAccumulator != null && executionId != null) {
            org.opencrawling.core.event.JobExecutionCompletedEvent event = ledgerAccumulator.getCompletedEvent(executionId);
            if (event != null) {
                return event.totalDocuments();
            }
        }
        return previousCount;
    }

    private long getActualDbDocCount() {
        try {
            Long count = jdbcTemplate.queryForObject(
                "SELECT (SELECT count(*) FROM vector_store) + " +
                "(SELECT count(*) FROM vector_store_1024) + " +
                "(SELECT count(*) FROM vector_store_768) + " +
                "(SELECT count(*) FROM vector_store_384)", 
                Long.class
            );
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("Failed to query pgvector doc count: {}", e.getMessage());
            return 0;
        }
    }

    @Schema(description = "Auto-Narrativization Copilot transformation configuration")
    public static record NarrativizationConfig(
        @Schema(description = "Whether LLM-driven narrativization is active for this job", example = "false")
        boolean enabled,
        @Schema(description = "Mustache narrative prompt template", example = "# {{title}}\n{{summary}}")
        String template,
        @Schema(description = "Connector type hint for template schema extraction", example = "FileSystem")
        String connectorType
    ) {
        public static NarrativizationConfig disabled() {
            return new NarrativizationConfig(false, null, null);
        }
    }

    /** Starts a job with no correlation id. */
    public ResponseEntity<Void> startJob(String id) {
        return startJob(id, null);
    }

    void setCallerResolver(CallerResolver callerResolver) {
        this.callerResolver = callerResolver;
    }

    void setAuthorization(ManagementAuthorization authorization) {
        this.authorization = authorization;
    }

    /**
     * The key the per-caller cap counts runs under: the verified party that starts the run (for a delegated token,
     * the acting agent), qualified by its tenant when it has one. {@code null} for a run with no verified caller.
     */
    public static String callerKey(ExecutionProvenance provenance) {
        if (provenance == null || !provenance.callerVerified() || provenance.triggeredBy() == null) {
            return null;
        }
        return provenance.callerTenant() != null
                ? provenance.callerTenant() + "/" + provenance.triggeredBy()
                : provenance.triggeredBy();
    }

    /** Who is starting a run through the API: the verified caller of this request, if there is one. */
    private ExecutionProvenance provenance(String correlationId) {
        Optional<Caller> caller = callerResolver != null ? callerResolver.verifiedCaller() : Optional.empty();
        return caller
                .map(c -> ExecutionProvenance.verified(c.principal(),
                        c.attributes().get(JwtCallerMapper.ACTOR) instanceof String actor ? actor : null,
                        c.groups(), c.tenant(), ExecutionProvenance.SOURCE_API, correlationId))
                .orElseGet(() -> ExecutionProvenance.anonymous(ExecutionProvenance.SOURCE_API, correlationId));
    }

    /**
     * Validates a job against its repository connector's factory, if it has one.
     *
     * @return the validation error, or null if the job is valid
     */
    private String validate(JobDTO job) {
        ConnectorController.ConnectorDTO connConfig = controlPlaneStore
            .loadList("connectors.json", ConnectorController.ConnectorDTO.class, List.of()).stream()
            .filter(c -> c.name().equalsIgnoreCase(job.repositoryConnector()))
            .findFirst()
            .orElse(null);
        RepositoryConnectorFactory factory = connConfig != null ? findFactory(connConfig.className()) : null;
        if (factory == null) {
            return null;
        }
        try {
            factory.create(connConfig.configuration(), jobContext(job));
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static RepositoryConnectorFactory findFactory(String className) {
        if (className == null) {
            return null;
        }
        for (RepositoryConnectorFactory factory : ServiceLoader.load(RepositoryConnectorFactory.class)) {
            if (factory.className().equals(className)) {
                return factory;
            }
        }
        return null;
    }

    private JobContext jobContext(JobDTO job) {
        return new JobContext(job.id(), job.path(), job.crawlSpec(),
            environment != null ? environment::getProperty : key -> null);
    }

    /**
     * Atomically updates the schedule definition on an existing job (bypassing user-thread RBAC checks when invoked
     * for internal transitions such as circuit-breaker auto-pause). Must be called inside or synchronized through the
     * control-plane lock.
     */
    public Optional<JobDTO> updateScheduleDefinition(String jobId, JobScheduleConfig nextSchedule) {
        return controlPlaneStore.locked(() -> {
            List<JobDTO> list = jobs();
            for (int i = 0; i < list.size(); i++) {
                JobDTO existing = list.get(i);
                if (existing.id().equals(jobId)) {
                    JobDTO updated = existing.withSchedule(nextSchedule);
                    list.set(i, updated);
                    controlPlaneStore.save("jobs.json", list);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        });
    }

    private SchedulerProperties effectiveSchedulerProperties() {
        return schedulerProperties != null ? schedulerProperties : new SchedulerProperties();
    }

    private void syncScheduleStateLocked(String jobId, JobScheduleConfig previous, JobScheduleConfig current) {
        if (scheduleStore == null) {
            return;
        }
        if (current == null) {
            scheduleStore.deleteState(jobId);
            return;
        }
        if (Objects.equals(previous, current) && scheduleStore.getState(jobId).isPresent()) {
            return;
        }
        Instant now = scheduleClock.instant();
        JobScheduleState prev = scheduleStore.getState(jobId).orElse(null);
        boolean configReset = previous == null
                || !Objects.equals(previous.cron(), current.cron())
                || !Objects.equals(previous.timezone(), current.timezone())
                || !Objects.equals(previous.startAt(), current.startAt())
                || !Objects.equals(previous.endAt(), current.endAt())
                || (!previous.enabled() && current.enabled());
        Instant nextFireAt = CronScheduleParser.computeNextFireAt(
                current, effectiveSchedulerProperties().getDefaultTimezone(), now).orElse(null);
        int consecutiveFailures = configReset || prev == null ? 0 : prev.consecutiveFailures();
        boolean coalescePending = (!configReset && prev != null) && prev.coalescePending() && current.enabled();
        JobScheduleState synced = new JobScheduleState(
                jobId,
                current.enabled(),
                nextFireAt,
                prev != null ? prev.lastFiredAt() : null,
                prev != null ? prev.lastExecutionId() : null,
                configReset ? null : (prev != null ? prev.lastOutcome() : null),
                configReset ? null : (prev != null ? prev.lastDetail() : null),
                coalescePending,
                consecutiveFailures,
                now);
        scheduleStore.saveState(synced);
    }

    @Schema(description = "Pipeline job definition and execution status")
    public static record JobDTO(
        @Schema(description = "Unique job identifier", example = "1")
        String id,
        @Schema(description = "Human-readable job name", example = "Default_Job")
        String name,
        @Schema(description = "Name of the configured repository connector", example = "FileSystem_Local")
        String repositoryConnector,
        @Schema(description = "Name of the configured output connector", example = "PGVector_Output")
        String outputConnector,
        @Schema(description = "Name of authority connector for ACL verification", example = "")
        String authorityConnector,
        @Schema(description = "Root path or target repository resource", example = "/data")
        String path,
        @Schema(description = "Job execution state (Ready, Running, Paused, Finished, Error)", example = "Ready")
        String status,
        @Schema(description = "Current processing stage (Idle, Scanning, Extracting, Embedding, Writing, Completed, Failed)", example = "Idle")
        String currentStage,
        @Schema(description = "Total number of documents processed", example = "0")
        long documents,
        @Schema(description = "Timestamp of the last job execution", example = "N/A")
        String lastRun,
        @Schema(description = "Name of the transformation connector or embedding provider", example = "Ollama_Embedding_Default")
        String transformationConnector,
        @Schema(description = "Auto-Narrativization Copilot configuration")
        NarrativizationConfig narrativization,
        @Schema(description = "Execution pipeline mode (RAG or MIGRATION)", example = "RAG")
        String pipelineMode,
        @Schema(description = "Connector-specific job settings, such as the web crawler's seeds and limits")
        Map<String, Object> crawlSpec,
        @Schema(description = "Who may see and run the job when management authorization is on; null for jobs created before it")
        JobAccess access,
        @Schema(description = "Optional recurring schedule configuration (#197)")
        JobScheduleConfig schedule
    ) {
        public JobDTO {
            if (narrativization == null) narrativization = NarrativizationConfig.disabled();
        }

        public JobDTO(
            String id,
            String name,
            String repositoryConnector,
            String outputConnector,
            String authorityConnector,
            String path,
            String status,
            String currentStage,
            long documents,
            String lastRun,
            String transformationConnector,
            NarrativizationConfig narrativization
        ) {
            this(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, null, null, null, null);
        }

        public JobDTO(
            String id,
            String name,
            String repositoryConnector,
            String outputConnector,
            String authorityConnector,
            String path,
            String status,
            String currentStage,
            long documents,
            String lastRun,
            String transformationConnector,
            NarrativizationConfig narrativization,
            String pipelineMode
        ) {
            this(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, pipelineMode, null, null, null);
        }

        /** The fourteen-field form used before jobs carried an access block. */
        public JobDTO(
            String id,
            String name,
            String repositoryConnector,
            String outputConnector,
            String authorityConnector,
            String path,
            String status,
            String currentStage,
            long documents,
            String lastRun,
            String transformationConnector,
            NarrativizationConfig narrativization,
            String pipelineMode,
            Map<String, Object> crawlSpec
        ) {
            this(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, pipelineMode, crawlSpec, null, null);
        }

        /** The fifteen-field form used before jobs carried a schedule block. */
        public JobDTO(
            String id,
            String name,
            String repositoryConnector,
            String outputConnector,
            String authorityConnector,
            String path,
            String status,
            String currentStage,
            long documents,
            String lastRun,
            String transformationConnector,
            NarrativizationConfig narrativization,
            String pipelineMode,
            Map<String, Object> crawlSpec,
            JobAccess access
        ) {
            this(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, pipelineMode, crawlSpec, access, null);
        }

        public JobDTO withPipelineMode(String mode) {
            return new JobDTO(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, mode, crawlSpec, access, schedule);
        }

        public JobDTO withAccess(JobAccess newAccess) {
            return new JobDTO(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, pipelineMode, crawlSpec, newAccess, schedule);
        }

        public JobDTO withSchedule(JobScheduleConfig newSchedule) {
            return new JobDTO(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, pipelineMode, crawlSpec, access, newSchedule);
        }
    }
}
