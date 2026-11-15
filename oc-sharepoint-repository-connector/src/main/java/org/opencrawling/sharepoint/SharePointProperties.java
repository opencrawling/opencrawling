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
package org.opencrawling.sharepoint;

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
 * Settings of the SharePoint repository connector.
 *
 * <p>Two sources, same keys: a connector configuration ({@code connectors.json}, camelCase keys such as {@code tenantId})
 * via {@link #fromConfiguration(Map)}, or Spring properties under {@code spring.opencrawling.connector.sharepoint.*}
 * (kebab-case, e.g. {@code tenant-id}; YAML lists allowed) via {@link #fromEnvironment(PropertyResolver)}.
 *
 * @param tenantId                  Microsoft Entra ID tenant (GUID or domain)
 * @param clientId                  application (client) id of the app registration
 * @param clientSecret              client secret of the app registration (client-credentials grant)
 * @param authorityUrl              Entra ID authority; {@code https://login.microsoftonline.com} unless a sovereign cloud
 * @param graphUrl                  Microsoft Graph base including the version; {@code https://graph.microsoft.com/v1.0}
 * @param siteUrl                   the site to crawl, e.g. {@code https://contoso.sharepoint.com/sites/engineering}
 * @param siteId                    the site id instead of the URL ({@code host,siteCollectionId,webId})
 * @param driveNames                document libraries to crawl by name; empty crawls every document library of the site
 * @param driveId                   one document library by id instead of names
 * @param crawlMode                 {@code DELTA} (Graph delta query, incremental) or {@code TREE} (folder traversal)
 * @param rootFolderPath            tree mode: the folder to start from, relative to the library root
 * @param includeSubfolders         tree mode: descend into sub-folders
 * @param includeContentStream      download file content
 * @param maxFileSizeBytes          larger files are indexed by metadata only
 * @param includeCustomFields       emit the library's list item columns as {@code <fieldPrefix><InternalName>}
 * @param fieldPrefix               prefix of custom field keys
 * @param includeAcls               read item permissions; without, documents are public
 * @param userIdentity              {@code EMAIL}: users by e-mail / UPN (lower case), {@code ID}: by Entra object id
 * @param fallbackPrincipals        identities granted read when no permissions are readable; empty means nobody
 * @param everyoneIdentity          identity for SharePoint's "Everyone" claim; empty grants nothing through it
 * @param everyoneExceptExternalIdentity identity for "Everyone except external users"; empty grants nothing through it
 * @param organizationLinkIdentity  identity for organization-wide sharing links; empty grants nothing through them
 * @param anonymousLinkIdentity     identity for anonymous ("anyone with the link") sharing links; empty grants nothing
 * @param incremental               keep a crawl state per library: the delta link, and per item its eTag and ACL
 *                                  fingerprint, so unchanged items are skipped and vanished ones get tombstones
 * @param stateDirectory            where the crawl state is kept (one JSON file per library)
 * @param batchSize                 page size ({@code $top}) of children and delta requests
 * @param parallelism               items processed at the same time
 * @param timeout                   HTTP timeout
 * @param maxRetries                retries on 429, 503, 504 and connection failures
 * @param initialBackoff            first back-off when a throttled response carries no {@code Retry-After}
 * @param maxDocuments              stop after this many files (0 = no limit); meant for test runs
 */
public record SharePointProperties(
        String tenantId,
        String clientId,
        String clientSecret,
        String authorityUrl,
        String graphUrl,
        String siteUrl,
        String siteId,
        List<String> driveNames,
        String driveId,
        CrawlMode crawlMode,
        String rootFolderPath,
        boolean includeSubfolders,
        boolean includeContentStream,
        long maxFileSizeBytes,
        boolean includeCustomFields,
        String fieldPrefix,
        boolean includeAcls,
        UserIdentity userIdentity,
        List<String> fallbackPrincipals,
        String everyoneIdentity,
        String everyoneExceptExternalIdentity,
        String organizationLinkIdentity,
        String anonymousLinkIdentity,
        boolean incremental,
        String stateDirectory,
        int batchSize,
        int parallelism,
        Duration timeout,
        int maxRetries,
        Duration initialBackoff,
        int maxDocuments) {

    public enum CrawlMode { DELTA, TREE }

    public enum UserIdentity { EMAIL, ID }

    public static final String SPRING_PREFIX = "spring.opencrawling.connector.sharepoint.";
    public static final String DEFAULT_AUTHORITY = "https://login.microsoftonline.com";
    public static final String DEFAULT_GRAPH = "https://graph.microsoft.com/v1.0";

    /** Every configuration key (camelCase); the Spring property is {@link #SPRING_PREFIX} + {@link #springName(String)}. */
    static final List<String> KEYS = List.of("tenantId", "clientId", "clientSecret", "authorityUrl", "graphUrl", "siteUrl",
            "siteId", "driveName", "driveNames", "driveId", "crawlMode", "rootFolderPath", "includeSubfolders",
            "includeContentStream", "maxFileSizeBytes", "includeCustomFields", "fieldPrefix", "includeAcls", "userIdentity",
            "fallbackPrincipals", "everyoneIdentity", "everyoneExceptExternalIdentity", "organizationLinkIdentity",
            "anonymousLinkIdentity", "incremental", "stateDirectory", "batchSize", "parallelism", "timeoutSeconds",
            "maxRetries", "initialBackoffMs", "maxDocuments");

    public static SharePointProperties fromConfiguration(Map<String, String> config) {
        Map<String, String> c = config == null ? Map.of() : config;
        List<String> drives = new ArrayList<>(list(value(c, "driveNames", "")));
        // the issue's single drive-name is accepted as well
        list(value(c, "driveName", "")).stream().filter(d -> !drives.contains(d)).forEach(drives::add);
        return new SharePointProperties(
                value(c, "tenantId", null),
                value(c, "clientId", null),
                value(c, "clientSecret", null),
                stripSlashes(value(c, "authorityUrl", DEFAULT_AUTHORITY)),
                stripSlashes(value(c, "graphUrl", DEFAULT_GRAPH)),
                stripSlashes(value(c, "siteUrl", null)),
                value(c, "siteId", null),
                List.copyOf(drives),
                value(c, "driveId", null),
                "tree".equalsIgnoreCase(value(c, "crawlMode", "delta")) ? CrawlMode.TREE : CrawlMode.DELTA,
                value(c, "rootFolderPath", "/"),
                bool(c, "includeSubfolders", true),
                bool(c, "includeContentStream", true),
                Math.max(0, longValue(c, "maxFileSizeBytes", 52_428_800L)),
                bool(c, "includeCustomFields", true),
                c.getOrDefault("fieldPrefix", "sharepoint_field_").strip(),
                bool(c, "includeAcls", true),
                "id".equalsIgnoreCase(value(c, "userIdentity", "email")) ? UserIdentity.ID : UserIdentity.EMAIL,
                list(value(c, "fallbackPrincipals", "")),
                value(c, "everyoneIdentity", ""),
                value(c, "everyoneExceptExternalIdentity", ""),
                value(c, "organizationLinkIdentity", ""),
                value(c, "anonymousLinkIdentity", ""),
                bool(c, "incremental", true),
                value(c, "stateDirectory", "data/sharepoint-state"),
                Math.max(1, Math.min(999, integer(c, "batchSize", 100))),
                Math.max(1, integer(c, "parallelism", 4)),
                Duration.ofSeconds(Math.max(1, integer(c, "timeoutSeconds", 30))),
                Math.max(0, integer(c, "maxRetries", 5)),
                Duration.ofMillis(Math.max(1, integer(c, "initialBackoffMs", 1000))),
                Math.max(0, integer(c, "maxDocuments", 0)));
    }

    /**
     * Reads {@code spring.opencrawling.connector.sharepoint.*}. List properties may be comma-separated strings or YAML lists
     * ({@code drive-names[0]}, {@code drive-names[1]}, …).
     */
    public static SharePointProperties fromEnvironment(PropertyResolver environment) {
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
        if (tenantId == null) {
            problems.add("tenant-id (the Microsoft Entra ID tenant) must be configured.");
        }
        if (clientId == null || clientSecret == null) {
            problems.add("client-id and client-secret of the app registration must be configured.");
        }
        if (siteUrl == null && siteId == null) {
            problems.add("site-url (e.g. https://contoso.sharepoint.com/sites/engineering) or site-id must be configured.");
        } else if (siteId == null && hostname() == null) {
            problems.add("site-url '" + siteUrl + "' is not an https URL of a SharePoint site.");
        }
        if (!graphUrl.startsWith("https://") && !graphUrl.startsWith("http://")) {
            problems.add("graph-url must be an http(s) URL.");
        }
        return problems;
    }

    /** The host of {@code siteUrl} ({@code contoso.sharepoint.com}), or null. */
    public String hostname() {
        if (siteUrl == null) {
            return null;
        }
        try {
            URI uri = URI.create(siteUrl);
            return uri.getScheme() != null && uri.getScheme().startsWith("http") ? uri.getHost() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The server-relative path of {@code siteUrl} ({@code /sites/engineering}), {@code ""} for the root site. */
    public String sitePath() {
        if (siteUrl == null) {
            return "";
        }
        String path = URI.create(siteUrl).getRawPath();
        return path == null ? "" : stripSlashes(path);
    }

    /** The Spring property name of a key: kebab-case. */
    static String springName(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String value(Map<String, String> config, String key, String fallback) {
        String value = config.get(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String stripSlashes(String url) {
        if (url == null) {
            return null;
        }
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
        // never log the client secret
        return "SharePointProperties[tenant=" + tenantId + ", clientId=" + clientId + ", graph=" + graphUrl + ", site="
                + (siteId != null ? siteId : siteUrl) + ", drives=" + (driveId != null ? driveId : driveNames) + ", crawlMode="
                + crawlMode + ", rootFolderPath=" + rootFolderPath + ", includeContentStream=" + includeContentStream
                + ", includeCustomFields=" + includeCustomFields + ", includeAcls=" + includeAcls + ", userIdentity="
                + userIdentity + ", incremental=" + incremental + ", batchSize=" + batchSize + ", parallelism=" + parallelism
                + ", maxDocuments=" + maxDocuments + "]";
    }
}
