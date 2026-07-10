package org.opencrawling.runtime.mcp;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
public class McpVectorServer {

    private static final Logger log = LoggerFactory.getLogger(McpVectorServer.class);
    private final VectorStore vectorStore;

    public McpVectorServer(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        log.info("Initialized OpenCrawling Secure MCP Server module.");
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

    @McpTool(description = "Perform a secure similarity search on vectorized enterprise knowledge documents. Results are filtered on the server side using the caller's identity (principal/groups) and security Access Control Lists (ACLs) associated with each document, ensuring the LLM never receives unauthorized content.")
    public List<DocumentSearchResult> secureVectorSearch(
            @McpToolParam(description = "The natural language query or keywords to search for", required = true) String query,
            @McpToolParam(description = "The user principal identity or email of the caller to enforce ACL check", required = true) String userPrincipal,
            @McpToolParam(description = "Comma-separated list of groups/roles the caller belongs to (e.g. 'finance,engineering')", required = false) String userRoles,
            @McpToolParam(description = "Maximum number of search results to return", required = false) Integer maxResults,
            @McpToolParam(description = "Minimum similarity threshold score (0.0 to 1.0)", required = false) Double minScore
    ) {
        log.info("MCP Security Search Request received. Query: '{}', Principal: '{}', Roles: '{}'", query, userPrincipal, userRoles);

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
        if (userRoles != null && !userRoles.trim().isBlank()) {
            List<String> rolesList = Arrays.stream(userRoles.split(","))
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
            List<Document> docs = vectorStore.similaritySearch(searchRequest);
            log.info("Found {} secure matches in Vector Store for query '{}'", docs.size(), query);

            return docs.stream()
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
            @McpToolParam(description = "Comma-separated list of groups/roles the caller belongs to", required = false) String userRoles
    ) {
        log.info("MCP Get Document Details Request. URI: '{}', Principal: '{}'", documentUri, userPrincipal);

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
                .query("") // blank search for exact metadata matching
                .filterExpression(finalExpression)
                .topK(1)
                .build();

        try {
            List<Document> docs = vectorStore.similaritySearch(searchRequest);
            if (docs.isEmpty()) {
                log.warn("Document with URI '{}' not found or access denied for user '{}'", documentUri, userPrincipal);
                throw new NoSuchElementException("Document not found or access denied.");
            }

            Document doc = docs.get(0);
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
            @McpToolParam(description = "Comma-separated list of groups/roles the caller belongs to", required = false) String userRoles
    ) {
        log.info("MCP List Accessible Sources Request. Principal: '{}', Roles: '{}'", userPrincipal, userRoles);

        FilterExpressionBuilder b = new FilterExpressionBuilder();
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

        SearchRequest searchRequest = SearchRequest.builder()
                .query("") // retrieve all matches
                .filterExpression(securityExpression.build())
                .topK(100) // limit list size
                .build();

        try {
            List<Document> docs = vectorStore.similaritySearch(searchRequest);
            
            // Map to summary structure
            return docs.stream()
                    .map(doc -> {
                        Map<String, Object> summary = new HashMap<>();
                        summary.put("id", doc.getId());
                        summary.put("uri", doc.getMetadata().getOrDefault("uri", ""));
                        summary.put("acl", doc.getMetadata().getOrDefault("acl", "public"));
                        summary.put("lastModified", doc.getMetadata().getOrDefault("lastModified", ""));
                        return summary;
                    })
                    .distinct() // eliminate duplicate chunks of the same document
                    .toList();
        } catch (Exception e) {
            log.error("Failed to list accessible sources: {}", e.getMessage(), e);
            throw new RuntimeException("Error listing accessible sources", e);
        }
    }
}
