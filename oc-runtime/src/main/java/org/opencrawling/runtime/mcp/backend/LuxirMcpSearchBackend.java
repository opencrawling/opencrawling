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

import org.opencrawling.luxir.LuxirConstants;
import org.opencrawling.luxir.client.LuxirClient;
import org.opencrawling.luxir.config.LuxirOutputProperties;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(name = "opencrawling.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "luxir")
public class LuxirMcpSearchBackend extends GatedMcpSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(LuxirMcpSearchBackend.class);

    private final LuxirClient luxirClient;
    private final LuxirOutputProperties properties;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public LuxirMcpSearchBackend(
            LuxirClient luxirClient,
            LuxirOutputProperties properties,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel,
            @Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        this.luxirClient = luxirClient;
        this.properties = properties;
        this.embeddingModel = (ollamaEmbeddingModel != null) ? ollamaEmbeddingModel : openAiEmbeddingModel;
        log.info("Initialized LuxirMcpSearchBackend for collection '{}'.", properties.collection());
    }

    @Override
    public String backendType() {
        return "luxir";
    }

    @Override
    public List<McpVectorServer.DocumentSearchResult> secureVectorSearch(
            String query,
            String userPrincipal,
            List<String> userRoles,
            int maxResults,
            double minScore,
            Integer dimensions
    ) {
        log.info("Luxir backend secure search. Query: '{}', Principal: '{}'", query, userPrincipal);
        int limit = maxResults > 0 ? maxResults : 5;

        float[] vector = null;
        if (embeddingModel != null && query != null && !query.isBlank()) {
            try {
                vector = embeddingModel.embed(query);
            } catch (Exception e) {
                log.warn("Failed to generate embedding for Luxir search: {}", e.getMessage());
            }
        }

        if (vector == null) {
            int dims = dimensions != null ? dimensions : properties.dimensions();
            vector = new float[dims];
            vector[0] = 1.0f;
        }

        List<Float> floatList = new ArrayList<>(vector.length);
        for (float f : vector) {
            floatList.add(f);
        }

        int topK = Math.max(limit * 3, 20);
        Map<String, Object> knnQuery = Map.of(
                "field", properties.vectorField(),
                "query", floatList,
                "k", topK
        );
        Map<String, Object> queryPayload = Map.of("query", Map.of("knn", knnQuery));

        try {
            LuxirClient.LuxirSearchResult result = luxirClient.search(properties.collection(), queryPayload);

            List<McpVectorServer.DocumentSearchResult> results = new ArrayList<>();
            if (result != null && result.docs() != null) {
                List<Map<String, Object>> candidates = result.docs().stream()
                        .filter(doc -> doc != null && scoreOf(doc) >= minScore)
                        .toList();
                for (Map<String, Object> doc : readable(userPrincipal, userRoles, candidates,
                        LuxirMcpSearchBackend::toResourceRef)) {
                    double score = scoreOf(doc);
                    String id = String.valueOf(doc.getOrDefault(LuxirConstants.FIELD_ID, doc.getOrDefault("id", "")));
                    String uri = String.valueOf(doc.getOrDefault(LuxirConstants.FIELD_URI, doc.getOrDefault("uri", "")));
                    String text = String.valueOf(doc.getOrDefault(LuxirConstants.FIELD_TEXT, doc.getOrDefault("text", "")));
                    String acl = String.valueOf(doc.getOrDefault(LuxirConstants.FIELD_ACL, "public"));
                    results.add(new McpVectorServer.DocumentSearchResult(id, uri, text, doc, acl, score));
                    if (results.size() >= limit) break;
                }
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to perform secure vector search in Luxir: {}", e.getMessage(), e);
            throw new RuntimeException("Error executing secure vector search in Luxir", e);
        }
    }

    @Override
    public McpVectorServer.DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("Luxir backend get document content. URI: '{}', Principal: '{}'", documentUri, userPrincipal);

        Map<String, Object> queryPayload = Map.of("query", "uri_s:\"" + escape(documentUri) + "\"");

        try {
            LuxirClient.LuxirSearchResult result = luxirClient.search(properties.collection(), queryPayload);

            if (result != null && result.docs() != null) {
                List<Map<String, Object>> candidates = result.docs().stream()
                        .filter(Objects::nonNull)
                        .toList();
                for (Map<String, Object> doc : readable(userPrincipal, userRoles, candidates,
                        LuxirMcpSearchBackend::toResourceRef)) {
                    String id = String.valueOf(doc.getOrDefault(LuxirConstants.FIELD_ID, doc.getOrDefault("id", "")));
                    String uri = String.valueOf(doc.getOrDefault(LuxirConstants.FIELD_URI, doc.getOrDefault("uri", "")));
                    String text = String.valueOf(doc.getOrDefault(LuxirConstants.FIELD_TEXT, doc.getOrDefault("text", "")));
                    String acl = String.valueOf(doc.getOrDefault(LuxirConstants.FIELD_ACL, "public"));
                    return new McpVectorServer.DocumentDetailsResult(id, uri, text, doc, acl);
                }
            }
            throw new NoSuchElementException("Document not found or access denied.");
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to retrieve document details in Luxir: {}", e.getMessage(), e);
            throw new RuntimeException("Error retrieving document details from Luxir", e);
        }
    }

    @Override
    public List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("Luxir backend list accessible sources. Principal: '{}'", userPrincipal);

        Map<String, Object> queryPayload = Map.of("query", "*:*", "limit", 200);

        try {
            LuxirClient.LuxirSearchResult result = luxirClient.search(properties.collection(), queryPayload);

            List<Map<String, Object>> summaries = new ArrayList<>();
            if (result != null && result.docs() != null) {
                List<Map<String, Object>> candidates = result.docs().stream()
                        .filter(Objects::nonNull)
                        .toList();
                for (Map<String, Object> doc : readable(userPrincipal, userRoles, candidates,
                        LuxirMcpSearchBackend::toResourceRef)) {
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("id", doc.getOrDefault(LuxirConstants.FIELD_ID, doc.get("id")));
                    summary.put("uri", doc.getOrDefault(LuxirConstants.FIELD_URI, doc.get("uri")));
                    summary.put("acl", doc.getOrDefault(LuxirConstants.FIELD_ACL, "public"));
                    summary.put("security", doc.get("security"));
                    summary.put("lastModified", doc.getOrDefault(LuxirConstants.FIELD_LAST_MODIFIED, doc.get("lastModified")));
                    summaries.add(summary);
                }
            }
            return summaries.stream().distinct().toList();
        } catch (Exception e) {
            log.error("Failed to list accessible sources in Luxir: {}", e.getMessage(), e);
            throw new RuntimeException("Error listing accessible sources from Luxir", e);
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static double scoreOf(Map<String, Object> doc) {
        return doc.get("_score") instanceof Number n ? n.doubleValue() : 1.0;
    }

    /** Luxir keeps the source document id in {@link LuxirConstants#FIELD_DOC_ID}. */
    static ResourceRef toResourceRef(Map<String, Object> doc) {
        return new ResourceRef(ResourceRef.idValue(doc.get(LuxirConstants.FIELD_DOC_ID)), doc);
    }
}
