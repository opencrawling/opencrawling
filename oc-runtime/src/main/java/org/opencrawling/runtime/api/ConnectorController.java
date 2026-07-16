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

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {

    private final List<ConnectorDTO> storage;

    public ConnectorController() {
        // Initial mock data defaults
        List<ConnectorDTO> defaults = new ArrayList<>();
        defaults.add(new ConnectorDTO("FileSystem_Local", "Local File System", "repository", "org.opencrawling.crawler.connectors.filesystem.FileConnector", 10, new HashMap<>()));
        defaults.add(new ConnectorDTO("Alfresco_Content_Services", "Alfresco Repository", "repository", "org.opencrawling.alfresco.AlfrescoRepositoryConnector", 10, Map.of("url", "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1", "username", "admin", "password", "admin", "batchSize", "100")));
        defaults.add(new ConnectorDTO("Apache_Iceberg_Local", "Local Iceberg Warehouse Catalog", "repository", "org.opencrawling.iceberg.IcebergRepositoryConnector", 10, Map.of("catalogType", "in-memory", "warehouse", "tmp/iceberg-warehouse")));
        defaults.add(new ConnectorDTO("PGVector_Output", "PGVector Store", "output", "org.opencrawling.vector.VectorOutputConnector", 10, new HashMap<>()));
        defaults.add(new ConnectorDTO("Ollama_Embedding_Default", "Local Ollama Embeddings using mxbai-embed-large", "transformation", "org.opencrawling.embedding.OllamaEmbeddingConnector", 10, Map.of("engine", "ollama", "model", "mxbai-embed-large")));
        defaults.add(new ConnectorDTO("OpenAI_Embedding_Prod", "Production OpenAI Embeddings", "transformation", "org.opencrawling.embedding.OpenAIEmbeddingConnector", 10, Map.of("engine", "openai", "model", "text-embedding-3-small", "apiKey", "sk-placeholder")));
        
        // Load persisted list
        this.storage = new CopyOnWriteArrayList<>(PersistenceHelper.loadList("connectors.json", ConnectorDTO.class, defaults));
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
        // Simple duplicate check by name
        storage.removeIf(c -> c.name().equals(connector.name()));
        storage.add(connector);
        PersistenceHelper.save("connectors.json", storage);
        return ResponseEntity.status(201).build();
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
        int maxConnections, 
        Map<String, String> configuration
    ) {}
}
