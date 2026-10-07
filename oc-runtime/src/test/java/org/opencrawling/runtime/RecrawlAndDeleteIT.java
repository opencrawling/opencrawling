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
package org.opencrawling.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.runtime.messaging.VectorStoreWriterConsumer;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.stormcrawler.StormCrawlerRepositoryConnector;
import org.opencrawling.stormcrawler.config.StormCrawlerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Crawls a local 100-page site three times through the Kafka pipeline (test embeddings, 1024
 * dimensions) and checks {@code vector_store_1024}: a second crawl of the same pages leaves one row
 * per page, and pages that turn 404 are purged. A second test feeds chunks straight to the writer and
 * checks that a document re-crawled with fewer chunks loses its extra rows.
 *
 * <p>Requires PostgreSQL and Kafka from {@code docker-compose.yml}.
 */
@SpringBootTest(properties = {
    // KafkaConfig forces auto.offset.reset=earliest, so a new group replays every message ever
    // published to the topics. -Dit.kafka.group lets a local run use a group already moved to
    // the end of the topics.
    "spring.kafka.consumer.group-id=${it.kafka.group:test-group-${random.uuid}}"
})
@ActiveProfiles("test")
class RecrawlAndDeleteIT {

    private static final Logger log = LoggerFactory.getLogger(RecrawlAndDeleteIT.class);
    private static final int PAGES = 100;
    private static final int GONE_PAGES = 10;
    private static final long STABLE_MS = 5_000;
    private static final long TIMEOUT_MS = 120_000;
    private static final long CLEANUP_ZERO_MS = 3_000;
    private static final long CLEANUP_TIMEOUT_MS = 30_000;

    @Autowired
    private JobOrchestrator jobOrchestrator;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate jdbc;

    @Autowired
    private VectorStoreWriterConsumer writer;

    /** Prefix of every document id this run creates; set once the local site is up. */
    private String prefix;

