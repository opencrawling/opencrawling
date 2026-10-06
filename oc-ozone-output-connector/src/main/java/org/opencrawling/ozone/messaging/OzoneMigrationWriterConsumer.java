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

    /**
     * Each listener thread owns a subset of the topic partitions. Messages are keyed by document id,
     * so all events of one document (UPSERT, later DELETE) stay on one partition and are applied in
     * order, while different documents migrate in parallel. Effective parallelism is
     * min(consumer-concurrency, topic partitions).
     */
    @KafkaListener(
            topics = "${spring.opencrawling.kafka.topic.ingestion:opencrawling-documents}",
            groupId = "${spring.opencrawling.output.ozone.consumer-group:opencrawling-ozone-migration-group}",
            concurrency = "${spring.opencrawling.output.ozone.consumer-concurrency:3}")
    public void consume(IngestionMessage message) {
        if (message.pipelineMode() != PipelineMode.MIGRATION) {
            log.debug("OzoneMigrationWriterConsumer ignoring document {} in non-migration mode: {}",
                    message.documentId(), message.pipelineMode());
            return;
        }

        log.info("OzoneMigrationWriterConsumer processing migration document: {}", message.documentId());

        try {
            InputStream contentStream = null;
            if (message.action() != DocumentAction.DELETE) {
                // Bit-for-bit parity: an UPSERT without its pristine binary must fail rather than
                // produce an empty object with a seemingly valid OIS sidecar.
                if (message.uri() == null || claimCheckStore == null) {
                    throw new IllegalStateException("No Claim Check store/URI available to resolve binary for document "
                            + message.documentId());
                }
                contentStream = claimCheckStore.get(URI.create(message.uri()));
                if (contentStream == null) {
                    throw new IllegalStateException("Claim Check returned no content for URI " + message.uri());
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
            // Rethrow so the Kafka container error handler can retry / dead-letter the record
            throw new IllegalStateException("Ozone migration failed for document " + message.documentId(), e);
        }
    }
}
