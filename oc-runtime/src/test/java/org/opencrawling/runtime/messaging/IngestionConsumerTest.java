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
import org.opencrawling.core.claimcheck.CompositeClaimCheckStore;
import org.opencrawling.core.claimcheck.LocalFileClaimCheckStore;
import org.opencrawling.core.claimcheck.OzoneClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentChunkMessage;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.text.TextExtractionResult;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.runtime.config.KafkaConfig;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

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
    private ClaimCheckStore claimCheckStore;
    private IngestionConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        kafkaTemplate = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate).send(anyString(), anyString(), any());

        claimCheckStore = mock(ClaimCheckStore.class);
        when(claimCheckStore.supports(any())).thenReturn(true);
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

    @Test
    void chunksCarrySecurityAllowedAndDeniedRead() {
        org.opencrawling.core.security.SecurityConfig sec = new org.opencrawling.core.security.SecurityConfig(false, List.of(
                new org.opencrawling.core.security.PermissionRule("engineering", "group", "engineering", "read"),
                new org.opencrawling.core.security.PermissionRule("finance", "group", "finance", "write"),
                new org.opencrawling.core.security.PermissionRule("contractor", "group", "contractor", "deny")
        ));
        IngestionMessage msg = new IngestionMessage(
                "http://example.com/multi.html",
                "file:///claims/doc",
                Map.of(),
                "engineering,finance",
                sec,
                "2026-01-01T00:00:00Z",
                null,
                null,
                Map.of()
        );

        clearInvocations(kafkaTemplate);
        consumer.consume(msg);

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce()).send(eq(KafkaConfig.CHUNKS_TOPIC_NAME), anyString(), sent.capture());
        DocumentChunkMessage chunk = (DocumentChunkMessage) sent.getAllValues().get(0);

        assertEquals(List.of("group:engineering", "group:finance"), chunk.metadata().get("security_allowed_read"));
        assertEquals(List.of("group:contractor"), chunk.metadata().get("security_denied_read"));
    }

    @Test
    void chunksWithEmptyPermissionsCarryEmptySecurityAllowedRead() {
        org.opencrawling.core.security.SecurityConfig sec = new org.opencrawling.core.security.SecurityConfig(false, List.of());
        IngestionMessage msg = new IngestionMessage(
                "http://example.com/nobody.html",
                "file:///claims/doc",
                Map.of(),
                "",
                sec,
                "2026-01-01T00:00:00Z",
                null,
                null,
                Map.of()
        );

        clearInvocations(kafkaTemplate);
        consumer.consume(msg);

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce()).send(eq(KafkaConfig.CHUNKS_TOPIC_NAME), anyString(), sent.capture());
        DocumentChunkMessage chunk = (DocumentChunkMessage) sent.getAllValues().get(0);

        assertEquals(List.of(), chunk.metadata().get("security_allowed_read"));
        assertEquals(List.of(), chunk.metadata().get("security_denied_read"));
    }

    @Test
    void aDocumentsChunksAndItsDeleteShareOneKey() {
        // One key per document puts its chunks and its DELETE on one partition, so a writer can never apply the
        // DELETE before a chunk published earlier and bring the deleted document back.
        String documentId = "http://example.com/a.html";

        clearInvocations(kafkaTemplate);
        consumer.consume(new IngestionMessage(documentId, "file:///claims/doc", Map.of(), "public", "2026-01-01T00:00:00Z", null, null, Map.of()));
        consumer.consume(new IngestionMessage(documentId, "file:///claims/doc", Map.of(), "public", null, "2026-01-02T00:00:00Z",
                null, null, Map.of(), DocumentAction.DELETE));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce()).send(eq(KafkaConfig.CHUNKS_TOPIC_NAME), keys.capture(), sent.capture());

        List<DocumentChunkMessage> messages = sent.getAllValues().stream().map(DocumentChunkMessage.class::cast).toList();
        assertTrue(messages.size() > 2, "expected several chunks and a DELETE, got " + messages.size());
        assertEquals(DocumentAction.DELETE, messages.get(messages.size() - 1).action());
        assertEquals(Set.of(documentId), new HashSet<>(keys.getAllValues()));
    }

    @Test
    void thePartitionKeyIsNotPartOfTheMessage() throws Exception {
        DocumentChunkMessage chunk = new DocumentChunkMessage("doc-1", "chunk-1", "text", Map.of(), null, null, Map.of());

        String json = JsonMapper.builder().build().writeValueAsString(chunk);

        assertFalse(json.contains("partitionKey"), json);
    }

    @Test
    void aFailedPublishIsRethrownAndKeepsTheClaimForTheRetry() throws Exception {
        doReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")))
                .when(kafkaTemplate).send(anyString(), anyString(), any());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> consumer.consume(
                new IngestionMessage("doc-1", "file:///claims/doc", Map.of(), "public", "2026-01-01T00:00:00Z", null, null, Map.of())));

        assertTrue(thrown.getMessage().contains("doc-1"));
        // The retry reads the content again, so the claim must still be there.
        verify(claimCheckStore, never()).delete(any());
    }

    @Test
    void aMissingClaimIsSkippedRatherThanRetried() throws Exception {
        when(claimCheckStore.get(any())).thenThrow(new java.nio.file.NoSuchFileException("/claims/doc"));

        assertDoesNotThrow(() -> consumer.consume(
                new IngestionMessage("doc-1", "file:///claims/doc", Map.of(), "public", "2026-01-01T00:00:00Z", null, null, Map.of())));
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void aMetadataOnlyRepositoryUriIsSkippedRatherThanRetried(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        LocalFileClaimCheckStore localStore = new LocalFileClaimCheckStore(tempDir);
        OzoneClaimCheckStore ozoneStore = new OzoneClaimCheckStore(null, "claims", false);
        IngestionMessage metadataOnly = new IngestionMessage(
                "documentum://corp/documents/090277b680000137",
                "documentum://corp/documents/090277b680000137",
                Map.of(), "public", "2026-01-01T00:00:00Z", null, null, Map.of());

        for (ClaimCheckStore primary : List.of(localStore, ozoneStore)) {
            CompositeClaimCheckStore composite = new CompositeClaimCheckStore(primary, List.of(localStore, ozoneStore));
            IngestionConsumer realStoreConsumer = new IngestionConsumer(
                    kafkaTemplate, composite, new ClaimCheckProperties(), null,
                    mock(TextExtractionService.class));

            clearInvocations(kafkaTemplate);
            assertDoesNotThrow(() -> realStoreConsumer.consume(metadataOnly));
            verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        }
    }

    @Test
    void aMetadataOnlyMessageWithoutUriIsSkippedRatherThanRetried() {
        clearInvocations(kafkaTemplate);

        assertDoesNotThrow(() -> consumer.consume(
                new IngestionMessage("doc-no-uri", null, Map.of(), "public", "2026-01-01T00:00:00Z", null, null, Map.of())));
        assertDoesNotThrow(() -> consumer.consume(
                new IngestionMessage("doc-blank-uri", "   ", Map.of(), "public", "2026-01-01T00:00:00Z", null, null, Map.of())));
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void aClaimReadErrorIsRethrownForRetry() throws Exception {
        when(claimCheckStore.get(any())).thenThrow(new java.io.IOException("storage unavailable"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> consumer.consume(
                new IngestionMessage("doc-1", "file:///claims/doc", Map.of(), "public", "2026-01-01T00:00:00Z", null, null, Map.of())));

        assertTrue(thrown.getMessage().contains("doc-1"));
        verify(claimCheckStore, never()).delete(any());
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
