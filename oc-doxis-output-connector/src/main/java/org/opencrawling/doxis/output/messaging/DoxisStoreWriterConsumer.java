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
package org.opencrawling.doxis.output.messaging;

import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.output.DoxisOutputConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Decoupled Doxis writer. Consumes {@link IngestionMessage}s from {@code opencrawling-documents} in its own consumer group
 * ({@code spring.opencrawling.output.doxis.consumer-group}, default {@code opencrawling-doxis-writer}), so archiving runs in
 * parallel to (and independently of) text extraction and embedding. Messages only carry a URI — the binary is opened lazily
 * through the {@link ClaimCheckStore} and only when the content plan uploads it.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "doxis")
@ConditionalOnExpression("'${opencrawling.consumer.writer.enabled:false}' == 'true'")
public class DoxisStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DoxisStoreWriterConsumer.class);

    private final DoxisOutputConnector connector;
    private final ObjectProvider<ClaimCheckStore> claimCheckStore;

    public DoxisStoreWriterConsumer(DoxisOutputConnector connector, ObjectProvider<ClaimCheckStore> claimCheckStore) {
        this.connector = connector;
        this.claimCheckStore = claimCheckStore;
    }

    @KafkaListener(topics = "opencrawling-documents",
            groupId = "${spring.opencrawling.output.doxis.consumer-group:opencrawling-doxis-writer}")
    public void consume(IngestionMessage message) {
        log.info("Received document {} for Doxis archiving ({}).", message.documentId(),
                message.action() == DocumentAction.DELETE ? "DELETE" : "UPSERT");
        try {
            connector.send(toDocument(message)).block();
        } catch (Exception e) {
            log.error("Failed to archive document {} into Doxis: {}", message.documentId(), e.getMessage(), e);
        }
    }

    RepositoryDocument toDocument(IngestionMessage message) {
        if (message.action() == DocumentAction.DELETE) {
            return RepositoryDocument.createTombstone(message.documentId(), message.uri());
        }
        Instant lastModified = null;
        if (message.lastModified() != null) {
            try {
                lastModified = Instant.parse(message.lastModified());
            } catch (RuntimeException ignored) {
                // leave unset
            }
        }
        String uri = message.uri();
        LazyInputStream content = null;
        if (uri != null && !uri.startsWith("file:")) {
            ClaimCheckStore store = claimCheckStore.getIfAvailable();
            if (store != null) {
                content = new LazyInputStream(() -> store.get(URI.create(uri)));
            }
        }
        Map<String, List<String>> metadata = message.metadata() != null ? message.metadata() : Map.of();
        SecurityConfig security = message.security() != null ? message.security() : SecurityConfig.createPublic();
        return new RepositoryDocument(message.documentId(), uri, content, metadata, message.acl(), security, lastModified,
                DocumentAction.UPSERT);
    }
}
