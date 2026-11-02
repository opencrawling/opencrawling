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

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.opencrawling.qdrant.QdrantFields;
import org.opencrawling.qdrant.config.QdrantOutputProperties;
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

import static io.qdrant.client.ConditionFactory.matchKeyword;

@Component
@ConditionalOnProperty(name = "opencrawling.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "qdrant")
public class QdrantMcpSearchBackend extends GatedMcpSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(QdrantMcpSearchBackend.class);

    private final QdrantClient qdrantClient;
    private final QdrantOutputProperties properties;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public QdrantMcpSearchBackend(
            QdrantClient qdrantClient,
            QdrantOutputProperties properties,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel,
            @Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        this.qdrantClient = qdrantClient;
        this.properties = properties;
        this.embeddingModel = (ollamaEmbeddingModel != null) ? ollamaEmbeddingModel : openAiEmbeddingModel;
        log.info("Initialized QdrantMcpSearchBackend for collection '{}'.", properties.collectionName());
    }

    @Override
    public String backendType() {
        return "qdrant";
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
        log.info("Qdrant backend secure search. Query: '{}', Principal: '{}'", query, userPrincipal);
        int limit = maxResults > 0 ? maxResults : 5;

        float[] vector = null;
        if (embeddingModel != null && query != null && !query.isBlank()) {
            try {
                vector = embeddingModel.embed(query);
            } catch (Exception e) {
                log.warn("Failed to generate embedding for query in Qdrant backend: {}", e.getMessage());
            }
        }

        if (vector == null) {
            int dims = dimensions != null ? dimensions : properties.dimensions();
            vector = new float[dims];
            vector[0] = 1.0f;
        }

        List<Float> vectorList = new ArrayList<>(vector.length);
        for (float v : vector) {
            vectorList.add(v);
        }

        try {
            Points.SearchPoints searchPoints = Points.SearchPoints.newBuilder()
                    .setCollectionName(properties.collectionName())
                    .addAllVector(vectorList)
                    .setLimit(Math.max(limit * 3, 20))
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();

            List<Points.ScoredPoint> points = qdrantClient.searchAsync(searchPoints).get();

            List<Map.Entry<Points.ScoredPoint, Map<String, Object>>> candidates = points.stream()
                    .filter(point -> point.getScore() >= minScore)
                    .<Map.Entry<Points.ScoredPoint, Map<String, Object>>>map(point ->
                            new AbstractMap.SimpleEntry<>(point, convertPayload(point.getPayloadMap())))
                    .toList();
            return readable(userPrincipal, userRoles, candidates, entry -> toResourceRef(entry.getValue())).stream()
                    .limit(limit)
                    .map(entry -> {
                        Points.ScoredPoint point = entry.getKey();
                        Map<String, Object> payload = entry.getValue();
                        String id = String.valueOf(payload.getOrDefault(QdrantFields.FIELD_CHUNK_ID, point.getId().toString()));
                        String uri = String.valueOf(payload.getOrDefault(QdrantFields.FIELD_URI, ""));
                        String text = String.valueOf(payload.getOrDefault(QdrantFields.FIELD_TEXT, ""));
                        String acl = String.valueOf(payload.getOrDefault(QdrantFields.FIELD_ACL, "public"));
                        return new McpVectorServer.DocumentSearchResult(
                                id,
                                uri,
                                text,
                                payload,
                                acl,
                                point.getScore()
                        );
                    })
                    .toList();
        } catch (Exception e) {
            log.error("Failed to perform secure vector search in Qdrant: {}", e.getMessage(), e);
            throw new RuntimeException("Error executing secure vector search in Qdrant", e);
        }
    }

    @Override
    public McpVectorServer.DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("Qdrant backend get document content. URI: '{}', Principal: '{}'", documentUri, userPrincipal);

        try {
            Common.Filter filter = Common.Filter.newBuilder()
                    .addMust(matchKeyword(QdrantFields.FIELD_URI, documentUri))
                    .build();

            Points.ScrollPoints scrollPoints = Points.ScrollPoints.newBuilder()
                    .setCollectionName(properties.collectionName())
                    .setFilter(filter)
                    .setLimit(20)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();

            Points.ScrollResponse scrollResp = qdrantClient.scrollAsync(scrollPoints).get();

            List<Map<String, Object>> candidates = scrollResp.getResultList().stream()
                    .map(point -> convertPayload(point.getPayloadMap()))
                    .toList();
            return readable(userPrincipal, userRoles, candidates, QdrantMcpSearchBackend::toResourceRef).stream()
                    .findFirst()
                    .map(payload -> new McpVectorServer.DocumentDetailsResult(
                            String.valueOf(payload.getOrDefault(QdrantFields.FIELD_CHUNK_ID, "")),
                            String.valueOf(payload.getOrDefault(QdrantFields.FIELD_URI, "")),
                            String.valueOf(payload.getOrDefault(QdrantFields.FIELD_TEXT, "")),
                            payload,
                            String.valueOf(payload.getOrDefault(QdrantFields.FIELD_ACL, "public"))
                    ))
                    .orElseThrow(() -> new NoSuchElementException("Document not found or access denied."));
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to retrieve document details in Qdrant: {}", e.getMessage(), e);
            throw new RuntimeException("Error retrieving document details from Qdrant", e);
        }
    }

    @Override
    public List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("Qdrant backend list accessible sources. Principal: '{}'", userPrincipal);

        try {
            Points.ScrollPoints scrollPoints = Points.ScrollPoints.newBuilder()
                    .setCollectionName(properties.collectionName())
                    .setLimit(200)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();

            Points.ScrollResponse scrollResp = qdrantClient.scrollAsync(scrollPoints).get();

            List<Map<String, Object>> candidates = scrollResp.getResultList().stream()
                    .map(point -> convertPayload(point.getPayloadMap()))
                    .toList();
            return readable(userPrincipal, userRoles, candidates, QdrantMcpSearchBackend::toResourceRef).stream()
                    .map(payload -> {
                        Map<String, Object> summary = new HashMap<>();
                        summary.put("id", payload.getOrDefault(QdrantFields.FIELD_CHUNK_ID, ""));
                        summary.put("uri", payload.getOrDefault(QdrantFields.FIELD_URI, ""));
                        summary.put("acl", payload.getOrDefault(QdrantFields.FIELD_ACL, "public"));
                        summary.put("security", payload.get("security"));
                        summary.put("lastModified", payload.getOrDefault(QdrantFields.FIELD_LAST_MODIFIED, ""));
                        return summary;
                    })
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.error("Failed to list accessible sources in Qdrant: {}", e.getMessage(), e);
            throw new RuntimeException("Error listing accessible sources from Qdrant", e);
        }
    }

    private static ResourceRef toResourceRef(Map<String, Object> payload) {
        return ResourceRef.fromMetadata(payload);
    }

    public static Map<String, Object> convertPayload(Map<String, JsonWithInt.Value> payloadMap) {
        Map<String, Object> map = new HashMap<>();
        if (payloadMap != null) {
            payloadMap.forEach((key, val) -> map.put(key, convertValue(val)));
        }
        return map;
    }

    public static Object convertValue(JsonWithInt.Value value) {
        if (value == null) return null;
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case LIST_VALUE -> value.getListValue().getValuesList().stream()
                    .map(QdrantMcpSearchBackend::convertValue)
                    .toList();
            case STRUCT_VALUE -> {
                Map<String, Object> map = new HashMap<>();
                value.getStructValue().getFieldsMap().forEach((k, v) -> map.put(k, convertValue(v)));
                yield map;
            }
            default -> null;
        };
    }
}
