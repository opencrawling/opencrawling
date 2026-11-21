/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.googledrive;

import org.springframework.core.env.PropertyResolver;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
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
import java.util.Set;

/**
 * Strongly-typed configuration settings for the Google Drive Repository Connector (issue #339).
 *
 * <p>Loaded either from a per-job connector configuration map ({@code connectors.json}, camelCase or kebab-case keys)
 * via {@link #fromConfiguration(Map)}, or from Spring properties under {@code spring.opencrawling.connector.googledrive.*}
 * via {@link #fromEnvironment(PropertyResolver)}.
 */
public record GoogleDriveProperties(
        String apiBaseUrl,
        String tokenUrl,
        AuthType authType,
        String serviceAccountJson,
        String delegatedUser,
        String scope,
        String clientId,
        String clientSecret,
        String refreshToken,
        String accessToken,
        String driveId,
        String rootFolderId,
        List<String> rootFolderIds,
        boolean includeSubfolders,
        boolean includeSharedDrives,
        boolean followShortcuts,
        CrawlMode crawlMode,
        String stateFile,
        Duration fullScanInterval,
        String fullScanWindow,
        ZoneId fullScanTimezone,
        boolean exportGoogleDocs,
        String exportDocMimeType,
        String exportSheetMimeType,
        String exportSlidesMimeType,
        String exportDrawingMimeType,
        List<String> excludedMimeTypes,
        List<String> includedMimeTypes,
        long maxFileSizeBytes,
        boolean includeCustomProperties,
        boolean includeAcls,
        boolean allowAnyonePublic,
        String domainIdentityPrefix,
        int pageSize,
        int parallelism,
        int maxRetries,
        long initialBackoffMs,
        Duration timeout,
        int maxDocuments
) {

    public enum AuthType {
        /** Google Cloud Service Account RS256 JWT assertion with optional Domain-Wide Delegation. */
        SERVICE_ACCOUNT,
        /** OAuth2 client credentials + refresh token grant. */
        OAUTH2,
        /** Static OAuth2 Bearer access token (primarily for short-lived tests or external token minting). */
        BEARER
    }

    public enum CrawlMode {
        /** Incremental sync via Drive Changes API (/changes/startPageToken &amp; /changes). */
        CHANGES,
        /** Full folder hierarchy traversal from configured root folder(s). */
        TREE
    }

    public static final String SPRING_PREFIX = "spring.opencrawling.connector.googledrive.";
    public static final String DEFAULT_API_BASE_URL = "https://www.googleapis.com/drive/v3";
    public static final String DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";
    public static final String DEFAULT_SCOPE = "https://www.googleapis.com/auth/drive.readonly";

    public static final String MIME_FOLDER = "application/vnd.google-apps.folder";
    public static final String MIME_SHORTCUT = "application/vnd.google-apps.shortcut";
    public static final String MIME_GOOGLE_DOC = "application/vnd.google-apps.document";
    public static final String MIME_GOOGLE_SHEET = "application/vnd.google-apps.spreadsheet";
    public static final String MIME_GOOGLE_SLIDES = "application/vnd.google-apps.presentation";
    public static final String MIME_GOOGLE_DRAWING = "application/vnd.google-apps.drawing";
    public static final String MIME_GOOGLE_FORM = "application/vnd.google-apps.form";

    /** Google Docs export defaults to HTML so headings survive structure-aware chunking (#385). */
    public static final String DEFAULT_EXPORT_DOC_MIME = "text/html";
    public static final String DEFAULT_EXPORT_SHEET_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** Google Slides export defaults to plain text for clean retrieval indexing. */
    public static final String DEFAULT_EXPORT_SLIDES_MIME = "text/plain";
    public static final String DEFAULT_EXPORT_DRAWING_MIME = "application/pdf";

    /** Sheets and Forms are excluded by default because spreadsheets and forms embed poorly as text. */
    public static final List<String> DEFAULT_EXCLUDED_MIME_TYPES = List.of(
            MIME_GOOGLE_SHEET,
            MIME_GOOGLE_FORM
    );

    public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 52_428_800L; // 50 MB

    static final List<String> KEYS = List.of(
            "apiBaseUrl", "tokenUrl", "authType", "serviceAccountJson", "delegatedUser", "scope",
            "clientId", "clientSecret", "refreshToken", "accessToken",
            "driveId", "rootFolderId", "rootFolderIds", "includeSubfolders", "includeSharedDrives", "followShortcuts",
            "crawlMode", "stateFile", "fullScanInterval", "fullScanWindow", "fullScanTimezone",
            "exportGoogleDocs", "exportDocMimeType", "exportSheetMimeType", "exportSlidesMimeType", "exportDrawingMimeType",
            "excludedMimeTypes", "includedMimeTypes", "maxFileSizeBytes",
            "includeCustomProperties", "includeAcls", "allowAnyonePublic", "domainIdentityPrefix",
            "pageSize", "parallelism", "maxRetries", "initialBackoffMs", "timeoutSeconds", "maxDocuments"
    );

    public static GoogleDriveProperties fromConfiguration(Map<String, String> config) {
        Map<String, String> c = config == null ? Map.of() : config;

        String apiBaseUrl = stripTrailingSlashes(value(c, "apiBaseUrl", DEFAULT_API_BASE_URL));
        String tokenUrl = stripTrailingSlashes(value(c, "tokenUrl", DEFAULT_TOKEN_URL));
        AuthType authType = parseAuthType(value(c, "authType", "service_account"));
        String serviceAccountJson = value(c, "serviceAccountJson", "");
        String delegatedUser = value(c, "delegatedUser", "");
        String scope = value(c, "scope", DEFAULT_SCOPE);
        String clientId = value(c, "clientId", "");
        String clientSecret = value(c, "clientSecret", "");
        String refreshToken = value(c, "refreshToken", "");
        String accessToken = value(c, "accessToken", "");

        String driveId = value(c, "driveId", "");
        String rootFolderId = value(c, "rootFolderId", "root");
        List<String> rootFolderIds = list(value(c, "rootFolderIds", ""));
        boolean includeSubfolders = bool(c, "includeSubfolders", true);
        boolean includeSharedDrives = bool(c, "includeSharedDrives", true);
        boolean followShortcuts = bool(c, "followShortcuts", false);

        CrawlMode crawlMode = "tree".equalsIgnoreCase(value(c, "crawlMode", "changes"))
                ? CrawlMode.TREE : CrawlMode.CHANGES;
        String stateFile = value(c, "stateFile", "");
        Duration fullScanInterval = parseDuration(value(c, "fullScanInterval", null));
        String fullScanWindow = value(c, "fullScanWindow", null);
        ZoneId fullScanTimezone = parseZone(value(c, "fullScanTimezone", "UTC"));

        boolean exportGoogleDocs = bool(c, "exportGoogleDocs", true);
        String exportDocMimeType = value(c, "exportDocMimeType", DEFAULT_EXPORT_DOC_MIME);
        String exportSheetMimeType = value(c, "exportSheetMimeType", DEFAULT_EXPORT_SHEET_MIME);
        String exportSlidesMimeType = value(c, "exportSlidesMimeType", DEFAULT_EXPORT_SLIDES_MIME);
        String exportDrawingMimeType = value(c, "exportDrawingMimeType", DEFAULT_EXPORT_DRAWING_MIME);

        List<String> excludedMimeTypes;
        if (hasKey(c, "excludedMimeTypes")) {
            String raw = rawValue(c, "excludedMimeTypes");
            excludedMimeTypes = (raw == null || raw.isBlank() || "none".equalsIgnoreCase(raw.strip()))
                    ? List.of() : list(raw);
        } else {
            excludedMimeTypes = DEFAULT_EXCLUDED_MIME_TYPES;
        }
        List<String> includedMimeTypes = list(value(c, "includedMimeTypes", ""));

        long maxFileSizeBytes = Math.max(0L, longValue(c, "maxFileSizeBytes", DEFAULT_MAX_FILE_SIZE_BYTES));
        boolean includeCustomProperties = bool(c, "includeCustomProperties", true);
        boolean includeAcls = bool(c, "includeAcls", true);
        boolean allowAnyonePublic = bool(c, "allowAnyonePublic", false);
        String domainIdentityPrefix = rawValue(c, "domainIdentityPrefix") != null
                ? rawValue(c, "domainIdentityPrefix").strip() : "domain:";

        int pageSize = Math.max(1, Math.min(1000, integer(c, "pageSize", 100)));
        int parallelism = Math.max(1, integer(c, "parallelism", 4));
        int maxRetries = Math.max(0, integer(c, "maxRetries", 5));
        long initialBackoffMs = Math.max(1L, longValue(c, "initialBackoffMs", 1000L));
        Duration timeout = Duration.ofSeconds(Math.max(1, integer(c, "timeoutSeconds", 30)));
        int maxDocuments = Math.max(0, integer(c, "maxDocuments", 0));

        return new GoogleDriveProperties(
                apiBaseUrl, tokenUrl, authType, serviceAccountJson, delegatedUser, scope,
                clientId, clientSecret, refreshToken, accessToken,
                driveId, rootFolderId, rootFolderIds, includeSubfolders, includeSharedDrives, followShortcuts,
                crawlMode, stateFile, fullScanInterval, fullScanWindow, fullScanTimezone,
                exportGoogleDocs, exportDocMimeType, exportSheetMimeType, exportSlidesMimeType, exportDrawingMimeType,
                List.copyOf(excludedMimeTypes), List.copyOf(includedMimeTypes), maxFileSizeBytes,
                includeCustomProperties, includeAcls, allowAnyonePublic, domainIdentityPrefix,
                pageSize, parallelism, maxRetries, initialBackoffMs, timeout, maxDocuments
        );
    }

    public static GoogleDriveProperties fromEnvironment(PropertyResolver environment) {
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
                if (!items.isEmpty()) {
                    value = String.join(",", items);
                }
            }
            if (value != null) {
                config.put(key, value);
            }
        }
        return fromConfiguration(config);
    }

    /** Configuration problems that prevent connecting or crawling; empty when usable. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (apiBaseUrl == null || !(apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://"))) {
            problems.add("api-base-url must be a valid HTTP(S) URL.");
        }
        switch (authType) {
            case SERVICE_ACCOUNT -> {
                if (tokenUrl == null || !(tokenUrl.startsWith("http://") || tokenUrl.startsWith("https://"))) {
                    problems.add("token-url must be a valid HTTP(S) URL for auth-type 'service_account'.");
                }
                if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
                    problems.add("service-account-json (inline JSON or path to key file) must be configured for auth-type 'service_account'.");
                }
            }
            case OAUTH2 -> {
                if (tokenUrl == null || !(tokenUrl.startsWith("http://") || tokenUrl.startsWith("https://"))) {
                    problems.add("token-url must be a valid HTTP(S) URL for auth-type 'oauth2'.");
                }
                if (clientId == null || clientId.isBlank()
                        || clientSecret == null || clientSecret.isBlank()
                        || refreshToken == null || refreshToken.isBlank()) {
                    problems.add("client-id, client-secret, and refresh-token must be configured for auth-type 'oauth2'.");
                }
            }
            case BEARER -> {
                if (accessToken == null || accessToken.isBlank()) {
                    problems.add("access-token must be configured for auth-type 'bearer'.");
                }
            }
        }
        if (fullScanWindow != null && parseWindow(fullScanWindow) == null) {
            problems.add("full-scan-window must look like 01:00-05:00 (24-hour clock; it may wrap past midnight).");
        }
        if (fullScanTimezone == null) {
            problems.add("full-scan-timezone must be an IANA timezone such as UTC or Europe/Rome.");
        }
        return problems;
    }

    /**
     * The starting folder IDs to crawl. Combines {@code rootFolderIds} with {@code rootFolderId} (unless
     * {@code rootFolderId} is the default {@code "root"} while explicit {@code rootFolderIds} or a {@code driveId} are set).
     */
    public List<String> effectiveRootFolderIds() {
        Set<String> ids = new LinkedHashSet<>();
        if (rootFolderIds != null) {
            for (String id : rootFolderIds) {
                if (id != null && !id.isBlank()) {
                    ids.add(id.strip());
                }
            }
        }
        String single = rootFolderId == null ? "" : rootFolderId.strip();
        if (!single.isEmpty()) {
            if (ids.isEmpty()) {
                if ("root".equalsIgnoreCase(single) && hasDriveId()) {
                    ids.add(driveId.strip());
                } else {
                    ids.add(single);
                }
            } else if (!"root".equalsIgnoreCase(single)) {
                ids.add(single);
            }
        }
        if (ids.isEmpty()) {
            ids.add(hasDriveId() ? driveId.strip() : "root");
        }
        return List.copyOf(ids);
    }

    public boolean hasDriveId() {
        return driveId != null && !driveId.isBlank();
    }

    /**
     * Whether the crawl is constrained to specific folder ID(s) or direct children only (not an entire drive).
     */
    public boolean isFolderScoped() {
        if (!includeSubfolders) {
            return true;
        }
        List<String> roots = effectiveRootFolderIds();
        if (roots.size() > 1) {
            return true;
        }
        String only = roots.getFirst();
        if (hasDriveId()) {
            return !only.equals(driveId.strip()) && !"root".equalsIgnoreCase(only);
        }
        return !"root".equalsIgnoreCase(only);
    }

    /** Whether a file with {@code originalMimeType} (and optional {@code exportedMimeType}) passes MIME filters. */
    public boolean acceptsMimeType(String originalMimeType, String exportedMimeType) {
        String orig = originalMimeType == null ? "" : originalMimeType.strip().toLowerCase(Locale.ROOT);
        String exp = exportedMimeType == null ? "" : exportedMimeType.strip().toLowerCase(Locale.ROOT);
        for (String excluded : excludedMimeTypes) {
            String ex = excluded.strip().toLowerCase(Locale.ROOT);
            if (!ex.isEmpty() && (orig.equals(ex) || (!exp.isEmpty() && exp.equals(ex)))) {
                return false;
            }
        }
        if (includedMimeTypes.isEmpty()) {
            return true;
        }
        for (String included : includedMimeTypes) {
            String inc = included.strip().toLowerCase(Locale.ROOT);
            if (!inc.isEmpty() && (orig.equals(inc) || (!exp.isEmpty() && exp.equals(inc)))) {
                return true;
            }
        }
        return false;
    }

    /** Stable scope identifier for crawl-state storage (never includes secrets or rendering toggles). */
    public String scopeKey() {
        return "v1|" + apiBaseUrl
                + "|drive=" + (hasDriveId() ? driveId.strip() : "")
                + "|roots=" + String.join(",", effectiveRootFolderIds())
                + "|subfolders=" + includeSubfolders
                + "|shared=" + includeSharedDrives
                + "|delegated=" + (delegatedUser == null ? "" : delegatedUser.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * Signature of export/filter/ACL rendering settings so changing export MIME types, max file size, or ACL policy
     * re-evaluates known files cleanly.
     */
    public String renderSignature() {
        return "r1|export=" + exportGoogleDocs
                + "|doc=" + exportDocMimeType
                + "|sheet=" + exportSheetMimeType
                + "|slides=" + exportSlidesMimeType
                + "|drawing=" + exportDrawingMimeType
                + "|excl=" + String.join(",", excludedMimeTypes)
                + "|incl=" + String.join(",", includedMimeTypes)
                + "|max=" + maxFileSizeBytes
                + "|props=" + includeCustomProperties
                + "|acls=" + includeAcls
                + "|anyone=" + allowAnyonePublic
                + "|dom=" + domainIdentityPrefix
                + "|shortcuts=" + followShortcuts;
    }

    /** Source instance URI reported in OIS {@code ois:source.instance}. */
    public String sourceInstanceUri() {
        if (hasDriveId()) {
            return "https://drive.google.com/drive/folders/" + driveId.strip();
        }
        List<String> roots = effectiveRootFolderIds();
        if (roots.size() == 1 && !"root".equalsIgnoreCase(roots.getFirst())) {
            return "https://drive.google.com/drive/folders/" + roots.getFirst();
        }
        return "https://drive.google.com";
    }

    // ------------------------------------------------------------------ Periodic full reconciliation

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
        if (fullScanWindow == null || fullScanWindow.isBlank()) {
            return true;
        }
        LocalTime[] window = parseWindow(fullScanWindow);
        if (window == null) {
            return true;
        }
        LocalTime local = ZonedDateTime.ofInstant(now, fullScanTimezone != null ? fullScanTimezone : ZoneOffset.UTC)
                .toLocalTime();
        LocalTime start = window[0];
        LocalTime end = window[1];
        if (start.isBefore(end)) {
            return !local.isBefore(start) && local.isBefore(end);
        }
        return !local.isBefore(start) || local.isBefore(end);
    }

    static LocalTime[] parseWindow(String raw) {
        if (raw == null || raw.isBlank()) {
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

    // ------------------------------------------------------------------ Parsing helpers

    static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static AuthType parseAuthType(String raw) {
        if (raw == null || raw.isBlank()) {
            return AuthType.SERVICE_ACCOUNT;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "oauth2", "refresh_token" -> AuthType.OAUTH2;
            case "bearer", "token", "access_token" -> AuthType.BEARER;
            default -> AuthType.SERVICE_ACCOUNT;
        };
    }

    private static boolean hasKey(Map<String, String> config, String camelKey) {
        return config.containsKey(camelKey) || config.containsKey(kebab(camelKey));
    }

    private static String rawValue(Map<String, String> config, String camelKey) {
        if (config.containsKey(camelKey)) {
            return config.get(camelKey);
        }
        return config.get(kebab(camelKey));
    }

    private static String value(Map<String, String> config, String camelKey, String fallback) {
        String v = rawValue(config, camelKey);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    private static List<String> list(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,\\r\\n]+"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static boolean bool(Map<String, String> config, String key, boolean fallback) {
        String v = value(config, key, null);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    private static int integer(Map<String, String> config, String key, int fallback) {
        try {
            String v = value(config, key, null);
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longValue(Map<String, String> config, String key, long fallback) {
        try {
            String v = value(config, key, null);
            return v == null ? fallback : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static Duration parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.strip();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        try {
            if (upper.startsWith("P")) {
                if (upper.contains("T")) {
                    return Duration.parse(upper);
                }
                Period p = Period.parse(upper);
                long days = p.getYears() * 365L + p.getMonths() * 30L + p.getDays();
                return Duration.ofDays(days);
            }
            if (upper.endsWith("D")) {
                return Duration.ofDays(Long.parseLong(upper.substring(0, upper.length() - 1).strip()));
            }
            if (upper.endsWith("H")) {
                return Duration.ofHours(Long.parseLong(upper.substring(0, upper.length() - 1).strip()));
            }
            if (upper.endsWith("M")) {
                return Duration.ofMinutes(Long.parseLong(upper.substring(0, upper.length() - 1).strip()));
            }
            if (upper.endsWith("S")) {
                return Duration.ofSeconds(Long.parseLong(upper.substring(0, upper.length() - 1).strip()));
            }
            return Duration.parse(trimmed);
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    private static ZoneId parseZone(String raw) {
        if (raw == null || raw.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(raw.strip());
        } catch (DateTimeException e) {
            return null;
        }
    }

    static String stripTrailingSlashes(String url) {
        if (url == null) {
            return null;
        }
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    @Override
    public String toString() {
        // Never log credentials, private keys, client secrets, refresh tokens, or access tokens.
        return "GoogleDriveProperties[apiBaseUrl=" + apiBaseUrl
                + ", authType=" + authType
                + ", delegatedUser=" + delegatedUser
                + ", driveId=" + driveId
                + ", rootFolderIds=" + effectiveRootFolderIds()
                + ", includeSubfolders=" + includeSubfolders
                + ", includeSharedDrives=" + includeSharedDrives
                + ", crawlMode=" + crawlMode
                + ", exportGoogleDocs=" + exportGoogleDocs
                + ", exportDocMimeType=" + exportDocMimeType
                + ", exportSlidesMimeType=" + exportSlidesMimeType
                + ", excludedMimeTypes=" + excludedMimeTypes
                + ", maxFileSizeBytes=" + maxFileSizeBytes
                + ", includeAcls=" + includeAcls
                + ", allowAnyonePublic=" + allowAnyonePublic
                + ", pageSize=" + pageSize
                + ", parallelism=" + parallelism
                + ", maxDocuments=" + maxDocuments + "]";
    }
}
