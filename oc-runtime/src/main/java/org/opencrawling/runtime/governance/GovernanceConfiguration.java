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
package org.opencrawling.runtime.governance;

import org.opencrawling.core.governance.PreEmbeddingGovernanceHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The governance settings are always bound, so job starts can tell whether the gate is on. The gate itself exists
 * only when {@code opencrawling.governance.enabled=true}, and a gate that is on but cannot be built stops startup:
 * running without it would embed documents nobody approved.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GovernanceProperties.class)
public class GovernanceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GovernanceConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = GovernanceProperties.PREFIX + ".enabled", havingValue = "true")
    public GovernanceGate governanceGate(GovernanceProperties properties, ObjectProvider<PreEmbeddingGovernanceHook> beans) {
        GovernanceGate gate = gate(properties, beans.orderedStream().toList());
        log.info("Pre-embedding governance is on: {}", gate);
        return gate;
    }

    /** The gate the settings describe, from the built-in hooks and the given hook beans. */
    static GovernanceGate gate(GovernanceProperties properties, List<PreEmbeddingGovernanceHook> beans) {
        Map<String, PreEmbeddingGovernanceHook> byName = new LinkedHashMap<>();
        for (PreEmbeddingGovernanceHook bean : beans) {
            String name = bean.name();
            if (name == null || name.isBlank() || name.equals(GovernanceProperties.PII_MASKING)
                    || name.equals(GovernanceProperties.HTTP) || byName.putIfAbsent(name, bean) != null) {
                throw new IllegalStateException("Governance hook bean " + bean.getClass().getName()
                        + " needs a name of its own; '" + name + "' is blank, built in or taken.");
            }
        }
        List<String> names = properties.getHooks();
        if (names.isEmpty()) {
            throw new IllegalStateException(GovernanceProperties.PREFIX + ".hooks must list at least one hook when "
                    + GovernanceProperties.PREFIX + ".enabled is true.");
        }
        List<PreEmbeddingGovernanceHook> hooks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : names) {
            String name = raw != null ? raw.strip() : "";
            if (!seen.add(name)) {
                throw new IllegalStateException("Governance hook '" + name + "' is listed twice.");
            }
            PreEmbeddingGovernanceHook hook = switch (name) {
                case GovernanceProperties.PII_MASKING -> {
                    if (properties.getPii().getTypes().isEmpty()) {
                        throw new IllegalStateException(GovernanceProperties.PREFIX
                                + ".pii.types is empty, so the pii-masking hook would mask nothing.");
                    }
                    yield new PiiMaskingGovernanceHook(properties.getPii().getTypes(), properties.getPii().isMaskMetadata());
                }
                case GovernanceProperties.HTTP -> {
                    try {
                        yield new HttpGovernanceHook(properties.getHttp());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                }
                default -> byName.get(name);
            };
            if (hook == null) {
                throw new IllegalStateException("Unknown governance hook '" + name + "': use "
                        + GovernanceProperties.PII_MASKING + ", " + GovernanceProperties.HTTP
                        + " or the name of a PreEmbeddingGovernanceHook bean.");
            }
            hooks.add(hook);
        }
        return new GovernanceGate(hooks, properties.getOnRejectedUpdate());
    }
}
