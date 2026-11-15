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
package org.opencrawling.openarch.output.config;

import org.opencrawling.openarch.output.client.OpenArchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link OpenArchOutputProperties} and a shared {@link OpenArchClient} bean
 * when the Hyland OpenArch output connector is selected.
 */
@Configuration
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "openarch")
@EnableConfigurationProperties(OpenArchOutputProperties.class)
public class OpenArchClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenArchClientConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public OpenArchClient openArchClient(OpenArchOutputProperties properties) {
        log.info("Initializing OpenArchClient pointing to endpoint: {} (Repository: {})",
                properties.getEndpointUrl(), properties.getRepositoryId());
        return new OpenArchClient(properties.getEndpointUrl(), properties.getUsername(), properties.getPassword());
    }
}
