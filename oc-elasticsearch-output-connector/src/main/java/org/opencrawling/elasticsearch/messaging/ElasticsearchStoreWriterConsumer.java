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
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.FieldValue;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.elasticsearch.ElasticsearchConstants;
import org.opencrawling.elasticsearch.ElasticsearchDocumentMapper;
import org.opencrawling.elasticsearch.config.ElasticsearchOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Stores embedded chunks from {@code opencrawling-embedded} in Elasticsearch (decoupled pipeline writer stage).
 *
 * <p>Failures are rethrown so the Kafka container's error handler retries the record instead of silently dropping
 * the chunk.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "elasticsearch")
@ConditionalOnExpression("'${opencrawling.consumer.writer.enabled:false}' == 'true'")
public class ElasticsearchStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchStoreWriterConsumer.class);

    /** Chunk position and count set by {@code IngestionConsumer}; stored as plain metadata fields. */
    static final String FIELD_CHUNK_INDEX = "chunk_index";
    static final String FIELD_TOTAL_CHUNKS = "total_chunks";

    private final ElasticsearchClient client;
    private final ElasticsearchOutputProperties properties;
    private final ElasticsearchDocumentMapper mapper = new ElasticsearchDocumentMapper();

    public ElasticsearchStoreWriterConsumer(ElasticsearchClient client, ElasticsearchOutputProperties properties) {
        this.client = client;
        this.properties = properties;
        log.info("ElasticsearchStoreWriterConsumer initialized for index '{}'.", properties.indexName());
    }

    @KafkaListener(topics = "opencrawling-embedded")
    public void consume(DocumentEmbeddedMessage message) {
        try {
            if (message.action() == DocumentAction.DELETE) {
                delete(message);
            } else {
                upsert(message);
            }
        } catch (IOException e) {
            log.error("Failed to store chunk {} in Elasticsearch: {}", message.chunkId(), e.getMessage(), e);
            throw new IllegalStateException("Elasticsearch write failed for chunk " + message.chunkId(), e);
        }
    }

    private void delete(DocumentEmbeddedMessage message) throws IOException {
        String documentId = isPresent(message.documentId()) ? message.documentId() : message.chunkId();
        log.info("Received DELETE tombstone for Elasticsearch: documentId={}, chunkId={}", documentId, message.chunkId());
        var response = client.deleteByQuery(d -> d
                .index(properties.indexName())
                .conflicts(Conflicts.Proceed)
                .query(q -> q.bool(b -> b
                        .should(s -> s.term(t -> t.field(ElasticsearchConstants.FIELD_DOCUMENT_ID).value(FieldValue.of(documentId))))
                        .should(s -> s.term(t -> t.field(ElasticsearchConstants.FIELD_ID).value(FieldValue.of(documentId))))
                        .should(s -> s.term(t -> t.field(ElasticsearchConstants.FIELD_ID).value(FieldValue.of(message.chunkId()))))
                        .minimumShouldMatch("1"))));
        log.info("Deleted {} chunks of document {} from Elasticsearch index '{}'.",
                response != null ? response.deleted() : null, documentId, properties.indexName());
    }

    private void upsert(DocumentEmbeddedMessage message) throws IOException {
        Map<String, Object> metadata = message.metadata() != null ? message.metadata() : Map.of();
        EffectiveAcl effectiveAcl = EffectiveAcl.resolve(
                metadata.get(ElasticsearchConstants.METADATA_SECURITY), metadata.get(ElasticsearchConstants.FIELD_ACL));
        Object uri = metadata.get(ElasticsearchConstants.FIELD_URI);
        Object lastModified = metadata.get(ElasticsearchConstants.FIELD_LAST_MODIFIED);

        Map<String, Object> source = mapper.toSource(new ElasticsearchDocumentMapper.Chunk(
                message.chunkId(),
                message.documentId(),
                message.text(),
                uri != null ? String.valueOf(uri) : "",
                metadata.get(ElasticsearchConstants.FIELD_ACL),
                lastModified != null ? String.valueOf(lastModified) : null,
                effectiveAcl,
                message.embedding(),
                metadata));

        client.index(i -> i
                .index(properties.indexName())
                .id(message.chunkId())
                .document(source));
        log.debug("Saved chunk {} to Elasticsearch index '{}'.", message.chunkId(), properties.indexName());

        purgeChunksPastNewLength(message.documentId(), metadata);
    }

    /**
     * A re-crawled document that got shorter keeps chunks past its new length, with its old text and ACL. Its first
     * chunk carries the new total, so remove those (the same rule as the pgvector writer).
     */
    private void purgeChunksPastNewLength(String documentId, Map<String, Object> metadata) throws IOException {
        if (!isPresent(documentId)
                || !(metadata.get(FIELD_CHUNK_INDEX) instanceof Number index) || index.intValue() != 0
                || !(metadata.get(FIELD_TOTAL_CHUNKS) instanceof Number total)) {
            return;
        }
        var response = client.deleteByQuery(d -> d
                .index(properties.indexName())
                .conflicts(Conflicts.Proceed)
                .query(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t.field(ElasticsearchConstants.FIELD_DOCUMENT_ID).value(FieldValue.of(documentId))))
                        .filter(f -> f.range(r -> r.number(n -> n.field(FIELD_CHUNK_INDEX).gte(total.doubleValue())))))));
        if (response != null && response.deleted() != null && response.deleted() > 0) {
            log.info("Removed {} stale chunks of document {} past its new length {}.", response.deleted(), documentId, total);
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
