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

import org.opencrawling.core.security.AuthorizationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Selects the document authorization provider from {@code opencrawling.security.authorization.mode}.
 *
 * <p>{@code metadata} (the default) decides from the ACLs stored with each document. The relationship-based
 * modes ({@code shadow}, {@code intersection}, {@code openfga}) are supplied by the OpenFGA module, which
 * registers its own {@link AuthorizationProvider}; asking for one of them without that module stops startup
 * rather than silently running with metadata decisions.
 */
@Configuration
public class AuthorizationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationConfiguration.class);

    public static final String MODE_PROPERTY = "opencrawling.security.authorization.mode";

    @Bean
    @ConditionalOnMissingBean(AuthorizationProvider.class)
    public AuthorizationProvider metadataAuthorizationProvider(
            @Value("${" + MODE_PROPERTY + ":metadata}") String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "", MetadataAuthorizationProvider.NAME -> {
                log.info("Document authorization mode: metadata (stored document ACLs).");
                return new MetadataAuthorizationProvider();
            }
            case "shadow", "intersection", "openfga" -> throw new IllegalStateException(MODE_PROPERTY + "="
                    + normalized + " needs the OpenFGA authorization module, which is not on the classpath.");
            default -> throw new IllegalStateException("Unknown " + MODE_PROPERTY + " '" + mode
                    + "'. Supported: metadata, shadow, intersection, openfga.");
        }
    }

    @Bean
    @ConditionalOnMissingBean(DocumentAccessGate.class)
    public DocumentAccessGate documentAccessGate(AuthorizationProvider provider) {
        return new DocumentAccessGate(provider);
    }
}
