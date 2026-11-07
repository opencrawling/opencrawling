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

import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.mcp.backend.PgVectorMcpSearchBackend;
import org.opencrawling.runtime.security.CallerResolver;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * OpenCrawling Federated MCP Server for Secure Vector Store interaction.
 * Exposes canonical tools to LLMs for querying any supported vector database securely
 * behind a unified tool contract and server-side Zero-Trust ACL enforcement.
 */
@Component
@ConditionalOnProperty(name = "opencrawling.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
public class McpVectorServer {

    private static final Logger log = LoggerFactory.getLogger(McpVectorServer.class);

    /** Identity rule text shared by the tool parameter descriptions. */
    static final String IDENTITY_NOTE = " Ignored when the server verifies callers by bearer token: the token's identity is used instead.";

    private final McpVectorSearchBackend backend;
    private CallerResolver callerResolver = CallerResolver.asserted();

    @Autowired
    public McpVectorServer(@Autowired(required = false) McpVectorSearchBackend backend) {
        this.backend = backend;
        if (backend != null) {
            log.info("Initialized OpenCrawling Secure MCP Server backed by {} (SPI: {}).",
                    backend.backendType(), backend.getClass().getSimpleName());
        } else {
            log.info("OpenCrawling Secure MCP Server initialized without active vector backend (observability/metadata only).");
        }
    }

    /**
     * Backward-compatibility constructor for tests or programmatic VectorStore injection.
     */
    public McpVectorServer(VectorStore vectorStore) {
        this(new PgVectorMcpSearchBackend(vectorStore, vectorStore, vectorStore, vectorStore, null, null));
    }

    /**
     * Data Transfer Object for query results.
     */
    public record DocumentSearchResult(
            String id,
            String uri,
            String content,
            Map<String, Object> metadata,
            String acl,
            double score
    ) {}

    /**
     * Data Transfer Object for full document details.
     */
    public record DocumentDetailsResult(
            String id,
            String uri,
            String content,
            Map<String, Object> metadata,
            String acl
    ) {}

    public McpVectorSearchBackend getBackend() {
        return backend;
    }

    /**
     * How tool calls get their caller; without a resolver (tests, programmatic use) the asserted arguments are used.
     */
    @Autowired(required = false)
    public void setCallerResolver(CallerResolver callerResolver) {
        if (callerResolver != null) {
            this.callerResolver = callerResolver;
        }
    }

    @McpTool(description = "Perform a secure similarity search on vectorized enterprise knowledge documents. Results are filtered on the server side using the caller's identity (principal/groups) and security Access Control Lists (ACLs) associated with each document, ensuring the LLM never receives unauthorized content.")
    public List<DocumentSearchResult> secureVectorSearch(
            @McpToolParam(description = "The natural language query or keywords to search for", required = true) String query,
            @McpToolParam(description = "The user principal identity or email of the caller to enforce ACL check." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated list of groups/roles the caller belongs to (e.g. 'finance,engineering')." + IDENTITY_NOTE, required = false) String userRoles,
            @McpToolParam(description = "Maximum number of search results to return", required = false) Integer maxResults,
            @McpToolParam(description = "Minimum similarity threshold score (0.0 to 1.0)", required = false) Double minScore,
            @McpToolParam(description = "Dimensions of the vector index to read (384, 768, 1024). Usually left out: the server then reads the index the documents were embedded into", required = false) Integer dimensions
    ) {
        if (backend == null) {
            throw new IllegalStateException("Vector search is unavailable: no vector database backend is configured for the active output type.");
        }
        int limit = (maxResults != null && maxResults > 0) ? maxResults : 5;
        double threshold = (minScore != null) ? minScore : 0.0;
        Caller caller = callerResolver.resolve(userPrincipal, userRoles);
        return backend.secureVectorSearch(query, caller.principal(), caller.groups(), limit, threshold, dimensions);
    }

    @McpTool(description = "Retrieve the full text content and metadata of a specific document by its URI or ID, enforcing security checks to ensure the caller has access permissions.")
    public DocumentDetailsResult getDocumentContent(
            @McpToolParam(description = "The URI of the document to retrieve (e.g. file:///path/to/doc.txt)", required = true) String documentUri,
            @McpToolParam(description = "The user principal identity of the caller." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated list of groups/roles the caller belongs to." + IDENTITY_NOTE, required = false) String userRoles,
            @McpToolParam(description = "Dimensions of the vector index to read (384, 768, 1024). Usually left out: the server then reads the index the documents were embedded into", required = false) Integer dimensions
    ) {
        if (backend == null) {
            throw new IllegalStateException("Document retrieval is unavailable: no vector database backend is configured for the active output type.");
        }
        Caller caller = callerResolver.resolve(userPrincipal, userRoles);
        return backend.getDocumentContent(documentUri, caller.principal(), caller.groups(), dimensions);
    }

    @McpTool(description = "List all documents currently indexed in the vector store that are accessible to the caller, showing their URIs, creation metadata, and access rules.")
    public List<Map<String, Object>> listAccessibleSources(
            @McpToolParam(description = "The user principal identity of the caller." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated list of groups/roles the caller belongs to." + IDENTITY_NOTE, required = false) String userRoles,
            @McpToolParam(description = "Dimensions of the vector index to read (384, 768, 1024). Usually left out: the server then reads the index the documents were embedded into", required = false) Integer dimensions
    ) {
        Caller caller = callerResolver.resolve(userPrincipal, userRoles);
        if (backend == null) {
            return Collections.emptyList();
        }
        return backend.listAccessibleSources(caller.principal(), caller.groups(), dimensions);
    }

    @McpTool(description = "Search Hyland OpenArch federated enterprise content layer using semantic vector search and Zero-Trust permissions.")
    public List<DocumentSearchResult> openarch_search(
            @McpToolParam(description = "Natural language or keyword query", required = true) String query,
            @McpToolParam(description = "The caller user identity." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated caller roles or groups." + IDENTITY_NOTE, required = false) String userRoles,
            @McpToolParam(description = "Maximum matching results", required = false) Integer maxResults,
            @McpToolParam(description = "Minimum score threshold", required = false) Double minScore
    ) {
        return secureVectorSearch(query, userPrincipal, userRoles, maxResults, minScore, null);
    }

    @McpTool(description = "Fetch full document context and metadata from Hyland OpenArch federated repository.")
    public DocumentDetailsResult openarch_fetch_document(
            @McpToolParam(description = "The document ID or URI in OpenArch", required = true) String documentId,
            @McpToolParam(description = "The caller user identity." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated caller roles or groups." + IDENTITY_NOTE, required = false) String userRoles
    ) {
        String uri = documentId.startsWith("openarch://") ? documentId : "openarch://default/" + documentId;
        return getDocumentContent(uri, userPrincipal, userRoles, null);
    }

    public List<DocumentSearchResult> secureVectorSearch(
            String query,
            String userPrincipal,
            String userRoles,
            Integer maxResults,
            Double minScore
    ) {
        return secureVectorSearch(query, userPrincipal, userRoles, maxResults, minScore, null);
    }

    public DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            String userRoles
    ) {
        return getDocumentContent(documentUri, userPrincipal, userRoles, null);
    }

    public List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            String userRoles
    ) {
        return listAccessibleSources(userPrincipal, userRoles, null);
    }
}
