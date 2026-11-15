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
package org.opencrawling.s3.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.s3.S3OutputConnector;
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

class S3MigrationWriterConsumerTest {

    private S3OutputConnector connector;
    private ClaimCheckStore claimCheckStore;
    private S3MigrationWriterConsumer consumer;

    @BeforeEach
    void setUp() {
        connector = mock(S3OutputConnector.class);
        claimCheckStore = mock(ClaimCheckStore.class);
        consumer = new S3MigrationWriterConsumer(connector, claimCheckStore);

        when(connector.send(any())).thenReturn(Mono.empty());
    }

    @Test
    void testConsumesMigrationMessageAndInvokesConnector() throws Exception {
        URI claimUri = URI.create("s3://temp-claim-bucket/claims/doc-s3-101.pdf");
        when(claimCheckStore.get(claimUri))
                .thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

        IngestionMessage message = new IngestionMessage(
                "doc-s3-101",
                claimUri.toString(),
                Map.of("filename", List.of("doc-s3-101.pdf")),
                "read:all",
                SecurityConfig.createPublic(),
                "2026-10-30T10:00:00Z",
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
        assertEquals("doc-s3-101", sentDoc.id());
        assertEquals(claimUri.toString(), sentDoc.uri());
        assertEquals(DocumentAction.UPSERT, sentDoc.action());
    }

    @Test
    void testIgnoresRagMessages() {
        IngestionMessage message = new IngestionMessage(
                "doc-rag-ignore",
                "file:///tmp/doc.txt",
                Map.of(),
                "read:all",
                SecurityConfig.createPublic(),
                "2026-10-30T10:00:00Z",
                null,
                null,
                Map.of(),
                DocumentAction.UPSERT,
                PipelineMode.RAG
        );

        consumer.consume(message);
        verifyNoInteractions(connector);
        verifyNoInteractions(claimCheckStore);
    }

    @Test
    void testConsumesDeleteTombstoneWithoutClaimCheckBinary() {
        IngestionMessage message = new IngestionMessage(
                "doc-to-delete",
                "s3://bucket/deleted.pdf",
                Map.of(),
                "",
                SecurityConfig.createPublic(),
                "2026-10-30T10:00:00Z",
                null,
                null,
                Map.of(),
                DocumentAction.DELETE,
                PipelineMode.MIGRATION
        );

        consumer.consume(message);

        ArgumentCaptor<RepositoryDocument> captor = ArgumentCaptor.forClass(RepositoryDocument.class);
        verify(connector).send(captor.capture());
        verifyNoInteractions(claimCheckStore);

        assertEquals(DocumentAction.DELETE, captor.getValue().action());
        assertEquals("doc-to-delete", captor.getValue().id());
    }

    @Test
    void testThrowsExceptionWhenClaimCheckUnavailableForUpsert() {
        IngestionMessage message = new IngestionMessage(
                "doc-missing-binary",
                null,
                Map.of(),
                "",
                SecurityConfig.createPublic(),
                "2026-10-30T10:00:00Z",
                null,
                null,
                Map.of(),
                DocumentAction.UPSERT,
                PipelineMode.MIGRATION
        );

        assertThrows(RuntimeException.class, () -> consumer.consume(message));
    }
}
