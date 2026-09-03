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
        defaults.add(new ConnectorDTO("Alfresco_Content_Services", "Alfresco Repository", "repository", "org.opencrawling.alfresco.AlfrescoRepositoryConnector", 10, Map.of("url", "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1", "username", "admin", "password", "admin", "batchSize", "100")));
        defaults.add(new ConnectorDTO("Apache_Iceberg_Local", "Local Iceberg Warehouse Catalog", "repository", "org.opencrawling.iceberg.IcebergRepositoryConnector", 10, Map.of("catalogType", "in-memory", "warehouse", "tmp/iceberg-warehouse")));
        defaults.add(new ConnectorDTO("Flowable_REST_Engine", "Flowable REST Engine", "repository", "org.opencrawling.flowable.FlowableRepositoryConnector", 10, Map.of("endpoint", "http://localhost:8080/flowable-rest/service", "username", "rest-admin", "password", "test")));
        defaults.add(new ConnectorDTO("Camunda_7_REST_Engine", "Camunda 7 REST Engine", "repository", "org.opencrawling.camunda.CamundaRepositoryConnector", 10, Map.of("url", "http://localhost:8080/engine-rest", "username", "demo", "password", "demo")));
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

    @GetMapping("/{type}")
    public List<ConnectorDTO> getConnectors(@PathVariable String type) {
        return storage.stream()
                .filter(c -> c.type().equalsIgnoreCase(type))
                .toList();
    }

    @PostMapping
    public ResponseEntity<Void> createConnector(@RequestBody ConnectorDTO connector) {
        System.out.println("Saving connector: " + connector.name());
        storage.removeIf(c -> c.name().equals(connector.name()));
        storage.add(connector);
        PersistenceHelper.save("connectors.json", storage);
        return ResponseEntity.status(201).build();
    }

    @PostMapping("/check")
    public ResponseEntity<ConnectionCheckResult> checkConnection(@RequestBody ConnectorDTO connector) {
        System.out.println("Checking connection for connector: " + connector.name() + " (" + connector.className() + ")");
        ConnectionCheckResult result = checkerService.check(connector);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConnector(@PathVariable String id) {
        storage.removeIf(c -> c.name().equals(id));
        PersistenceHelper.save("connectors.json", storage);
        return ResponseEntity.ok().build();
    }

    public static record ConnectorDTO(
        String name, 
        String description, 
        String type, 
        String className, 
        Integer maxConnections, 
        Map<String, String> configuration
    ) {}
}
