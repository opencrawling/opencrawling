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
package org.opencrawling.doxis.output.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.doxis.output.DoxisDatasetManager;
import org.opencrawling.doxis.output.DoxisRowMapper;
import org.opencrawling.doxis.output.client.DoxisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "doxis")
@EnableConfigurationProperties(DoxisOutputProperties.class)
public class DoxisClientConfig {

    private static final Logger log = LoggerFactory.getLogger(DoxisClientConfig.class);

    @Bean(destroyMethod = "close")
    public DoxisClient doxisClient(DoxisOutputProperties properties) {
        log.info("Initializing DoxisClient pointing to: {}", properties.baseUrl());
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            log.warn("spring.opencrawling.output.doxis.api-key is not set; Doxis requests will be rejected with HTTP 401.");
        }
        return new DoxisClient(properties.baseUrl(), properties.apiKey(),
                Duration.ofSeconds(properties.timeoutSeconds()), properties.maxRetries());
    }

    @Bean
    public DoxisDatasetManager doxisDatasetManager(DoxisClient doxisClient, DoxisOutputProperties properties) {
        return new DoxisDatasetManager(doxisClient, properties);
    }

    @Bean
    public DoxisRowMapper doxisRowMapper(DoxisOutputProperties properties) {
        return new DoxisRowMapper(properties, new ObjectMapper());
    }
}
