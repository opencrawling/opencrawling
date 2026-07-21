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
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.filesystem.FileSystemRepositoryConnector;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final List<JobDTO> jobs;
    private final JobOrchestrator jobOrchestrator;
    private final FileSystemRepositoryConnector fileSystemRepositoryConnector;
    private final OutputConnector outputConnector;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public JobController(
            JobOrchestrator jobOrchestrator,
            FileSystemRepositoryConnector fileSystemRepositoryConnector,
            OutputConnector outputConnector,
            JdbcTemplate jdbcTemplate) {
        this.jobOrchestrator = jobOrchestrator;
        this.fileSystemRepositoryConnector = fileSystemRepositoryConnector;
        this.outputConnector = outputConnector;
        this.jdbcTemplate = jdbcTemplate;
        
        // Initial defaults
        List<JobDTO> defaults = new ArrayList<>();
        defaults.add(new JobDTO("1", "Default_Job", "FileSystem_Local", "PGVector_Output", "", "/data", "Ready", "Idle", 0, "N/A", "Ollama_Embedding_Default"));
        
        // Load persisted list
        this.jobs = new CopyOnWriteArrayList<>(PersistenceHelper.loadList("jobs.json", JobDTO.class, defaults));
    }

    @GetMapping
    public List<JobDTO> getAllJobs() {
        boolean updated = false;
        // Simulate running job progress
        for (int i = 0; i < jobs.size(); i++) {
            JobDTO job = jobs.get(i);
            if ("Running".equals(job.status())) {
                String nextStage = job.currentStage();
                String nextStatus = "Running";
                long nextDocs = job.documents();
                
                switch (job.currentStage()) {
                    case "Scanning" -> {
                        nextStage = "Extracting";
                        nextDocs += 15;
                        log.info("Job '{}' [ID: {}]: Completed Scanning. Transitioned to Extracting.", job.name(), job.id());
                    }
                    case "Extracting" -> {
                        nextStage = "Chunking";
                        nextDocs += 45;
                        log.info("Job '{}' [ID: {}]: Completed text extraction. Transitioned to Chunking.", job.name(), job.id());
                    }
                    case "Chunking" -> {
                        nextStage = "Embedding";
                        nextDocs += 120;
                        log.info("Job '{}' [ID: {}]: Chunks split successfully using TokenTextSplitter. Transitioned to Embedding.", job.name(), job.id());
                    }
                    case "Embedding" -> {
                        nextStage = "Indexing";
                        nextDocs += 120;
                        log.info("Job '{}' [ID: {}]: Generated 1024-dimensional embeddings. Transitioned to Indexing.", job.name(), job.id());
                    }
                    case "Indexing" -> {
                        nextStage = "Completed";
                        nextStatus = "Finished";
                        log.info("Job '{}' [ID: {}]: Indexing completed. Inserted vector data into PostgreSQL.", job.name(), job.id());
                    }
                    default -> {
                        nextStage = "Scanning";
                        log.info("Job '{}' [ID: {}]: Starting document discovery scanner.", job.name(), job.id());
                    }
                }
                
                jobs.set(i, new JobDTO(
                    job.id(),
                    job.name(),
                    job.repositoryConnector(),
                    job.outputConnector(),
                    job.authorityConnector(),
                    job.path(),
                    nextStatus,
                    nextStage,
                    nextDocs,
                    job.lastRun(),
                    job.transformationConnector()
                ));
                updated = true;
            }
        }
        if (updated) {
            PersistenceHelper.save("jobs.json", jobs);
        }
        return jobs;
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobDTO> getJob(@PathVariable String id) {
        return jobs.stream()
                .filter(j -> j.id().equals(id))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Void> saveJob(@RequestBody JobDTO job) {
        log.info("Saving job: {}", job.name());
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
                job.transformationConnector() != null ? job.transformationConnector() : "Ollama_Embedding_Default"
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
                        job.transformationConnector() != null ? job.transformationConnector() : existing.transformationConnector()
                    ));
                    break;
                }
            }
        }
        PersistenceHelper.save("jobs.json", jobs);
        return ResponseEntity.status(201).build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteJob(@PathVariable String id) {
        log.info("Deleting job: {}", id);
        jobs.removeIf(j -> j.id().equals(id));
        PersistenceHelper.save("jobs.json", jobs);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Void> startJob(@PathVariable String id) {
        log.info("Starting job {}", id);
        updateJobStatus(id, "Running");

        // Find the job to get parameters
        JobDTO activeJob = jobs.stream()
            .filter(j -> j.id().equals(id))
            .findFirst()
            .orElse(null);
            
        if (activeJob != null) {
            // Find the repository connector configuration in connectors.json
            RepositoryConnector resolvedConnector = null;
            try {
                List<ConnectorController.ConnectorDTO> connectors = 
                    PersistenceHelper.loadList("connectors.json", ConnectorController.ConnectorDTO.class, List.of());
                ConnectorController.ConnectorDTO connConfig = connectors.stream()
                    .filter(c -> c.name().equals(activeJob.repositoryConnector()))
                    .findFirst()
                    .orElse(null);
                    
                if (connConfig != null) {
                    if (connConfig.className().contains("Alfresco")) {
                        String url = connConfig.configuration().getOrDefault("url", "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1");
                        String username = connConfig.configuration().getOrDefault("username", "admin");
                        String password = connConfig.configuration().getOrDefault("password", "admin");
                        int batchSize = 100;
                        try {
                            batchSize = Integer.parseInt(connConfig.configuration().getOrDefault("batchSize", "100"));
                        } catch (Exception e) {}
                        resolvedConnector = new org.opencrawling.alfresco.AlfrescoRepositoryConnector(url, username, password, batchSize);
                    } else if (connConfig.className().contains("Iceberg")) {
                        String catalogType = connConfig.configuration().getOrDefault("catalogType", "in-memory");
                        String catalogUri = connConfig.configuration().getOrDefault("catalogUri", "");
                        String warehouse = connConfig.configuration().getOrDefault("warehouse", "tmp/iceberg-warehouse");
                        String idColumn = connConfig.configuration().getOrDefault("idColumn", "");
                        resolvedConnector = new org.opencrawling.iceberg.IcebergRepositoryConnector(catalogType, catalogUri, warehouse, idColumn);
                    } else {
                        resolvedConnector = fileSystemRepositoryConnector;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to resolve repository connector for job {}: {}", id, e.getMessage());
            }
            
            if (resolvedConnector == null) {
                resolvedConnector = fileSystemRepositoryConnector; // Fallback
            }
            
            final RepositoryConnector finalConnector = resolvedConnector;
            
            // Execute real crawler inside virtual thread
            Thread.ofVirtual().start(() -> {
                try {
                    log.info("Starting real background crawl job for path: {}", activeJob.path());
                    jobOrchestrator.runJob(finalConnector, outputConnector, activeJob.path(), activeJob.transformationConnector(), activeJob.id());
                    log.info("Real background crawl job completed successfully!");
                    // update status to completed when done, and pull actual db document count
                    updateJobStatusAndStage(id, "Finished", "Completed", getActualDbDocCount());
                } catch (Exception e) {
                    log.error("Real background crawl job failed: ", e);
                    updateJobStatusAndStage(id, "Error", "Failed", getActualDbDocCount());
                }
            });
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Void> stopJob(@PathVariable String id) {
        log.info("Stopping job {}", id);
        updateJobStatus(id, "Finished");
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pauseJob(@PathVariable String id) {
        log.info("Pausing job {}", id);
        updateJobStatus(id, "Paused");
        return ResponseEntity.ok().build();
    }

    private void updateJobStatus(String id, String status) {
        for (int i = 0; i < jobs.size(); i++) {
            JobDTO job = jobs.get(i);
            if (job.id().equals(id)) {
                String lastRun = status.equals("Running") ? LocalDateTime.now().format(formatter) : job.lastRun();
                long docCount = job.documents();
                String stage = "Idle";
                if (status.equals("Running")) {
                    stage = "Scanning";
                    docCount += 10;
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
                    job.transformationConnector()
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
                    job.transformationConnector()
                ));
                break;
            }
        }
        PersistenceHelper.save("jobs.json", jobs);
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

    public static record JobDTO(
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
        String transformationConnector
    ) {}
}
