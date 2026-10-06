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
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
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

    @Autowired(required = false)
    private PipelineProperties pipelineProperties;

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
        try {
            PipelineMode requestedMode = PipelineMode.parseStrict(job.pipelineMode());
            job = job.withPipelineMode(requestedMode != null ? requestedMode.externalName() : null);
        } catch (IllegalArgumentException e) {
            log.warn("Rejecting job '{}': {}", job.name(), e.getMessage());
            return ResponseEntity.badRequest().build();
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
                job.pipelineMode()
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
                        job.pipelineMode() != null ? job.pipelineMode() : existing.pipelineMode()
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
                        } catch (Exception ignored) {}
                        String crawlModeStr = connConfig.configuration().getOrDefault("crawlMode", "folder");
                        String rootFolderPath = connConfig.configuration().getOrDefault("rootFolderPath", "/");
                        String rootFolderId = connConfig.configuration().getOrDefault("rootFolderId", "");
                        String siteId = connConfig.configuration().getOrDefault("siteId", "");
                        boolean includeSubfolders = Boolean.parseBoolean(connConfig.configuration().getOrDefault("includeSubfolders", "true"));
                        String excludedFoldersStr = connConfig.configuration().getOrDefault("excludedFolders", "Data Dictionary");
                        java.util.Set<String> excludedFolders = java.util.Arrays.stream(excludedFoldersStr.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .collect(java.util.stream.Collectors.toSet());
                        String searchQuery = connConfig.configuration().getOrDefault("searchQuery", "TYPE:'cm:content'");
                        String queryLanguage = connConfig.configuration().getOrDefault("queryLanguage", "afts");
                        boolean includeAcls = Boolean.parseBoolean(connConfig.configuration().getOrDefault("includeAcls", "true"));
                        boolean includeContentStream = Boolean.parseBoolean(connConfig.configuration().getOrDefault("includeContentStream", "true"));
                        long maxContentSizeBytes = 52428800L;
                        try {
                            maxContentSizeBytes = Long.parseLong(connConfig.configuration().getOrDefault("maxContentSizeBytes", "52428800"));
                        } catch (Exception ignored) {}
                        String mimeTypeFilterStr = connConfig.configuration().getOrDefault("mimeTypeFilter", "");
                        java.util.Set<String> mimeTypeFilter = java.util.Arrays.stream(mimeTypeFilterStr.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .collect(java.util.stream.Collectors.toSet());
                        int timeoutSeconds = 30;
                        try {
                            timeoutSeconds = Integer.parseInt(connConfig.configuration().getOrDefault("timeoutSeconds", "30"));
                        } catch (Exception ignored) {}

                        resolvedConnector = new org.opencrawling.alfresco.AlfrescoRepositoryConnector(
                                url, username, password, batchSize,
                                org.opencrawling.alfresco.AlfrescoCrawlMode.fromString(crawlModeStr),
                                rootFolderPath, rootFolderId, siteId,
                                includeSubfolders, excludedFolders,
                                searchQuery, queryLanguage,
                                includeAcls, includeContentStream,
                                maxContentSizeBytes, mimeTypeFilter,
                                timeoutSeconds
                        );
                    } else if (connConfig.className().contains("Iceberg")) {
                        String catalogType = connConfig.configuration().getOrDefault("catalogType", "in-memory");
                        String catalogUri = connConfig.configuration().getOrDefault("catalogUri", "");
                        String warehouse = connConfig.configuration().getOrDefault("warehouse", "tmp/iceberg-warehouse");
                        String idColumn = connConfig.configuration().getOrDefault("idColumn", "");
                        resolvedConnector = new org.opencrawling.iceberg.IcebergRepositoryConnector(catalogType, catalogUri, warehouse, idColumn);
                    } else if (connConfig.className().contains("Cmis") || connConfig.className().contains("cmis")) {
                        String endpointUrl = connConfig.configuration().getOrDefault("endpointUrl",
                                connConfig.configuration().getOrDefault("url", "http://localhost:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser"));
                        String repositoryId = connConfig.configuration().getOrDefault("repositoryId", "");
                        String username = connConfig.configuration().getOrDefault("username", "admin");
                        String password = connConfig.configuration().getOrDefault("password", "admin");
                        String bindingTypeStr = connConfig.configuration().getOrDefault("bindingType", "browser");
                        String crawlModeStr = connConfig.configuration().getOrDefault("crawlMode", "folder");
                        String rootFolderPath = connConfig.configuration().getOrDefault("rootFolderPath", "/");
                        String rootFolderId = connConfig.configuration().getOrDefault("rootFolderId", "");
                        boolean includeSubfolders = Boolean.parseBoolean(connConfig.configuration().getOrDefault("includeSubfolders", "true"));
                        String excludedFolderPathsStr = connConfig.configuration().getOrDefault("excludedFolderPaths", "");
                        java.util.Set<String> excluded = java.util.Arrays.stream(excludedFolderPathsStr.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .collect(java.util.stream.Collectors.toSet());
                        String cmisQuery = connConfig.configuration().getOrDefault("cmisQuery", "SELECT * FROM cmis:document");
                        String versionsModeStr = connConfig.configuration().getOrDefault("versionsMode", "latest_major");
                        boolean includeContentStream = Boolean.parseBoolean(connConfig.configuration().getOrDefault("includeContentStream", "true"));
                        long maxContentSizeBytes = 52428800L;
                        try {
                            maxContentSizeBytes = Long.parseLong(connConfig.configuration().getOrDefault("maxContentSizeBytes", "52428800"));
                        } catch (Exception ignored) {}
                        boolean includeAcls = Boolean.parseBoolean(connConfig.configuration().getOrDefault("includeAcls", "true"));
                        boolean includeSecondaryTypes = Boolean.parseBoolean(connConfig.configuration().getOrDefault("includeSecondaryTypes", "true"));
                        boolean changeLogEnabled = Boolean.parseBoolean(connConfig.configuration().getOrDefault("changeLogEnabled", "false"));
                        String changeLogToken = connConfig.configuration().getOrDefault("changeLogToken", "");
                        int batchSize = 100;
                        try {
                            batchSize = Integer.parseInt(connConfig.configuration().getOrDefault("batchSize", "100"));
                        } catch (Exception ignored) {}
                        int timeoutSeconds = 30;
                        try {
                            timeoutSeconds = Integer.parseInt(connConfig.configuration().getOrDefault("timeoutSeconds", "30"));
                        } catch (Exception ignored) {}

                        resolvedConnector = new org.opencrawling.cmis.CmisRepositoryConnector(
                                endpointUrl, repositoryId, username, password,
                                org.opencrawling.cmis.CmisBindingType.fromString(bindingTypeStr),
                                org.opencrawling.cmis.CmisCrawlMode.fromString(crawlModeStr),
                                rootFolderPath, rootFolderId, includeSubfolders, excluded,
                                cmisQuery, org.opencrawling.cmis.CmisVersionsMode.fromString(versionsModeStr),
                                includeContentStream, maxContentSizeBytes, includeAcls, includeSecondaryTypes,
                                changeLogEnabled, changeLogToken, batchSize, timeoutSeconds
                        );
                        log.info("Successfully resolved dynamic CMIS repository connector for endpoint '{}'", endpointUrl);
                    } else if (connConfig.className().contains("Jdbc")) {
                        String url = connConfig.configuration().getOrDefault("url", "jdbc:h2:mem:opencrawling;DB_CLOSE_DELAY=-1");
                        String driverClassName = connConfig.configuration().getOrDefault("driverClassName", connConfig.configuration().getOrDefault("driver-class-name", ""));
                        String username = connConfig.configuration().getOrDefault("username", "");
                        String password = connConfig.configuration().getOrDefault("password", "");
                        String crawlModeStr = connConfig.configuration().getOrDefault("crawlMode", connConfig.configuration().getOrDefault("mode", "table"));
                        String tableName = connConfig.configuration().getOrDefault("tableName", connConfig.configuration().getOrDefault("table-name", ""));
                        String schemaName = connConfig.configuration().getOrDefault("schemaName", connConfig.configuration().getOrDefault("schema-name", ""));
                        String pkCols = connConfig.configuration().getOrDefault("primaryKeyColumns", connConfig.configuration().getOrDefault("primaryKeyColumn", "id"));
                        java.util.Set<String> primaryKeyColumns = java.util.Arrays.stream(pkCols.split(","))
                                .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
                        String titleColumn = connConfig.configuration().getOrDefault("titleColumn", "title");
                        String exclCols = connConfig.configuration().getOrDefault("excludedColumns", "");
                        java.util.Set<String> excludedColumns = java.util.Arrays.stream(exclCols.split(","))
                                .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
                        String querySql = connConfig.configuration().getOrDefault("querySql", connConfig.configuration().getOrDefault("sql", ""));
                        String blobColumnName = connConfig.configuration().getOrDefault("blobColumnName", "");
                        String fileNameColumn = connConfig.configuration().getOrDefault("fileNameColumn", "");
                        String mimeTypeColumn = connConfig.configuration().getOrDefault("mimeTypeColumn", "");
                        boolean incrementalEnabled = Boolean.parseBoolean(connConfig.configuration().getOrDefault("incrementalEnabled", "false"));
                        String hwmColumn = connConfig.configuration().getOrDefault("hwmColumn", "updated_at");
                        String hwmType = connConfig.configuration().getOrDefault("hwmType", "timestamp");
                        boolean softDeleteEnabled = Boolean.parseBoolean(connConfig.configuration().getOrDefault("softDeleteEnabled", "false"));
                        String softDeleteColumn = connConfig.configuration().getOrDefault("softDeleteColumn", "is_deleted");
                        String softDeleteValue = connConfig.configuration().getOrDefault("softDeleteValue", "true");
                        boolean securityEnabled = Boolean.parseBoolean(connConfig.configuration().getOrDefault("securityEnabled", "false"));
                        String userCols = connConfig.configuration().getOrDefault("userColumns", "");
                        java.util.Set<String> userColumns = java.util.Arrays.stream(userCols.split(","))
                                .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
                        String groupCols = connConfig.configuration().getOrDefault("groupColumns", "");
                        java.util.Set<String> groupColumns = java.util.Arrays.stream(groupCols.split(","))
                                .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
                        String tenantColumn = connConfig.configuration().getOrDefault("tenantColumn", "");
                        String defaultPermission = connConfig.configuration().getOrDefault("defaultPermission", "read");
                        int fetchSize = 1000;
                        try { fetchSize = Integer.parseInt(connConfig.configuration().getOrDefault("fetchSize", "1000")); } catch (Exception ignored) {}
                        int batchSize = 100;
                        try { batchSize = Integer.parseInt(connConfig.configuration().getOrDefault("batchSize", "100")); } catch (Exception ignored) {}
                        int maxPoolSize = 10;
                        try { maxPoolSize = Integer.parseInt(connConfig.configuration().getOrDefault("maxPoolSize", "10")); } catch (Exception ignored) {}
                        int minIdle = 2;
                        try { minIdle = Integer.parseInt(connConfig.configuration().getOrDefault("minIdle", "2")); } catch (Exception ignored) {}
                        long connectionTimeoutMs = 30000L;
                        try { connectionTimeoutMs = Long.parseLong(connConfig.configuration().getOrDefault("connectionTimeoutMs", "30000")); } catch (Exception ignored) {}

                        String narrativizationTemplate = connConfig.configuration().getOrDefault("narrativizationTemplate", "");

                        org.opencrawling.jdbc.JdbcRepositoryConnector jdbcConn = new org.opencrawling.jdbc.JdbcRepositoryConnector(
                                url, driverClassName, username, password,
                                org.opencrawling.jdbc.JdbcCrawlMode.fromString(crawlModeStr),
                                tableName, schemaName, primaryKeyColumns, titleColumn, excludedColumns,
                                querySql, blobColumnName, fileNameColumn, mimeTypeColumn,
                                incrementalEnabled, hwmColumn, hwmType,
                                softDeleteEnabled, softDeleteColumn, softDeleteValue,
                                securityEnabled, userColumns, groupColumns, tenantColumn, defaultPermission,
                                fetchSize, batchSize, maxPoolSize, minIdle, connectionTimeoutMs
                        );
                        if (!narrativizationTemplate.isBlank()) {
                            jdbcConn.setNarrativizationTemplate(narrativizationTemplate);
                        }
                        resolvedConnector = jdbcConn;
                        log.info("Successfully resolved dynamic JDBC repository connector for URL '{}' (target table: '{}')", url, tableName);
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
                    } else if (cls.contains("SeaTunnel") || cls.contains("seatunnel")) {
                        String restUrl = outConfig.configuration().getOrDefault("seatunnelRestUrl", "http://localhost:8080");
                        String jobName = outConfig.configuration().getOrDefault("seatunnelJobName", "opencrawling_ingestion_pipeline");
                        String kafkaBootstrap = outConfig.configuration().getOrDefault("seatunnelKafkaBootstrapServers", "localhost:9092");
                        String targetSinks = outConfig.configuration().getOrDefault("seatunnelTargetSinks", "console");
                        org.opencrawling.seatunnel.config.SeaTunnelOutputProperties stProps = new org.opencrawling.seatunnel.config.SeaTunnelOutputProperties(
                                restUrl, jobName, "STREAMING", 5000, 4, kafkaBootstrap,
                                "opencrawling-embedded", "opencrawling-seatunnel-group",
                                targetSinks, 1024, true, 30
                        );
                        org.opencrawling.seatunnel.client.SeaTunnelRestClient restClient = new org.opencrawling.seatunnel.client.SeaTunnelRestClient(restUrl, 30);
                        resolvedOutputConnector = new org.opencrawling.seatunnel.SeaTunnelOutputConnector(restClient, stProps, null, null);
                        log.info("Successfully resolved dynamic SeaTunnel output connector for endpoint '{}'", restUrl);
                    } else if (cls.contains("Ozone") || cls.contains("ozone")) {
                        String volume = outConfig.configuration().getOrDefault("volume", "opencrawling");
                        String bucket = outConfig.configuration().getOrDefault("bucket", "migration");
                        String clientType = outConfig.configuration().getOrDefault("clientType", "NATIVE");
                        String omHost = outConfig.configuration().getOrDefault("omHost", "localhost");
                        int omPort = 9862;
                        try { omPort = Integer.parseInt(outConfig.configuration().getOrDefault("omPort", "9862")); } catch (Exception ignored) {}
                        String s3Endpoint = outConfig.configuration().getOrDefault("s3Endpoint", "http://localhost:9878");
                        String accessKey = outConfig.configuration().getOrDefault("accessKey", "any");
                        String secretKey = outConfig.configuration().getOrDefault("secretKey", "any");

                        org.opencrawling.ozone.config.OzoneOutputProperties ozoneProps = new org.opencrawling.ozone.config.OzoneOutputProperties();
                        ozoneProps.setVolume(volume);
                        ozoneProps.setBucket(bucket);
                        ozoneProps.setClientType(clientType);
                        ozoneProps.setOmHost(omHost);
                        ozoneProps.setOmPort(omPort);
                        ozoneProps.setS3Endpoint(s3Endpoint);
                        ozoneProps.setAccessKey(accessKey);
                        ozoneProps.setSecretKey(secretKey);
                        ozoneProps.setKeyStrategy(outConfig.configuration().getOrDefault("keyStrategy", ozoneProps.getKeyStrategy()));
                        ozoneProps.setTombstoneAction(outConfig.configuration().getOrDefault("tombstoneAction", ozoneProps.getTombstoneAction()));

                        org.opencrawling.ozone.OzoneOutputConnector ozoneConnector = new org.opencrawling.ozone.OzoneOutputConnector(ozoneProps, null, pipelineProperties, null);
                        ozoneConnector.setPipelineMode(resolvePipelineMode(activeJob));
                        resolvedOutputConnector = ozoneConnector;
                        log.info("Successfully resolved dynamic Apache Ozone output connector (Volume: {}, Bucket: {}, Strategy: {})", volume, bucket, clientType);
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

            final PipelineMode mode = resolvePipelineMode(activeJob);

            // Fail fast: the Apache Ozone output connector is dedicated to Migration Mode only
            if (resolvedOutputConnector instanceof org.opencrawling.ozone.OzoneOutputConnector && !mode.isMigration()) {
                log.error("Job {} rejected: OzoneOutputConnector requires pipeline mode 'migration' but job resolved to '{}'", id, mode.externalName());
                updateJobStatusAndStage(id, "Error", "Rejected: Ozone output requires migration mode", getActualDbDocCount());
                return ResponseEntity.status(409).build();
            }
            
            final RepositoryConnector finalConnector = resolvedConnector;
            final OutputConnector finalOutputConnector = resolvedOutputConnector;
            final JobDTO finalActiveJob = activeJob;

            log.info("Launching background Virtual Thread for job {} with OutputConnector: {} (pipeline mode: {})", id, finalOutputConnector.getName(), mode.externalName());
            
            // Execute real crawler inside virtual thread
            Thread.ofVirtual().start(() -> {
                try {
                    log.info("Background Virtual Thread running. Path: {}, OutputConnector: {}", finalActiveJob.path(), finalOutputConnector.getName());
                    jobOrchestrator.runJob(finalConnector, finalOutputConnector, finalActiveJob.path(), finalActiveJob.transformationConnector(), finalActiveJob.id(), finalActiveJob.narrativization(), mode);
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

    /**
     * Resolves the effective pipeline mode for a job: the per-job override wins, otherwise the
     * global {@code opencrawling.pipeline.mode} default applies.
     */
    PipelineMode resolvePipelineMode(JobDTO job) {
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
        NarrativizationConfig narrativization,
        String pipelineMode
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
            this(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, null);
        }

        public JobDTO withPipelineMode(String mode) {
            return new JobDTO(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, mode);
        }
    }
}
