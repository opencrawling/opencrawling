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
package org.opencrawling.milvus.messaging;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.milvus.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
// Mirrors VectorStoreWriterConsumer (pgvector): enabled by default so a single-process
// deployment writes with no extra config, and decoupled non-writer services opt out with
// opencrawling.consumer.writer.enabled=false. Only active when the Milvus output is selected.
@ConditionalOnProperty(name = "opencrawling.consumer.writer.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("'${spring.opencrawling.output.type:pgvector}' == 'milvus'")
public class MilvusStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(MilvusStoreWriterConsumer.class);

    private final MilvusClientV2 client;
    private final Gson gson;

    @Value("${spring.opencrawling.output.milvus.collection-name:enterprise_kb}")
    private String collectionName;

    @Value("${spring.opencrawling.output.milvus.vector-field-name:embeddings}")
    private String vectorFieldName;

    public MilvusStoreWriterConsumer(MilvusClientV2 client) {
        this.client = client;
        this.gson = new Gson();
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("MilvusStoreWriterConsumer initialized successfully!");
    }

    @KafkaListener(topics = "opencrawling-embedded")
    public void consume(DocumentEmbeddedMessage message) {
        log.info("Received embedded chunk for Milvus storage: {} (Dimensions: {})", message.chunkId(), 
            message.embedding() != null ? message.embedding().length : 0);
        try {
            if (message.action() == DocumentAction.DELETE) {
                log.info("Received DELETE tombstone for Milvus storage: {}", message.chunkId());
                DeleteReq deleteReq = DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter("id == '" + message.chunkId() + "'")
                    .build();
                client.delete(deleteReq);
                log.info("Successfully deleted tombstone chunk {} from Milvus collection '{}'.", message.chunkId(), collectionName);
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

            JsonObject row = new JsonObject();
            row.addProperty(MilvusConstants.FIELD_ID, message.chunkId());
            row.addProperty(MilvusConstants.FIELD_TEXT, message.text());

            Object uriVal = message.metadata().get(MilvusConstants.FIELD_URI);
            row.addProperty(MilvusConstants.FIELD_URI, uriVal != null ? String.valueOf(uriVal) : "");

            Object aclVal = message.metadata().get(MilvusConstants.FIELD_ACL);
            row.addProperty(MilvusConstants.FIELD_ACL, aclVal != null ? String.valueOf(aclVal) : "");

            Object lastModifiedVal = message.metadata().get(MilvusConstants.FIELD_LAST_MODIFIED);
            row.addProperty(MilvusConstants.FIELD_LAST_MODIFIED, lastModifiedVal != null ? String.valueOf(lastModifiedVal) : "");

            row.addProperty(MilvusConstants.FIELD_SECURITY_INHERITANCE, inheritanceEnabled);
            row.add(MilvusConstants.FIELD_SECURITY_ALLOWED_READ, gson.toJsonTree(allowedRead));
            row.add(MilvusConstants.FIELD_SECURITY_DENIED_READ, gson.toJsonTree(deniedRead));
            row.add(vectorFieldName, gson.toJsonTree(message.embedding()));

            // Put other dynamic metadata properties to row
            message.metadata().forEach((key, val) -> {
                if (!MilvusConstants.FIELD_URI.equals(key) &&
                    !MilvusConstants.FIELD_ACL.equals(key) &&
                    !MilvusConstants.FIELD_LAST_MODIFIED.equals(key) &&
                    !"security".equals(key)) {
                    row.add(key, gson.toJsonTree(val));
                }
            });

            InsertReq insertReq = InsertReq.builder()
                    .collectionName(collectionName)
                    .data(Collections.singletonList(row))
                    .build();
            client.insert(insertReq);
            log.info("Successfully saved chunk {} to Milvus collection '{}'.", message.chunkId(), collectionName);
        } catch (Exception e) {
            log.error("Failed to store embedded chunk in Milvus: {}", message.chunkId(), e);
        }
    }
}
