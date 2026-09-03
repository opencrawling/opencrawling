/*
 * Copyright © ${year} the original author or authors (michael@michaelcizmar.com)
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
package org.opencrawling.qdrant.messaging;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.PointStruct;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.qdrant.QdrantFields;
import org.opencrawling.qdrant.QdrantPointMapper;
import org.opencrawling.qdrant.config.QdrantOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static io.qdrant.client.PointIdFactory.id;

@Component
// Mirrors VectorStoreWriterConsumer (pgvector) / MilvusStoreWriterConsumer: enabled by default so a
// single-process deployment writes with no extra config, and decoupled non-writer services opt out with
// opencrawling.consumer.writer.enabled=false. Only active when the Qdrant output is selected.
@ConditionalOnProperty(name = "opencrawling.consumer.writer.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("'${spring.opencrawling.output.type:pgvector}' == 'qdrant'")
public class QdrantStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(QdrantStoreWriterConsumer.class);

    private final QdrantClient client;
    private final QdrantOutputProperties properties;
    private final QdrantPointMapper mapper;

    public QdrantStoreWriterConsumer(QdrantClient client, QdrantOutputProperties properties, QdrantPointMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("QdrantStoreWriterConsumer initialized successfully!");
    }

    //Todo check for retry
    @KafkaListener(topics = "opencrawling-embedded")
    public void consume(DocumentEmbeddedMessage message) {
        log.info("Received embedded chunk for Qdrant storage: {} (Dimensions: {})", message.chunkId(),
                message.embedding() != null ? message.embedding().length : 0);
        try {
            if (message.action() == DocumentAction.DELETE) {
                log.info("Received DELETE tombstone for Qdrant storage: {}", message.chunkId());
                UUID pointUuid = UUID.nameUUIDFromBytes(message.chunkId().getBytes(StandardCharsets.UTF_8));
                client.deleteAsync(properties.collectionName(), List.of(id(pointUuid))).get();
                log.info("Successfully deleted tombstone chunk {} from Qdrant collection '{}'.", message.chunkId(), properties.collectionName());
                return;
            }
            Object uri = message.metadata().get(QdrantFields.FIELD_URI);
            Object acl = message.metadata().get(QdrantFields.FIELD_ACL);
            Object lastModified = message.metadata().get(QdrantFields.FIELD_LAST_MODIFIED);
            Object security = message.metadata().get("security");

            PointStruct point = mapper.toPoint(
                    message.chunkId(),
                    message.text(),
                    uri != null ? String.valueOf(uri) : "",
                    acl != null ? String.valueOf(acl) : "",
                    lastModified != null ? String.valueOf(lastModified) : "",
                    security,
                    message.embedding(),
                    message.metadata());

            client.upsertAsync(properties.collectionName(), List.of(point)).get();
            log.info("Successfully saved chunk {} to Qdrant collection '{}'.", message.chunkId(), properties.collectionName());
        } catch (Exception e) {
            log.error("Failed to store embedded chunk in Qdrant: {}", message.chunkId(), e);
        }
    }
}
