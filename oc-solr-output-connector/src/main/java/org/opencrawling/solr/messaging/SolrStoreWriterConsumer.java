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
                log.info("Received DELETE tombstone for Solr storage: document {}", message.documentId());
                solrClient.deleteByQuery(collectionName, "id:\"" + message.documentId() + "\" OR chunk_id:\"" + message.chunkId() + "\"", commitWithinMs);
                log.info("Successfully deleted tombstone document/chunk {} from Solr collection '{}'.", message.documentId(), collectionName);
                return;
            }
            // Map Zero-Trust security ACLs
            List<String> allowedRead = new ArrayList<>();
            List<String> deniedRead = new ArrayList<>();
            boolean inheritanceEnabled = true;

            Object securityObj = message.metadata().get("security");
            if (securityObj instanceof Map) {
                Map<?, ?> securityMap = (Map<?, ?>) securityObj;
                if (securityMap.containsKey("inheritanceEnabled")) {
                    inheritanceEnabled = Boolean.TRUE.equals(securityMap.get("inheritanceEnabled"));
                }
                Object permsObj = securityMap.get("permissions");
                if (permsObj instanceof List) {
                    List<?> permsList = (List<?>) permsObj;
                    for (Object permObj : permsList) {
                        if (permObj instanceof Map) {
                            Map<?, ?> permMap = (Map<?, ?>) permObj;
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
            } else if (securityObj instanceof org.opencrawling.core.security.SecurityConfig) {
                org.opencrawling.core.security.SecurityConfig sc = (org.opencrawling.core.security.SecurityConfig) securityObj;
                inheritanceEnabled = sc.inheritanceEnabled();
                for (org.opencrawling.core.security.PermissionRule rule : sc.permissions()) {
                    if ("read".equalsIgnoreCase(rule.access()) || "write".equalsIgnoreCase(rule.access())) {
                        allowedRead.add(rule.identity());
                    } else if ("deny".equalsIgnoreCase(rule.access())) {
                        deniedRead.add(rule.identity());
                    }
                }
            }

            SolrInputDocument doc = new SolrInputDocument();
            doc.addField(SolrConstants.FIELD_ID, message.chunkId());
            doc.addField(SolrConstants.FIELD_TEXT, message.text());

            Object uriVal = message.metadata().get("uri");
            doc.addField(SolrConstants.FIELD_URI, uriVal != null ? String.valueOf(uriVal) : "");

            Object aclVal = message.metadata().get("acl");
            doc.addField(SolrConstants.FIELD_ACL, aclVal != null ? String.valueOf(aclVal) : "");

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

            // Put other dynamic metadata properties into doc
            message.metadata().forEach((key, val) -> {
                if (!"uri".equals(key) && !"acl".equals(key) && !"lastModified".equals(key) && !"security".equals(key)) {
                    doc.addField(key, val);
                }
            });

            solrClient.add(collectionName, doc, commitWithinMs);
            log.info("Successfully saved chunk {} to Solr collection '{}'.", message.chunkId(), collectionName);
        } catch (Exception e) {
            log.error("Failed to store embedded chunk in Solr: {}", message.chunkId(), e);
        }
    }
}
