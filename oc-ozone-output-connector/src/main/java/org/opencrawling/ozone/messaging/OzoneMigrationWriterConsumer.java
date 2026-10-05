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
package org.opencrawling.ozone.messaging;

import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.ozone.OzoneOutputConnector;
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

/**
 * Decoupled Kafka consumer for Apache Ozone Output Connector.
 * Consumes ingestion messages directly from Kafka topic in Migration Mode and streams
 * content binaries into Apache Ozone along with OIS metadata sidecars.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "ozone")
@ConditionalOnProperty(name = "opencrawling.consumer.writer.enabled", havingValue = "true", matchIfMissing = true)
public class OzoneMigrationWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(OzoneMigrationWriterConsumer.class);

    private final OzoneOutputConnector outputConnector;
    private final ClaimCheckStore claimCheckStore;

    @Autowired
    public OzoneMigrationWriterConsumer(
            OzoneOutputConnector outputConnector,
            @Autowired(required = false) @Qualifier("claimCheckStore") ClaimCheckStore claimCheckStore) {
        this.outputConnector = outputConnector;
        this.claimCheckStore = claimCheckStore;
    }

    @KafkaListener(topics = "${spring.opencrawling.kafka.topic.ingestion:opencrawling-documents}")
    public void consume(IngestionMessage message) {
        if (message.pipelineMode() != PipelineMode.MIGRATION) {
            log.debug("OzoneMigrationWriterConsumer ignoring document {} in non-migration mode: {}",
                    message.documentId(), message.pipelineMode());
            return;
        }

        log.info("OzoneMigrationWriterConsumer processing migration document: {}", message.documentId());

        try {
            InputStream contentStream = null;
            if (message.action() != DocumentAction.DELETE && message.uri() != null && claimCheckStore != null) {
                try {
                    contentStream = claimCheckStore.get(URI.create(message.uri()));
                } catch (Exception ex) {
                    log.warn("Could not retrieve claim check stream for URI {}: {}", message.uri(), ex.getMessage());
                }
            }

            Instant lastModified = Instant.now();
            if (message.lastModified() != null) {
                try {
                    lastModified = Instant.parse(message.lastModified());
                } catch (Exception ignored) {}
            }

            RepositoryDocument doc = new RepositoryDocument(
                    message.documentId(),
                    message.uri(),
                    contentStream,
                    message.metadata(),
                    message.acl(),
                    message.security(),
                    lastModified,
                    message.action()
            );

            outputConnector.send(doc).block();
            log.info("Successfully migrated document {} to Apache Ozone via decoupled consumer.", message.documentId());

        } catch (Exception e) {
            log.error("Failed to migrate document {} to Apache Ozone: {}", message.documentId(), e.getMessage(), e);
        }
    }
}
