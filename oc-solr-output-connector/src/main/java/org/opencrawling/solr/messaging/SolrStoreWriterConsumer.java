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
package org.opencrawling.solr.messaging;

import jakarta.annotation.PostConstruct;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.solr.SolrConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "solr")
@ConditionalOnExpression("'${opencrawling.consumer.writer.enabled:false}' == 'true'")
public class SolrStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(SolrStoreWriterConsumer.class);

    private static final Set<String> WRITER_FIELDS = Set.of(
            "uri", "acl", "lastModified", "security",
            SolrConstants.FIELD_SECURITY_INHERITANCE,
            SolrConstants.FIELD_SECURITY_ALLOWED_READ,
            SolrConstants.FIELD_SECURITY_DENIED_READ);

    private final SolrClient solrClient;

    @Value("${spring.opencrawling.output.solr.collection:enterprise_kb}")
    private String collectionName;

    @Value("${spring.opencrawling.output.solr.commit-within-ms:1000}")
    private int commitWithinMs;

    public SolrStoreWriterConsumer(SolrClient solrClient) {
        this.solrClient = solrClient;
    }

    @PostConstruct
    public void init() {
        log.info("SolrStoreWriterConsumer initialized successfully for collection '{}'!", collectionName);
    }

    @KafkaListener(topics = "opencrawling-embedded")
    public void consume(DocumentEmbeddedMessage message) {
        log.info("Received embedded chunk for Solr storage: {} (Dimensions: {})", message.chunkId(),
                message.embedding() != null ? message.embedding().length : 0);
        try {
            if (message.action() == DocumentAction.DELETE) {
                String docId = (message.documentId() != null && !message.documentId().isBlank())
                        ? message.documentId()
                        : message.chunkId();
                log.info("Received DELETE tombstone for Solr storage: document {}", docId);
                solrClient.deleteByQuery(collectionName, "id:\"" + docId + "\" OR documentId:\"" + docId + "\" OR chunk_id:\"" + message.chunkId() + "\"", commitWithinMs);
                log.info("Successfully deleted tombstone document/chunk {} from Solr collection '{}'.", docId, collectionName);
                return;
            }
            // Map Zero-Trust security ACLs (one normalization for every store, see EffectiveAcl)
            EffectiveAcl effectiveAcl = EffectiveAcl.resolve(message.metadata().get("security"), message.metadata().get("acl"));
            List<String> allowedRead = effectiveAcl.allowedRead();
            List<String> deniedRead = effectiveAcl.deniedRead();
            boolean inheritanceEnabled = effectiveAcl.inheritanceEnabled();

            SolrInputDocument doc = new SolrInputDocument();
            doc.addField(SolrConstants.FIELD_ID, message.chunkId());
            doc.addField("chunk_id", message.chunkId());
            if (message.documentId() != null && !message.documentId().isBlank()) {
                doc.addField("documentId", message.documentId());
            }
            doc.addField(SolrConstants.FIELD_TEXT, message.text());

            Object uriVal = message.metadata().get("uri");
            doc.addField(SolrConstants.FIELD_URI, uriVal != null ? String.valueOf(uriVal) : "");

            Object aclVal = message.metadata().get("acl");
            // Solr does not store an empty multi-valued field, and readers then fall back to the acl field, so it
            // holds the effective readers rather than the raw acl string (which permission rules override).
            doc.addField(SolrConstants.FIELD_ACL, String.join(",", allowedRead));

            Object lastModifiedVal = message.metadata().get("lastModified");
            doc.addField(SolrConstants.FIELD_LAST_MODIFIED, lastModifiedVal != null ? String.valueOf(lastModifiedVal) : "");

            doc.addField(SolrConstants.FIELD_SECURITY_INHERITANCE, inheritanceEnabled);
            doc.addField(SolrConstants.FIELD_SECURITY_ALLOWED_READ, allowedRead);
            doc.addField(SolrConstants.FIELD_SECURITY_DENIED_READ, deniedRead);

            if (message.embedding() != null) {
                List<Float> vectorList = new ArrayList<>(message.embedding().length);
                for (float f : message.embedding()) {
                    vectorList.add(f);
                }
                doc.addField(SolrConstants.FIELD_EMBEDDINGS, vectorList);
            }

            // Put other dynamic metadata properties into doc. Fields set above are skipped: addField appends to an
            // existing value, which would duplicate (or, for the immutable EffectiveAcl lists, reject) them.
            message.metadata().forEach((key, val) -> {
                if (!WRITER_FIELDS.contains(key)) {
                    doc.addField(key, val);
                }
            });

            solrClient.add(collectionName, doc, commitWithinMs);
            log.info("Successfully saved chunk {} to Solr collection '{}'.", message.chunkId(), collectionName);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to store embedded chunk in Solr: {}", message.chunkId(), e);
            // Rethrown so the container's error handler retries the chunk, then dead-letters it. Documents are added
            // under their unique key and a DELETE removes by document id, so retrying either is safe.
            throw new IllegalStateException("Solr write failed for chunk " + message.chunkId(), e);
        }
    }
}