    @Test
    void recrawlKeepsOneRowPerPageAndGonePagesArePurged() throws Exception {
        String token = "recrawl-" + UUID.randomUUID().toString().substring(0, 8);
        Set<Integer> gone = ConcurrentHashMap.newKeySet();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int page = -1;
            if (path.startsWith("/" + token + "/p") && path.endsWith(".html")) {
                page = Integer.parseInt(path.substring(("/" + token + "/p").length(), path.length() - ".html".length()));
            }
            if (page < 0 || gone.contains(page)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = ("<html><head><title>Page " + page + "</title></head><body><p>"
                    + token + "-page-" + page + " alpha beta gamma</p></body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        prefix = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + token + "/";
        String urls = String.join(",", IntStream.range(0, PAGES).mapToObj(i -> prefix + "p" + i + ".html").toList());

        StormCrawlerProperties properties = new StormCrawlerProperties();
        properties.setDelayMs(0);
        StormCrawlerRepositoryConnector connector = new StormCrawlerRepositoryConnector(properties);

        try {
            crawlAndCheck("crawl", connector, urls, PAGES);
            crawlAndCheck("same crawl again", connector, urls, PAGES);
            IntStream.range(0, GONE_PAGES).forEach(gone::add);
            crawlAndCheck("crawl with " + GONE_PAGES + " pages gone", connector, urls, PAGES - GONE_PAGES);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shorterDocumentLosesTheRowsPastItsNewLength() {
        prefix = "http://shrink-" + UUID.randomUUID().toString().substring(0, 8) + "/";
        String documentId = prefix + "p0.html";
        for (int i = 0; i < 3; i++) {
            writer.consume(chunk(documentId, i, 3));
        }
        assertThat(rowsOf(documentId)).as("rows before the document shrinks").isEqualTo(3);

        writer.consume(chunk(documentId, 0, 1));

        assertThat(rowsOf(documentId)).as("rows after the document shrinks").isEqualTo(1);
    }

    private static DocumentEmbeddedMessage chunk(String documentId, int index, int total) {
        float[] embedding = new float[1024];
        embedding[0] = 1.0f;
        String chunkId = UUID.nameUUIDFromBytes((documentId + "#" + index).getBytes(StandardCharsets.UTF_8)).toString();
        return new DocumentEmbeddedMessage(documentId, chunkId, "chunk " + index,
                Map.of("documentId", documentId, "chunk_index", index, "total_chunks", total), embedding);
    }

    private int rowsOf(String documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = ?",
                Integer.class, documentId);
    }

    /**
     * Repeats the delete until no row of this run is left for {@code CLEANUP_ZERO_MS}: after a failure,
     * messages still in the pipeline can write rows again.
     */
    @AfterEach
    void deleteThisRunsRows() throws InterruptedException {
        if (prefix == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + CLEANUP_TIMEOUT_MS;
        long zeroSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            if (jdbc.update("DELETE FROM vector_store_1024 WHERE metadata->>'documentId' LIKE ?", prefix + "%") > 0) {
                zeroSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - zeroSince >= CLEANUP_ZERO_MS) {
                return;
            }
            Thread.sleep(500);
        }
        log.warn("Rows with document id prefix {} were still being written {} ms after the test", prefix, CLEANUP_TIMEOUT_MS);
    }

    /**
     * Runs one crawl, then waits until the counts equal the expected ones and stay unchanged for
     * {@code STABLE_MS}; fails with the last counts if that does not happen within {@code TIMEOUT_MS}.
     */
    private void crawlAndCheck(String phase, StormCrawlerRepositoryConnector connector, String urls, int expectedPages)
            throws InterruptedException {
        Counts expected = new Counts(expectedPages, expectedPages, expectedPages, expectedPages);
        Instant start = Instant.now();
        assertThat(jobOrchestrator.runJob(connector, null, urls)).as(phase + ": runJob").isTrue();
        long jobMs = Instant.now().toEpochMilli() - start.toEpochMilli();

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        Counts counts = count(start);
        long lastChange = System.currentTimeMillis();
        while (!(counts.equals(expected) && System.currentTimeMillis() - lastChange >= STABLE_MS)) {
            if (System.currentTimeMillis() >= deadline) {
                fail("%s: expected %s for %d ms within %d ms, last counts %s", phase, expected, STABLE_MS, TIMEOUT_MS, counts);
            }
            Thread.sleep(500);
            Counts now = count(start);
            if (!now.equals(counts)) {
                counts = now;
                lastChange = System.currentTimeMillis();
            }
        }
        log.info("{}: job {} ms, counts reached {} {} ms after job start",
                phase, jobMs, counts, lastChange - start.toEpochMilli());
    }

    /**
     * @param rows chunk rows of the site's pages
     * @param pages distinct pages with at least one row
     * @param pagesWithOwnText pages with a row holding the page's own marker text and no markup
     * @param pagesWrittenThisPhase pages whose rows were written by a crawl started at or after the phase start
     */
    private record Counts(int rows, int pages, int pagesWithOwnText, int pagesWrittenThisPhase) {
    }

    private Counts count(Instant phaseStart) {
        String sql = "SELECT count(*), count(DISTINCT metadata->>'documentId'), "
                + "count(DISTINCT CASE WHEN content LIKE '%-page-' || substring(metadata->>'documentId' from '/p([0-9]+)[.]html$') || ' alpha%' "
                + "  AND content NOT LIKE '%<%' THEN metadata->>'documentId' END), "
                + "count(DISTINCT CASE WHEN (metadata->>'lastModified')::timestamptz >= ? THEN metadata->>'documentId' END) "
                + "FROM vector_store_1024 WHERE metadata->>'documentId' LIKE ?";
        return jdbc.queryForObject(sql,
                (rs, rowNum) -> new Counts(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)),
                OffsetDateTime.ofInstant(phaseStart, ZoneOffset.UTC), prefix + "%");
    }
}
