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
package org.opencrawling.runtime.mcp.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.opensearch3.OpenSearch3Constants;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.util.ObjectBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;

@SuppressWarnings("unchecked")
class OpenSearch3McpSearchBackendTest {

    private OpenSearchClient openSearchClient;
    private EmbeddingModel embeddingModel;
    private OpenSearch3McpSearchBackend backend;

    @BeforeEach
    void setUp() {
        openSearchClient = mock(OpenSearchClient.class);
        embeddingModel = mock(EmbeddingModel.class);
        backend = new OpenSearch3McpSearchBackend(openSearchClient, embeddingModel, null);
        ReflectionTestUtils.setField(backend, "indexName", "enterprise_kb");
        ReflectionTestUtils.setField(backend, "defaultDimensions", 1024);
    }

    @Test
    void testBackendType() {
        assertThat(backend.backendType()).isEqualTo("opensearch3");
    }

    @Test
    void testSecureVectorSearch_success() throws IOException {
        when(embeddingModel.embed("opensearch3 query")).thenReturn(new float[]{0.1f, 0.2f});

        SearchResponse<Map> response = mock(SearchResponse.class);
        HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);

        Hit<Map> hit1 = mock(Hit.class);
        when(hit1.id()).thenReturn("chunk-1");
        when(hit1.score()).thenReturn(0.95);
        when(hit1.source()).thenReturn(Map.of(
                OpenSearch3Constants.FIELD_ID, "chunk-1",
                OpenSearch3Constants.FIELD_URI, "file:///opensearch3.txt",
                OpenSearch3Constants.FIELD_TEXT, "OpenSearch 3 Content",
                OpenSearch3Constants.FIELD_ACL, "engineering"
        ));

        Hit<Map> hit2 = mock(Hit.class);
        when(hit2.id()).thenReturn("chunk-2");
        when(hit2.score()).thenReturn(0.90);
        when(hit2.source()).thenReturn(Map.of(
                OpenSearch3Constants.FIELD_ID, "chunk-2",
                OpenSearch3Constants.FIELD_URI, "file:///finance.txt",
                OpenSearch3Constants.FIELD_TEXT, "Finance Content",
                OpenSearch3Constants.FIELD_ACL, "finance"
        ));

        when(hitsMetadata.hits()).thenReturn(List.of(hit1, hit2));
        when(response.hits()).thenReturn(hitsMetadata);
        when(openSearchClient.search(any(Function.class), eq(Map.class))).thenReturn(response);

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch(
                "opensearch3 query", "user@enterprise.com", List.of("engineering"), 5, 0.0, null
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("chunk-1");
        assertThat(results.get(0).acl()).isEqualTo("engineering");
    }

    @Test
    void testGetDocumentContent_success() throws IOException {
        SearchResponse<Map> response = mock(SearchResponse.class);
        HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);

        Hit<Map> hit = mock(Hit.class);
        when(hit.id()).thenReturn("chunk-1");
        when(hit.source()).thenReturn(Map.of(
                OpenSearch3Constants.FIELD_ID, "chunk-1",
                OpenSearch3Constants.FIELD_URI, "file:///doc.txt",
                OpenSearch3Constants.FIELD_TEXT, "Content",
                OpenSearch3Constants.FIELD_ACL, "public"
        ));

        when(hitsMetadata.hits()).thenReturn(List.of(hit));
        when(response.hits()).thenReturn(hitsMetadata);
        when(openSearchClient.search(any(Function.class), eq(Map.class))).thenReturn(response);

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///doc.txt", "user", List.of(), null);
        assertThat(result.id()).isEqualTo("chunk-1");
        assertThat(result.uri()).isEqualTo("file:///doc.txt");
    }

    @Test
    void testGetDocumentContent_notFoundThrows() throws IOException {
        SearchResponse<Map> response = mock(SearchResponse.class);
        HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);
        when(hitsMetadata.hits()).thenReturn(Collections.emptyList());
        when(response.hits()).thenReturn(hitsMetadata);
        when(openSearchClient.search(any(Function.class), eq(Map.class))).thenReturn(response);

        assertThatThrownBy(() -> backend.getDocumentContent("file:///missing.txt", "user", List.of(), null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testListAccessibleSources() throws IOException {
        SearchResponse<Map> response = mock(SearchResponse.class);
        HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);

        Hit<Map> hit = mock(Hit.class);
        when(hit.id()).thenReturn("chunk-1");
        when(hit.source()).thenReturn(Map.of(
                OpenSearch3Constants.FIELD_ID, "chunk-1",
                OpenSearch3Constants.FIELD_URI, "file:///doc.txt",
                OpenSearch3Constants.FIELD_ACL, "public"
        ));

        when(hitsMetadata.hits()).thenReturn(List.of(hit));
        when(response.hits()).thenReturn(hitsMetadata);
        when(openSearchClient.search(any(Function.class), eq(Map.class))).thenReturn(response);

        List<Map<String, Object>> sources = backend.listAccessibleSources("user", List.of(), null);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("id")).isEqualTo("chunk-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getDocumentContentJoinsTheDocumentsChunksInOrder() throws IOException {
        SearchResponse<Map> response = mock(SearchResponse.class);
        HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);
        List<Hit<Map>> hits = List.of(openSearchChunk(1, "second"), openSearchChunk(0, "first"));
        when(hitsMetadata.hits()).thenReturn(hits);
        when(response.hits()).thenReturn(hitsMetadata);
        when(openSearchClient.search(any(Function.class), eq(Map.class))).thenReturn(response);

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///doc.txt", "user", List.of(), null);

        assertThat(result.content()).isEqualTo("first\nsecond");
        assertThat(result.id()).isEqualTo("file:///doc.txt");
        assertThat(result.complete()).isTrue();
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> request =
                ArgumentCaptor.forClass(Function.class);
        verify(openSearchClient).search(request.capture(), eq(Map.class));
        SearchRequest searchRequest = request.getValue().apply(new SearchRequest.Builder()).build();
        assertThat(searchRequest.size()).isEqualTo(GatedMcpSearchBackend.DEFAULT_DOCUMENT_MAX_CHUNKS + 1);
        // The chunks' vectors are not fetched.
        assertThat(searchRequest.source().filter().excludes()).containsExactly(OpenSearch3Constants.FIELD_EMBEDDINGS);
        // Ordered by position before the limit, so a long document returns its first chunks.
        assertThat(searchRequest.sort()).singleElement().satisfies(sort -> {
            assertThat(sort.field().field()).isEqualTo(DocumentChunks.CHUNK_INDEX);
            assertThat(sort.field().order()).isEqualTo(org.opensearch.client.opensearch._types.SortOrder.Asc);
            assertThat(sort.field().missing().stringValue()).isEqualTo("_last");
        });
    }

    @SuppressWarnings("unchecked")
    private static Hit<Map> openSearchChunk(int index, String text) {
        Hit<Map> hit = mock(Hit.class);
        when(hit.id()).thenReturn("chunk-" + index);
        when(hit.source()).thenReturn(Map.of(
                OpenSearch3Constants.FIELD_ID, "chunk-" + index,
                OpenSearch3Constants.FIELD_DOCUMENT_ID, "file:///doc.txt",
                OpenSearch3Constants.FIELD_URI, "file:///doc.txt",
                OpenSearch3Constants.FIELD_TEXT, text,
                OpenSearch3Constants.FIELD_ACL, "public",
                DocumentChunks.CHUNK_INDEX, index,
                DocumentChunks.TOTAL_CHUNKS, 2));
        return hit;
    }
}
