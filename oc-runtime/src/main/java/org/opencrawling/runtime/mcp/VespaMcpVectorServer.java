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
import org.opencrawling.runtime.mcp.backend.VespaMcpSearchBackend;
import org.opencrawling.runtime.security.CallerResolver;
import org.opencrawling.vespa.config.VespaOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Vespa-specific MCP Server delegate retained for backward compatibility with existing
 * Vespa MCP clients and integration scripts. Delegates core execution to {@link VespaMcpSearchBackend}.
 */
@Component
@ConditionalOnProperty(name = "opencrawling.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("'${spring.opencrawling.output.type:pgvector}' == 'vespa'")
public class VespaMcpVectorServer {

    private static final Logger log = LoggerFactory.getLogger(VespaMcpVectorServer.class);

    private final VespaMcpSearchBackend backend;
    private CallerResolver callerResolver = CallerResolver.asserted();

    @Autowired
    public VespaMcpVectorServer(VespaMcpSearchBackend backend) {
        this.backend = backend;
        log.info("Initialized VespaMcpVectorServer delegate.");
    }

    public VespaMcpVectorServer(VespaOutputProperties properties,
                                 @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        this(new VespaMcpSearchBackend(properties, embeddingModel, null));
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

    public record VespaSearchHit(String chunkId, String uri, String content, String acl, double relevance) {}

    public record VespaSecureSearchResult(List<VespaSearchHit> hits, int totalCount, boolean degraded, String message) {}

    public record VespaDocumentDetails(String chunkId, String uri, String content, String acl, List<String> allowedRead) {}

    public record RawHit(String chunkId, String uri, String content, String acl, List<String> allowedRead, List<String> deniedRead, double relevance) {}

    @McpTool(description = "Perform a secure search on vectorized documents stored in Vespa. Defaults to Vespa's 'hybrid' rank profile, fusing BM25 keyword scoring with vector similarity in a single query. Results are filtered on the server side using the caller's identity/groups against each document's security rules before anything is returned to the LLM.")
    public VespaSecureSearchResult vespaSecureVectorSearch(
            @McpToolParam(description = "The natural language query or keywords to search for", required = true) String query,
            @McpToolParam(description = "The user principal identity or email of the caller to enforce ACL check." + McpVectorServer.IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated list of groups/roles the caller belongs to (e.g. 'finance,engineering')." + McpVectorServer.IDENTITY_NOTE, required = false) String userRoles,
            @McpToolParam(description = "Maximum number of results to return (default 5)", required = false) Integer maxResults,
            @McpToolParam(description = "Vespa rank profile: 'hybrid' (default, BM25+vector fused), 'semantic' (vector only), or 'default' (BM25 only)", required = false) String rankProfile,
            @McpToolParam(description = "Dimensions of the target document type to query (384, 768, 1024)", required = false) Integer dimensions
    ) {
        int limit = (maxResults != null && maxResults > 0) ? maxResults : 5;
        Caller caller = callerResolver.resolve(userPrincipal, userRoles);
        String principal = caller.principal();
        List<String> roles = caller.groups();
        String documentType = backend.resolveDocumentType(dimensions);
        String effectiveRankProfile = (rankProfile == null || rankProfile.isBlank()) ? "hybrid" : rankProfile.toLowerCase();

        try {
            VespaMcpSearchBackend.RawSearchResult raw = backend.executeSearch(documentType, query, effectiveRankProfile, limit, principal, roles);
            List<VespaSearchHit> accessible = backend.readableHits(principal, roles, raw.hits()).stream()
                    .limit(limit)
                    .map(hit -> new VespaSearchHit(hit.chunkId(), hit.uri(), hit.content(), hit.acl(), hit.relevance()))
                    .toList();
            return new VespaSecureSearchResult(accessible, raw.totalCount(), raw.degraded(), raw.message());
        } catch (Exception e) {
            log.error("Vespa MCP secure search failed: {}", e.getMessage(), e);
            throw new RuntimeException("Error executing secure Vespa search", e);
        }
    }

    @McpTool(description = "Retrieve the full text content of a specific document chunk stored in Vespa by its URI, enforcing security checks to ensure the caller has read access.")
    public VespaDocumentDetails vespaGetDocumentContent(
            @McpToolParam(description = "The URI of the document to retrieve (e.g. file:///path/to/doc.txt)", required = true) String documentUri,
            @McpToolParam(description = "The user principal identity of the caller." + McpVectorServer.IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated list of groups/roles the caller belongs to." + McpVectorServer.IDENTITY_NOTE, required = false) String userRoles,
            @McpToolParam(description = "Dimensions of the target document type to query (384, 768, 1024)", required = false) Integer dimensions
    ) {
        Caller caller = callerResolver.resolve(userPrincipal, userRoles);
        McpVectorServer.DocumentDetailsResult details = backend.getDocumentContent(documentUri, caller.principal(), caller.groups(), dimensions);
        List<String> allowed = Collections.emptyList();
        if (details.metadata() != null && details.metadata().containsKey("security_allowed_read")) {
            Object obj = details.metadata().get("security_allowed_read");
            if (obj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> l = (List<String>) obj;
                allowed = l;
            }
        }
        return new VespaDocumentDetails(details.id(), details.uri(), details.content(), details.acl(), allowed);
    }

    @McpTool(description = "List documents currently indexed in Vespa that are accessible to the caller, showing their URIs and access rules.")
    public List<Map<String, Object>> vespaListAccessibleSources(
            @McpToolParam(description = "The user principal identity of the caller." + McpVectorServer.IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated list of groups/roles the caller belongs to." + McpVectorServer.IDENTITY_NOTE, required = false) String userRoles,
            @McpToolParam(description = "Dimensions of the target document type to query (384, 768, 1024)", required = false) Integer dimensions
    ) {
        Caller caller = callerResolver.resolve(userPrincipal, userRoles);
        return backend.listAccessibleSources(caller.principal(), caller.groups(), dimensions);
    }

    public static boolean isAccessible(RawHit hit, String userPrincipal, List<String> userGroups) {
        return McpAclEvaluator.isAccessible(hit.acl(), hit.allowedRead(), hit.deniedRead(), userPrincipal, userGroups);
    }

    public static String buildDenyExclusion(String userPrincipal, List<String> userGroups) {
        List<String> candidates = new ArrayList<>();
        if (userPrincipal != null && !userPrincipal.isBlank()) {
            candidates.add(userPrincipal.trim());
        }
        if (userGroups != null) {
            candidates.addAll(userGroups);
        }
        if (candidates.isEmpty()) {
            return "true";
        }
        String denyClause = candidates.stream()
                .map(candidate -> org.opencrawling.vespa.VespaFields.FIELD_SECURITY_DENIED_READ + " contains \"" + candidate.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(" or "));
        return "!(" + denyClause + ")";
    }

    public String resolveDocumentType(Integer dimensions) {
        return backend.resolveDocumentType(dimensions);
    }
}
