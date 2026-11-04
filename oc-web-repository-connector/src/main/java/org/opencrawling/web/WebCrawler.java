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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.hc.client5.http.utils.DateUtils;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.observability.concurrency.ObservabilityTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.sitemaps.AbstractSiteMap;
import crawlercommons.sitemaps.SiteMap;
import crawlercommons.sitemaps.SiteMapIndex;
import crawlercommons.sitemaps.SiteMapParser;
import crawlercommons.sitemaps.SiteMapURL;

/**
 * One in-memory crawl run (spec Phase 1a).
 *
 * <p>URLs wait in one queue per host. A host is handed to at most one worker at a time and becomes ready again
 * after its politeness delay, so a slow host never blocks the others. Workers are virtual threads in a
 * {@link StructuredTaskScope}. Every request also goes through the process-wide {@link HostRegistry} gate,
 * so concurrent jobs share each host's politeness budget.
 */
public final class WebCrawler {

    private static final Logger log = LoggerFactory.getLogger(WebCrawler.class);

    private static final int MAX_SITEMAPS = 50;
    private static final long MAX_RETRY_AFTER_MS = 5 * 60 * 1000;
    private static final long POLL_MS = 200;

    private final WebCrawlSettings settings;
    private final String jobId;
    private final HttpFetcher fetcher;
    private final HostRegistry hosts;
    private final Consumer<RepositoryDocument> emitter;
    private final UrlNormalizer normalizer;
    private final HtmlPageParser parser;
    private final CrawlStats stats;
    private UrlPolicy policy;

    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Set<String> excluded = ConcurrentHashMap.newKeySet();
    private final Set<String> contentHashes = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, HostQueue> hostQueues = new ConcurrentHashMap<>();
    private final DelayQueue<Ticket> ready = new DelayQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger pagesFetched = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> consecutiveErrors = new ConcurrentHashMap<>();
    private final Set<String> suspendedHosts = ConcurrentHashMap.newKeySet();
    private final Set<String> blockedHostsLogged = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    /**
     * A URL waiting to be fetched. {@code seedTask} marks a seed or a redirect target of a seed.
     * {@code publish} is false for seeds kept only for link discovery.
     */
    record Task(String url, int depth, String referrer, String seed, String anchorText,
                int attempt, int redirectHops, boolean seedTask, boolean publish) {

        Task retry() {
            return new Task(url, depth, referrer, seed, anchorText, attempt + 1, redirectHops, seedTask, publish);
        }
    }

    /**
     * @param emitter receives each document to publish; may block to apply backpressure
     */
    public WebCrawler(WebCrawlSettings settings, String jobId, HttpFetcher fetcher, HostRegistry hosts,
                      Consumer<RepositoryDocument> emitter) {
        this.settings = settings;
        this.jobId = jobId;
        this.fetcher = fetcher;
        this.hosts = hosts;
        this.emitter = emitter;
        this.normalizer = new UrlNormalizer(settings.stripQueryParams());
        this.parser = new HtmlPageParser(settings.robotsAgentName(), settings.followFrames());
        this.stats = new CrawlStats(jobId);
    }

    public CrawlStats stats() {
        return stats;
    }

    /** Stops the crawl; workers finish their current request and exit. */
    public void stop() {
        stopped = true;
    }

    /**
     * Runs the crawl to completion: until no URLs are left, {@code maxPages} is reached or {@link #stop()} is called.
     */
    @SuppressWarnings("preview")
    public void run() throws InterruptedException {
        long start = System.currentTimeMillis();
        List<String> seeds = new ArrayList<>();
        for (String seed : settings.seeds()) {
            normalizer.normalize(seed).ifPresentOrElse(seeds::add,
                () -> log.warn("Job {}: ignoring invalid seed URL {}", jobId, seed));
        }
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("No valid seed URLs to crawl");
        }
        policy = new UrlPolicy(settings, seeds);
        if (!settings.respectRobots()) {
            log.warn("Job {}: respectRobots=false. robots.txt is ignored for this crawl (admin override).", jobId);
        }

