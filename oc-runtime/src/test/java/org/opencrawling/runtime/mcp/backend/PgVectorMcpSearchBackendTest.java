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
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class PgVectorMcpSearchBackendTest {

    private VectorStore vectorStore;
    private EmbeddingModel embeddingModel;
    private PgVectorMcpSearchBackend backend;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        embeddingModel = mock(EmbeddingModel.class);
        backend = new PgVectorMcpSearchBackend(
                vectorStore, vectorStore, vectorStore, vectorStore,
                embeddingModel, null
        );
    }

    @Test
    void testBackendType() {
        assertThat(backend.backendType()).isEqualTo("pgvector");
    }

    @Test
    void testSecureVectorSearch_embedsQueryAndFiltersAcl() {
        when(embeddingModel.embed("cloud architecture")).thenReturn(new float[]{0.1f, 0.2f});

        List<Document> docs = List.of(
                new Document("1", "Architecture Guide", Map.of("uri", "file:///arch.txt", "acl", "engineering")),
                new Document("2", "Secret Document", Map.of("uri", "file:///secret.txt", "acl", "executive"))
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(docs);

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch(
                "cloud architecture", "john@enterprise.com", List.of("engineering"), 5, 0.0, null
        );

        verify(embeddingModel).embed("cloud architecture");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("1");
        assertThat(results.get(0).acl()).isEqualTo("engineering");
    }

    @Test
    void testSecureVectorSearch_missingAclShowsEmptyString() {
        when(embeddingModel.embed("cloud architecture")).thenReturn(new float[]{0.1f, 0.2f});

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("uri", "file:///arch.txt");
        metadata.put("security_allowed_read", List.of("engineering"));

        List<Document> docs = List.of(
                new Document("1", "Architecture Guide", metadata)
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(docs);

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch(
                "cloud architecture", "john@enterprise.com", List.of("engineering"), 5, 0.0, null
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).acl()).isEqualTo("");
    }

    @Test
    void testGetDocumentContent_success() {
        Document doc = new Document("1", "Architecture Guide", Map.of("uri", "file:///arch.txt", "acl", "public"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///arch.txt", "john", List.of(), null);
        assertThat(result.id()).isEqualTo("1");
        assertThat(result.uri()).isEqualTo("file:///arch.txt");
    }

    @Test
    void testGetDocumentContent_deniedThrows() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> backend.getDocumentContent("file:///secret.txt", "john", List.of(), null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testListAccessibleSources() {
        List<Document> docs = List.of(
                new Document("1", "Public Doc", Map.of("uri", "file:///public.txt", "acl", "public")),
                new Document("2", "Private Doc", Map.of("uri", "file:///private.txt", "acl", "private"))
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(docs);

        List<Map<String, Object>> sources = backend.listAccessibleSources("john", List.of(), null);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("uri")).isEqualTo("file:///public.txt");
    }

    @Test
    void testSecureVectorSearch_multiReaderDocumentFoundForEveryReader() {
        when(embeddingModel.embed("budget plan")).thenReturn(new float[]{0.1f, 0.2f});

        Document multiReaderDoc = new Document("doc-123", "Shared budget plan for engineering and finance.", Map.of(
                "uri", "http://repro.example/two-readers.html",
                "acl", "engineering,finance",
                "security_allowed_read", List.of("engineering", "finance")
        ));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(multiReaderDoc));

        // 1. Engineering caller finds the document
        List<McpVectorServer.DocumentSearchResult> engResults = backend.secureVectorSearch(
                "budget plan", "alice@enterprise.com", List.of("engineering"), 5, 0.0, null
        );
        assertThat(engResults).hasSize(1);
        assertThat(engResults.get(0).id()).isEqualTo("doc-123");

        // 2. Finance caller ALSO finds the document (issue #163 fix)
        List<McpVectorServer.DocumentSearchResult> finResults = backend.secureVectorSearch(
                "budget plan", "bob@enterprise.com", List.of("finance"), 5, 0.0, null
        );
        assertThat(finResults).hasSize(1);
        assertThat(finResults.get(0).id()).isEqualTo("doc-123");

        // 3. HR caller does NOT get the document
        List<McpVectorServer.DocumentSearchResult> hrResults = backend.secureVectorSearch(
                "budget plan", "carol@enterprise.com", List.of("hr"), 5, 0.0, null
        );
        assertThat(hrResults).isEmpty();
    }

    @Test
    void testSecureVectorSearch_filterExpressionIncludesSecurityAllowedReadAndAcl() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        backend.secureVectorSearch("query", "user1", List.of("finance", "engineering"), 5, 0.0, null);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());

        SearchRequest req = captor.getValue();
        assertThat(req.getTopK()).isEqualTo(5);
        assertThat(req.getFilterExpression()).isNotNull();
        String exprStr = req.getFilterExpression().toString();
        assertThat(exprStr).contains("security_allowed_read");
        assertThat(exprStr).contains("acl");
        assertThat(exprStr).contains("finance");
        assertThat(exprStr).contains("engineering");
        assertThat(exprStr).contains("user1");
        assertThat(exprStr).contains("public");
    }

    @Test
    void theStoredAclFilterAsksForTheCallersReadKeysAndItsPrefixedForms() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        backend.secureVectorSearch("query", "User:Alice", List.of("GROUP:Finance"), 5, 0.0, null);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        String jsonPath = new org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter()
                .convertExpression(captor.getValue().getFilterExpression());

        assertThat(jsonPath)
                .contains("$.\"security_read_keys\" == \"public\"")
                .contains("$.\"security_read_keys\" == \"u:alice\"")
                .contains("$.\"security_read_keys\" == \"g:finance\"")
                // chunks written before read keys existed
                .contains("$.\"security_allowed_read\" == \"user:Alice\"")
                .contains("$.\"security_allowed_read\" == \"Alice\"")
                .contains("$.\"security_allowed_read\" == \"group:Finance\"")
                .contains("$.\"security_allowed_read\" == \"Finance\"")
                .contains("$.\"acl\" == \"public\"");
    }

    @Test
    void testEmptyPermissionsDocumentExcludedFromAllToolsForOutsider() {
        // A document pushed with "permissions": [] has:
        // acl="", security_allowed_read=[], and security.permissions=[]
        String docUri = "http://repro.example/nobody-access.html";
        Map<String, Object> emptyPermsMeta = Map.of(
                "uri", docUri,
                "acl", "",
                "security_allowed_read", List.of(),
                "security", Map.of("permissions", List.of())
        );
        Document emptyPermsDoc = new Document("doc-empty-perms", "Confidential text for nobody.", emptyPermsMeta);

        // 1. secureVectorSearch as outsider -> excluded by McpAclEvaluator even if returned by similaritySearch
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(emptyPermsDoc));

        List<McpVectorServer.DocumentSearchResult> searchResults = backend.secureVectorSearch(
                "confidential", "outsider", List.of(), 5, 0.0, null
        );
        assertThat(searchResults).isEmpty();

        // 2. getDocumentContent as outsider -> throws NoSuchElementException (access denied)
        assertThatThrownBy(() -> backend.getDocumentContent(docUri, "outsider", List.of(), null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Document not found or access denied.");

        // 3. listAccessibleSources as outsider -> excluded from results
        List<Map<String, Object>> sources = backend.listAccessibleSources("outsider", List.of(), null);
        assertThat(sources).isEmpty();
    }

    @Test
    void testGetDocumentContent_byFlowableScopeId_fallback() {
        Document flowableDoc = new Document("5001", "Process Instance details", Map.of(
                "uri", "flowable://historic-process-instances/5001",
                "flowable_scope_id", List.of("5001"),
                "flowable_scope_type", List.of("bpmn"),
                "acl", "public"
        ));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(flowableDoc));

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("5001", "john", List.of(), null);
        assertThat(result.id()).isEqualTo("5001");
        assertThat(result.uri()).isEqualTo("flowable://historic-process-instances/5001");
    }

    @Test
    void testListAccessibleSources_surfacesScopeMetadata() {
        Document flowableDoc = new Document("5001", "Process Instance details", Map.of(
                "uri", "flowable://historic-process-instances/5001",
                "flowable_scope_id", List.of("5001"),
                "flowable_scope_type", List.of("bpmn"),
                "acl", "public"
        ));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(flowableDoc));

        List<Map<String, Object>> sources = backend.listAccessibleSources("john", List.of(), null);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("scope_id")).isEqualTo("5001");
        assertThat(sources.get(0).get("scope_type")).isEqualTo("bpmn");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetDocumentContent_withJdbcAndScopeId() {
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        PgVectorMcpSearchBackend jdbcBackend = new PgVectorMcpSearchBackend(
                vectorStore, vectorStore, vectorStore, vectorStore,
                mockJdbc, embeddingModel, null
        );

        when(mockJdbc.queryForObject(anyString(), eq(Integer.class), eq("vector_store"))).thenReturn(1);

        McpVectorServer.DocumentDetailsResult expectedDoc = new McpVectorServer.DocumentDetailsResult(
                "5001", "flowable://historic-process-instances/5001", "Process Instance Content",
                Map.of("acl", "public", "flowable_scope_id", "5001"), "public"
        );

        when(mockJdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(expectedDoc));

        McpVectorServer.DocumentDetailsResult result = jdbcBackend.getDocumentContent("5001", "john", List.of(), null);
        assertThat(result.id()).isEqualTo("5001");
        assertThat(result.uri()).isEqualTo("flowable://historic-process-instances/5001");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testListAccessibleSources_withJdbcAndScopeColumns() {
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        PgVectorMcpSearchBackend jdbcBackend = new PgVectorMcpSearchBackend(
                vectorStore, vectorStore, vectorStore, vectorStore,
                mockJdbc, embeddingModel, null
        );

        when(mockJdbc.queryForObject(anyString(), eq(Integer.class), eq("vector_store"))).thenReturn(1);

        Map<String, Object> row = new HashMap<>();
        row.put("id", "5001");
        row.put("uri", "flowable://historic-process-instances/5001");
        row.put("documentId", "5001");
        row.put("scope_id", "5001");
        row.put("scope_type", "bpmn");
        row.put("acl", "public");
        row.put("_meta", Map.of("acl", "public"));

        when(mockJdbc.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(row));

        List<Map<String, Object>> sources = jdbcBackend.listAccessibleSources("john", List.of(), null);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("scope_id")).isEqualTo("5001");
        assertThat(sources.get(0).get("scope_type")).isEqualTo("bpmn");
    }

    @Test
    void relationshipModesFetchAWiderUnfilteredWindow() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(Collections.emptyList());
        backend.setAccessGate(new org.opencrawling.runtime.authorization.DocumentAccessGate(
                new org.opencrawling.core.security.RelationshipAuthorizationProvider() {
                    @Override
                    public String name() {
                        return "rel";
                    }

                    @Override
                    public boolean[] canRead(org.opencrawling.core.security.Caller caller,
                                             List<org.opencrawling.core.security.ResourceRef> candidates) {
                        return new boolean[candidates.size()];
                    }
                }, 10, 500, null));

        backend.secureVectorSearch("q", "john", List.of("engineering"), 5, 0.0, null);

        org.mockito.ArgumentCaptor<SearchRequest> request = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getTopK()).isEqualTo(50);
        assertThat(request.getValue().getFilterExpression()).isNull();
    }

    @Test
    void metadataModeKeepsTheStoredAclFilterInTheQuery() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(Collections.emptyList());

        backend.secureVectorSearch("q", "john", List.of("engineering"), 5, 0.0, null);

        org.mockito.ArgumentCaptor<SearchRequest> request = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getTopK()).isEqualTo(5);
        assertThat(request.getValue().getFilterExpression()).isNotNull();
    }

    // --- Which table a call reads: the writer routes each chunk by its embedding's length ---

    private final VectorStore store384 = mock(VectorStore.class);
    private final VectorStore store768 = mock(VectorStore.class);
    private final VectorStore store1024 = mock(VectorStore.class);

    private PgVectorMcpSearchBackend routedBackend(JdbcTemplate jdbc) {
        return new PgVectorMcpSearchBackend(vectorStore, store384, store768, store1024, jdbc, embeddingModel, null);
    }

    @Test
    void withoutDimensionsTheQueryEmbeddingPicksTheTableTheWriterUsed() {
        when(embeddingModel.embed("forecast")).thenReturn(new float[1024]);
        Document doc = new Document("c1", "Finance Q3 forecast", Map.of("uri", "doc-1", "acl", "public"));
        when(store1024.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        List<McpVectorServer.DocumentSearchResult> results =
                routedBackend(null).secureVectorSearch("forecast", "john", List.of(), 5, 0.0, null);

        assertThat(results).extracting(McpVectorServer.DocumentSearchResult::id).containsExactly("c1");
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void a768DimensionModelReadsThe768Table() {
        when(embeddingModel.embed("forecast")).thenReturn(new float[768]);

        routedBackend(null).secureVectorSearch("forecast", "john", List.of(), 5, 0.0, null);

        verify(store768).similaritySearch(any(SearchRequest.class));
        verify(store1024, never()).similaritySearch(any(SearchRequest.class));
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void dimensionsFromTheCallerStillWin() {
        when(embeddingModel.embed("forecast")).thenReturn(new float[1024]);

        routedBackend(null).secureVectorSearch("forecast", "john", List.of(), 5, 0.0, 384);

        verify(store384).similaritySearch(any(SearchRequest.class));
        verify(store1024, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutDimensionsDocumentContentIsFoundInWhicheverTableHoldsIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        McpVectorServer.DocumentDetailsResult doc = new McpVectorServer.DocumentDetailsResult(
                "c1", "doc-1", "Finance Q3 forecast", Map.of("uri", "doc-1", "acl", "public"), "public");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM vector_store_1024 ")) {
                throw new org.springframework.jdbc.BadSqlGrammarException("query", sql,
                        new java.sql.SQLException("relation \"vector_store_1024\" does not exist"));
            }
            return sql.contains("FROM vector_store_768 ") ? List.of(doc) : List.of();
        });

        McpVectorServer.DocumentDetailsResult result = routedBackend(jdbc).getDocumentContent("doc-1", "john", List.of(), null);

        assertThat(result.id()).isEqualTo("c1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyTablesAreSkippedUntilTheOneHoldingTheDocument() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        McpVectorServer.DocumentDetailsResult doc = new McpVectorServer.DocumentDetailsResult(
                "c3", "doc-3", "Small model chunk", Map.of("uri", "doc-3", "acl", "public"), "public");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).contains("FROM vector_store_384 ") ? List.of(doc) : List.of());

        assertThat(routedBackend(jdbc).getDocumentContent("doc-3", "john", List.of(), null).id()).isEqualTo("c3");
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentContentIsStillHiddenWhenNoTableHasAReadableCopy() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        McpVectorServer.DocumentDetailsResult secret = new McpVectorServer.DocumentDetailsResult(
                "c2", "doc-2", "Board pack", Map.of("uri", "doc-2", "acl", "executive"), "executive");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(secret));

        assertThatThrownBy(() -> routedBackend(jdbc).getDocumentContent("doc-2", "john", List.of(), null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMissingTableIsAnErrorWhenTheCallerAskedForIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenThrow(
                new org.springframework.jdbc.BadSqlGrammarException("query", "SELECT", new java.sql.SQLException("missing")));

        assertThatThrownBy(() -> routedBackend(jdbc).getDocumentContent("doc-1", "john", List.of(), 1024))
                .isInstanceOf(RuntimeException.class).isNotInstanceOf(NoSuchElementException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutDimensionsAccessibleSourcesComeFromEveryTable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM vector_store_768 ")) {
                throw new org.springframework.jdbc.BadSqlGrammarException("query", sql, new java.sql.SQLException("missing"));
            }
            String table = sql.contains("FROM vector_store_1024 ") ? "big" : sql.contains("FROM vector_store_384 ") ? "small" : null;
            if (table == null) {
                return new ArrayList<>();
            }
            Map<String, Object> row = new HashMap<>(Map.of("id", table, "uri", table, "documentId", table));
            row.put("_meta", Map.of("documentId", table, "acl", "public"));
            return new ArrayList<>(List.of(row));
        });

        List<Map<String, Object>> sources = routedBackend(jdbc).listAccessibleSources("john", List.of(), null);

        assertThat(sources).extracting(row -> row.get("id")).containsExactlyInAnyOrder("big", "small");
    }
}
