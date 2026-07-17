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
package org.opencrawling.milvus;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.milvus.config.MilvusStoreConfig;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.testcontainers.milvus.MilvusContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MilvusOutputConnectorIT {

    @Container
    private static final MilvusContainer milvus = new MilvusContainer("milvusdb/milvus:v2.4.5")
            .withEnv("KNOWHERE_SIMD_TYPE", "sse4_2");

    private static MilvusClientV2 milvusClient;
    private static MilvusOutputConnector connector;

    @BeforeAll
    static void setUpAll() {
        // Start Milvus container and initialize Client
        String endpoint = milvus.getEndpoint();
        System.setProperty("spring.opencrawling.output.type", "milvus");
        System.setProperty("spring.opencrawling.output.milvus.uri", endpoint);
        System.setProperty("spring.opencrawling.output.milvus.token", "root:Milvus");
        System.setProperty("spring.opencrawling.output.milvus.collection-name", "it_kb");
        System.setProperty("spring.opencrawling.output.milvus.dimensions", "4");

        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri(endpoint)
                .token("root:Milvus")
                .build();
        milvusClient = new MilvusClientV2(connectConfig);

        // Define a simple dummy embedding model for mapping
        EmbeddingModel dummyModel = new EmbeddingModel() {
            @Override
            public float[] embed(Document document) {
                return new float[]{0.1f, 0.2f, 0.3f, 0.4f};
            }

            @Override
            public float[] embed(String text) {
                return new float[]{0.1f, 0.2f, 0.3f, 0.4f};
            }

            @Override
            public int dimensions() {
                return 4;
            }

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> embeddings = request.getInstructions().stream()
                        .map(text -> new Embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, 0))
                        .toList();
                return new EmbeddingResponse(embeddings);
            }
        };

        // Initialize collection schema manually using the configuration steps (since we don't boot full Spring context in this IT test)
        MilvusStoreConfig helper = new MilvusStoreConfig();
        // Use reflection or set values to helper fields
        setField(helper, "uri", endpoint);
        setField(helper, "token", "root:Milvus");
        setField(helper, "collectionName", "it_kb");
        setField(helper, "vectorFieldName", "embeddings");
        setField(helper, "dimensions", 4);
        setField(helper, "indexType", "HNSW");
        setField(helper, "metricType", "COSINE");

        // Execute initializer
        helper.milvusClientV2();

        connector = new MilvusOutputConnector(milvusClient, dummyModel);
        setField(connector, "collectionName", "it_kb");
        setField(connector, "vectorFieldName", "embeddings");
        setField(connector, "dimensions", 4);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testCollectionCreationAndIngestionFlow() throws Exception {
        // Verify collection exists
        boolean hasColl = milvusClient.hasCollection(HasCollectionReq.builder()
                .collectionName("it_kb")
                .build());
        assertThat(hasColl).isTrue();

        // 1. Send public document
        RepositoryDocument publicDoc = new RepositoryDocument(
                "doc-public",
                "file://tmp/doc-public.txt",
                new ByteArrayInputStream("Public information about RAG systems.".getBytes(StandardCharsets.UTF_8)),
                Map.of("mimeType", List.of("text/plain"), "title", List.of("Public Doc")),
                "acl-public",
                new SecurityConfig(true, List.of(new PermissionRule("public", "public", "Everyone", "read"))),
                Instant.now()
        );
        connector.send(publicDoc).block();

        // 2. Send restricted document
        RepositoryDocument restrictedDoc = new RepositoryDocument(
                "doc-restricted",
                "file://tmp/doc-restricted.txt",
                new ByteArrayInputStream("Top secret restricted document containing payroll details.".getBytes(StandardCharsets.UTF_8)),
                Map.of("mimeType", List.of("text/plain"), "title", List.of("Restricted Doc")),
                "acl-restricted",
                new SecurityConfig(true, List.of(
                        new PermissionRule("hr-group", "oauth-group", "HR Department", "read"),
                        new PermissionRule("finance-dept", "oauth-group", "Finance Dept", "read"),
                        new PermissionRule("external-user", "user", "External", "deny")
                )),
                Instant.now()
        );
        connector.send(restrictedDoc).block();

        // Load collection to memory before search
        milvusClient.loadCollection(io.milvus.v2.service.collection.request.LoadCollectionReq.builder()
                .collectionName("it_kb")
                .build());

        // Perform search mimicking a normal user (no permissions)
        SearchReq searchPublic = SearchReq.builder()
                .collectionName("it_kb")
                .data(Collections.singletonList(new FloatVec(Arrays.asList(0.1f, 0.2f, 0.3f, 0.4f))))
                .filter("ARRAY_CONTAINS(security_allowed_read, 'public')")
                .topK(10)
                .outputFields(Arrays.asList("id", "text", "uri", "security_allowed_read"))
                .build();

        SearchResp respPublic = milvusClient.search(searchPublic);
        List<List<SearchResp.SearchResult>> resultsPublic = respPublic.getSearchResults();
        assertThat(resultsPublic).isNotEmpty();
        assertThat(resultsPublic.get(0)).isNotEmpty();
        assertThat(resultsPublic.get(0).get(0).getEntity().get("id").toString()).contains("doc-public");

        // Perform search mimicking HR user ("hr-group")
        SearchReq searchHr = SearchReq.builder()
                .collectionName("it_kb")
                .data(Collections.singletonList(new FloatVec(Arrays.asList(0.1f, 0.2f, 0.3f, 0.4f))))
                .filter("ARRAY_CONTAINS(security_allowed_read, 'hr-group')")
                .topK(10)
                .outputFields(Arrays.asList("id", "text", "security_allowed_read"))
                .build();

        SearchResp respHr = milvusClient.search(searchHr);
        List<List<SearchResp.SearchResult>> resultsHr = respHr.getSearchResults();
        assertThat(resultsHr).isNotEmpty();
        assertThat(resultsHr.get(0)).isNotEmpty();
        assertThat(resultsHr.get(0).get(0).getEntity().get("id").toString()).contains("doc-restricted");
    }
}
