/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.filesystem.FileSystemRepositoryConnector;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.opencrawling.core.text.TextExtractionService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

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
 *   <li>A job that names no output uses the runtime's configured output connector: in decoupled deployments the
 *       writer service decides where vectors go. A named output is built for the run by its
 *       {@link OutputConnectorFactory} (#319); an output class no factory builds stops the job.</li>
 * </ul>
 */
@Component
public class ConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(ConnectorFactory.class);

    private final FileSystemRepositoryConnector fileSystemRepositoryConnector;
    private final OutputConnector activeOutputConnector;
    private final ObjectProvider<PipelineProperties> pipelinePropertiesProvider;
    // The runtime's collaborators for per-job outputs: without them a per-job vector output has no embedding model
    // and fails every document (#333). Null when built without them, as in some tests.
    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final ObjectProvider<TextExtractionService> textExtractionServices;
    private final ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplates;
    private volatile List<OutputConnectorFactory> outputFactories;

    public ConnectorFactory(FileSystemRepositoryConnector fileSystemRepositoryConnector,
                            OutputConnector activeOutputConnector,
                            ObjectProvider<PipelineProperties> pipelinePropertiesProvider) {
        this(fileSystemRepositoryConnector, activeOutputConnector, pipelinePropertiesProvider, null, null, null);
    }

    @Autowired
    public ConnectorFactory(FileSystemRepositoryConnector fileSystemRepositoryConnector,
                            OutputConnector activeOutputConnector,
                            ObjectProvider<PipelineProperties> pipelinePropertiesProvider,
                            @Qualifier("ollamaEmbeddingModel") ObjectProvider<EmbeddingModel> embeddingModels,
                            ObjectProvider<TextExtractionService> textExtractionServices,
                            ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplates) {
        this.fileSystemRepositoryConnector = fileSystemRepositoryConnector;
        this.activeOutputConnector = activeOutputConnector;
        this.pipelinePropertiesProvider = pipelinePropertiesProvider;
        this.embeddingModels = embeddingModels;
        this.textExtractionServices = textExtractionServices;
        this.kafkaTemplates = kafkaTemplates;
    }

    private static <T> T available(ObjectProvider<T> provider) {
        return provider == null ? null : provider.getIfAvailable();
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
                case ZAMMAD -> buildZammad(name, config);
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
     * The output connector for a job run (#319).
     *
     * <p>A run that names no saved output uses the runtime's configured output. A named output is built for this run
     * by the {@link OutputConnectorFactory} whose {@link OutputConnectorFactory#className()} matches its class name
     * (the fully qualified name, or the simple name), so two runs can write to two instances of the same output at
     * the same time. A named output that no factory builds stops the run: it no longer falls back to the runtime's
     * output, which wrote the run's documents somewhere it did not ask for.
     *
     * @param connector the saved connector configuration, or {@code null} when the job names no saved connector
     * @param mode      the run's pipeline mode
     * @throws ConnectorResolutionException when no factory builds the output, or its configuration is rejected
     */
    public OutputConnector output(ConnectorDTO connector, PipelineMode mode) {
        if (connector == null) {
            return activeOutputConnector;
        }
        OutputConnectorFactory factory = outputFactory(connector.className()).orElseThrow(() ->
                new ConnectorResolutionException("Output connector '" + connector.name() + "' has an unknown class '"
                        + connector.className() + "': no output connector factory builds it."));
        Map<String, String> config = connector.configuration() != null ? connector.configuration() : Map.of();
        try {
            return factory.create(config, new OutputContext(mode, activeOutputConnector, this::service));
        } catch (RuntimeException e) {
            throw new ConnectorResolutionException("Output connector '" + connector.name() + "' could not be built: "
                    + e.getMessage(), e);
        }
    }

    /** The factory that builds outputs of this class: the fully qualified name, else the simple name. */
    Optional<OutputConnectorFactory> outputFactory(String className) {
        if (className == null || className.isBlank()) {
            return Optional.empty();
        }
        String name = className.trim();
        List<OutputConnectorFactory> factories = outputFactories();
        return factories.stream().filter(f -> f.className().equals(name)).findFirst()
                .or(() -> factories.stream().filter(f -> simpleName(f.className()).equals(simpleName(name))).findFirst());
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** Replaces the factories found through {@link ServiceLoader}, as in tests. */
    void useOutputFactories(List<OutputConnectorFactory> factories) {
        this.outputFactories = List.copyOf(factories);
    }

    private List<OutputConnectorFactory> outputFactories() {
        List<OutputConnectorFactory> loaded = outputFactories;
        if (loaded == null) {
            List<OutputConnectorFactory> found = new ArrayList<>();
            ServiceLoader.load(OutputConnectorFactory.class, ConnectorFactory.class.getClassLoader()).forEach(found::add);
            loaded = List.copyOf(found);
            outputFactories = loaded;
        }
        return loaded;
    }

    /** The runtime's shared services a per-run output may use. */
    private Object service(Class<?> type) {
        if (type == EmbeddingModel.class) {
            return available(embeddingModels);
        }
        if (type == TextExtractionService.class) {
            return available(textExtractionServices);
        }
        if (type == KafkaTemplate.class) {
            return available(kafkaTemplates);
        }
        if (type == PipelineProperties.class) {
            return pipelineProperties();
        }
        return null;
    }

    /**
     * Releases an output connector built for one run by {@link #output(ConnectorDTO, PipelineMode)}: such a connector
     * owns its clients and sessions (e.g. a Doxis CSB login, a connection pool). The runtime's shared output connector
     * is left untouched.
     */
    public void release(OutputConnector connector) {
        if (connector == null || connector == activeOutputConnector) {
            return;
        }
        try {
            connector.disconnect();
        } catch (Exception e) {
            log.warn("Failed to disconnect per-job output connector {}: {}", connector.getName(), e.getMessage());
        }
    }

    private PipelineProperties pipelineProperties() {
        return pipelinePropertiesProvider.getIfAvailable();
    }

    private RepositoryConnector buildAlfresco(String name, Map<String, String> config) {
        org.opencrawling.alfresco.AlfrescoProperties alfrescoSettings =
                org.opencrawling.alfresco.AlfrescoProperties.fromConfiguration(config);
        log.info("Resolving dynamic Alfresco repository connector for '{}' ({} mode, auth {}, incremental={})",
                alfrescoSettings.coreApiUrl(), alfrescoSettings.crawlMode(),
                alfrescoSettings.authType(), alfrescoSettings.incrementalEnabled());
        return new org.opencrawling.alfresco.AlfrescoRepositoryConnector(alfrescoSettings);
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

    private RepositoryConnector buildZammad(String name, Map<String, String> config) {
        org.opencrawling.zammad.ZammadProperties zammadSettings =
                org.opencrawling.zammad.ZammadProperties.fromConfiguration(config);
        log.info("Resolving dynamic Zammad repository connector for '{}' ({} mode, granularity {})",
                zammadSettings.baseUrl(), zammadSettings.crawlMode(), zammadSettings.articleGranularity());
        return new org.opencrawling.zammad.ZammadRepositoryConnector(zammadSettings);
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

    /** The Apache Ozone settings a registered Ozone output connector's configuration describes. */
    public static org.opencrawling.ozone.config.OzoneOutputProperties ozoneProperties(Map<String, String> config) {
        return org.opencrawling.ozone.OzoneOutputConnectorFactory.properties(config);
    }

    /** The AWS S3 settings a registered S3 output connector's configuration describes. */
    public static org.opencrawling.s3.config.S3OutputProperties s3Properties(Map<String, String> config) {
        return org.opencrawling.s3.S3OutputConnectorFactory.properties(config);
    }

    /** The Apache Iceberg settings a registered Iceberg output connector's configuration describes. */
    public static org.opencrawling.iceberg.output.config.IcebergOutputProperties icebergProperties(Map<String, String> config) {
        return org.opencrawling.iceberg.output.IcebergOutputConnectorFactory.properties(config);
    }
}
