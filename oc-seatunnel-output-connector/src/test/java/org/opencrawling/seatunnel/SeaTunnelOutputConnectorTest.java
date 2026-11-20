/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.seatunnel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.seatunnel.client.SeaTunnelRestClient;
import org.opencrawling.seatunnel.config.SeaTunnelOutputProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeast;

class SeaTunnelOutputConnectorTest {

    private SeaTunnelRestClient restClient;
    private SeaTunnelOutputProperties properties;
    private EmbeddingModel embeddingModel;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private SeaTunnelOutputConnector connector;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        restClient = Mockito.mock(SeaTunnelRestClient.class);
        properties = new SeaTunnelOutputProperties(
                "http://localhost:8080",
                "opencrawling_ingestion_pipeline",
                "STREAMING",
                5000,
                4,
                "localhost:9092",
                "opencrawling-embedded",
                "seatunnel-consumer",
                "clickhouse,milvus",
                384,
                true,
                30
        );
        embeddingModel = Mockito.mock(EmbeddingModel.class);
        Mockito.when(embeddingModel.embed(any(org.springframework.ai.document.Document.class)))
                .thenReturn(new float[384]);
        kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        connector = new SeaTunnelOutputConnector(restClient, properties, embeddingModel, kafkaTemplate);
    }

    @Test
    void testGetName() {
        assertEquals("SeaTunnelOutputConnector", connector.getName());
    }

    @Test
    void testConnect_reachableAndAutoSubmit() throws Exception {
        Mockito.when(restClient.isReachable()).thenReturn(true);
        connector.connect();

        verify(restClient).isReachable();
        verify(restClient).submitJob(eq("opencrawling_ingestion_pipeline"), any(String.class));
    }

    @Test
    void testSendDocument_upsert() {
        RepositoryDocument doc = new RepositoryDocument(
                "doc-st-1",
                "file:///tmp/seatunnel-test.txt",
                new ByteArrayInputStream("Sample text content for SeaTunnel fanout.".getBytes()),
                Map.of("mimeType", List.of("text/plain"), "title", List.of("SeaTunnel Title")),
                "ROLE_ADMIN,user:bob",
                Instant.now()
        );

        connector.send(doc).block();

        ArgumentCaptor<DocumentEmbeddedMessage> captor = ArgumentCaptor.forClass(DocumentEmbeddedMessage.class);
        // Keyed by document id, like the document's DELETE, so they reach the same partition in order.
        verify(kafkaTemplate).send(eq("opencrawling-embedded"), eq("doc-st-1"), captor.capture());

        DocumentEmbeddedMessage captured = captor.getValue();
        assertThat(captured.documentId()).isEqualTo("doc-st-1");
        assertThat(captured.action()).isEqualTo(DocumentAction.UPSERT);
        assertThat(captured.text()).isEqualTo("Sample text content for SeaTunnel fanout.");
        assertThat(captured.metadata().get("rowKind")).isEqualTo(SeaTunnelConstants.ROW_KIND_INSERT);
        assertThat(captured.metadata().get(SeaTunnelConstants.FIELD_ACL)).isEqualTo("ROLE_ADMIN,user:bob");
    }

    @Test
    void testSendDocument_deleteTombstone() {
        RepositoryDocument tombstone = RepositoryDocument.createTombstone("doc-st-delete-1", "file:///tmp/deleted.txt");

        connector.send(tombstone).block();

        ArgumentCaptor<DocumentEmbeddedMessage> captor = ArgumentCaptor.forClass(DocumentEmbeddedMessage.class);
        verify(kafkaTemplate).send(eq("opencrawling-embedded"), eq("doc-st-delete-1"), captor.capture());

        DocumentEmbeddedMessage captured = captor.getValue();
        assertThat(captured.documentId()).isEqualTo("doc-st-delete-1");
        assertThat(captured.action()).isEqualTo(DocumentAction.DELETE);
        assertThat(captured.metadata().get("rowKind")).isEqualTo(SeaTunnelConstants.ROW_KIND_DELETE);
    }

    @Test
    void testDisconnect() throws Exception {
        connector.disconnect();
        verify(restClient).close();
    }

    /** #333: a missing model, a failed embedding call or an empty vector fails the document, and nothing is written. */
    @Test
    void aDocumentWithoutItsRealVectorsIsNotStored() throws Exception {
        assertThatThrownBy(() -> new SeaTunnelOutputConnector(restClient, properties, (EmbeddingModel) null, kafkaTemplate).send(failFastDocument("doc-333-a", "Text that needs its vector.")).block())
                .hasStackTraceContaining("No embedding model is configured");

        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class))).thenReturn(new float[0]);
        assertThatThrownBy(() -> connector.send(failFastDocument("doc-333-b", "Text that needs its vector.")).block())
                .hasStackTraceContaining("returned no vector");

        doThrow(new RuntimeException("model down")).when(embeddingModel).embed(any(org.springframework.ai.document.Document.class));
        doThrow(new RuntimeException("model down")).when(embeddingModel).embed(anyString());
        assertThatThrownBy(() -> connector.send(failFastDocument("doc-333-c", "Text that needs its vector.")).block())
                .hasStackTraceContaining("model down");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    /** #333: every chunk is embedded before anything is written, so a later chunk's failure stores nothing. */
    @Test
    void aFailedLaterChunkStoresNothingOfTheDocument() throws Exception {
        doThrow(new RuntimeException("by document")).when(embeddingModel).embed(any(org.springframework.ai.document.Document.class));
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f}).thenThrow(new RuntimeException("second chunk failed"));

        assertThatThrownBy(() -> connector.send(failFastDocument("doc-333-long", "word ".repeat(5000))).block())
                .hasStackTraceContaining("second chunk failed");

        verify(embeddingModel, atLeast(2)).embed(anyString());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    /** #333: a chunk the broker did not take fails the document, and without Kafka nothing is silently dropped. */
    @Test
    void aChunkTheBrokerDidNotTakeFailsTheDocument() {
        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class))).thenReturn(new float[] {0.1f, 0.2f});
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        assertThatThrownBy(() -> connector.send(failFastDocument("doc-333-kafka", "Text for the stream.")).block())
                .hasStackTraceContaining("broker unavailable");

        SeaTunnelOutputConnector noKafka = new SeaTunnelOutputConnector(restClient, properties, embeddingModel, null);
        assertThatThrownBy(() -> noKafka.send(failFastDocument("doc-333-nokafka", "Text for the stream.")).block())
                .hasStackTraceContaining("No Kafka template is configured");
    }

    private static org.opencrawling.core.document.RepositoryDocument failFastDocument(String id, String text) {
        return new org.opencrawling.core.document.RepositoryDocument(id, "file://tmp/" + id + ".txt",
                new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.util.Map.of("mimeType", java.util.List.of("text/plain")), "",
                org.opencrawling.core.security.SecurityConfig.createPublic(), java.time.Instant.now());
    }
}
