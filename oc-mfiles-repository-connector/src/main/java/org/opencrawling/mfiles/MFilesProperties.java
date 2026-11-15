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
package org.opencrawling.mfiles;

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

/**
 * Settings of the M-Files repository connector.
 *
 * <p>Two sources, same keys: a connector configuration ({@code connectors.json}, camelCase keys such as {@code vaultGuid})
 * via {@link #fromConfiguration(Map)}, or Spring properties under {@code spring.opencrawling.connector.mfiles.*} (kebab-case,
 * e.g. {@code vault-guid}; YAML lists allowed) via {@link #fromEnvironment(PropertyResolver)}.
 *
 * @param url                  MFWS base URL including {@code /REST}, e.g. {@code https://mfiles.example.com/REST}
 * @param vaultGuid            the vault to crawl, with or without braces
 * @param authType             {@code token}: user name + password exchanged for a vault-scoped authentication token
 *                             ({@code POST /server/authenticationtokens}, sent as {@code X-Authentication}); {@code basic}:
 *                             the credentials on every request ({@code X-Username}/{@code X-Password}); {@code oauth2}: a
 *                             bearer access token, given directly or obtained with the client-credentials grant
 * @param domain               Windows domain of the account ({@code Domain} of the MFWS {@code Authentication}); empty for
 *                             M-Files users
 * @param crawlMode            {@code SEARCH} (MFWS search), {@code VIEW} (an M-Files view), {@code ALL_OBJECTS} (every
 *                             object of {@code objectType}) or {@code VAF} (a change feed served by a Vault Application
 *                             Framework app)
 * @param objectType           object type to crawl ({@code 0} = Document); negative crawls every object type
 * @param classIds             classes to keep; empty keeps every class
 * @param viewId               the view crawled in {@code VIEW} mode: an id ({@code 101}) or a view path ({@code v101/L5})
 * @param searchQuery          MFWS search conditions appended to {@code /objects}, e.g. {@code q=contract} or
 *                             {@code p1020=5}; text without {@code =} is a quick search ({@code q=})
 * @param versionMode          {@code LATEST_ONLY} or {@code ALL_VERSIONS}
 * @param includeFiles         download the object files (one OIS document per file of a multi-file document)
 * @param maxFileSizeBytes     files larger than this are indexed by metadata only
 * @param includeProperties    emit every property value as {@code <propertyPrefix><name>}
 * @param includeAcls          read object permissions; without, documents are public
 * @param propertyPrefix       prefix for property metadata keys ({@code mfiles_prop_customer})
 * @param fallbackPrincipals   identities granted read when no permissions are readable; empty means nobody (deny by
 *                             default)
 * @param publicGroupIds       M-Files user group ids treated as everybody (mapped to {@code public})
 * @param principalFormat      {@code TOKEN}: identities as {@code mfiles:user:<id>} / {@code mfiles:group:<id>} (the
 *                             ManifoldCF M-Files connector's tokens); {@code NAME}: resolved through the users and user
 *                             groups value lists, falling back to the token when a name is unknown
 * @param usersValueList       value list holding the users ({@code NAME} format); built-in list 6, verified on a real vault
 * @param userGroupsValueList  value list holding the user groups ({@code NAME} format); built-in list 16, verified
 * @param qualifyPrincipals    prefix every identity with the vault GUID; M-Files user and group ids are per vault
 * @param emitDeletes          search deleted objects too ({@code d=include}) and send DELETE tombstones for them
 * @param incrementalEnabled   keep a crawl state between runs: search from the last watermark, skip unchanged objects,
 *                             and tombstone the ids an object no longer produces
 * @param modifiedAfter        optional lower bound on the last-modified time (ISO date or timestamp)
 * @param clockSkewSeconds     the incremental watermark is set this far before the scan start
 * @param batchSize            maximum results per MFWS search request ({@code limit}); a time window with more results is
 *                             split
 * @param parallelism          objects processed at the same time
 * @param maxDocuments         stop after this many objects (0 = no limit); meant for test runs against shared vaults
 * @param stateDirectory       where the incremental crawl state is kept (one JSON file per vault and crawl scope)
 * @param vafBasePath          VAF mode: base path of the vault application's endpoints, relative to {@code url}
 * @param vafChangeFeedPath    VAF mode: change feed path ({@code ?since=<cursor>&pageSize=<n>})
 * @param vafNormalizedPath    VAF mode: normalized object path; {@code {objType}}, {@code {objId}} and {@code {version}}
 *                             are replaced
 */
