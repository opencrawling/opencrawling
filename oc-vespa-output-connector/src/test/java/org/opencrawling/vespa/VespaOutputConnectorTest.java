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
package org.opencrawling.vespa;

import ai.vespa.feed.client.DocumentId;
import ai.vespa.feed.client.FeedClient;
import ai.vespa.feed.client.OperationParameters;
import ai.vespa.feed.client.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.vespa.config.VespaOutputProperties;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeast;

class VespaOutputConnectorTest {

    private VespaOutputConnector connector;

    @Mock
    private FeedClient feedClient;

    @Mock
    private EmbeddingModel embeddingModel;

    private final VespaOutputProperties properties = VespaOutputProperties.builder()
            .endpoint("http://localhost:8080")
            .dimensions(2)
            .build();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        connector = new VespaOutputConnector(feedClient, properties, new VespaDocumentMapper(), embeddingModel);
    }

    @Test
    void testGetName() {
        assertThat(connector.getName()).isEqualTo("VespaOutputConnector");
    }

    @Test
    void testSendDocumentFeedsEachChunk() throws Exception {
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

        Result result = mock(Result.class);
        when(feedClient.put(any(DocumentId.class), anyString(), any(OperationParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(result));

        connector.send(doc).block();

        ArgumentCaptor<DocumentId> idCaptor = ArgumentCaptor.forClass(DocumentId.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(feedClient, times(1)).put(idCaptor.capture(), jsonCaptor.capture(), any(OperationParameters.class));

        assertThat(idCaptor.getValue().namespace()).isEqualTo("opencrawling");
        assertThat(idCaptor.getValue().documentType()).isEqualTo("opencrawling_chunk");
        assertThat(idCaptor.getValue().userSpecific()).contains("doc-123");
        assertThat(jsonCaptor.getValue()).contains("\"security_allowed_read\"");
        assertThat(jsonCaptor.getValue()).contains("\"security_denied_read\"");
    }

    @Test
    void testSendRoutesToDimensionSpecificDocumentTypeWithoutAnyConfigChange() throws Exception {
        String contentText = "Switching embedding models should not require redeploying anything.";
        ByteArrayInputStream bais = new ByteArrayInputStream(contentText.getBytes(StandardCharsets.UTF_8));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-768",
                "file://tmp/doc-768.txt",
                bais,
                Map.of(),
                "acl-768",
                Instant.now()
        );

        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class)))
                .thenReturn(new float[768]);

        Result result = mock(Result.class);
        when(feedClient.put(any(DocumentId.class), anyString(), any(OperationParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(result));

        connector.send(doc).block();

        ArgumentCaptor<DocumentId> idCaptor = ArgumentCaptor.forClass(DocumentId.class);
        verify(feedClient, times(1)).put(idCaptor.capture(), anyString(), any(OperationParameters.class));

        assertThat(idCaptor.getValue().documentType()).isEqualTo(VespaFields.DOCUMENT_TYPE_768);
    }

    /** #333: a missing model, a failed embedding call or an empty vector fails the document, and nothing is written. */
    @Test
    void aDocumentWithoutItsRealVectorsIsNotStored() throws Exception {
        assertThatThrownBy(() -> new VespaOutputConnector(feedClient, properties, new VespaDocumentMapper(), (EmbeddingModel) null).send(failFastDocument("doc-333-a", "Text that needs its vector.")).block())
                .hasStackTraceContaining("No embedding model is configured");

        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class))).thenReturn(new float[0]);
        assertThatThrownBy(() -> connector.send(failFastDocument("doc-333-b", "Text that needs its vector.")).block())
                .hasStackTraceContaining("returned no vector");

        doThrow(new RuntimeException("model down")).when(embeddingModel).embed(any(org.springframework.ai.document.Document.class));
        doThrow(new RuntimeException("model down")).when(embeddingModel).embed(anyString());
        assertThatThrownBy(() -> connector.send(failFastDocument("doc-333-c", "Text that needs its vector.")).block())
                .hasStackTraceContaining("model down");

        verify(feedClient, never()).put(any(DocumentId.class), anyString(), any(OperationParameters.class));
    }

    /** #333: every chunk is embedded before anything is written, so a later chunk's failure stores nothing. */
    @Test
    void aFailedLaterChunkStoresNothingOfTheDocument() throws Exception {
        doThrow(new RuntimeException("by document")).when(embeddingModel).embed(any(org.springframework.ai.document.Document.class));
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f}).thenThrow(new RuntimeException("second chunk failed"));

        assertThatThrownBy(() -> connector.send(failFastDocument("doc-333-long", "word ".repeat(5000))).block())
                .hasStackTraceContaining("second chunk failed");

        verify(embeddingModel, atLeast(2)).embed(anyString());
        verify(feedClient, never()).put(any(DocumentId.class), anyString(), any(OperationParameters.class));
    }

    private static org.opencrawling.core.document.RepositoryDocument failFastDocument(String id, String text) {
        return new org.opencrawling.core.document.RepositoryDocument(id, "file://tmp/" + id + ".txt",
                new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.util.Map.of("mimeType", java.util.List.of("text/plain")), "",
                org.opencrawling.core.security.SecurityConfig.createPublic(), java.time.Instant.now());
    }

    @Test
    void aTombstoneRemovesEveryChunkTheDocumentStoredInEveryDocumentType() throws Exception {
        // A per-job Vespa output gets RAG tombstones directly (the pipeline's writer does not reach it). What the
        // connector feeds is stored in the stand-in, which deletes by selection like Vespa.
        try (VespaDocumentApiStandIn vespa = new VespaDocumentApiStandIn()) {
            Result result = mock(Result.class);
            when(feedClient.put(any(DocumentId.class), anyString(), any(OperationParameters.class))).thenAnswer(call -> {
                DocumentId id = call.getArgument(0);
                com.fasterxml.jackson.databind.JsonNode fields = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree((String) call.getArgument(1)).path("fields");
                vespa.store(id.namespace(), id.documentType(), id.userSpecific(),
                        fields.path("document_id").asText(null), fields.path("uri").asText());
                return CompletableFuture.completedFuture(result);
            });
            // 1024 dimensions: the chunks go to the dimension-specific document type, not the configured default one.
            when(embeddingModel.embed(any(org.springframework.ai.document.Document.class))).thenReturn(new float[1024]);
            VespaOutputConnector local = new VespaOutputConnector(feedClient, vespa.properties(), new VespaDocumentMapper(),
                    embeddingModel);
            String text = "Section of the handbook covers retention, indexing and search practices. ".repeat(400);
            local.send(failFastDocument("doc-777", text)).block();
            assertThat(vespa.documents).hasSizeGreaterThan(1)
                    .allSatisfy((id, fields) -> assertThat(id).startsWith("id:opencrawling:opencrawling_chunk_1024::"));

            local.send(RepositoryDocument.createTombstone("doc-777", "file://tmp/doc-777.txt")).block();

            assertThat(vespa.documents).isEmpty();
        }
    }

    @Test
    void aTombstoneForADocumentNothingWasStoredForRemovesNothing() throws Exception {
        try (VespaDocumentApiStandIn vespa = new VespaDocumentApiStandIn()) {
            vespa.store("opencrawling", "opencrawling_chunk_1024", "kept", "another-doc", "file://tmp/another.txt");
            VespaOutputConnector local = new VespaOutputConnector(feedClient, vespa.properties(), new VespaDocumentMapper(),
                    embeddingModel);

            local.send(RepositoryDocument.createTombstone("doc-778", "file://tmp/doc-778.txt")).block();

            assertThat(vespa.documents).containsOnlyKeys("id:opencrawling:opencrawling_chunk_1024::kept");
        }
    }

    @Test
    void disconnectingClosesTheChunkRemoversClientToo() throws Exception {
        // A per-job output is disconnected when its run ends; the deletions' HTTP client must not outlive it.
        try (VespaDocumentApiStandIn vespa = new VespaDocumentApiStandIn()) {
            VespaOutputConnector local = new VespaOutputConnector(feedClient, vespa.properties(), new VespaDocumentMapper(),
                    embeddingModel);
            local.send(RepositoryDocument.createTombstone("doc-779", "file://tmp/doc-779.txt")).block();

            local.disconnect();

            verify(feedClient).close();
            assertThatThrownBy(() -> local.send(RepositoryDocument.createTombstone("doc-779", "file://tmp/doc-779.txt"))
                    .block()).hasRootCauseMessage("The Vespa chunk remover for " + vespa.properties().endpoint()
                    + " is closed");
        }
    }

    @Test
    void aTombstoneVespaCannotBeAskedToDeleteFailsTheDocument() {
        // Nothing listens on the endpoint: the delete must not report success.
        VespaOutputConnector local = new VespaOutputConnector(feedClient, VespaOutputProperties.builder()
                .endpoint("http://127.0.0.1:59998")
                .dimensions(2)
                .timeoutSeconds(2)
                .build(),
                new VespaDocumentMapper(), embeddingModel);

        assertThatThrownBy(() -> local.send(RepositoryDocument.createTombstone("doc-779", "file://tmp/doc-779.txt")).block())
                .hasMessageContaining("doc-779");
    }

    @Test
    void aDirectWriteDoesNotFallBackToAnOlderSchema() {
        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class))).thenReturn(new float[]{0.1f, 0.2f});
        when(feedClient.put(any(DocumentId.class), anyString(), any(OperationParameters.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(
                        "Field 'document_id' is not defined in document type 'opencrawling_chunk'")));

        assertThatThrownBy(() -> connector.send(failFastDocument("doc-11", "Some text to feed.")).block())
                .hasStackTraceContaining("deploy the updated schemas");
        verify(feedClient, times(1)).put(any(DocumentId.class), anyString(), any(OperationParameters.class));
    }
}
