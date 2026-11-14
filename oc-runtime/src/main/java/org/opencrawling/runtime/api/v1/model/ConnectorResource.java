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
package org.opencrawling.runtime.api.v1.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.opencrawling.runtime.api.ConnectorController;
import org.opencrawling.runtime.api.v1.ApiProblem;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A connector as the Platform API returns it. Secret settings (passwords, tokens, keys) are masked; callers who are
 * not admins get no configuration at all.
 */
@Schema(description = "A configured connector")
public record ConnectorResource(
        @Schema(description = "Unique name", example = "FileSystem_Local") String name,
        @Schema(description = "Description", example = "Local File System") String description,
        @Schema(description = "Category", example = "repository") String type,
        @Schema(description = "Implementation class") String className,
        @Schema(description = "Maximum concurrent connections", example = "10") Integer maxConnections,
        @Schema(description = "Settings, with secret values masked; null for callers who may not see them") Map<String, String> configuration) {

    public static final String MASK = "********";

    /** Key endings that name secrets: {@code password}, {@code apiKey}, {@code clientSecret}, {@code milvusToken}, ... */
    private static final Set<String> SECRET_SUFFIXES = Set.of("password", "passwd", "secret", "apikey", "api_key",
            "token", "credential", "credentials", "privatekey", "private_key", "secretkey", "secret_key", "passphrase");

    public static boolean isSecret(String key) {
        String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return SECRET_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    public static ConnectorResource of(ConnectorController.ConnectorDTO connector) {
        Map<String, String> masked = null;
        if (connector.configuration() != null) {
            masked = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(connector.configuration()).entrySet()) {
                boolean hide = isSecret(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty();
                masked.put(entry.getKey(), hide ? MASK : entry.getValue());
            }
        }
        return new ConnectorResource(connector.name(), connector.description(), connector.type(), connector.className(),
                connector.maxConnections(), masked);
    }

    /**
     * The configuration to store: values sent as the mask keep the stored secret, so a client can send back what it
     * read without knowing the secrets.
     */
    public static Map<String, String> unmask(Map<String, String> sent, Map<String, String> stored) {
        if (sent == null) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        sent.forEach((key, value) -> {
            if (MASK.equals(value)) {
                String previous = stored == null ? null : stored.get(key);
                if (previous == null) {
                    throw ApiProblem.invalid("configuration." + key, "is the mask, but no value is stored to keep");
                }
                result.put(key, previous);
            } else {
                result.put(key, value);
            }
        });
        return result;
    }
}
