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
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.elasticsearch.config.ElasticsearchClientFactory;
import org.opencrawling.elasticsearch.config.ElasticsearchIndexInitializer;
import org.opencrawling.elasticsearch.config.ElasticsearchOutputProperties;
import org.opencrawling.elasticsearch.messaging.ElasticsearchStoreWriterConsumer;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the Elasticsearch output against a real cluster: index bootstrap, direct and Kafka write paths, kNN
 * search with the ACL pre-filter, re-crawl replacement and tombstone deletes. Subclasses pick the server version.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
abstract class AbstractElasticsearchOutputConnectorIT {

    private static final int DIMS = 4;
    private static final float[] VECTOR = {0.1f, 0.2f, 0.3f, 0.4f};

    protected static ElasticsearchContainer newContainer(String version) {
        return new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:" + version)
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
    }

    protected abstract ElasticsearchContainer container();

    private ElasticsearchOutputProperties properties;
    private ElasticsearchClient client;
    private ElasticsearchOutputConnector connector;

    @BeforeEach
    void setUp() throws Exception {
        properties = properties(ElasticsearchOutputProperties.IndexType.HNSW);
        client = ElasticsearchClientFactory.create(properties);
        new ElasticsearchIndexInitializer(client, properties).ensureIndex();
        connector = new ElasticsearchOutputConnector(client, properties, fixedEmbeddingModel());
    }

    @AfterEach
    void tearDown() throws Exception {
        client.indices().delete(d -> d.index(properties.indexName()).ignoreUnavailable(true));
        client.close();
    }

    @Test
    void createsIndexWithDenseVectorAndKeywordAclMapping() throws Exception {
        Map<String, Property> mapping = mappingOf(properties.indexName());

        assertThat(mapping.get(ElasticsearchConstants.FIELD_EMBEDDINGS).isDenseVector()).isTrue();
        assertThat(mapping.get(ElasticsearchConstants.FIELD_EMBEDDINGS).denseVector().dims()).isEqualTo(DIMS);
        assertThat(mapping.get(ElasticsearchConstants.FIELD_SECURITY_READ_KEYS).isKeyword()).isTrue();
        assertThat(mapping.get(ElasticsearchConstants.FIELD_DOCUMENT_ID).isKeyword()).isTrue();
    }

    @Test
    void ensureIndexIsIdempotent() throws Exception {
        new ElasticsearchIndexInitializer(client, properties).ensureIndex();

        assertThat(client.indices().exists(e -> e.index(properties.indexName())).value()).isTrue();
    }

    @Test
    void createsQuantizedInt8HnswIndex() throws Exception {
        ElasticsearchOutputProperties int8 = properties(ElasticsearchOutputProperties.IndexType.INT8_HNSW);
        try {
            new ElasticsearchIndexInitializer(client, int8).ensureIndex();

            Property vector = mappingOf(int8.indexName()).get(ElasticsearchConstants.FIELD_EMBEDDINGS);
            assertThat(vector.denseVector().indexOptions().type().jsonValue()).isEqualTo("int8_hnsw");
        } finally {
            client.indices().delete(d -> d.index(int8.indexName()).ignoreUnavailable(true));
        }
    }

    @Test
    void directSendIsSearchableOnlyByReadersThroughTheAclPreFilter() throws Exception {
        connector.send(document("doc-acl", "Quarterly engineering roadmap.", security(
                rule("alice", "user", "read"),
                rule("engineering", "group", "read"),
                rule("mallory", "user", "deny")))).block();
        refresh();

        assertThat(knn(ElasticsearchAclQueries.readableBy("alice", List.of()))).extracting(Hit::id).containsExactly("doc-acl_0");
        assertThat(knn(ElasticsearchAclQueries.readableBy("bob", List.of("group:Engineering")))).hasSize(1);
        assertThat(knn(ElasticsearchAclQueries.readableBy("bob", List.of("finance")))).isEmpty();
        assertThat(knn(ElasticsearchAclQueries.readableBy("user:ALICE", List.of()))).as("case-insensitive principal").hasSize(1);

        Map<String, Object> source = (Map<String, Object>) knn(null).get(0).source();
        assertThat(source)
                .containsEntry(ElasticsearchConstants.FIELD_DOCUMENT_ID, "doc-acl")
                .containsEntry(ElasticsearchConstants.FIELD_URI, "file:///kb/doc-acl.txt");
        assertThat((List<String>) source.get(ElasticsearchConstants.FIELD_SECURITY_DENIED_READ)).contains("mallory");
    }

