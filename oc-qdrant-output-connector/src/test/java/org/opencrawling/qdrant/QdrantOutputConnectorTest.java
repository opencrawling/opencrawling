/*
 * Copyright © ${year} the original author or authors (michael@michaelcizmar.com)
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
package org.opencrawling.qdrant;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.UpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.qdrant.config.QdrantOutputProperties;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class QdrantOutputConnectorTest {

    private QdrantOutputConnector connector;

    @Mock
    private QdrantClient qdrantClient;

    @Mock
    private EmbeddingModel embeddingModel;

    private final QdrantOutputProperties properties = new QdrantOutputProperties(
            "localhost", 6334, "", "enterprise_kb", 2,
            QdrantOutputProperties.Distance.COSINE, QdrantOutputProperties.Quantization.NONE, false, 500);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        connector = new QdrantOutputConnector(qdrantClient, properties, new QdrantPointMapper(), embeddingModel);
    }

    @Test
    void testGetName() {
        assertThat(connector.getName()).isEqualTo("QdrantOutputConnector");
    }

    @Test
    void testSendDocumentUpsertsPointsForEachChunk() throws Exception {
        String contentText = "This is a test content that is long enough to be indexed in the vector store.";
        ByteArrayInputStream bais = new ByteArrayInputStream(contentText.getBytes(StandardCharsets.UTF_8));

        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("mimeType", List.of("text/plain"));
        metadata.put("title", List.of("Test Title"));

        SecurityConfig securityConfig = new SecurityConfig(true, List.of(
                new PermissionRule("user1", "user", "User One", "read"),
                new PermissionRule("baduser", "user", "Bad User", "deny")
        ));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-123",
                "file://tmp/doc-123.txt",
                bais,
                metadata,
                "acl-123",
                securityConfig,
                Instant.now()
        );

        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class)))
                .thenReturn(new float[]{0.1f, 0.2f});
        when(qdrantClient.upsertAsync(eq("enterprise_kb"), anyList()))
                .thenReturn(Futures.immediateFuture(UpdateResult.newBuilder().build()));

        connector.send(doc).block();

        ArgumentCaptor<List<PointStruct>> captor = ArgumentCaptor.forClass(List.class);
        verify(qdrantClient, times(1)).upsertAsync(eq("enterprise_kb"), captor.capture());

        List<PointStruct> points = captor.getValue();
        assertThat(points).isNotEmpty();
        assertThat(points.get(0).getPayloadMap().get(QdrantFields.FIELD_CHUNK_ID).getStringValue()).contains("doc-123");
        assertThat(points.get(0).getPayloadMap().get(QdrantFields.FIELD_DOCUMENT_ID).getStringValue()).isEqualTo("doc-123");
        assertThat(points.get(0).getPayloadMap()).containsKeys(
                QdrantFields.FIELD_SECURITY_ALLOWED_READ,
                QdrantFields.FIELD_SECURITY_DENIED_READ,
                QdrantFields.FIELD_SECURITY_INHERITANCE);
    }

    @Test
    void testSendDeleteActionDeletesByFilter() throws Exception {
        RepositoryDocument deleteDoc = new RepositoryDocument(
                "doc-to-delete",
                "file://tmp/doc-to-delete.txt",
                new ByteArrayInputStream(new byte[0]),
                Map.of(),
                "acl-public",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.DELETE
        );

        when(qdrantClient.deleteAsync(eq("enterprise_kb"), any(Filter.class)))
                .thenReturn(Futures.immediateFuture(UpdateResult.newBuilder().build()));
        when(qdrantClient.deleteAsync(eq("enterprise_kb"), any(List.class)))
                .thenReturn(Futures.immediateFuture(UpdateResult.newBuilder().build()));

        connector.send(deleteDoc).block();

        ArgumentCaptor<Filter> captor = ArgumentCaptor.forClass(Filter.class);
        verify(qdrantClient).deleteAsync(eq("enterprise_kb"), captor.capture());
        Filter filter = captor.getValue();
        assertThat(filter.getShould(0).getField().getKey()).isEqualTo(QdrantFields.FIELD_DOCUMENT_ID);
        assertThat(filter.getShould(0).getField().getMatch().getKeyword()).isEqualTo("doc-to-delete");
    }
}
