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

import org.opencrawling.luxir.client.LuxirClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "luxir")
@EnableConfigurationProperties(LuxirOutputProperties.class)
public class LuxirClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LuxirClientConfig.class);

    @Bean(destroyMethod = "close")
    public LuxirClient luxirClient(LuxirOutputProperties properties) {
        log.info("Initializing LuxirClient pointing to endpoint: {}", properties.endpoint());
        Duration timeout = Duration.ofSeconds(properties.timeoutSeconds());
        return new LuxirClient(properties.endpoint(), timeout);
    }
}
