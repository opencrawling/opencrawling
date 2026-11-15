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
package org.opencrawling.qdrant.messaging;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.UpdateResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.qdrant.QdrantFields;
import org.opencrawling.qdrant.QdrantPointMapper;
import org.opencrawling.qdrant.config.QdrantOutputProperties;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class QdrantStoreWriterConsumerTest {

    @Test
    void testConsumeDeleteTombstoneDeletesByFilterFromQdrant() throws Exception {
        QdrantClient client = mock(QdrantClient.class);
        QdrantOutputProperties properties = new QdrantOutputProperties("localhost", 6334, null, "enterprise_kb", 384, QdrantOutputProperties.Distance.COSINE, QdrantOutputProperties.Quantization.NONE, false, 64);
        QdrantPointMapper mapper = new QdrantPointMapper();

        when(client.deleteAsync(eq("enterprise_kb"), any(Filter.class))).thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        when(client.deleteAsync(eq("enterprise_kb"), any(List.class))).thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));

        QdrantStoreWriterConsumer consumer = new QdrantStoreWriterConsumer(client, properties, mapper);

        DocumentEmbeddedMessage tombstone = new DocumentEmbeddedMessage(
                "doc-qdrant-1",
                "chunk-qdrant-1",
                "",
                Map.of(),
                new float[0],
                DocumentAction.DELETE
        );

        consumer.consume(tombstone);

        ArgumentCaptor<Filter> filterCaptor = ArgumentCaptor.forClass(Filter.class);
        verify(client).deleteAsync(eq("enterprise_kb"), filterCaptor.capture());

        Filter capturedFilter = filterCaptor.getValue();
        assertEquals(2, capturedFilter.getShouldCount());
        assertEquals(QdrantFields.FIELD_DOCUMENT_ID, capturedFilter.getShould(0).getField().getKey());
        assertEquals("doc-qdrant-1", capturedFilter.getShould(0).getField().getMatch().getKeyword());
    }

    @Test
    void testConsumeUpsertStoresDocumentIdInPayload() throws Exception {
        QdrantClient client = mock(QdrantClient.class);
        QdrantOutputProperties properties = new QdrantOutputProperties("localhost", 6334, null, "enterprise_kb", 384, QdrantOutputProperties.Distance.COSINE, QdrantOutputProperties.Quantization.NONE, false, 64);
        QdrantPointMapper mapper = new QdrantPointMapper();

        when(client.upsertAsync(eq("enterprise_kb"), any(List.class))).thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));

        QdrantStoreWriterConsumer consumer = new QdrantStoreWriterConsumer(client, properties, mapper);

        DocumentEmbeddedMessage insertMessage = new DocumentEmbeddedMessage(
                "doc-qdrant-1",
                "chunk-qdrant-1",
                "Test chunk text content",
                Map.of("uri", "file://tmp/doc-1.txt"),
                new float[]{0.1f, 0.2f, 0.3f, 0.4f},
                DocumentAction.UPSERT
        );

        consumer.consume(insertMessage);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PointStruct>> pointsCaptor = ArgumentCaptor.forClass(List.class);
        verify(client).upsertAsync(eq("enterprise_kb"), pointsCaptor.capture());

        List<PointStruct> points = pointsCaptor.getValue();
        assertEquals(1, points.size());
        assertEquals("doc-qdrant-1", points.get(0).getPayloadMap().get(QdrantFields.FIELD_DOCUMENT_ID).getStringValue());
        assertEquals("chunk-qdrant-1", points.get(0).getPayloadMap().get(QdrantFields.FIELD_CHUNK_ID).getStringValue());
    }

    @Test
    void aFailedWriteIsRethrownSoKafkaRetriesIt() {
        QdrantClient client = mock(QdrantClient.class);
        QdrantOutputProperties properties = new QdrantOutputProperties("localhost", 6334, null, "enterprise_kb", 384, QdrantOutputProperties.Distance.COSINE, QdrantOutputProperties.Quantization.NONE, false, 64);
        when(client.upsertAsync(eq("enterprise_kb"), any(List.class)))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("connection refused")));
        QdrantStoreWriterConsumer consumer = new QdrantStoreWriterConsumer(client, properties, new QdrantPointMapper());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> consumer.consume(
                new DocumentEmbeddedMessage("doc-qdrant-1", "chunk-qdrant-1", "text", Map.of(),
                        new float[]{0.1f, 0.2f, 0.3f, 0.4f}, DocumentAction.UPSERT)));
        assertTrue(thrown.getMessage().contains("chunk-qdrant-1"));
    }
}
