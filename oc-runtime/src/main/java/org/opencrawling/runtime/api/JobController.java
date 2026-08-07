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
        defaults.add(new JobDTO("1", "Default_Job", "FileSystem_Local", "PGVector_Output", "", "/data", "Ready", "Idle", 0, "N/A", "Ollama_Embedding_Default", null));
        
        // Load persisted list
        this.jobs = new CopyOnWriteArrayList<>(PersistenceHelper.loadList("jobs.json", JobDTO.class, defaults));
    }

    @GetMapping
    public List<JobDTO> getAllJobs() {
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
                job.transformationConnector() != null ? job.transformationConnector() : "Ollama_Embedding_Default",
                job.narrativization()
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
                        job.narrativization() != null ? job.narrativization() : existing.narrativization()
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
            RepositoryConnector resolvedConnector = null;
            OutputConnector resolvedOutputConnector = null;
            try {
                List<ConnectorController.ConnectorDTO> connectors = 
                    PersistenceHelper.loadList("connectors.json", ConnectorController.ConnectorDTO.class, List.of());
                
                // 1. Repository Connector Resolution
                ConnectorController.ConnectorDTO connConfig = connectors.stream()
                    .filter(c -> c.name().equalsIgnoreCase(activeJob.repositoryConnector()))
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

                // 2. Output Connector Resolution
                ConnectorController.ConnectorDTO outConfig = connectors.stream()
                    .filter(c -> c.name().equalsIgnoreCase(activeJob.outputConnector()) || (c.type() != null && c.type().equalsIgnoreCase("output") && c.name().equalsIgnoreCase(activeJob.outputConnector())))
                    .findFirst()
                    .orElse(null);

                if (outConfig != null) {
                    String cls = outConfig.className();
                    if (cls.contains("Qdrant")) {
                        String host = outConfig.configuration().getOrDefault("qdrantHost", "localhost");
                        int grpcPort = 6334;
                        try {
                            grpcPort = Integer.parseInt(outConfig.configuration().getOrDefault("qdrantPort", "6334"));
                        } catch (Exception ignored) {}
                        boolean useTls = Boolean.parseBoolean(outConfig.configuration().getOrDefault("useTls", "false"));
                        String apiKey = outConfig.configuration().getOrDefault("qdrantApiKey", "");
                        String collectionName = outConfig.configuration().getOrDefault("qdrantCollection", "enterprise_kb");
                        int dimensions = 1024;
                        try {
                            dimensions = Integer.parseInt(outConfig.configuration().getOrDefault("qdrantDimensions", "1024"));
                        } catch (Exception ignored) {}

                        io.qdrant.client.QdrantGrpcClient.Builder grpcBuilder = io.qdrant.client.QdrantGrpcClient.newBuilder(host, grpcPort, useTls);
                        if (apiKey != null && !apiKey.isBlank()) {
                            grpcBuilder.withApiKey(apiKey);
                        }
                        io.qdrant.client.QdrantClient qdrantClient = new io.qdrant.client.QdrantClient(grpcBuilder.build());

                        org.opencrawling.qdrant.config.QdrantOutputProperties props = new org.opencrawling.qdrant.config.QdrantOutputProperties(
                            host, grpcPort, apiKey, collectionName, dimensions,
                            org.opencrawling.qdrant.config.QdrantOutputProperties.Distance.COSINE,
                            org.opencrawling.qdrant.config.QdrantOutputProperties.Quantization.NONE,
                            useTls, 500
                        );
                        org.opencrawling.qdrant.config.QdrantCollectionInitializer initializer = new org.opencrawling.qdrant.config.QdrantCollectionInitializer(qdrantClient, props);
                        initializer.initializeCollection();

                        org.opencrawling.qdrant.QdrantPointMapper mapper = new org.opencrawling.qdrant.QdrantPointMapper();
                        resolvedOutputConnector = new org.opencrawling.qdrant.QdrantOutputConnector(qdrantClient, props, mapper, null);
                        log.info("Successfully resolved dynamic Qdrant output connector for collection '{}'", collectionName);
                    } else if (cls.contains("Vespa")) {
                        String endpoint = outConfig.configuration().getOrDefault("vespaEndpoint", "http://localhost:8080");
                        String namespace = outConfig.configuration().getOrDefault("vespaNamespace", "opencrawling");
                        String documentType = outConfig.configuration().getOrDefault("vespaDocumentType", "opencrawling_chunk");
                        int dimensions = 1024;
                        try {
                            dimensions = Integer.parseInt(outConfig.configuration().getOrDefault("vespaDimensions", "1024"));
                        } catch (Exception ignored) {}
                        int timeoutSeconds = 30;
                        try {
                            timeoutSeconds = Integer.parseInt(outConfig.configuration().getOrDefault("vespaTimeoutSeconds", "30"));
                        } catch (Exception ignored) {}
                        boolean tlsEnabled = Boolean.parseBoolean(outConfig.configuration().getOrDefault("vespaTlsEnabled", "false"));
                        String tlsCertificate = outConfig.configuration().getOrDefault("vespaTlsCertificate", "");
                        String tlsPrivateKey = outConfig.configuration().getOrDefault("vespaTlsPrivateKey", "");
                        String tlsCaCertificates = outConfig.configuration().getOrDefault("vespaTlsCaCertificates", "");

                        ai.vespa.feed.client.FeedClientBuilder feedClientBuilder = ai.vespa.feed.client.FeedClientBuilder.create(java.net.URI.create(endpoint));
                        if (tlsEnabled && !tlsCertificate.isBlank() && !tlsPrivateKey.isBlank()) {
                            feedClientBuilder.setCertificate(java.nio.file.Path.of(tlsCertificate), java.nio.file.Path.of(tlsPrivateKey));
                            if (!tlsCaCertificates.isBlank()) {
                                feedClientBuilder.setCaCertificatesFile(java.nio.file.Path.of(tlsCaCertificates));
                            }
                        }
                        ai.vespa.feed.client.FeedClient feedClient = feedClientBuilder.build();

                        org.opencrawling.vespa.config.VespaOutputProperties vespaProps = new org.opencrawling.vespa.config.VespaOutputProperties(
                                endpoint, namespace, documentType, dimensions, timeoutSeconds, tlsEnabled,
                                tlsCertificate.isBlank() ? null : tlsCertificate,
                                tlsPrivateKey.isBlank() ? null : tlsPrivateKey,
                                tlsCaCertificates.isBlank() ? null : tlsCaCertificates
                        );

                        org.opencrawling.vespa.VespaDocumentMapper vespaMapper = new org.opencrawling.vespa.VespaDocumentMapper();
                        resolvedOutputConnector = new org.opencrawling.vespa.VespaOutputConnector(feedClient, vespaProps, vespaMapper, null);
                        log.info("Successfully resolved dynamic Vespa output connector at endpoint '{}'", endpoint);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to resolve dynamic connectors for job {}: {}", id, e.getMessage(), e);
            }
            
            if (resolvedConnector == null) {
                resolvedConnector = fileSystemRepositoryConnector; // Fallback
            }
            if (resolvedOutputConnector == null) {
                resolvedOutputConnector = this.outputConnector; // Fallback
            }
            
            final RepositoryConnector finalConnector = resolvedConnector;
            final OutputConnector finalOutputConnector = resolvedOutputConnector;
            final JobDTO finalActiveJob = activeJob;

            log.info("Launching background Virtual Thread for job {} with OutputConnector: {}", id, finalOutputConnector.getName());
            
            // Execute real crawler inside virtual thread
            Thread.ofVirtual().start(() -> {
                try {
                    log.info("Background Virtual Thread running. Path: {}, OutputConnector: {}", finalActiveJob.path(), finalOutputConnector.getName());
                    jobOrchestrator.runJob(finalConnector, finalOutputConnector, finalActiveJob.path(), finalActiveJob.transformationConnector(), finalActiveJob.id(), finalActiveJob.narrativization());
                    log.info("Background Virtual Thread completed successfully!");
                    // update status to completed when done, and pull actual db document count
                    updateJobStatusAndStage(id, "Finished", "Completed", getActualDbDocCount());
                } catch (Exception e) {
                    log.error("Background Virtual Thread failed: {}", e.getMessage(), e);
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
        String cleanId = id != null ? id.trim() : "";
        for (int i = 0; i < jobs.size(); i++) {
            JobDTO job = jobs.get(i);
            if (job.id() != null && job.id().trim().equalsIgnoreCase(cleanId)) {
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
                    job.transformationConnector(),
                    job.narrativization()
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
                    job.narrativization()
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

    public static record NarrativizationConfig(
        boolean enabled,
        String template,
        String connectorType
    ) {
        public static NarrativizationConfig disabled() {
            return new NarrativizationConfig(false, null, null);
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
        String transformationConnector,
        NarrativizationConfig narrativization
    ) {
        public JobDTO {
            if (narrativization == null) narrativization = NarrativizationConfig.disabled();
        }
    }
}
