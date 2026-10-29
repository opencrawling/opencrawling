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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.runtime.mcp.McpAclEvaluator;
import org.opencrawling.runtime.mcp.McpVectorSearchBackend;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.opencrawling.vector.config.PrecomputedEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(name = "opencrawling.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "pgvector", matchIfMissing = true)
public class PgVectorMcpSearchBackend implements McpVectorSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(PgVectorMcpSearchBackend.class);

    private final VectorStore vectorStore;
    private final VectorStore vectorStore384;
    private final VectorStore vectorStore768;
    private final VectorStore vectorStore1024;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Boolean> scopeColumnsCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public PgVectorMcpSearchBackend(
            @Qualifier("vectorStore") VectorStore vectorStore,
            @Qualifier("vectorStore384") VectorStore vectorStore384,
            @Qualifier("vectorStore768") VectorStore vectorStore768,
            @Qualifier("vectorStore1024") VectorStore vectorStore1024,
            @Autowired(required = false) @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel,
            @Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        this.vectorStore = vectorStore;
        this.vectorStore384 = vectorStore384;
        this.vectorStore768 = vectorStore768;
        this.vectorStore1024 = vectorStore1024;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = (ollamaEmbeddingModel != null) ? ollamaEmbeddingModel : openAiEmbeddingModel;
        log.info("Initialized PgVectorMcpSearchBackend (JdbcTemplate: {}).", jdbcTemplate != null ? "enabled" : "disabled");
    }

    public PgVectorMcpSearchBackend(
            VectorStore vectorStore,
            VectorStore vectorStore384,
            VectorStore vectorStore768,
            VectorStore vectorStore1024,
            EmbeddingModel ollamaEmbeddingModel,
            EmbeddingModel openAiEmbeddingModel) {
        this(vectorStore, vectorStore384, vectorStore768, vectorStore1024, null, ollamaEmbeddingModel, openAiEmbeddingModel);
    }

    @Override
    public String backendType() {
        return "pgvector";
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

    private String getTableName(Integer dimensions) {
        if (dimensions == null) return "vector_store";
        return switch (dimensions) {
            case 384 -> "vector_store_384";
            case 768 -> "vector_store_768";
            case 1024 -> "vector_store_1024";
            default -> "vector_store";
        };
    }

    private boolean hasScopeColumns(String tableName) {
        if (jdbcTemplate == null || tableName == null) {
            return false;
        }
        return scopeColumnsCache.computeIfAbsent(tableName, tbl -> {
            try {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = 'scope_id_'",
                        Integer.class, tbl
                );
                return count != null && count > 0;
            } catch (Exception e) {
                log.debug("Unable to check information_schema for scope_id_ on table {}: {}", tbl, e.getMessage());
                return false;
            }
        });
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
        log.info("pgvector secure search. Query: '{}', Principal: '{}', Roles: {}, Dimensions: {}",
                query, userPrincipal, userRoles, dimensions);

        int limit = maxResults > 0 ? maxResults : 5;
        double threshold = minScore >= 0.0 ? minScore : 0.0;

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var securityExpression = buildSecurityFilterExpression(b, userPrincipal, userRoles);

        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .filterExpression(securityExpression.build())
                .topK(limit)
                .similarityThreshold(threshold)
                .build();

        boolean precomputedSet = false;
        try {
            if (query != null && !query.isBlank() && embeddingModel != null && !(embeddingModel instanceof PrecomputedEmbeddingModel)) {
                try {
                    float[] vector = embeddingModel.embed(query);
                    if (vector != null && vector.length > 0) {
                        int targetDims = (dimensions != null) ? dimensions : 1024;
                        if (vector.length != targetDims) {
                            float[] adapted = new float[targetDims];
                            System.arraycopy(vector, 0, adapted, 0, Math.min(vector.length, targetDims));
                            if (adapted[0] == 0.0f) adapted[0] = 1.0f;
                            vector = adapted;
                        }
                        PrecomputedEmbeddingModel.setPrecomputedVector(query, vector);
                        precomputedSet = true;
                    }
                } catch (Exception e) {
                    log.warn("Failed to generate query embedding via active model: {}", e.getMessage());
                }
            }

            List<Document> docs = getVectorStore(dimensions).similaritySearch(searchRequest);
            return docs.stream()
                    .filter(doc -> McpAclEvaluator.isAccessible(doc.getMetadata(), userPrincipal, userRoles))
                    .limit(limit)
                    .map(doc -> {
                        String docId = (String) doc.getMetadata().getOrDefault("documentId", doc.getMetadata().getOrDefault("parent_document_id", ""));
                        String uri = (docId != null && !docId.isBlank()) ? docId : (String) doc.getMetadata().getOrDefault("uri", doc.getId());
                        return new McpVectorServer.DocumentSearchResult(
                                doc.getId(),
                                uri,
                                doc.getText(),
                                doc.getMetadata(),
                                formatAcl(doc.getMetadata().getOrDefault("acl", "public")),
                                doc.getScore() != null ? doc.getScore() : 0.0
                        );
                    })
                    .toList();
        } catch (Exception e) {
            log.error("Failed to perform secure vector search in pgvector: {}", e.getMessage(), e);
            throw new RuntimeException("Error executing secure vector search in pgvector", e);
        } finally {
            if (precomputedSet) {
                PrecomputedEmbeddingModel.clear();
            }
        }
    }

    @Override
    public McpVectorServer.DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("pgvector get document content. URI: '{}', Principal: '{}', Dimensions: {}", documentUri, userPrincipal, dimensions);

        if (jdbcTemplate != null) {
            try {
                String tableName = getTableName(dimensions);
                boolean hasScope = hasScopeColumns(tableName);
                String sql;
                Object[] args;
                if (hasScope) {
                    sql = "SELECT id, content, metadata, scope_id_, scope_type_ FROM " + tableName +
                            " WHERE metadata->>'uri' = ? OR metadata->>'documentId' = ? OR id::text = ?" +
                            " OR scope_id_ = ?" +
                            " OR metadata->>'uri' = ('flowable://historic-process-instances/' || ?)" +
                            " OR metadata->>'uri' = ('flowable://runtime-process-instances/' || ?) LIMIT 10";
                    args = new Object[]{documentUri, documentUri, documentUri, documentUri, documentUri, documentUri};
                } else {
                    sql = "SELECT id, content, metadata FROM " + tableName +
                            " WHERE metadata->>'uri' = ? OR metadata->>'documentId' = ? OR id::text = ?" +
                            " OR metadata->>'uri' = ('flowable://historic-process-instances/' || ?)" +
                            " OR metadata->>'uri' = ('flowable://runtime-process-instances/' || ?) LIMIT 10";
                    args = new Object[]{documentUri, documentUri, documentUri, documentUri, documentUri};
                }

                List<McpVectorServer.DocumentDetailsResult> found = jdbcTemplate.query(
                        sql,
                        (rs, rowNum) -> {
                            String id = rs.getString("id");
                            String content = rs.getString("content");
                            Map<String, Object> meta = parseMetadata(rs.getObject("metadata"));
                            if (hasScope) {
                                String scopeId = rs.getString("scope_id_");
                                String scopeType = rs.getString("scope_type_");
                                if (scopeId != null && !scopeId.isBlank()) {
                                    meta.putIfAbsent("scope_id", scopeId);
                                }
                                if (scopeType != null && !scopeType.isBlank()) {
                                    meta.putIfAbsent("scope_type", scopeType);
                                }
                            }
                            String docId = (String) meta.getOrDefault("documentId", meta.getOrDefault("parent_document_id", ""));
                            String uri = (docId != null && !docId.isBlank()) ? docId : (String) meta.getOrDefault("uri", id);
                            String acl = formatAcl(meta.getOrDefault("acl", "public"));
                            return new McpVectorServer.DocumentDetailsResult(id, uri, content, meta, acl);
                        },
                        args
                );

                return found.stream()
                        .filter(doc -> McpAclEvaluator.isAccessible(doc.metadata(), userPrincipal, userRoles))
                        .findFirst()
                        .orElseThrow(() -> new NoSuchElementException("Document not found or access denied."));
            } catch (NoSuchElementException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to retrieve document details via JDBC in pgvector: {}", e.getMessage(), e);
                throw new RuntimeException("Error retrieving document details from pgvector", e);
            }
        }

        // Fallback for mock environments without active JDBC connection
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var securityExpression = buildSecurityFilterExpression(b, userPrincipal, userRoles);

        var matchDocExpression = b.or(
                b.eq("uri", documentUri),
                b.or(
                        b.eq("uri", "flowable://historic-process-instances/" + documentUri),
                        b.or(
                                b.eq("uri", "flowable://runtime-process-instances/" + documentUri),
                                b.or(
                                        b.eq("documentId", documentUri),
                                        b.eq("flowable_scope_id", documentUri)
                                )
                        )
                )
        );

        var finalExpression = b.and(matchDocExpression, securityExpression).build();
        SearchRequest searchRequest = SearchRequest.builder()
                .query(documentUri)
                .filterExpression(finalExpression)
                .topK(20)
                .build();

        try {
            List<Document> docs = getVectorStore(dimensions).similaritySearch(searchRequest);
            return docs.stream()
                    .filter(doc -> McpAclEvaluator.isAccessible(doc.getMetadata(), userPrincipal, userRoles))
                    .findFirst()
                    .map(doc -> {
                        String docId = (String) doc.getMetadata().getOrDefault("documentId", doc.getMetadata().getOrDefault("parent_document_id", ""));
                        String uri = (docId != null && !docId.isBlank()) ? docId : (String) doc.getMetadata().getOrDefault("uri", doc.getId());
                        return new McpVectorServer.DocumentDetailsResult(
                                doc.getId(),
                                uri,
                                doc.getText(),
                                doc.getMetadata(),
                                formatAcl(doc.getMetadata().getOrDefault("acl", "public"))
                        );
                    })
                    .orElseThrow(() -> new NoSuchElementException("Document not found or access denied."));
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to retrieve document details in pgvector: {}", e.getMessage(), e);
            throw new RuntimeException("Error retrieving document details from pgvector", e);
        }
    }

    private FilterExpressionBuilder.Op buildSecurityFilterExpression(
            FilterExpressionBuilder b,
            String userPrincipal,
            List<String> userRoles
    ) {
        var securityExpression = b.or(b.eq("acl", "public"), b.eq("security_allowed_read", "public"));

        if (userPrincipal != null && !userPrincipal.trim().isBlank()) {
            String principal = userPrincipal.trim();
            securityExpression = b.or(securityExpression, b.or(b.eq("acl", principal), b.eq("security_allowed_read", principal)));
        }

        if (userRoles != null && !userRoles.isEmpty()) {
            for (String role : userRoles) {
                if (role != null && !role.isBlank()) {
                    String r = role.trim();
                    securityExpression = b.or(securityExpression, b.or(b.eq("acl", r), b.eq("security_allowed_read", r)));
                }
            }
        }

        return securityExpression;
    }

    private String formatAcl(Object aclObj) {
        if (aclObj == null) return "public";
        if (aclObj instanceof Collection<?> col) {
            return String.join(",", col.stream().map(String::valueOf).toList());
        }
        return String.valueOf(aclObj);
    }

    @Override
    public List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("pgvector list accessible sources. Principal: '{}', Dimensions: {}", userPrincipal, dimensions);

        if (jdbcTemplate != null) {
            try {
                String tableName = getTableName(dimensions);
                boolean hasScope = hasScopeColumns(tableName);
                String sql = hasScope
                        ? "SELECT id, metadata, scope_id_, scope_type_ FROM " + tableName + " ORDER BY metadata->>'lastModified' DESC NULLS LAST LIMIT 500"
                        : "SELECT id, metadata FROM " + tableName + " ORDER BY metadata->>'lastModified' DESC NULLS LAST LIMIT 500";

                List<Map<String, Object>> rows = jdbcTemplate.query(
                        sql,
                        (rs, rowNum) -> {
                            String id = rs.getString("id");
                            Map<String, Object> meta = parseMetadata(rs.getObject("metadata"));
                            Map<String, Object> summary = new HashMap<>();
                            String docId = (String) meta.getOrDefault("documentId", meta.getOrDefault("parent_document_id", ""));
                            String uri = (docId != null && !docId.isBlank()) ? docId : (String) meta.getOrDefault("uri", id);
                            summary.put("id", id);
                            summary.put("uri", uri);
                            summary.put("documentId", docId != null && !docId.isBlank() ? docId : uri);
                            summary.put("acl", meta.getOrDefault("acl", "public"));
                            summary.put("security", meta.get("security"));
                            summary.put("lastModified", meta.getOrDefault("lastModified", ""));

                            // Surface Flowable collocated scope attributes if present
                            String scopeId = hasScope ? rs.getString("scope_id_") : null;
                            String scopeType = hasScope ? rs.getString("scope_type_") : null;
                            if (scopeId == null || scopeId.isBlank()) {
                                Object s = meta.get("flowable_scope_id");
                                if (s instanceof List<?> list && !list.isEmpty()) {
                                    scopeId = String.valueOf(list.get(0));
                                } else if (s != null) {
                                    scopeId = String.valueOf(s);
                                }
                            }
                            if (scopeType == null || scopeType.isBlank()) {
                                Object s = meta.get("flowable_scope_type");
                                if (s instanceof List<?> list && !list.isEmpty()) {
                                    scopeType = String.valueOf(list.get(0));
                                } else if (s != null) {
                                    scopeType = String.valueOf(s);
                                }
                            }
                            if (scopeId != null && !scopeId.isBlank()) {
                                summary.put("scope_id", scopeId);
                            }
                            if (scopeType != null && !scopeType.isBlank()) {
                                summary.put("scope_type", scopeType);
                            }

                            summary.put("_meta", meta);
                            return summary;
                        }
                );

                return rows.stream()
                        .filter(item -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> meta = (Map<String, Object>) item.remove("_meta");
                            return McpAclEvaluator.isAccessible(meta, userPrincipal, userRoles);
                        })
                        .distinct()
                        .toList();
            } catch (Exception e) {
                log.error("Failed to list accessible sources via JDBC in pgvector: {}", e.getMessage(), e);
                throw new RuntimeException("Error listing accessible sources from pgvector", e);
            }
        }

        // Fallback for mock environments without active JDBC connection
        SearchRequest searchRequest = SearchRequest.builder()
                .query("")
                .topK(200)
                .build();

        try {
            List<Document> docs = getVectorStore(dimensions).similaritySearch(searchRequest);
            return docs.stream()
                    .filter(doc -> McpAclEvaluator.isAccessible(doc.getMetadata(), userPrincipal, userRoles))
                    .map(doc -> {
                        Map<String, Object> summary = new HashMap<>();
                        String docId = (String) doc.getMetadata().getOrDefault("documentId", doc.getMetadata().getOrDefault("parent_document_id", ""));
                        String uri = (docId != null && !docId.isBlank()) ? docId : (String) doc.getMetadata().getOrDefault("uri", doc.getId());
                        summary.put("id", doc.getId());
                        summary.put("uri", uri);
                        summary.put("documentId", docId != null && !docId.isBlank() ? docId : uri);
                        summary.put("acl", doc.getMetadata().getOrDefault("acl", "public"));
                        summary.put("security", doc.getMetadata().get("security"));
                        summary.put("lastModified", doc.getMetadata().getOrDefault("lastModified", ""));

                        Object s = doc.getMetadata().get("flowable_scope_id");
                        String scopeId = null;
                        if (s instanceof List<?> list && !list.isEmpty()) {
                            scopeId = String.valueOf(list.get(0));
                        } else if (s != null) {
                            scopeId = String.valueOf(s);
                        }
                        if (scopeId != null && !scopeId.isBlank()) {
                            summary.put("scope_id", scopeId);
                        }

                        Object st = doc.getMetadata().get("flowable_scope_type");
                        String scopeType = null;
                        if (st instanceof List<?> list && !list.isEmpty()) {
                            scopeType = String.valueOf(list.get(0));
                        } else if (st != null) {
                            scopeType = String.valueOf(st);
                        }
                        if (scopeType != null && !scopeType.isBlank()) {
                            summary.put("scope_type", scopeType);
                        }

                        return summary;
                    })
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.error("Failed to list accessible sources in pgvector: {}", e.getMessage(), e);
            throw new RuntimeException("Error listing accessible sources from pgvector", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(Object metadataObj) {
        if (metadataObj == null) {
            return new HashMap<>();
        }
        if (metadataObj instanceof Map<?, ?> m) {
            return new HashMap<>((Map<String, Object>) m);
        }
        String jsonStr = metadataObj.toString();
        if (jsonStr == null || jsonStr.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(jsonStr, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse metadata JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
