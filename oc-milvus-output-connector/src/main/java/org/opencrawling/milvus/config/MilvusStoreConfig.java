/*
 * Copyright © 2026 Piergiorgio Lucidi (plucidi@ziaconsulting.com)
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
package org.opencrawling.milvus.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import org.opencrawling.milvus.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Configuration
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "milvus")
public class MilvusStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusStoreConfig.class);

    @Value("${spring.opencrawling.output.milvus.uri:http://localhost:19530}")
    private String uri;

    @Value("${spring.opencrawling.output.milvus.token:root:Milvus}")
    private String token;

    @Value("${spring.opencrawling.output.milvus.collection-name:enterprise_kb}")
    private String collectionName;

    @Value("${spring.opencrawling.output.milvus.vector-field-name:embeddings}")
    private String vectorFieldName;

    @Value("${spring.opencrawling.output.milvus.dimensions:1024}")
    private int dimensions;

    @Value("${spring.opencrawling.output.milvus.index-type:HNSW}")
    private String indexType;

    @Value("${spring.opencrawling.output.milvus.metric-type:COSINE}")
    private String metricType;

    @Bean
    public MilvusClientV2 milvusClientV2() {
        log.info("Initializing MilvusClientV2 connected to: {}", uri);
        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri(uri)
                .token(token)
                .build();
        MilvusClientV2 client = new MilvusClientV2(connectConfig);
        initializeCollection(client);
        return client;
    }

    private void initializeCollection(MilvusClientV2 client) {
        try {
            boolean exists = client.hasCollection(HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            
            if (exists) {
                log.info("Milvus collection '{}' already exists.", collectionName);
                warnIfItKeepsNoChunkPositions(client, collectionName);
                return;
            }

            log.info("Creating Milvus collection '{}' with dimensions: {}", collectionName, dimensions);

            CreateCollectionReq.CollectionSchema schema = client.createSchema();
            // Dynamic fields keep the chunk metadata that has no column of its own, among it documentId,
            // chunk_index and total_chunks, which getDocumentContent needs to put a document back together.
            schema.setEnableDynamicField(true);

            // Primary key ID
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusConstants.FIELD_ID)
                    .dataType(DataType.VarChar)
                    .maxLength(256)
                    .isPrimaryKey(true)
                    .autoID(false)
                    .build());

            // Vector field
            schema.addField(AddFieldReq.builder()
                    .fieldName(vectorFieldName)
                    .dataType(DataType.FloatVector)
                    .dimension(dimensions)
                    .build());

            // Text field
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusConstants.FIELD_TEXT)
                    .dataType(DataType.VarChar)
                    .maxLength(65535)
                    .build());

            // URI field
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusConstants.FIELD_URI)
                    .dataType(DataType.VarChar)
                    .maxLength(2048)
                    .build());

            // ACL / raw security field
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusConstants.FIELD_ACL)
                    .dataType(DataType.VarChar)
                    .maxLength(4096)
                    .build());

            // Last modified field
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusConstants.FIELD_LAST_MODIFIED)
                    .dataType(DataType.VarChar)
                    .maxLength(128)
                    .build());

            // Zero-Trust security: inheritanceEnabled
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusConstants.FIELD_SECURITY_INHERITANCE)
                    .dataType(DataType.Bool)
                    .build());

            // Zero-Trust security: security_allowed_read Array
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusConstants.FIELD_SECURITY_ALLOWED_READ)
                    .dataType(DataType.Array)
                    .elementType(DataType.VarChar)
                    .maxLength(256)
                    .maxCapacity(256)
                    .build());

            // Zero-Trust security: security_denied_read Array
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusConstants.FIELD_SECURITY_DENIED_READ)
                    .dataType(DataType.Array)
                    .elementType(DataType.VarChar)
                    .maxLength(256)
                    .maxCapacity(256)
                    .build());

            // Define index param
            java.util.Map<String, Object> extraParams = new java.util.HashMap<>();
            if ("HNSW".equalsIgnoreCase(indexType)) {
                extraParams.put("M", 16);
                extraParams.put("efConstruction", 64);
            } else if ("IVF_FLAT".equalsIgnoreCase(indexType)) {
                extraParams.put("nlist", 128);
            }

            IndexParam indexParam = IndexParam.builder()
                    .fieldName(vectorFieldName)
                    .metricType(IndexParam.MetricType.valueOf(metricType))
                    .indexType(IndexParam.IndexType.valueOf(indexType))
                    .extraParams(extraParams)
                    .build();

            CreateCollectionReq createReq = CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .collectionSchema(schema)
                    .indexParams(Collections.singletonList(indexParam))
                    .build();

            client.createCollection(createReq);
            log.info("Successfully created Milvus collection '{}'.", collectionName);

        } catch (Exception e) {
            log.error("Failed to initialize Milvus collection '{}'", collectionName, e);
            throw new RuntimeException("Milvus initialization error", e);
        }
    }

    /**
     * A collection created before dynamic fields were enabled drops every chunk's document id and position, so
     * {@code getDocumentContent} can only order its chunks when the document id is the URI. Recreating the
     * collection (and re-crawling) fixes that.
     */
    private static void warnIfItKeepsNoChunkPositions(MilvusClientV2 client, String collectionName) {
        try {
            Boolean dynamic = client.describeCollection(io.milvus.v2.service.collection.request.DescribeCollectionReq
                    .builder().collectionName(collectionName).build()).getEnableDynamicField();
            if (!Boolean.TRUE.equals(dynamic)) {
                log.warn("Milvus collection '{}' has no dynamic fields, so it keeps no document id or chunk position: "
                        + "getDocumentContent can only order a document's chunks when its id is its URI. Recreate the "
                        + "collection and re-crawl to keep them.", collectionName);
            }
        } catch (RuntimeException e) {
            log.debug("Could not describe Milvus collection '{}': {}", collectionName, e.getMessage());
        }
    }
}
