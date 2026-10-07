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
import org.opencrawling.core.claimcheck.ClaimCheckProperties;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.messaging.DocumentChunkMessage;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.text.TextExtractionResult;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.runtime.config.KafkaConfig;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngestionConsumerTest {

    // Long enough for TokenTextSplitter's default 800-token chunks to produce several chunks.
    private static final String TEXT = IntStream.range(0, 3000)
            .mapToObj(i -> "word" + i + ".")
            .collect(Collectors.joining(" "));

    private KafkaTemplate<String, Object> kafkaTemplate;
    private IngestionConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        kafkaTemplate = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate).send(anyString(), anyString(), any());

        ClaimCheckStore claimCheckStore = mock(ClaimCheckStore.class);
        when(claimCheckStore.get(any())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[] {1}));

        TextExtractionService textExtractionService = mock(TextExtractionService.class);
        when(textExtractionService.extractText(any(byte[].class), any()))
                .thenReturn(TextExtractionResult.success(TEXT, Map.of(), false));

        consumer = new IngestionConsumer(kafkaTemplate, claimCheckStore, new ClaimCheckProperties(), null, textExtractionService);
    }

    @Test
    void reingestingTheSameDocumentProducesTheSameChunkIds() {
        List<String> first = ingest("http://example.com/a.html");
        List<String> second = ingest("http://example.com/a.html");

        assertTrue(first.size() > 1, "expected several chunks, got " + first.size());
        assertEquals(first, second);
    }

    @Test
    void chunkIdsDifferAcrossChunksAndDocuments() {
        List<String> a = ingest("http://example.com/a.html");
        List<String> b = ingest("http://example.com/b.html");

        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        assertEquals(a.size() + b.size(), all.size());
    }

    @Test
    void chunksCarryTheirPositionAndTheDocumentsChunkCount() {
        // VectorStoreWriterConsumer drops the rows past a shorter document's length using these two keys.
        List<DocumentChunkMessage> chunks = publishedChunks("http://example.com/a.html");

        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).metadata().get("chunk_index"));
            assertEquals(chunks.size(), chunks.get(i).metadata().get("total_chunks"));
        }
    }

    /** Consumes one UPSERT message for {@code documentId} and returns the chunk ids it published, in order. */
    private List<String> ingest(String documentId) {
        return publishedChunks(documentId).stream().map(DocumentChunkMessage::chunkId).toList();
    }

    /** Consumes one UPSERT message for {@code documentId} and returns the chunk messages it published, in order. */
    private List<DocumentChunkMessage> publishedChunks(String documentId) {
        clearInvocations(kafkaTemplate);
        consumer.consume(new IngestionMessage(documentId, "file:///claims/doc", Map.of(), "public", "2026-01-01T00:00:00Z", null, null, Map.of()));

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce()).send(eq(KafkaConfig.CHUNKS_TOPIC_NAME), anyString(), sent.capture());
        return sent.getAllValues().stream()
                .map(DocumentChunkMessage.class::cast)
                .toList();
    }
}
