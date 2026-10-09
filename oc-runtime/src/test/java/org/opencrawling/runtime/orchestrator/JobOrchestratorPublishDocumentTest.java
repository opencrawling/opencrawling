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
package org.opencrawling.runtime.orchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobOrchestratorPublishDocumentTest {

    private static final String URL = "http://example.com/page.html";

    private KafkaTemplate<String, Object> kafkaTemplate;
    private ClaimCheckStore claimCheckStore;
    private JobOrchestrator orchestrator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        claimCheckStore = mock(ClaimCheckStore.class);
        orchestrator = new JobOrchestrator(kafkaTemplate, claimCheckStore, mock(TelemetryTraceStore.class));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void upsertStoresTheTextAsPlainTextAndPublishesItsClaimForRag() throws Exception {
        URI claim = URI.create("file:///claims/page.txt");
        when(claimCheckStore.put(anyString(), any(InputStream.class), anyLong(), eq("text/plain"))).thenReturn(claim);
        RepositoryDocument page = new RepositoryDocument(URL, URL,
                new ByteArrayInputStream("page text".getBytes(StandardCharsets.UTF_8)),
                Map.of("title", List.of("Page")), "ROLE_USER", SecurityConfig.createPublic(), Instant.now(), DocumentAction.UPSERT);

        orchestrator.publishDocument(page, null);

        ArgumentCaptor<InputStream> stored = ArgumentCaptor.forClass(InputStream.class);
        verify(claimCheckStore).put(anyString(), stored.capture(), anyLong(), eq("text/plain"));
        assertEquals("page text", new String(stored.getValue().readAllBytes(), StandardCharsets.UTF_8));

        IngestionMessage message = sentMessage(URL);
        assertEquals(claim.toString(), message.uri());
        assertEquals(DocumentAction.UPSERT, message.action());
        assertEquals(PipelineMode.RAG, message.pipelineMode());
        assertEquals("ollama", message.transformationEngine());
        assertEquals("ROLE_USER", message.acl());
        assertEquals(List.of("Page"), message.metadata().get("title"));
    }

    @Test
    void upsertMetadataDescribesTheStoredPlainText() throws Exception {
        when(claimCheckStore.put(anyString(), any(InputStream.class), anyLong(), anyString())).thenReturn(URI.create("file:///claims/x"));
        Map<String, List<String>> producerMetadata = Map.of(
                "mimeType", List.of("application/pdf"), "mimetype", List.of("application/pdf"),
                "Content-Type", List.of("application/pdf"), "content-type", List.of("application/pdf"),
                "title", List.of("Report"));
        RepositoryDocument pdfText = new RepositoryDocument(URL, URL,
                new ByteArrayInputStream("extracted text".getBytes(StandardCharsets.UTF_8)), producerMetadata, "ROLE_USER", Instant.now());

        orchestrator.publishDocument(pdfText, null);

        Map<String, List<String>> metadata = sentMessage(URL).metadata();
        assertEquals(List.of("text/plain"), metadata.get("mimeType"));
        assertFalse(metadata.containsKey("mimetype"));
        assertFalse(metadata.containsKey("Content-Type"));
        assertFalse(metadata.containsKey("content-type"));
        assertEquals(List.of("Report"), metadata.get("title"));
    }

    @Test
    void deletePublishesATombstoneWithoutTouchingTheClaimCheckStore() throws Exception {
        RepositoryDocument gone = new RepositoryDocument(URL, URL, null, Map.of(), "ROLE_USER",
                SecurityConfig.createPublic(), Instant.now(), DocumentAction.DELETE);

        orchestrator.publishDocument(gone, null);

        verifyNoInteractions(claimCheckStore);
        IngestionMessage message = sentMessage(URL);
        assertEquals(DocumentAction.DELETE, message.action());
        assertEquals(URL, message.uri());
    }

    @Test
    void kafkaFailureIsThrownToTheCaller() throws Exception {
        when(claimCheckStore.put(anyString(), any(InputStream.class), anyLong(), anyString())).thenReturn(URI.create("file:///claims/x"));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        RepositoryDocument page = new RepositoryDocument(URL, URL,
                new ByteArrayInputStream("text".getBytes(StandardCharsets.UTF_8)), Map.of(), "ROLE_USER", Instant.now());

        ExecutionException thrown = assertThrows(ExecutionException.class, () -> orchestrator.publishDocument(page, null));
        assertEquals("broker down", thrown.getCause().getMessage());
    }

    private IngestionMessage sentMessage(String key) {
        ArgumentCaptor<IngestionMessage> sent = ArgumentCaptor.forClass(IngestionMessage.class);
        verify(kafkaTemplate).send(eq(KafkaConfig.TOPIC_NAME), eq(key), sent.capture());
        return sent.getValue();
    }
}
