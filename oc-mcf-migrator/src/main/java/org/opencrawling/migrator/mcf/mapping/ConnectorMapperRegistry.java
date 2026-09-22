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
package org.opencrawling.migrator.mcf.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers every {@link ConnectorMapper} on the classpath via {@link ServiceLoader} and resolves
 * a ManifoldCF class name to the (at most one) mapper that claims it. To add support for a new
 * ManifoldCF connector without touching this class or any existing mapper: implement {@link
 * ConnectorMapper} and list it in {@code
 * META-INF/services/org.opencrawling.migrator.mcf.mapping.ConnectorMapper} — either in
 * this module or in a standalone jar dropped on the classpath alongside it.
 */
public class ConnectorMapperRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectorMapperRegistry.class);

    private final List<ConnectorMapper> mappers;

    public ConnectorMapperRegistry() {
        this(ServiceLoader.load(ConnectorMapper.class).stream().map(ServiceLoader.Provider::get).toList());
    }

    /** Package-visible test/extension hook — build a registry from an explicit mapper list. */
    ConnectorMapperRegistry(List<ConnectorMapper> mappers) {
        this.mappers = List.copyOf(mappers);
        log.info("Discovered {} connector mapper(s): {}", this.mappers.size(),
            this.mappers.stream().map(m -> m.getClass().getName()).toList());
    }

    public Optional<ConnectorMapper> find(String manifoldClassName) {
        return mappers.stream().filter(mapper -> mapper.supports(manifoldClassName)).findFirst();
    }

    public List<ConnectorMapper> all() {
        return mappers;
    }
}
