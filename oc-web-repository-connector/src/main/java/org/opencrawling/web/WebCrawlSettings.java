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
package org.opencrawling.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

/**
 * Resolved settings for one crawl. Setting names follow issue #38 and the web crawler specification (§8).
 *
 * <p>Each setting is resolved in this order: the job's crawlSpec, the connector configuration,
 * the Spring property {@code spring.opencrawling.connector.web.<kebab-case-name>}, then the built-in default.
 */
public record WebCrawlSettings(
    List<String> seeds,
    List<String> sitemapUrls,
    boolean discoverSitemaps,
    int maxDepth,
    long delayMs,
    long maxCrawlDelayMs,
    Scope scope,
    String userAgent,
    String robotsAgentName,
    boolean respectRobots,
    int maxPages,
    List<Pattern> includePatterns,
    List<Pattern> excludePatterns,
    List<String> stripQueryParams,
    long maxContentBytes,
    int connectTimeoutMs,
    int readTimeoutMs,
    int maxRedirects,
    int maxRetries,
    int maxThreads,
    boolean allowPrivateNetworks,
    List<String> privateAllowlist,
    List<String> acceptMimeTypes,
    boolean followFrames,
    boolean respectNofollow,
    AclMode aclMode,
    String aclIdentity,
    int maxUrlLength,
    int maxQueryParams,
    int maxRepeatedSegments,
    int hostErrorThreshold
) {

    public static final String PROPERTY_PREFIX = "spring.opencrawling.connector.web.";
    public static final String DEFAULT_USER_AGENT = "OpenCrawlingBot/1.0 (+https://opencrawling.github.io)";

    /** How far link discovery may leave the seeds. */
    public enum Scope { HOST, DOMAIN, PREFIX, ANY }

    /** How the access-control rules of crawled documents are set. */
    public enum AclMode { PUBLIC, STATIC }

    static final List<String> DEFAULT_STRIP_QUERY_PARAMS =
        List.of("utm_*", "fbclid", "gclid", "msclkid", "jsessionid", "phpsessid", "sid");

    static final List<String> DEFAULT_ACCEPT_MIME_TYPES = List.of(
        "text/html", "application/xhtml+xml", "text/plain", "text/markdown", "application/pdf",
        "application/msword", "application/vnd.openxmlformats-officedocument.*",
        "application/vnd.oasis.opendocument.*", "application/rtf");

    /**
     * Resolves and validates the settings for a crawl.
     *
     * @param jobSettings the job's crawlSpec (may be empty)
     * @param connectorConfig the connector configuration from connectors.json (may be empty)
     * @param propertyResolver resolver for runtime-wide Spring properties
     * @param path the job's path, used as seeds when no seeds are configured
     * @throws IllegalArgumentException listing every invalid setting
     */
    public static WebCrawlSettings resolve(Map<String, Object> jobSettings, Map<String, String> connectorConfig,
                                           Function<String, String> propertyResolver, String path) {
        Source source = new Source(
            jobSettings != null ? jobSettings : Map.of(),
            connectorConfig != null ? connectorConfig : Map.of(),
            propertyResolver != null ? propertyResolver : key -> null);
        List<String> errors = new ArrayList<>();

        List<String> seeds = source.list("seeds", false);
        if (seeds.isEmpty() && path != null) {
            seeds = splitList(path, false);
        }
        for (String seed : seeds) {
            if (!isHttpUrl(seed)) {
                errors.add("seed is not an absolute http(s) URL: " + seed);
            }
        }
        if (seeds.isEmpty()) {
            errors.add("at least one seed URL is required (crawlSpec.seeds or the job path)");
        }
        List<String> sitemapUrls = source.list("sitemapUrls", false);
        for (String sitemap : sitemapUrls) {
            if (!isHttpUrl(sitemap)) {
                errors.add("sitemap URL is not an absolute http(s) URL: " + sitemap);
            }
        }

        int maxDepth = source.integer("maxDepth", 3, errors);
        if (maxDepth < 0 || maxDepth > 50) {
            errors.add("maxDepth must be between 0 and 50");
        }
        long delayMs = source.longValue("delayMs", 1000, errors);
        if (delayMs < 0) {
            errors.add("delayMs must not be negative");
        }
        long maxCrawlDelayMs = source.longValue("maxCrawlDelayMs", 30_000, errors);

        boolean stayOnDomain = source.bool("stayOnDomain", true);
        Scope scope = stayOnDomain ? Scope.DOMAIN : Scope.ANY;
        String scopeValue = source.string("scope");
        if (scopeValue != null) {
            try {
                scope = Scope.valueOf(scopeValue.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add("scope must be one of HOST, DOMAIN, PREFIX, ANY");
            }
        }

        String userAgent = source.stringOrDefault("userAgent", DEFAULT_USER_AGENT);
        if (userAgent.isBlank() || !(userAgent.contains("http") || userAgent.contains("@"))) {
            errors.add("userAgent must include a contact URL or email address, e.g. \"" + DEFAULT_USER_AGENT + "\"");
        }
        String robotsAgentName = source.stringOrDefault("robotsAgentName", productToken(userAgent));

        int maxPages = source.integer("maxPages", 1000, errors);
        if (maxPages < 1) {
            errors.add("maxPages must be at least 1");
        }

        List<Pattern> includePatterns = compile(source.list("includePatterns", true), "includePatterns", errors);
        List<Pattern> excludePatterns = compile(source.list("excludePatterns", true), "excludePatterns", errors);

        List<String> stripQueryParams = new ArrayList<>(DEFAULT_STRIP_QUERY_PARAMS);
        stripQueryParams.addAll(source.list("stripQueryParams", false));

        boolean allowPrivateNetworks = source.bool("allowPrivateNetworks", false);
        List<String> privateAllowlist = source.list("privateAllowlist", false);
        if (allowPrivateNetworks && privateAllowlist.isEmpty()) {
            errors.add("allowPrivateNetworks requires a privateAllowlist of host names or CIDR ranges");
        }
        for (String entry : privateAllowlist) {
            if (entry.contains("/")) {
                try {
                    Cidr.parse(entry);
                } catch (IllegalArgumentException e) {
                    errors.add("invalid privateAllowlist CIDR: " + entry);
                }
            }
        }

        AclMode aclMode = AclMode.PUBLIC;
        String aclModeValue = source.string("aclMode");
        if (aclModeValue != null) {
            try {
                aclMode = AclMode.valueOf(aclModeValue.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add("aclMode must be one of public, static");
            }
        }
        String aclIdentity = source.string("aclIdentity");
        if (aclMode == AclMode.STATIC && (aclIdentity == null || aclIdentity.isBlank())) {
            errors.add("aclMode static requires aclIdentity (the user or group allowed to read the documents)");
        }
        // Fail safe: crawling private networks must not silently make content public (spec §5.9)
        if (allowPrivateNetworks) {
            if (aclModeValue == null) {
                errors.add("aclMode is required when allowPrivateNetworks is true");
            } else if (aclMode == AclMode.PUBLIC && !source.bool("confirmPublicExposure", false)) {
                errors.add("aclMode public on private networks requires confirmPublicExposure: true");
            }
        }

        List<String> acceptMimeTypes = source.list("acceptMimeTypes", false);
        if (acceptMimeTypes.isEmpty()) {
            acceptMimeTypes = DEFAULT_ACCEPT_MIME_TYPES;
        }

        WebCrawlSettings settings = new WebCrawlSettings(
            List.copyOf(seeds),
            List.copyOf(sitemapUrls),
            source.bool("discoverSitemaps", true),
            maxDepth,
            delayMs,
            maxCrawlDelayMs,
            scope,
            userAgent,
            robotsAgentName,
            source.bool("respectRobots", true),
            maxPages,
            includePatterns,
            excludePatterns,
            List.copyOf(stripQueryParams),
            source.longValue("maxContentBytes", 50L * 1024 * 1024, errors),
            source.integer("connectTimeoutMs", 10_000, errors),
            source.integer("readTimeoutMs", 30_000, errors),
            source.integer("maxRedirects", 5, errors),
            source.integer("maxRetries", 2, errors),
            source.integer("maxThreads", 16, errors),
            allowPrivateNetworks,
            List.copyOf(privateAllowlist),
            List.copyOf(acceptMimeTypes),
            source.bool("followFrames", false),
            source.bool("respectNofollow", true),
            aclMode,
            aclIdentity,
            source.integer("maxUrlLength", 2048, errors),
            source.integer("maxQueryParams", 10, errors),
            source.integer("maxRepeatedSegments", 3, errors),
            source.integer("hostErrorThreshold", 20, errors));

        if (settings.maxThreads() < 1) {
            errors.add("maxThreads must be at least 1");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid web crawler settings: " + String.join("; ", errors));
        }
        return settings;
    }

    /** Frontier size cap: discovered URLs beyond this are dropped. */
    public int maxUrls() {
        return (int) Math.min(Integer.MAX_VALUE, maxPages * 10L);
    }

    public boolean acceptsMimeType(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        for (String accepted : acceptMimeTypes) {
            if (accepted.endsWith("*") ? mimeType.startsWith(accepted.substring(0, accepted.length() - 1))
                                       : mimeType.equals(accepted)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String productToken(String userAgent) {
        String token = userAgent.trim().split("[/\\s]", 2)[0];
        return token.isBlank() ? "OpenCrawlingBot" : token;
    }

    private static List<Pattern> compile(List<String> patterns, String name, List<String> errors) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            try {
                compiled.add(Pattern.compile(pattern));
            } catch (PatternSyntaxException e) {
                errors.add(name + " has an invalid pattern \"" + pattern + "\": " + e.getDescription());
            }
        }
        return List.copyOf(compiled);
    }

    /**
     * Splits a list given as a single string. Patterns are split on newlines only, since regexes may contain commas.
     */
    static List<String> splitList(String value, boolean newlineOnly) {
        String separator = newlineOnly ? "\\r?\\n" : "[\\s,]+";
        return Arrays.stream(value.split(separator))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    /** Layered lookup: crawlSpec, connector configuration, Spring property. */
    private record Source(Map<String, Object> job, Map<String, String> connector, Function<String, String> properties) {

        Object raw(String key) {
            Object value = job.get(key);
            if (value == null) {
                value = connector.get(key);
            }
            if (value == null) {
                value = properties.apply(PROPERTY_PREFIX + kebab(key));
            }
            if (value instanceof String s && s.isBlank()) {
                return null;
            }
            return value;
        }

        String string(String key) {
            Object value = raw(key);
            return value != null ? value.toString().trim() : null;
        }

        String stringOrDefault(String key, String defaultValue) {
            String value = string(key);
            return value != null ? value : defaultValue;
        }

        boolean bool(String key, boolean defaultValue) {
            Object value = raw(key);
            return value != null ? Boolean.parseBoolean(value.toString().trim()) : defaultValue;
        }

        int integer(String key, int defaultValue, List<String> errors) {
            return (int) longValue(key, defaultValue, errors);
        }

        long longValue(String key, long defaultValue, List<String> errors) {
            Object value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            if (value instanceof Number n) {
                return n.longValue();
            }
            try {
                return Long.parseLong(value.toString().trim());
            } catch (NumberFormatException e) {
                errors.add(key + " must be a number");
                return defaultValue;
            }
        }

        List<String> list(String key, boolean newlineOnly) {
            Object value = raw(key);
            if (value == null) {
                return List.of();
            }
            if (value instanceof List<?> items) {
                return items.stream()
                    .filter(item -> item != null && !item.toString().isBlank())
                    .map(item -> item.toString().trim())
                    .toList();
            }
            return splitList(value.toString(), newlineOnly);
        }

        private static String kebab(String camel) {
            return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        }
    }
}
