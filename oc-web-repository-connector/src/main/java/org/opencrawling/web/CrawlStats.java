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

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * Counters for one crawl run. Outcomes are also exported as Micrometer counters with bounded labels
 * (spec §12: no host label).
 */
public final class CrawlStats {

    private final String jobId;
    private final MeterRegistry registry;
    private final LongAdder requests = new LongAdder();
    private final LongAdder published = new LongAdder();
    private final LongAdder bytes = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> outcomes = new ConcurrentHashMap<>();

    public CrawlStats(String jobId) {
        this(jobId, Metrics.globalRegistry);
    }

    CrawlStats(String jobId, MeterRegistry registry) {
        this.jobId = jobId != null ? jobId : "unknown";
        this.registry = registry;
    }

    /** Counts a completed HTTP request by status class (2xx, 3xx, 4xx, 5xx or error). */
    void request(int status, long bodyBytes) {
        requests.increment();
        bytes.add(bodyBytes);
        String statusClass = status <= 0 ? "error" : (status / 100) + "xx";
        counter("oc.crawl.fetch", "status_class", statusClass).increment();
        if (bodyBytes > 0) {
            counter("oc.crawl.bytes").increment(bodyBytes);
        }
    }

    void published() {
        published.increment();
        counter("oc.crawl.published", "operation", "upsert").increment();
    }

    /** Counts a URL that was excluded, skipped, failed or deduplicated, by reason. */
    void outcome(String reason) {
        outcomes.computeIfAbsent(reason, r -> new LongAdder()).increment();
        if (reason.equals("robots")) {
            counter("oc.crawl.robots.denied").increment();
        }
    }

    public long requests() {
        return requests.sum();
    }

    public long publishedCount() {
        return published.sum();
    }

    public long count(String reason) {
        LongAdder adder = outcomes.get(reason);
        return adder != null ? adder.sum() : 0;
    }

    public Map<String, Long> outcomes() {
        Map<String, Long> snapshot = new TreeMap<>();
        outcomes.forEach((reason, adder) -> snapshot.put(reason, adder.sum()));
        return snapshot;
    }

    private Counter counter(String name, String... tags) {
        String[] allTags = new String[tags.length + 2];
        allTags[0] = "job_id";
        allTags[1] = jobId;
        System.arraycopy(tags, 0, allTags, 2, tags.length);
        return registry.counter(name, allTags);
    }

    @Override
    public String toString() {
        return "requests=" + requests.sum() + ", published=" + published.sum() + ", bytes=" + bytes.sum()
            + ", outcomes=" + outcomes();
    }
}
