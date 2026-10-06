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
package org.opencrawling.runtime.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.springframework.kafka.core.KafkaTemplate;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Crawler-side parallelism: claim-check uploads and Kafka publishes run in parallel lanes, while events of the
 * same document keep their order.
 */
class JobOrchestratorParallelismTest {

    private KafkaTemplate<String, Object> kafkaTemplate;
    private ClaimCheckStore claimCheckStore;
    private JobOrchestrator orchestrator;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final List<String> published = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        kafkaTemplate = mock(KafkaTemplate.class);
        claimCheckStore = mock(ClaimCheckStore.class);
        orchestrator = new JobOrchestrator(kafkaTemplate, claimCheckStore, mock(TelemetryTraceStore.class));

        when(kafkaTemplate.send(anyString(), anyString(), any())).thenAnswer(inv -> {
            IngestionMessage msg = inv.getArgument(2);
            published.add(msg.documentId() + ":" + msg.action());
            return CompletableFuture.completedFuture(null);
        });
        // Remote primary store: every local file is uploaded through the (slow) claim check.
        when(claimCheckStore.supports(any())).thenReturn(false);
        when(claimCheckStore.put(anyString(), any(), anyLong(), any())).thenAnswer(inv -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30);
            } finally {
                inFlight.decrementAndGet();
            }
            return URI.create("s3://claims/" + inv.getArgument(0, String.class));
        });
    }

    @Test
    void testStandaloneCrawlerUploadsAndPublishesInParallel() {
        orchestrator.setCrawlerConcurrency(4);
        List<RepositoryDocument> docs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            docs.add(doc("doc-" + i, DocumentAction.UPSERT));
        }
        RepositoryConnector repo = mock(RepositoryConnector.class);
        when(repo.supportsConcurrentProcessing()).thenReturn(true);
        when(repo.scan(anyString())).thenReturn(Flux.fromIterable(docs));

        assertTrue(orchestrator.runJob(repo, null, "/crawl", null, "job-par", null, PipelineMode.MIGRATION));

        assertEquals(40, published.size(), "every document is published exactly once");
        assertEquals(40, published.stream().distinct().count());
        assertTrue(maxInFlight.get() > 1, "uploads should overlap, max in flight was " + maxInFlight.get());
        assertTrue(maxInFlight.get() <= 4, "uploads must be bounded by the lane count, was " + maxInFlight.get());
    }

    @Test
    void testEventsOfTheSameDocumentKeepTheirOrder() {
        orchestrator.setCrawlerConcurrency(4);
        // UPSERT (slow claim-check upload) immediately followed by a DELETE (no upload) of the same document:
        // without per-document lanes the DELETE would overtake the UPSERT.
        List<RepositoryDocument> docs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            docs.add(doc("doc-" + i, DocumentAction.UPSERT));
            docs.add(doc("doc-" + i, DocumentAction.DELETE));
        }
        RepositoryConnector repo = mock(RepositoryConnector.class);
        when(repo.supportsConcurrentProcessing()).thenReturn(true);
        when(repo.scan(anyString())).thenReturn(Flux.fromIterable(docs));

        orchestrator.runJob(repo, null, "/crawl", null, "job-order", null, PipelineMode.MIGRATION);

        assertEquals(40, published.size());
        for (int i = 0; i < 20; i++) {
            int upsert = published.indexOf("doc-" + i + ":UPSERT");
            int delete = published.indexOf("doc-" + i + ":DELETE");
            assertTrue(upsert >= 0 && upsert < delete, "doc-" + i + " published out of order: " + published);
        }
    }

    @Test
    void testDirectOutputConnectorKeepsSequentialProcessing() {
        orchestrator.setCrawlerConcurrency(4);
        OutputConnector output = mock(OutputConnector.class);
        when(output.getName()).thenReturn("MockOutput");
        when(output.send(any())).thenReturn(Mono.empty());
        List<RepositoryDocument> docs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            docs.add(doc("doc-" + i, DocumentAction.UPSERT));
        }
        RepositoryConnector repo = mock(RepositoryConnector.class);
        when(repo.supportsConcurrentProcessing()).thenReturn(true);
        when(repo.scan(anyString())).thenReturn(Flux.fromIterable(docs));

        orchestrator.runJob(repo, output, "/crawl", null, "job-seq", null, PipelineMode.MIGRATION);

        assertEquals(10, published.size());
        assertEquals(1, maxInFlight.get(), "output connectors are not required to be thread-safe");
    }

    @Test
    void testConnectorWithoutOptInStaysSequentialAndPacesTheScan() {
        orchestrator.setCrawlerConcurrency(8);
        // e.g. CMIS/Alfresco open an HTTP response per document while scanning: the next document must not be
        // emitted before the previous one has been processed, otherwise open connections pile up.
        RepositoryConnector repo = mock(RepositoryConnector.class);
        when(repo.getName()).thenReturn("EagerStreamConnector");
        when(repo.scan(anyString())).thenReturn(Flux.range(0, 10).map(i -> {
            published.add("emit:doc-" + i);
            return doc("doc-" + i, DocumentAction.UPSERT);
        }));

        orchestrator.runJob(repo, null, "/crawl", null, "job-no-opt-in", null, PipelineMode.MIGRATION);

        assertEquals(1, maxInFlight.get(), "connectors that do not opt in must be processed sequentially");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            expected.add("emit:doc-" + i);
            expected.add("doc-" + i + ":UPSERT");
        }
        assertEquals(expected, published, "each document must be processed before the next one is emitted");
    }

    private static RepositoryDocument doc(String id, DocumentAction action) {
        return new RepositoryDocument(
                id,
                "file:///crawl/" + id + ".txt",
                new ByteArrayInputStream(id.getBytes(StandardCharsets.UTF_8)),
                Map.of("filename", List.of(id + ".txt")),
                "public",
                SecurityConfig.createPublic(),
                Instant.parse("2026-10-06T10:00:00Z"),
                action);
    }
}
