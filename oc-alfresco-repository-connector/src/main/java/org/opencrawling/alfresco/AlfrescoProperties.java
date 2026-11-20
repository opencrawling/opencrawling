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
package org.opencrawling.alfresco;

import org.springframework.core.env.PropertyResolver;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Configuration settings for the Alfresco Content Services (ACS) repository connector (#425).
 *
 * <p>Binds from either {@code connectors.json} / Admin UI configuration maps (camelCase or kebab-case keys) via
 * {@link #fromConfiguration(Map)}, or Spring Boot properties under {@code spring.opencrawling.connector.alfresco.*}
 * via {@link #fromEnvironment(PropertyResolver)}.
 */
public record AlfrescoProperties(
        String url,
        AuthType authType,
        String username,
        String password,
        String oauth2TokenUrl,
        String oauth2ClientId,
        String oauth2ClientSecret,
        String oauth2Scope,
        String oauth2AccessToken,
        String ticket,
        AlfrescoCrawlMode crawlMode,
        String rootFolderPath,
        String rootFolderId,
        String siteId,
        List<String> siteIds,
        boolean includeSubfolders,
        Set<String> excludedFolders,
        String searchQuery,
        String queryLanguage,
        List<String> nodeTypes,
        List<String> includedAspects,
        List<String> excludedAspects,
        boolean excludeWorkingCopies,
        List<String> includedTags,
        List<String> excludedTags,
        String createdAfter,
        String modifiedAfter,
        List<String> excludedNamePatterns,
        Set<String> mimeTypeFilter,
        int maxDocuments,
        VersionMode versionMode,
        boolean includeContentStream,
        long maxContentSizeBytes,
        String preferRendition,
        boolean includeTags,
        boolean includeComments,
        boolean includeSystemProperties,
        String propertyPrefix,
        String webAppUrl,
        boolean includeAcls,
        boolean everyonePublic,
        String everyoneIdentity,
        List<String> fallbackPrincipals,
        PrincipalFormat principalFormat,
        boolean incrementalEnabled,
        String stateFile,
        DeletionTracking deletionTracking,
        int clockSkewSeconds,
        Duration fullScanInterval,
        String fullScanWindow,
        ZoneId fullScanTimezone,
        String auditApplication,
        int batchSize,
        int parallelism,
        Duration timeout,
        int maxRetries,
        long initialBackoffMs
) {

    public enum AuthType {
        BASIC,
        TICKET,
        BEARER,
        OAUTH2
    }

    public enum VersionMode {
        /** Index only the current tip version under {@code <nodeId>}. */
        CURRENT,
        /** Index the latest major version ({@code MAJOR} / {@code n.0}), falling back to current when unversioned. */
        LATEST_MAJOR,
        /** Index every version under {@code <nodeId>;v=<versionId>}, tombstoning purged versions. */
        ALL
    }

    public enum DeletionTracking {
        /** Compare full listings with state, plus Trashcan polling during {@code SEARCH_DELTA} incremental runs. */
        RECONCILE,
        /** Poll Alfresco Trashcan ({@code GET /deleted-nodes}) for archived deletions. */
        TRASHCAN,
        /** Poll Alfresco Audit API ({@code /audit-applications/<app>/audit-entries}) for deletions and ACL changes. */
        AUDIT,
        /** Do not emit deletion tombstones. */
        NONE
    }

    public enum PrincipalFormat {
        /** Keep Alfresco authority IDs as returned (e.g. {@code GROUP_FINANCE}, {@code alice}). */
        RAW,
        /** Lowercase identities and strip leading {@code GROUP_} / {@code ROLE_} prefixes for OIDC/OpenFGA alignment. */
        NORMALIZED
    }

    public static final String SPRING_PREFIX = "spring.opencrawling.connector.alfresco.";
    public static final String DEFAULT_URL = "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1";
    public static final int MAX_PAGE_SIZE = 1000;
    static final int RENDER_VERSION = 1;

    static final List<String> KEYS = List.of(
            "url", "baseUrl", "authType", "username", "password",
            "oauth2TokenUrl", "oauth2ClientId", "oauth2ClientSecret", "oauth2Scope", "oauth2AccessToken", "accessToken",
            "ticket",
            "crawlMode", "rootFolderPath", "rootFolderId", "siteId", "siteIds", "includeSubfolders", "excludedFolders",
            "searchQuery", "queryLanguage",
            "nodeTypes", "includedAspects", "excludedAspects", "excludeWorkingCopies",
            "includedTags", "excludedTags", "createdAfter", "modifiedAfter", "excludedNamePatterns",
            "mimeTypeFilter", "mimetypeFilter", "maxDocuments",
            "versionMode", "includeContentStream", "maxContentSizeBytes", "preferRendition",
            "includeTags", "includeComments", "includeSystemProperties", "propertyPrefix", "webAppUrl",
            "includeAcls", "everyonePublic", "everyoneIdentity", "fallbackPrincipals", "principalFormat",
            "incrementalEnabled", "stateFile", "stateDirectory", "deletionTracking", "clockSkewSeconds",
            "fullScanInterval", "fullScanWindow", "fullScanTimezone", "auditApplication",
            "batchSize", "parallelism", "timeoutSeconds", "maxRetries", "initialBackoffMs"
    );

    public static AlfrescoProperties fromConfiguration(Map<String, String> config) {
        Map<String, String> c = normalizeKeys(config);

        String url = stripTrailingSlashes(firstNonBlank(c.get("url"), c.get("baseUrl"), DEFAULT_URL));
        AuthType authType = parseEnum(c.get("authType"), AuthType.class, AuthType.BASIC);
        String username = c.containsKey("username") ? c.get("username") : "admin";
        String password = c.containsKey("password") ? c.get("password") : "admin";
        String oauth2TokenUrl = blankToNull(c.get("oauth2TokenUrl"));
        String oauth2ClientId = blankToNull(c.get("oauth2ClientId"));
        String oauth2ClientSecret = blankToNull(c.get("oauth2ClientSecret"));
        String oauth2Scope = blankToNull(c.get("oauth2Scope"));
        String oauth2AccessToken = firstNonBlank(c.get("oauth2AccessToken"), c.get("accessToken"), null);
        String ticket = blankToNull(c.get("ticket"));

        AlfrescoCrawlMode crawlMode = AlfrescoCrawlMode.fromString(c.get("crawlMode"));
        String rootFolderPath = firstNonBlank(c.get("rootFolderPath"), "/", "/");
        String rootFolderId = c.getOrDefault("rootFolderId", "").trim();
        String siteId = c.getOrDefault("siteId", "").trim();
        LinkedHashSet<String> siteSet = new LinkedHashSet<>();
        if (!siteId.isEmpty()) {
            siteSet.add(siteId);
        }
        siteSet.addAll(list(c.get("siteIds")));
        List<String> siteIds = List.copyOf(siteSet);
        boolean includeSubfolders = bool(c, "includeSubfolders", true);
        Set<String> excludedFolders = c.containsKey("excludedFolders")
                ? Set.copyOf(list(c.get("excludedFolders")))
                : Set.of("Data Dictionary");
        String searchQuery = firstNonBlank(c.get("searchQuery"), "TYPE:'cm:content'", "TYPE:'cm:content'");
        String queryLanguage = firstNonBlank(c.get("queryLanguage"), "afts", "afts");

        List<String> nodeTypes = list(c.get("nodeTypes"));
        List<String> includedAspects = list(c.get("includedAspects"));
        List<String> excludedAspects = list(c.get("excludedAspects"));
        boolean excludeWorkingCopies = bool(c, "excludeWorkingCopies", true);
        List<String> includedTags = list(c.get("includedTags"));
        List<String> excludedTags = list(c.get("excludedTags"));
        String createdAfter = blankToNull(c.get("createdAfter"));
        String modifiedAfter = blankToNull(c.get("modifiedAfter"));
        List<String> excludedNamePatterns = patternList(c.get("excludedNamePatterns"));
        String rawMime = c.containsKey("mimeTypeFilter") ? c.get("mimeTypeFilter") : c.get("mimetypeFilter");
        Set<String> mimeTypeFilter = Set.copyOf(list(rawMime));
        int maxDocuments = Math.max(0, integer(c, "maxDocuments", 0));

        VersionMode versionMode = parseEnum(c.get("versionMode"), VersionMode.class, VersionMode.CURRENT);
        boolean includeContentStream = bool(c, "includeContentStream", true);
        long maxContentSizeBytes = Math.max(0L, longValue(c, "maxContentSizeBytes", 52_428_800L));
        String preferRendition = c.getOrDefault("preferRendition", "").trim();
        boolean includeTags = bool(c, "includeTags", false);
        boolean includeComments = bool(c, "includeComments", false);
        boolean includeSystemProperties = bool(c, "includeSystemProperties", true);
        String propertyPrefix = c.getOrDefault("propertyPrefix", "").trim();
        String webAppUrl = stripTrailingSlashes(blankToNull(c.get("webAppUrl")));

        boolean includeAcls = bool(c, "includeAcls", true);
        boolean everyonePublic = bool(c, "everyonePublic", true);
        String everyoneIdentity = c.getOrDefault("everyoneIdentity", "").trim();
        List<String> fallbackPrincipals = list(c.get("fallbackPrincipals"));
        PrincipalFormat principalFormat = parseEnum(c.get("principalFormat"), PrincipalFormat.class, PrincipalFormat.RAW);

        boolean incrementalEnabled = bool(c, "incrementalEnabled", true);
        String stateFile = firstNonBlank(c.get("stateFile"), c.get("stateDirectory"), "data/alfresco-state");
        DeletionTracking deletionTracking = parseEnum(c.get("deletionTracking"), DeletionTracking.class, DeletionTracking.RECONCILE);
        int clockSkewSeconds = Math.max(0, integer(c, "clockSkewSeconds", 300));
        Duration fullScanInterval = parseDuration(c.get("fullScanInterval"));
        String fullScanWindow = blankToNull(c.get("fullScanWindow"));
        ZoneId fullScanTimezone = parseZone(c.getOrDefault("fullScanTimezone", "UTC"));
        String auditApplication = firstNonBlank(c.get("auditApplication"), "alfresco-access", "alfresco-access");

        int batchSize = Math.clamp(integer(c, "batchSize", 100), 1, MAX_PAGE_SIZE);
        int parallelism = Math.max(1, integer(c, "parallelism", 4));
        Duration timeout = Duration.ofSeconds(Math.max(1, integer(c, "timeoutSeconds", 30)));
        int maxRetries = Math.max(0, integer(c, "maxRetries", 3));
        long initialBackoffMs = Math.max(10L, longValue(c, "initialBackoffMs", 500L));

        return new AlfrescoProperties(
                url, authType, username, password,
                oauth2TokenUrl, oauth2ClientId, oauth2ClientSecret, oauth2Scope, oauth2AccessToken, ticket,
                crawlMode, rootFolderPath, rootFolderId, siteId, siteIds, includeSubfolders, excludedFolders,
                searchQuery, queryLanguage,
                nodeTypes, includedAspects, excludedAspects, excludeWorkingCopies,
                includedTags, excludedTags, createdAfter, modifiedAfter, excludedNamePatterns,
                mimeTypeFilter, maxDocuments,
                versionMode, includeContentStream, maxContentSizeBytes, preferRendition,
                includeTags, includeComments, includeSystemProperties, propertyPrefix, webAppUrl,
                includeAcls, everyonePublic, everyoneIdentity, fallbackPrincipals, principalFormat,
                incrementalEnabled, stateFile, deletionTracking, clockSkewSeconds,
                fullScanInterval, fullScanWindow, fullScanTimezone, auditApplication,
                batchSize, parallelism, timeout, maxRetries, initialBackoffMs
        );
    }

    /**
     * Reads {@code spring.opencrawling.connector.alfresco.*}. List properties may be comma-separated strings or YAML lists.
     */
    public static AlfrescoProperties fromEnvironment(PropertyResolver environment) {
        Map<String, String> config = new HashMap<>();
        if (environment != null) {
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
        }
        return fromConfiguration(config);
    }

    /**
     * Builds settings equivalent to the legacy 17-argument {@link AlfrescoRepositoryConnector} constructor
     * (incremental state disabled and zero retries to preserve deterministic unit test semantics).
     */
    static AlfrescoProperties forLegacyConstructor(
            String url,
            String username,
            String password,
            int batchSize,
            AlfrescoCrawlMode crawlMode,
            String rootFolderPath,
            String rootFolderId,
            String siteId,
            boolean includeSubfolders,
            Set<String> excludedFolders,
            String searchQuery,
            String queryLanguage,
            boolean includeAcls,
            boolean includeContentStream,
            long maxContentSizeBytes,
            Set<String> mimeTypeFilter,
            int timeoutSeconds
    ) {
        String cleanUrl = stripTrailingSlashes(firstNonBlank(url, DEFAULT_URL, DEFAULT_URL));
        String cleanSite = siteId == null ? "" : siteId.trim();
        return new AlfrescoProperties(
                cleanUrl,
                AuthType.BASIC,
                username != null ? username : "admin",
                password != null ? password : "admin",
                null, null, null, null, null, null,
                crawlMode != null ? crawlMode : AlfrescoCrawlMode.FOLDER,
                firstNonBlank(rootFolderPath, "/", "/"),
                rootFolderId != null ? rootFolderId.trim() : "",
                cleanSite,
                cleanSite.isEmpty() ? List.of() : List.of(cleanSite),
                includeSubfolders,
                excludedFolders != null ? Set.copyOf(excludedFolders) : Set.of("Data Dictionary"),
                firstNonBlank(searchQuery, "TYPE:'cm:content'", "TYPE:'cm:content'"),
                firstNonBlank(queryLanguage, "afts", "afts"),
                List.of(), List.of(), List.of(), true,
                List.of(), List.of(), null, null, List.of(),
                mimeTypeFilter != null ? Set.copyOf(mimeTypeFilter) : Set.of(),
                0,
                VersionMode.CURRENT,
                includeContentStream,
                maxContentSizeBytes > 0 ? maxContentSizeBytes : 52_428_800L,
                "",
                false, false, true, "", null,
                includeAcls, true, "", List.of(), PrincipalFormat.RAW,
                false, "data/alfresco-state", DeletionTracking.NONE, 300,
                null, null, ZoneOffset.UTC, "alfresco-access",
                batchSize > 0 ? Math.min(batchSize, MAX_PAGE_SIZE) : 100,
                4,
                Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 30),
                0,
                500L
        );
    }

    /** Configuration problems that prevent a crawl; empty when settings are valid. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            problems.add("url must be an http(s) Alfresco REST API URL, e.g. http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1.");
        }
        switch (authType) {
            case BASIC -> {
                if (username == null || username.isBlank() || password == null) {
                    problems.add("username and password must be configured for auth-type 'basic'.");
                }
            }
            case TICKET -> {
                if (ticket == null && (username == null || username.isBlank() || password == null)) {
                    problems.add("auth-type 'ticket' requires either a pre-issued ticket or username and password to exchange one.");
                }
            }
            case BEARER -> {
                if (oauth2AccessToken == null) {
                    problems.add("auth-type 'bearer' requires oauth2-access-token (or access-token).");
                }
            }
            case OAUTH2 -> {
                if (oauth2AccessToken == null && (oauth2TokenUrl == null || oauth2ClientId == null || oauth2ClientSecret == null)) {
                    problems.add("auth-type 'oauth2' requires oauth2-access-token, or oauth2-token-url, oauth2-client-id and oauth2-client-secret.");
                }
            }
        }
        if (createdAfter != null && parseThreshold(createdAfter, Instant.now()).isEmpty()) {
            problems.add("created-after must be an ISO date (2024-01-01), instant (2024-01-01T00:00:00Z), or period/duration (P2Y, P90D, PT36H).");
        }
        if (modifiedAfter != null && parseThreshold(modifiedAfter, Instant.now()).isEmpty()) {
            problems.add("modified-after must be an ISO date (2024-01-01), instant (2024-01-01T00:00:00Z), or period/duration (P2Y, P90D, PT36H).");
        }
        for (String pattern : excludedNamePatterns) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                problems.add("excluded-name-patterns contains an invalid regular expression '" + pattern + "': " + e.getDescription() + ".");
            }
        }
        if (fullScanWindow != null && parseWindow(fullScanWindow) == null) {
            problems.add("full-scan-window must look like 01:00-05:00 (24-hour clock; may wrap past midnight).");
        }
        if (fullScanTimezone == null) {
            problems.add("full-scan-timezone must be an IANA timezone such as Europe/Berlin or UTC.");
        }
        return problems;
    }

    // ------------------------------------------------------------------ URL Helpers

    /** Canonical instance authority used in state filenames and metadata. */
    public String instanceKey() {
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            if (host != null && !host.isBlank()) {
                return u.getPort() > 0 ? host + ":" + u.getPort() : host;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return "alfresco";
    }

    /** Public Alfresco Core REST API v1 base URL ({@code .../public/alfresco/versions/1} or custom mock URL). */
    public String coreApiUrl() {
        return url;
    }

    /** Search REST API v1 endpoint URL. */
    public String resolveSearchUrl() {
        if (url.contains("/public/alfresco/versions/1")) {
            return url.replace("/public/alfresco/versions/1", "/public/search/versions/1/search");
        }
        return url + "/search";
    }

    /** Authentication REST API v1 tickets endpoint URL. */
    public String resolveTicketsUrl() {
        if (url.contains("/public/alfresco/versions/1")) {
            return url.replace("/public/alfresco/versions/1", "/public/authentication/versions/1/tickets");
        }
        return url + "/tickets";
    }

    // ------------------------------------------------------------------ Selection Filter Helpers

    public Optional<Instant> createdAfterInstant(Instant now) {
        return parseThreshold(createdAfter, now);
    }

    public Optional<Instant> modifiedAfterInstant(Instant now) {
        return parseThreshold(modifiedAfter, now);
    }

    public boolean acceptsNodeType(String nodeType) {
        if (nodeTypes.isEmpty()) {
            return true;
        }
        if (nodeType == null || nodeType.isBlank()) {
            return false;
        }
        String clean = nodeType.strip();
        return nodeTypes.stream().anyMatch(t -> t.equalsIgnoreCase(clean));
    }

    public boolean acceptsAspects(List<String> aspects) {
        List<String> present = aspects == null ? List.of() : aspects;
        if (excludeWorkingCopies && present.stream().anyMatch(a -> a.equalsIgnoreCase("cm:workingcopy"))) {
            return false;
        }
        for (String excluded : excludedAspects) {
            if (present.stream().anyMatch(a -> a.equalsIgnoreCase(excluded))) {
                return false;
            }
        }
        if (includedAspects.isEmpty()) {
            return true;
        }
        return includedAspects.stream().anyMatch(inc -> present.stream().anyMatch(a -> a.equalsIgnoreCase(inc)));
    }

    public boolean acceptsTags(List<String> tags) {
        List<String> present = tags == null ? List.of() : tags;
        for (String excluded : excludedTags) {
            if (present.stream().anyMatch(t -> t.equalsIgnoreCase(excluded))) {
                return false;
            }
        }
        if (includedTags.isEmpty()) {
            return true;
        }
        return includedTags.stream().anyMatch(inc -> present.stream().anyMatch(t -> t.equalsIgnoreCase(inc)));
    }

    public boolean filtersByTags() {
        return !includedTags.isEmpty() || !excludedTags.isEmpty();
    }

    public List<Pattern> compiledNamePatterns() {
        List<Pattern> compiled = new ArrayList<>();
        for (String p : excludedNamePatterns) {
            try {
                compiled.add(Pattern.compile(p));
            } catch (PatternSyntaxException ignored) {
                // reported by validate()
            }
        }
        return List.copyOf(compiled);
    }

    public boolean matchesMimeType(String mimeType) {
        if (mimeTypeFilter == null || mimeTypeFilter.isEmpty()) {
            return true;
        }
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        String target = mimeType.trim().toLowerCase(Locale.ROOT);
        for (String filter : mimeTypeFilter) {
            String f = filter.trim().toLowerCase(Locale.ROOT);
            if (f.equals(target)) {
                return true;
            }
            if (f.endsWith("/*")) {
                String prefix = f.substring(0, f.length() - 1);
                if (target.startsWith(prefix)) {
                    return true;
                }
            }
            if (f.startsWith("*") && f.length() > 1 && target.endsWith(f.substring(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Exact case-insensitive folder exclusion by folder name, folder node ID, or full/suffix path (avoiding accidental
     * substring exclusions such as {@code temp} excluding {@code Templates}).
     */
    public boolean isFolderExcluded(String folderName, String folderId, String folderPath) {
        if (excludedFolders == null || excludedFolders.isEmpty()) {
            return false;
        }
        String cleanName = folderName == null ? "" : folderName.strip();
        String cleanId = folderId == null ? "" : folderId.strip();
        String cleanPath = folderPath == null ? "" : stripTrailingSlashes(folderPath.strip());
        for (String raw : excludedFolders) {
            String ex = raw == null ? "" : raw.strip();
            if (ex.isEmpty()) {
                continue;
            }
            if (ex.equalsIgnoreCase(cleanName) || (!cleanId.isEmpty() && ex.equalsIgnoreCase(cleanId))) {
                return true;
            }
            if (ex.contains("/") && !cleanPath.isEmpty()) {
                String normEx = stripTrailingSlashes(ex);
                if (cleanPath.equalsIgnoreCase(normEx)
                        || cleanPath.toLowerCase(Locale.ROOT).endsWith("/" + stripLeadingSlashes(normEx).toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    public AlfrescoSecurityMapper.Policy securityPolicy() {
        return new AlfrescoSecurityMapper.Policy(everyonePublic, everyoneIdentity, fallbackPrincipals, principalFormat);
    }

    // ------------------------------------------------------------------ Periodic Full Scan

    public boolean fullScanDue(Instant lastFullScanAt, Instant now) {
        if (fullScanInterval == null || fullScanInterval.isZero() || fullScanInterval.isNegative()) {
            return false;
        }
        if (lastFullScanAt != null && Duration.between(lastFullScanAt, now).compareTo(fullScanInterval) < 0) {
            return false;
        }
        return inFullScanWindow(now);
    }

    boolean inFullScanWindow(Instant now) {
        if (fullScanWindow == null) {
            return true;
        }
        LocalTime[] window = parseWindow(fullScanWindow);
        if (window == null) {
            return true;
        }
        LocalTime local = now.atZone(fullScanTimezone != null ? fullScanTimezone : ZoneOffset.UTC).toLocalTime();
        if (window[0].isBefore(window[1])) {
            return !local.isBefore(window[0]) && local.isBefore(window[1]);
        }
        return !local.isBefore(window[0]) || local.isBefore(window[1]);
    }

    static LocalTime[] parseWindow(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.strip().split("\\s*-\\s*");
        if (parts.length != 2) {
            return null;
        }
        try {
            LocalTime start = LocalTime.parse(parts[0].length() == 4 ? "0" + parts[0] : parts[0]);
            LocalTime end = LocalTime.parse(parts[1].length() == 4 ? "0" + parts[1] : parts[1]);
            return start.equals(end) ? null : new LocalTime[] {start, end};
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Crawl-State Signatures

    /**
     * Scope identity used to derive the default crawl-state file name. Changing rendering/versioning/security options
     * keeps the same state file so stale IDs are cleanly tombstoned.
     */
    public String scopeKey() {
        return "acs-v1|" + url + "|" + crawlMode + "|" + rootFolderPath + "|" + rootFolderId + "|"
                + siteIds + "|" + includeSubfolders + "|" + sortedLower(excludedFolders) + "|"
                + searchQuery + "|" + queryLanguage;
    }

    /** Options affecting how documents are produced/rendered; a change triggers re-indexing under the same state. */
    public String renderSignature() {
        return "v" + RENDER_VERSION
                + "|vm:" + versionMode
                + "|cs:" + includeContentStream
                + "|ren:" + preferRendition.toLowerCase(Locale.ROOT)
                + "|tags:" + includeTags
                + "|com:" + includeComments
                + "|sys:" + includeSystemProperties
                + "|pfx:" + propertyPrefix
                + "|web:" + (webAppUrl == null ? "" : webAppUrl)
                + "|acl:" + includeAcls
                + "|evp:" + everyonePublic
                + "|evi:" + everyoneIdentity
                + "|fb:" + fallbackPrincipals
                + "|pf:" + principalFormat;
    }

    // ------------------------------------------------------------------ Parsing Utilities

    static Optional<Instant> parseThreshold(String raw, Instant now) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String v = raw.strip();
        try {
            if (v.startsWith("P") || v.startsWith("p")) {
                String upper = v.toUpperCase(Locale.ROOT);
                if (upper.contains("T")) {
                    return Optional.of(now.minus(Duration.parse(upper)));
                }
                Period period = Period.parse(upper);
                return Optional.of(ZonedDateTime.ofInstant(now, ZoneOffset.UTC).minus(period).toInstant());
            }
            if (v.length() == 10) {
                return Optional.of(LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant());
            }
            return Optional.of(Instant.parse(v));
        } catch (DateTimeParseException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static Map<String, String> normalizeKeys(Map<String, String> config) {
        Map<String, String> out = new HashMap<>();
        if (config == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : config.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().strip();
            out.put(key, entry.getValue());
            if (key.contains("-") || key.contains("_")) {
                out.putIfAbsent(toCamelCase(key), entry.getValue());
            }
        }
        return out;
    }

    private static String toCamelCase(String key) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char ch : key.toCharArray()) {
            if (ch == '-' || ch == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            }
        }
        return sb.toString();
    }

    static String springName(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String a, String b, String fallback) {
        if (a != null && !a.isBlank()) {
            return a.strip();
        }
        if (b != null && !b.isBlank()) {
            return b.strip();
        }
        return fallback;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String stripTrailingSlashes(String value) {
        if (value == null) {
            return null;
        }
        String u = value.strip();
        while (u.length() > 1 && u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String stripLeadingSlashes(String value) {
        int i = 0;
        while (i < value.length() && value.charAt(i) == '/') {
            i++;
        }
        return value.substring(i);
    }

    private static List<String> list(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static List<String> patternList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.contains("\n") ? raw.split("\\R") : raw.split(",");
        return Arrays.stream(parts).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean bool(Map<String, String> c, String key, boolean fallback) {
        String v = c.get(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(v.strip());
    }

    private static int integer(Map<String, String> c, String key, int fallback) {
        String v = c.get(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longValue(Map<String, String> c, String key, long fallback) {
        String v = c.get(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(normalized)) {
                return constant;
            }
        }
        return fallback;
    }

    private static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Duration d = Duration.parse(value.strip().toUpperCase(Locale.ROOT));
            return d.isZero() || d.isNegative() ? null : d;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value == null || value.isBlank() ? "UTC" : value.strip());
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static List<String> sortedLower(Set<String> values) {
        return values.stream().map(v -> v.toLowerCase(Locale.ROOT)).sorted().toList();
    }

    @Override
    public String toString() {
        return "AlfrescoProperties[url=" + url
                + ", authType=" + authType
                + ", crawlMode=" + crawlMode
                + ", versionMode=" + versionMode
                + ", incrementalEnabled=" + incrementalEnabled
                + ", deletionTracking=" + deletionTracking
                + ", fullScanInterval=" + fullScanInterval
                + ", includeAcls=" + includeAcls
                + ", batchSize=" + batchSize
                + ", parallelism=" + parallelism + "]";
    }
}