    @Test
    void publicDocumentsAreReadableByAnyone() throws Exception {
        connector.send(new RepositoryDocument("doc-pub", "file:///kb/pub.txt", stream("Public handbook."),
                Map.of(), "public", SecurityConfig.createPublic(), Instant.now())).block();
        refresh();

        assertThat(knn(ElasticsearchAclQueries.readableBy("anyone", List.of()))).hasSize(1);
    }

    @Test
    void resendReplacesThePreviousChunksOfTheDocument() throws Exception {
        String longText = IntStream.range(0, 3000).mapToObj(i -> "word" + i).collect(Collectors.joining(" "));
        connector.send(document("doc-re", longText, security(rule("alice", "user", "read")))).block();
        refresh();
        assertThat(countDocument("doc-re")).isGreaterThan(1);

        connector.send(document("doc-re", "Now a single short chunk.", security(rule("alice", "user", "read")))).block();
        refresh();

        assertThat(countDocument("doc-re")).isEqualTo(1);
    }

    @Test
    void directDeleteTombstoneRemovesTheDocument() throws Exception {
        connector.send(document("doc-del", "To be deleted.", security(rule("alice", "user", "read")))).block();
        refresh();
        assertThat(countDocument("doc-del")).isEqualTo(1);

        connector.send(new RepositoryDocument("doc-del", "file:///kb/doc-del.txt", null, Map.of(), "",
                SecurityConfig.createPublic(), Instant.now(), DocumentAction.DELETE)).block();
        refresh();

        assertThat(countDocument("doc-del")).isZero();
    }

    @Test
    void kafkaWriterStoresChunksAndPurgesThemOnTombstone() throws Exception {
        ElasticsearchStoreWriterConsumer writer = new ElasticsearchStoreWriterConsumer(client, properties);
        Map<String, Object> metadata = Map.of(
                "uri", "file:///kb/kafka.txt",
                "acl", "engineering",
                "lastModified", "2026-03-04T05:06:07Z",
                "security", Map.of("inheritanceEnabled", true, "permissions", List.of(
                        Map.of("identity", "engineering", "identityType", "group", "displayName", "Eng", "access", "read"))));

        writer.consume(new DocumentEmbeddedMessage("doc-k", "doc-k_0", "first", metadata, VECTOR, DocumentAction.UPSERT));
        writer.consume(new DocumentEmbeddedMessage("doc-k", "doc-k_1", "second", metadata, VECTOR, DocumentAction.UPSERT));
        refresh();
        assertThat(countDocument("doc-k")).isEqualTo(2);
        assertThat(knn(ElasticsearchAclQueries.readableBy("carol", List.of("engineering")))).hasSize(2);
        assertThat(knn(ElasticsearchAclQueries.readableBy("carol", List.of()))).isEmpty();

        writer.consume(new DocumentEmbeddedMessage("doc-k", "doc-k_0", "", Map.of(), new float[0], DocumentAction.DELETE));
        refresh();

        assertThat(countDocument("doc-k")).isZero();
    }

    @Test
    void kafkaRecrawlThatShrinksTheDocumentRemovesTheStaleChunks() throws Exception {
        ElasticsearchStoreWriterConsumer writer = new ElasticsearchStoreWriterConsumer(client, properties);
        for (int i = 0; i < 3; i++) {
            writer.consume(new DocumentEmbeddedMessage("doc-shrink", "doc-shrink_" + i, "old " + i,
                    Map.of("uri", "file:///shrink.txt", "acl", "everyone", "chunk_index", i, "total_chunks", 3),
                    VECTOR, DocumentAction.UPSERT));
        }
        refresh();
        assertThat(countDocument("doc-shrink")).isEqualTo(3);

        writer.consume(new DocumentEmbeddedMessage("doc-shrink", "doc-shrink_0", "new",
                Map.of("uri", "file:///shrink.txt", "acl", "alice", "chunk_index", 0, "total_chunks", 1),
                VECTOR, DocumentAction.UPSERT));
        refresh();

        assertThat(countDocument("doc-shrink")).isEqualTo(1);
        assertThat(knn(ElasticsearchAclQueries.readableBy("everyone", List.of()))).as("no stale chunk keeps the old grant").isEmpty();
    }

