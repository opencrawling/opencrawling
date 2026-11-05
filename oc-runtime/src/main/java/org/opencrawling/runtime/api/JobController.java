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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

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
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.connector.ConnectorResolutionException;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
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
    private final List<JobDTO> jobs;
    private final JobOrchestrator jobOrchestrator;
    private final ConnectorFactory connectorFactory;
    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private PipelineProperties pipelineProperties;

    @Autowired(required = false)
    private Environment environment;

    @Autowired(required = false)
    private org.opencrawling.runtime.reporting.JobLedgerAccumulator ledgerAccumulator;

    @Autowired
    public JobController(
            JobOrchestrator jobOrchestrator,
            ConnectorFactory connectorFactory,
            JdbcTemplate jdbcTemplate) {
        this.jobOrchestrator = jobOrchestrator;
        this.connectorFactory = connectorFactory;
        this.jdbcTemplate = jdbcTemplate;
        
        // Initial defaults
        List<JobDTO> defaults = new ArrayList<>();
        defaults.add(new JobDTO("1", "Default_Job", "FileSystem_Local", "PGVector_Output", "", "/data", "Ready", "Idle", 0, "N/A", "Ollama_Embedding_Default", null));
        
        // Load persisted list
        this.jobs = new CopyOnWriteArrayList<>(PersistenceHelper.loadList("jobs.json", JobDTO.class, defaults));
    }

    @Operation(summary = "List all pipeline jobs", description = "Returns a list of all configured crawling pipeline jobs and their latest execution state.")
    @ApiResponse(responseCode = "200", description = "List of configured jobs successfully retrieved")
    @GetMapping
    public List<JobDTO> getAllJobs() {
        return jobs;
    }

    @Operation(summary = "Get pipeline job by ID", description = "Retrieves details and status of a specific crawling job by its identifier.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Job details successfully retrieved"),
        @ApiResponse(responseCode = "404", description = "Job not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<JobDTO> getJob(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String id) {
        return jobs.stream()
                .filter(j -> j.id().equals(id))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create or update a pipeline job", description = "Creates a new crawling job or updates an existing job definition.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Job created or updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid job configuration or unrecognized pipeline mode")
    })
    @PostMapping
    public ResponseEntity<?> saveJob(@RequestBody JobDTO job) {
        log.info("Saving job: {}", job.name());
        try {
            PipelineMode requestedMode = PipelineMode.parseStrict(job.pipelineMode());
            job = job.withPipelineMode(requestedMode != null ? requestedMode.externalName() : null);
        } catch (IllegalArgumentException e) {
            log.warn("Rejecting job '{}': {}", job.name(), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        String validationError = validate(job);
        if (validationError != null) {
            log.warn("Rejecting job '{}': {}", job.name(), validationError);
            return ResponseEntity.badRequest().body(Map.of("error", validationError));
        }
        if (job.id() == null || job.id().isBlank() || job.id().equals("new")) {
            // Generate unique ID based on timestamp
            String newId = String.valueOf(System.currentTimeMillis());
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
                job.crawlSpec()
            );
            jobs.add(newJob);
        } else {
            // Edit/Update existing job
            for (int i = 0; i < jobs.size(); i++) {
                if (jobs.get(i).id().equals(job.id())) {
                    JobDTO existing = jobs.get(i);
                    jobs.set(i, new JobDTO(
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
                        job.transformationConnector() != null ? job.transformationConnector() : existing.transformationConnector(),
                        job.narrativization() != null ? job.narrativization() : existing.narrativization(),
                        job.pipelineMode() != null ? job.pipelineMode() : existing.pipelineMode(),
                        job.crawlSpec() != null ? job.crawlSpec() : existing.crawlSpec()
                    ));
                    break;
                }
            }
        }
        PersistenceHelper.save("jobs.json", jobs);
        return ResponseEntity.status(201).build();
    }

    @Operation(summary = "Delete a pipeline job", description = "Removes a pipeline job definition by its identifier.")
    @ApiResponse(responseCode = "200", description = "Job successfully deleted")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteJob(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String id) {
        log.info("Deleting job: {}", id);
        jobs.removeIf(j -> j.id().equals(id));
        PersistenceHelper.save("jobs.json", jobs);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Start a pipeline job", description = "Initiates asynchronous crawling and ingestion execution for the specified job on a Java 25 Virtual Thread.")
    @ApiResponse(responseCode = "200", description = "Job start signal accepted and initiated")
    @PostMapping("/{id}/start")
    public ResponseEntity<Void> startJob(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String id) {
        log.info("Starting job {}", id);
        updateJobStatus(id, "Running");

        final String cleanId = id != null ? id.trim() : "";

        // Find the job to get parameters
        JobDTO activeJob = jobs.stream()
            .filter(j -> j.id() != null && j.id().trim().equalsIgnoreCase(cleanId))
            .findFirst()
            .orElse(null);
            
        if (activeJob == null) {
            log.warn("Job with ID '{}' not found in loaded jobs list: {}", cleanId, jobs.stream().map(JobDTO::id).toList());
        } else {
            log.info("Found activeJob: {} [path: {}, outputConnector: {}]", activeJob.name(), activeJob.path(), activeJob.outputConnector());
            final PipelineMode mode = resolvePipelineMode(activeJob);
            RepositoryConnector resolvedConnector;
            OutputConnector resolvedOutputConnector;
            try {
                List<ConnectorController.ConnectorDTO> connectors =
                    PersistenceHelper.loadList("connectors.json", ConnectorController.ConnectorDTO.class, List.of());

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
                        log.error("Job {} rejected: invalid configuration: {}", id, e.getMessage(), e);
                        updateJobStatusAndStage(id, "Error", "Rejected: " + e.getMessage(), getActualDbDocCount());
                        return ResponseEntity.status(409).build();
                    }
                } else {
                    resolvedConnector = connectorFactory.repository(connConfig);
                }
                resolvedOutputConnector = connectorFactory.output(outConfig, mode);
            } catch (ConnectorResolutionException e) {
                log.error("Job {} rejected: {}", id, e.getMessage(), e);
                updateJobStatusAndStage(id, "Error", "Rejected: " + e.getMessage(), getActualDbDocCount());
                return ResponseEntity.status(409).build();
            }

            // Fail fast: the Apache Ozone and AWS S3 output connectors are dedicated to Migration Mode only
            if ((resolvedOutputConnector instanceof org.opencrawling.ozone.OzoneOutputConnector
                    || resolvedOutputConnector instanceof org.opencrawling.s3.S3OutputConnector) && !mode.isMigration()) {
                String connectorName = resolvedOutputConnector.getClass().getSimpleName();
                log.error("Job {} rejected: {} requires pipeline mode 'migration' but job resolved to '{}'", id, connectorName, mode.externalName());
                updateJobStatusAndStage(id, "Error", "Rejected: " + connectorName + " requires migration mode", getActualDbDocCount());
                return ResponseEntity.status(409).build();
            }
            
            final RepositoryConnector finalConnector = resolvedConnector;
            final OutputConnector finalOutputConnector = resolvedOutputConnector;
            final JobDTO finalActiveJob = activeJob;

            log.info("Launching background Virtual Thread for job {} with OutputConnector: {} (pipeline mode: {})", id, finalOutputConnector.getName(), mode.externalName());
            
            // Execute real crawler inside virtual thread
            Thread.ofVirtual().start(() -> {
                String executionId = "exec-" + java.util.UUID.randomUUID().toString().substring(0, 8);
                try {
                    log.info("Background Virtual Thread running [Exec ID: {}]. Path: {}, OutputConnector: {}", executionId, finalActiveJob.path(), finalOutputConnector.getName());
                    jobOrchestrator.runJob(finalConnector, finalOutputConnector, finalActiveJob.path(), finalActiveJob.transformationConnector(), finalActiveJob.id(), finalActiveJob.name(), finalActiveJob.narrativization(), mode, executionId);
                    log.info("Background Virtual Thread completed successfully!");
                    long docCount = resolveExecutionDocCount(executionId, finalActiveJob.documents());
                    updateJobStatusAndStage(id, "Finished", "Completed", docCount);
                } catch (Exception e) {
                    log.error("Background Virtual Thread failed: {}", e.getMessage(), e);
                    long docCount = resolveExecutionDocCount(executionId, finalActiveJob.documents());
                    updateJobStatusAndStage(id, "Error", "Failed", docCount);
                }
            });
        }

        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Stop a pipeline job", description = "Signals a running pipeline job to terminate execution gracefully.")
    @ApiResponse(responseCode = "200", description = "Job stop signal processed")
    @PostMapping("/{id}/stop")
    public ResponseEntity<Void> stopJob(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String id) {
        log.info("Stopping job {}", id);
        updateJobStatus(id, "Finished");
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Pause a pipeline job", description = "Signals a running pipeline job to pause execution.")
    @ApiResponse(responseCode = "200", description = "Job pause signal processed")
    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pauseJob(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String id) {
        log.info("Pausing job {}", id);
        updateJobStatus(id, "Paused");
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

    private void updateJobStatus(String id, String status) {
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
                    job.crawlSpec()
                ));
                break;
            }
        }
        PersistenceHelper.save("jobs.json", jobs);
    }

    private void updateJobStatusAndStage(String id, String status, String stage, long docCount) {
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
                    job.crawlSpec()
                ));
                break;
            }
        }
        PersistenceHelper.save("jobs.json", jobs);
    }

    public void recordPushExecution(String id, long pushedDocs, boolean success) {
        if (id == null || id.isBlank()) return;
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
                    job.crawlSpec()
                ));
                PersistenceHelper.save("jobs.json", jobs);
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

    /**
     * Validates a job against its repository connector's factory, if it has one.
     *
     * @return the validation error, or null if the job is valid
     */
    private String validate(JobDTO job) {
        ConnectorController.ConnectorDTO connConfig = PersistenceHelper
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
        Map<String, Object> crawlSpec
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
            this(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, null, null);
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
            this(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, pipelineMode, null);
        }

        public JobDTO withPipelineMode(String mode) {
            return new JobDTO(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, mode, crawlSpec);
        }
    }
}
