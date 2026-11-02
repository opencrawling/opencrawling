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

import org.opencrawling.opensearch3.OpenSearch3Constants;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
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
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "opensearch3")
public class OpenSearch3McpSearchBackend extends GatedMcpSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(OpenSearch3McpSearchBackend.class);

    private final OpenSearchClient openSearchClient;
    private final EmbeddingModel embeddingModel;

    @Value("${spring.opencrawling.output.opensearch3.index-name:enterprise_kb}")
    private String indexName;

    @Value("${spring.opencrawling.output.opensearch3.dimensions:1024}")
    private int defaultDimensions;

    @Autowired
    public OpenSearch3McpSearchBackend(
            OpenSearchClient openSearchClient,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel,
            @Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        this.openSearchClient = openSearchClient;
        this.embeddingModel = (ollamaEmbeddingModel != null) ? ollamaEmbeddingModel : openAiEmbeddingModel;
        log.info("Initialized OpenSearch3McpSearchBackend.");
    }

    @Override
    public String backendType() {
        return "opensearch3";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<McpVectorServer.DocumentSearchResult> secureVectorSearch(
            String query,
            String userPrincipal,
            List<String> userRoles,
            int maxResults,
            double minScore,
            Integer dimensions
    ) {
        log.info("OpenSearch 3.x backend secure search. Query: '{}', Principal: '{}'", query, userPrincipal);
        int limit = maxResults > 0 ? maxResults : 5;

        float[] vector = null;
        if (embeddingModel != null && query != null && !query.isBlank()) {
            try {
                vector = embeddingModel.embed(query);
            } catch (Exception e) {
                log.warn("Failed to generate embedding for OpenSearch 3.x search: {}", e.getMessage());
            }
        }

        if (vector == null) {
            int dims = dimensions != null ? dimensions : defaultDimensions;
            vector = new float[dims];
            vector[0] = 1.0f;
        }

        final float[] searchVector = vector;
        int topK = Math.max(limit * 3, 20);

        try {
            SearchResponse<Map> response = openSearchClient.search(s -> s
                    .index(indexName)
                    .size(topK)
                    .query(q -> q
                            .knn(k -> k
                                    .field(OpenSearch3Constants.FIELD_EMBEDDINGS)
                                    .vector(searchVector)
                                    .k(topK)
                            )
                    ),
                    Map.class
            );

            List<McpVectorServer.DocumentSearchResult> results = new ArrayList<>();
            if (response != null && response.hits() != null && response.hits().hits() != null) {
                List<Hit<Map>> candidates = response.hits().hits().stream()
                        .filter(hit -> (hit.score() != null ? hit.score() : 0.0) >= minScore)
                        .toList();
                for (Hit<Map> hit : readable(userPrincipal, userRoles, candidates,
                        hit -> ResourceRef.fromMetadata(hit.id(), sourceOf(hit)))) {
                    Map<String, Object> source = sourceOf(hit);
                    double score = hit.score() != null ? hit.score() : 0.0;
                    String id = String.valueOf(source.getOrDefault(OpenSearch3Constants.FIELD_ID, hit.id()));
                    String uri = String.valueOf(source.getOrDefault(OpenSearch3Constants.FIELD_URI, ""));
                    String text = String.valueOf(source.getOrDefault(OpenSearch3Constants.FIELD_TEXT, ""));
                    String acl = String.valueOf(source.getOrDefault(OpenSearch3Constants.FIELD_ACL, "public"));
                    results.add(new McpVectorServer.DocumentSearchResult(id, uri, text, source, acl, score));
                    if (results.size() >= limit) break;
                }
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to perform secure vector search in OpenSearch 3.x: {}", e.getMessage(), e);
            throw new RuntimeException("Error executing secure vector search in OpenSearch 3.x", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public McpVectorServer.DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("OpenSearch 3.x backend get document content. URI: '{}', Principal: '{}'", documentUri, userPrincipal);

        try {
            SearchResponse<Map> response = openSearchClient.search(s -> s
                    .index(indexName)
                    .size(20)
                    .query(q -> q
                            .term(t -> t
                                    .field(OpenSearch3Constants.FIELD_URI)
                                    .value(v -> v.stringValue(documentUri))
                            )
                    ),
                    Map.class
            );

            if (response != null && response.hits() != null && response.hits().hits() != null) {
                List<Hit<Map>> candidates = response.hits().hits().stream()
                        .filter(hit -> hit.source() != null)
                        .toList();
                for (Hit<Map> hit : readable(userPrincipal, userRoles, candidates,
                        hit -> ResourceRef.fromMetadata(hit.id(), sourceOf(hit)))) {
                    Map<String, Object> source = sourceOf(hit);
                    String id = String.valueOf(source.getOrDefault(OpenSearch3Constants.FIELD_ID, hit.id()));
                    String uri = String.valueOf(source.getOrDefault(OpenSearch3Constants.FIELD_URI, ""));
                    String text = String.valueOf(source.getOrDefault(OpenSearch3Constants.FIELD_TEXT, ""));
                    String acl = String.valueOf(source.getOrDefault(OpenSearch3Constants.FIELD_ACL, "public"));
                    return new McpVectorServer.DocumentDetailsResult(id, uri, text, source, acl);
                }
            }
            throw new NoSuchElementException("Document not found or access denied.");
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to retrieve document details in OpenSearch 3.x: {}", e.getMessage(), e);
            throw new RuntimeException("Error retrieving document details from OpenSearch 3.x", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("OpenSearch 3.x backend list accessible sources. Principal: '{}'", userPrincipal);

        try {
            SearchResponse<Map> response = openSearchClient.search(s -> s
                    .index(indexName)
                    .size(200)
                    .query(q -> q.matchAll(m -> m)),
                    Map.class
            );

            List<Map<String, Object>> summaries = new ArrayList<>();
            if (response != null && response.hits() != null && response.hits().hits() != null) {
                List<Hit<Map>> candidates = response.hits().hits().stream()
                        .filter(hit -> hit.source() != null)
                        .toList();
                for (Hit<Map> hit : readable(userPrincipal, userRoles, candidates,
                        hit -> ResourceRef.fromMetadata(hit.id(), sourceOf(hit)))) {
                    Map<String, Object> source = sourceOf(hit);
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("id", source.getOrDefault(OpenSearch3Constants.FIELD_ID, hit.id()));
                    summary.put("uri", source.getOrDefault(OpenSearch3Constants.FIELD_URI, ""));
                    summary.put("acl", source.getOrDefault(OpenSearch3Constants.FIELD_ACL, "public"));
                    summary.put("security", source.get("security"));
                    summary.put("lastModified", source.getOrDefault(OpenSearch3Constants.FIELD_LAST_MODIFIED, ""));
                    summaries.add(summary);
                }
            }
            return summaries.stream().distinct().toList();
        } catch (Exception e) {
            log.error("Failed to list accessible sources in OpenSearch 3.x: {}", e.getMessage(), e);
            throw new RuntimeException("Error listing accessible sources from OpenSearch 3.x", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sourceOf(Hit<Map> hit) {
        Map<String, Object> source = hit.source();
        return source != null ? source : Collections.emptyMap();
    }
}
