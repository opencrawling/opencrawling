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
package org.opencrawling.runtime.mcp;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * OpenCrawling Federated MCP Server for Secure Vector Store interaction.
 * Exposes tools to LLMs for querying vector databases securely.
 * Enforces server-side ACL (Access Control List) checks before returning matches.
 */
@Component
@ConditionalOnProperty(name = "opencrawling.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(VectorStore.class)
public class McpVectorServer {

    private static final Logger log = LoggerFactory.getLogger(McpVectorServer.class);
    private final VectorStore vectorStore;
    private final VectorStore vectorStore384;
    private final VectorStore vectorStore768;
    private final VectorStore vectorStore1024;

    public McpVectorServer(VectorStore vectorStore) {
        this(vectorStore, vectorStore, vectorStore, vectorStore);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public McpVectorServer(
            VectorStore vectorStore,
            VectorStore vectorStore384,
            VectorStore vectorStore768,
            VectorStore vectorStore1024) {
        this.vectorStore = vectorStore;
        this.vectorStore384 = vectorStore384;
        this.vectorStore768 = vectorStore768;
        this.vectorStore1024 = vectorStore1024;
        log.info("Initialized OpenCrawling Secure MCP Server module.");
    }

    private VectorStore getVectorStore(Integer dimensions) {
        if (dimensions == null) return vectorStore;
        return switch (dimensions) {
            case 384 -> vectorStore384;
            case 768 -> vectorStore768;
            case 1024 -> vectorStore1024;
            default -> vectorStore;
        };
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

    private boolean isAccessible(Map<String, Object> metadata, String userPrincipal, List<String> userGroups) {
        // 1. If security metadata is missing, fall back to flat "acl" string check for backward compatibility
        if (metadata == null || !metadata.containsKey("security")) {
            String aclVal = (String) (metadata != null ? metadata.getOrDefault("acl", "public") : "public");
            if ("public".equalsIgnoreCase(aclVal)) return true;
            if (userPrincipal != null && userPrincipal.equalsIgnoreCase(aclVal)) return true;
            if (userGroups != null && userGroups.contains(aclVal)) return true;
            return false;
        }

        // 2. Parse the security object
        Object securityObj = metadata.get("security");
        if (!(securityObj instanceof Map)) {
            return false;
        }

        Map<?, ?> securityMap = (Map<?, ?>) securityObj;
        Object permissionsObj = securityMap.get("permissions");
        if (!(permissionsObj instanceof List)) return false;
        List<?> permissionsList = (List<?>) permissionsObj;

        boolean hasReadAccess = false;

        for (Object permObj : permissionsList) {
            if (permObj instanceof Map) {
                Map<?, ?> perm = (Map<?, ?>) permObj;
                String identity = (String) perm.get("identity");
                String identityType = (String) perm.get("identityType");
                String access = (String) perm.get("access");

                if (identity == null || access == null) continue;

                // Check if this rule applies to the user
                boolean matchesUser = false;
                if ("public".equalsIgnoreCase(identity) || "public".equalsIgnoreCase(identityType)) {
                    matchesUser = true;
                } else if (userPrincipal != null && userPrincipal.equalsIgnoreCase(identity)) {
                    matchesUser = true;
                } else if (userGroups != null && userGroups.stream().anyMatch(g -> g.equalsIgnoreCase(identity))) {
                    matchesUser = true;
                }

                if (matchesUser) {
                    if ("deny".equalsIgnoreCase(access)) {
                        // Explicit DENY rules override everything in OIS
                        return false;
                    } else if ("read".equalsIgnoreCase(access) || "write".equalsIgnoreCase(access)) {
                        hasReadAccess = true;
                    }
                }
            }
        }

        return hasReadAccess;
    }

    @McpTool(description = "Perform a secure similarity search on vectorized enterprise knowledge documents. Results are filtered on the server side using the caller's identity (principal/groups) and security Access Control Lists (ACLs) associated with each document, ensuring the LLM never receives unauthorized content.")
    public List<DocumentSearchResult> secureVectorSearch(
            @McpToolParam(description = "The natural language query or keywords to search for", required = true) String query,
            @McpToolParam(description = "The user principal identity or email of the caller to enforce ACL check", required = true) String userPrincipal,
            @McpToolParam(description = "Comma-separated list of groups/roles the caller belongs to (e.g. 'finance,engineering')", required = false) String userRoles,
            @McpToolParam(description = "Maximum number of search results to return", required = false) Integer maxResults,
            @McpToolParam(description = "Minimum similarity threshold score (0.0 to 1.0)", required = false) Double minScore,
            @McpToolParam(description = "Dimensions of the target vector store to query (384, 768, 1024)", required = false) Integer dimensions
    ) {
        log.info("MCP Security Search Request received. Query: '{}', Principal: '{}', Roles: '{}', Dimensions: {}", query, userPrincipal, userRoles, dimensions);

        int limit = (maxResults != null && maxResults > 0) ? maxResults : 5;
        double threshold = (minScore != null) ? minScore : 0.0;

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        
        // Base security filter: always allow public documents
        var securityExpression = b.eq("acl", "public");

        // Expand filter if user principal is provided
        if (userPrincipal != null && !userPrincipal.trim().isBlank()) {
            securityExpression = b.or(securityExpression, b.eq("acl", userPrincipal.trim()));
        }

        // Expand filter if user roles/groups are provided
        List<String> rolesList = new ArrayList<>();
        if (userRoles != null && !userRoles.trim().isBlank()) {
            rolesList = Arrays.stream(userRoles.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!rolesList.isEmpty()) {
                securityExpression = b.or(securityExpression, b.in("acl", rolesList));
            }
        }

        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .filterExpression(securityExpression.build())
                .topK(limit)
                .similarityThreshold(threshold)
                .build();

        try {
            List<Document> docs = getVectorStore(dimensions).similaritySearch(searchRequest);
            log.info("Found {} pre-filtered candidates in Vector Store for query '{}'", docs.size(), query);

            final List<String> finalRolesList = rolesList;
            return docs.stream()
                    .filter(doc -> isAccessible(doc.getMetadata(), userPrincipal, finalRolesList))
                    .limit(limit)
                    .map(doc -> new DocumentSearchResult(
                            doc.getId(),
                            (String) doc.getMetadata().getOrDefault("uri", ""),
                            doc.getText(),
                            doc.getMetadata(),
                            (String) doc.getMetadata().getOrDefault("acl", "public"),
                            doc.getScore() != null ? doc.getScore() : 0.0
                    ))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to perform secure vector search: {}", e.getMessage(), e);
            throw new RuntimeException("Error executing secure vector search", e);
        }
    }

    @McpTool(description = "Retrieve the full text content and metadata of a specific document by its URI or ID, enforcing security checks to ensure the caller has access permissions.")
    public DocumentDetailsResult getDocumentContent(
            @McpToolParam(description = "The URI of the document to retrieve (e.g. file:///path/to/doc.txt)", required = true) String documentUri,
            @McpToolParam(description = "The user principal identity of the caller", required = true) String userPrincipal,
            @McpToolParam(description = "Comma-separated list of groups/roles the caller belongs to", required = false) String userRoles,
            @McpToolParam(description = "Dimensions of the target vector store to query (384, 768, 1024)", required = false) Integer dimensions
    ) {
        log.info("MCP Get Document Details Request. URI: '{}', Principal: '{}', Dimensions: {}", documentUri, userPrincipal, dimensions);

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        
        // Base security filter
        var securityExpression = b.eq("acl", "public");
        if (userPrincipal != null && !userPrincipal.trim().isBlank()) {
            securityExpression = b.or(securityExpression, b.eq("acl", userPrincipal.trim()));
        }
        if (userRoles != null && !userRoles.trim().isBlank()) {
            List<String> rolesList = Arrays.stream(userRoles.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!rolesList.isEmpty()) {
                securityExpression = b.or(securityExpression, b.in("acl", rolesList));
            }
        }

        // Must match both the URI and the security check
        var finalExpression = b.and(b.eq("uri", documentUri), securityExpression).build();

        SearchRequest searchRequest = SearchRequest.builder()
                .query("")
                .filterExpression(finalExpression)
                .topK(20)
                .build();

        try {
            List<Document> docs = getVectorStore(dimensions).similaritySearch(searchRequest);
            
            List<String> rolesList = new ArrayList<>();
            if (userRoles != null && !userRoles.trim().isBlank()) {
                rolesList = Arrays.stream(userRoles.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
            }

            final List<String> finalRolesList = rolesList;
            List<Document> accessibleDocs = docs.stream()
                    .filter(doc -> isAccessible(doc.getMetadata(), userPrincipal, finalRolesList))
                    .toList();

            if (accessibleDocs.isEmpty()) {
                log.warn("Document with URI '{}' not found or access denied for user '{}'", documentUri, userPrincipal);
                throw new NoSuchElementException("Document not found or access denied.");
            }

            Document doc = accessibleDocs.get(0);
            return new DocumentDetailsResult(
                    doc.getId(),
                    (String) doc.getMetadata().getOrDefault("uri", ""),
                    doc.getText(),
                    doc.getMetadata(),
                    (String) doc.getMetadata().getOrDefault("acl", "public")
            );
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to retrieve document details: {}", e.getMessage(), e);
            throw new RuntimeException("Error retrieving document details", e);
        }
    }

    @McpTool(description = "List all documents currently indexed in the vector store that are accessible to the caller, showing their URIs, creation metadata, and access rules.")
    public List<Map<String, Object>> listAccessibleSources(
            @McpToolParam(description = "The user principal identity of the caller", required = true) String userPrincipal,
            @McpToolParam(description = "Comma-separated list of groups/roles the caller belongs to", required = false) String userRoles,
            @McpToolParam(description = "Dimensions of the target vector store to query (384, 768, 1024)", required = false) Integer dimensions
    ) {
        log.info("MCP List Accessible Sources Request. Principal: '{}', Roles: '{}', Dimensions: {}", userPrincipal, userRoles, dimensions);

        SearchRequest searchRequest = SearchRequest.builder()
                .query("")
                .topK(200)
                .build();

        try {
            List<Document> docs = getVectorStore(dimensions).similaritySearch(searchRequest);
            
            List<String> rolesList = new ArrayList<>();
            if (userRoles != null && !userRoles.trim().isBlank()) {
                rolesList = Arrays.stream(userRoles.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
            }

            final List<String> finalRolesList = rolesList;
            return docs.stream()
                    .filter(doc -> isAccessible(doc.getMetadata(), userPrincipal, finalRolesList))
                    .map(doc -> {
                        Map<String, Object> summary = new HashMap<>();
                        summary.put("id", doc.getId());
                        summary.put("uri", doc.getMetadata().getOrDefault("uri", ""));
                        summary.put("acl", doc.getMetadata().getOrDefault("acl", "public"));
                        summary.put("security", doc.getMetadata().get("security"));
                        summary.put("lastModified", doc.getMetadata().getOrDefault("lastModified", ""));
                        return summary;
                    })
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.error("Failed to list accessible sources: {}", e.getMessage(), e);
            throw new RuntimeException("Error listing accessible sources", e);
        }
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
