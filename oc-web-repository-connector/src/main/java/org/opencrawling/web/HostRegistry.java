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

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;

/**
 * Per-host state shared by every crawl in this process (spec §4.2, Phase 1a): a politeness gate that allows
 * one request at a time per host with a minimum delay between requests, and a robots.txt cache.
 *
 * <p>Sharing it means two jobs crawling the same site share one politeness budget and fetch robots.txt once.
 */
public final class HostRegistry {

    private static final Logger log = LoggerFactory.getLogger(HostRegistry.class);
    private static final HostRegistry SHARED = new HostRegistry();

    static final Duration ROBOTS_TTL = Duration.ofHours(24);
    static final Duration ROBOTS_FAILURE_TTL = Duration.ofMinutes(10);
    private static final long ROBOTS_MAX_BYTES = 512 * 1024;

    private final ConcurrentHashMap<String, HostGate> gates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedRules> robots = new ConcurrentHashMap<>();
    private final SimpleRobotRulesParser robotsParser = new SimpleRobotRulesParser();

    public static HostRegistry shared() {
        return SHARED;
    }

    public HostGate gate(String host) {
        return gates.computeIfAbsent(host.toLowerCase(Locale.ROOT), h -> new HostGate());
    }

    /** A request that may throw, run while holding a host's politeness gate. */
    @FunctionalInterface
    public interface Request<T> {
        T run() throws Exception;
    }

    /**
     * Serializes requests to one host and keeps at least {@code delayMs} between the start of consecutive requests.
     */
    public static final class HostGate {
        private final Semaphore permit = new Semaphore(1, true);
        private volatile long nextAllowedAt;

        public <T> T call(long delayMs, Request<T> request) throws Exception {
            permit.acquire();
            try {
                long wait = nextAllowedAt - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                nextAllowedAt = System.currentTimeMillis() + delayMs;
                return request.run();
            } finally {
                permit.release();
            }
        }

        /** Pushes the next allowed request time back, for example after a 429 with Retry-After. */
        public void backOff(long millis) {
            nextAllowedAt = Math.max(nextAllowedAt, System.currentTimeMillis() + millis);
        }
    }

    /**
     * Returns the robots.txt rules for an origin such as {@code https://example.com}, fetching them when
     * missing or expired (RFC 9309: 4xx allows everything, 5xx or network failure disallows everything).
     *
     * @throws SsrfGuard.BlockedAddressException if the origin resolves to a blocked internal address
     */
    public BaseRobotRules robots(String origin, String agentName, HttpFetcher fetcher, long delayMs, int maxRedirects)
            throws SsrfGuard.BlockedAddressException {
        String key = origin + " " + agentName.toLowerCase(Locale.ROOT);
        CachedRules cached = robots.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.rules();
        }
        CachedRules fresh = fetchRobots(origin, agentName, fetcher, delayMs, maxRedirects);
        robots.put(key, fresh);
        return fresh.rules();
    }

    private CachedRules fetchRobots(String origin, String agentName, HttpFetcher fetcher, long delayMs, int maxRedirects)
            throws SsrfGuard.BlockedAddressException {
        String key = origin + " " + agentName.toLowerCase(Locale.ROOT);
        CachedRules cached = robots.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached;
        }
        String url = origin + "/robots.txt";
        try {
            for (int hop = 0; hop <= maxRedirects; hop++) {
                cached = robots.get(key);
                if (cached != null && !cached.isExpired()) {
                    return cached;
                }
                String target = url;
                String host = java.net.URI.create(target).getHost();
                HttpFetcher.Response response = gate(host != null ? host : "")
                    .call(delayMs, () -> {
                        CachedRules insideGate = robots.get(key);
                        if (insideGate != null && !insideGate.isExpired()) {
                            return null;
                        }
                        HttpFetcher.Response res = fetcher.get(target, ROBOTS_MAX_BYTES);
                        if (!res.isRedirect()) {
                            int status = res.status();
                            CachedRules result;
                            if (status >= 200 && status < 300) {
                                BaseRobotRules rules = robotsParser.parseContent(target, res.body(),
                                    res.header("content-type") != null ? res.header("content-type") : "text/plain",
                                    List.of(agentName.toLowerCase(Locale.ROOT)));
                                result = new CachedRules(rules, System.currentTimeMillis() + ROBOTS_TTL.toMillis());
                            } else {
                                result = failed(status);
                            }
                            robots.put(key, result);
                        }
                        return res;
                    });
                if (response == null) {
                    CachedRules existing = robots.get(key);
                    if (existing != null && !existing.isExpired()) {
                        return existing;
                    }
                    continue;
                }
                if (response.isRedirect()) {
                    url = java.net.URI.create(url).resolve(response.header("location")).toString();
                    continue;
                }
                CachedRules existing = robots.get(key);
                if (existing != null) {
                    return existing;
                }
                int status = response.status();
                CachedRules result = (status >= 200 && status < 300)
                    ? new CachedRules(robotsParser.parseContent(url, response.body(),
                        response.header("content-type") != null ? response.header("content-type") : "text/plain",
                        List.of(agentName.toLowerCase(Locale.ROOT))), System.currentTimeMillis() + ROBOTS_TTL.toMillis())
                    : failed(status);
                robots.put(key, result);
                return result;
            }
            // Too many redirects: treat as not found (RFC 9309 §2.3.1.2)
            CachedRules result = failed(404);
            robots.put(key, result);
            return result;
        } catch (SsrfGuard.BlockedAddressException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CachedRules result = failed(503);
            robots.put(key, result);
            return result;
        } catch (Exception e) {
            // RFC 9309: an unreachable robots.txt means the whole site is disallowed until the next attempt
            log.warn("Could not fetch {}: {}. Treating the site as disallowed for {} minutes.",
                url, e.toString(), ROBOTS_FAILURE_TTL.toMinutes());
            CachedRules result = failed(503);
            robots.put(key, result);
            return result;
        }
    }

    private CachedRules failed(int status) {
        BaseRobotRules rules = robotsParser.failedFetch(status);
        long ttl = status >= 500 ? ROBOTS_FAILURE_TTL.toMillis() : ROBOTS_TTL.toMillis();
        return new CachedRules(rules, System.currentTimeMillis() + ttl);
    }

    /** Clears cached state. Intended for tests. */
    void clear() {
        gates.clear();
        robots.clear();
    }

    private record CachedRules(BaseRobotRules rules, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
