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
package org.opencrawling.runtime.mcp.backend;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.SourceConfig;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.elasticsearch.ElasticsearchAclQueries;
import org.opencrawling.elasticsearch.config.ElasticsearchOutputProperties;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

import static org.opencrawling.elasticsearch.ElasticsearchConstants.*;

/**
 * MCP search over the Elasticsearch output. Uses top-level kNN search; when stored ACL metadata decides access, the
 * caller's read keys pre-filter the kNN candidates inside Elasticsearch. Every hit is still re-checked by the access
 * gate, so an explicit deny always wins.
 */
@Component
@ConditionalOnProperty(name = "opencrawling.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "elasticsearch")
public class ElasticsearchMcpSearchBackend extends GatedMcpSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchMcpSearchBackend.class);

    static final int DOCUMENT_CHUNK_LIMIT = 20;
    static final int SOURCES_LIMIT = 200;

    /** Hits are returned to MCP clients as metadata; the stored vector is never useful there. */
    private static final SourceConfig NO_VECTORS = SourceConfig.of(c -> c.filter(f -> f.excludes(FIELD_EMBEDDINGS)));

    private final ElasticsearchClient client;
    private final ElasticsearchOutputProperties properties;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public ElasticsearchMcpSearchBackend(
            ElasticsearchClient client,
            ElasticsearchOutputProperties properties,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel,
            @Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        this.client = client;
        this.properties = properties;
        this.embeddingModel = (ollamaEmbeddingModel != null) ? ollamaEmbeddingModel : openAiEmbeddingModel;
        log.info("Initialized ElasticsearchMcpSearchBackend for index '{}'.", properties.indexName());
    }

    @Override
    public String backendType() {
        return OUTPUT_TYPE;
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
        log.info("Elasticsearch backend secure search. Query: '{}', Principal: '{}'", query, userPrincipal);
        int limit = maxResults > 0 ? maxResults : 5;
        int topK = candidateWindow(limit, Math.max(limit * 3, 20));
        List<Float> vector = queryVector(query, dimensions);
        Query aclFilter = storePrefilterAllowed() ? ElasticsearchAclQueries.readableBy(userPrincipal, userRoles) : null;

        SearchRequest request = SearchRequest.of(s -> s
                .index(properties.indexName())
                .size(topK)
                .source(NO_VECTORS)
                .knn(k -> {
                    k.field(FIELD_EMBEDDINGS).queryVector(vector).k(topK).numCandidates(Math.max(topK * 2, 100));
                    if (aclFilter != null) {
                        k.filter(aclFilter);
                    }
                    return k;
                }));

        try {
            List<Hit<Map>> candidates = hits(client.search(request, Map.class)).stream()
                    .filter(hit -> score(hit) >= minScore)
                    .toList();
            List<McpVectorServer.DocumentSearchResult> results = new ArrayList<>();
            for (Hit<Map> hit : readable(userPrincipal, userRoles, candidates, hit -> ResourceRef.fromMetadata(sourceOf(hit)))) {
                Map<String, Object> source = sourceOf(hit);
                results.add(new McpVectorServer.DocumentSearchResult(
                        idOf(hit), str(source, FIELD_URI, ""), str(source, FIELD_TEXT, ""), source,
                        str(source, FIELD_ACL, "public"), score(hit)));
                if (results.size() >= limit) {
                    break;
                }
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to perform secure vector search in Elasticsearch: {}", e.getMessage(), e);
            throw new RuntimeException("Error executing secure vector search in Elasticsearch", e);
        }
    }

    @Override
    public McpVectorServer.DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("Elasticsearch backend get document content. URI: '{}', Principal: '{}'", documentUri, userPrincipal);
        Query byDocument = Query.of(q -> q.bool(b -> b
                .should(s -> s.term(t -> t.field(FIELD_URI).value(FieldValue.of(documentUri))))
                .should(s -> s.term(t -> t.field(FIELD_DOCUMENT_ID).value(FieldValue.of(documentUri))))
                .minimumShouldMatch("1")));

        try {
            SearchResponse<Map> response = client.search(s -> s
                    .index(properties.indexName())
                    .size(DOCUMENT_CHUNK_LIMIT)
                    .source(NO_VECTORS)
                    .query(withAclFilter(byDocument, userPrincipal, userRoles)), Map.class);
            List<Hit<Map>> candidates = hits(response).stream().filter(hit -> hit.source() != null).toList();
            for (Hit<Map> hit : readable(userPrincipal, userRoles, candidates, hit -> ResourceRef.fromMetadata(sourceOf(hit)))) {
                Map<String, Object> source = sourceOf(hit);
                return new McpVectorServer.DocumentDetailsResult(
                        idOf(hit), str(source, FIELD_URI, ""), str(source, FIELD_TEXT, ""), source,
                        str(source, FIELD_ACL, "public"));
            }
            throw new NoSuchElementException("Document not found or access denied.");
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to retrieve document details in Elasticsearch: {}", e.getMessage(), e);
            throw new RuntimeException("Error retrieving document details from Elasticsearch", e);
        }
    }

    @Override
    public List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("Elasticsearch backend list accessible sources. Principal: '{}'", userPrincipal);
        Query all = Query.of(q -> q.matchAll(m -> m));

        try {
            SearchResponse<Map> response = client.search(s -> s
                    .index(properties.indexName())
                    .size(SOURCES_LIMIT)
                    .source(NO_VECTORS)
                    .query(withAclFilter(all, userPrincipal, userRoles)), Map.class);
            List<Hit<Map>> candidates = hits(response).stream().filter(hit -> hit.source() != null).toList();
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (Hit<Map> hit : readable(userPrincipal, userRoles, candidates, hit -> ResourceRef.fromMetadata(sourceOf(hit)))) {
                Map<String, Object> source = sourceOf(hit);
                Map<String, Object> summary = new HashMap<>();
                summary.put("id", source.getOrDefault(FIELD_ID, hit.id()));
                summary.put("uri", source.getOrDefault(FIELD_URI, ""));
                summary.put("acl", source.getOrDefault(FIELD_ACL, "public"));
                summary.put("security", source.get(METADATA_SECURITY));
                summary.put("lastModified", source.getOrDefault(FIELD_LAST_MODIFIED, ""));
                summaries.add(summary);
            }
            return summaries.stream().distinct().toList();
        } catch (Exception e) {
            log.error("Failed to list accessible sources in Elasticsearch: {}", e.getMessage(), e);
            throw new RuntimeException("Error listing accessible sources from Elasticsearch", e);
        }
    }

    /** {@code query} AND the caller's read-key filter, when stored metadata decides access. */
    private Query withAclFilter(Query query, String userPrincipal, List<String> userRoles) {
        if (!storePrefilterAllowed()) {
            return query;
        }
        Query acl = ElasticsearchAclQueries.readableBy(userPrincipal, userRoles);
        return Query.of(q -> q.bool(b -> b.must(query).filter(acl)));
    }

    private List<Float> queryVector(String query, Integer dimensions) {
        float[] vector = null;
        if (embeddingModel != null && query != null && !query.isBlank()) {
            try {
                vector = embeddingModel.embed(query);
            } catch (Exception e) {
                log.warn("Failed to generate embedding for Elasticsearch search: {}", e.getMessage());
            }
        }
        if (vector == null || vector.length == 0) {
            vector = new float[dimensions != null && dimensions > 0 ? dimensions : properties.dimensions()];
            vector[0] = 1.0f;
        }
        List<Float> values = new ArrayList<>(vector.length);
        for (float v : vector) {
            values.add(v);
        }
        return values;
    }

    private static List<Hit<Map>> hits(SearchResponse<Map> response) {
        if (response == null || response.hits() == null || response.hits().hits() == null) {
            return List.of();
        }
        return response.hits().hits();
    }

    private static double score(Hit<Map> hit) {
        return hit.score() != null ? hit.score() : 0.0;
    }

    private static String idOf(Hit<Map> hit) {
        return str(sourceOf(hit), FIELD_ID, hit.id());
    }

    private static String str(Map<String, Object> source, String field, String fallback) {
        Object value = source.get(field);
        return value != null ? String.valueOf(value) : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sourceOf(Hit<Map> hit) {
        Map<String, Object> source = hit.source();
        return source != null ? source : Collections.emptyMap();
    }
}
