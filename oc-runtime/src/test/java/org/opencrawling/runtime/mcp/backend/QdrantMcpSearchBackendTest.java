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

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.qdrant.QdrantFields;
import org.opencrawling.qdrant.config.QdrantOutputProperties;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QdrantMcpSearchBackendTest {

    private QdrantClient qdrantClient;
    private QdrantOutputProperties properties;
    private EmbeddingModel embeddingModel;
    private QdrantMcpSearchBackend backend;

    @BeforeEach
    void setUp() {
        qdrantClient = mock(QdrantClient.class);
        properties = new QdrantOutputProperties(
                "localhost", 6334, "", "enterprise_kb", 1024,
                QdrantOutputProperties.Distance.COSINE,
                QdrantOutputProperties.Quantization.NONE,
                false, 500
        );
        embeddingModel = mock(EmbeddingModel.class);
        backend = new QdrantMcpSearchBackend(qdrantClient, properties, embeddingModel, null);
    }

    @Test
    void testBackendType() {
        assertThat(backend.backendType()).isEqualTo("qdrant");
    }

    @Test
    void testSecureVectorSearch_success() {
        when(embeddingModel.embed("qdrant search")).thenReturn(new float[]{0.1f, 0.2f});

        Points.ScoredPoint point1 = Points.ScoredPoint.newBuilder()
                .setId(PointIdFactory.id(UUID.randomUUID()))
                .setScore(0.85f)
                .putPayload(QdrantFields.FIELD_CHUNK_ID, ValueFactory.value("chunk-1"))
                .putPayload(QdrantFields.FIELD_URI, ValueFactory.value("file:///qdrant.txt"))
                .putPayload(QdrantFields.FIELD_TEXT, ValueFactory.value("Qdrant Documentation"))
                .putPayload(QdrantFields.FIELD_ACL, ValueFactory.value("engineering"))
                .build();

        Points.ScoredPoint point2 = Points.ScoredPoint.newBuilder()
                .setId(PointIdFactory.id(UUID.randomUUID()))
                .setScore(0.90f)
                .putPayload(QdrantFields.FIELD_CHUNK_ID, ValueFactory.value("chunk-2"))
                .putPayload(QdrantFields.FIELD_URI, ValueFactory.value("file:///finance.txt"))
                .putPayload(QdrantFields.FIELD_TEXT, ValueFactory.value("Confidential Finance"))
                .putPayload(QdrantFields.FIELD_ACL, ValueFactory.value("finance"))
                .build();

        when(qdrantClient.searchAsync(any(Points.SearchPoints.class)))
                .thenReturn(Futures.immediateFuture(List.of(point1, point2)));

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch(
                "qdrant search", "user@enterprise.com", List.of("engineering"), 5, 0.0, null
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("chunk-1");
        assertThat(results.get(0).acl()).isEqualTo("engineering");
    }

    @Test
    void testGetDocumentContent_success() {
        Points.RetrievedPoint point = Points.RetrievedPoint.newBuilder()
                .setId(PointIdFactory.id(UUID.randomUUID()))
                .putPayload(QdrantFields.FIELD_CHUNK_ID, ValueFactory.value("chunk-1"))
                .putPayload(QdrantFields.FIELD_URI, ValueFactory.value("file:///doc.txt"))
                .putPayload(QdrantFields.FIELD_TEXT, ValueFactory.value("Doc Content"))
                .putPayload(QdrantFields.FIELD_ACL, ValueFactory.value("public"))
                .build();

        Points.ScrollResponse scrollResponse = Points.ScrollResponse.newBuilder()
                .addResult(point)
                .build();

        when(qdrantClient.scrollAsync(any(Points.ScrollPoints.class)))
                .thenReturn(Futures.immediateFuture(scrollResponse));

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///doc.txt", "user", List.of(), null);
        assertThat(result.id()).isEqualTo("chunk-1");
        assertThat(result.uri()).isEqualTo("file:///doc.txt");
    }

    @Test
    void testGetDocumentContent_notFoundThrows() {
        Points.ScrollResponse scrollResponse = Points.ScrollResponse.newBuilder().build();
        when(qdrantClient.scrollAsync(any(Points.ScrollPoints.class)))
                .thenReturn(Futures.immediateFuture(scrollResponse));

        assertThatThrownBy(() -> backend.getDocumentContent("file:///missing.txt", "user", List.of(), null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testListAccessibleSources() {
        Points.RetrievedPoint point = Points.RetrievedPoint.newBuilder()
                .setId(PointIdFactory.id(UUID.randomUUID()))
                .putPayload(QdrantFields.FIELD_CHUNK_ID, ValueFactory.value("chunk-1"))
                .putPayload(QdrantFields.FIELD_URI, ValueFactory.value("file:///doc.txt"))
                .putPayload(QdrantFields.FIELD_ACL, ValueFactory.value("public"))
                .build();

        Points.ScrollResponse scrollResponse = Points.ScrollResponse.newBuilder()
                .addResult(point)
                .build();

        when(qdrantClient.scrollAsync(any(Points.ScrollPoints.class)))
                .thenReturn(Futures.immediateFuture(scrollResponse));

        List<Map<String, Object>> sources = backend.listAccessibleSources("user", List.of(), null);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("id")).isEqualTo("chunk-1");
    }
}
