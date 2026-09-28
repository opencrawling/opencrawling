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
        verify(kafkaTemplate).send(eq("opencrawling-embedded"), any(String.class), captor.capture());

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
        verify(kafkaTemplate).send(eq("opencrawling-embedded"), any(String.class), captor.capture());

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
}
