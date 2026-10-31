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

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.opencrawling.runtime.service.ConnectorCheckerService;
import org.opencrawling.runtime.service.ConnectorCheckerService.ConnectionCheckResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Connectors", description = "Management, configuration, and live connectivity tests for repository, output, and transformation connectors")
@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {

    private final List<ConnectorDTO> storage;
    private final ConnectorCheckerService checkerService;

    @Autowired
    public ConnectorController(ConnectorCheckerService checkerService) {
        this.checkerService = checkerService;

        // Initial mock data defaults
        List<ConnectorDTO> defaults = new ArrayList<>();
        defaults.add(new ConnectorDTO("FileSystem_Local", "Local File System", "repository", "org.opencrawling.crawler.connectors.filesystem.FileConnector", 10, new HashMap<>()));
        defaults.add(new ConnectorDTO("Alfresco_Content_Services", "Alfresco Repository", "repository", "org.opencrawling.alfresco.AlfrescoRepositoryConnector", 10, Map.of(
            "url", "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1",
            "username", "admin",
            "password", "admin",
            "batchSize", "100",
            "crawlMode", "folder",
            "includeSubfolders", "true",
            "includeAcls", "true",
            "includeContentStream", "true"
        )));
        defaults.add(new ConnectorDTO("Apache_Iceberg_Local", "Local Iceberg Warehouse Catalog", "repository", "org.opencrawling.iceberg.IcebergRepositoryConnector", 10, Map.of("catalogType", "in-memory", "warehouse", "tmp/iceberg-warehouse")));
        defaults.add(new ConnectorDTO("Flowable_REST_Engine", "Flowable REST Engine", "repository", "org.opencrawling.flowable.FlowableRepositoryConnector", 10, Map.of("endpoint", "http://localhost:8080/flowable-rest/service", "username", "rest-admin", "password", "test")));
        defaults.add(new ConnectorDTO("Camunda_7_REST_Engine", "Camunda 7 REST Engine", "repository", "org.opencrawling.camunda.CamundaRepositoryConnector", 10, Map.of("url", "http://localhost:8080/engine-rest", "username", "demo", "password", "demo")));
        defaults.add(new ConnectorDTO("APS_REST_Engine", "Alfresco Process Services (APS) Engine", "repository", "org.opencrawling.aps.ApsRepositoryConnector", 10, Map.of(
            "url", "http://localhost:8080/activiti-app/api/enterprise",
            "username", "admin@app.activiti.com",
            "password", "admin",
            "includeVariables", "true",
            "includeTasks", "true"
        )));
        defaults.add(new ConnectorDTO("StormCrawler_Web_Engine", "Apache StormCrawler Engine", "repository", "org.opencrawling.stormcrawler.StormCrawlerRepositoryConnector", 10, Map.of(
            "nimbusHost", "localhost",
            "nimbusPort", "6627",
            "nimbusRestUrl", "http://localhost:8080",
            "topologyName", "opencrawling-web-crawler",
            "seeds", "https://docs.example.com",
            "delayMs", "1000",
            "customUserAgent", "OpenCrawling-StormCrawler-Bot/1.0"
        )));
        defaults.add(new ConnectorDTO("CMIS_Repository", "OASIS CMIS 1.1 Repository", "repository", "org.opencrawling.cmis.CmisRepositoryConnector", 10, Map.ofEntries(
            Map.entry("endpointUrl", "http://localhost:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser"),
            Map.entry("bindingType", "BROWSER"),
            Map.entry("repositoryId", "-default-"),
            Map.entry("username", "admin"),
            Map.entry("password", "admin"),
            Map.entry("crawlMode", "FOLDER"),
            Map.entry("rootFolderPath", "/"),
            Map.entry("cmisQuery", "SELECT * FROM cmis:document"),
            Map.entry("versionsMode", "LATEST_MAJOR"),
            Map.entry("includeAcls", "true"),
            Map.entry("includeContentStream", "true"),
            Map.entry("batchSize", "100")
        )));
        defaults.add(new ConnectorDTO("Relational_Database_JDBC", "Relational Database (JDBC)", "repository", "org.opencrawling.jdbc.JdbcRepositoryConnector", 10, Map.ofEntries(
            Map.entry("url", "jdbc:h2:mem:opencrawling;DB_CLOSE_DELAY=-1"),
            Map.entry("driverClassName", "org.h2.Driver"),
            Map.entry("username", "sa"),
            Map.entry("password", ""),
            Map.entry("crawlMode", "TABLE"),
            Map.entry("tableName", "support_tickets"),
            Map.entry("primaryKeyColumns", "id"),
            Map.entry("titleColumn", "title"),
            Map.entry("incrementalEnabled", "false"),
            Map.entry("hwmColumn", "updated_at"),
            Map.entry("softDeleteEnabled", "false"),
            Map.entry("softDeleteColumn", "is_deleted"),
            Map.entry("softDeleteValue", "true"),
            Map.entry("securityEnabled", "false"),
            Map.entry("batchSize", "100"),
            Map.entry("fetchSize", "1000")
        )));
        defaults.add(new ConnectorDTO("Doxis_Repository", "Doxis 4 ECM Repository (CSB REST API)", "repository", "org.opencrawling.doxis.DoxisRepositoryConnector", 10, Map.ofEntries(
            Map.entry("url", "http://localhost:8080/restws/publicws/rest/api/v1"),
            Map.entry("authType", "basic"),
            Map.entry("customerName", ""),
            Map.entry("username", ""),
            Map.entry("password", ""),
            Map.entry("role", "admins"),
            Map.entry("repositoryId", ""),
            Map.entry("crawlMode", "search"),
            Map.entry("documentClasses", ""),
            Map.entry("searchQuery", ""),
            Map.entry("rootFolderId", ""),
            Map.entry("includeSubfolders", "true"),
            Map.entry("versionMode", "latest_only"),
            Map.entry("includeContentStream", "true"),
            Map.entry("maxContentSizeBytes", "52428800"),
            Map.entry("includeDescriptors", "true"),
            Map.entry("includeAcls", "true"),
            Map.entry("descriptorPrefix", ""),
            Map.entry("batchSize", "100"),
            Map.entry("parallelism", "2"),
            Map.entry("timeoutSeconds", "120"),
            Map.entry("incremental", "false"),
            Map.entry("stateDirectory", "data/doxis-state"),
            Map.entry("tenantIsolation", "false")
        )));
        defaults.add(new ConnectorDTO("SharePoint_Repository", "Microsoft SharePoint Online (Microsoft Graph API)", "repository", "org.opencrawling.sharepoint.SharePointRepositoryConnector", 10, Map.ofEntries(
            Map.entry("tenantId", ""),
            Map.entry("clientId", ""),
            Map.entry("clientSecret", ""),
            Map.entry("siteUrl", "https://contoso.sharepoint.com/sites/engineering"),
            Map.entry("driveNames", ""),
            Map.entry("crawlMode", "delta"),
            Map.entry("rootFolderPath", "/"),
            Map.entry("includeSubfolders", "true"),
            Map.entry("includeContentStream", "true"),
            Map.entry("maxFileSizeBytes", "52428800"),
            Map.entry("includeCustomFields", "true"),
            Map.entry("includeAcls", "true"),
            Map.entry("userIdentity", "email"),
            Map.entry("fallbackPrincipals", ""),
            Map.entry("everyoneExceptExternalIdentity", ""),
            Map.entry("organizationLinkIdentity", ""),
            Map.entry("incremental", "true"),
            Map.entry("stateDirectory", "data/sharepoint-state"),
            Map.entry("batchSize", "100"),
            Map.entry("parallelism", "4"),
            Map.entry("maxRetries", "5"),
            Map.entry("timeoutSeconds", "30")
        )));
        defaults.add(new ConnectorDTO("MFiles_Repository", "M-Files Vault (M-Files Web Service REST API)", "repository", "org.opencrawling.mfiles.MFilesRepositoryConnector", 10, Map.ofEntries(
            Map.entry("url", "https://localhost/REST"),
            Map.entry("vaultGuid", ""),
            Map.entry("authType", "token"),
            Map.entry("username", ""),
            Map.entry("password", ""),
            Map.entry("domain", ""),
            Map.entry("crawlMode", "search"),
            Map.entry("objectType", "0"),
            Map.entry("classIds", ""),
            Map.entry("viewId", ""),
            Map.entry("searchQuery", ""),
            Map.entry("versionMode", "latest_only"),
            Map.entry("includeFiles", "true"),
            Map.entry("maxFileSizeBytes", "52428800"),
            Map.entry("includeProperties", "true"),
            Map.entry("includeAcls", "true"),
            Map.entry("fallbackPrincipals", ""),
            Map.entry("principalFormat", "token"),
            Map.entry("emitDeletes", "true"),
            Map.entry("incrementalEnabled", "false"),
            Map.entry("modifiedAfter", ""),
            Map.entry("batchSize", "500"),
            Map.entry("parallelism", "4"),
            Map.entry("timeoutSeconds", "30"),
            Map.entry("stateDirectory", "data/mfiles-state")
        )));
        defaults.add(new ConnectorDTO("FileNet_Repository", "IBM FileNet P8 (Content Services GraphQL API)", "repository", "org.opencrawling.filenet.FileNetRepositoryConnector", 10, Map.ofEntries(
            Map.entry("url", "https://localhost:9443/content-services-graphql/graphql"),
            Map.entry("objectStore", ""),
            Map.entry("authType", "basic"),
            Map.entry("username", ""),
            Map.entry("password", ""),
            Map.entry("crawlMode", "query"),
            Map.entry("documentClasses", "Document"),
            Map.entry("where", ""),
            Map.entry("sqlQuery", ""),
            Map.entry("rootFolderPath", "/"),
            Map.entry("includeSubfolders", "true"),
            Map.entry("versionMode", "current"),
            Map.entry("includeContentStream", "true"),
            Map.entry("maxContentSizeBytes", "52428800"),
            Map.entry("includeAcls", "true"),
            Map.entry("includeCustomProperties", "true"),
            Map.entry("readAccessMask", "131201"),
            Map.entry("authenticatedUsersIdentity", "#authenticated-users"),
            Map.entry("fallbackPrincipals", ""),
            Map.entry("principalFormat", "name"),
            Map.entry("markedDocuments", "exclude"),
            Map.entry("deletionTracking", "reconcile"),
            Map.entry("incrementalEnabled", "true"),
            Map.entry("batchSize", "100"),
            Map.entry("parallelism", "4"),
            Map.entry("timeoutSeconds", "30"),
            Map.entry("stateDirectory", "data/filenet-state")
        )));
        defaults.add(new ConnectorDTO("Documentum_Repository", "OpenText Documentum (REST Services)", "repository", "org.opencrawling.documentum.DocumentumRepositoryConnector", 10, Map.ofEntries(
            Map.entry("url", "https://localhost:8443/dctm-rest"),
            Map.entry("repositoryName", ""),
            Map.entry("authType", "basic"),
            Map.entry("username", ""),
            Map.entry("password", ""),
            Map.entry("crawlMode", "dql"),
            Map.entry("objectTypes", "dm_document"),
            Map.entry("where", ""),
            Map.entry("dqlQuery", ""),
            Map.entry("rootCabinetPath", "/"),
            Map.entry("includeSubfolders", "true"),
            Map.entry("versionMode", "current_only"),
            Map.entry("includeContentStream", "true"),
            Map.entry("maxContentSizeBytes", "52428800"),
            Map.entry("preferRendition", ""),
            Map.entry("includeAcls", "true"),
            Map.entry("worldIdentity", "dm_world"),
            Map.entry("principalFormat", "name"),
            Map.entry("requiredGroupDocuments", "exclude"),
            Map.entry("fallbackPrincipals", ""),
            Map.entry("includeCustomAttributes", "true"),
            Map.entry("deletionTracking", "reconcile"),
            Map.entry("incrementalEnabled", "true"),
            Map.entry("serverTimeZone", "auto"),
            Map.entry("batchSize", "100"),
            Map.entry("parallelism", "4"),
            Map.entry("timeoutSeconds", "30"),
            Map.entry("stateDirectory", "data/documentum-state")
        )));
        defaults.add(new ConnectorDTO("Grpc_Push_Connector", "gRPC Push Streaming Ingestion", "repository", "org.opencrawling.push.grpc.GrpcPushRepositoryConnector", 100, Map.of(
            "port", "9096",
            "maxMessageSizeMb", "64",
            "inlineBinaryThresholdKb", "512",
            "token", "",
            "tlsEnabled", "false",
            "clientAuth", "NONE"
        )));
        defaults.add(new ConnectorDTO("OpenArch_Repository", "Hyland OpenArch Federated Repository", "repository", "org.opencrawling.openarch.repository.OpenArchRepositoryConnector", 10, Map.ofEntries(
            Map.entry("endpointUrl", "http://localhost:8080"),
            Map.entry("repositoryId", "default"),
            Map.entry("username", "Administrator"),
            Map.entry("password", "Administrator"),
            Map.entry("crawlMode", "QUERY"),
            Map.entry("hxqlQuery", "SELECT * FROM SysFile"),
            Map.entry("rootFolderId", "00000000-0000-0000-0000-000000000000"),
            Map.entry("includeSubfolders", "true"),
            Map.entry("batchSize", "100"),
            Map.entry("includeAcls", "true"),
            Map.entry("includeContentStream", "true"),
            Map.entry("incremental", "false")
        )));
        defaults.add(new ConnectorDTO("PGVector_Output", "PGVector Store", "output", "org.opencrawling.vector.VectorOutputConnector", 10, Map.of(
            "pgVectorUrl", "jdbc:postgresql://127.0.0.1:5432/opencrawling",
            "pgVectorUsername", "opencrawling",
            "pgVectorPassword", "opencrawling_password",
            "pgVectorTableName", "vector_store",
            "pgVectorDimensions", "1024"
        )));
        defaults.add(new ConnectorDTO("Milvus_Output", "Milvus Vector Database Store", "output", "org.opencrawling.milvus.MilvusOutputConnector", 10, Map.of(
            "milvusUri", "http://localhost:19530",
            "milvusToken", "root:Milvus",
            "milvusCollection", "enterprise_kb",
            "milvusVectorField", "embeddings",
            "milvusDimensions", "1024",
            "milvusMetricType", "COSINE",
            "milvusIndexType", "HNSW"
        )));
        defaults.add(new ConnectorDTO("Qdrant_Output", "Qdrant Vector Database Store", "output", "org.opencrawling.qdrant.QdrantOutputConnector", 10, Map.of(
            "qdrantUri", "http://localhost:6333",
            "qdrantHost", "localhost",
            "qdrantPort", "6334",
            "qdrantCollection", "enterprise_kb",
            "qdrantDimensions", "1024",
            "qdrantDistance", "Cosine"
        )));
        defaults.add(new ConnectorDTO("OpenSearch2_Output", "OpenSearch 2.x Search Engine Store", "output", "org.opencrawling.opensearch2.OpenSearch2OutputConnector", 10, Map.of(
            "opensearch2Uris", "http://localhost:9200",
            "opensearch2Username", "admin",
            "opensearch2Password", "admin",
            "opensearch2IndexName", "enterprise_kb",
            "opensearch2Dimensions", "1024"
        )));
        defaults.add(new ConnectorDTO("OpenSearch3_Output", "OpenSearch 3.x Search Engine Store", "output", "org.opencrawling.opensearch3.OpenSearch3OutputConnector", 10, Map.of(
            "opensearch3Uris", "http://localhost:9200",
            "opensearch3Username", "admin",
            "opensearch3Password", "admin",
            "opensearch3IndexName", "enterprise_kb",
            "opensearch3Dimensions", "1024"
        )));
        defaults.add(new ConnectorDTO("Vespa_Output", "Vespa Hybrid Search Store", "output", "org.opencrawling.vespa.VespaOutputConnector", 10, Map.of(
            "vespaEndpoint", "http://localhost:8080",
            "vespaConfigEndpoint", "http://localhost:19071",
            "vespaNamespace", "opencrawling",
            "vespaDocumentType", "opencrawling_chunk",
            "vespaDimensions", "1024",
            "vespaTimeoutSeconds", "30",
            "vespaTlsEnabled", "false"
        )));
        defaults.add(new ConnectorDTO("Solr_Output", "Apache Solr 10 Vector Search Store", "output", "org.opencrawling.solr.SolrOutputConnector", 10, Map.ofEntries(
            Map.entry("solrMode", "standalone"),
            Map.entry("solrUrl", "http://localhost:8983/solr"),
            Map.entry("solrZkHost", "localhost:2181"),
            Map.entry("solrCollection", "enterprise_kb"),
            Map.entry("solrDimensions", "1024"),
            Map.entry("solrSimilarity", "cosine"),
            Map.entry("solrVectorEncoding", "FLOAT32"),
            Map.entry("solrQuantization", "none"),
            Map.entry("solrHnswMaxConnections", "16"),
            Map.entry("solrHnswBeamWidth", "100"),
            Map.entry("solrEfSearch", "100"),
            Map.entry("solrCommitWithinMs", "1000")
        )));
        defaults.add(new ConnectorDTO("Luxir_Output", "Luxir Hybrid Search Vector Store", "output", "org.opencrawling.luxir.LuxirOutputConnector", 10, Map.of(
            "luxirEndpoint", "http://localhost:9400",
            "luxirCollection", "opencrawling",
            "luxirVectorField", "embedding_v",
            "luxirDimensions", "1024",
            "luxirSimilarity", "cosine"
        )));
        defaults.add(new ConnectorDTO("SeaTunnel_Output", "Apache SeaTunnel Distributed Fan-Out", "output", "org.opencrawling.seatunnel.SeaTunnelOutputConnector", 10, Map.of(
            "seaTunnelRestUrl", "http://localhost:8080",
            "seaTunnelJobName", "opencrawling_ingestion_pipeline",
            "seaTunnelJobMode", "STREAMING",
            "seaTunnelKafkaBootstrapServers", "localhost:9092",
            "seaTunnelKafkaTopic", "opencrawling-embedded",
            "seaTunnelTargetSinks", "clickhouse,milvus"
        )));
        defaults.add(new ConnectorDTO("Ozone_Migration_Output", "Apache Ozone Migration Target (Migration Mode only)", "output", "org.opencrawling.ozone.OzoneOutputConnector", 10, Map.of(
            "clientType", "NATIVE",
            "volume", "s3v",
            "bucket", "migration",
            "omHost", "localhost",
            "omPort", "9862",
            "s3Endpoint", "http://localhost:9878",
            "keyStrategy", "HIERARCHICAL",
            "tombstoneAction", "DELETE_KEY"
        )));
        defaults.add(new ConnectorDTO("OpenArch_Output", "Hyland OpenArch Federated Ingestion & Parquet Vector Bridge", "output", "org.opencrawling.openarch.output.OpenArchOutputConnector", 10, Map.ofEntries(
            Map.entry("endpointUrl", "http://localhost:8080"),
            Map.entry("repositoryId", "default"),
            Map.entry("username", "Administrator"),
            Map.entry("password", "Administrator"),
            Map.entry("rootFolderId", "00000000-0000-0000-0000-000000000000"),
            Map.entry("parquetBatchThreshold", "50"),
            Map.entry("embeddingsType", "mxbai-embed-large"),
            Map.entry("dimensions", "1024"),
            Map.entry("upsert", "true"),
            Map.entry("syncAcls", "true"),
            Map.entry("uploadBinaries", "true")
        )));
        defaults.add(new ConnectorDTO("Ollama_Embedding_Default", "Local Ollama Embeddings using mxbai-embed-large", "transformation", "org.opencrawling.embedding.OllamaEmbeddingConnector", 10, Map.of("baseUrl", "http://localhost:11434", "engine", "ollama", "model", "mxbai-embed-large")));
        defaults.add(new ConnectorDTO("OpenAI_Embedding_Prod", "Production OpenAI Embeddings", "transformation", "org.opencrawling.embedding.OpenAIEmbeddingConnector", 10, Map.of("engine", "openai", "model", "text-embedding-3-small", "apiKey", "sk-placeholder")));
        
        // Load persisted list
        this.storage = new CopyOnWriteArrayList<>(PersistenceHelper.loadList("connectors.json", ConnectorDTO.class, defaults));
        
        // Ensure standard built-in defaults exist in storage even if an existing connectors.json file was loaded from disk
        for (ConnectorDTO def : defaults) {
            if (storage.stream().noneMatch(c -> c.name().equalsIgnoreCase(def.name()))) {
                storage.add(def);
            }
        }
        
        // Dynamically discover SPI connectors loaded via plugin classloaders
        try {
            java.util.ServiceLoader.load(org.opencrawling.core.connector.RepositoryConnector.class).forEach(conn -> {
                String name = conn.getName();
                if (storage.stream().noneMatch(c -> c.name().equalsIgnoreCase(name))) {
                    storage.add(new ConnectorDTO(name, name + " (Dynamic Repository Connector)", "repository", conn.getClass().getName(), 10, new HashMap<>()));
                }
            });
            java.util.ServiceLoader.load(org.opencrawling.core.connector.OutputConnector.class).forEach(conn -> {
                String name = conn.getName();
                if (storage.stream().noneMatch(c -> c.name().equalsIgnoreCase(name))) {
                    storage.add(new ConnectorDTO(name, name + " (Dynamic Output Connector)", "output", conn.getClass().getName(), 10, new HashMap<>()));
                }
            });
            java.util.ServiceLoader.load(org.opencrawling.core.connector.TransformationConnector.class).forEach(conn -> {
                String name = conn.getName();
                if (storage.stream().noneMatch(c -> c.name().equalsIgnoreCase(name))) {
                    storage.add(new ConnectorDTO(name, name + " (Dynamic Transformation Connector)", "transformation", conn.getClass().getName(), 10, new HashMap<>()));
                }
            });
        } catch (Throwable t) {
            System.err.println("Notice: Dynamic SPI Connector discovery skipped: " + t.getMessage());
        }
    }

    @Operation(summary = "List all connectors", description = "Retrieves all configured connectors across all categories.")
    @ApiResponse(responseCode = "200", description = "List of all configured connectors successfully retrieved")
    @GetMapping
    public List<ConnectorDTO> getAllConnectors() {
        return storage;
    }

    @Operation(summary = "List connectors by type", description = "Retrieves all configured connectors filtered by category ('repository', 'output', or 'transformation').")
    @ApiResponse(responseCode = "200", description = "Filtered list of connectors successfully retrieved")
    @GetMapping("/{type}")
    public List<ConnectorDTO> getConnectors(
            @Parameter(description = "Connector category type ('repository', 'output', or 'transformation')", example = "repository") @PathVariable String type) {
        return storage.stream()
                .filter(c -> c.type().equalsIgnoreCase(type))
                .toList();
    }

    @Operation(summary = "Create or update connector", description = "Saves or updates a connector configuration definition.")
    @ApiResponse(responseCode = "201", description = "Connector definition saved successfully")
    @PostMapping
    public ResponseEntity<Void> createConnector(@RequestBody ConnectorDTO connector) {
        System.out.println("Saving connector: " + connector.name());
        storage.removeIf(c -> c.name().equals(connector.name()));
        storage.add(connector);
        PersistenceHelper.save("connectors.json", storage);
        return ResponseEntity.status(201).build();
    }

    @Operation(summary = "Test connector connectivity", description = "Performs a live connectivity and authentication verification check against the target system.")
    @ApiResponse(responseCode = "200", description = "Connection check result with status and diagnostic message")
    @PostMapping("/check")
    public ResponseEntity<ConnectionCheckResult> checkConnection(@RequestBody ConnectorDTO connector) {
        System.out.println("Checking connection for connector: " + connector.name() + " (" + connector.className() + ")");
        ConnectorDTO target = connector;
        if (target.className() == null && target.name() != null) {
            ConnectorDTO stored = storage.stream()
                    .filter(c -> c.name().equalsIgnoreCase(connector.name()))
                    .findFirst()
                    .orElse(null);
            if (stored != null) {
                Map<String, String> mergedConfig = new HashMap<>(stored.configuration() != null ? stored.configuration() : Map.of());
                if (connector.configuration() != null) {
                    mergedConfig.putAll(connector.configuration());
                }
                target = new ConnectorDTO(
                        stored.name(),
                        connector.description() != null ? connector.description() : stored.description(),
                        connector.type() != null ? connector.type() : stored.type(),
                        stored.className(),
                        connector.maxConnections() != null ? connector.maxConnections() : stored.maxConnections(),
                        mergedConfig
                );
            }
        }
        ConnectionCheckResult result = checkerService.check(target);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Delete connector", description = "Removes a configured connector definition by its name/id.")
    @ApiResponse(responseCode = "200", description = "Connector deleted successfully")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConnector(
            @Parameter(description = "Connector unique identifier or name", example = "FileSystem_Local") @PathVariable String id) {
        storage.removeIf(c -> c.name().equals(id));
        PersistenceHelper.save("connectors.json", storage);
        return ResponseEntity.ok().build();
    }

    @Schema(description = "Connector configuration and metadata definition")
    public static record ConnectorDTO(
        @Schema(description = "Unique connector identifier or name", example = "FileSystem_Local")
        String name, 
        @Schema(description = "Human-readable description of the connector", example = "Local File System")
        String description, 
        @Schema(description = "Connector category: 'repository', 'output', or 'transformation'", example = "repository")
        String type, 
        @Schema(description = "Fully-qualified Java class name of the connector implementation", example = "org.opencrawling.crawler.connectors.filesystem.FileConnector")
        String className, 
        @Schema(description = "Maximum concurrent connections allowed for this connector", example = "10")
        Integer maxConnections, 
        @Schema(description = "Key-value configuration properties specific to this connector implementation")
        Map<String, String> configuration
    ) {}
}
