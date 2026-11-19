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
package org.opencrawling.zammad;

import org.springframework.core.env.PropertyResolver;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration settings for the Zammad repository connector (issue #342).
 *
 * <p>Loaded either from a per-job connector configuration map ({@code connectors.json}, camelCase or kebab-case keys)
 * via {@link #fromConfiguration(Map)}, or from Spring properties under {@code spring.opencrawling.connector.zammad.*}
 * via {@link #fromEnvironment(PropertyResolver)}.
 */
public record ZammadProperties(
        String baseUrl,
        AuthType authType,
        String accessToken,
        String username,
        String password,
        String onBehalfOf,
        List<String> groupNames,
        List<String> ticketStates,
        boolean includeTickets,
        boolean includeInternalArticles,
        ArticleGranularity articleGranularity,
        boolean includeAttachments,
        boolean includeKnowledgeBase,
        boolean includeTags,
        CrawlMode crawlMode,
        String stateFile,
        long maxAttachmentSizeBytes,
        boolean includeAcls,
        boolean includeCustomerInPublicAcl,
        boolean includeSharedOrganizationInPublicAcl,
        boolean allowPublicKb,
        int pageSize,
        int parallelism,
        int maxRetries,
        long initialBackoffMs,
        Duration timeout
) {

    public enum AuthType {
        /** {@code Authorization: Token token=<accessToken>} (Zammad personal access token). */
        TOKEN,
        /** {@code Authorization: Bearer <accessToken>} (Zammad OAuth2 bearer token). */
        OAUTH2,
        /** {@code Authorization: Basic <base64(username:password)>}. */
        BASIC
    }

    public enum ArticleGranularity {
        /**
         * Emits one OIS document for public customer-visible articles ({@code zammad://<instance>/tickets/<id>/public})
         * and a separate OIS document with agent-only ACLs for internal notes
         * ({@code zammad://<instance>/tickets/<id>/internal}).
         */
        SPLIT_PUBLIC_AND_INTERNAL,
        /** Emits one OIS document per ticket article ({@code zammad://<instance>/tickets/<id>/articles/<articleId>}). */
        PER_ARTICLE,
        /** Emits only public customer-visible articles ({@code zammad://<instance>/tickets/<id>/public}). */
        PUBLIC_ONLY
    }

    public enum CrawlMode {
        /** Incremental ticket search by {@code updated_at:[<watermark> TO *]} with persistent {@link ZammadCrawlState}. */
        SEARCH_DELTA,
        /** Full scan across all tickets and Knowledge Base entries. */
        FULL
    }

    public static final String SPRING_PREFIX = "spring.opencrawling.connector.zammad.";
    public static final String DEFAULT_BASE_URL = "http://localhost:8080";

    static final List<String> KEYS = List.of(
            "baseUrl", "authType", "accessToken", "username", "password", "onBehalfOf",
            "groupNames", "ticketStates", "includeTickets", "includeInternalArticles",
            "articleGranularity", "includeAttachments", "includeKnowledgeBase", "includeTags",
            "crawlMode", "stateFile", "maxAttachmentSizeBytes", "includeAcls",
            "includeCustomerInPublicAcl", "includeSharedOrganizationInPublicAcl", "allowPublicKb",
            "pageSize", "parallelism", "maxRetries", "initialBackoffMs", "timeoutSeconds"
    );

    public static ZammadProperties fromConfiguration(Map<String, String> config) {
        Map<String, String> c = normalizeKeys(config);
        String rawBaseUrl = firstNonBlank(c, "baseUrl", "url");
        String baseUrl = stripSlashes(rawBaseUrl != null ? rawBaseUrl : DEFAULT_BASE_URL);
        AuthType authType = parseAuthType(value(c, "authType", "token"));
        String accessToken = firstNonBlank(c, "accessToken", "token", "apiToken");
        String username = value(c, "username", null);
        String password = value(c, "password", null);
        String onBehalfOf = value(c, "onBehalfOf", null);
        List<String> groupNames = list(value(c, "groupNames", ""));
        List<String> ticketStates = list(value(c, "ticketStates", ""));
        boolean includeTickets = bool(c, "includeTickets", true);
        ArticleGranularity granularity = parseGranularity(value(c, "articleGranularity", "SPLIT_PUBLIC_AND_INTERNAL"));
        boolean includeInternal = granularity != ArticleGranularity.PUBLIC_ONLY
                && bool(c, "includeInternalArticles", true);
        boolean includeAttachments = bool(c, "includeAttachments", true);
        boolean includeKb = bool(c, "includeKnowledgeBase", true);
        boolean includeTags = bool(c, "includeTags", true);
        CrawlMode crawlMode = parseCrawlMode(value(c, "crawlMode", "search_delta"));
        String stateFile = value(c, "stateFile", "");
        long maxAttachmentSizeBytes = Math.max(0L, longValue(c, "maxAttachmentSizeBytes", 52_428_800L));
        boolean includeAcls = bool(c, "includeAcls", true);
        boolean includeCustomer = bool(c, "includeCustomerInPublicAcl", true);
        boolean includeSharedOrg = bool(c, "includeSharedOrganizationInPublicAcl", true);
        boolean allowPublicKb = bool(c, "allowPublicKb", false);
        int pageSize = Math.max(1, Math.min(ZammadClient.MAX_TICKETS_SEARCH_PAGE_SIZE, integer(c, "pageSize", 50)));
        int parallelism = Math.max(1, integer(c, "parallelism", 4));
        int maxRetries = Math.max(0, integer(c, "maxRetries", 5));
        long initialBackoffMs = Math.max(10L, longValue(c, "initialBackoffMs", 1000L));
        Duration timeout = Duration.ofSeconds(Math.max(1, integer(c, "timeoutSeconds", 30)));

        return new ZammadProperties(
                baseUrl, authType, accessToken, username, password, onBehalfOf,
                groupNames, ticketStates, includeTickets, includeInternal,
                granularity, includeAttachments, includeKb, includeTags,
                crawlMode, stateFile, maxAttachmentSizeBytes, includeAcls,
                includeCustomer, includeSharedOrg, allowPublicKb,
                pageSize, parallelism, maxRetries, initialBackoffMs, timeout
        );
    }

    public static ZammadProperties fromEnvironment(PropertyResolver environment) {
        Map<String, String> config = new HashMap<>();
        for (String key : KEYS) {
            String property = SPRING_PREFIX + kebab(key);
            String value = environment.getProperty(property);
            if (value == null) {
                List<String> items = new ArrayList<>();
                for (int i = 0; environment.containsProperty(property + "[" + i + "]"); i++) {
                    String item = environment.getProperty(property + "[" + i + "]");
                    if (item != null && !item.isBlank()) {
                        items.add(item.strip());
                    }
                }
                value = items.isEmpty() ? null : String.join(",", items);
            }
            if (value != null) {
                config.put(key, value);
            }
        }
        String urlAlias = environment.getProperty(SPRING_PREFIX + "url");
        if (urlAlias != null && !config.containsKey("baseUrl")) {
            config.put("baseUrl", urlAlias);
        }
        return fromConfiguration(config);
    }

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (baseUrl == null || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            problems.add("base-url must be a valid HTTP(S) URL, e.g. https://support.acme.com.");
        }
        switch (authType) {
            case TOKEN, OAUTH2 -> {
                if (accessToken == null || accessToken.isBlank()) {
                    problems.add("access-token must be configured for auth-type '" + authType.name().toLowerCase(Locale.ROOT) + "'.");
                }
            }
            case BASIC -> {
                if (username == null || username.isBlank() || password == null || password.isBlank()) {
                    problems.add("username and password must be configured for auth-type 'basic'.");
                }
            }
        }
        if (!includeTickets && !includeKnowledgeBase) {
            problems.add("At least one of include-tickets or include-knowledge-base must be true.");
        }
        return problems;
    }

    /**
     * Canonical instance authority used in OIS document URIs (e.g. {@code support.acme.com} or {@code localhost:8080}).
     */
    public String instanceKey() {
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return baseUrl.replaceFirst("^https?://", "");
            }
            int port = uri.getPort();
            if (port > 0 && port != 80 && port != 443) {
                return host.toLowerCase(Locale.ROOT) + ":" + port;
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return baseUrl.replaceFirst("^https?://", "");
        }
    }

    public boolean acceptsGroup(String groupName) {
        if (groupNames == null || groupNames.isEmpty()) {
            return true;
        }
        if (groupName == null || groupName.isBlank()) {
            return false;
        }
        return groupNames.stream().anyMatch(g -> g.equalsIgnoreCase(groupName.strip()));
    }

    public boolean acceptsTicketState(String stateName) {
        if (ticketStates == null || ticketStates.isEmpty()) {
            return true;
        }
        if (stateName == null || stateName.isBlank()) {
            return false;
        }
        return ticketStates.stream().anyMatch(s -> s.equalsIgnoreCase(stateName.strip()));
    }

    static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    static String camel(String key) {
        if (key == null || !key.contains("-")) {
            return key;
        }
        String[] parts = key.split("-");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(parts[i].substring(0, 1).toUpperCase(Locale.ROOT)).append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }

    private static Map<String, String> normalizeKeys(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new HashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && v != null) {
                normalized.put(k, v);
                normalized.putIfAbsent(camel(k), v);
            }
        });
        return normalized;
    }

    private static AuthType parseAuthType(String value) {
        if (value == null) {
            return AuthType.TOKEN;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "oauth2", "bearer" -> AuthType.OAUTH2;
            case "basic" -> AuthType.BASIC;
            default -> AuthType.TOKEN;
        };
    }

    private static ArticleGranularity parseGranularity(String value) {
        if (value == null) {
            return ArticleGranularity.SPLIT_PUBLIC_AND_INTERNAL;
        }
        return switch (value.strip().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "PER_ARTICLE", "ARTICLE" -> ArticleGranularity.PER_ARTICLE;
            case "PUBLIC_ONLY", "PUBLIC" -> ArticleGranularity.PUBLIC_ONLY;
            default -> ArticleGranularity.SPLIT_PUBLIC_AND_INTERNAL;
        };
    }

    private static CrawlMode parseCrawlMode(String value) {
        if (value == null) {
            return CrawlMode.SEARCH_DELTA;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "full", "all" -> CrawlMode.FULL;
            default -> CrawlMode.SEARCH_DELTA;
        };
    }

    private static String firstNonBlank(Map<String, String> config, String... keys) {
        for (String key : keys) {
            String val = value(config, key, null);
            if (val != null) {
                return val;
            }
        }
        return null;
    }

    private static String value(Map<String, String> config, String key, String fallback) {
        String value = config.get(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String stripSlashes(String url) {
        String u = url.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.endsWith("/api/v1")) {
            u = u.substring(0, u.length() - "/api/v1".length());
        }
        return u;
    }

    private static List<String> list(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean bool(Map<String, String> config, String key, boolean fallback) {
        String value = value(config, key, null);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int integer(Map<String, String> config, String key, int fallback) {
        try {
            String value = value(config, key, null);
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longValue(Map<String, String> config, String key, long fallback) {
        try {
            String value = value(config, key, null);
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return "ZammadProperties[baseUrl=" + baseUrl
                + ", authType=" + authType
                + ", username=" + username
                + ", onBehalfOf=" + onBehalfOf
                + ", groupNames=" + groupNames
                + ", ticketStates=" + ticketStates
                + ", includeTickets=" + includeTickets
                + ", includeInternalArticles=" + includeInternalArticles
                + ", articleGranularity=" + articleGranularity
                + ", includeAttachments=" + includeAttachments
                + ", includeKnowledgeBase=" + includeKnowledgeBase
                + ", crawlMode=" + crawlMode
                + ", includeAcls=" + includeAcls
                + ", pageSize=" + pageSize
                + ", parallelism=" + parallelism + "]";
    }
}