        // Follow seed redirects first (http to https, example.com to www.example.com), so the final hosts are in
        // scope before sitemap entries and links are checked
        Map<String, String> resolvedSeeds = resolveSeedRedirects(seeds);
        resolvedSeeds.keySet().forEach(policy::addSeed);
        resolvedSeeds.forEach((resolved, seed) -> enqueueSeed(resolved, seed, 0));
        expandSitemaps(new ArrayList<>(resolvedSeeds.keySet()));

        try (var scope = StructuredTaskScope.open()) {
            for (int i = 0; i < settings.maxThreads(); i++) {
                scope.fork(ObservabilityTask.observed(() -> {
                    workerLoop();
                    return null;
                }));
            }
            scope.join();
        }
        log.info("Job {}: web crawl finished in {} ms: {}", jobId, System.currentTimeMillis() - start, stats);
    }

    private void workerLoop() {
        while (!stopped) {
            Ticket ticket;
            try {
                ticket = ready.poll(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                stopped = true;
                return;
            }
            if (ticket == null) {
                if (pending.get() == 0) {
                    return;
                }
                continue;
            }
            HostQueue queue = ticket.queue();
            Task task;
            synchronized (queue) {
                task = queue.tasks.poll();
                if (task == null) {
                    queue.scheduled = false;
                    continue;
                }
            }
            try {
                process(task, queue);
            } catch (InterruptedException e) {
                stopped = true;
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Job {}: unexpected error crawling {}: {}", jobId, task.url(), e.toString());
                stats.outcome("error");
            } finally {
                synchronized (queue) {
                    queue.readyAt = System.currentTimeMillis() + queue.delayMs;
                    if (queue.tasks.isEmpty()) {
                        queue.scheduled = false;
                    } else {
                        ready.put(new Ticket(queue, queue.readyAt));
                    }
                }
                pending.decrementAndGet();
            }
        }
    }

    private void process(Task task, HostQueue queue) throws Exception {
        if (stopped) {
            return;
        }
        String host = URI.create(task.url()).getHost();
        if (suspendedHosts.contains(host)) {
            stats.outcome("host-suspended");
            return;
        }

        long delayMs = settings.delayMs();
        if (settings.respectRobots()) {
            BaseRobotRules rules;
            try {
                rules = hosts.robots(origin(task.url()), settings.robotsAgentName(), fetcher,
                    settings.delayMs(), settings.maxRedirects());
            } catch (SsrfGuard.BlockedAddressException e) {
                reportBlocked(e);
                return;
            }
            if (!rules.isAllowed(task.url())) {
                stats.outcome("robots");
                return;
            }
            long crawlDelay = rules.getCrawlDelay();
            if (crawlDelay != BaseRobotRules.UNSET_CRAWL_DELAY && crawlDelay > 0) {
                if (crawlDelay > settings.maxCrawlDelayMs()) {
                    stats.outcome("crawl-delay-too-long");
                    return;
                }
                delayMs = Math.max(delayMs, crawlDelay);
            }
        }
        queue.delayMs = delayMs;

        if (pagesFetched.incrementAndGet() > settings.maxPages()) {
            if (!stopped) {
                log.info("Job {}: reached maxPages={}, stopping the crawl", jobId, settings.maxPages());
            }
            stopped = true;
            return;
        }

        HttpFetcher.Response response;
        HostRegistry.HostGate gate = hosts.gate(host);
        try {
            response = gate.call(delayMs, () -> fetcher.get(task.url(), settings.maxContentBytes()));
        } catch (SsrfGuard.BlockedAddressException e) {
            reportBlocked(e);
            return;
        } catch (IOException e) {
            stats.request(0, 0);
            log.debug("Job {}: fetch failed for {}: {}", jobId, task.url(), e.toString());
            retryOrFail(task, queue, host, "network-error");
            return;
        }
        stats.request(response.status(), response.body().length);

        int status = response.status();
        if (response.isRedirect()) {
            consecutiveErrors.remove(host);
            followRedirect(task, response.header("location"));
        } else if (status == 429 || status == 503) {
            gate.backOff(retryAfterMs(response.header("retry-after"), delayMs));
            retryOrFail(task, queue, host, "http-" + status);
        } else if (status >= 500) {
            retryOrFail(task, queue, host, "http-5xx");
        } else if (status == 404 || status == 410) {
            consecutiveErrors.remove(host);
            stats.outcome("not-found");
        } else if (status >= 400) {
            consecutiveErrors.remove(host);
            stats.outcome("http-4xx");
        } else if (status >= 200 && status < 300) {
            consecutiveErrors.remove(host);
            handlePage(task, response);
        } else {
            stats.outcome("http-" + status);
        }
    }

    private void handlePage(Task task, HttpFetcher.Response response) throws IOException {
        if (response.truncated()) {
            stats.outcome("too-large");
            return;
        }
        String mimeType = response.mimeType() != null ? response.mimeType() : sniffMimeType(response.body());
        boolean html = "text/html".equals(mimeType) || "application/xhtml+xml".equals(mimeType);
        if (!html && !settings.acceptsMimeType(mimeType)) {
            stats.outcome("mime-type");
            return;
        }

        HtmlPageParser.ParsedPage page = null;
        if (html) {
            page = parser.parse(response.body(), response.charset(), task.url(), response.header("x-robots-tag"));
            String canonical = page.canonicalUrl() != null ? normalizer.normalize(page.canonicalUrl()).orElse(null) : null;
            if (canonical != null && !canonical.equals(task.url()) && policy.inScope(canonical, URI.create(canonical).getHost())) {
                // Index the canonical version only (spec FR-32)
                stats.outcome("duplicate-canonical");
                enqueue(canonical, task.depth(), task.referrer(), task.seed(), task.anchorText(), 0);
                return;
            }
            if (!page.nofollow() && task.depth() < settings.maxDepth()) {
                for (HtmlPageParser.Link link : page.links()) {
                    if (settings.respectNofollow() && link.nofollow()) {
                        continue;
                    }
                    normalizer.normalize(link.url(), task.url()).ifPresent(url ->
                        enqueue(url, task.depth() + 1, task.url(), task.seed(), link.anchorText(), 0));
                }
            }
            if (page.noindex()) {
                stats.outcome("noindex");
                return;
            }
        } else if (hasNoindexHeader(response.header("x-robots-tag"))) {
            stats.outcome("noindex");
            return;
        }

        if (!task.publish()) {
            stats.outcome("seed-not-indexed");
            return;
        }
        if (!settings.acceptsMimeType(mimeType)) {
            stats.outcome("mime-type");
            return;
        }
        String contentHash = WebIds.sha256Hex(html ? page.text().getBytes(java.nio.charset.StandardCharsets.UTF_8) : response.body());
        if (!contentHashes.add(contentHash)) {
            stats.outcome("duplicate-content");
            return;
        }

        emitter.accept(toDocument(task, response, mimeType, page, contentHash));
        stats.published();
    }

    private void followRedirect(Task task, String location) {
        if (task.redirectHops() >= settings.maxRedirects()) {
            stats.outcome("too-many-redirects");
            return;
        }
        stats.outcome("redirect");
        normalizer.normalize(location, task.url()).ifPresent(target -> {
            if (task.seedTask()) {
                // A redirecting seed (http to https, example.com to www.example.com) extends the scope to its target
                policy.addSeed(target);
                enqueueSeed(target, task.seed(), task.redirectHops() + 1);
            } else {
                enqueue(target, task.depth(), task.referrer(), task.seed(), task.anchorText(), task.redirectHops() + 1);
            }
        });
    }

    private void retryOrFail(Task task, HostQueue queue, String host, String reason) {
        int errors = consecutiveErrors.computeIfAbsent(host, h -> new AtomicInteger()).incrementAndGet();
        if (errors >= settings.hostErrorThreshold() && suspendedHosts.add(host)) {
            log.warn("Job {}: suspending host {} for this crawl after {} consecutive errors", jobId, host, errors);
        }
        if (task.attempt() < settings.maxRetries() && !suspendedHosts.contains(host)) {
            stats.outcome("retry");
            add(queue, task.retry());
        } else {
            stats.outcome(reason);
        }
    }

    /**
     * Follows each seed's redirects with HEAD requests (falling back to GET).
     *
     * @return the final URL of each seed mapped to the seed it came from, in seed order
     */
    private Map<String, String> resolveSeedRedirects(List<String> seeds) throws InterruptedException {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String seed : seeds) {
            String current = seed;
            for (int hop = 0; hop < settings.maxRedirects(); hop++) {
                if (settings.respectRobots() && !robotsAllow(current)) {
                    break;
                }
                String target = current;
                HttpFetcher.Response response;
                try {
                    response = hosts.gate(URI.create(target).getHost()).call(settings.delayMs(), () -> fetcher.head(target));
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    // Reported when the seed itself is crawled
                    break;
                }
                stats.request(response.status(), 0);
                String next = response.isRedirect() ? normalizer.normalize(response.header("location"), current).orElse(null) : null;
                if (next == null || next.equals(current)) {
                    break;
                }
                stats.outcome("redirect");
                current = next;
            }
            if (!current.equals(seed)) {
                log.info("Job {}: seed {} redirects to {}", jobId, seed, current);
            }
            resolved.putIfAbsent(current, seed);
        }
        return resolved;
    }

    private boolean robotsAllow(String url) {
        try {
            return hosts.robots(origin(url), settings.robotsAgentName(), fetcher, settings.delayMs(),
                settings.maxRedirects()).isAllowed(url);
        } catch (SsrfGuard.BlockedAddressException e) {
            return false;
        }
    }

    /** Logs the first block per host at WARN, so a blocked intranet seed is easy to diagnose. */
    private void reportBlocked(SsrfGuard.BlockedAddressException e) {
        stats.outcome("ssrf-blocked");
        if (blockedHostsLogged.add(e.getMessage())) {
            log.warn("Job {}: {}", jobId, e.getMessage());
        }
    }

    private void enqueueSeed(String url, String seed, int redirectHops) {
        if (!seen.add(url)) {
            return;
        }
        String reason = policy.reject(url, 0);
        if (reason != null && !reason.endsWith("-pattern")) {
            stats.outcome("excluded-" + reason);
            return;
        }
        // Seeds rejected by include/exclude patterns are still fetched for their links, but not indexed
        add(hostQueue(url), new Task(url, 0, null, seed, null, 0, redirectHops, true, reason == null));
    }

    private void enqueue(String url, int depth, String referrer, String seed, String anchorText, int redirectHops) {
        if (stopped || seen.contains(url)) {
            return;
        }
        // A rejected URL isn't marked as seen: the scope can grow (seed redirects) and a later, shallower link may pass
        String reason = policy.reject(url, depth);
        if (reason != null) {
            if (excluded.size() >= settings.maxUrls() || excluded.add(url)) {
                stats.outcome("excluded-" + reason);
            }
            return;
        }
        if (!seen.add(url)) {
            return;
        }
        if (seen.size() > settings.maxUrls()) {
            stats.outcome("excluded-max-urls");
            return;
        }
        add(hostQueue(url), new Task(url, depth, referrer, seed, anchorText, 0, redirectHops, false, true));
    }

    private HostQueue hostQueue(String url) {
        return hostQueues.computeIfAbsent(URI.create(url).getHost(), h -> new HostQueue(settings.delayMs()));
    }

    private void add(HostQueue queue, Task task) {
        pending.incrementAndGet();
        synchronized (queue) {
            queue.tasks.add(task);
            if (!queue.scheduled) {
                queue.scheduled = true;
                ready.put(new Ticket(queue, Math.max(System.currentTimeMillis(), queue.readyAt)));
            }
        }
    }

    /** Adds URLs from explicit sitemaps and from {@code Sitemap:} lines in the seeds' robots.txt (spec FR-2). */
    private void expandSitemaps(List<String> seeds) {
        Set<String> sitemaps = new LinkedHashSet<>();
        for (String sitemap : settings.sitemapUrls()) {
            normalizer.normalize(sitemap).ifPresent(sitemaps::add);
        }
        if (settings.discoverSitemaps()) {
            Set<String> origins = new LinkedHashSet<>();
            seeds.forEach(seed -> origins.add(origin(seed)));
            for (String origin : origins) {
                try {
                    BaseRobotRules rules = hosts.robots(origin, settings.robotsAgentName(), fetcher,
                        settings.delayMs(), settings.maxRedirects());
                    for (String sitemap : rules.getSitemaps()) {
                        normalizer.normalize(sitemap).ifPresent(sitemaps::add);
                    }
                } catch (SsrfGuard.BlockedAddressException e) {
                    // Reported when the seed itself is crawled
                }
            }
        }

        SiteMapParser sitemapParser = new SiteMapParser(false);
        Deque<String> toFetch = new ArrayDeque<>(sitemaps);
        Set<String> fetched = new LinkedHashSet<>();
        while (!toFetch.isEmpty() && fetched.size() < MAX_SITEMAPS && !stopped) {
            String sitemapUrl = toFetch.poll();
            if (!fetched.add(sitemapUrl)) {
                continue;
            }
            try {
                HttpFetcher.Response response = hosts.gate(URI.create(sitemapUrl).getHost())
                    .call(settings.delayMs(), () -> fetcher.get(sitemapUrl, settings.maxContentBytes()));
                stats.request(response.status(), response.body().length);
                if (response.status() < 200 || response.status() >= 300 || response.truncated()) {
                    stats.outcome("sitemap-error");
                    continue;
                }
                String contentType = response.header("content-type") != null ? response.header("content-type") : "text/xml";
                AbstractSiteMap parsed = sitemapParser.parseSiteMap(contentType, response.body(), URI.create(sitemapUrl).toURL());
                if (parsed instanceof SiteMapIndex index) {
                    for (AbstractSiteMap child : index.getSitemaps()) {
                        normalizer.normalize(child.getUrl().toString()).ifPresent(toFetch::add);
                    }
                } else if (parsed instanceof SiteMap sitemap) {
                    for (SiteMapURL entry : sitemap.getSiteMapUrls()) {
                        normalizer.normalize(entry.getUrl().toString())
                            .ifPresent(url -> enqueue(url, 0, null, sitemapUrl, null, 0));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
            } catch (Exception e) {
                log.warn("Job {}: failed to read sitemap {}: {}", jobId, sitemapUrl, e.toString());
                stats.outcome("sitemap-error");
            }
        }
    }

    private RepositoryDocument toDocument(Task task, HttpFetcher.Response response, String mimeType,
                                          HtmlPageParser.ParsedPage page, String contentHash) {
        Instant fetchedAt = Instant.now();
        URI uri = URI.create(task.url());
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        put(metadata, "name", documentName(uri.getRawPath(), mimeType));
        put(metadata, "mimeType", mimeType);
        put(metadata, "source", "web");
        put(metadata, "jobId", jobId);
        if (page != null) {
            put(metadata, "title", page.title());
            put(metadata, "description", page.description());
            put(metadata, "language", page.language());
            put(metadata, "keywords", page.keywords());
        }
        if (metadata.get("language") == null) {
            put(metadata, "language", response.header("content-language"));
        }
        put(metadata, "http_status", String.valueOf(response.status()));
        put(metadata, "http_server", response.header("server"));
        put(metadata, "http_etag", response.header("etag"));
        put(metadata, "http_last_modified", response.header("last-modified"));
        put(metadata, "http_content_length", String.valueOf(response.body().length));
        put(metadata, "web_host", uri.getHost());
        put(metadata, "web_canonical_url", task.url());
        put(metadata, "web_depth", String.valueOf(task.depth()));
        put(metadata, "web_referrer", task.referrer());
        put(metadata, "web_seed", task.seed());
        put(metadata, "web_anchor_text", task.anchorText());
        put(metadata, "web_content_hash", contentHash);
        put(metadata, "web_fetched_at", fetchedAt.toString());

        String acl;
        SecurityConfig security;
        if (settings.aclMode() == WebCrawlSettings.AclMode.STATIC) {
            acl = settings.aclIdentity();
            security = new SecurityConfig(false, List.of(new PermissionRule(acl, "group", acl, "read")));
        } else {
            acl = "public";
            security = SecurityConfig.createPublic();
        }

        return new RepositoryDocument(
            WebIds.documentId(jobId, task.url()),
            task.url(),
            new ByteArrayInputStream(response.body()),
            metadata,
            acl,
            security,
            lastModified(response.header("last-modified"), page != null ? page.modifiedTime() : null, fetchedAt),
            // Phase 1a keeps no state between runs, so it never knows a page was removed and only upserts
            DocumentAction.UPSERT);
    }

    static Instant lastModified(String header, String articleModifiedTime, Instant fallback) {
        if (header != null) {
            Instant parsed = DateUtils.parseStandardDate(header);
            if (parsed != null) {
                return parsed;
            }
        }
        if (articleModifiedTime != null) {
            String value = articleModifiedTime.trim();
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (RuntimeException ignored) {
                // try the next format
            }
            try {
                return Instant.parse(value);
            } catch (RuntimeException ignored) {
                // try the next format
            }
            try {
                return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (RuntimeException ignored) {
                // fall back to the fetch time
            }
        }
        return fallback;
    }

    static String documentName(String rawPath, String mimeType) {
        String path = rawPath != null ? rawPath : "/";
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isEmpty()) {
            return "index.html";
        }
        if (!name.contains(".") && ("text/html".equals(mimeType) || "application/xhtml+xml".equals(mimeType))) {
            return name + ".html";
        }
        return name;
    }

    private static String origin(String normalizedUrl) {
        int pathStart = normalizedUrl.indexOf('/', normalizedUrl.indexOf("://") + 3);
        return pathStart < 0 ? normalizedUrl : normalizedUrl.substring(0, pathStart);
    }

    /** Parses Retry-After (seconds or an HTTP date), capped at five minutes. */
    static long retryAfterMs(String header, long defaultMs) {
        long backoff = Math.max(defaultMs * 2, 1000);
        if (header != null) {
            try {
                backoff = Long.parseLong(header.trim()) * 1000;
            } catch (NumberFormatException e) {
                Instant date = DateUtils.parseStandardDate(header);
                if (date != null) {
                    backoff = date.toEpochMilli() - System.currentTimeMillis();
                }
            }
        }
        return Math.max(0, Math.min(backoff, MAX_RETRY_AFTER_MS));
    }

    private static boolean hasNoindexHeader(String xRobotsTag) {
        if (xRobotsTag == null || xRobotsTag.contains(":")) {
            return false;
        }
        String value = xRobotsTag.toLowerCase(Locale.ROOT);
        return value.contains("noindex") || value.contains("none");
    }

    private static String sniffMimeType(byte[] body) {
        String head = new String(body, 0, Math.min(body.length, 512), java.nio.charset.StandardCharsets.ISO_8859_1)
            .trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("%pdf")) {
            return "application/pdf";
        }
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            return "text/html";
        }
        return "application/octet-stream";
    }

    private static void put(Map<String, List<String>> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, List.of(value));
        }
    }

    /** URLs waiting for one host. Guarded by its own monitor. */
    private static final class HostQueue {
        final Deque<Task> tasks = new ArrayDeque<>();
        boolean scheduled;
        long readyAt;
        volatile long delayMs;

        HostQueue(long delayMs) {
            this.delayMs = delayMs;
        }
    }

    /** A host that may be crawled again from {@code readyAt}. */
    private record Ticket(HostQueue queue, long readyAt) implements Delayed {
        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(readyAt - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(readyAt, ((Ticket) other).readyAt);
        }
    }
}
