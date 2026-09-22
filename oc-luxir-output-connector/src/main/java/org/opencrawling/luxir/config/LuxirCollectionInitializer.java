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
package org.opencrawling.luxir.config;

import jakarta.annotation.PostConstruct;
import org.opencrawling.luxir.LuxirConstants;
import org.opencrawling.luxir.client.LuxirClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "luxir")
public class LuxirCollectionInitializer {

    private static final Logger log = LoggerFactory.getLogger(LuxirCollectionInitializer.class);

    private final LuxirClient luxirClient;
    private final LuxirOutputProperties properties;

    public LuxirCollectionInitializer(LuxirClient luxirClient, LuxirOutputProperties properties) {
        this.luxirClient = luxirClient;
        this.properties = properties;
    }

    @PostConstruct
    public void initializeSchema() {
        String collection = properties.collection();
        log.info("Checking Luxir collection and schema auto-provisioning for collection '{}'...", collection);

        try {
            Map<String, Object> fields = new LinkedHashMap<>();

            Map<String, Object> embeddingField = new LinkedHashMap<>();
            embeddingField.put("type", "vector");
            embeddingField.put("dims", properties.dimensions());
            embeddingField.put("metric", properties.similarity());
            fields.put(properties.vectorField(), embeddingField);

            Map<String, Object> passagesField = new LinkedHashMap<>();
            passagesField.put("type", "vector");
            passagesField.put("dims", properties.dimensions());
            passagesField.put("metric", properties.similarity());
            passagesField.put("multi", true);
            fields.put(LuxirConstants.FIELD_PASSAGES, passagesField);

            Map<String, Object> schema = Map.of("fields", fields);

            boolean exists = luxirClient.collectionExists(collection);
            if (!exists) {
                log.info("Collection '{}' does not exist in Luxir, creating with vector schema...", collection);
                try {
                    luxirClient.createCollection(collection, schema);
                } catch (Exception e) {
                    log.debug("Collection create returned '{}', falling back to _schema endpoint...", e.getMessage());
                    luxirClient.setSchema(collection, schema);
                }
            } else {
                log.info("Collection '{}' exists in Luxir. Verifying vector schema definitions...", collection);
                luxirClient.setSchema(collection, schema);
            }

            log.info("Luxir collection '{}' schema verification completed successfully.", collection);
        } catch (Exception e) {
            log.warn("Luxir schema auto-provisioning for collection '{}' encountered an issue (server may be offline or managed manually): {}",
                    collection, e.getMessage());
        }
    }
}
