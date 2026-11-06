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
package org.opencrawling.runtime.discovery;

import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.api.PersistenceHelper;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Repository discovery, when {@code opencrawling.discovery.enabled} is on. Discovery works on the registered
 * connectors the job API runs ({@code connectors.json}), built the same way.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "opencrawling.discovery.enabled", havingValue = "true")
@EnableConfigurationProperties(DiscoveryProperties.class)
public class DiscoveryConfiguration {

    @Bean
    public DiscoveryService discoveryService(ConnectorFactory connectorFactory, DiscoveryProperties properties) {
        return new DiscoveryService(
                () -> PersistenceHelper.loadList("connectors.json", ConnectorDTO.class, List.of()),
                connectorFactory::repository, properties);
    }
}
