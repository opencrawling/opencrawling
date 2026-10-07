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
package org.opencrawling.runtime.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.vector.config.PrecomputedEmbeddingModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VectorStoreWriterConsumerTest {

    private PgVectorStore defaultStore;
    private PgVectorStore store384;
    private PgVectorStore store768;
    private PgVectorStore store1024;
    private VectorStoreWriterConsumer consumer;

    @BeforeEach
    void setUp() {
        defaultStore = mock(PgVectorStore.class);
        store384 = mock(PgVectorStore.class);
        store768 = mock(PgVectorStore.class);
        store1024 = mock(PgVectorStore.class);
        consumer = new VectorStoreWriterConsumer(defaultStore, store384, store768, store1024, null);
    }

    @Test
    void testConsumeRoutesTo1024StoreAndSetsPrecomputedVectorInContext() {
        float[] vector1024 = new float[1024];
        vector1024[0] = 0.42f;
        vector1024[1] = 0.99f;

        DocumentEmbeddedMessage message = new DocumentEmbeddedMessage(
                "doc-1",
                "chunk-1",
                "Sample embedded text content",
                Map.of("source", "kafka"),
                vector1024
        );

        // Intercept targetStore.add call to verify that PrecomputedEmbeddingModel thread-local context contains the vector during storage
        doAnswer(invocation -> {
            PrecomputedEmbeddingModel model = new PrecomputedEmbeddingModel(1024);
            float[] retrieved = model.embed("Sample embedded text content");
            assertArrayEquals(vector1024, retrieved);
            return null;
        }).when(store1024).add(anyList());

        consumer.consume(message);

        verify(store1024).add(anyList());
        verifyNoInteractions(store384, store768, defaultStore);
    }

    @Test
    void testConsumeDeleteTombstonePurgesTheDocumentChunksFromAllVectorStores() {
        String documentId = "http://example.com/gone.html";
        DocumentEmbeddedMessage tombstone = new DocumentEmbeddedMessage(
                documentId,
                documentId,
                "",
                Map.of(),
                new float[0],
                org.opencrawling.core.document.DocumentAction.DELETE
        );

        consumer.consume(tombstone);

        Filter.Expression byDocument = new FilterExpressionBuilder().eq("documentId", documentId).build();
        for (PgVectorStore store : List.of(defaultStore, store384, store768, store1024)) {
            verify(store).delete(byDocument);
            verify(store, never()).delete(anyList());
        }
    }

    @Test
    void testFirstChunkPurgesTheChunksBeyondTheDocumentsNewLength() {
        String documentId = "http://example.com/shrunk.html";
        DocumentEmbeddedMessage firstChunk = new DocumentEmbeddedMessage(
                documentId,
                "chunk-0",
                "The only chunk left",
                Map.of("documentId", documentId, "chunk_index", 0, "total_chunks", 1),
                new float[1024]
        );

        consumer.consume(firstChunk);

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression beyondNewLength = b.and(b.eq("documentId", documentId), b.gte("chunk_index", 1)).build();
        verify(store1024).add(anyList());
        verify(store1024).delete(beyondNewLength);
    }

    @Test
    void testLaterChunksDoNotPurge() {
        String documentId = "http://example.com/page.html";
        DocumentEmbeddedMessage secondChunk = new DocumentEmbeddedMessage(
                documentId,
                "chunk-1",
                "Second chunk",
                Map.of("documentId", documentId, "chunk_index", 1, "total_chunks", 3),
                new float[1024]
        );

        consumer.consume(secondChunk);

        verify(store1024).add(anyList());
        verify(store1024, never()).delete(any(Filter.Expression.class));
    }
}
