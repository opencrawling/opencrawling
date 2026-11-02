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
package org.opencrawling.runtime.mcp.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.opencrawling.vespa.VespaFields;
import org.opencrawling.vespa.config.VespaOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "opencrawling.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "vespa")
public class VespaMcpSearchBackend extends GatedMcpSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(VespaMcpSearchBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VespaOutputProperties properties;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public VespaMcpSearchBackend(
            VespaOutputProperties properties,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel,
            @Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        this.properties = properties;
        this.embeddingModel = (ollamaEmbeddingModel != null) ? ollamaEmbeddingModel : openAiEmbeddingModel;
        log.info("Initialized VespaMcpSearchBackend (endpoint: {}).", properties.endpoint());
    }

    @Override
    public String backendType() {
        return "vespa";
    }

    public record RawHit(String chunkId, String uri, String content, String acl,
                          List<String> allowedRead, List<String> deniedRead,
                          Map<String, Object> metadata, double relevance) {}

    public record RawSearchResult(List<RawHit> hits, int totalCount, boolean degraded, String message) {}

    @Override
    public List<McpVectorServer.DocumentSearchResult> secureVectorSearch(
            String query,
            String userPrincipal,
            List<String> userRoles,
            int maxResults,
            double minScore,
            Integer dimensions
    ) {
        int limit = maxResults > 0 ? maxResults : 5;
        String documentType = resolveDocumentType(dimensions);
        String rankProfile = "hybrid";

        log.info("Vespa backend secure search. Query: '{}', Principal: '{}', DocumentType: '{}'",
                query, userPrincipal, documentType);

        try {
            RawSearchResult raw = executeSearch(documentType, query, rankProfile, limit, userPrincipal, userRoles);
            List<RawHit> candidates = raw.hits().stream()
                    .filter(hit -> hit.relevance() >= minScore)
                    .toList();
            return readableHits(userPrincipal, userRoles, candidates).stream()
                    .limit(limit)
                    .map(hit -> new McpVectorServer.DocumentSearchResult(
                            hit.chunkId(),
                            hit.uri(),
                            hit.content(),
                            hit.metadata(),
                            hit.acl(),
                            hit.relevance()
                    ))
                    .toList();
        } catch (Exception e) {
            log.error("Vespa MCP secure search failed: {}", e.getMessage(), e);
            throw new RuntimeException("Error executing secure Vespa search", e);
        }
    }

    @Override
    public McpVectorServer.DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        String documentType = resolveDocumentType(dimensions);
        log.info("Vespa backend get document content. URI: '{}', Principal: '{}', DocumentType: '{}'",
                documentUri, userPrincipal, documentType);

        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("yql", "select * from " + documentType + " where (" + buildDenyExclusion(userPrincipal, userRoles) + ") and "
                    + VespaFields.FIELD_URI + " contains \"" + escapeYql(documentUri) + "\"");
            body.put("hits", 20);
            List<RawHit> hits = parseHits(postSearch(body)).hits();

            return readableHits(userPrincipal, userRoles, hits).stream()
                    .findFirst()
                    .map(hit -> new McpVectorServer.DocumentDetailsResult(
                            hit.chunkId(),
                            hit.uri(),
                            hit.content(),
                            hit.metadata(),
                            hit.acl()
                    ))
                    .orElseThrow(() -> new NoSuchElementException("Document not found or access denied."));
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Vespa backend get document content failed: {}", e.getMessage(), e);
            throw new RuntimeException("Error retrieving document details from Vespa", e);
        }
    }

    @Override
    public List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        String documentType = resolveDocumentType(dimensions);
        log.info("Vespa backend list accessible sources. Principal: '{}', DocumentType: '{}'",
                userPrincipal, documentType);

        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("yql", "select * from " + documentType + " where " + buildDenyExclusion(userPrincipal, userRoles));
            body.put("hits", 200);
            List<RawHit> hits = parseHits(postSearch(body)).hits();

            return readableHits(userPrincipal, userRoles, hits).stream()
                    .map(hit -> {
                        Map<String, Object> summary = new HashMap<>();
                        summary.put("id", hit.chunkId());
                        summary.put("uri", hit.uri());
                        summary.put("acl", hit.acl());
                        summary.put("allowedRead", hit.allowedRead());
                        return summary;
                    })
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.error("Vespa backend list accessible sources failed: {}", e.getMessage(), e);
            throw new RuntimeException("Error listing accessible sources from Vespa", e);
        }
    }

    public RawSearchResult executeSearch(String documentType, String query, String rankProfile, int limit,
                                          String userPrincipal, List<String> roles) throws Exception {
        int resultLimit = Math.max(limit * 3, 30);
        String denyExclusion = buildDenyExclusion(userPrincipal, roles);

        if ("default".equals(rankProfile)) {
            return bm25Search(documentType, query, denyExclusion, resultLimit, false, null);
        }

        if (embeddingModel == null) {
            return bm25Search(documentType, query, denyExclusion, resultLimit, true,
                    "No embedding model is configured; showing BM25-only results instead of " + rankProfile + ".");
        }

        float[] vector;
        try {
            vector = embeddingModel.embed(query);
        } catch (Exception e) {
            return bm25Search(documentType, query, denyExclusion, resultLimit, true,
                    "Failed to compute a query embedding (" + e.getMessage() + "); showing BM25-only results instead.");
        }
        return vectorSearch(documentType, query, rankProfile, denyExclusion, resultLimit, vector);
    }

    private RawSearchResult bm25Search(String documentType, String query, String denyExclusion, int resultLimit,
                                        boolean degraded, String degradedMessage) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("yql", "select * from " + documentType + " where (" + denyExclusion + ") and userQuery()");
        body.put("query", query);
        body.put("ranking", "default");
        body.put("hits", resultLimit);
        RawSearchResult parsed = parseHits(postSearch(body));
        return new RawSearchResult(parsed.hits(), parsed.totalCount(), degraded, degradedMessage);
    }

    private RawSearchResult vectorSearch(String documentType, String query, String rankProfile, String denyExclusion,
                                         int resultLimit, float[] vector) throws Exception {
        String queryInputName = resolveQueryInputName(documentType);
        ObjectNode body = MAPPER.createObjectNode();
        String yql;
        if ("hybrid".equals(rankProfile)) {
            yql = "select * from " + documentType + " where (" + denyExclusion + ") and (({targetHits:" + resultLimit
                    + "}nearestNeighbor(" + VespaFields.FIELD_EMBEDDING + ", " + queryInputName + ")) or userQuery())";
            body.put("query", query);
        } else {
            yql = "select * from " + documentType + " where (" + denyExclusion + ") and ({targetHits:" + resultLimit
                    + "}nearestNeighbor(" + VespaFields.FIELD_EMBEDDING + ", " + queryInputName + "))";
        }
        body.put("yql", yql);
        ArrayNode vectorValues = body.putArray("input.query(" + queryInputName + ")");
        for (float value : vector) {
            vectorValues.add(value);
        }
        body.put("ranking", rankProfile);
        body.put("hits", resultLimit);
        return parseHits(postSearch(body));
    }

    /**
     * Keeps the hits the caller may read, in their original order.
     */
    public List<RawHit> readableHits(String userPrincipal, List<String> userRoles, List<RawHit> hits) {
        return readable(userPrincipal, userRoles, hits, VespaMcpSearchBackend::toResourceRef);
    }

    /**
     * Presents a hit's ACL fields in the metadata shape the authorization providers read: the legacy
     * {@code acl}, plus {@code security_allowed_read}/{@code security_denied_read} when the hit carries them,
     * keyed by the source document id.
     */
    public static ResourceRef toResourceRef(RawHit hit) {
        Map<String, Object> acl = new HashMap<>();
        acl.put("acl", hit.acl());
        if (hit.allowedRead() != null && !hit.allowedRead().isEmpty()) {
            acl.put(VespaFields.FIELD_SECURITY_ALLOWED_READ, hit.allowedRead());
        }
        if (hit.deniedRead() != null && !hit.deniedRead().isEmpty()) {
            acl.put(VespaFields.FIELD_SECURITY_DENIED_READ, hit.deniedRead());
        }
        return new ResourceRef(documentIdOf(hit), acl);
    }

    /**
     * Vespa schemas are strict, so the source document id travels inside the {@code metadata_json} field
     * (the ingestion pipeline puts {@code documentId} into every chunk's metadata).
     */
    @SuppressWarnings("unchecked")
    static String documentIdOf(RawHit hit) {
        if (hit.metadata() == null) {
            return null;
        }
        String direct = ResourceRef.idValue(hit.metadata().get("documentId"));
        if (direct != null) {
            return direct;
        }
        if (hit.metadata().get(VespaFields.FIELD_METADATA_JSON) instanceof String json && !json.isBlank()) {
            try {
                return ResourceRef.fromMetadata(MAPPER.readValue(json, Map.class)).documentId();
            } catch (Exception e) {
                log.debug("Unreadable metadata_json on Vespa hit {}: {}", hit.chunkId(), e.getMessage());
            }
        }
        return null;
    }

    public RawSearchResult parseHits(JsonNode response) {
        JsonNode root = response.path("root");
        int totalCount = root.path("fields").path("totalCount").asInt(0);
        List<RawHit> hits = new ArrayList<>();
        for (JsonNode child : root.path("children")) {
            JsonNode fields = child.path("fields");
            Map<String, Object> meta = new HashMap<>();
            fields.fieldNames().forEachRemaining(name -> meta.put(name, fields.get(name).asText()));
            hits.add(new RawHit(
                    fields.path(VespaFields.FIELD_CHUNK_ID).asText(""),
                    fields.path(VespaFields.FIELD_URI).asText(""),
                    fields.path(VespaFields.FIELD_TEXT).asText(""),
                    fields.path(VespaFields.FIELD_ACL).asText(""),
                    toStringList(fields.path(VespaFields.FIELD_SECURITY_ALLOWED_READ)),
                    toStringList(fields.path(VespaFields.FIELD_SECURITY_DENIED_READ)),
                    meta,
                    child.path("relevance").asDouble(0)
            ));
        }
        return new RawSearchResult(hits, totalCount, false, null);
    }

    public JsonNode postSearch(ObjectNode body) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.timeoutSeconds())).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.endpoint() + "/search/"))
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = MAPPER.readTree(response.body());
        JsonNode errors = json.path("root").path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException("Vespa query failed: " + errors.get(0).path("message").asText(errors.toString()));
        }
        return json;
    }

    public String buildDenyExclusion(String userPrincipal, List<String> userGroups) {
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
                .map(candidate -> VespaFields.FIELD_SECURITY_DENIED_READ + " contains \"" + escapeYql(candidate) + "\"")
                .collect(Collectors.joining(" or "));
        return "!(" + denyClause + ")";
    }

    public String escapeYql(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static List<String> toStringList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                values.add(item.asText());
            }
        }
        return values;
    }

    public String resolveDocumentType(Integer dimensions) {
        if (dimensions == null) return properties.documentType();
        return switch (dimensions) {
            case 384 -> VespaFields.DOCUMENT_TYPE_384;
            case 768 -> VespaFields.DOCUMENT_TYPE_768;
            case 1024 -> VespaFields.DOCUMENT_TYPE_1024;
            default -> properties.documentType();
        };
    }

    public static String resolveQueryInputName(String documentType) {
        if (VespaFields.DOCUMENT_TYPE_384.equals(documentType)) return VespaFields.QUERY_INPUT_384;
        if (VespaFields.DOCUMENT_TYPE_768.equals(documentType)) return VespaFields.QUERY_INPUT_768;
        if (VespaFields.DOCUMENT_TYPE_1024.equals(documentType)) return VespaFields.QUERY_INPUT_1024;
        return VespaFields.QUERY_INPUT_DEFAULT;
    }
}
