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
package org.opencrawling.documentum;

import org.springframework.core.env.PropertyResolver;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Settings of the OpenText Documentum repository connector.
 *
 * <p>Two sources, same keys: a connector configuration ({@code connectors.json}, camelCase keys such as
 * {@code repositoryName}) via {@link #fromConfiguration(Map)}, or Spring properties under
 * {@code spring.opencrawling.connector.documentum.*} (kebab-case, e.g. {@code repository-name}; YAML lists allowed) via
 * {@link #fromEnvironment(PropertyResolver)}.
 *
 * @param url                    the Documentum REST Services context, e.g. {@code https://dctm.example.com/dctm-rest}
 * @param repository             the repository (docbase) name
 * @param authType               {@code basic} (user name and password on every request; a login ticket can be the
 *                               password), {@code bearer} (a given access token) or {@code oauth2} (client-credentials grant
 *                               against {@code oauth2.token-url}, e.g. OTDS, or a given access token)
 * @param crawlMode              {@code DQL}: objects of {@code objectTypes} matching {@code where} (or {@code dqlQuery});
 *                               {@code FOLDER}: the same restricted to {@code rootCabinetPath} ({@code FOLDER(..., DESCEND)},
 *                               or without {@code DESCEND} when subfolders are excluded)
 * @param objectTypes            types searched, one query each; subtypes are included, as DQL does
 * @param where                  an extra DQL condition, e.g. {@code a_status = 'Approved'}
 * @param dqlQuery               a full DQL statement ({@code SELECT ... FROM <type> [WHERE ...]}); its FROM type and WHERE
 *                               condition are used (the connector chooses the selected attributes and the order)
 * @param versionMode            {@code CURRENT} (the CURRENT version of each document), {@code LATEST_MAJOR} (the highest
 *                               major version, {@code n.0}) or {@code ALL} (every version)
 * @param preferRendition        a rendition format (dm_format name, e.g. {@code pdf}) indexed instead of the primary
 *                               content when the document has it; empty for the primary content
 * @param worldIdentity          identity for the {@code dm_world} accessor (a group); empty grants nothing
 * @param worldPublic            map {@code dm_world} to {@code public} instead
 * @param principalFormat        {@code NAME}: accessor names as Documentum stores them ({@code user_name}), lower-cased;
 *                               {@code LOGIN}: users as their {@code user_login_name} (groups keep their name)
 * @param requiredGroupDocuments documents whose permission set has required groups or required group sets (an AND of
 *                               groups, which OpenCrawling's permission model cannot express): {@code EXCLUDE} (default)
 *                               leaves them out, {@code FALLBACK} indexes them for the fallback principals only
 * @param deletionTracking       {@code RECONCILE}: compare the ids in scope with the crawl state each run (needs no
 *                               auditing); {@code AUDIT}: read {@code dm_audittrail} events (needs auditing of those events
 *                               and the View Audit privilege); {@code NONE}
 * @param serverTimeZone         the Content Server's time zone, in which DQL reads date literals; {@code null} means
 *                               automatic: the offset of the dates the REST server returns (a region such as
 *                               Europe/Berlin follows daylight saving rules, and an invalid value is an error)
 */
public record DocumentumProperties(
        String url,
        String repository,
        String authType,
        String username,
        String password,
        String oauth2TokenUrl,
        String oauth2ClientId,
        String oauth2ClientSecret,
        String oauth2Scope,
        String oauth2AccessToken,
        CrawlMode crawlMode,
        List<String> objectTypes,
        String where,
        String dqlQuery,
        String rootCabinetPath,
        boolean includeSubfolders,
        VersionMode versionMode,
        boolean includeContentStream,
        long maxContentSizeBytes,
        String preferRendition,
        boolean includeAcls,
        String worldIdentity,
        boolean worldPublic,
        PrincipalFormat principalFormat,
        RequiredGroupDocuments requiredGroupDocuments,
        List<String> fallbackPrincipals,
        boolean includeCustomAttributes,
        boolean includeSystemAttributes,
        boolean includeFolderPaths,
        String attributePrefix,
        DeletionTracking deletionTracking,
        List<String> auditEvents,
        boolean incrementalEnabled,
        String modifiedAfter,
        ZoneId serverTimeZone,
        int clockSkewSeconds,
        int batchSize,
        int parallelism,
        Duration timeout,
        int maxRetries,
        int maxDocuments,
        String stateDirectory) {

    public enum CrawlMode { DQL, FOLDER }

    public enum VersionMode { CURRENT, LATEST_MAJOR, ALL }

    public enum PrincipalFormat { NAME, LOGIN }

    public enum RequiredGroupDocuments { EXCLUDE, FALLBACK }

    public enum DeletionTracking { RECONCILE, AUDIT, NONE }

    public static final String SPRING_PREFIX = "spring.opencrawling.connector.documentum.";
    public static final String DEFAULT_URL = "http://localhost:8080/dctm-rest";
    /** The REST server's {@code rest.paging.max.size} default: a larger {@code items-per-page} is refused. */
    public static final int MAX_PAGE_SIZE = 1000;

    private static final Pattern DQL = Pattern.compile(
            "(?is)^\\s*SELECT\\s+.+?\\s+FROM\\s+(?<type>\\w+)(?:\\s*\\(\\s*ALL\\s*\\))?"
                    + "(?:\\s+WHERE\\s+(?<where>.+?))?(?:\\s+ORDER\\s+BY\\s+.+?)?(?:\\s+ENABLE\\s*\\(.*\\))?\\s*;?\\s*$");
    private static final Pattern NAME = Pattern.compile("\\w+");

    /** Every configuration key (camelCase); the Spring property is {@link #SPRING_PREFIX} + {@link #springName(String)}. */
    static final List<String> KEYS = List.of("url", "repository", "repositoryName", "authType", "username", "password",
            "oauth2TokenUrl", "oauth2ClientId", "oauth2ClientSecret", "oauth2Scope", "oauth2AccessToken", "crawlMode",
            "objectTypes", "where", "dqlQuery", "rootCabinetPath", "rootFolderPath", "includeSubfolders", "versionMode",
            "includeContentStream", "maxContentSizeBytes", "preferRendition", "includeAcls", "worldIdentity", "worldPublic",
            "principalFormat", "requiredGroupDocuments", "fallbackPrincipals", "includeCustomAttributes",
            "includeSystemAttributes", "includeFolderPaths", "attributePrefix", "deletionTracking",
            "deletionTrackingEnabled", "auditDeletionEnabled", "auditEvents", "incrementalEnabled", "modifiedAfter",
            "serverTimeZone", "clockSkewSeconds", "batchSize", "parallelism", "timeoutSeconds", "maxRetries",
            "maxDocuments", "stateDirectory");

    public static DocumentumProperties fromConfiguration(Map<String, String> config) {
        Map<String, String> c = config == null ? Map.of() : config;
        DeletionTracking tracking;
        if (!bool(c, "deletionTrackingEnabled", true)) {
            tracking = DeletionTracking.NONE;
        } else if (value(c, "deletionTracking", null) == null && bool(c, "auditDeletionEnabled", false)) {
            tracking = DeletionTracking.AUDIT; // the issue's audit-deletion-enabled
        } else {
            tracking = deletionTracking(value(c, "deletionTracking", "reconcile"));
        }
        return new DocumentumProperties(
                stripSlashes(value(c, "url", DEFAULT_URL)),
                value(c, "repository", value(c, "repositoryName", null)),
                value(c, "authType", "basic").toLowerCase(Locale.ROOT),
                value(c, "username", null),
                value(c, "password", null),
                value(c, "oauth2TokenUrl", null),
                value(c, "oauth2ClientId", null),
                value(c, "oauth2ClientSecret", null),
                value(c, "oauth2Scope", null),
                value(c, "oauth2AccessToken", null),
                "folder".equalsIgnoreCase(value(c, "crawlMode", "dql")) ? CrawlMode.FOLDER : CrawlMode.DQL,
                list(value(c, "objectTypes", "dm_document")).stream().map(t -> t.toLowerCase(Locale.ROOT)).toList(),
                value(c, "where", null),
                value(c, "dqlQuery", null),
                stripSlashes(value(c, "rootCabinetPath", value(c, "rootFolderPath", "/"))),
                bool(c, "includeSubfolders", true),
                versionMode(value(c, "versionMode", "current_only")),
                bool(c, "includeContentStream", true),
                Math.max(0, longValue(c, "maxContentSizeBytes", 52_428_800L)),
                value(c, "preferRendition", "").toLowerCase(Locale.ROOT),
                bool(c, "includeAcls", true),
                c.containsKey("worldIdentity") ? c.get("worldIdentity").strip().toLowerCase(Locale.ROOT) : "dm_world",
                bool(c, "worldPublic", false),
                "login".equalsIgnoreCase(value(c, "principalFormat", "name")) ? PrincipalFormat.LOGIN : PrincipalFormat.NAME,
                "fallback".equalsIgnoreCase(value(c, "requiredGroupDocuments", "exclude")) ? RequiredGroupDocuments.FALLBACK
                        : RequiredGroupDocuments.EXCLUDE,
                list(value(c, "fallbackPrincipals", "")),
                bool(c, "includeCustomAttributes", true),
                bool(c, "includeSystemAttributes", false),
                bool(c, "includeFolderPaths", true),
                c.getOrDefault("attributePrefix", "documentum_attr_").strip(),
                tracking,
                list(value(c, "auditEvents", "dm_destroy,dm_prune")),
                bool(c, "incrementalEnabled", true),
                value(c, "modifiedAfter", ""),
                zone(value(c, "serverTimeZone", "auto")),
                Math.max(0, integer(c, "clockSkewSeconds", 300)),
                Math.clamp(integer(c, "batchSize", 100), 1, MAX_PAGE_SIZE),
                Math.max(1, integer(c, "parallelism", 4)),
                Duration.ofSeconds(Math.max(1, integer(c, "timeoutSeconds", 30))),
                Math.max(0, integer(c, "maxRetries", 3)),
                Math.max(0, integer(c, "maxDocuments", 0)),
                value(c, "stateDirectory", "data/documentum-state"));
    }

    /**
     * Reads {@code spring.opencrawling.connector.documentum.*}. List properties may be comma-separated strings or YAML lists
     * ({@code object-types[0]}, …).
     */
    public static DocumentumProperties fromEnvironment(PropertyResolver environment) {
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
            problems.add("url must be the Documentum REST Services context, e.g. https://dctm.example.com/dctm-rest.");
        }
        if (repository == null) {
            problems.add("repository-name (the Documentum repository / docbase) must be configured.");
        } else if (!repository.matches("[\\w.-]+")) {
            problems.add("repository-name '" + repository + "' is not a repository name.");
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
            case "kerberos" -> problems.add("auth-type 'kerberos' is not supported: the connector's HTTP client cannot "
                    + "negotiate SPNEGO. Use 'basic' (a login ticket works as the password) or 'oauth2' (OTDS).");
            default -> problems.add("auth-type '" + authType + "' is not supported; use 'basic', 'bearer' or 'oauth2'.");
        }
        if (objectTypes.isEmpty()) {
            problems.add("object-types must name at least one type, e.g. dm_document.");
        }
        for (String type : objectTypes) {
            if (!NAME.matcher(type).matches()) {
                problems.add("object type '" + type + "' is not a type name.");
            }
        }
        if (dqlQuery != null && parsedDql().isEmpty()) {
            problems.add("dql-query must be a DQL statement 'SELECT ... FROM <type> [WHERE ...]'.");
        }
        if (crawlMode == CrawlMode.FOLDER && (rootCabinetPath == null || !rootCabinetPath.startsWith("/")
                || rootCabinetPath.equals("/"))) {
            problems.add("root-cabinet-path must be a cabinet or folder path such as /Regulatory/Submissions in folder "
                    + "crawl mode.");
        }
        if (preferRendition != null && !preferRendition.isEmpty() && !NAME.matcher(preferRendition).matches()) {
            problems.add("prefer-rendition '" + preferRendition + "' is not a format name such as pdf.");
        }
        for (String event : auditEvents) {
            if (!NAME.matcher(event).matches()) {
                problems.add("audit event '" + event + "' is not an event name such as dm_destroy.");
            }
        }
        if (deletionTracking == DeletionTracking.AUDIT && auditEvents.isEmpty()) {
            problems.add("audit-events must name at least one event (default dm_destroy, dm_prune) for audit tracking.");
        }
        if (modifiedAfter != null && !modifiedAfter.isBlank() && modifiedAfterInstant().isEmpty()) {
            problems.add("modified-after '" + modifiedAfter + "' is not an ISO date (2026-01-01) or timestamp "
                    + "(2026-01-01T00:00:00Z).");
        }
        return problems;
    }

    /** The type and WHERE condition of {@code dqlQuery}, when configured and well-formed: [type, where-or-null]. */
    public Optional<String[]> parsedDql() {
        if (dqlQuery == null) {
            return Optional.empty();
        }
        Matcher m = DQL.matcher(dqlQuery);
        return m.matches() ? Optional.of(new String[]{m.group("type").toLowerCase(Locale.ROOT),
                m.group("where") == null ? null : m.group("where").strip()}) : Optional.empty();
    }

    /** The types queried: the {@code dqlQuery} type, or {@code objectTypes}. */
    public List<String> types() {
        return parsedDql().map(d -> List.of(d[0])).orElse(objectTypes);
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

    /** The REST resource of the repository: {@code <url>/repositories/<repository>}. */
    public String repositoryUrl() {
        return url + "/repositories/" + repository;
    }

    /** The Spring property name of a key: kebab-case, with the OAuth2 keys nested as in issue #124 ({@code oauth2.token-url}). */
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
            case "latest_major", "major", "released" -> VersionMode.LATEST_MAJOR;
            case "all", "all_versions" -> VersionMode.ALL;
            default -> VersionMode.CURRENT;
        };
    }

    private static DeletionTracking deletionTracking(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "audit", "audittrail", "events" -> DeletionTracking.AUDIT;
            case "none", "off", "false" -> DeletionTracking.NONE;
            default -> DeletionTracking.RECONCILE;
        };
    }

    /** {@code auto} (null) or a zone id / offset; an invalid value falls back to automatic. */
    private static ZoneId zone(String value) {
        if (value == null || value.equalsIgnoreCase("auto")) {
            return null;
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) { // never "auto" in disguise: a wrong zone would shift every date literal
            throw new IllegalArgumentException("server-time-zone '" + value + "' is not a time zone: use auto, an offset "
                    + "such as +02:00, or a region such as Europe/Berlin (which follows daylight saving).", e);
        }
    }

    private static String value(Map<String, String> config, String key, String fallback) {
        String value = config.get(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String stripSlashes(String url) {
        String u = url;
        while (u.length() > 1 && u.endsWith("/")) {
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
        // never log passwords, client secrets or access tokens
        return "DocumentumProperties[url=" + url + ", repository=" + repository + ", authType=" + authType + ", user="
                + username + ", oauth2TokenUrl=" + oauth2TokenUrl + ", oauth2ClientId=" + oauth2ClientId + ", crawlMode="
                + crawlMode + ", objectTypes=" + objectTypes + ", where=" + where + ", dqlQuery=" + dqlQuery
                + ", rootCabinetPath=" + rootCabinetPath + ", includeSubfolders=" + includeSubfolders + ", versionMode="
                + versionMode + ", includeContentStream=" + includeContentStream + ", preferRendition=" + preferRendition
                + ", includeAcls=" + includeAcls + ", principalFormat=" + principalFormat + ", requiredGroupDocuments="
                + requiredGroupDocuments + ", deletionTracking=" + deletionTracking + ", incrementalEnabled="
                + incrementalEnabled + ", modifiedAfter=" + modifiedAfter + ", serverTimeZone="
                + (serverTimeZone == null ? "auto" : serverTimeZone) + ", batchSize=" + batchSize + ", parallelism="
                + parallelism + ", maxDocuments=" + maxDocuments + "]";
    }
}
