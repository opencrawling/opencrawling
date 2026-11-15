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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
    private static final Set<String> SECRET_SUFFIXES = Set.of("password", "passwd", "apikey", "api_key", "token",
            "credential", "credentials", "privatekey", "private_key", "passphrase", "connectionstring", "sastoken");

    /** Keys whose whole value may carry credentials, such as an {@code Authorization} header. */
    private static final Set<String> SECRET_KEYS = Set.of("customheaders", "headers", "authorization");

    /** Keys that say where a connector connects: changing one must not reuse stored secrets. */
    private static final Pattern ADDRESS_KEY = Pattern.compile(".*(url|uri|endpoint|host|server|bootstrap).*");

    /** The password in a URL's user information: {@code scheme://user:password@host}. */
    private static final Pattern URL_PASSWORD = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*://[^/@:\\s]*:)([^@/\\s]+)(@)");

    /**
     * Whether a setting holds a secret: any key containing {@code secret} ({@code s3SecretAccessKey}, {@code clientSecret}),
     * keys ending in a secret's name ({@code password}, {@code token}, {@code apiKey}, ...), and headers.
     */
    public static boolean isSecret(String key) {
        String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return lower.contains("secret") || SECRET_KEYS.contains(lower) || SECRET_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    static boolean isAddress(String key) {
        return key != null && ADDRESS_KEY.matcher(key.toLowerCase(Locale.ROOT)).matches();
    }

    /** A value as clients see it: secrets as the mask, a URL with its password masked, everything else as is. */
    public static String masked(String key, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (isSecret(key)) {
            return MASK;
        }
        return URL_PASSWORD.matcher(value).replaceAll("$1" + Matcher.quoteReplacement(MASK) + "$3");
    }

    public static ConnectorResource of(ConnectorController.ConnectorDTO connector) {
        Map<String, String> masked = null;
        if (connector.configuration() != null) {
            masked = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(connector.configuration()).entrySet()) {
                masked.put(entry.getKey(), masked(entry.getKey(), entry.getValue()));
            }
        }
        return new ConnectorResource(connector.name(), connector.description(), connector.type(), connector.className(),
                connector.maxConnections(), masked);
    }

    /**
     * The configuration to store: values sent as they were read (the mask, or a URL with its password masked) keep the
     * stored value, so a client can send back what it read without knowing the secrets.
     *
     * <p>Stored secrets are only kept while the connector keeps pointing where it did: if an address setting (a URL,
     * endpoint or host) changes, a secret sent as the mask is refused and must be entered again. Otherwise anyone who
     * may edit connectors could send a stored password to a server of their choice.
     */
    public static Map<String, String> unmask(Map<String, String> sent, Map<String, String> stored) {
        if (sent == null) {
            return null;
        }
        Map<String, String> previous = stored == null ? Map.of() : stored;
        Map<String, String> result = new LinkedHashMap<>();
        List<String> kept = new ArrayList<>();
        sent.forEach((key, value) -> {
            String old = previous.get(key);
            if (MASK.equals(value)) {
                if (old == null) {
                    throw ApiProblem.invalid("configuration." + key, "is the mask, but no value is stored to keep");
                }
                result.put(key, old);
                kept.add(key);
            } else if (old != null && value != null && !value.equals(old) && value.equals(masked(key, old))) {
                result.put(key, old); // a URL sent back with its password masked
                kept.add(key);
            } else {
                result.put(key, value);
            }
        });
        if (!kept.isEmpty()) {
            List<String> moved = new java.util.TreeSet<>(sentAndStoredKeys(result, previous)).stream()
                    .filter(ConnectorResource::isAddress)
                    .filter(key -> !java.util.Objects.equals(result.get(key), previous.get(key)))
                    .toList();
            if (!moved.isEmpty()) {
                throw ApiProblem.invalid(kept.stream().sorted().map(key -> new ApiProblem.FieldError("configuration." + key,
                        "must be entered again, because the connector's address changed (" + String.join(", ", moved) + ")"))
                        .toList());
            }
        }
        return result;
    }

    private static java.util.Set<String> sentAndStoredKeys(Map<String, String> sent, Map<String, String> stored) {
        java.util.Set<String> keys = new java.util.HashSet<>(sent.keySet());
        keys.addAll(stored.keySet());
        return keys;
    }

    /**
     * A keyed digest of a connector's stored secrets, for its ETag: rotating a secret changes the tag (so a concurrent
     * change of a secret is detected), and the digest reveals nothing that could be tested offline without the key.
     */
    public static String secretsDigest(Map<String, String> configuration, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            if (configuration != null) {
                new java.util.TreeMap<>(configuration).forEach((name, value) -> {
                    if (value != null && !value.equals(masked(name, value))) {
                        mac.update((name + "\u0000" + value + "\u0000").getBytes(StandardCharsets.UTF_8));
                    }
                });
            }
            return java.util.HexFormat.of().formatHex(mac.doFinal());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
