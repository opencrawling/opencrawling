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
package org.opencrawling.luxir.messaging;

import jakarta.annotation.PostConstruct;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.luxir.LuxirConstants;
import org.opencrawling.luxir.client.LuxirClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "luxir")
@ConditionalOnExpression("'${opencrawling.consumer.writer.enabled:false}' == 'true'")
public class LuxirStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(LuxirStoreWriterConsumer.class);

    private static final Set<String> RESERVED_KEYS = Set.of(
            "id",
            "doc_id",
            "documentId",
            "chunkId",
            "chunk_index",
            "total_chunks",
            "text",
            "uri",
            "acl",
            "title",
            "source",
            "lastModified",
            "security",
            "security_inheritance",
            "security_allowed_read",
            "security_denied_read",
            "embedding",
            LuxirConstants.FIELD_DOC_ID,
            LuxirConstants.FIELD_TEXT,
            LuxirConstants.FIELD_TITLE,
            LuxirConstants.FIELD_URI,
            LuxirConstants.FIELD_SOURCE,
            LuxirConstants.FIELD_LAST_MODIFIED,
            LuxirConstants.FIELD_ACL,
            LuxirConstants.FIELD_SECURITY_INHERITANCE,
            LuxirConstants.FIELD_SECURITY_ALLOWED_READ,
            LuxirConstants.FIELD_SECURITY_DENIED_READ
    );

    private final LuxirClient luxirClient;

    @Value("${spring.opencrawling.output.luxir.collection:opencrawling}")
    private String collectionName;

    @Value("${spring.opencrawling.output.luxir.vector-field:embedding_v}")
    private String vectorFieldName;

    @Value("${spring.opencrawling.output.luxir.auto-commit:true}")
    private boolean autoCommit;

    public LuxirStoreWriterConsumer(LuxirClient luxirClient) {
        this.luxirClient = luxirClient;
    }

    @PostConstruct
    public void init() {
        log.info("LuxirStoreWriterConsumer initialized successfully for collection '{}'!", collectionName);
    }

    @KafkaListener(topics = "opencrawling-embedded")
    public void consume(DocumentEmbeddedMessage message) {
        log.info("Received embedded chunk for Luxir storage: {} (Dimensions: {})", message.chunkId(),
                message.embedding() != null ? message.embedding().length : 0);
        try {
            if (message.action() == DocumentAction.DELETE) {
                log.info("Received DELETE tombstone for Luxir storage: document {}", message.documentId());
                List<String> deleteIds = new ArrayList<>();
                if (message.documentId() != null && !message.documentId().isBlank()) {
                    deleteIds.add(message.documentId());
                }
                if (message.chunkId() != null && !message.chunkId().isBlank() && !deleteIds.contains(message.chunkId())) {
                    deleteIds.add(message.chunkId());
                }
                luxirClient.deleteDocuments(collectionName, deleteIds, autoCommit);
                log.info("Successfully processed tombstone delete for IDs {} in Luxir collection '{}'.", deleteIds, collectionName);
                return;
            }

            // Map Zero-Trust security ACLs (one normalization for every store, see EffectiveAcl)
            EffectiveAcl effectiveAcl = EffectiveAcl.resolve(message.metadata().get("security"), message.metadata().get("acl"));
            List<String> allowedRead = effectiveAcl.allowedRead();
            List<String> deniedRead = effectiveAcl.deniedRead();
            boolean inheritanceEnabled = effectiveAcl.inheritanceEnabled();

            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put(LuxirConstants.FIELD_ID, message.chunkId());
            doc.put(LuxirConstants.FIELD_DOC_ID, message.documentId());
            doc.put(LuxirConstants.FIELD_TEXT, message.text());

            Object titleVal = message.metadata().get("title");
            if (titleVal != null) {
                doc.put(LuxirConstants.FIELD_TITLE, String.valueOf(titleVal));
            }

            Object uriVal = message.metadata().get("uri");
            if (uriVal != null) {
                doc.put(LuxirConstants.FIELD_URI, String.valueOf(uriVal));
            }

            Object sourceVal = message.metadata().get("source");
            if (sourceVal != null) {
                doc.put(LuxirConstants.FIELD_SOURCE, String.valueOf(sourceVal));
            }

            Object lastModifiedVal = message.metadata().get("lastModified");
            if (lastModifiedVal != null) {
                doc.put(LuxirConstants.FIELD_LAST_MODIFIED, String.valueOf(lastModifiedVal));
            }

            // Empty lists are not stored, and readers then fall back to acl_ss, so it holds the effective readers.
            doc.put(LuxirConstants.FIELD_ACL, allowedRead);

            // Zero-Trust security metadata
            doc.put(LuxirConstants.FIELD_SECURITY_INHERITANCE, String.valueOf(inheritanceEnabled));
            doc.put(LuxirConstants.FIELD_SECURITY_ALLOWED_READ, allowedRead);
            doc.put(LuxirConstants.FIELD_SECURITY_DENIED_READ, deniedRead);

            // Dense vector embedding
            if (message.embedding() != null) {
                List<Float> vectorList = new ArrayList<>(message.embedding().length);
                for (float f : message.embedding()) {
                    vectorList.add(f);
                }
                doc.put(vectorFieldName, vectorList);
            }

            // Extra metadata
            message.metadata().forEach((key, val) -> {
                if (!RESERVED_KEYS.contains(key) && !key.equals(vectorFieldName)) {
                    String targetKey;
                    if (key.endsWith("_s") || key.endsWith("_t") || key.endsWith("_ss")) {
                        targetKey = key;
                    } else if (val instanceof Collection<?> || (val != null && val.getClass().isArray())) {
                        targetKey = key + "_ss";
                    } else {
                        targetKey = key + "_s";
                    }
                    doc.put(targetKey, val);
                }
            });

            luxirClient.updateDocuments(collectionName, List.of(doc), autoCommit);
            log.info("Successfully saved chunk {} to Luxir collection '{}'.", message.chunkId(), collectionName);
        } catch (Exception e) {
            log.error("Failed to store embedded chunk in Luxir: {}", message.chunkId(), e);
        }
    }
}
