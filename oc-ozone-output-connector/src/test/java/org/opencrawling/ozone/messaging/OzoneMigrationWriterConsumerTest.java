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
package org.opencrawling.ozone.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.ozone.OzoneOutputConnector;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OzoneMigrationWriterConsumerTest {

    private OzoneOutputConnector connector;
    private ClaimCheckStore claimCheckStore;
    private OzoneMigrationWriterConsumer consumer;

    @BeforeEach
    void setUp() {
        connector = mock(OzoneOutputConnector.class);
        claimCheckStore = mock(ClaimCheckStore.class);
        consumer = new OzoneMigrationWriterConsumer(connector, claimCheckStore);

        when(connector.send(any())).thenReturn(Mono.empty());
    }

    @Test
    void testConsumesMigrationMessageAndInvokesConnector() throws Exception {
        URI claimUri = URI.create("ofs://s3v/claims/doc-101.pdf");
        when(claimCheckStore.get(claimUri))
                .thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

        IngestionMessage message = new IngestionMessage(
                "doc-101",
                claimUri.toString(),
                Map.of("filename", List.of("doc-101.pdf")),
                "read:all",
                SecurityConfig.createPublic(),
                "2026-10-05T20:00:00Z",
                null,
                null,
                Map.of(),
                DocumentAction.UPSERT,
                PipelineMode.MIGRATION
        );

        consumer.consume(message);

        ArgumentCaptor<RepositoryDocument> captor = ArgumentCaptor.forClass(RepositoryDocument.class);
        verify(connector).send(captor.capture());

        RepositoryDocument sentDoc = captor.getValue();
        assertEquals("doc-101", sentDoc.id());
        assertEquals(claimUri.toString(), sentDoc.uri());
    }

    @Test
    void testIgnoresRagMessages() {
        IngestionMessage message = new IngestionMessage(
                "doc-rag-ignore",
                "file:///tmp/doc.txt",
                Map.of(),
                "read:all",
                SecurityConfig.createPublic(),
                "2026-10-05T20:00:00Z",
                null,
                null,
                Map.of(),
                DocumentAction.UPSERT,
                PipelineMode.RAG
        );

        consumer.consume(message);

        verify(connector, never()).send(any());
        verifyNoInteractions(claimCheckStore);
    }

    @Test
    void testUpsertWithoutClaimCheckContentFailsInsteadOfWritingEmptyBinary() throws Exception {
        URI claimUri = URI.create("s3://claims/missing.pdf");
        when(claimCheckStore.get(claimUri)).thenThrow(new java.io.FileNotFoundException("gone"));

        IngestionMessage message = new IngestionMessage(
                "doc-missing", claimUri.toString(), Map.of(), "", SecurityConfig.createPublic(),
                "2026-10-05T20:00:00Z", null, null, Map.of(), DocumentAction.UPSERT, PipelineMode.MIGRATION);

        assertThrows(IllegalStateException.class, () -> consumer.consume(message));
        verify(connector, never()).send(any());
    }

    @Test
    void testConnectorFailureIsPropagatedForKafkaRetry() throws Exception {
        URI claimUri = URI.create("s3://claims/doc.pdf");
        when(claimCheckStore.get(claimUri)).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        when(connector.send(any())).thenReturn(Mono.error(new RuntimeException("ozone down")));

        IngestionMessage message = new IngestionMessage(
                "doc-fail", claimUri.toString(), Map.of(), "", SecurityConfig.createPublic(),
                "2026-10-05T20:00:00Z", null, null, Map.of(), DocumentAction.UPSERT, PipelineMode.MIGRATION);

        assertThrows(IllegalStateException.class, () -> consumer.consume(message));
    }

    @Test
    void testDeleteTombstoneDoesNotTouchClaimCheck() {
        IngestionMessage message = new IngestionMessage(
                "doc-del", "file:///data/doc.pdf", Map.of(), "", SecurityConfig.createPublic(),
                "2026-10-05T20:00:00Z", null, null, Map.of(), DocumentAction.DELETE, PipelineMode.MIGRATION);

        consumer.consume(message);

        ArgumentCaptor<RepositoryDocument> captor = ArgumentCaptor.forClass(RepositoryDocument.class);
        verify(connector).send(captor.capture());
        assertEquals(DocumentAction.DELETE, captor.getValue().action());
        verifyNoInteractions(claimCheckStore);
    }
}
