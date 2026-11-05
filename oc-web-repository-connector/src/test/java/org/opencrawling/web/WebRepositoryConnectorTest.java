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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.RepositoryDocument;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.reactivestreams.Subscription;

import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

/**
 * Crawls a small test website served on 127.0.0.1 (issue #38 testing strategy).
 */
class WebRepositoryConnectorTest {

    private static final long DELAY_MS = 60;

    private HttpServer server;
    private String base;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    private record Request(String method, String path, long at) {}

    @BeforeEach
    void startSite() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, Page> site = new HashMap<>();
        site.put("/robots.txt", text("User-agent: *\nDisallow: /private/\nSitemap: " + base + "/sitemap.xml\n"));
        site.put("/sitemap.xml", new Page(200, "application/xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>%s/orphan</loc></url>
            </urlset>""".formatted(base), Map.of()));
        site.put("/", html("""
            <html lang="en"><head><title>Home</title>
            <meta name="description" content="Test site home"><meta name="keywords" content="crawler, test"></head>
            <body><h1>Welcome</h1>
            <a href="/a">A</a> <a href="b">B</a> <a href="/b-copy">B copy</a> <a href="/private/secret">Secret</a>
            <a href="/loop1">Loop</a> <a href="/dup-canonical">Dup</a> <a href="/noindex">Noindex</a>
            <a href="/doc.pdf">PDF</a> <a href="http://external.example.com/x">External</a>
            <a href="/a#frag">A again</a> <a href="/a?utm_source=x">A tracked</a> <a href="/missing">Missing</a>
            <a href="/big">Big</a> <a href="/nofollow-target" rel="nofollow">Nofollow</a>
            <a href="/image.png">Image</a> <a href="mailto:x@example.com">Mail</a>
            <a href="/moved">Moved</a> <a href="/gone">Gone</a>
            </body></html>"""));
        site.put("/a", html("<html><head><title>A</title></head><body>Page A <a href='/a/deep1'>deeper</a></body></html>"));
        site.put("/a/deep1", html("<html><body>Deep 1 <a href='/a/deep2'>deeper</a></body></html>"));
        site.put("/a/deep2", html("<html><body>Deep 2 <a href='/a/deep3'>deeper</a></body></html>"));
        site.put("/a/deep3", html("<html><body>Deep 3, beyond maxDepth</body></html>"));
        site.put("/b", html("<html><body>Same content</body></html>"));
        site.put("/b-copy", html("<html><body>Same content</body></html>"));
        site.put("/private/secret", html("<html><body>Disallowed by robots.txt</body></html>"));
        // Redirects and error pages with bodies, like GitHub Pages sends
        site.put("/moved", new Page(301, "text/html", "<html><body>Moved to <a href='/a'>/a</a></body></html>",
            Map.of("Location", "/a")));
        site.put("/gone", new Page(410, "text/html", "<html><body>" + "gone ".repeat(20_000) + "</body></html>", Map.of()));
        site.put("/loop1", new Page(302, "text/html", "", Map.of("Location", "/loop2")));
        site.put("/loop2", new Page(302, "text/html", "", Map.of("Location", "/loop1")));
        site.put("/dup-canonical", html("<html><head><link rel='canonical' href='/a'></head><body>Copy of A</body></html>"));
        site.put("/noindex", html("<html><head><meta name='robots' content='noindex'></head>"
            + "<body><a href='/from-noindex'>link</a></body></html>"));
        site.put("/from-noindex", html("<html><body>Linked from a noindex page</body></html>"));
        site.put("/doc.pdf", new Page(200, "application/pdf", "%PDF-1.4 fake pdf body", Map.of()));
        site.put("/big", html("<html><body>" + "x".repeat(5000) + "</body></html>"));
        site.put("/nofollow-target", html("<html><body>Only linked with rel=nofollow</body></html>"));
        site.put("/image.png", new Page(200, "image/png", "png", Map.of()));
        site.put("/orphan", html("<html><body>Only in the sitemap</body></html>"));

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(exchange.getRequestMethod(), path, System.currentTimeMillis()));
            Page page = site.get(path);
            respond(exchange, page != null ? page : new Page(404, "text/plain", "not found", Map.of()));
        });
        server.start();
    }

    @AfterEach
    void stopSite() {
        server.stop(0);
    }

    @Test
    void crawlsTheSiteWithinScopeLimitsAndRobotsRules() {
        List<RepositoryDocument> docs = crawl(connector("job-1", Map.of()));

        assertThat(paths(docs)).containsExactlyInAnyOrder(
            "/", "/a", "/b", "/a/deep1", "/a/deep2", "/doc.pdf", "/from-noindex", "/orphan");

        Set<String> fetched = requests.stream().map(Request::path).collect(Collectors.toSet());
        assertThat(fetched).doesNotContain("/private/secret", "/image.png", "/a/deep3", "/nofollow-target");
        assertThat(fetched).contains("/noindex", "/loop1", "/loop2", "/missing", "/big", "/b-copy", "/dup-canonical",
            "/moved", "/gone");
        // Each URL is fetched once, even when linked several times or with tracking parameters and fragments
        assertThat(requests.stream().filter(r -> r.method().equals("GET") && r.path().equals("/a")).count()).isEqualTo(1);
    }

    @Test
    void mapsPageMetadataAsInIssue38() {
        RepositoryDocument home = crawl(connector("job-1", Map.of())).stream()
            .filter(d -> d.uri().equals(base + "/")).findFirst().orElseThrow();

        assertThat(home.id()).isEqualTo(WebIds.documentId("job-1", base + "/"));
        assertThat(home.acl()).isEqualTo("public");
        assertThat(home.action()).isEqualTo(org.opencrawling.core.document.DocumentAction.UPSERT);
        assertThat(home.metadata()).containsEntry("name", List.of("index.html"))
            .containsEntry("mimeType", List.of("text/html"))
            .containsEntry("source", List.of("web"))
            .containsEntry("title", List.of("Home"))
            .containsEntry("description", List.of("Test site home"))
            .containsEntry("keywords", List.of("crawler, test"))
            .containsEntry("language", List.of("en"))
            .containsEntry("http_status", List.of("200"))
            .containsEntry("web_depth", List.of("0"))
            .containsEntry("web_host", List.of("127.0.0.1"))
            .containsKeys("web_content_hash", "web_fetched_at", "jobId");

        RepositoryDocument pageA = crawl(connector("job-1", Map.of())).stream()
            .filter(d -> d.uri().equals(base + "/a")).findFirst().orElseThrow();
        assertThat(pageA.metadata()).containsEntry("web_referrer", List.of(base + "/"))
            .containsEntry("web_depth", List.of("1"))
            .containsEntry("name", List.of("a.html"));
    }

    @Test
    void documentIdsAreStableAcrossRunsAndDifferPerJob() {
        Set<String> first = ids(crawl(connector("job-1", Map.of())));
        Set<String> second = ids(crawl(connector("job-1", Map.of())));
        Set<String> otherJob = ids(crawl(connector("job-2", Map.of())));

        assertThat(second).isEqualTo(first);
        assertThat(otherJob).doesNotContainAnyElementsOf(first);
    }

    @Test
    void keepsThePolitenessDelayBetweenRequestsToTheSameHost() {
        crawl(connector("job-1", Map.of("maxThreads", 8)));
        assertMinimumGap(requests);
    }

    @Test
    void sharesThePolitenessBudgetAcrossConcurrentJobs() {
        List<RepositoryDocument> docs = Flux.merge(
                connector("job-1", Map.of()).scan(null),
                connector("job-2", Map.of()).scan(null))
            .collectList().block(Duration.ofMinutes(1));

        assertThat(docs).hasSize(16);
        assertMinimumGap(requests);
    }

    @Test
    void stopsAtMaxPages() {
        List<RepositoryDocument> docs = crawl(connector("job-1", Map.of("maxPages", 3, "discoverSitemaps", false)));
        long pageRequests = requests.stream()
            .filter(r -> r.method().equals("GET") && !r.path().equals("/robots.txt")).count();
        assertThat(pageRequests).isEqualTo(3);
        assertThat(docs).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void includePatternsStillCrawlTheSeedForLinksButOnlyIndexMatches() {
        List<RepositoryDocument> docs = crawl(connector("job-1", Map.of("includePatterns", List.of("^/a"))));
        assertThat(paths(docs)).containsExactlyInAnyOrder("/a", "/a/deep1", "/a/deep2");
    }

    @Test
    void blocksLocalAddressesByDefault() {
        WebCrawlSettings settings = WebCrawlSettings.resolve(Map.of("delayMs", DELAY_MS), Map.of(), key -> null, base + "/");
        WebRepositoryConnector connector = new WebRepositoryConnector(settings, "job-1");
        List<RepositoryDocument> docs = crawl(connector);

        assertThat(docs).isEmpty();
        assertThat(requests).isEmpty();
        assertThatThrownBy(() -> new WebRepositoryConnector(settings, "job-1").connect())
            .hasMessageContaining("No seed URL is reachable")
            .hasMessageContaining("Blocked by SSRF guard");
    }

    @Test
    void cancellingDownstreamStopsTheCrawl() {
        WebRepositoryConnector connector = connector("job-1", Map.of());
        List<RepositoryDocument> received = Collections.synchronizedList(new ArrayList<>());
        // Request one document only, so the crawler's workers block waiting for more demand
        BaseSubscriber<RepositoryDocument> subscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(1);
            }

            @Override
            protected void hookOnNext(RepositoryDocument doc) {
                received.add(doc);
            }
        };
        connector.scan(null).subscribe(subscriber);
        sleep(DELAY_MS * 10);
        assertThat(received).hasSize(1);
        assertThat(connector.isCrawling()).isTrue();
        subscriber.dispose();

        // Workers waiting for downstream demand must wake up and exit, ending the crawl thread
        long deadline = System.currentTimeMillis() + 5_000;
        while (connector.isCrawling() && System.currentTimeMillis() < deadline) {
            sleep(20);
        }
        assertThat(connector.isCrawling()).as("crawl still running after cancel").isFalse();
        assertThat(requests.size()).isLessThan(16);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aSeedThatRedirectsToAnotherHostBringsThatHostIntoScope() throws Exception {
        // Like opencrawling.github.io redirecting to opencrawling.org: the sitemap and links live on the target host
        HttpServer redirecting = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirecting.createContext("/", exchange -> respond(exchange, new Page(301, "text/html",
            "<html><body>Moved</body></html>", Map.of("Location", base + exchange.getRequestURI().getPath()))));
        redirecting.start();
        try {
            String seed = "http://localhost:" + redirecting.getAddress().getPort() + "/";
            List<RepositoryDocument> docs = crawl(connector("job-1", Map.of(
                "seeds", List.of(seed), "scope", "host", "privateAllowlist", List.of("localhost", "127.0.0.0/8"))));
            assertThat(paths(docs)).contains("/", "/a", "/orphan");
        } finally {
            redirecting.stop(0);
        }
    }

    @Test
    void followsARobotsTxtRedirectThatHasABody() throws Exception {
        HttpServer redirecting = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirecting.createContext("/", exchange -> respond(exchange, new Page(301, "text/html",
            "<html><body>Moved</body></html>", Map.of("Location", base + exchange.getRequestURI().getPath()))));
        redirecting.start();
        try {
            String origin = "http://127.0.0.1:" + redirecting.getAddress().getPort();
            WebCrawlSettings settings = WebCrawlSettings.resolve(Map.of(
                "seeds", List.of(origin + "/"), "delayMs", DELAY_MS,
                "allowPrivateNetworks", true, "privateAllowlist", List.of("127.0.0.0/8"),
                "aclMode", "public", "confirmPublicExposure", true), Map.of(), key -> null, null);
            try (HttpFetcher fetcher = new HttpFetcher(settings)) {
                var rules = new HostRegistry().robots(origin, "OpenCrawlingBot", fetcher, DELAY_MS, 5);
                assertThat(rules.isAllowed(origin + "/a")).isTrue();
                assertThat(rules.isAllowed(origin + "/private/secret")).isFalse();
            }
        } finally {
            redirecting.stop(0);
        }
    }

    @Test
    void connectChecksTheSeedsWithHead() throws Exception {
        connector("job-1", Map.of()).connect();
        assertThat(requests).extracting(Request::path).containsExactly("/");
    }

    @Test
    void ignoresRobotsOnlyWhenOverridden() {
        List<RepositoryDocument> docs = crawl(connector("job-1", Map.of("respectRobots", false, "discoverSitemaps", false)));
        assertThat(paths(docs)).contains("/private/secret").doesNotContain("/orphan");
        assertThat(requests).extracting(Request::path).doesNotContain("/robots.txt");
    }

    private WebRepositoryConnector connector(String jobId, Map<String, Object> overrides) {
        Map<String, Object> spec = new HashMap<>(Map.of(
            "seeds", List.of(base + "/"),
            "delayMs", DELAY_MS,
            "maxContentBytes", 2000,
            "excludePatterns", List.of("\\.png$"),
            "allowPrivateNetworks", true,
            "privateAllowlist", List.of("127.0.0.0/8"),
            "aclMode", "public",
            "confirmPublicExposure", true));
        spec.putAll(overrides);
        return new WebRepositoryConnector(WebCrawlSettings.resolve(spec, Map.of(), key -> null, null), jobId);
    }

    private static List<RepositoryDocument> crawl(WebRepositoryConnector connector) {
        return connector.scan(null).collectList().block(Duration.ofMinutes(1));
    }

    private Set<String> paths(List<RepositoryDocument> docs) {
        return docs.stream().map(d -> d.uri().substring(base.length())).collect(Collectors.toSet());
    }

    private static Set<String> ids(List<RepositoryDocument> docs) {
        return docs.stream().map(RepositoryDocument::id).collect(Collectors.toSet());
    }

    private static void assertMinimumGap(List<Request> requests) {
        List<Long> times = requests.stream().map(Request::at).sorted().toList();
        assertThat(times).hasSizeGreaterThan(5);
        for (int i = 1; i < times.size(); i++) {
            // 5 ms tolerance for clock granularity
            assertThat(times.get(i) - times.get(i - 1)).as("gap before request %d", i).isGreaterThanOrEqualTo(DELAY_MS - 5);
        }
    }

    private record Page(int status, String contentType, String body, Map<String, String> headers) {}

    private static Page html(String body) {
        return new Page(200, "text/html; charset=utf-8", body, Map.of());
    }

    private static Page text(String body) {
        return new Page(200, "text/plain", body, Map.of());
    }

    private static void respond(HttpExchange exchange, Page page) throws IOException {
        byte[] body = page.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", page.contentType());
        page.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        boolean head = exchange.getRequestMethod().equals("HEAD");
        exchange.sendResponseHeaders(page.status(), head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }
}
