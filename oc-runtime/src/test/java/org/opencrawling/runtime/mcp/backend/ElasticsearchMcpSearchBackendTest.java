/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.ObjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.security.AuthorizationProvider;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.elasticsearch.ElasticsearchConstants;
import org.opencrawling.elasticsearch.config.ElasticsearchOutputProperties;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class ElasticsearchMcpSearchBackendTest {

    private ElasticsearchClient client;
    private EmbeddingModel embeddingModel;
    private ElasticsearchMcpSearchBackend backend;

    @BeforeEach
    void setUp() {
        client = mock(ElasticsearchClient.class);
        embeddingModel = mock(EmbeddingModel.class);
        backend = new ElasticsearchMcpSearchBackend(client, properties(), embeddingModel, null);
    }

    @Test
    void backendType() {
        assertThat(backend.backendType()).isEqualTo("elasticsearch");
    }

    @Test
    void secureVectorSearchPreFiltersByReadKeysAndKeepsTheGateAuthoritative() throws Exception {
        when(embeddingModel.embed("roadmap")).thenReturn(new float[]{0.1f, 0.2f, 0.3f, 0.4f});
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response(
                hit("chunk-ok", 0.95, source("chunk-ok", "file:///ok.txt", List.of("engineering"), List.of())),
                hit("chunk-denied", 0.93, source("chunk-denied", "file:///denied.txt", List.of("engineering"), List.of("alice"))),
                hit("chunk-low", 0.10, source("chunk-low", "file:///low.txt", List.of("engineering"), List.of()))));

        List<McpVectorServer.DocumentSearchResult> results =
                backend.secureVectorSearch("roadmap", "alice", List.of("engineering"), 5, 0.5, null);

        assertThat(results).extracting(McpVectorServer.DocumentSearchResult::id).containsExactly("chunk-ok");
        assertThat(results.get(0).uri()).isEqualTo("file:///ok.txt");
        assertThat(results.get(0).score()).isEqualTo(0.95);

        SearchRequest request = capturedSearchRequest();
        assertThat(request.index()).containsExactly("kb");
        assertThat(request.source().filter().excludes()).containsExactly(ElasticsearchConstants.FIELD_EMBEDDINGS);
        KnnSearch knn = request.knn().get(0);
        assertThat(knn.field()).isEqualTo(ElasticsearchConstants.FIELD_EMBEDDINGS);
        assertThat(knn.queryVector()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(knn.k()).isEqualTo(20);
        assertThat(knn.numCandidates()).isGreaterThanOrEqualTo(knn.k());
        assertThat(knn.filter()).hasSize(1);
        Query filter = knn.filter().get(0);
        assertThat(filter.terms().field()).isEqualTo(ElasticsearchConstants.FIELD_SECURITY_READ_KEYS);
        assertThat(filter.terms().terms().value()).extracting(v -> v.stringValue())
                .containsExactly("public", "u:alice", "g:engineering");
    }

    @Test
    void secureVectorSearchSkipsThePreFilterWhenTheProviderDecides() throws Exception {
        backend.setAccessGate(new DocumentAccessGate(relationshipProvider(Set.of("doc-2"))));
        when(embeddingModel.embed("q")).thenReturn(new float[]{1f, 0f, 0f, 0f});
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response(
                hit("doc-1_0", 0.9, source("doc-1_0", "file:///1.txt", List.of("public"), List.of())),
                hit("doc-2_0", 0.8, source("doc-2_0", "file:///2.txt", List.of(), List.of()))));

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch("q", "bob", List.of(), 5, 0.0, null);

        assertThat(results).extracting(McpVectorServer.DocumentSearchResult::id).containsExactly("doc-2_0");
        KnnSearch knn = capturedSearchRequest().knn().get(0);
        assertThat(knn.filter()).isEmpty();
        assertThat(knn.k()).as("wider candidate window when the provider may reject stored-ACL matches")
                .isGreaterThan(20);
    }

    @Test
    void secureVectorSearchFallsBackToAPlaceholderVectorOfTheRequestedDimensions() throws Exception {
        ElasticsearchMcpSearchBackend noModel = new ElasticsearchMcpSearchBackend(client, properties(), null, null);
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response());

        assertThat(noModel.secureVectorSearch("q", "alice", List.of(), 3, 0.0, 8)).isEmpty();

        List<Float> vector = capturedSearchRequest().knn().get(0).queryVector();
        assertThat(vector).hasSize(8).first().isEqualTo(1.0f);
    }

    @Test
    void secureVectorSearchWrapsClientFailures() throws Exception {
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenThrow(new java.io.IOException("down"));

        assertThatThrownBy(() -> backend.secureVectorSearch("q", "alice", List.of(), 3, 0.0, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Elasticsearch")
                .hasRootCauseMessage("down");
    }

    @Test
    void getDocumentContentMatchesUriOrDocumentIdUnderTheAclFilter() throws Exception {
        when(client.search(any(Function.class), eq(Map.class))).thenReturn(response(
                hit("doc_0", 1.0, source("doc_0", "file:///doc.txt", List.of("alice"), List.of()))));

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///doc.txt", "alice", List.of(), null);

        assertThat(result.id()).isEqualTo("doc_0");
        assertThat(result.uri()).isEqualTo("file:///doc.txt");
        assertThat(result.content()).isEqualTo("text of doc_0");

        Query query = capturedFunctionSearchRequest().query();
        assertThat(query.bool().filter()).hasSize(1);
        assertThat(query.bool().filter().get(0).terms().field()).isEqualTo(ElasticsearchConstants.FIELD_SECURITY_READ_KEYS);
        assertThat(query.bool().must().get(0).bool().should())
                .extracting(q -> q.term().field() + "=" + q.term().value().stringValue())
                .containsExactlyInAnyOrder("uri=file:///doc.txt", "documentId=file:///doc.txt");
    }

    @Test
    void getDocumentContentThrowsWhenNothingIsReadable() throws Exception {
        when(client.search(any(Function.class), eq(Map.class))).thenReturn(response(
                hit("doc_0", 1.0, source("doc_0", "file:///doc.txt", List.of("finance"), List.of()))));

        assertThatThrownBy(() -> backend.getDocumentContent("file:///doc.txt", "alice", List.of(), null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void listAccessibleSourcesReturnsReadableSummaries() throws Exception {
        when(client.search(any(Function.class), eq(Map.class))).thenReturn(response(
                hit("a_0", 1.0, source("a_0", "file:///a.txt", List.of("alice"), List.of())),
                hit("b_0", 1.0, source("b_0", "file:///b.txt", List.of("finance"), List.of()))));

        List<Map<String, Object>> sources = backend.listAccessibleSources("alice", List.of(), null);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0)).containsEntry("id", "a_0").containsEntry("uri", "file:///a.txt");
        assertThat(capturedFunctionSearchRequest().size()).isEqualTo(ElasticsearchMcpSearchBackend.SOURCES_LIMIT);
    }

    private SearchRequest capturedSearchRequest() throws Exception {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captor.capture(), eq(Map.class));
        return captor.getValue();
    }

    private SearchRequest capturedFunctionSearchRequest() throws Exception {
        ArgumentCaptor<Function> captor = ArgumentCaptor.forClass(Function.class);
        verify(client).search(captor.capture(), eq(Map.class));
        Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> fn = captor.getValue();
        return fn.apply(new SearchRequest.Builder()).build();
    }

    private static ElasticsearchOutputProperties properties() {
        return new ElasticsearchOutputProperties("http://localhost:9200", "", "", "", "kb", 4,
                ElasticsearchOutputProperties.Similarity.COSINE, ElasticsearchOutputProperties.IndexType.HNSW,
                500, false, 8, null);
    }

    private static Map<String, Object> source(String id, String uri, List<String> allowed, List<String> denied) {
        Map<String, Object> source = new HashMap<>();
        source.put(ElasticsearchConstants.FIELD_ID, id);
        source.put(ElasticsearchConstants.FIELD_DOCUMENT_ID, id.contains("_") ? id.substring(0, id.lastIndexOf('_')) : id);
        source.put(ElasticsearchConstants.FIELD_URI, uri);
        source.put(ElasticsearchConstants.FIELD_TEXT, "text of " + id);
        source.put(ElasticsearchConstants.FIELD_ACL, String.join(",", allowed));
        source.put(ElasticsearchConstants.FIELD_SECURITY_ALLOWED_READ, allowed);
        source.put(ElasticsearchConstants.FIELD_SECURITY_DENIED_READ, denied);
        return source;
    }

    private static Hit<Map> hit(String id, double score, Map<String, Object> source) {
        return Hit.of(h -> h.index("kb").id(id).score(score).source(source));
    }

    @SafeVarargs
    private static SearchResponse<Map> response(Hit<Map>... hits) {
        return SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(List.of(hits))));
    }

    private static AuthorizationProvider relationshipProvider(Set<String> readableDocuments) {
        return new AuthorizationProvider() {
            @Override
            public String name() {
                return "relationship-test";
            }

            @Override
            public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
                boolean[] decisions = new boolean[candidates.size()];
                for (int i = 0; i < decisions.length; i++) {
                    decisions[i] = readableDocuments.contains(candidates.get(i).documentId());
                }
                return decisions;
            }

            @Override
            public boolean metadataPrefilterSafe() {
                return false;
            }
        };
    }
}
