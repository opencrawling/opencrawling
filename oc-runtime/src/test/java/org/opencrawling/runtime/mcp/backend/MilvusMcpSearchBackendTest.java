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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
}