public record MFilesProperties(
        String url,
        String vaultGuid,
        String authType,
        String username,
        String password,
        String domain,
        String oauth2TokenUrl,
        String oauth2ClientId,
        String oauth2ClientSecret,
        String oauth2Scope,
        String oauth2AccessToken,
        CrawlMode crawlMode,
        int objectType,
        List<Integer> classIds,
        String viewId,
        String searchQuery,
        VersionMode versionMode,
        boolean includeFiles,
        long maxFileSizeBytes,
        boolean includeProperties,
        boolean includeAcls,
        String propertyPrefix,
        List<String> fallbackPrincipals,
        List<Integer> publicGroupIds,
        PrincipalFormat principalFormat,
        int usersValueList,
        int userGroupsValueList,
        boolean qualifyPrincipals,
        boolean emitDeletes,
        boolean incrementalEnabled,
        String modifiedAfter,
        int clockSkewSeconds,
        int batchSize,
        int parallelism,
        Duration timeout,
        int maxRetries,
        int maxDocuments,
        String stateDirectory,
        String vafBasePath,
        String vafChangeFeedPath,
        String vafNormalizedPath) {

    public enum CrawlMode { SEARCH, VIEW, ALL_OBJECTS, VAF }

    public enum VersionMode { LATEST_ONLY, ALL_VERSIONS }

    public enum PrincipalFormat { TOKEN, NAME }

    public enum LoginMode { TOKEN, HEADERS, BEARER }

    public static final String SPRING_PREFIX = "spring.opencrawling.connector.mfiles.";
    public static final String DEFAULT_URL = "http://localhost/REST";

    /** Every configuration key (camelCase); the Spring property is {@link #SPRING_PREFIX} + {@link #springName(String)}. */
    static final List<String> KEYS = List.of("url", "vaultGuid", "authType", "username", "password", "domain",
            "oauth2TokenUrl", "oauth2ClientId", "oauth2ClientSecret", "oauth2Scope", "oauth2AccessToken", "crawlMode",
            "objectType", "classIds", "viewId", "searchQuery", "versionMode", "includeFiles", "maxFileSizeBytes",
            "includeProperties", "includeAcls", "propertyPrefix", "fallbackPrincipals", "publicGroupIds", "principalFormat",
            "usersValueList", "userGroupsValueList", "qualifyPrincipals", "emitDeletes", "incrementalEnabled", "modifiedAfter",
            "clockSkewSeconds", "batchSize", "parallelism", "timeoutSeconds", "maxRetries", "maxDocuments", "stateDirectory",
            "vafBasePath", "vafChangeFeedPath", "vafNormalizedPath");

    public static MFilesProperties fromConfiguration(Map<String, String> config) {
        Map<String, String> c = config == null ? Map.of() : config;
        return new MFilesProperties(
                stripSlashes(value(c, "url", DEFAULT_URL)),
                value(c, "vaultGuid", null),
                value(c, "authType", "token").toLowerCase(Locale.ROOT),
                value(c, "username", null),
                value(c, "password", null),
                value(c, "domain", null),
                value(c, "oauth2TokenUrl", null),
                value(c, "oauth2ClientId", null),
                value(c, "oauth2ClientSecret", null),
                value(c, "oauth2Scope", null),
                value(c, "oauth2AccessToken", null),
                crawlMode(value(c, "crawlMode", "search")),
                integer(c, "objectType", 0),
                integers(value(c, "classIds", "")),
                value(c, "viewId", null),
                value(c, "searchQuery", ""),
                value(c, "versionMode", "latest_only").toLowerCase(Locale.ROOT).startsWith("all")
                        ? VersionMode.ALL_VERSIONS : VersionMode.LATEST_ONLY,
                bool(c, "includeFiles", true),
                Math.max(0, longValue(c, "maxFileSizeBytes", 52_428_800L)),
                bool(c, "includeProperties", true),
                bool(c, "includeAcls", true),
                c.getOrDefault("propertyPrefix", "mfiles_prop_").strip(),
                list(value(c, "fallbackPrincipals", "")),
                integers(value(c, "publicGroupIds", "")),
                "name".equalsIgnoreCase(value(c, "principalFormat", "token")) ? PrincipalFormat.NAME : PrincipalFormat.TOKEN,
                integer(c, "usersValueList", 6),
                integer(c, "userGroupsValueList", 16),
                bool(c, "qualifyPrincipals", false),
                bool(c, "emitDeletes", true),
                bool(c, "incrementalEnabled", false),
                value(c, "modifiedAfter", ""),
                Math.max(0, integer(c, "clockSkewSeconds", 300)),
                Math.max(1, integer(c, "batchSize", 500)),
                Math.max(1, integer(c, "parallelism", 4)),
                Duration.ofSeconds(Math.max(1, integer(c, "timeoutSeconds", 30))),
                Math.max(0, integer(c, "maxRetries", 3)),
                Math.max(0, integer(c, "maxDocuments", 0)),
                value(c, "stateDirectory", "data/mfiles-state"),
                value(c, "vafBasePath", null),
                value(c, "vafChangeFeedPath", "/changes"),
                value(c, "vafNormalizedPath", "/object/{objType}/{objId}/{version}/normalized"));
    }

    /**
     * Reads {@code spring.opencrawling.connector.mfiles.*}. List properties may be comma-separated strings or YAML lists
     * ({@code class-ids[0]}, {@code class-ids[1]}, …).
     */
    public static MFilesProperties fromEnvironment(PropertyResolver environment) {
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
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            problems.add("url must be the MFWS base URL, e.g. https://mfiles.example.com/REST.");
        }
        if (vaultGuid == null) {
            problems.add("vault-guid must be configured; without it M-Files issues a server-level token that cannot read "
                    + "any vault.");
        }
        switch (authType) {
            case "token", "basic" -> {
                // an empty user name gives an anonymous token that fails every vault call with HTTP 403
                if (username == null || password == null) {
                    problems.add("username and password must be configured for auth-type '" + authType + "'.");
                }
            }
            case "oauth2" -> {
                if (oauth2AccessToken == null && (oauth2TokenUrl == null || oauth2ClientId == null || oauth2ClientSecret == null)) {
                    problems.add("auth-type 'oauth2' needs oauth2.access-token, or oauth2.token-url, oauth2.client-id and "
                            + "oauth2.client-secret for the client-credentials grant.");
                }
            }
            default -> problems.add("auth-type '" + authType + "' is not supported; use 'token' (user name and password "
                    + "exchanged for an authentication token), 'basic' (credentials on every request) or 'oauth2'.");
        }
        if (crawlMode == CrawlMode.VIEW && viewId == null) {
            problems.add("view-id is required in view crawl mode.");
        }
        if (crawlMode == CrawlMode.VAF && vafBasePath == null) {
            problems.add("vaf-base-path (the vault application's endpoint path, e.g. /vaf/<app>) is required in vaf "
                    + "crawl mode.");
        }
        if (incrementalEnabled && crawlMode == CrawlMode.VIEW) {
            problems.add("incremental crawling is supported in search, all_objects and vaf crawl modes only.");
        }
        if (modifiedAfter != null && !modifiedAfter.isBlank() && modifiedAfterInstant().isEmpty()) {
            problems.add("modified-after '" + modifiedAfter + "' is not an ISO date (2026-01-01) or timestamp "
                    + "(2026-01-01T00:00:00Z).");
        }
        return problems;
    }

    /** How requests authenticate: {@code token} → {@code X-Authentication}, {@code basic} → credential headers, {@code oauth2} → bearer. */
    public LoginMode loginMode() {
        return switch (authType) {
            case "basic" -> LoginMode.HEADERS;
            case "oauth2" -> LoginMode.BEARER;
            default -> LoginMode.TOKEN;
        };
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

    /** The vault GUID without braces, as used in document ids (braces are not valid in every store's document keys). */
    public String vaultKey() {
        return stripBraces(vaultGuid);
    }

    static String stripBraces(String guid) {
        if (guid == null) {
            return null;
        }
        String g = guid.strip();
        if (g.startsWith("{") && g.endsWith("}")) {
            g = g.substring(1, g.length() - 1);
        }
        return g;
    }

    /** The Spring property name of a key: kebab-case, with the OAuth2 keys nested as in issue #123 ({@code oauth2.token-url}). */
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

    private static CrawlMode crawlMode(String value) {
        return switch (value.toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "view" -> CrawlMode.VIEW;
            case "all_objects", "all" -> CrawlMode.ALL_OBJECTS;
            case "vaf", "changes" -> CrawlMode.VAF;
            default -> CrawlMode.SEARCH;
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

    private static List<Integer> integers(String value) {
        List<Integer> ids = new ArrayList<>();
        for (String item : list(value)) {
            try {
                ids.add(Integer.parseInt(item));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + item + "' is not a numeric M-Files id.", e);
            }
        }
        return List.copyOf(ids);
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
        return "MFilesProperties[url=" + url + ", vault=" + vaultGuid + ", authType=" + authType + ", user=" + username
                + ", domain=" + domain + ", oauth2TokenUrl=" + oauth2TokenUrl + ", oauth2ClientId=" + oauth2ClientId
                + ", crawlMode=" + crawlMode + ", objectType=" + objectType + ", classIds=" + classIds + ", viewId=" + viewId
                + ", searchQuery=" + searchQuery + ", versionMode=" + versionMode + ", includeFiles=" + includeFiles
                + ", includeProperties=" + includeProperties + ", includeAcls=" + includeAcls + ", principalFormat="
                + principalFormat + ", emitDeletes=" + emitDeletes + ", incrementalEnabled=" + incrementalEnabled
                + ", modifiedAfter=" + modifiedAfter + ", batchSize=" + batchSize + ", parallelism=" + parallelism
                + ", maxDocuments=" + maxDocuments + ", vafBasePath=" + vafBasePath + "]";
    }
}
