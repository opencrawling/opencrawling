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
package org.opencrawling.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import co.elastic.clients.util.ObjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.elasticsearch.config.ElasticsearchOutputProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class ElasticsearchOutputConnectorTest {

    private ElasticsearchClient client;
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(ElasticsearchClient.class);
        embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(Document.class))).thenReturn(new float[]{0.1f, 0.2f, 0.3f, 0.4f});
        when(client.bulk(any(BulkRequest.class))).thenReturn(okResponse());
    }

    @Test
    void getName() {
        assertThat(connector(500).getName()).isEqualTo("ElasticsearchOutputConnector");
    }

    @Test
    void sendIndexesChunkWithAclFieldsAndVector() throws Exception {
        connector(500).send(document("doc-123", "A short document about vector search.")).block();

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(captor.capture());
        List<BulkOperation> ops = captor.getValue().operations();
        assertThat(ops).hasSize(1);

        BulkOperation op = ops.get(0);
        assertThat(op.isIndex()).isTrue();
        assertThat(op.index().index()).isEqualTo("test_kb");
        assertThat(op.index().id()).isEqualTo("doc-123_0");

        Map<String, Object> source = (Map<String, Object>) op.index().document();
        assertThat(source)
                .containsEntry(ElasticsearchConstants.FIELD_ID, "doc-123_0")
                .containsEntry(ElasticsearchConstants.FIELD_DOCUMENT_ID, "doc-123")
                .containsEntry(ElasticsearchConstants.FIELD_URI, "file://tmp/doc-123.txt")
                .containsEntry(ElasticsearchConstants.FIELD_ACL, "acl-123")
                .containsEntry(ElasticsearchConstants.FIELD_TEXT, "A short document about vector search.")
                .containsEntry(ElasticsearchConstants.FIELD_SECURITY_INHERITANCE, true)
                .containsKey(ElasticsearchConstants.FIELD_LAST_MODIFIED)
                .containsEntry("title", List.of("Test Title"));
        assertThat((List<String>) source.get(ElasticsearchConstants.FIELD_SECURITY_ALLOWED_READ))
                .contains("user1", "group1")
                .doesNotContain("baduser");
        assertThat((List<String>) source.get(ElasticsearchConstants.FIELD_SECURITY_DENIED_READ)).contains("baduser");
        assertThat((List<String>) source.get(ElasticsearchConstants.FIELD_SECURITY_READ_KEYS)).contains("u:user1", "g:group1");
        assertThat((float[]) source.get(ElasticsearchConstants.FIELD_EMBEDDINGS)).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
    }

    @Test
    void sendPurgesExistingChunksOfTheDocumentBeforeIndexing() throws Exception {
        connector(500).send(document("doc-7", "Re-crawled content.")).block();

        InOrder order = inOrder(client);
        ArgumentCaptor<Function> deleteCaptor = ArgumentCaptor.forClass(Function.class);
        order.verify(client).deleteByQuery(deleteCaptor.capture());
        order.verify(client).bulk(any(BulkRequest.class));

        DeleteByQueryRequest request = buildDeleteByQuery(deleteCaptor.getValue());
        assertThat(request.index()).containsExactly("test_kb");
        assertThat(request.query().term().field()).isEqualTo(ElasticsearchConstants.FIELD_DOCUMENT_ID);
        assertThat(request.query().term().value().stringValue()).isEqualTo("doc-7");
    }

    @Test
    void sendSplitsLargeDocumentsIntoBulkBatches() throws Exception {
        String longText = IntStream.range(0, 4000).mapToObj(i -> "token" + i).collect(Collectors.joining(" "));

        connector(2).send(document("doc-big", longText)).block();

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client, atLeast(2)).bulk(captor.capture());
        List<BulkRequest> requests = captor.getAllValues();
        assertThat(requests).allSatisfy(r -> assertThat(r.operations()).hasSizeBetween(1, 2));

        List<String> ids = requests.stream().flatMap(r -> r.operations().stream()).map(o -> o.index().id()).toList();
        assertThat(ids).doesNotHaveDuplicates().first().isEqualTo("doc-big_0");
        assertThat(ids).hasSizeGreaterThan(2);
    }

    @Test
    void sendFailsWhenBulkReportsItemErrors() throws Exception {
        BulkResponse failed = BulkResponse.of(b -> b
                .errors(true)
                .took(1)
                .items(BulkResponseItem.of(i -> i
                        .operationType(OperationType.Index)
                        .index("test_kb")
                        .id("doc-err_0")
                        .status(400)
                        .error(e -> e.type("document_parsing_exception").reason("dims mismatch")))));
        when(client.bulk(any(BulkRequest.class))).thenReturn(failed);

        assertThatThrownBy(() -> connector(500).send(document("doc-err", "Some text.")).block())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("doc-err")
                .rootCause()
                .hasMessageContaining("document_parsing_exception")
                .hasMessageContaining("dims mismatch");
    }

    @Test
    void sendSkipsEmptyContent() throws Exception {
        connector(500).send(document("doc-empty", "")).block();

        verify(client, never()).bulk(any(BulkRequest.class));
        verify(client, never()).deleteByQuery(any(Function.class));
    }

    @Test
    void sendDeleteTombstonePurgesWithoutReadingContent() throws Exception {
        RepositoryDocument tombstone = new RepositoryDocument("doc-gone", "file://tmp/gone.txt", null, Map.of(),
                "", SecurityConfig.createPublic(), Instant.now(), DocumentAction.DELETE);

        connector(500).send(tombstone).block();

        ArgumentCaptor<Function> deleteCaptor = ArgumentCaptor.forClass(Function.class);
        verify(client).deleteByQuery(deleteCaptor.capture());
        assertThat(buildDeleteByQuery(deleteCaptor.getValue()).query().term().value().stringValue()).isEqualTo("doc-gone");
        verify(client, never()).bulk(any(BulkRequest.class));
    }

    @Test
    void sendWithoutEmbeddingModelUsesPlaceholderVectorOfConfiguredDimensions() throws Exception {
        ElasticsearchOutputConnector noModel = new ElasticsearchOutputConnector(client, properties(500), null);

        noModel.send(document("doc-nomodel", "Text without a model.")).block();

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(captor.capture());
        Map<String, Object> source = (Map<String, Object>) captor.getValue().operations().get(0).index().document();
        float[] vector = (float[]) source.get(ElasticsearchConstants.FIELD_EMBEDDINGS);
        assertThat(vector).hasSize(4);
        assertThat(vector[0]).isEqualTo(1.0f);
    }

    @Test
    void firstErrorDescribesTheFailedItem() {
        BulkResponse response = BulkResponse.of(b -> b.errors(true).took(1).items(
                BulkResponseItem.of(i -> i.operationType(OperationType.Index).index("x").id("ok").status(201)),
                BulkResponseItem.of(i -> i.operationType(OperationType.Index).index("x").id("bad").status(400)
                        .error(e -> e.type("t").reason("r")))));

        assertThat(ElasticsearchOutputConnector.firstError(response)).isEqualTo("id=bad type=t reason=r");
    }

    private ElasticsearchOutputConnector connector(int batchSize) {
        return new ElasticsearchOutputConnector(client, properties(batchSize), embeddingModel);
    }

    private static ElasticsearchOutputProperties properties(int batchSize) {
        return new ElasticsearchOutputProperties("http://localhost:9200", "", "", "", "test_kb", 4,
                ElasticsearchOutputProperties.Similarity.COSINE, ElasticsearchOutputProperties.IndexType.HNSW,
                batchSize, false, 8, null);
    }

    private static RepositoryDocument document(String id, String text) {
        SecurityConfig security = new SecurityConfig(true, List.of(
                new PermissionRule("user1", "user", "User One", "read"),
                new PermissionRule("group1", "group", "Group One", "read"),
                new PermissionRule("baduser", "user", "Bad User", "deny")));
        return new RepositoryDocument(id, "file://tmp/" + id + ".txt",
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                Map.of("mimeType", List.of("text/plain"), "title", List.of("Test Title")),
                "acl-123", security, Instant.parse("2026-01-02T03:04:05Z"));
    }

    private static BulkResponse okResponse() {
        return BulkResponse.of(b -> b.errors(false).took(1).items(List.of()));
    }

    private static DeleteByQueryRequest buildDeleteByQuery(Function fn) {
        Function<DeleteByQueryRequest.Builder, ObjectBuilder<DeleteByQueryRequest>> typed = fn;
        return typed.apply(new DeleteByQueryRequest.Builder()).build();
    }
}
