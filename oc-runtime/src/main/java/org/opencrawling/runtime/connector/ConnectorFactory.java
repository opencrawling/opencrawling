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
package org.opencrawling.runtime.connector;

import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.filesystem.FileSystemRepositoryConnector;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Builds the repository and output connectors of a job from their saved configuration ({@code connectors.json}).
 *
 * <p>Extracted from {@code JobController.startJob}: each connector is built exactly as before. The difference is
 * what happens when a repository connector cannot be built. Before, an unknown class, a connector that only runs
 * in its own crawler service, or a configuration error all fell back to the local file system connector, so the
 * job crawled the wrong source. Now they raise {@link ConnectorResolutionException} and the job does not start.
 *
 * <p>Unchanged on purpose:
 * <ul>
 *   <li>A job whose connector name is not in the saved configuration still uses the file system connector (the
 *       built-in default job relies on this).</li>
 *   <li>Outputs that are not built per job use the runtime's configured output connector, as do unknown output
 *       class names: in decoupled deployments the writer service decides where vectors go.</li>
 * </ul>
 */
@Component
public class ConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(ConnectorFactory.class);

    private final FileSystemRepositoryConnector fileSystemRepositoryConnector;
    private final OutputConnector activeOutputConnector;
    private final ObjectProvider<PipelineProperties> pipelinePropertiesProvider;

    public ConnectorFactory(FileSystemRepositoryConnector fileSystemRepositoryConnector,
                            OutputConnector activeOutputConnector,
                            ObjectProvider<PipelineProperties> pipelinePropertiesProvider) {
        this.fileSystemRepositoryConnector = fileSystemRepositoryConnector;
        this.activeOutputConnector = activeOutputConnector;
        this.pipelinePropertiesProvider = pipelinePropertiesProvider;
    }

    /**
     * The repository connector for a job.
     *
     * @param connector the saved connector configuration, or {@code null} when the job names no saved connector
     * @throws ConnectorResolutionException when the connector is unknown, cannot run from the job API, or its
     *                                      configuration is rejected
     */
    public RepositoryConnector repository(ConnectorDTO connector) {
        if (connector == null) {
            return fileSystemRepositoryConnector;
        }
        String name = connector.name();
        Map<String, String> config = connector.configuration() != null ? connector.configuration() : Map.of();
        RepositoryKind kind = RepositoryKind.of(connector.className());
        switch (kind) {
            case FILESYSTEM:
                return fileSystemRepositoryConnector;
            case NOT_RUNNABLE:
                throw new ConnectorResolutionException("Repository connector '" + name + "' (" + connector.className()
                        + ") cannot be started from the job API; run it through its crawler service.");
            case UNKNOWN:
                throw new ConnectorResolutionException("Repository connector '" + name + "' has an unknown class '"
                        + connector.className() + "'.");
            default:
                break;
        }
        try {
            return switch (kind) {
                case ALFRESCO -> buildAlfresco(name, config);
                case ICEBERG -> buildIceberg(name, config);
                case CMIS -> buildCmis(name, config);
                case DOXIS -> buildDoxis(name, config);
                case SHAREPOINT -> buildSharePoint(name, config);
                case MFILES -> buildMFiles(name, config);
                case FILENET -> buildFileNet(name, config);
                case DOCUMENTUM -> buildDocumentum(name, config);
                case HYLAND_CIC -> buildHylandCic(name, config);
                case JDBC -> buildJdbc(name, config);
                case GRPC_PUSH -> buildGrpcPush(name, config);
                default -> throw new IllegalStateException("Unhandled repository kind " + kind);
            };
        } catch (RuntimeException e) {
            throw new ConnectorResolutionException("Repository connector '" + name + "' could not be built: "
                    + e.getMessage(), e);
        }
    }

    /**
     * The output connector for a job.
     *
     * @param connector the saved connector configuration, or {@code null} when the job names no saved connector
     * @param mode      the job's pipeline mode (the Ozone and S3 outputs carry it)
     * @throws ConnectorResolutionException when a per-job output's configuration is rejected
     */
    public OutputConnector output(ConnectorDTO connector, PipelineMode mode) {
        if (connector == null) {
            return activeOutputConnector;
        }
        Map<String, String> config = connector.configuration() != null ? connector.configuration() : Map.of();
        OutputKind kind = OutputKind.of(connector.className());
        if (kind == OutputKind.ACTIVE) {
            return activeOutputConnector;
        }
        try {
            return switch (kind) {
                case QDRANT -> buildQdrant(config, mode);
                case VESPA -> buildVespa(config, mode);
                case SEATUNNEL -> buildSeaTunnel(config, mode);
                case OZONE -> buildOzone(config, mode);
                case S3 -> buildS3(config, mode);
                case ICEBERG -> buildIcebergOutput(config);
                case HYLAND_CIC -> buildHylandCicOutput(config);
                default -> throw new IllegalStateException("Unhandled output kind " + kind);
            };
        } catch (RuntimeException e) {
            throw new ConnectorResolutionException("Output connector '" + connector.name() + "' could not be built: "
                    + e.getMessage(), e);
        }
    }

    private PipelineProperties pipelineProperties() {
        return pipelinePropertiesProvider.getIfAvailable();
    }

    private RepositoryConnector buildAlfresco(String name, Map<String, String> config) {
        String url = config.getOrDefault("url", "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1");
        String username = config.getOrDefault("username", "admin");
        String password = config.getOrDefault("password", "admin");
        int batchSize = 100;
        try {
            batchSize = Integer.parseInt(config.getOrDefault("batchSize", "100"));
        } catch (Exception ignored) {}
        String crawlModeStr = config.getOrDefault("crawlMode", "folder");
        String rootFolderPath = config.getOrDefault("rootFolderPath", "/");
        String rootFolderId = config.getOrDefault("rootFolderId", "");
        String siteId = config.getOrDefault("siteId", "");
        boolean includeSubfolders = Boolean.parseBoolean(config.getOrDefault("includeSubfolders", "true"));
        String excludedFoldersStr = config.getOrDefault("excludedFolders", "Data Dictionary");
        java.util.Set<String> excludedFolders = java.util.Arrays.stream(excludedFoldersStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        String searchQuery = config.getOrDefault("searchQuery", "TYPE:'cm:content'");
        String queryLanguage = config.getOrDefault("queryLanguage", "afts");
        boolean includeAcls = Boolean.parseBoolean(config.getOrDefault("includeAcls", "true"));
        boolean includeContentStream = Boolean.parseBoolean(config.getOrDefault("includeContentStream", "true"));
        long maxContentSizeBytes = 52428800L;
        try {
            maxContentSizeBytes = Long.parseLong(config.getOrDefault("maxContentSizeBytes", "52428800"));
        } catch (Exception ignored) {}
        String mimeTypeFilterStr = config.getOrDefault("mimeTypeFilter", "");
        java.util.Set<String> mimeTypeFilter = java.util.Arrays.stream(mimeTypeFilterStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        int timeoutSeconds = 30;
        try {
            timeoutSeconds = Integer.parseInt(config.getOrDefault("timeoutSeconds", "30"));
        } catch (Exception ignored) {}
        return new org.opencrawling.alfresco.AlfrescoRepositoryConnector(
                url, username, password, batchSize,
                org.opencrawling.alfresco.AlfrescoCrawlMode.fromString(crawlModeStr),
                rootFolderPath, rootFolderId, siteId,
                includeSubfolders, excludedFolders,
                searchQuery, queryLanguage,
                includeAcls, includeContentStream,
                maxContentSizeBytes, mimeTypeFilter,
                timeoutSeconds
        );
    }

    private RepositoryConnector buildIceberg(String name, Map<String, String> config) {
        String catalogType = config.getOrDefault("catalogType", "in-memory");
        String catalogUri = config.getOrDefault("catalogUri", "");
        String warehouse = config.getOrDefault("warehouse", "tmp/iceberg-warehouse");
        String idColumn = config.getOrDefault("idColumn", "");
        return new org.opencrawling.iceberg.IcebergRepositoryConnector(catalogType, catalogUri, warehouse, idColumn);
    }

    private RepositoryConnector buildCmis(String name, Map<String, String> config) {
        String endpointUrl = config.getOrDefault("endpointUrl",
                config.getOrDefault("url", "http://localhost:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser"));
        String repositoryId = config.getOrDefault("repositoryId", "");
        String username = config.getOrDefault("username", "admin");
        String password = config.getOrDefault("password", "admin");
        String bindingTypeStr = config.getOrDefault("bindingType", "browser");
        String crawlModeStr = config.getOrDefault("crawlMode", "folder");
        String rootFolderPath = config.getOrDefault("rootFolderPath", "/");
        String rootFolderId = config.getOrDefault("rootFolderId", "");
        boolean includeSubfolders = Boolean.parseBoolean(config.getOrDefault("includeSubfolders", "true"));
        String excludedFolderPathsStr = config.getOrDefault("excludedFolderPaths", "");
        java.util.Set<String> excluded = java.util.Arrays.stream(excludedFolderPathsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        String cmisQuery = config.getOrDefault("cmisQuery", "SELECT * FROM cmis:document");
        String versionsModeStr = config.getOrDefault("versionsMode", "latest_major");
        boolean includeContentStream = Boolean.parseBoolean(config.getOrDefault("includeContentStream", "true"));
        long maxContentSizeBytes = 52428800L;
        try {
            maxContentSizeBytes = Long.parseLong(config.getOrDefault("maxContentSizeBytes", "52428800"));
        } catch (Exception ignored) {}
        boolean includeAcls = Boolean.parseBoolean(config.getOrDefault("includeAcls", "true"));
        boolean includeSecondaryTypes = Boolean.parseBoolean(config.getOrDefault("includeSecondaryTypes", "true"));
        boolean changeLogEnabled = Boolean.parseBoolean(config.getOrDefault("changeLogEnabled", "false"));
        String changeLogToken = config.getOrDefault("changeLogToken", "");
        int batchSize = 100;
        try {
            batchSize = Integer.parseInt(config.getOrDefault("batchSize", "100"));
        } catch (Exception ignored) {}
        int timeoutSeconds = 30;
        try {
            timeoutSeconds = Integer.parseInt(config.getOrDefault("timeoutSeconds", "30"));
        } catch (Exception ignored) {}

        log.info("Resolving dynamic CMIS repository connector for endpoint '{}'", endpointUrl);
        return new org.opencrawling.cmis.CmisRepositoryConnector(
                endpointUrl, repositoryId, username, password,
                org.opencrawling.cmis.CmisBindingType.fromString(bindingTypeStr),
                org.opencrawling.cmis.CmisCrawlMode.fromString(crawlModeStr),
                rootFolderPath, rootFolderId, includeSubfolders, excluded,
                cmisQuery, org.opencrawling.cmis.CmisVersionsMode.fromString(versionsModeStr),
                includeContentStream, maxContentSizeBytes, includeAcls, includeSecondaryTypes,
                changeLogEnabled, changeLogToken, batchSize, timeoutSeconds
        );
    }

    private RepositoryConnector buildDoxis(String name, Map<String, String> config) {
        org.opencrawling.doxis.DoxisProperties doxisSettings =
                org.opencrawling.doxis.DoxisProperties.fromConfiguration(config);
        log.info("Resolving dynamic Doxis repository connector for '{}' (repository '{}', {} mode)",
                doxisSettings.url(), doxisSettings.repositoryId(), doxisSettings.crawlMode());
        return new org.opencrawling.doxis.DoxisRepositoryConnector(doxisSettings);
    }

    private RepositoryConnector buildSharePoint(String name, Map<String, String> config) {
        org.opencrawling.sharepoint.SharePointProperties sharePointSettings =
                org.opencrawling.sharepoint.SharePointProperties.fromConfiguration(config);
        log.info("Resolving dynamic SharePoint repository connector for '{}' ({} mode)",
                sharePointSettings.siteUrl() != null ? sharePointSettings.siteUrl() : sharePointSettings.siteId(),
                sharePointSettings.crawlMode());
        return new org.opencrawling.sharepoint.SharePointRepositoryConnector(sharePointSettings);
    }

    private RepositoryConnector buildMFiles(String name, Map<String, String> config) {
        org.opencrawling.mfiles.MFilesProperties mfilesSettings =
                org.opencrawling.mfiles.MFilesProperties.fromConfiguration(config);
        log.info("Resolving dynamic M-Files repository connector for '{}' (vault '{}', {} mode)",
                mfilesSettings.url(), mfilesSettings.vaultGuid(), mfilesSettings.crawlMode());
        return new org.opencrawling.mfiles.MFilesRepositoryConnector(mfilesSettings);
    }

    private RepositoryConnector buildFileNet(String name, Map<String, String> config) {
        org.opencrawling.filenet.FileNetProperties filenetSettings =
                org.opencrawling.filenet.FileNetProperties.fromConfiguration(config);
        log.info("Resolving dynamic FileNet repository connector for '{}' (object store '{}', {} mode)",
                filenetSettings.url(), filenetSettings.objectStore(), filenetSettings.crawlMode());
        return new org.opencrawling.filenet.FileNetRepositoryConnector(filenetSettings);
    }

    private RepositoryConnector buildDocumentum(String name, Map<String, String> config) {
        org.opencrawling.documentum.DocumentumProperties documentumSettings =
                org.opencrawling.documentum.DocumentumProperties.fromConfiguration(config);
        log.info("Resolving dynamic Documentum repository connector for '{}' (repository '{}', {} mode)",
                documentumSettings.url(), documentumSettings.repository(), documentumSettings.crawlMode());
        return new org.opencrawling.documentum.DocumentumRepositoryConnector(documentumSettings);
    }

    private RepositoryConnector buildHylandCic(String name, Map<String, String> config) {
        org.opencrawling.hyland.cic.config.HylandCicProperties cicSettings =
                org.opencrawling.hyland.cic.config.HylandCicProperties.fromConfiguration(config);
        log.info("Resolving dynamic Hyland CIC repository connector for '{}' (base URL '{}', tenant '{}')",
                name, cicSettings.getBaseUrl(), cicSettings.getTenantId());
        return new org.opencrawling.hyland.cic.repository.HylandCicRepositoryConnector(cicSettings);
    }

    private RepositoryConnector buildJdbc(String name, Map<String, String> config) {
        String url = config.getOrDefault("url", "jdbc:h2:mem:opencrawling;DB_CLOSE_DELAY=-1");
        String driverClassName = config.getOrDefault("driverClassName", config.getOrDefault("driver-class-name", ""));
        String username = config.getOrDefault("username", "");
        String password = config.getOrDefault("password", "");
        String crawlModeStr = config.getOrDefault("crawlMode", config.getOrDefault("mode", "table"));
        String tableName = config.getOrDefault("tableName", config.getOrDefault("table-name", ""));
        String schemaName = config.getOrDefault("schemaName", config.getOrDefault("schema-name", ""));
        String pkCols = config.getOrDefault("primaryKeyColumns", config.getOrDefault("primaryKeyColumn", "id"));
        java.util.Set<String> primaryKeyColumns = java.util.Arrays.stream(pkCols.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
        String titleColumn = config.getOrDefault("titleColumn", "title");
        String exclCols = config.getOrDefault("excludedColumns", "");
        java.util.Set<String> excludedColumns = java.util.Arrays.stream(exclCols.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
        String querySql = config.getOrDefault("querySql", config.getOrDefault("sql", ""));
        String blobColumnName = config.getOrDefault("blobColumnName", "");
        String fileNameColumn = config.getOrDefault("fileNameColumn", "");
        String mimeTypeColumn = config.getOrDefault("mimeTypeColumn", "");
        boolean incrementalEnabled = Boolean.parseBoolean(config.getOrDefault("incrementalEnabled", "false"));
        String hwmColumn = config.getOrDefault("hwmColumn", "updated_at");
        String hwmType = config.getOrDefault("hwmType", "timestamp");
        boolean softDeleteEnabled = Boolean.parseBoolean(config.getOrDefault("softDeleteEnabled", "false"));
        String softDeleteColumn = config.getOrDefault("softDeleteColumn", "is_deleted");
        String softDeleteValue = config.getOrDefault("softDeleteValue", "true");
        boolean securityEnabled = Boolean.parseBoolean(config.getOrDefault("securityEnabled", "false"));
        String userCols = config.getOrDefault("userColumns", "");
        java.util.Set<String> userColumns = java.util.Arrays.stream(userCols.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
        String groupCols = config.getOrDefault("groupColumns", "");
        java.util.Set<String> groupColumns = java.util.Arrays.stream(groupCols.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
        String tenantColumn = config.getOrDefault("tenantColumn", "");
        String defaultPermission = config.getOrDefault("defaultPermission", "read");
        int fetchSize = 1000;
        try { fetchSize = Integer.parseInt(config.getOrDefault("fetchSize", "1000")); } catch (Exception ignored) {}
        int batchSize = 100;
        try { batchSize = Integer.parseInt(config.getOrDefault("batchSize", "100")); } catch (Exception ignored) {}
        int maxPoolSize = 10;
        try { maxPoolSize = Integer.parseInt(config.getOrDefault("maxPoolSize", "10")); } catch (Exception ignored) {}
        int minIdle = 2;
        try { minIdle = Integer.parseInt(config.getOrDefault("minIdle", "2")); } catch (Exception ignored) {}
        long connectionTimeoutMs = 30000L;
        try { connectionTimeoutMs = Long.parseLong(config.getOrDefault("connectionTimeoutMs", "30000")); } catch (Exception ignored) {}

        String narrativizationTemplate = config.getOrDefault("narrativizationTemplate", "");

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
        return jdbcConn;
    }

    private RepositoryConnector buildGrpcPush(String name, Map<String, String> config) {
        log.info("Resolving dynamic gRPC Push repository connector for '{}'", name);
        return new org.opencrawling.push.grpc.GrpcPushRepositoryConnector();
    }

    private OutputConnector buildQdrant(Map<String, String> config, PipelineMode mode) {
        String host = config.getOrDefault("qdrantHost", "localhost");
        int grpcPort = 6334;
        try {
            grpcPort = Integer.parseInt(config.getOrDefault("qdrantPort", "6334"));
        } catch (Exception ignored) {}
        boolean useTls = Boolean.parseBoolean(config.getOrDefault("useTls", "false"));
        String apiKey = config.getOrDefault("qdrantApiKey", "");
        String collectionName = config.getOrDefault("qdrantCollection", "enterprise_kb");
        int dimensions = 1024;
        try {
            dimensions = Integer.parseInt(config.getOrDefault("qdrantDimensions", "1024"));
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
        log.info("Resolving dynamic Qdrant output connector for collection '{}'", collectionName);
        return new org.opencrawling.qdrant.QdrantOutputConnector(qdrantClient, props, mapper, null);
    }

    private OutputConnector buildVespa(Map<String, String> config, PipelineMode mode) {
        String endpoint = config.getOrDefault("vespaEndpoint", "http://localhost:8080");
        String namespace = config.getOrDefault("vespaNamespace", "opencrawling");
        String documentType = config.getOrDefault("vespaDocumentType", "opencrawling_chunk");
        int dimensions = 1024;
        try {
            dimensions = Integer.parseInt(config.getOrDefault("vespaDimensions", "1024"));
        } catch (Exception ignored) {}
        int timeoutSeconds = 30;
        try {
            timeoutSeconds = Integer.parseInt(config.getOrDefault("vespaTimeoutSeconds", "30"));
        } catch (Exception ignored) {}
        boolean tlsEnabled = Boolean.parseBoolean(config.getOrDefault("vespaTlsEnabled", "false"));
        String tlsCertificate = config.getOrDefault("vespaTlsCertificate", "");
        String tlsPrivateKey = config.getOrDefault("vespaTlsPrivateKey", "");
        String tlsCaCertificates = config.getOrDefault("vespaTlsCaCertificates", "");

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
        log.info("Resolving dynamic Vespa output connector at endpoint '{}'", endpoint);
        return new org.opencrawling.vespa.VespaOutputConnector(feedClient, vespaProps, vespaMapper, null);
    }

    private OutputConnector buildSeaTunnel(Map<String, String> config, PipelineMode mode) {
        String restUrl = config.getOrDefault("seatunnelRestUrl", "http://localhost:8080");
        String jobName = config.getOrDefault("seatunnelJobName", "opencrawling_ingestion_pipeline");
        String kafkaBootstrap = config.getOrDefault("seatunnelKafkaBootstrapServers", "localhost:9092");
        String targetSinks = config.getOrDefault("seatunnelTargetSinks", "console");
        org.opencrawling.seatunnel.config.SeaTunnelOutputProperties stProps = new org.opencrawling.seatunnel.config.SeaTunnelOutputProperties(
                restUrl, jobName, "STREAMING", 5000, 4, kafkaBootstrap,
                "opencrawling-embedded", "opencrawling-seatunnel-group",
                targetSinks, 1024, true, 30
        );
        org.opencrawling.seatunnel.client.SeaTunnelRestClient restClient = new org.opencrawling.seatunnel.client.SeaTunnelRestClient(restUrl, 30);
        log.info("Resolving dynamic SeaTunnel output connector for endpoint '{}'", restUrl);
        return new org.opencrawling.seatunnel.SeaTunnelOutputConnector(restClient, stProps, null, null);
    }

    private OutputConnector buildOzone(Map<String, String> config, PipelineMode mode) {
        org.opencrawling.ozone.config.OzoneOutputProperties ozoneProps = ozoneProperties(config);
        String volume = ozoneProps.getVolume();
        String bucket = ozoneProps.getBucket();
        String clientType = ozoneProps.getClientType();

        org.opencrawling.ozone.OzoneOutputConnector ozoneConnector = new org.opencrawling.ozone.OzoneOutputConnector(ozoneProps, null, pipelineProperties(), null);
        ozoneConnector.setPipelineMode(mode);
        log.info("Resolving dynamic Apache Ozone output connector (Volume: {}, Bucket: {}, Strategy: {})", volume, bucket, clientType);
        return ozoneConnector;
    }

    private OutputConnector buildS3(Map<String, String> config, PipelineMode mode) {
        org.opencrawling.s3.config.S3OutputProperties s3Props = s3Properties(config);
        String bucket = s3Props.getBucket();
        String region = s3Props.getRegion();
        String keyStrategy = s3Props.getKeyStrategy();

        org.opencrawling.s3.S3OutputConnector s3Connector = new org.opencrawling.s3.S3OutputConnector(s3Props, null, pipelineProperties(), null);
        s3Connector.setPipelineMode(mode);
        log.info("Resolving dynamic AWS S3 output connector (Bucket: {}, Region: {}, Strategy: {})", bucket, region, keyStrategy);
        return s3Connector;
    }

    /** The Apache Ozone settings a registered Ozone output connector's configuration describes. */
    public static org.opencrawling.ozone.config.OzoneOutputProperties ozoneProperties(Map<String, String> config) {
        String volume = config.getOrDefault("volume", "opencrawling");
        String bucket = config.getOrDefault("bucket", "migration");
        String clientType = config.getOrDefault("clientType", "NATIVE");
        String omHost = config.getOrDefault("omHost", "localhost");
        int omPort = 9862;
        try { omPort = Integer.parseInt(config.getOrDefault("omPort", "9862")); } catch (Exception ignored) {}
        String s3Endpoint = config.getOrDefault("s3Endpoint", "http://localhost:9878");
        String accessKey = config.getOrDefault("accessKey", "any");
        String secretKey = config.getOrDefault("secretKey", "any");
        org.opencrawling.ozone.config.OzoneOutputProperties ozoneProps = new org.opencrawling.ozone.config.OzoneOutputProperties();
        ozoneProps.setVolume(volume);
        ozoneProps.setBucket(bucket);
        ozoneProps.setClientType(clientType);
        ozoneProps.setOmHost(omHost);
        ozoneProps.setOmPort(omPort);
        ozoneProps.setS3Endpoint(s3Endpoint);
        ozoneProps.setAccessKey(accessKey);
        ozoneProps.setSecretKey(secretKey);
        ozoneProps.setKeyStrategy(config.getOrDefault("keyStrategy", ozoneProps.getKeyStrategy()));
        ozoneProps.setTombstoneAction(config.getOrDefault("tombstoneAction", ozoneProps.getTombstoneAction()));

        return ozoneProps;
    }

    /** The AWS S3 settings a registered S3 output connector's configuration describes. */
    public static org.opencrawling.s3.config.S3OutputProperties s3Properties(Map<String, String> config) {
        String bucket = config.getOrDefault("bucket", "enterprise-migration-lake");
        String region = config.getOrDefault("region", "us-east-1");
        String endpoint = config.getOrDefault("endpointOverride", "");
        boolean pathStyle = Boolean.parseBoolean(config.getOrDefault("pathStyleAccess", "false"));
        String credsStrategy = config.getOrDefault("credentialsStrategy", "DEFAULT_CHAIN");
        String accessKey = config.getOrDefault("accessKey", "");
        String secretKey = config.getOrDefault("secretKey", "");
        String sessionToken = config.getOrDefault("sessionToken", "");
        String assumeRoleArn = config.getOrDefault("assumeRoleArn", "");
        String assumeRoleExternalId = config.getOrDefault("assumeRoleExternalId", "");
        String keyStrategy = config.getOrDefault("keyStrategy", "HIERARCHICAL");
        String keyPrefix = config.getOrDefault("keyPrefix", "migration/v1/");
        String storageClass = config.getOrDefault("storageClass", "INTELLIGENT_TIERING");
        String sse = config.getOrDefault("serverSideEncryption", "SSE_S3");
        String kmsKeyId = config.getOrDefault("kmsKeyId", "");
        boolean bucketKey = Boolean.parseBoolean(config.getOrDefault("bucketKeyEnabled", "true"));
        String tombstoneAction = config.getOrDefault("tombstoneAction", "DELETE_OBJECTS");

        org.opencrawling.s3.config.S3OutputProperties s3Props = new org.opencrawling.s3.config.S3OutputProperties();
        s3Props.setBucket(bucket);
        s3Props.setRegion(region);
        s3Props.setEndpointOverride(endpoint);
        s3Props.setPathStyleAccess(pathStyle);
        s3Props.setCredentialsStrategy(credsStrategy);
        s3Props.setAccessKey(accessKey);
        s3Props.setSecretKey(secretKey);
        s3Props.setSessionToken(sessionToken);
        s3Props.setAssumeRoleArn(assumeRoleArn);
        s3Props.setAssumeRoleExternalId(assumeRoleExternalId);
        s3Props.setKeyStrategy(keyStrategy);
        s3Props.setKeyPrefix(keyPrefix);
        s3Props.setStorageClass(storageClass);
        s3Props.setServerSideEncryption(sse);
        s3Props.setKmsKeyId(kmsKeyId);
        s3Props.setBucketKeyEnabled(bucketKey);
        s3Props.setTombstoneAction(tombstoneAction);

        String customHeadersJson = config.get("customHeaders");
        if (customHeadersJson != null && !customHeadersJson.isBlank()) {
            try {
                Map<String, String> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        customHeadersJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
                if (parsed != null) {
                    s3Props.getCustomHeaders().putAll(parsed);
                }
            } catch (Exception ex) {
                log.warn("Failed to parse customHeaders JSON for S3 output connector: {}", ex.getMessage());
            }
        }
        config.forEach((k, v) -> {
            if (k != null && k.startsWith("customHeader.") && v != null && !v.isBlank()) {
                String headerName = k.substring("customHeader.".length()).trim();
                if (!headerName.isBlank()) {
                    s3Props.getCustomHeaders().put(headerName, v.trim());
                }
            }
        });
        return s3Props;
    }

    private OutputConnector buildHylandCicOutput(Map<String, String> config) {
        org.opencrawling.hyland.cic.config.HylandCicProperties cicSettings =
                org.opencrawling.hyland.cic.config.HylandCicProperties.fromConfiguration(config);
        log.info("Resolving dynamic Hyland CIC output connector for base URL '{}' (target folder '{}')",
                cicSettings.getBaseUrl(), cicSettings.getTargetFolderPath());
        return new org.opencrawling.hyland.cic.output.HylandCicOutputConnector(cicSettings);
    }

    private OutputConnector buildIcebergOutput(Map<String, String> config) {
        org.opencrawling.iceberg.output.config.IcebergOutputProperties properties = icebergProperties(config);
        log.info("Resolving dynamic Iceberg output connector for catalog '{}' (table strategy: {})",
                properties.getCatalogName(), properties.getTableStrategy());
        return new org.opencrawling.iceberg.output.IcebergOutputConnector(properties);
    }

    public static org.opencrawling.iceberg.output.config.IcebergOutputProperties icebergProperties(Map<String, String> config) {
        org.opencrawling.iceberg.output.config.IcebergOutputProperties props = new org.opencrawling.iceberg.output.config.IcebergOutputProperties();
        props.setCatalogType(config.getOrDefault("catalogType", "rest"));
        props.setCatalogName(config.getOrDefault("catalogName", "opencrawling_catalog"));
        props.setCatalogUri(config.getOrDefault("catalogUri", "http://localhost:8181"));
        props.setWarehouse(config.getOrDefault("warehouse", "s3://warehouse"));
        props.setNamespace(config.getOrDefault("namespace", "enterprise_data"));
        props.setTableName(config.getOrDefault("tableName", "documents"));
        if (config.containsKey("tableStrategy")) {
            try {
                props.setTableStrategy(org.opencrawling.iceberg.output.config.IcebergOutputProperties.TableStrategy.valueOf(config.get("tableStrategy").toUpperCase()));
            } catch (Exception ignored) {}
        }
        if (config.containsKey("partitionStrategy")) {
            try {
                props.setPartitionBy(org.opencrawling.iceberg.output.config.IcebergOutputProperties.PartitionStrategy.valueOf(config.get("partitionStrategy").toUpperCase()));
            } catch (Exception ignored) {}
        }
        if (config.containsKey("autoSchemaEvolution")) {
            props.setAutoSchemaEvolution(Boolean.parseBoolean(config.get("autoSchemaEvolution")));
        }
        return props;
    }
}
