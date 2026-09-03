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
package org.opencrawling.solr.config;

import jakarta.annotation.PostConstruct;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.opencrawling.solr.SolrConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "solr")
public class SolrCollectionInitializer {

    private static final Logger log = LoggerFactory.getLogger(SolrCollectionInitializer.class);

    private final SolrClient solrClient;
    private final SolrOutputProperties properties;

    public SolrCollectionInitializer(SolrClient solrClient, SolrOutputProperties properties) {
        this.solrClient = solrClient;
        this.properties = properties;
    }

    @PostConstruct
    public void initializeSchema() {
        String collection = properties.collection();
        log.info("Checking Solr schema auto-provisioning for collection '{}'...", collection);

        try {
            ensureFieldType(collection);
            ensureFields(collection);
            log.info("Solr collection '{}' schema verification completed successfully.", collection);
        } catch (Exception e) {
            log.warn("Solr schema auto-provisioning for collection '{}' encountered an issue (may already exist or managed manually): {}",
                    collection, e.getMessage());
        }
    }

    private void ensureFieldType(String collection) throws Exception {
        try {
            SchemaRequest.FieldType getFieldTypeReq = new SchemaRequest.FieldType(SolrConstants.DEFAULT_FIELD_TYPE_KNN);
            SchemaResponse.FieldTypeResponse resp = getFieldTypeReq.process(solrClient, collection);
            if (resp.getFieldType() != null) {
                log.info("Solr field type '{}' already exists in collection '{}'.", SolrConstants.DEFAULT_FIELD_TYPE_KNN, collection);
                return;
            }
        } catch (Exception e) {
            log.debug("Field type '{}' not found, creating...", SolrConstants.DEFAULT_FIELD_TYPE_KNN);
        }

        Map<String, Object> fieldTypeProps = new HashMap<>();
        fieldTypeProps.put("name", SolrConstants.DEFAULT_FIELD_TYPE_KNN);
        fieldTypeProps.put("class", "solr.DenseVectorField");
        fieldTypeProps.put("vectorDimension", properties.dimensions());
        fieldTypeProps.put("similarityFunction", properties.similarity());
        fieldTypeProps.put("knnAlgorithm", "hnsw");
        fieldTypeProps.put("hnswMaxConnections", properties.hnswMaxConnections());
        fieldTypeProps.put("hnswBeamWidth", properties.hnswBeamWidth());
        fieldTypeProps.put("vectorEncoding", properties.vectorEncoding());

        org.apache.solr.client.solrj.request.schema.FieldTypeDefinition ftd = new org.apache.solr.client.solrj.request.schema.FieldTypeDefinition();
        ftd.setAttributes(fieldTypeProps);

        SchemaRequest.AddFieldType addFieldTypeReq = new SchemaRequest.AddFieldType(ftd);
        addFieldTypeReq.process(solrClient, collection);
        log.info("Successfully created field type '{}' in collection '{}'.", SolrConstants.DEFAULT_FIELD_TYPE_KNN, collection);
    }

    private void ensureFields(String collection) throws Exception {
        addFieldIfMissing(collection, SolrConstants.FIELD_EMBEDDINGS, SolrConstants.DEFAULT_FIELD_TYPE_KNN, true, true, false);
        addFieldIfMissing(collection, SolrConstants.FIELD_TEXT, "text_general", true, true, false);
        addFieldIfMissing(collection, SolrConstants.FIELD_URI, "string", true, true, false);
        addFieldIfMissing(collection, SolrConstants.FIELD_ACL, "string", true, true, false);
        addFieldIfMissing(collection, SolrConstants.FIELD_LAST_MODIFIED, "string", true, true, false);
        addFieldIfMissing(collection, SolrConstants.FIELD_SECURITY_INHERITANCE, "boolean", true, true, false);
        addFieldIfMissing(collection, SolrConstants.FIELD_SECURITY_ALLOWED_READ, "string", true, true, true);
        addFieldIfMissing(collection, SolrConstants.FIELD_SECURITY_DENIED_READ, "string", true, true, true);
    }

    private void addFieldIfMissing(String collection, String fieldName, String type, boolean indexed, boolean stored, boolean multiValued) {
        try {
            SchemaRequest.Field getFieldReq = new SchemaRequest.Field(fieldName);
            SchemaResponse.FieldResponse resp = getFieldReq.process(solrClient, collection);
            if (resp.getField() != null) {
                return;
            }
        } catch (Exception ignored) {
        }

        try {
            Map<String, Object> fieldProps = new HashMap<>();
            fieldProps.put("name", fieldName);
            fieldProps.put("type", type);
            fieldProps.put("indexed", indexed);
            fieldProps.put("stored", stored);
            if (multiValued) {
                fieldProps.put("multiValued", true);
            }

            SchemaRequest.AddField addFieldReq = new SchemaRequest.AddField(fieldProps);
            addFieldReq.process(solrClient, collection);
            log.info("Successfully added field '{}' to collection '{}'.", fieldName, collection);
        } catch (Exception e) {
            log.debug("Field '{}' creation skipped or already present: {}", fieldName, e.getMessage());
        }
    }
}
