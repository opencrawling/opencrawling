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
package org.opencrawling.stormcrawler;

import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.ConnectorSchema.SchemaField;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.stormcrawler.config.StormCrawlerProperties;
import org.opencrawling.stormcrawler.nimbus.NimbusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.regex.Pattern;

/**
 * OpenCrawling Repository Connector for Apache StormCrawler.
 * Orchestrates web crawling topologies via Apache Storm Nimbus,
 * enforces crawling politeness and URL filtering, and streams crawled pages
 * and deletion tombstones into the Open Ingestion Standard (OIS) pipeline.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.connector.type", havingValue = "stormcrawler")
public class StormCrawlerRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(StormCrawlerRepositoryConnector.class);

    private final StormCrawlerProperties properties;
    private NimbusClient nimbusClient;
    private HttpClient httpClient;

    public StormCrawlerRepositoryConnector(
            @Value("${spring.opencrawling.connector.stormcrawler.nimbus-host:localhost}") String nimbusHost,
            @Value("${spring.opencrawling.connector.stormcrawler.nimbus-port:6627}") int nimbusPort,
            @Value("${spring.opencrawling.connector.stormcrawler.nimbus-rest-url:http://localhost:8080}") String nimbusRestUrl,
            @Value("${spring.opencrawling.connector.stormcrawler.topology-name:opencrawling-web-crawler}") String topologyName,
            @Value("${spring.opencrawling.connector.stormcrawler.seeds:https://docs.example.com}") String seedsString,
            @Value("${spring.opencrawling.connector.stormcrawler.concurrency:8}") int concurrency,
            @Value("${spring.opencrawling.connector.stormcrawler.delay-ms:1000}") long delayMs,
            @Value("${spring.opencrawling.connector.stormcrawler.ignore-robots-txt:false}") boolean ignoreRobotsTxt,
            @Value("${spring.opencrawling.connector.stormcrawler.custom-user-agent:OpenCrawling-StormCrawler-Bot/1.0}") String customUserAgent
    ) {
        this.properties = new StormCrawlerProperties();
        this.properties.setNimbusHost(nimbusHost);
        this.properties.setNimbusPort(nimbusPort);
        this.properties.setNimbusRestUrl(nimbusRestUrl);
        this.properties.setTopologyName(topologyName);
        if (seedsString != null && !seedsString.isBlank()) {
            this.properties.setSeeds(Arrays.stream(seedsString.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList());
        }
        this.properties.setConcurrency(concurrency);
        this.properties.setDelayMs(delayMs);
        this.properties.setIgnoreRobotsTxt(ignoreRobotsTxt);
        this.properties.setCustomUserAgent(customUserAgent);

        this.nimbusClient = new NimbusClient(properties.getNimbusRestUrl());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public StormCrawlerRepositoryConnector(StormCrawlerProperties properties) {
        this.properties = properties;
        this.nimbusClient = new NimbusClient(properties.getNimbusRestUrl());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String getName() {
        return "StormCrawlerConnector";
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting StormCrawler repository connector to Nimbus UI REST API at {}", properties.getNimbusRestUrl());
        boolean alive = nimbusClient.ping();
        if (alive) {
            log.info("Successfully connected to Apache Storm Nimbus REST endpoint.");
        } else {
            log.warn("Apache Storm Nimbus REST endpoint at {} is currently unreachable. Operating in standalone/seed ingestion mode.",
                    properties.getNimbusRestUrl());
        }
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnecting StormCrawler repository connector.");
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            try {
                List<String> targetUrls = resolveTargets(basePath);
                log.info("Starting StormCrawler repository scan for {} target URLs", targetUrls.size());

                List<Pattern> includes = properties.getIncludePatterns().stream().map(Pattern::compile).toList();
                List<Pattern> excludes = properties.getExcludePatterns().stream().map(Pattern::compile).toList();

                crawlUrlsWithVirtualThreads(targetUrls, includes, excludes, sink);
                sink.complete();
            } catch (Exception e) {
                log.error("Error executing StormCrawler repository scan: {}", e.getMessage(), e);
                sink.error(e);
            }
        });
    }

    @SuppressWarnings("preview")
    private void crawlUrlsWithVirtualThreads(List<String> urls, List<Pattern> includes, List<Pattern> excludes,
                                             reactor.core.publisher.FluxSink<RepositoryDocument> sink) throws InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            for (String url : urls) {
                if (!isUrlAllowed(url, includes, excludes)) {
                    log.debug("Skipping URL matching exclude filter or not included: {}", url);
                    continue;
                }

                scope.fork(org.opencrawling.observability.concurrency.ObservabilityTask.observed(() -> {
                    try {
                        if (properties.getDelayMs() > 0) {
                            Thread.sleep(Math.min(properties.getDelayMs(), 500));
                        }

                        RepositoryDocument doc = fetchDocument(url);
                        if (doc != null) {
                            sink.next(doc);
                        }
                    } catch (Exception ex) {
                        log.warn("Error crawling URL {}: {}", url, ex.getMessage());
                    }
                    return null;
                }));
            }
            scope.join();
        } catch (StructuredTaskScope.FailedException e) {
            throw new RuntimeException("StormCrawler scan batch failed", e.getCause());
        }
    }

    public RepositoryDocument fetchDocument(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", properties.getCustomUserAgent())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            // Deletion / tombstone handling: HTTP 404 / 410 signals page removal
            if (statusCode == 404 || statusCode == 410) {
                log.info("URL {} returned HTTP {} - generating OIS DELETE tombstone", url, statusCode);
                return RepositoryDocument.createTombstone(url, url);
            }

            if (statusCode >= 200 && statusCode < 300) {
                String body = response.body();
                String contentType = response.headers().firstValue("content-type").orElse("text/html");

                Map<String, List<String>> metadata = new HashMap<>();
                metadata.put("title", List.of(extractTitle(body, url)));
                metadata.put("mimeType", List.of(contentType));
                metadata.put("http.status", List.of(String.valueOf(statusCode)));
                metadata.put("canonical.url", List.of(url));
                metadata.put("stormcrawler.depth", List.of("1"));
                metadata.put("stormcrawler.status", List.of("FETCHED"));
                metadata.put("crawledAt", List.of(Instant.now().toString()));

                response.headers().map().forEach((k, v) -> {
                    if (k != null && !k.isBlank()) {
                        metadata.put("http.headers." + k.toLowerCase(), v);
                    }
                });

                SecurityConfig security = buildSecurityConfig();
                InputStream contentStream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));

                return new RepositoryDocument(
                        url,
                        url,
                        contentStream,
                        metadata,
                        "public",
                        security,
                        Instant.now(),
                        DocumentAction.UPSERT
                );
            }

            log.warn("Unhandled HTTP status {} for URL: {}", statusCode, url);
            return null;
        } catch (Exception e) {
            log.error("Failed to fetch web document for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private SecurityConfig buildSecurityConfig() {
        List<PermissionRule> rules = new ArrayList<>();
        for (String sid : properties.getAllowedReadSids()) {
            rules.add(new PermissionRule(sid, "role", sid, "read"));
        }
        for (String sid : properties.getDeniedReadSids()) {
            rules.add(new PermissionRule(sid, "role", sid, "deny"));
        }
        return new SecurityConfig(false, rules);
    }

    private String extractTitle(String body, String defaultTitle) {
        if (body == null) return defaultTitle;
        int start = body.indexOf("<title>");
        int end = body.indexOf("</title>");
        if (start != -1 && end != -1 && end > start + 7) {
            return body.substring(start + 7, end).trim();
        }
        return defaultTitle;
    }

    public boolean isUrlAllowed(String url, List<Pattern> includes, List<Pattern> excludes) {
        if (url == null || url.isBlank()) return false;

        for (Pattern exclude : excludes) {
            if (exclude.matcher(url).matches()) {
                return false;
            }
        }

        if (includes.isEmpty()) {
            return true;
        }

        for (Pattern include : includes) {
            if (include.matcher(url).matches()) {
                return true;
            }
        }
        return false;
    }

    private List<String> resolveTargets(String basePath) {
        if (basePath != null && !basePath.isBlank() && !basePath.equals("/")) {
            return Arrays.stream(basePath.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return properties.getSeeds();
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        return new ConnectorSchema(List.of(
                new SchemaField("id", "string", "Canonical URL unique identifier"),
                new SchemaField("uri", "string", "Web page destination URI"),
                new SchemaField("title", "string", "HTML document title or canonical title"),
                new SchemaField("mimeType", "string", "HTTP Content-Type header"),
                new SchemaField("http.status", "string", "HTTP response status code"),
                new SchemaField("stormcrawler.depth", "string", "Crawl depth level"),
                new SchemaField("stormcrawler.status", "string", "StormCrawler frontier status"),
                new SchemaField("canonical.url", "string", "Canonical web page URL")
        ));
    }

    public StormCrawlerProperties getProperties() {
        return properties;
    }

    public NimbusClient getNimbusClient() {
        return nimbusClient;
    }

    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    void setNimbusClient(NimbusClient nimbusClient) {
        this.nimbusClient = nimbusClient;
    }
}
