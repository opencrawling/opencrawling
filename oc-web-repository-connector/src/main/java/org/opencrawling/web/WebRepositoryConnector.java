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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;

/**
 * Crawls websites from seed URLs and emits one {@link RepositoryDocument} per page (issue #38, spec Phase 1a).
 *
 * <p>Instances are created per job by {@link WebRepositoryConnectorFactory}. The no-argument constructor
 * exists for {@link java.util.ServiceLoader} discovery in the Admin UI connector list.
 */
public class WebRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(WebRepositoryConnector.class);

    private final WebCrawlSettings settings;
    private final String jobId;
    private final HostRegistry hosts;
    private final AtomicReference<WebCrawler> activeCrawl = new AtomicReference<>();

    public WebRepositoryConnector() {
        this(null, null, HostRegistry.shared());
    }

    public WebRepositoryConnector(WebCrawlSettings settings, String jobId) {
        this(settings, jobId, HostRegistry.shared());
    }

    WebRepositoryConnector(WebCrawlSettings settings, String jobId, HostRegistry hosts) {
        this.settings = settings;
        this.jobId = jobId;
        this.hosts = hosts;
    }

    @Override
    public String getName() {
        return "WebCrawler";
    }

    /**
     * Checks that at least one seed is reachable (spec FR-18): HEAD, falling back to GET when HEAD isn't supported.
     * An unreachable seed is only a warning; the connector fails when every seed is unreachable.
     */
    @Override
    public void connect() throws Exception {
        WebCrawlSettings resolved = requireSettings();
        List<String> failures = new ArrayList<>();
        try (HttpFetcher fetcher = new HttpFetcher(resolved)) {
            for (String seed : resolved.seeds()) {
                try {
                    int status = hosts.gate(java.net.URI.create(seed).getHost())
                        .call(resolved.delayMs(), () -> fetcher.head(seed)).status();
                    if (status < 500) {
                        log.info("Web crawler seed {} is reachable (HTTP {})", seed, status);
                        return;
                    }
                    failures.add(seed + " (HTTP " + status + ")");
                } catch (IOException e) {
                    failures.add(seed + " (" + e.getMessage() + ")");
                }
                log.warn("Web crawler seed is unreachable: {}", failures.getLast());
            }
        }
        throw new IOException("No seed URL is reachable: " + String.join(", ", failures));
    }

    @Override
    public void disconnect() {
        WebCrawler crawl = activeCrawl.get();
        if (crawl != null) {
            crawl.stop();
        }
    }

    /**
     * Crawls from the configured seeds. {@code basePath} is used as the seed list when the job doesn't configure
     * seeds explicitly. The returned Flux applies backpressure: the crawl pauses while downstream is busy.
     */
    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        WebCrawlSettings resolved = settings != null && !settings.seeds().isEmpty()
            ? settings
            : WebCrawlSettings.resolve(Map.of(), Map.of(), key -> null, basePath);

        return Flux.create(sink -> {
            Semaphore demand = new Semaphore(0);
            AtomicBoolean unbounded = new AtomicBoolean();
            sink.onRequest(n -> {
                if (n >= Integer.MAX_VALUE) {
                    unbounded.set(true);
                    demand.release(Integer.MAX_VALUE / 2);
                } else {
                    demand.release((int) n);
                }
            });

            Thread crawlThread = Thread.ofVirtual().name("web-crawl-" + jobId).unstarted(() -> {
                try (HttpFetcher fetcher = new HttpFetcher(resolved)) {
                    WebCrawler crawler = new WebCrawler(resolved, jobId, fetcher, hosts, doc -> {
                        try {
                            if (!unbounded.get()) {
                                demand.acquire();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Crawl interrupted while waiting for downstream", e);
                        }
                        sink.next(doc);
                    });
                    activeCrawl.set(crawler);
                    sink.onDispose(() -> {
                        crawler.stop();
                        // Wake workers waiting for demand, so they see the stop and exit
                        unbounded.set(true);
                        demand.release(Integer.MAX_VALUE / 2);
                    });
                    if (sink.isCancelled()) {
                        return;
                    }
                    crawler.run();
                    sink.complete();
                } catch (Throwable e) {
                    sink.error(e);
                } finally {
                    activeCrawl.set(null);
                }
            });
            crawlThread.start();
        });
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        return new ConnectorSchema(List.of(
            new ConnectorSchema.SchemaField("title", "string", "Page title"),
            new ConnectorSchema.SchemaField("description", "string", "Meta description"),
            new ConnectorSchema.SchemaField("language", "string", "Page language"),
            new ConnectorSchema.SchemaField("keywords", "string", "Meta keywords"),
            new ConnectorSchema.SchemaField("web_host", "string", "Host name"),
            new ConnectorSchema.SchemaField("web_canonical_url", "string", "Canonical URL"),
            new ConnectorSchema.SchemaField("web_depth", "integer", "Link hops from the seed"),
            new ConnectorSchema.SchemaField("web_referrer", "string", "Page that linked here"),
            new ConnectorSchema.SchemaField("http_status", "integer", "HTTP status code"),
            new ConnectorSchema.SchemaField("http_server", "string", "Server header")));
    }

    WebCrawlSettings settings() {
        return settings;
    }

    boolean isCrawling() {
        return activeCrawl.get() != null;
    }

    private WebCrawlSettings requireSettings() {
        if (settings == null) {
            throw new IllegalStateException("WebRepositoryConnector has no settings; create it with WebRepositoryConnectorFactory");
        }
        return settings;
    }
}
