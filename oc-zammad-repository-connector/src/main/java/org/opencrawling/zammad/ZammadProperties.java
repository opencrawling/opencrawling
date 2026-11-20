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
package org.opencrawling.zammad;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
        Duration timeout,
        List<String> excludedArticleSenders,
        List<String> excludedArticleTypes,
        boolean skipAutoReplies,
        String createdAfter,
        List<String> includedTags,
        List<String> excludedTags,
        List<String> excludedTitlePatterns,
        List<String> priorities,
        List<String> organizations,
        String baseQuery,
        PrivacyProfile privacyProfile,
        ParticipantMetadata participantMetadata,
        IdentityRendering identityRendering,
        CustomerIdentity customerIdentity,
        Map<String, String> articleRoleLabels,
        Duration fullScanInterval,
        String fullScanWindow,
        ZoneId fullScanTimezone
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
        PUBLIC_ONLY,
        /**
         * Emits one OIS document per ticket ({@code zammad://<instance>/tickets/<id>}) with every article in
         * chronological order and its role (customer, agent, internal, system); readable by the ticket's group only,
         * because internal notes are included (#411).
         */
        TICKET
    }

    /** Shortcut for privacy-related defaults (#414). */
    public enum PrivacyProfile {
        /** Settings apply as configured. */
        STANDARD,
        /**
         * No e-mail address in rendered headers, metadata or permission fields: identities rendered by name, customer
         * identities by Zammad user id, and no customer or shared-organization principals on public threads.
         */
        STRICT
    }

    /** Participant information written to {@code zammad.participants} for downstream governance hooks (#414). */
    public enum ParticipantMetadata {
        NONE,
        /** Display names of the customer, the owner and every article author. */
        NAMES,
        /** Display names plus Zammad user ids ({@code zammad.participantIds}, {@code zammad-user-<id>}). */
        NAMES_AND_IDS
    }

    /** How people are shown in rendered ticket headers, article headings and identity metadata (#414). */
    public enum IdentityRendering {
        EMAIL,
        NAME,
        /** {@code zammad-user-<id>}. */
        ID,
        NONE
    }

    /** Identity used for the ticket customer in permission rules (#414). */
    public enum CustomerIdentity {
        /** The customer's e-mail or login. */
        EMAIL,
        /** {@code zammad-user-<id>}, so no e-mail address ends up in permission fields. */
        ID
    }

    /** Default labels of the article roles in rendered conversations (#411); localizable via {@code articleRoleLabels}. */
    public static final Map<String, String> DEFAULT_ROLE_LABELS = Map.of(
            "customer", "Customer",
            "agent", "Agent",
            "internal", "Internal note",
            "system", "System");

    /** Bumped whenever rendering changes, so that indexed tickets are rendered again once (see {@link #renderSignature()}). */
    static final int RENDER_VERSION = 2;

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
            "pageSize", "parallelism", "maxRetries", "initialBackoffMs", "timeoutSeconds",
            "excludedArticleSenders", "excludedArticleTypes", "skipAutoReplies",
            "createdAfter", "includedTags", "excludedTags", "excludedTitlePatterns", "priorities", "organizations",
            "baseQuery", "privacyProfile", "participantMetadata", "identityRendering", "customerIdentity",
            "articleRoleLabels", "fullScanInterval", "fullScanWindow", "fullScanTimezone"
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
        PrivacyProfile privacyProfile = parseEnum(value(c, "privacyProfile", null), PrivacyProfile.class, PrivacyProfile.STANDARD);
        boolean strict = privacyProfile == PrivacyProfile.STRICT;
        boolean includeCustomer = !strict && bool(c, "includeCustomerInPublicAcl", true);
        boolean includeSharedOrg = !strict && bool(c, "includeSharedOrganizationInPublicAcl", true);
        boolean allowPublicKb = bool(c, "allowPublicKb", false);
        int pageSize = Math.max(1, Math.min(ZammadClient.MAX_TICKETS_SEARCH_PAGE_SIZE, integer(c, "pageSize", 50)));
        int parallelism = Math.max(1, integer(c, "parallelism", 4));
        int maxRetries = Math.max(0, integer(c, "maxRetries", 5));
        long initialBackoffMs = Math.max(10L, longValue(c, "initialBackoffMs", 1000L));
        Duration timeout = Duration.ofSeconds(Math.max(1, integer(c, "timeoutSeconds", 30)));

        List<String> excludedSenders = c.containsKey("excludedArticleSenders")
                ? list(c.get("excludedArticleSenders"))
                : List.of("System");
        List<String> excludedTypes = list(value(c, "excludedArticleTypes", ""));
        boolean skipAutoReplies = bool(c, "skipAutoReplies", true);
        String createdAfter = value(c, "createdAfter", null);
        List<String> includedTags = list(value(c, "includedTags", ""));
        List<String> excludedTags = list(value(c, "excludedTags", ""));
        List<String> excludedTitlePatterns = patternList(value(c, "excludedTitlePatterns", ""));
        List<String> priorities = list(value(c, "priorities", ""));
        List<String> organizations = list(value(c, "organizations", ""));
        String baseQuery = value(c, "baseQuery", null);
        ParticipantMetadata participantMetadata = parseEnum(
                value(c, "participantMetadata", null), ParticipantMetadata.class, ParticipantMetadata.NAMES);
        IdentityRendering identityRendering = parseEnum(
                value(c, "identityRendering", null), IdentityRendering.class, IdentityRendering.EMAIL);
        CustomerIdentity customerIdentity = parseEnum(
                value(c, "customerIdentity", null), CustomerIdentity.class, CustomerIdentity.EMAIL);
        if (strict) {
            if (identityRendering == IdentityRendering.EMAIL) {
                identityRendering = IdentityRendering.NAME;
            }
            customerIdentity = CustomerIdentity.ID;
        }
        Map<String, String> roleLabels = roleLabels(value(c, "articleRoleLabels", ""));
        Duration fullScanInterval = parseDuration(value(c, "fullScanInterval", null));
        String fullScanWindow = value(c, "fullScanWindow", null);
        ZoneId fullScanTimezone = parseZone(value(c, "fullScanTimezone", "UTC"));

        return new ZammadProperties(
                baseUrl, authType, accessToken, username, password, onBehalfOf,
                groupNames, ticketStates, includeTickets, includeInternal,
                granularity, includeAttachments, includeKb, includeTags,
                crawlMode, stateFile, maxAttachmentSizeBytes, includeAcls,
                includeCustomer, includeSharedOrg, allowPublicKb,
                pageSize, parallelism, maxRetries, initialBackoffMs, timeout,
                excludedSenders, excludedTypes, skipAutoReplies,
                createdAfter, includedTags, excludedTags, excludedTitlePatterns, priorities, organizations, baseQuery,
                privacyProfile, participantMetadata, identityRendering, customerIdentity, roleLabels,
                fullScanInterval, fullScanWindow, fullScanTimezone
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
        if (createdAfter != null && createdAfterInstant(Instant.now()).isEmpty()) {
            problems.add("created-after must be a date (2024-01-01), an instant (2024-01-01T00:00:00Z) or a period (P2Y, P90D).");
        }
        for (String pattern : excludedTitlePatterns) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                problems.add("excluded-title-patterns contains an invalid regular expression '" + pattern + "': "
                        + e.getDescription() + ".");
            }
        }
        if (fullScanWindow != null && parseWindow(fullScanWindow) == null) {
            problems.add("full-scan-window must look like 01:00-05:00 (24-hour clock; it may wrap past midnight).");
        }
        if (fullScanTimezone == null) {
            problems.add("full-scan-timezone must be an IANA timezone such as Europe/Berlin.");
        }
        return problems;
    }

    // ------------------------------------------------------------------ Selection filters (#413)

    /** Lower bound for ticket {@code created_at}, resolved against {@code now} for relative periods. */
    public Optional<Instant> createdAfterInstant(Instant now) {
        if (createdAfter == null || createdAfter.isBlank()) {
            return Optional.empty();
        }
        String v = createdAfter.strip();
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

    public boolean acceptsPriority(String priorityName) {
        return matchesAny(priorities, priorityName);
    }

    public boolean acceptsOrganization(String organizationName) {
        return matchesAny(organizations, organizationName);
    }

    /** Whether the tags allowed by {@code includedTags} / {@code excludedTags} let the ticket in. */
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

    /** Whether the tag filters are configured, so that tags must be read even when {@code includeTags} is off. */
    public boolean filtersByTags() {
        return !includedTags.isEmpty() || !excludedTags.isEmpty();
    }

    /** Compiles {@code excludedTitlePatterns}; invalid expressions are reported by {@link #validate()} and ignored here. */
    public List<Pattern> compiledTitlePatterns() {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : excludedTitlePatterns) {
            try {
                compiled.add(Pattern.compile(pattern));
            } catch (PatternSyntaxException e) {
                // reported by validate()
            }
        }
        return List.copyOf(compiled);
    }

    private static boolean matchesAny(List<String> allowed, String value) {
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        return allowed.stream().anyMatch(a -> a.equalsIgnoreCase(value.strip()));
    }

    // ------------------------------------------------------------------ Article filters (#412)

    public boolean excludesSender(String sender) {
        return sender != null && excludedArticleSenders.stream().anyMatch(s -> s.equalsIgnoreCase(sender.strip()));
    }

    public boolean excludesArticleType(String type) {
        return type != null && excludedArticleTypes.stream().anyMatch(t -> t.equalsIgnoreCase(type.strip()));
    }

    /** Label shown for an article role (customer, agent, internal, system) in rendered conversations. */
    public String roleLabel(String role) {
        String label = articleRoleLabels.get(role);
        return label != null ? label : DEFAULT_ROLE_LABELS.getOrDefault(role, role);
    }

    // ------------------------------------------------------------------ Periodic full scan (#415)

    /**
     * Whether an incremental run should enumerate every ticket this time: the interval since the last full scan has
     * elapsed (or no full scan was recorded yet) and {@code now} falls in the optional window.
     */
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
        LocalTime local = ZonedDateTime.ofInstant(now, fullScanTimezone != null ? fullScanTimezone : ZoneOffset.UTC).toLocalTime();
        LocalTime start = window[0];
        LocalTime end = window[1];
        if (start.isBefore(end)) {
            return !local.isBefore(start) && local.isBefore(end);
        }
        return !local.isBefore(start) || local.isBefore(end);
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

    // ------------------------------------------------------------------ Crawl-state identity

    /**
     * Options that change how documents are rendered, but not which tickets belong to the crawl. A change re-renders
     * every ticket once and tombstones ids that are no longer produced, under the same crawl state (#411, #412, #414).
     */
    public String renderSignature() {
        return "v" + RENDER_VERSION
                + "|g:" + articleGranularity
                + "|int:" + includeInternalArticles
                + "|att:" + includeAttachments
                + "|tags:" + includeTags
                + "|xs:" + lower(excludedArticleSenders)
                + "|xt:" + lower(excludedArticleTypes)
                + "|auto:" + skipAutoReplies
                + "|pm:" + participantMetadata
                + "|ir:" + identityRendering
                + "|rl:" + new java.util.TreeMap<>(articleRoleLabels);
    }

    private static String lower(List<String> values) {
        return values.stream().map(v -> v.toLowerCase(Locale.ROOT)).sorted().toList().toString();
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
            case "TICKET", "CONVERSATION" -> ArticleGranularity.TICKET;
            default -> ArticleGranularity.SPLIT_PUBLIC_AND_INTERNAL;
        };
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
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

    /** {@code customer=Kunde,agent=Agent,internal=Interne Notiz,system=System}: role labels, unknown roles ignored. */
    private static Map<String, String> roleLabels(String value) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String entry : list(value)) {
            int eq = entry.indexOf('=');
            if (eq > 0 && eq < entry.length() - 1) {
                String role = entry.substring(0, eq).strip().toLowerCase(Locale.ROOT);
                if (DEFAULT_ROLE_LABELS.containsKey(role)) {
                    labels.put(role, entry.substring(eq + 1).strip());
                }
            }
        }
        return Map.copyOf(labels);
    }

    /** Regular expressions, one per line, or comma-separated when the value has no line breaks. */
    private static List<String> patternList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.contains("\n") ? value.split("\\R") : value.split(",");
        return Arrays.stream(parts).map(String::strip).filter(s -> !s.isEmpty()).toList();
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
                + ", fullScanInterval=" + fullScanInterval
                + ", privacyProfile=" + privacyProfile
                + ", includeAcls=" + includeAcls
                + ", pageSize=" + pageSize
                + ", parallelism=" + parallelism + "]";
    }
}
