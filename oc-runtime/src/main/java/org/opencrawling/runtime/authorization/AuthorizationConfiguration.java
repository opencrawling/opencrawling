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
package org.opencrawling.runtime.authorization;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.opencrawling.core.security.AuthorizationProvider;
import org.opencrawling.core.security.RelationshipAuthorizationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Builds the {@link DocumentAccessGate} for {@code opencrawling.security.authorization.mode}:
 * <ul>
 *   <li>{@code metadata} (default): the ACLs stored with each document decide.</li>
 *   <li>{@code shadow}: metadata decides; the relationship provider is consulted and disagreements logged.</li>
 *   <li>{@code intersection}: both must allow.</li>
 *   <li>{@code openfga}: the relationship provider decides alone.</li>
 * </ul>
 * The relationship modes need a {@link RelationshipAuthorizationProvider} bean (the OpenFGA module); without
 * one, startup stops rather than silently running with metadata decisions.
 */
@Configuration
public class AuthorizationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationConfiguration.class);

    public static final String MODE_PROPERTY = "opencrawling.security.authorization.mode";

    @Bean
    @ConditionalOnMissingBean(DocumentAccessGate.class)
    public DocumentAccessGate documentAccessGate(
            @Value("${" + MODE_PROPERTY + ":metadata}") String mode,
            @Value("${opencrawling.security.authorization.overfetch-factor:" + DocumentAccessGate.DEFAULT_OVERFETCH_FACTOR + "}") int overfetchFactor,
            @Value("${opencrawling.security.authorization.candidate-budget:" + DocumentAccessGate.DEFAULT_CANDIDATE_BUDGET + "}") int candidateBudget,
            ObjectProvider<RelationshipAuthorizationProvider> relationshipProvider,
            ObjectProvider<MeterRegistry> meterRegistry) {
        AuthorizationProvider provider = select(mode, relationshipProvider.getIfAvailable());
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null && provider instanceof ShadowAuthorizationProvider shadow) {
            FunctionCounter.builder("oc.authz.shadow.mismatch", shadow, ShadowAuthorizationProvider::mismatches)
                    .description("Decisions where the shadow provider disagreed").register(registry);
            FunctionCounter.builder("oc.authz.shadow.errors", shadow, ShadowAuthorizationProvider::errors)
                    .description("Shadow provider failures").register(registry);
        }
        log.info("Document authorization mode '{}' using provider {}.", normalize(mode), provider.name());
        return new DocumentAccessGate(provider, overfetchFactor, candidateBudget, registry);
    }

    /**
     * The provider for a mode.
     *
     * @throws IllegalStateException for an unknown mode, or a relationship mode without a relationship provider
     */
    public static AuthorizationProvider select(String mode, RelationshipAuthorizationProvider relationship) {
        String normalized = normalize(mode);
        MetadataAuthorizationProvider metadata = new MetadataAuthorizationProvider();
        return switch (normalized) {
            case "", MetadataAuthorizationProvider.NAME -> metadata;
            case "shadow" -> new ShadowAuthorizationProvider(metadata, require(normalized, relationship));
            case "intersection" -> new IntersectionAuthorizationProvider(metadata, require(normalized, relationship));
            case "openfga" -> require(normalized, relationship);
            default -> throw new IllegalStateException("Unknown " + MODE_PROPERTY + " '" + mode
                    + "'. Supported: metadata, shadow, intersection, openfga.");
        };
    }

    private static RelationshipAuthorizationProvider require(String mode, RelationshipAuthorizationProvider provider) {
        if (provider == null) {
            throw new IllegalStateException(MODE_PROPERTY + "=" + mode
                    + " needs the OpenFGA authorization module (oc-authorization-openfga) and its settings "
                    + "under opencrawling.security.authorization.openfga.*");
        }
        return provider;
    }

    private static String normalize(String mode) {
        return mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }
}
