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
package org.opencrawling.iceberg.output.messaging;

import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.iceberg.output.IcebergOutputConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "iceberg")
@ConditionalOnProperty(name = "opencrawling.consumer.writer.enabled", havingValue = "true", matchIfMissing = true)
public class IcebergStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(IcebergStoreWriterConsumer.class);

    private final IcebergOutputConnector outputConnector;
    private final ClaimCheckStore claimCheckStore;

    @Autowired
    public IcebergStoreWriterConsumer(
            IcebergOutputConnector outputConnector,
            @Autowired(required = false) @Qualifier("claimCheckStore") ClaimCheckStore claimCheckStore) {
        this.outputConnector = outputConnector;
        this.claimCheckStore = claimCheckStore;
    }

    @KafkaListener(
            topics = "${spring.opencrawling.kafka.topic.ingestion:opencrawling-documents}",
            groupId = "${spring.opencrawling.output.iceberg.consumer-group:opencrawling-iceberg-group}",
            concurrency = "${spring.opencrawling.output.iceberg.consumer-concurrency:3}")
    public void consume(IngestionMessage message) {
        log.info("IcebergStoreWriterConsumer received document message: {}", message.documentId());

        try {
            if (message.action() == DocumentAction.DELETE) {
                log.info("Processing DELETE tombstone for document: {}", message.documentId());
                RepositoryDocument tombstone = RepositoryDocument.createTombstone(message.documentId(), message.uri());
                outputConnector.send(tombstone).block();
                return;
            }

            InputStream contentStream = null;
            if (claimCheckStore != null && message.uri() != null) {
                try {
                    URI fileUri = URI.create(message.uri());
                    contentStream = claimCheckStore.get(fileUri);
                } catch (Exception e) {
                    log.debug("No claim check content stream for {}: {}", message.documentId(), e.getMessage());
                }
            }

            Map<String, List<String>> metadata = message.metadata() != null ? new HashMap<>(message.metadata()) : new HashMap<>();

            RepositoryDocument document = new RepositoryDocument(
                    message.documentId(),
                    message.uri(),
                    contentStream,
                    metadata,
                    null,
                    message.security(),
                    Instant.now(),
                    DocumentAction.UPSERT
            );

            outputConnector.send(document).block();
            log.info("IcebergStoreWriterConsumer completed ingestion for document: {}", message.documentId());
        } catch (Exception e) {
            log.error("Failed to write document {} to Iceberg: {}", message.documentId(), e.getMessage(), e);
            throw new RuntimeException("IcebergStoreWriterConsumer error for document: " + message.documentId(), e);
        }
    }
}
