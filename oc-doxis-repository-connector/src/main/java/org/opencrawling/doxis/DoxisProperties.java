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
package org.opencrawling.doxis;

import org.springframework.core.env.PropertyResolver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings of the Doxis repository connector.
 *
 * <p>Two sources, same keys: a connector configuration ({@code connectors.json}, camelCase keys such as {@code repositoryId})
 * via {@link #fromConfiguration(Map)}, or Spring properties under {@code spring.opencrawling.connector.doxis.*} (kebab-case,
 * e.g. {@code repository-id}; YAML lists allowed) via {@link #fromEnvironment(PropertyResolver)}.
 *
 * @param url                    CSB REST base, e.g. {@code http://csb:8080/restws/publicws/rest/api/v1}
 * @param authType               {@code basic}: user name + password ({@code POST /login}); {@code ticket}: a CSB session
 *                               ticket ({@code POST /loginBySessionTicket}); {@code oauth2}: an OIDC/OAuth2 access token
 *                               ({@code POST /loginOIDCWithAccessToken}), given directly or obtained with the client-credentials
 *                               grant from {@code oauth2TokenUrl}
 * @param crawlMode              {@code SEARCH} (one CQL search) or {@code FOLDER} (traverse an e-file's folder nodes)
 * @param documentClasses        document classes to keep (names or UUIDs); empty keeps every class
 * @param searchQuery            CQL condition appended as {@code WHERE …} (descriptor short names, {@code LIKE} with {@code *})
 * @param rootFolderId           e-file (record) UUID crawled in {@code FOLDER} mode
 * @param includeSubfolders      in {@code FOLDER} mode, descend into child folder nodes and sub-e-files
 * @param versionMode            {@code LATEST_ONLY} or {@code ALL_VERSIONS}
 * @param descriptorPrefix       prefix for descriptor metadata keys; empty emits the Doxis names as-is ({@code ObjectName}),
 *                               {@code doxis_desc_} gives {@code doxis_desc_ObjectName}
 * @param fallbackPrincipals     identities granted read when a document and its e-file carry no instance ACEs (class rights
 *                               then apply, which REST cannot read); empty means nobody, i.e. deny by default
 * @param emitLogicalDeletes     search {@code ANY_OBJECTS} and send a DELETE tombstone for logically removed documents
 * @param modifiedSince          optional lower bound for an incremental crawl, compared against {@code modifiedSinceAttribute}
 * @param batchSize              hits per search page
 * @param parallelism            documents processed at the same time; keep low, CSB sessions are licence-capped
 * @param maxDocuments           stop after this many documents (0 = no limit); meant for test runs against shared systems
 * @param tenantIsolation        qualify every permission identity with the CSB customer ({@code DX4/Legal}), so groups and users
 *                               of different Doxis tenants (Mandanten) never match each other in OpenCrawling
 * @param incremental            search mode only: keep a crawl state between runs, skip documents whose modification date is
 *                               unchanged, and after a complete listing send DELETE tombstones for documents that disappeared
 * @param stateDirectory         where the incremental crawl state is kept (one JSON file per customer and repository)
 */
public record DoxisProperties(
        String url,
        String authType,
        String customerName,
        String username,
        String password,
        String sessionTicket,
        String oauth2TokenUrl,
        String oauth2ClientId,
        String oauth2ClientSecret,
        String oauth2Scope,
        String oauth2AccessToken,
        String role,
        String clientId,
        String repositoryId,
        CrawlMode crawlMode,
        List<String> documentClasses,
        String searchQuery,
        String rootFolderId,
        boolean includeSubfolders,
        VersionMode versionMode,
        boolean includeContentStream,
        long maxContentSizeBytes,
        boolean includeDescriptors,
        boolean includeAcls,
        String descriptorPrefix,
        List<String> fallbackPrincipals,
        boolean emitLogicalDeletes,
        String modifiedSince,
        String modifiedSinceAttribute,
        int batchSize,
        int parallelism,
        Duration timeout,
        int maxRetries,
        int maxDocuments,
        boolean tenantIsolation,
        boolean incremental,
        String stateDirectory) {

    public enum CrawlMode { SEARCH, FOLDER }

    public enum VersionMode { LATEST_ONLY, ALL_VERSIONS }

    public static final String SPRING_PREFIX = "spring.opencrawling.connector.doxis.";
    public static final String DEFAULT_URL = "http://localhost:8080/restws/publicws/rest/api/v1";

    /** Every configuration key (camelCase); the Spring property is {@link #SPRING_PREFIX} + {@link #springName(String)}. */
    static final List<String> KEYS = List.of("url", "authType", "customerName", "username", "password", "sessionTicket",
            "oauth2TokenUrl", "oauth2ClientId", "oauth2ClientSecret", "oauth2Scope", "oauth2AccessToken", "role", "clientId",
            "repositoryId", "crawlMode", "documentClasses", "searchQuery", "rootFolderId", "includeSubfolders", "versionMode",
            "includeContentStream", "maxContentSizeBytes", "includeDescriptors", "includeAcls", "descriptorPrefix",
            "fallbackPrincipals", "emitLogicalDeletes", "modifiedSince", "modifiedSinceAttribute", "batchSize", "parallelism",
            "timeoutSeconds", "maxRetries", "maxDocuments", "tenantIsolation", "incremental", "stateDirectory");

    public static DoxisProperties fromConfiguration(Map<String, String> config) {
        Map<String, String> c = config == null ? Map.of() : config;
        return new DoxisProperties(
                value(c, "url", DEFAULT_URL),
                value(c, "authType", "basic").toLowerCase(Locale.ROOT),
                value(c, "customerName", null),
                value(c, "username", null),
                value(c, "password", null),
                value(c, "sessionTicket", null),
                value(c, "oauth2TokenUrl", null),
                value(c, "oauth2ClientId", null),
                value(c, "oauth2ClientSecret", null),
                value(c, "oauth2Scope", null),
                value(c, "oauth2AccessToken", null),
                value(c, "role", "admins"),
                value(c, "clientId", "OpenCrawling-RepositoryConnector"),
                value(c, "repositoryId", null),
                "folder".equalsIgnoreCase(value(c, "crawlMode", "search")) ? CrawlMode.FOLDER : CrawlMode.SEARCH,
                list(value(c, "documentClasses", "")),
                value(c, "searchQuery", ""),
                value(c, "rootFolderId", null),
                bool(c, "includeSubfolders", true),
                value(c, "versionMode", "latest_only").toLowerCase(Locale.ROOT).startsWith("all")
                        ? VersionMode.ALL_VERSIONS : VersionMode.LATEST_ONLY,
                bool(c, "includeContentStream", true),
                Math.max(0, longValue(c, "maxContentSizeBytes", 52_428_800L)),
                bool(c, "includeDescriptors", true),
                bool(c, "includeAcls", true),
                c.getOrDefault("descriptorPrefix", "").strip(),
                list(value(c, "fallbackPrincipals", "")),
                bool(c, "emitLogicalDeletes", true),
                value(c, "modifiedSince", ""),
                value(c, "modifiedSinceAttribute", "DXE_MODDATE"),
                Math.max(1, integer(c, "batchSize", 100)),
                Math.max(1, integer(c, "parallelism", 2)),
                Duration.ofSeconds(Math.max(1, integer(c, "timeoutSeconds", 120))),
                Math.max(0, integer(c, "maxRetries", 2)),
                Math.max(0, integer(c, "maxDocuments", 0)),
                bool(c, "tenantIsolation", false),
                bool(c, "incremental", false),
                value(c, "stateDirectory", "data/doxis-state"));
    }

    /**
     * Reads {@code spring.opencrawling.connector.doxis.*}. List properties may be comma-separated strings or YAML lists
     * ({@code document-classes[0]}, {@code document-classes[1]}, …).
     */
    public static DoxisProperties fromEnvironment(PropertyResolver environment) {
        Map<String, String> config = new HashMap<>();
        for (String key : KEYS) {
            String property = SPRING_PREFIX + springName(key);
            String value = environment.getProperty(property);
            if (value == null) {
                List<String> items = new ArrayList<>();
                for (int i = 0; environment.containsProperty(property + "[" + i + "]"); i++) {
                    items.add(environment.getProperty(property + "[" + i + "]"));
                }
                value = items.isEmpty() ? null : String.join(",", items);
            }
            if (value != null) {
                config.put(key, value);
            }
        }
        return fromConfiguration(config);
    }

    /**
     * Configuration problems that prevent a crawl; empty when the settings are usable.
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (customerName == null) {
            problems.add("customer-name (the CSB tenant, e.g. DX4) must be configured.");
        }
        switch (loginMode()) {
            case PASSWORD -> {
                if (!"basic".equals(authType) && !"login".equals(authType) && !"password".equals(authType)) {
                    problems.add("auth-type '" + authType + "' is not supported; use 'basic' (user name and password), "
                            + "'ticket' (CSB session ticket) or 'oauth2' (OIDC/OAuth2 access token).");
                } else if (username == null || password == null) {
                    problems.add("username and password must be configured for auth-type 'basic'.");
                }
            }
            case SESSION_TICKET -> {
                if (sessionTicket == null) {
                    problems.add("session-ticket must be configured for auth-type 'ticket'.");
                }
            }
            case OIDC_ACCESS_TOKEN -> {
                if (oauth2AccessToken == null && (oauth2TokenUrl == null || oauth2ClientId == null || oauth2ClientSecret == null)) {
                    problems.add("auth-type 'oauth2' needs oauth2.access-token, or oauth2.token-url, oauth2.client-id and "
                            + "oauth2.client-secret for the client-credentials grant.");
                }
            }
        }
        if (repositoryId == null) {
            problems.add("repository-id (the DMS repository name or UUID) must be configured.");
        }
        if (crawlMode == CrawlMode.FOLDER && rootFolderId == null) {
            problems.add("root-folder-id (an e-file UUID) is required in folder crawl mode.");
        }
        if (incremental && crawlMode == CrawlMode.FOLDER) {
            problems.add("incremental crawling is supported in search crawl mode only.");
        }
        return problems;
    }

    /**
     * The search statement: {@code SELECT * FROM <repository short name> [WHERE …]}.
     */
    public String cql(String repositoryShortName) {
        StringBuilder where = new StringBuilder();
        if (searchQuery != null && !searchQuery.isBlank()) {
            where.append('(').append(searchQuery.strip()).append(')');
        }
        if (modifiedSince != null && !modifiedSince.isBlank()) {
            if (!where.isEmpty()) {
                where.append(" AND ");
            }
            where.append(modifiedSinceAttribute).append(" >= '").append(modifiedSince.strip().replace("'", "''")).append('\'');
        }
        return "SELECT * FROM " + repositoryShortName + (where.isEmpty() ? "" : " WHERE " + where);
    }

    /**
     * The login the CSB REST API is asked for: {@code basic} → {@code POST /login}, {@code ticket} →
     * {@code POST /loginBySessionTicket}, {@code oauth2}/{@code oidc} → {@code POST /loginOIDCWithAccessToken}.
     */
    public org.opencrawling.doxis.client.DoxisClient.LoginMode loginMode() {
        return switch (authType == null ? "basic" : authType) {
            case "ticket", "session-ticket" -> org.opencrawling.doxis.client.DoxisClient.LoginMode.SESSION_TICKET;
            case "oauth2", "oidc" -> org.opencrawling.doxis.client.DoxisClient.LoginMode.OIDC_ACCESS_TOKEN;
            default -> org.opencrawling.doxis.client.DoxisClient.LoginMode.PASSWORD;
        };
    }

    /** The Spring property name of a key: kebab-case, with the OAuth2 keys nested as in issue #122 ({@code oauth2.token-url}). */
    static String springName(String key) {
        if (key.startsWith("oauth2")) {
            return "oauth2." + kebab(key.substring("oauth2".length(), "oauth2".length() + 1).toLowerCase(Locale.ROOT)
                    + key.substring("oauth2".length() + 1));
        }
        return kebab(key);
    }

    static String kebab(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String value(Map<String, String> config, String key, String fallback) {
        String value = config.get(key);
        return value == null || value.isBlank() ? fallback : value.strip();
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
        // never log passwords, session tickets, client secrets or access tokens
        return "DoxisProperties[url=" + url + ", authType=" + authType + ", customer=" + customerName + ", user=" + username
                + ", oauth2TokenUrl=" + oauth2TokenUrl + ", oauth2ClientId=" + oauth2ClientId + ", role=" + role
                + ", repository=" + repositoryId + ", crawlMode=" + crawlMode + ", searchQuery=" + searchQuery + ", rootFolderId="
                + rootFolderId + ", documentClasses=" + documentClasses + ", versionMode=" + versionMode + ", batchSize=" + batchSize
                + ", parallelism=" + parallelism + ", includeContentStream=" + includeContentStream + ", includeDescriptors="
                + includeDescriptors + ", includeAcls=" + includeAcls + ", emitLogicalDeletes=" + emitLogicalDeletes
                + ", modifiedSince=" + modifiedSince + ", maxDocuments=" + maxDocuments + ", tenantIsolation=" + tenantIsolation
                + ", incremental=" + incremental + "]";
    }
}