    @Test
    void malformedLastModifiedDoesNotRejectTheChunk() throws Exception {
        ElasticsearchStoreWriterConsumer writer = new ElasticsearchStoreWriterConsumer(client, properties);

        writer.consume(new DocumentEmbeddedMessage("doc-m", "doc-m_0", "text",
                Map.of("uri", "file:///m.txt", "lastModified", "yesterday"), VECTOR, DocumentAction.UPSERT));
        refresh();

        assertThat(countDocument("doc-m")).isEqualTo(1);
    }

    /**
     * Writes, searches and deletes through a client that requests REST API compatibility with {@code major}.
     */
    protected void roundTripWithCompatibility(int major) throws Exception {
        ElasticsearchOutputProperties compat = properties(ElasticsearchOutputProperties.IndexType.HNSW, major);
        try (ElasticsearchClient compatClient = ElasticsearchClientFactory.create(compat)) {
            new ElasticsearchIndexInitializer(compatClient, compat).ensureIndex();
            new ElasticsearchOutputConnector(compatClient, compat, fixedEmbeddingModel())
                    .send(document("doc-compat", "Compatibility check.", security(rule("alice", "user", "read")))).block();
            compatClient.indices().refresh(r -> r.index(compat.indexName()));

            assertThat(compatClient.count(c -> c.index(compat.indexName())).count()).isEqualTo(1);
        } finally {
            client.indices().delete(d -> d.index(compat.indexName()).ignoreUnavailable(true));
        }
    }

    private ElasticsearchOutputProperties properties(ElasticsearchOutputProperties.IndexType indexType) {
        return properties(indexType, 8);
    }

    private ElasticsearchOutputProperties properties(ElasticsearchOutputProperties.IndexType indexType, int compatibleWith) {
        return new ElasticsearchOutputProperties("http://" + container().getHttpHostAddress(), "", "", "",
                "it_" + indexType.name().toLowerCase(Locale.ROOT) + "_" + UUID.randomUUID().toString().substring(0, 8),
                DIMS, ElasticsearchOutputProperties.Similarity.COSINE, indexType, 2, true, compatibleWith, null);
    }

    private Map<String, Property> mappingOf(String index) throws Exception {
        return client.indices().getMapping(m -> m.index(index)).get(index).mappings().properties();
    }

    private List<Hit<Map>> knn(Query filter) throws Exception {
        SearchResponse<Map> response = client.search(s -> s
                        .index(properties.indexName())
                        .knn(k -> {
                            k.field(ElasticsearchConstants.FIELD_EMBEDDINGS)
                                    .queryVector(toList(VECTOR))
                                    .k(10)
                                    .numCandidates(50);
                            if (filter != null) {
                                k.filter(filter);
                            }
                            return k;
                        }),
                Map.class);
        return response.hits().hits();
    }

    private long countDocument(String documentId) throws Exception {
        return client.count(c -> c.index(properties.indexName())
                .query(q -> q.term(t -> t.field(ElasticsearchConstants.FIELD_DOCUMENT_ID).value(documentId)))).count();
    }

    private void refresh() throws Exception {
        client.indices().refresh(r -> r.index(properties.indexName()));
    }

    private static RepositoryDocument document(String id, String text, SecurityConfig security) {
        return new RepositoryDocument(id, "file:///kb/" + id + ".txt", stream(text),
                Map.of("title", List.of("IT " + id)), "", security, Instant.parse("2026-01-02T03:04:05Z"));
    }

    private static SecurityConfig security(PermissionRule... rules) {
        return new SecurityConfig(true, List.of(rules));
    }

    private static PermissionRule rule(String identity, String type, String access) {
        return new PermissionRule(identity, type, identity, access);
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Float> toList(float[] vector) {
        return IntStream.range(0, vector.length).mapToObj(i -> vector[i]).toList();
    }

    private static EmbeddingModel fixedEmbeddingModel() {
        return new EmbeddingModel() {
            @Override
            public float[] embed(Document document) {
                return VECTOR;
            }

            @Override
            public float[] embed(String text) {
                return VECTOR;
            }

            @Override
            public int dimensions() {
                return DIMS;
            }

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                return new EmbeddingResponse(request.getInstructions().stream().map(t -> new Embedding(VECTOR, 0)).toList());
            }
        };
    }
}
