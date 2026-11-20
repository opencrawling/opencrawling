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
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(QdrantOutputProperties.class)
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "qdrant")
public class QdrantClientConfig {

    private static final Logger log = LoggerFactory.getLogger(QdrantClientConfig.class);

    @Bean
    public QdrantClient qdrantClient(QdrantOutputProperties properties) {
        log.info("Initializing QdrantClient. Host: {}, Port: {}, TLS: {}", properties.host(), properties.port(), properties.useTls());
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(properties.host(), properties.port(), properties.useTls());
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            builder = builder.withApiKey(properties.apiKey());
        }
        log.info("Initialized QdrantClient. Host: {}, Port: {}, TLS: {}", properties.host(), properties.port(), properties.useTls());
        return new QdrantClient(builder.build());
    }
}
