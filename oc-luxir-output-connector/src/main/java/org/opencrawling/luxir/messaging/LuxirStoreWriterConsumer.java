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
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
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

            // Map Zero-Trust security ACLs
            List<String> allowedRead = new ArrayList<>();
            List<String> deniedRead = new ArrayList<>();
            boolean inheritanceEnabled = true;

            Object securityObj = message.metadata().get("security");
            if (securityObj instanceof Map<?, ?> securityMap) {
                if (securityMap.containsKey("inheritanceEnabled")) {
                    inheritanceEnabled = Boolean.TRUE.equals(securityMap.get("inheritanceEnabled"));
                }
                Object permsObj = securityMap.get("permissions");
                if (permsObj instanceof List<?> permsList) {
                    for (Object permObj : permsList) {
                        if (permObj instanceof Map<?, ?> permMap) {
                            String identity = String.valueOf(permMap.get("identity"));
                            String access = String.valueOf(permMap.get("access"));
                            if ("read".equalsIgnoreCase(access) || "write".equalsIgnoreCase(access)) {
                                allowedRead.add(identity);
                            } else if ("deny".equalsIgnoreCase(access)) {
                                deniedRead.add(identity);
                            }
                        }
                    }
                }
            } else if (securityObj instanceof SecurityConfig sc) {
                inheritanceEnabled = sc.inheritanceEnabled();
                for (PermissionRule rule : sc.permissions()) {
                    if ("read".equalsIgnoreCase(rule.access()) || "write".equalsIgnoreCase(rule.access())) {
                        allowedRead.add(rule.identity());
                    } else if ("deny".equalsIgnoreCase(rule.access())) {
                        deniedRead.add(rule.identity());
                    }
                }
            }

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

            // Legacy ACL token parsing
            Object aclVal = message.metadata().get("acl");
            if (aclVal != null) {
                List<String> aclTokens = Arrays.stream(String.valueOf(aclVal).split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                doc.put(LuxirConstants.FIELD_ACL, aclTokens);
            }

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
                if (!"uri".equals(key) && !"acl".equals(key) && !"lastModified".equals(key)
                        && !"security".equals(key) && !"title".equals(key) && !"source".equals(key)) {
                    String targetKey = key.endsWith("_s") || key.endsWith("_t") || key.endsWith("_ss") ? key : key + "_s";
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
