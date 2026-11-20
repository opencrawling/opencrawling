/*
 * Copyright © 2026 Michael Cizmar (piergiorgio@apache.org)
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
package org.opencrawling.qdrant.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.BinaryQuantization;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.QuantizationConfig;
import io.qdrant.client.grpc.Collections.QuantizationType;
import io.qdrant.client.grpc.Collections.ScalarQuantization;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import jakarta.annotation.PostConstruct;
import org.opencrawling.qdrant.QdrantFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Auto-provisions the configured Qdrant collection and its ACL payload indexes on startup,
 * kept separate from {@link QdrantClientConfig} so client construction has no I/O side effects.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "qdrant")
public class QdrantCollectionInitializer {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionInitializer.class);

    private final QdrantClient client;
    private final QdrantOutputProperties properties;

    public QdrantCollectionInitializer(QdrantClient client, QdrantOutputProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @PostConstruct
    public void initializeCollection() {
        try {
            String collectionName = properties.collectionName();
            boolean exists = Boolean.TRUE.equals(client.collectionExistsAsync(collectionName).get());
            if (exists) {
                log.info("Qdrant collection '{}' already exists.", collectionName);
                return;
            }

            log.info("Creating Qdrant collection '{}' with dimensions: {}", collectionName, properties.dimensions());

            VectorParams.Builder vectorParams = VectorParams.newBuilder()
                    .setSize(properties.dimensions())
                    .setDistance(mapDistance(properties.distance()));

            CreateCollection.Builder createCollection = CreateCollection.newBuilder()
                    .setCollectionName(collectionName)
                    .setVectorsConfig(VectorsConfig.newBuilder().setParams(vectorParams).build());

            QuantizationConfig quantizationConfig = mapQuantization(properties.quantization());
            if (quantizationConfig != null) {
                createCollection.setQuantizationConfig(quantizationConfig);
            }

            client.createCollectionAsync(createCollection.build()).get();

            client.createPayloadIndexAsync(collectionName, QdrantFields.FIELD_SECURITY_ALLOWED_READ, PayloadSchemaType.Keyword, null, true, null, null).get();
            client.createPayloadIndexAsync(collectionName, QdrantFields.FIELD_SECURITY_DENIED_READ, PayloadSchemaType.Keyword, null, true, null, null).get();

            log.info("Successfully created Qdrant collection '{}' with ACL payload indexes.", collectionName);
        } catch (Exception e) {
            log.error("Failed to initialize Qdrant collection '{}'", properties.collectionName(), e);
            throw new RuntimeException("Qdrant initialization error", e);
        }
    }

    private static Distance mapDistance(QdrantOutputProperties.Distance distance) {
        return switch (distance) {
            case COSINE -> Distance.Cosine;
            case DOT -> Distance.Dot;
            case EUCLID -> Distance.Euclid;
        };
    }

    private static QuantizationConfig mapQuantization(QdrantOutputProperties.Quantization quantization) {
        return switch (quantization) {
            case NONE -> null;
            case SCALAR_INT8 -> QuantizationConfig.newBuilder()
                    .setScalar(ScalarQuantization.newBuilder()
                            .setType(QuantizationType.Int8)
                            .setQuantile(0.99f) //Todo check Quantile
                            .setAlwaysRam(true)
                            .build())
                    .build();
            case BINARY -> QuantizationConfig.newBuilder()
                    .setBinary(BinaryQuantization.newBuilder().setAlwaysRam(true).build())
                    .build();
        };
    }
}
