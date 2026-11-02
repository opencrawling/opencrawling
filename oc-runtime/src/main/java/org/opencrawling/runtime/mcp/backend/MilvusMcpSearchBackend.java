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

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.opencrawling.milvus.MilvusConstants;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(name = "opencrawling.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "milvus")
public class MilvusMcpSearchBackend extends GatedMcpSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(MilvusMcpSearchBackend.class);

    private final MilvusClientV2 milvusClient;
    private final EmbeddingModel embeddingModel;

    @Value("${spring.opencrawling.output.milvus.collection-name:enterprise_kb}")
    private String collectionName;

    @Value("${spring.opencrawling.output.milvus.vector-field-name:embeddings}")
    private String vectorFieldName;

    @Value("${spring.opencrawling.output.milvus.dimensions:1024}")
    private int defaultDimensions;

    private static final List<String> OUTPUT_FIELDS = List.of(
            MilvusConstants.FIELD_ID,
            MilvusConstants.FIELD_TEXT,
            MilvusConstants.FIELD_URI,
            MilvusConstants.FIELD_ACL,
            MilvusConstants.FIELD_LAST_MODIFIED,
            MilvusConstants.FIELD_SECURITY_INHERITANCE,
            MilvusConstants.FIELD_SECURITY_ALLOWED_READ,
            MilvusConstants.FIELD_SECURITY_DENIED_READ
    );

    @Autowired
    public MilvusMcpSearchBackend(
            MilvusClientV2 milvusClient,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel,
            @Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        this.milvusClient = milvusClient;
        this.embeddingModel = (ollamaEmbeddingModel != null) ? ollamaEmbeddingModel : openAiEmbeddingModel;
        log.info("Initialized MilvusMcpSearchBackend.");
    }

    @Override
    public String backendType() {
        return "milvus";
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
        log.info("Milvus backend secure search. Query: '{}', Principal: '{}'", query, userPrincipal);
        int limit = maxResults > 0 ? maxResults : 5;

        float[] vector = null;
        if (embeddingModel != null && query != null && !query.isBlank()) {
            try {
                vector = embeddingModel.embed(query);
            } catch (Exception e) {
                log.warn("Failed to generate embedding for Milvus search: {}", e.getMessage());
            }
        }

        if (vector == null) {
            int dims = dimensions != null ? dimensions : defaultDimensions;
            vector = new float[dims];
            vector[0] = 1.0f;
        }

        try {
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(collectionName)
                    .data(Collections.singletonList(new FloatVec(vector)))
                    .topK(Math.max(limit * 3, 20))
                    .outputFields(OUTPUT_FIELDS)
                    .build();

            SearchResp searchResp = milvusClient.search(searchReq);

            List<McpVectorServer.DocumentSearchResult> results = new ArrayList<>();
            if (searchResp != null && searchResp.getSearchResults() != null) {
                for (List<SearchResp.SearchResult> subList : searchResp.getSearchResults()) {
                    if (subList == null) continue;
                    List<SearchResp.SearchResult> candidates = subList.stream()
                            .filter(hit -> hit.getScore() == null || hit.getScore() >= minScore)
                            .toList();
                    for (SearchResp.SearchResult hit : readable(userPrincipal, userRoles, candidates,
                            hit -> ResourceRef.fromMetadata(hit.getId(), entityOf(hit)))) {
                        Map<String, Object> entity = entityOf(hit);
                        String id = String.valueOf(entity.getOrDefault(MilvusConstants.FIELD_ID, hit.getId() != null ? hit.getId() : ""));
                        String uri = String.valueOf(entity.getOrDefault(MilvusConstants.FIELD_URI, ""));
                        String text = String.valueOf(entity.getOrDefault(MilvusConstants.FIELD_TEXT, ""));
                        String acl = String.valueOf(entity.getOrDefault(MilvusConstants.FIELD_ACL, "public"));
                        double score = hit.getScore() != null ? hit.getScore().doubleValue() : 0.0;
                        results.add(new McpVectorServer.DocumentSearchResult(id, uri, text, entity, acl, score));
                        if (results.size() >= limit) {
                            break;
                        }
                    }
                    if (results.size() >= limit) break;
                }
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to perform secure vector search in Milvus: {}", e.getMessage(), e);
            throw new RuntimeException("Error executing secure vector search in Milvus", e);
        }
    }

    @Override
    public McpVectorServer.DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("Milvus backend get document content. URI: '{}', Principal: '{}'", documentUri, userPrincipal);

        try {
            QueryReq queryReq = QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("uri == \"" + escapeFilter(documentUri) + "\"")
                    .outputFields(OUTPUT_FIELDS)
                    .limit(20)
                    .build();

            QueryResp queryResp = milvusClient.query(queryReq);

            if (queryResp != null && queryResp.getQueryResults() != null) {
                List<QueryResp.QueryResult> candidates = queryResp.getQueryResults().stream()
                        .filter(qr -> qr.getEntity() != null)
                        .toList();
                for (QueryResp.QueryResult qr : readable(userPrincipal, userRoles, candidates,
                        qr -> ResourceRef.fromMetadata(qr.getEntity().get(MilvusConstants.FIELD_ID), qr.getEntity()))) {
                    Map<String, Object> entity = qr.getEntity();
                    String id = String.valueOf(entity.getOrDefault(MilvusConstants.FIELD_ID, ""));
                    String uri = String.valueOf(entity.getOrDefault(MilvusConstants.FIELD_URI, ""));
                    String text = String.valueOf(entity.getOrDefault(MilvusConstants.FIELD_TEXT, ""));
                    String acl = String.valueOf(entity.getOrDefault(MilvusConstants.FIELD_ACL, "public"));
                    return new McpVectorServer.DocumentDetailsResult(id, uri, text, entity, acl);
                }
            }
            throw new NoSuchElementException("Document not found or access denied.");
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to retrieve document details in Milvus: {}", e.getMessage(), e);
            throw new RuntimeException("Error retrieving document details from Milvus", e);
        }
    }

    @Override
    public List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("Milvus backend list accessible sources. Principal: '{}'", userPrincipal);

        try {
            QueryReq queryReq = QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("id != \"\"")
                    .outputFields(OUTPUT_FIELDS)
                    .limit(200)
                    .build();

            QueryResp queryResp = milvusClient.query(queryReq);

            List<Map<String, Object>> summaries = new ArrayList<>();
            if (queryResp != null && queryResp.getQueryResults() != null) {
                List<QueryResp.QueryResult> candidates = queryResp.getQueryResults().stream()
                        .filter(qr -> qr.getEntity() != null)
                        .toList();
                for (QueryResp.QueryResult qr : readable(userPrincipal, userRoles, candidates,
                        qr -> ResourceRef.fromMetadata(qr.getEntity().get(MilvusConstants.FIELD_ID), qr.getEntity()))) {
                    Map<String, Object> entity = qr.getEntity();
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("id", entity.getOrDefault(MilvusConstants.FIELD_ID, ""));
                    summary.put("uri", entity.getOrDefault(MilvusConstants.FIELD_URI, ""));
                    summary.put("acl", entity.getOrDefault(MilvusConstants.FIELD_ACL, "public"));
                    summary.put("security", entity.get("security"));
                    summary.put("lastModified", entity.getOrDefault(MilvusConstants.FIELD_LAST_MODIFIED, ""));
                    summaries.add(summary);
                }
            }
            return summaries.stream().distinct().toList();
        } catch (Exception e) {
            log.error("Failed to list accessible sources in Milvus: {}", e.getMessage(), e);
            throw new RuntimeException("Error listing accessible sources from Milvus", e);
        }
    }

    private static String escapeFilter(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String, Object> entityOf(SearchResp.SearchResult hit) {
        Map<String, Object> entity = hit.getEntity();
        return entity != null ? entity : Collections.emptyMap();
    }
}
