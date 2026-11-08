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
package org.opencrawling.elasticsearch.messaging;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.util.ObjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.elasticsearch.ElasticsearchConstants;
import org.opencrawling.elasticsearch.config.ElasticsearchOutputProperties;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class ElasticsearchStoreWriterConsumerTest {

    private ElasticsearchClient client;
    private ElasticsearchStoreWriterConsumer consumer;

    @BeforeEach
    void setUp() {
        client = mock(ElasticsearchClient.class);
        consumer = new ElasticsearchStoreWriterConsumer(client, new ElasticsearchOutputProperties(
                "http://localhost:9200", "", "", "", "enterprise_kb", 4,
                ElasticsearchOutputProperties.Similarity.COSINE, ElasticsearchOutputProperties.IndexType.HNSW,
                500, false, 8, null));
    }

    @Test
    void upsertIndexesChunkWithFlattenedSecurity() throws Exception {
        Map<String, Object> security = Map.of(
                "inheritanceEnabled", false,
                "permissions", List.of(
                        Map.of("identity", "alice", "identityType", "user", "displayName", "Alice", "access", "read"),
                        Map.of("identity", "mallory", "identityType", "user", "displayName", "Mallory", "access", "deny")));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("uri", "file:///kb/a.txt");
        metadata.put("acl", "alice");
        metadata.put("lastModified", "2026-01-02T03:04:05Z");
        metadata.put("security", security);
        metadata.put("title", "Doc A");
        metadata.put("text", "metadata must not overwrite the chunk text");

        consumer.consume(new DocumentEmbeddedMessage("doc-a", "doc-a_0", "chunk text",
                metadata, new float[]{0.1f, 0.2f, 0.3f, 0.4f}, DocumentAction.UPSERT));

        IndexRequest<Map<String, Object>> request = capturedIndexRequest();
        assertThat(request.index()).isEqualTo("enterprise_kb");
        assertThat(request.id()).isEqualTo("doc-a_0");
        Map<String, Object> source = request.document();
        assertThat(source)
                .containsEntry(ElasticsearchConstants.FIELD_ID, "doc-a_0")
                .containsEntry(ElasticsearchConstants.FIELD_DOCUMENT_ID, "doc-a")
                .containsEntry(ElasticsearchConstants.FIELD_TEXT, "chunk text")
                .containsEntry(ElasticsearchConstants.FIELD_URI, "file:///kb/a.txt")
                .containsEntry(ElasticsearchConstants.FIELD_ACL, "alice")
                .containsEntry(ElasticsearchConstants.FIELD_LAST_MODIFIED, "2026-01-02T03:04:05Z")
                .containsEntry(ElasticsearchConstants.FIELD_SECURITY_INHERITANCE, false)
                .containsEntry("title", "Doc A")
                .doesNotContainKey("security");
        assertThat((List<String>) source.get(ElasticsearchConstants.FIELD_SECURITY_ALLOWED_READ)).contains("alice");
        assertThat((List<String>) source.get(ElasticsearchConstants.FIELD_SECURITY_DENIED_READ)).contains("mallory");
        assertThat((List<String>) source.get(ElasticsearchConstants.FIELD_SECURITY_READ_KEYS)).contains("u:alice");
    }

    @Test
    void upsertOmitsBlankLastModified() throws Exception {
        consumer.consume(new DocumentEmbeddedMessage("doc-b", "doc-b_0", "text",
                Map.of("uri", "file:///b.txt", "lastModified", ""), new float[]{1f, 0f, 0f, 0f}, DocumentAction.UPSERT));

        assertThat(capturedIndexRequest().document()).doesNotContainKey(ElasticsearchConstants.FIELD_LAST_MODIFIED);
    }

    @Test
    void deleteTombstonePurgesByDocumentIdOrChunkId() throws Exception {
        consumer.consume(new DocumentEmbeddedMessage("doc-c", "doc-c_3", "", Map.of(), new float[0], DocumentAction.DELETE));

        ArgumentCaptor<Function> captor = ArgumentCaptor.forClass(Function.class);
        verify(client).deleteByQuery(captor.capture());
        verify(client, never()).index(any(Function.class));

        Function<DeleteByQueryRequest.Builder, ObjectBuilder<DeleteByQueryRequest>> fn = captor.getValue();
        DeleteByQueryRequest request = fn.apply(new DeleteByQueryRequest.Builder()).build();
        assertThat(request.index()).containsExactly("enterprise_kb");
        List<Query> should = request.query().bool().should();
        assertThat(should).extracting(q -> q.term().field() + "=" + q.term().value().stringValue())
                .containsExactlyInAnyOrder("documentId=doc-c", "id=doc-c", "id=doc-c_3");
        assertThat(request.query().bool().minimumShouldMatch()).isEqualTo("1");
    }

    @Test
    void deleteTombstoneWithoutDocumentIdFallsBackToChunkId() throws Exception {
        consumer.consume(new DocumentEmbeddedMessage(null, "chunk-only", "", Map.of(), new float[0], DocumentAction.DELETE));

        ArgumentCaptor<Function> captor = ArgumentCaptor.forClass(Function.class);
        verify(client).deleteByQuery(captor.capture());
        Function<DeleteByQueryRequest.Builder, ObjectBuilder<DeleteByQueryRequest>> fn = captor.getValue();
        assertThat(fn.apply(new DeleteByQueryRequest.Builder()).build().query().bool().should())
                .extracting(q -> q.term().value().stringValue())
                .containsOnly("chunk-only");
    }

    @Test
    void firstChunkOfARecrawlPurgesChunksPastTheNewLength() throws Exception {
        consumer.consume(new DocumentEmbeddedMessage("doc-s", "doc-s_0", "text",
                Map.of("uri", "file:///s.txt", "chunk_index", 0, "total_chunks", 3), new float[]{1f, 0f, 0f, 0f},
                DocumentAction.UPSERT));

        ArgumentCaptor<Function> captor = ArgumentCaptor.forClass(Function.class);
        verify(client).deleteByQuery(captor.capture());
        Function<DeleteByQueryRequest.Builder, ObjectBuilder<DeleteByQueryRequest>> fn = captor.getValue();
        DeleteByQueryRequest request = fn.apply(new DeleteByQueryRequest.Builder()).build();
        List<Query> filters = request.query().bool().filter();
        assertThat(filters).hasSize(2);
        assertThat(filters.get(0).term().field()).isEqualTo(ElasticsearchConstants.FIELD_DOCUMENT_ID);
        assertThat(filters.get(0).term().value().stringValue()).isEqualTo("doc-s");
        assertThat(filters.get(1).range().number().field()).isEqualTo("chunk_index");
        assertThat(filters.get(1).range().number().gte()).isEqualTo(3.0);
    }

    @Test
    void laterChunksAndChunksWithoutPositionsDoNotPurge() throws Exception {
        consumer.consume(new DocumentEmbeddedMessage("doc-s", "doc-s_1", "text",
                Map.of("chunk_index", 1, "total_chunks", 3), new float[]{1f, 0f, 0f, 0f}, DocumentAction.UPSERT));
        consumer.consume(new DocumentEmbeddedMessage("doc-s", "doc-s_x", "text",
                Map.of("uri", "file:///s.txt"), new float[]{1f, 0f, 0f, 0f}, DocumentAction.UPSERT));
        consumer.consume(new DocumentEmbeddedMessage(null, "doc-s_0", "text",
                Map.of("chunk_index", 0, "total_chunks", 1), new float[]{1f, 0f, 0f, 0f}, DocumentAction.UPSERT));

        verify(client, never()).deleteByQuery(any(Function.class));
    }

    @Test
    void writeFailureIsRethrownSoKafkaCanRetry() throws Exception {
        when(client.index(any(Function.class))).thenThrow(new IOException("connection refused"));

        assertThatThrownBy(() -> consumer.consume(new DocumentEmbeddedMessage("doc-d", "doc-d_0", "text",
                Map.of(), new float[]{1f, 0f, 0f, 0f}, DocumentAction.UPSERT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("doc-d_0")
                .hasRootCauseMessage("connection refused");
    }

    private IndexRequest<Map<String, Object>> capturedIndexRequest() throws IOException {
        ArgumentCaptor<Function> captor = ArgumentCaptor.forClass(Function.class);
        verify(client).index(captor.capture());
        Function<IndexRequest.Builder<Map<String, Object>>, ObjectBuilder<IndexRequest<Map<String, Object>>>> fn = captor.getValue();
        return fn.apply(new IndexRequest.Builder<>()).build();
    }
}
