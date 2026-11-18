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
package org.opencrawling.runtime.mcp.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.luxir.LuxirConstants;
import org.opencrawling.luxir.client.LuxirClient;
import org.opencrawling.luxir.config.LuxirOutputProperties;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.IOException;
import java.util.*;
import org.mockito.ArgumentCaptor;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class LuxirMcpSearchBackendTest {

    private LuxirClient luxirClient;
    private LuxirOutputProperties properties;
    private EmbeddingModel embeddingModel;
    private LuxirMcpSearchBackend backend;

    @BeforeEach
    void setUp() {
        luxirClient = mock(LuxirClient.class);
        properties = new LuxirOutputProperties(
                "http://localhost:9400", "opencrawling", "embedding_v", 1024,
                "cosine", true, 0, 30
        );
        embeddingModel = mock(EmbeddingModel.class);
        backend = new LuxirMcpSearchBackend(luxirClient, properties, embeddingModel, null);
    }

    @Test
    void testBackendType() {
        assertThat(backend.backendType()).isEqualTo("luxir");
    }

    @Test
    void testSecureVectorSearch_success() throws IOException, InterruptedException {
        when(embeddingModel.embed("luxir query")).thenReturn(new float[]{0.1f, 0.2f});

        Map<String, Object> doc1 = Map.of(
                LuxirConstants.FIELD_ID, "chunk-1",
                LuxirConstants.FIELD_URI, "file:///luxir.txt",
                LuxirConstants.FIELD_TEXT, "Luxir Content",
                LuxirConstants.FIELD_ACL, List.of("engineering"),
                "_score", 0.95
        );

        Map<String, Object> doc2 = Map.of(
                LuxirConstants.FIELD_ID, "chunk-2",
                LuxirConstants.FIELD_URI, "file:///finance.txt",
                LuxirConstants.FIELD_TEXT, "Finance Content",
                LuxirConstants.FIELD_ACL, List.of("finance"),
                "_score", 0.90
        );

        when(luxirClient.search(eq("opencrawling"), any()))
                .thenReturn(new LuxirClient.LuxirSearchResult(2, List.of(doc1, doc2)));

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch(
                "luxir query", "user@enterprise.com", List.of("engineering"), 5, 0.0, null
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("chunk-1");
    }

    @Test
    void testGetDocumentContent_success() throws IOException, InterruptedException {
        Map<String, Object> doc = Map.of(
                LuxirConstants.FIELD_ID, "chunk-1",
                LuxirConstants.FIELD_URI, "file:///doc.txt",
                LuxirConstants.FIELD_TEXT, "Doc Content",
                LuxirConstants.FIELD_ACL, "public"
        );

        when(luxirClient.search(eq("opencrawling"), any()))
                .thenReturn(new LuxirClient.LuxirSearchResult(1, List.of(doc)));

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///doc.txt", "user", List.of(), null);
        assertThat(result.id()).isEqualTo("chunk-1");
        assertThat(result.uri()).isEqualTo("file:///doc.txt");
    }

    @Test
    void testGetDocumentContent_notFoundThrows() throws IOException, InterruptedException {
        when(luxirClient.search(eq("opencrawling"), any()))
                .thenReturn(new LuxirClient.LuxirSearchResult(0, Collections.emptyList()));

        assertThatThrownBy(() -> backend.getDocumentContent("file:///missing.txt", "user", List.of(), null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testListAccessibleSources() throws IOException, InterruptedException {
        Map<String, Object> doc = Map.of(
                LuxirConstants.FIELD_ID, "chunk-1",
                LuxirConstants.FIELD_URI, "file:///doc.txt",
                LuxirConstants.FIELD_ACL, "public"
        );

        when(luxirClient.search(eq("opencrawling"), any()))
                .thenReturn(new LuxirClient.LuxirSearchResult(1, List.of(doc)));

        List<Map<String, Object>> sources = backend.listAccessibleSources("user", List.of(), null);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("id")).isEqualTo("chunk-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getDocumentContentAsksForTheDocumentsFirstPositions() throws IOException, InterruptedException {
        // The writer keeps each chunk's position and the document's chunk count in int fields.
        when(luxirClient.search(eq("opencrawling"), any())).thenReturn(new LuxirClient.LuxirSearchResult(2,
                List.of(positioned(luxirChunk(1, "second"), 1), positioned(luxirChunk(0, "first"), 0))));

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///doc.txt", "user", List.of(), null);

        assertThat(result.content()).isEqualTo("first\nsecond");
        assertThat(result.id()).isEqualTo("file:///doc.txt");
        assertThat(result.totalChunks()).isEqualTo(2);
        assertThat(result.complete()).isTrue();
        assertThat(result.metadata()).doesNotContainKey(properties.vectorField());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(luxirClient).search(eq("opencrawling"), payload.capture());
        assertThat(payload.getValue()).containsEntry("query", "uri_s:\"file:///doc.txt\" AND chunk_index_i:[0 TO "
                + (GatedMcpSearchBackend.DEFAULT_DOCUMENT_MAX_CHUNKS - 1) + "]");
        assertThat(payload.getValue()).containsEntry("limit", GatedMcpSearchBackend.DEFAULT_DOCUMENT_MAX_CHUNKS + 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void chunksStoredWithoutAPositionAreFoundByThePlainLookupAndOrderedByTheirIds() throws IOException, InterruptedException {
        // Written before the writer kept positions: the bounded lookup finds nothing.
        when(luxirClient.search(eq("opencrawling"), any()))
                .thenReturn(new LuxirClient.LuxirSearchResult(0, List.of()))
                .thenReturn(new LuxirClient.LuxirSearchResult(2, List.of(luxirChunk(1, "second"), luxirChunk(0, "first"))));

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///doc.txt", "user", List.of(), null);

        assertThat(result.content()).isEqualTo("first\nsecond");
        // Nothing records how many chunks the document has.
        assertThat(result.complete()).isFalse();
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(luxirClient, times(2)).search(eq("opencrawling"), payloads.capture());
        assertThat(payloads.getAllValues().get(1)).containsEntry("query", "uri_s:\"file:///doc.txt\"");
    }

    private static Map<String, Object> positioned(Map<String, Object> chunk, int index) {
        Map<String, Object> withPosition = new java.util.HashMap<>(chunk);
        withPosition.put(LuxirConstants.FIELD_CHUNK_INDEX, index);
        withPosition.put(LuxirConstants.FIELD_TOTAL_CHUNKS, 2);
        return withPosition;
    }

    private Map<String, Object> luxirChunk(int index, String text) {
        return Map.of(
                LuxirConstants.FIELD_ID, DocumentChunksTest.pipelineId("file:///doc.txt", index),
                LuxirConstants.FIELD_DOC_ID, "file:///doc.txt",
                LuxirConstants.FIELD_URI, "file:///doc.txt",
                LuxirConstants.FIELD_TEXT, text,
                LuxirConstants.FIELD_ACL, "public",
                properties.vectorField(), List.of(0.1f, 0.2f));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLongDocumentStoredWithoutPositionsIsAskedForItsFirstChunksById() throws IOException, InterruptedException {
        backend.setDocumentMaxChunks(2);
        // No position: the bounded lookup finds nothing; the plain lookup fills a whole fetch (3), so there are more.
        when(luxirClient.search(eq("opencrawling"), any()))
                .thenReturn(new LuxirClient.LuxirSearchResult(0, List.of()))
                .thenReturn(new LuxirClient.LuxirSearchResult(3,
                        List.of(luxirChunk(7, "eighth"), luxirChunk(3, "fourth"), luxirChunk(5, "sixth"))))
                .thenReturn(new LuxirClient.LuxirSearchResult(2, List.of(luxirChunk(1, "second"), luxirChunk(0, "first"))));

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///doc.txt", "user", List.of(), null);

        assertThat(result.content()).isEqualTo("first\nsecond");
        assertThat(result.complete()).isFalse();
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(luxirClient, times(3)).search(eq("opencrawling"), payloads.capture());
        assertThat((String) payloads.getAllValues().get(2).get("query")).isEqualTo("uri_s:\"file:///doc.txt\" AND (id:\""
                + DocumentChunksTest.pipelineId("file:///doc.txt", 0) + "\" OR id:\""
                + DocumentChunksTest.pipelineId("file:///doc.txt", 1) + "\")");
    }
}
