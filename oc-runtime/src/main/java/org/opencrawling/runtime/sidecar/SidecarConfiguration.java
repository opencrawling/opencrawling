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
package org.opencrawling.runtime.sidecar;

import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.api.PersistenceHelper;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * The OIS sidecar reader, when {@code opencrawling.sidecars.enabled} is on. It reads the registered connectors the
 * job API runs ({@code connectors.json}) and decides access with the runtime's {@link DocumentAccessGate}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = SidecarProperties.PREFIX + ".enabled", havingValue = "true")
@EnableConfigurationProperties(SidecarProperties.class)
public class SidecarConfiguration {

    @Bean
    public SidecarService sidecarService(SidecarProperties properties, ObjectProvider<DocumentAccessGate> gate) {
        return new SidecarService(properties,
                () -> PersistenceHelper.loadList("connectors.json", ConnectorDTO.class, List.of()),
                SidecarStores::open, gate.getIfAvailable(DocumentAccessGate::metadataDefault), Clock.systemUTC());
    }
}
