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
package org.opencrawling.authorization.openfga;

import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.security.EffectiveAcl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Mirrors every document's permissions into OpenFGA. It reads the same documents topic as the ingestion
 * pipeline with its own consumer group, so crawled, REST-pushed and gRPC-pushed documents are covered
 * whichever output they go to. The Kafka key is the document id, so updates to one document stay in order.
 *
 * <p>A failed sync is rethrown so the listener container retries it; until it succeeds the document has no
 * tuples, and in {@code openfga} mode it stays unreadable (fail closed).
 */
public class AuthorizationSyncConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationSyncConsumer.class);

    public static final String TOPIC = "opencrawling-documents";

    private final OpenFgaTupleWriter writer;

    public AuthorizationSyncConsumer(OpenFgaTupleWriter writer) {
        this.writer = writer;
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = "${opencrawling.security.authorization.openfga.sync-group-id:opencrawling-authz-sync}")
    public void consume(IngestionMessage message) throws Exception {
        if (message == null || message.documentId() == null || message.documentId().isBlank()) {
            log.warn("Skipping authorization sync for a message without a document id.");
            return;
        }
        if (message.action() == DocumentAction.DELETE) {
            writer.delete(message.documentId());
        } else {
            writer.sync(message.documentId(), EffectiveAcl.resolve(message.security(), message.acl()));
        }
    }
}
