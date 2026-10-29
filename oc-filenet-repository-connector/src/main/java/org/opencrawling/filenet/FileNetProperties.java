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
package org.opencrawling.filenet;

import org.springframework.core.env.PropertyResolver;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings of the IBM FileNet P8 repository connector.
 *
 * <p>Two sources, same keys: a connector configuration ({@code connectors.json}, camelCase keys such as {@code objectStore})
 * via {@link #fromConfiguration(Map)}, or Spring properties under {@code spring.opencrawling.connector.filenet.*} (kebab-case,
 * e.g. {@code object-store}; YAML lists allowed) via {@link #fromEnvironment(PropertyResolver)}.
 *
 * @param url                         the Content Services GraphQL endpoint, e.g.
 *                                    {@code https://cpe.example.com/content-services-graphql/graphql}; content download
 *                                    URLs are resolved against its {@code /content-services-graphql} base
 * @param objectStore                 the object store to crawl: symbolic name or id
 * @param authType                    {@code basic} (user name and password on every request), {@code bearer} (a given
 *                                    access token), {@code oauth2} (client-credentials grant against {@code oauth2.token-url},
 *                                    or a given access token) or {@code ltpa} (a WebSphere Liberty {@code LtpaToken2} cookie)
 * @param crawlMode                   {@code QUERY}: documents of {@code documentClasses} matching {@code where};
 *                                    {@code FOLDER}: the same restricted to {@code rootFolderPath} ({@code This INSUBFOLDER},
 *                                    or {@code INFOLDER} without subfolders)
 * @param documentClasses             classes searched, one search each (the API takes a single class per search);
 *                                    subclasses are included
 * @param where                       an extra CE SQL condition, e.g. {@code [DocumentTitle] LIKE 'Loan%'}
 * @param sqlQuery                    a full CE SQL statement ({@code SELECT ... FROM <class> [WHERE ...]}); its FROM class
 *                                    and WHERE condition are used (the API shapes the SELECT itself)
 * @param versionMode                 {@code CURRENT} (the current version of each document), {@code RELEASED} (the latest
 *                                    released major version) or {@code ALL} (every version)
 * @param readAccessMask              access rights a grantee needs to be indexed as a reader; default 131201 (FileNet's
 *                                    "view content" level: read, view content, read permissions)
 * @param authenticatedUsersIdentity  identity for the {@code #AUTHENTICATED-USERS} grantee (a group); empty grants nothing
 * @param authenticatedUsersPublic    map {@code #AUTHENTICATED-USERS} to {@code public} instead
 * @param principalFormat             {@code NAME}: grantee names as FileNet returns them, lower-cased; {@code SHORT}: the
 *                                    short name ({@code cn=Finance,ou=..} and {@code DOMAIN\jdoe} become {@code finance},
 *                                    {@code jdoe})
 * @param deletionTracking            {@code RECONCILE}: compare the current ids with the crawl state each run (needs no
 *                                    auditing); {@code AUDIT}: read {@code DeletionEvent}s (needs deletion auditing on the
 *                                    document classes); {@code NONE}
 * @param deletionEventSql            the audit query; {@code {since}} is replaced by a CE timestamp literal
 */
public record FileNetProperties(
        String url,
        String objectStore,
        String authType,
        String username,
        String password,
        String oauth2TokenUrl,
        String oauth2ClientId,
        String oauth2ClientSecret,
        String oauth2Scope,
        String oauth2AccessToken,
        String ltpaToken,
        CrawlMode crawlMode,
        List<String> documentClasses,
        String where,
        String sqlQuery,
        String rootFolderPath,
        boolean includeSubfolders,
        VersionMode versionMode,
        boolean includeContentStream,
        long maxContentSizeBytes,
        boolean includeAcls,
        boolean includeCustomProperties,
        boolean includeSystemProperties,
        boolean includeFolderPaths,
        String propertyPrefix,
        int readAccessMask,
        String authenticatedUsersIdentity,
        boolean authenticatedUsersPublic,
        PrincipalFormat principalFormat,
        List<String> fallbackPrincipals,
        DeletionTracking deletionTracking,
        String deletionEventSql,
        boolean incrementalEnabled,
        String modifiedAfter,
        int clockSkewSeconds,
        int batchSize,
        int parallelism,
        Duration timeout,
        int maxRetries,
        int maxDocuments,
        String stateDirectory) {

    public enum CrawlMode { QUERY, FOLDER }

    public enum VersionMode { CURRENT, RELEASED, ALL }

    public enum PrincipalFormat { NAME, SHORT }

    public enum DeletionTracking { RECONCILE, AUDIT, NONE }

    public static final String SPRING_PREFIX = "spring.opencrawling.connector.filenet.";
    public static final String DEFAULT_URL = "http://localhost:9080/content-services-graphql/graphql";
    /** FileNet's "view content" access level, as IBM's own samples use it: read, view content and read permissions. */
    public static final int VIEW_CONTENT_LEVEL = 131201;
    public static final String DEFAULT_DELETION_EVENT_SQL =
            "SELECT [Id], [SourceObjectId], [DateCreated] FROM DeletionEvent WHERE [DateCreated] > {since}";

    private static final Pattern SQL = Pattern.compile(
            "(?is)^\\s*SELECT\\s+.+?\\s+FROM\\s+(?<from>\\S+(?:\\s+(?!WHERE\\b|ORDER\\b)\\w+)?(?:\\s+WITH\\s+\\w+)?)"
                    + "(?:\\s+WHERE\\s+(?<where>.+?))?(?:\\s+ORDER\\s+BY\\s+.+)?\\s*$");

    /** Every configuration key (camelCase); the Spring property is {@link #SPRING_PREFIX} + {@link #springName(String)}. */
    static final List<String> KEYS = List.of("url", "objectStore", "authType", "username", "password", "oauth2TokenUrl",
            "oauth2ClientId", "oauth2ClientSecret", "oauth2Scope", "oauth2AccessToken", "ltpaToken", "crawlMode",
            "documentClasses", "where", "sqlQuery", "rootFolderPath", "includeSubfolders", "versionMode",
            "includeContentStream", "maxContentSizeBytes", "includeAcls", "includeCustomProperties",
            "includeSystemProperties", "includeFolderPaths", "propertyPrefix", "readAccessMask",
            "authenticatedUsersIdentity", "authenticatedUsersPublic", "principalFormat", "fallbackPrincipals",
            "deletionTracking", "deletionTrackingEnabled", "deletionEventSql", "incrementalEnabled", "modifiedAfter",
            "clockSkewSeconds", "batchSize", "parallelism", "timeoutSeconds", "maxRetries", "maxDocuments", "stateDirectory");

    public static FileNetProperties fromConfiguration(Map<String, String> config) {
        Map<String, String> c = config == null ? Map.of() : config;
        DeletionTracking tracking = bool(c, "deletionTrackingEnabled", true)
                ? deletionTracking(value(c, "deletionTracking", "reconcile")) : DeletionTracking.NONE;
        return new FileNetProperties(
                stripSlashes(value(c, "url", DEFAULT_URL)),
                value(c, "objectStore", null),
                value(c, "authType", "basic").toLowerCase(Locale.ROOT),
                value(c, "username", null),
                value(c, "password", null),
                value(c, "oauth2TokenUrl", null),
                value(c, "oauth2ClientId", null),
                value(c, "oauth2ClientSecret", null),
                value(c, "oauth2Scope", null),
                value(c, "oauth2AccessToken", null),
                value(c, "ltpaToken", null),
                "folder".equalsIgnoreCase(value(c, "crawlMode", "query")) ? CrawlMode.FOLDER : CrawlMode.QUERY,
                list(value(c, "documentClasses", "Document")),
                value(c, "where", null),
                value(c, "sqlQuery", null),
                value(c, "rootFolderPath", "/"),
                bool(c, "includeSubfolders", true),
                versionMode(value(c, "versionMode", "current")),
                bool(c, "includeContentStream", true),
                Math.max(0, longValue(c, "maxContentSizeBytes", 52_428_800L)),
                bool(c, "includeAcls", true),
                bool(c, "includeCustomProperties", true),
                bool(c, "includeSystemProperties", false),
                bool(c, "includeFolderPaths", true),
                c.getOrDefault("propertyPrefix", "filenet_prop_").strip(),
                integer(c, "readAccessMask", VIEW_CONTENT_LEVEL),
                c.containsKey("authenticatedUsersIdentity") ? c.get("authenticatedUsersIdentity").strip()
                        : "#authenticated-users",
                bool(c, "authenticatedUsersPublic", false),
                "short".equalsIgnoreCase(value(c, "principalFormat", "name")) ? PrincipalFormat.SHORT : PrincipalFormat.NAME,
                list(value(c, "fallbackPrincipals", "")),
                tracking,
                value(c, "deletionEventSql", DEFAULT_DELETION_EVENT_SQL),
                bool(c, "incrementalEnabled", true),
                value(c, "modifiedAfter", ""),
                Math.max(0, integer(c, "clockSkewSeconds", 300)),
                Math.max(1, integer(c, "batchSize", 100)),
                Math.max(1, integer(c, "parallelism", 4)),
                Duration.ofSeconds(Math.max(1, integer(c, "timeoutSeconds", 30))),
                Math.max(0, integer(c, "maxRetries", 3)),
                Math.max(0, integer(c, "maxDocuments", 0)),
                value(c, "stateDirectory", "data/filenet-state"));
    }

    /**
     * Reads {@code spring.opencrawling.connector.filenet.*}. List properties may be comma-separated strings or YAML lists
     * ({@code document-classes[0]}, …).
     */
    public static FileNetProperties fromEnvironment(PropertyResolver environment) {
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

    /** Configuration problems that prevent a crawl; empty when the settings are usable. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            problems.add("url must be the Content Services GraphQL endpoint, e.g. "
                    + "https://cpe.example.com/content-services-graphql/graphql.");
        }
        if (objectStore == null) {
            problems.add("object-store (symbolic name or id of the object store) must be configured.");
        }
        switch (authType) {
            case "basic" -> {
                if (username == null || password == null) {
                    problems.add("username and password must be configured for auth-type 'basic'.");
                }
            }
            case "bearer" -> {
                if (oauth2AccessToken == null) {
                    problems.add("auth-type 'bearer' needs oauth2.access-token.");
                }
            }
            case "oauth2" -> {
                if (oauth2AccessToken == null && (oauth2TokenUrl == null || oauth2ClientId == null || oauth2ClientSecret == null)) {
                    problems.add("auth-type 'oauth2' needs oauth2.access-token, or oauth2.token-url, oauth2.client-id and "
                            + "oauth2.client-secret for the client-credentials grant.");
                }
            }
            case "ltpa" -> {
                if (ltpaToken == null) {
                    problems.add("auth-type 'ltpa' needs ltpa-token (the value of an LtpaToken2 cookie).");
                }
            }
            default -> problems.add("auth-type '" + authType + "' is not supported; use 'basic', 'bearer', 'oauth2' or 'ltpa'.");
        }
        if (documentClasses.isEmpty()) {
            problems.add("document-classes must name at least one class, e.g. Document.");
        }
        for (String cls : documentClasses) {
            if (!cls.matches("\\w+")) {
                problems.add("document class '" + cls + "' is not a symbolic name.");
            }
        }
        if (sqlQuery != null && parsedSql().isEmpty()) {
            problems.add("sql-query must be a CE SQL statement 'SELECT ... FROM <class> [WHERE ...]'.");
        }
        if (crawlMode == CrawlMode.FOLDER && (rootFolderPath == null || !rootFolderPath.startsWith("/"))) {
            problems.add("root-folder-path must be an absolute folder path such as /Mortgages in folder crawl mode.");
        }
        if (readAccessMask <= 0) {
            problems.add("read-access-mask must be a positive FileNet access mask (default " + VIEW_CONTENT_LEVEL + ").");
        }
        if (deletionTracking == DeletionTracking.AUDIT && !deletionEventSql.contains("{since}")) {
            problems.add("deletion-event-sql must contain {since}.");
        }
        if (modifiedAfter != null && !modifiedAfter.isBlank() && modifiedAfterInstant().isEmpty()) {
            problems.add("modified-after '" + modifiedAfter + "' is not an ISO date (2026-01-01) or timestamp "
                    + "(2026-01-01T00:00:00Z).");
        }
        return problems;
    }

    /** The FROM and WHERE of {@code sqlQuery}, when configured and well-formed: [from, where-or-null]. */
    public Optional<String[]> parsedSql() {
        if (sqlQuery == null) {
            return Optional.empty();
        }
        Matcher m = SQL.matcher(sqlQuery);
        return m.matches() ? Optional.of(new String[]{m.group("from").strip(), m.group("where") == null ? null
                : m.group("where").strip()}) : Optional.empty();
    }

    /** The configured {@code modified-after} as an instant; a date means its start in UTC. */
    public Optional<Instant> modifiedAfterInstant() {
        if (modifiedAfter == null || modifiedAfter.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(modifiedAfter));
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(LocalDate.parse(modifiedAfter).atStartOfDay(ZoneOffset.UTC).toInstant());
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
    }

    /** The {@code /content-services-graphql} base that download URLs are relative to: the endpoint without {@code /graphql}. */
    public String contentBase() {
        return url.endsWith("/graphql") ? url.substring(0, url.length() - "/graphql".length()) : url;
    }

    /** The object store as used in document ids: braces removed (they are not valid in every store's keys). */
    public String objectStoreKey() {
        return stripBraces(objectStore);
    }

    static String stripBraces(String guid) {
        if (guid == null) {
            return null;
        }
        String g = guid.strip();
        return g.startsWith("{") && g.endsWith("}") ? g.substring(1, g.length() - 1) : g;
    }

    /** The Spring property name of a key: kebab-case, with the OAuth2 keys nested as in issue #125 ({@code oauth2.token-url}). */
    static String springName(String key) {
        if (key.startsWith("oauth2")) {
            return "oauth2." + kebab(key.substring("oauth2".length(), "oauth2".length() + 1).toLowerCase(Locale.ROOT)
                    + key.substring("oauth2".length() + 1));
        }
        return kebab(key);
    }

    static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static VersionMode versionMode(String value) {
        return switch (value.toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "released", "latest_major", "released_only" -> VersionMode.RELEASED;
            case "all", "all_versions" -> VersionMode.ALL;
            default -> VersionMode.CURRENT;
        };
    }

    private static DeletionTracking deletionTracking(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "audit", "events" -> DeletionTracking.AUDIT;
            case "none", "off", "false" -> DeletionTracking.NONE;
            default -> DeletionTracking.RECONCILE;
        };
    }

    private static String value(Map<String, String> config, String key, String fallback) {
        String value = config.get(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String stripSlashes(String url) {
        String u = url;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
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
        // never log passwords, client secrets, access tokens or LTPA tokens
        return "FileNetProperties[url=" + url + ", objectStore=" + objectStore + ", authType=" + authType + ", user=" + username
                + ", oauth2TokenUrl=" + oauth2TokenUrl + ", oauth2ClientId=" + oauth2ClientId + ", crawlMode=" + crawlMode
                + ", documentClasses=" + documentClasses + ", where=" + where + ", sqlQuery=" + sqlQuery
                + ", rootFolderPath=" + rootFolderPath + ", includeSubfolders=" + includeSubfolders + ", versionMode="
                + versionMode + ", includeContentStream=" + includeContentStream + ", includeAcls=" + includeAcls
                + ", readAccessMask=" + readAccessMask + ", principalFormat=" + principalFormat + ", deletionTracking="
                + deletionTracking + ", incrementalEnabled=" + incrementalEnabled + ", modifiedAfter=" + modifiedAfter
                + ", batchSize=" + batchSize + ", parallelism=" + parallelism + ", maxDocuments=" + maxDocuments + "]";
    }
}
