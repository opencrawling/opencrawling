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
package org.opencrawling.stormcrawler.bolt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.storm.Config;
import org.apache.storm.LocalCluster;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.utils.Utils;
import org.apache.stormcrawler.Constants;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.bolt.FetcherBolt;
import org.apache.stormcrawler.bolt.JSoupParserBolt;
import org.apache.stormcrawler.persistence.MemoryStatusUpdater;
import org.apache.stormcrawler.persistence.Status;
import org.apache.stormcrawler.spout.MemorySpout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opencrawling.stormcrawler.bolt.dispatcher.InMemoryPayloadDispatcher.DispatchedItem;
import org.opencrawling.stormcrawler.bolt.dispatcher.PayloadDispatcher;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the bolt as the indexer of a StormCrawler topology in local mode, wired as in the
 * StormCrawler archetype: fetcher, JSoup parser, this bolt, and a status updater listening
 * to the {@code status} stream of all three. The bolt also listens to the status updater's
 * {@code deletion} stream.
 */
class OpenCrawlingBoltTopologyTest {

    private static final String PAGE =
            "<html><head><title>Fixture</title></head><body><p>Hello world</p></body></html>";

    @Test
    @DisplayName("StormCrawler Topology: Dispatches Extracted Text and Reports FETCHED to the Status Updater")
    void testTopologyDispatchesExtractedTextAndReportsFetched() throws Exception {
        CapturingDispatcher.ITEMS.clear();
        CapturingStatusUpdater.STATUSES.clear();

        HttpServer server = startFixtureServer(() -> 200);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/page.html";
        try (LocalCluster cluster = new LocalCluster();
             LocalCluster.LocalTopology ignored = cluster.submitTopology("opencrawling-bolt-topology-test", config(), topology(url))) {
            awaitUntil(() -> dispatchedFor(url, OpenCrawlingBolt.ACTION_UPSERT).isPresent()
                            && CapturingStatusUpdater.STATUSES.containsKey(url),
                    Duration.ofSeconds(60));
        } finally {
            server.stop(0);
        }

        DispatchedItem upsert = dispatchedFor(url, OpenCrawlingBolt.ACTION_UPSERT)
                .orElseThrow(() -> new AssertionError("no UPSERT dispatched for " + url));
        String text = new ObjectMapper().readTree(upsert.jsonPayload()).path("content").path("text").asText();
        assertTrue(text.contains("Hello world"), "payload text: " + text);
        assertFalse(text.contains("<p>"), "payload text carries HTML markup: " + text);

        assertEquals(Status.FETCHED, CapturingStatusUpdater.STATUSES.get(url));
    }

    @Test
    @DisplayName("StormCrawler Topology: Page Turned 404 is Deleted with the Id of its UPSERT")
    void testPageTurned404IsDeletedWithTheIdOfItsUpsert() throws Exception {
        CapturingDispatcher.ITEMS.clear();
        CapturingStatusUpdater.STATUSES.clear();

        AtomicInteger pageRequests = new AtomicInteger();
        HttpServer server = startFixtureServer(() -> pageRequests.getAndIncrement() == 0 ? 200 : 404);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/page.html";
        Config conf = config();
        // refetch the page as soon as it is FETCHED; the first fetch error turns it into ERROR,
        // which the status updater emits on the deletion stream, and it is never fetched again
        conf.put("fetchInterval.default", 0);
        conf.put("fetchInterval.error", -1);
        conf.put("max.fetch.errors", 1);
        try (LocalCluster cluster = new LocalCluster();
             LocalCluster.LocalTopology ignored = cluster.submitTopology("opencrawling-bolt-deletion-test", conf, topology(url))) {
            awaitUntil(() -> dispatchedFor(url, OpenCrawlingBolt.ACTION_DELETE).isPresent()
                            && CapturingStatusUpdater.STATUSES.get(url) == Status.ERROR,
                    Duration.ofSeconds(60));
        } finally {
            server.stop(0);
        }

        DispatchedItem upsert = dispatchedFor(url, OpenCrawlingBolt.ACTION_UPSERT)
                .orElseThrow(() -> new AssertionError("no UPSERT dispatched for " + url));
        assertEquals(Status.ERROR, CapturingStatusUpdater.STATUSES.get(url), "status stored for " + url);
        DispatchedItem delete = dispatchedFor(url, OpenCrawlingBolt.ACTION_DELETE)
                .orElseThrow(() -> new AssertionError("no DELETE dispatched for " + url));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode tombstone = mapper.readTree(delete.jsonPayload());
        assertEquals(mapper.readTree(upsert.jsonPayload()).path("id").asText(), tombstone.path("id").asText());
        assertEquals("ERROR", tombstone.path("metadata").path("stormcrawler.status").asText());
        assertEquals("404", tombstone.path("metadata").path("http.status").asText());
    }

    /** Serves {@code /page.html} with the status code {@code pageStatus} gives for each request to it. */
    private static HttpServer startFixtureServer(IntSupplier pageStatus) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (!"/page.html".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            int status = pageStatus.getAsInt();
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    @SuppressWarnings("unchecked")
    private static Config config() {
        Config conf = new Config();
        conf.putAll((Map<String, Object>) Utils.findAndReadConfigFile("crawler-default.yaml", true).get("config"));
        conf.put("http.agent.name", "opencrawling-test");
        // allow the local HTTP fixture on 127.0.0.1
        conf.put("http.filter.ipaddress.exclude", "");
        // MemorySpout's queue is static, shared by the tests in this JVM: do not reschedule fetched pages
        conf.put("fetchInterval.default", -1);
        return conf;
    }

    private static StormTopology topology(String url) {
        TopologyBuilder builder = new TopologyBuilder();
        builder.setSpout("spout", new MemorySpout(url));
        builder.setBolt("fetch", new FetcherBolt()).shuffleGrouping("spout");
        builder.setBolt("parse", new JSoupParserBolt()).localOrShuffleGrouping("fetch");
        builder.setBolt("index", new OpenCrawlingBolt(new CapturingDispatcher()))
                .localOrShuffleGrouping("parse")
                .localOrShuffleGrouping("status", Constants.DELETION_STREAM_NAME);
        builder.setBolt("status", new CapturingStatusUpdater())
                .localOrShuffleGrouping("fetch", Constants.StatusStreamName)
                .localOrShuffleGrouping("parse", Constants.StatusStreamName)
                .localOrShuffleGrouping("index", Constants.StatusStreamName);
        return builder.createTopology();
    }

    private static Optional<DispatchedItem> dispatchedFor(String url, String action) {
        return CapturingDispatcher.ITEMS.stream()
                .filter(item -> url.equals(item.documentId()) && action.equals(item.action()))
                .findFirst();
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
    }

    /** Storm serializes the bolts, so the captured payloads live in a static list shared with the test. */
    static class CapturingDispatcher implements PayloadDispatcher {
        static final List<DispatchedItem> ITEMS = new CopyOnWriteArrayList<>();

        @Override
        public void init(Map<String, Object> conf) {
        }

        @Override
        public void dispatch(String documentId, String action, String jsonPayload) {
            ITEMS.add(new DispatchedItem(documentId, action, jsonPayload));
        }

        @Override
        public void close() {
        }
    }

    /** Records the last status stored for each URL. */
    static class CapturingStatusUpdater extends MemoryStatusUpdater {
        static final Map<String, Status> STATUSES = new ConcurrentHashMap<>();

        @Override
        public void store(String url, Status status, Metadata metadata, Optional<Date> nextFetch, Tuple t) throws Exception {
            STATUSES.put(url, status);
            super.store(url, status, metadata, nextFetch, t);
        }
    }
}
