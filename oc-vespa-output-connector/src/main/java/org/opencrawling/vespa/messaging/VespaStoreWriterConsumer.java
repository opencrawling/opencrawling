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
package org.opencrawling.vespa.messaging;

import ai.vespa.feed.client.DocumentId;
import ai.vespa.feed.client.FeedClient;
import ai.vespa.feed.client.OperationParameters;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.vespa.VespaDocumentMapper;
import org.opencrawling.vespa.VespaFields;
import org.opencrawling.vespa.config.VespaOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
// Mirrors VectorStoreWriterConsumer (pgvector) / QdrantStoreWriterConsumer: enabled by default so a
// single-process deployment writes with no extra config, and decoupled non-writer services opt out with
// opencrawling.consumer.writer.enabled=false. Only active when the Vespa output is selected.
@ConditionalOnProperty(name = "opencrawling.consumer.writer.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("'${spring.opencrawling.output.type:pgvector}' == 'vespa'")
public class VespaStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(VespaStoreWriterConsumer.class);

    private final FeedClient client;
    private final VespaOutputProperties properties;
    private final VespaDocumentMapper mapper;

    public VespaStoreWriterConsumer(FeedClient client, VespaOutputProperties properties, VespaDocumentMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("VespaStoreWriterConsumer initialized successfully!");
    }

    @KafkaListener(topics = "opencrawling-embedded")
    public void consume(DocumentEmbeddedMessage message) {
        log.info("Received embedded chunk for Vespa storage: {} (Dimensions: {})", message.chunkId(),
                message.embedding() != null ? message.embedding().length : 0);
        try {
            if (message.action() == DocumentAction.DELETE) {
                log.info("Received DELETE tombstone for Vespa storage: {}", message.chunkId());
                String documentType = properties.documentType();
                DocumentId docId = DocumentId.of(properties.namespace(), documentType, message.chunkId());
                client.remove(docId, OperationParameters.empty()).get();
                log.info("Successfully removed tombstone chunk {} from Vespa.", message.chunkId());
                return;
            }
            Object uri = message.metadata().get(VespaFields.FIELD_URI);
            Object acl = message.metadata().get(VespaFields.FIELD_ACL);
            Object lastModified = message.metadata().get(VespaFields.FIELD_LAST_MODIFIED);
            Object security = message.metadata().get("security");

            // Route to a dimension-specific document type dynamically based on the embedded vector,
            // mirroring VectorStoreWriterConsumer's pgvector routing.
            String documentType = message.embedding() != null
                    ? VespaDocumentMapper.resolveDocumentType(message.embedding().length, properties.documentType())
                    : properties.documentType();

            VespaDocumentMapper.VespaDocument vespaDocument = mapper.toDocument(
                    properties.namespace(), documentType, message.chunkId(),
                    message.text(),
                    uri != null ? String.valueOf(uri) : "",
                    acl != null ? String.valueOf(acl) : "",
                    lastModified != null ? String.valueOf(lastModified) : "",
                    security,
                    message.embedding(),
                    message.metadata());

            client.put(vespaDocument.id(), vespaDocument.json(), OperationParameters.empty()).get();
            log.info("Successfully fed chunk {} to Vespa document type '{}'.", message.chunkId(), documentType);
        } catch (Exception e) {
            log.error("Failed to feed embedded chunk to Vespa: {}", message.chunkId(), e);
        }
    }
}
