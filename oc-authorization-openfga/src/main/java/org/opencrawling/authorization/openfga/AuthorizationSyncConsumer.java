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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Mirrors every document's permissions into OpenFGA. It reads the same documents topic as the ingestion
 * pipeline with its own consumer group, so crawled, REST-pushed and gRPC-pushed documents are covered
 * whichever output they go to. The Kafka key is the document id, so updates to one document stay in order.
 *
 * <p>A failed sync is rethrown: the listener retries it with backoff, then publishes it to
 * {@link #DEAD_LETTER_TOPIC}. Until a sync succeeds the document keeps its previous tuples (none for a new
 * document), so in {@code openfga} mode a new document stays unreadable (fail closed).
 *
 * <p>When the governance gate rejects a document, the ingestion consumer publishes a
 * {@link IngestionMessage#governanceRejection governance rejection} after it, and its tuples are removed. If a later
 * version of the document was synced in between, the notice is out of date and is ignored, so the later version keeps
 * its permissions. The versions synced last are remembered in memory, for a bounded number of documents; without that
 * record (after a restart, or for an evicted document) the tuples are removed, which fails closed until the next crawl.
 */
public class AuthorizationSyncConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationSyncConsumer.class);

    public static final String TOPIC = "opencrawling-documents";
    /**
     * Where a message goes after its retries are exhausted. Do not replay these records as they are: a later
     * update of the same document may already have been applied, and replaying the older one would roll its
     * permissions back. Recover by resetting the sync consumer group to the first dead letter's original offset
     * (header {@code kafka_dlt-original-offset}), so updates replay in order, or by re-crawling.
     */
    public static final String DEAD_LETTER_TOPIC = TOPIC + ".authz-dlt";
    public static final String CONTAINER_FACTORY = "authzSyncContainerFactory";

    static final int REMEMBERED_VERSIONS = 10_000;

    private final OpenFgaTupleWriter writer;
    /** Document id to the {@code lastModified} of the version synced last; bounded, least recently used out first. */
    private final Map<String, String> syncedVersions = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > REMEMBERED_VERSIONS;
                }
            });

    public AuthorizationSyncConsumer(OpenFgaTupleWriter writer) {
        this.writer = writer;
    }

    @KafkaListener(
            topics = TOPIC,
            containerFactory = CONTAINER_FACTORY,
            groupId = "${opencrawling.security.authorization.openfga.sync-group-id:opencrawling-authz-sync-v2}")
    public void consume(IngestionMessage message) throws Exception {
        if (message == null || message.documentId() == null || message.documentId().isBlank()) {
            log.warn("Skipping authorization sync for a message without a document id.");
            return;
        }
        String documentId = message.documentId();
        if (IngestionMessage.isGovernanceRejection(message)) {
            boolean laterVersionSynced;
            synchronized (syncedVersions) {
                laterVersionSynced = syncedVersions.containsKey(documentId)
                        && !Objects.equals(syncedVersions.get(documentId), message.lastModified());
            }
            if (laterVersionSynced) {
                log.info("Keeping the permissions of document {}: a later version was synced after the rejected one.",
                        documentId);
                return;
            }
            log.info("Removing the permissions of document {}: governance rejected it.", documentId);
            writer.delete(documentId);
            syncedVersions.remove(documentId);
        } else if (message.action() == DocumentAction.DELETE) {
            writer.delete(documentId);
            syncedVersions.remove(documentId);
        } else {
            writer.sync(documentId, EffectiveAcl.resolve(message.security(), message.acl()));
            syncedVersions.put(documentId, message.lastModified());
        }
    }
}
