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
package org.opencrawling.runtime.mcp;

import java.util.List;
import java.util.Map;

/**
 * Service Provider Interface (SPI) for storage-specific vector search and retrieval backends
 * powering the unified OpenCrawling Secure Model Context Protocol (MCP) tool surface.
 */
public interface McpVectorSearchBackend {

    /**
     * Storage backend type identifier (e.g., "pgvector", "vespa", "qdrant", "milvus", "opensearch2", "opensearch3", "elasticsearch", "solr", "luxir").
     */
    String backendType();

    /**
     * Performs a secure similarity/hybrid search on indexed documents with server-side Zero-Trust ACL enforcement.
     *
     * @param query natural language or keyword query
     * @param userPrincipal identity of the caller
     * @param userRoles roles or groups associated with the caller
     * @param maxResults maximum matching items to return
     * @param minScore minimum similarity/relevance threshold
     * @param dimensions optional dimensions (384, 768, 1024)
     * @return filtered accessible search results
     */
    List<McpVectorServer.DocumentSearchResult> secureVectorSearch(
            String query,
            String userPrincipal,
            List<String> userRoles,
            int maxResults,
            double minScore,
            Integer dimensions
    );

    /**
     * Retrieves content and metadata of a specific document by URI or ID, enforcing access controls.
     *
     * @param documentUri URI of document
     * @param userPrincipal identity of the caller
     * @param userRoles roles or groups associated with the caller
     * @param dimensions optional dimensions
     * @return document details result
     */
    McpVectorServer.DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    );

    /**
     * Lists accessible sources currently indexed in this store.
     *
     * @param userPrincipal identity of the caller
     * @param userRoles roles or groups associated with the caller
     * @param dimensions optional dimensions
     * @return list of source metadata summaries
     */
    List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    );
}
