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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.re2j.Pattern;

import crawlercommons.domains.EffectiveTldFinder;

/**
 * Decides whether a normalized URL is in scope for a crawl (spec §5.2, steps 3 to 6).
 * Robots rules are checked separately at fetch time.
 */
public final class UrlPolicy {

    private final WebCrawlSettings settings;
    private final Set<String> seedHosts = ConcurrentHashMap.newKeySet();
    private final Set<String> seedDomains = ConcurrentHashMap.newKeySet();
    private final Set<String> seedPrefixes = ConcurrentHashMap.newKeySet();

    /**
     * @param normalizedSeeds seeds after {@link UrlNormalizer}; they define the crawl scope
     */
    public UrlPolicy(WebCrawlSettings settings, List<String> normalizedSeeds) {
        this.settings = settings;
        normalizedSeeds.forEach(this::addSeed);
    }

    /** Extends the scope with another seed, for example the redirect target of a seed. */
    public void addSeed(String normalizedSeed) {
        URI uri = URI.create(normalizedSeed);
        seedHosts.add(uri.getHost());
        seedDomains.add(registrableDomain(uri.getHost()));
        String path = uri.getRawPath();
        String directory = path.substring(0, path.lastIndexOf('/') + 1);
        String origin = normalizedSeed.substring(0, normalizedSeed.indexOf('/', normalizedSeed.indexOf("://") + 3));
        seedPrefixes.add(origin + directory);
    }

    /**
     * @return the reason the URL is rejected, or null if it may be crawled
     */
    public String reject(String normalizedUrl, int depth) {
        if (normalizedUrl.length() > settings.maxUrlLength()) {
            return "url-length";
        }
        URI uri = URI.create(normalizedUrl);
        if (!inScope(normalizedUrl, uri.getHost())) {
            return "scope";
        }
        String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        for (Pattern exclude : settings.excludePatterns()) {
            if (exclude.matcher(pathAndQuery).find()) {
                return "exclude-pattern";
            }
        }
        if (!settings.includePatterns().isEmpty() && !matchesAny(settings.includePatterns(), pathAndQuery)) {
            return "include-pattern";
        }
        if (depth > settings.maxDepth()) {
            return "depth";
        }
        if (uri.getRawQuery() != null && uri.getRawQuery().split("&").length > settings.maxQueryParams()) {
            return "trap-query-params";
        }
        if (hasRepeatedSegments(uri.getRawPath())) {
            return "trap-repeated-segments";
        }
        return null;
    }

    public boolean inScope(String normalizedUrl, String host) {
        return switch (settings.scope()) {
            case HOST -> seedHosts.contains(host);
            case DOMAIN -> seedDomains.contains(registrableDomain(host));
            case PREFIX -> seedPrefixes.stream().anyMatch(normalizedUrl::startsWith);
            case ANY -> true;
        };
    }

    private boolean hasRepeatedSegments(String path) {
        Map<String, Integer> counts = new HashMap<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty() && counts.merge(segment, 1, Integer::sum) > settings.maxRepeatedSegments()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(List<Pattern> patterns, String value) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the registrable domain (for example {@code example.co.uk} for {@code docs.example.co.uk}),
     * or the host itself for IP addresses and hosts without a public suffix.
     */
    static String registrableDomain(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") || lower.matches("[0-9.]+")) {
            return lower;
        }
        String domain = EffectiveTldFinder.getAssignedDomain(lower, true, false);
        return domain != null && !domain.isBlank() ? domain : lower;
    }
}
