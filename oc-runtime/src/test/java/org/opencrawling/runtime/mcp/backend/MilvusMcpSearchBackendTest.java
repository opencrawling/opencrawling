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

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.milvus.MilvusConstants;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import org.mockito.ArgumentCaptor;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class MilvusMcpSearchBackendTest {

    private MilvusClientV2 milvusClient;
    private EmbeddingModel embeddingModel;
    private MilvusMcpSearchBackend backend;

    @BeforeEach
    void setUp() {
        milvusClient = mock(MilvusClientV2.class);
        embeddingModel = mock(EmbeddingModel.class);
        backend = new MilvusMcpSearchBackend(milvusClient, embeddingModel, null);
        ReflectionTestUtils.setField(backend, "collectionName", "enterprise_kb");
        ReflectionTestUtils.setField(backend, "vectorFieldName", "embeddings");
        ReflectionTestUtils.setField(backend, "defaultDimensions", 1024);
    }

    @Test
    void testBackendType() {
        assertThat(backend.backendType()).isEqualTo("milvus");
    }

    @Test
    void testSecureVectorSearch_success() {
        when(embeddingModel.embed("milvus query")).thenReturn(new float[]{0.1f, 0.2f});

        SearchResp.SearchResult hit1 = SearchResp.SearchResult.builder()
                .id("chunk-1")
                .score(0.92f)
                .entity(Map.of(
                        MilvusConstants.FIELD_ID, "chunk-1",
                        MilvusConstants.FIELD_URI, "file:///milvus.txt",
                        MilvusConstants.FIELD_TEXT, "Milvus Content",
                        MilvusConstants.FIELD_ACL, "engineering"
                ))
                .build();

        SearchResp.SearchResult hit2 = SearchResp.SearchResult.builder()
                .id("chunk-2")
                .score(0.95f)
                .entity(Map.of(
                        MilvusConstants.FIELD_ID, "chunk-2",
                        MilvusConstants.FIELD_URI, "file:///secret.txt",
                        MilvusConstants.FIELD_TEXT, "Secret Content",
                        MilvusConstants.FIELD_ACL, "executive"
                ))
                .build();

        SearchResp searchResp = mock(SearchResp.class);
        when(searchResp.getSearchResults()).thenReturn(List.of(List.of(hit1, hit2)));
        when(milvusClient.search(any(SearchReq.class))).thenReturn(searchResp);

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch(
                "milvus query", "user@enterprise.com", List.of("engineering"), 5, 0.0, null
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("chunk-1");
        assertThat(results.get(0).acl()).isEqualTo("engineering");
    }

    @Test
    void testGetDocumentContent_success() {
        QueryResp.QueryResult qr = QueryResp.QueryResult.builder()
                .entity(Map.of(
                        MilvusConstants.FIELD_ID, "chunk-1",
                        MilvusConstants.FIELD_URI, "file:///milvus.txt",
                        MilvusConstants.FIELD_TEXT, "Milvus Content",
                        MilvusConstants.FIELD_ACL, "public"
                ))
                .build();

        QueryResp queryResp = mock(QueryResp.class);
        when(queryResp.getQueryResults()).thenReturn(List.of(qr));
        when(milvusClient.query(any(QueryReq.class))).thenReturn(queryResp);

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///milvus.txt", "user", List.of(), null);
        assertThat(result.id()).isEqualTo("chunk-1");
        assertThat(result.uri()).isEqualTo("file:///milvus.txt");
    }

    @Test
    void testGetDocumentContent_notFoundThrows() {
        QueryResp queryResp = mock(QueryResp.class);
        when(queryResp.getQueryResults()).thenReturn(Collections.emptyList());
        when(milvusClient.query(any(QueryReq.class))).thenReturn(queryResp);

        assertThatThrownBy(() -> backend.getDocumentContent("file:///missing.txt", "user", List.of(), null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testListAccessibleSources() {
        QueryResp.QueryResult qr = QueryResp.QueryResult.builder()
                .entity(Map.of(
                        MilvusConstants.FIELD_ID, "chunk-1",
                        MilvusConstants.FIELD_URI, "file:///milvus.txt",
                        MilvusConstants.FIELD_ACL, "public"
                ))
                .build();

        QueryResp queryResp = mock(QueryResp.class);
        when(queryResp.getQueryResults()).thenReturn(List.of(qr));
        when(milvusClient.query(any(QueryReq.class))).thenReturn(queryResp);

        List<Map<String, Object>> sources = backend.listAccessibleSources("user", List.of(), null);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("id")).isEqualTo("chunk-1");
    }

    @Test
    void aCollectionWithDynamicFieldsIsAskedForTheDocumentsFirstPositions() {
        // New collections keep each chunk's document id, position and the document's chunk count.
        dynamicFields(true);
        QueryResp queryResp = mock(QueryResp.class);
        when(queryResp.getQueryResults()).thenReturn(List.of(positionedChunk(1, "second"), positionedChunk(0, "first")));
        when(milvusClient.query(any(QueryReq.class))).thenReturn(queryResp);

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///milvus.txt", "user", List.of(), null);

        assertThat(result.content()).isEqualTo("first\nsecond");
        assertThat(result.id()).isEqualTo("doc-7");
        assertThat(result.totalChunks()).isEqualTo(2);
        assertThat(result.complete()).isTrue();
        ArgumentCaptor<QueryReq> request = ArgumentCaptor.forClass(QueryReq.class);
        verify(milvusClient).query(request.capture());
        assertThat(request.getValue().getFilter())
                .isEqualTo("uri == \"file:///milvus.txt\" and chunk_index < " + GatedMcpSearchBackend.DEFAULT_DOCUMENT_MAX_CHUNKS);
        assertThat(request.getValue().getOutputFields())
                .contains(MilvusConstants.FIELD_DOCUMENT_ID, DocumentChunks.CHUNK_INDEX, DocumentChunks.TOTAL_CHUNKS);
        assertThat(request.getValue().getLimit()).isEqualTo(GatedMcpSearchBackend.DEFAULT_DOCUMENT_MAX_CHUNKS + 1);
    }

    @Test
    void chunksStoredWithoutAPositionAreFoundByThePlainLookup() {
        dynamicFields(true);
        QueryResp none = mock(QueryResp.class);
        when(none.getQueryResults()).thenReturn(List.of());
        QueryResp legacy = mock(QueryResp.class);
        when(legacy.getQueryResults()).thenReturn(List.of(milvusChunk(1, "second"), milvusChunk(0, "first")));
        when(milvusClient.query(any(QueryReq.class))).thenReturn(none, legacy);

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///milvus.txt", "user", List.of(), null);

        assertThat(result.content()).isEqualTo("first\nsecond");
        ArgumentCaptor<QueryReq> requests = ArgumentCaptor.forClass(QueryReq.class);
        verify(milvusClient, times(2)).query(requests.capture());
        assertThat(requests.getAllValues().get(1).getFilter()).isEqualTo("uri == \"file:///milvus.txt\"");
    }

    @Test
    void aCollectionWithoutDynamicFieldsIsOrderedByThePipelinesChunkIdsButNotReportedComplete() {
        // Collections created before dynamic fields were enabled keep only the chunk id, URI and text, and asking
        // them for other fields fails.
        dynamicFields(false);
        QueryResp queryResp = mock(QueryResp.class);
        when(queryResp.getQueryResults()).thenReturn(List.of(milvusChunk(1, "second"), milvusChunk(0, "first")));
        when(milvusClient.query(any(QueryReq.class))).thenReturn(queryResp);

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///milvus.txt", "user", List.of(), null);

        assertThat(result.content()).isEqualTo("first\nsecond");
        assertThat(result.id()).isEqualTo("file:///milvus.txt");
        // Nothing records how many chunks the document has.
        assertThat(result.complete()).isFalse();
        ArgumentCaptor<QueryReq> request = ArgumentCaptor.forClass(QueryReq.class);
        verify(milvusClient).query(request.capture());
        // Its first chunks are asked for by the ids the pipeline gives them, the URI standing for the document id.
        assertThat(request.getValue().getFilter())
                .startsWith("uri == \"file:///milvus.txt\" and id in [\"" + DocumentChunksTest.pipelineId("file:///milvus.txt", 0) + "\"")
                .contains(DocumentChunksTest.pipelineId("file:///milvus.txt", GatedMcpSearchBackend.DEFAULT_DOCUMENT_MAX_CHUNKS - 1));
        assertThat(request.getValue().getOutputFields()).doesNotContain(DocumentChunks.CHUNK_INDEX);
    }

    private void dynamicFields(boolean enabled) {
        DescribeCollectionResp description = mock(DescribeCollectionResp.class);
        when(description.getEnableDynamicField()).thenReturn(enabled);
        when(milvusClient.describeCollection(any(DescribeCollectionReq.class))).thenReturn(description);
    }

    private static QueryResp.QueryResult positionedChunk(int index, String text) {
        return QueryResp.QueryResult.builder()
                .entity(Map.of(
                        MilvusConstants.FIELD_ID, "chunk-" + index,
                        MilvusConstants.FIELD_DOCUMENT_ID, "doc-7",
                        MilvusConstants.FIELD_URI, "file:///milvus.txt",
                        MilvusConstants.FIELD_TEXT, text,
                        MilvusConstants.FIELD_ACL, "public",
                        DocumentChunks.CHUNK_INDEX, (long) index,
                        DocumentChunks.TOTAL_CHUNKS, 2L))
                .build();
    }

    private static QueryResp.QueryResult milvusChunk(int index, String text) {
        return QueryResp.QueryResult.builder()
                .entity(Map.of(
                        MilvusConstants.FIELD_ID, DocumentChunksTest.pipelineId("file:///milvus.txt", index),
                        MilvusConstants.FIELD_URI, "file:///milvus.txt",
                        MilvusConstants.FIELD_TEXT, text,
                        MilvusConstants.FIELD_ACL, "public"))
                .build();
    }

    @Test
    void aCollectionWithoutDynamicFieldsWhoseIdsAreNotThePipelinesGetsThePlainLookup() {
        // A document whose id is not its URI (CMIS): the ids derived from the URI find nothing.
        dynamicFields(false);
        QueryResp none = mock(QueryResp.class);
        when(none.getQueryResults()).thenReturn(List.of());
        QueryResp plain = mock(QueryResp.class);
        when(plain.getQueryResults()).thenReturn(List.of(milvusChunk(0, "first")));
        when(milvusClient.query(any(QueryReq.class))).thenReturn(none, plain);

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///milvus.txt", "user", List.of(), null);

        assertThat(result.content()).isEqualTo("first");
        ArgumentCaptor<QueryReq> requests = ArgumentCaptor.forClass(QueryReq.class);
        verify(milvusClient, times(2)).query(requests.capture());
        assertThat(requests.getAllValues().get(1).getFilter()).isEqualTo("uri == \"file:///milvus.txt\"");
    }
}
