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

import ai.vespa.feed.client.DocumentId;
import ai.vespa.feed.client.FeedClient;
import ai.vespa.feed.client.OperationParameters;
import ai.vespa.feed.client.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.opencrawling.runtime.reporting.JobLedgerAccumulator;
import org.opencrawling.vespa.VespaDocumentMapper;
import org.opencrawling.vespa.VespaOutputConnector;
import org.opencrawling.vespa.config.VespaOutputProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A per-job Vespa output is written directly, so its tombstones reach it directly too. For a source whose content
 * goes through the claim-check store, the write carries the claim-check URI and the tombstone the source's: the
 * tombstone must still remove every chunk of the document.
 */
class PerJobVespaTombstoneTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE_URI = "https://repo.test/doc-9";
    private static final String CLAIM_URI = "s3://claims/doc-9_document";
    private static final String TEXT = "Section of the handbook covers retention, indexing and search practices. ".repeat(400);

    /** What the stand-in Vespa holds: document id → its fields. */
    private final Map<String, JsonNode> stored = new ConcurrentHashMap<>();
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ClaimCheckStore claimCheckStore = mock(ClaimCheckStore.class);
    private HttpServer vespa;
    private JobOrchestrator orchestrator;
    private VespaOutputConnector output;

    @BeforeEach
    void setUp() throws Exception {
        orchestrator = new JobOrchestrator(kafkaTemplate, claimCheckStore, mock(TelemetryTraceStore.class));
        ReflectionTestUtils.setField(orchestrator, "ledgerAccumulator", mock(JobLedgerAccumulator.class));
        orchestrator.setCrawlerConcurrency(1);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        // The source's content is kept in the claim-check store: the direct write then carries the claim's URI.
        when(claimCheckStore.supports(any())).thenReturn(false);
        when(claimCheckStore.put(anyString(), any(), anyLong(), any())).thenReturn(URI.create(CLAIM_URI));
        when(claimCheckStore.get(URI.create(CLAIM_URI)))
                .thenAnswer(call -> new ByteArrayInputStream(TEXT.getBytes(StandardCharsets.UTF_8)));

        startVespa();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class))).thenReturn(new float[1024]);
        // Built for one job, outside the pipeline: a RAG run sends its documents, and tombstones, here directly.
        output = new VespaOutputConnector(feedClient(), new VespaOutputProperties(
                "http://127.0.0.1:" + vespa.getAddress().getPort(), "opencrawling", "opencrawling_chunk", 1024, 5,
                false, null, null, null), new VespaDocumentMapper(), embeddingModel, null, true);
    }

    @AfterEach
    void stop() {
        vespa.stop(0);
    }

    @Test
    void aTombstoneRemovesTheChunksOfADocumentWrittenUnderItsClaimCheckUri() {
        run(new RepositoryDocument("doc-9", SOURCE_URI, new ByteArrayInputStream(TEXT.getBytes(StandardCharsets.UTF_8)),
                Map.of("mimeType", List.of("text/plain")), "public", SecurityConfig.createPublic(), Instant.now()));
        assertThat(stored).hasSizeGreaterThan(1)
                .allSatisfy((id, fields) -> {
                    assertThat(fields.path("uri").asText()).isEqualTo(CLAIM_URI);
                    assertThat(fields.path("document_id").asText()).isEqualTo("doc-9");
                });

        run(RepositoryDocument.createTombstone("doc-9", SOURCE_URI));

        assertThat(stored).isEmpty();
    }

    private void run(RepositoryDocument document) {
        RepositoryConnector repository = mock(RepositoryConnector.class);
        when(repository.scan(anyString())).thenReturn(Flux.just(document));
        orchestrator.runJob(repository, output, "/data", null, "job-9", "Job 9", null, PipelineMode.RAG, "exec-9",
                () -> false);
    }

    /** Keeps what is fed and removed, like Vespa. */
    private FeedClient feedClient() {
        FeedClient feedClient = mock(FeedClient.class);
        Result result = mock(Result.class);
        when(feedClient.put(any(DocumentId.class), anyString(), any(OperationParameters.class))).thenAnswer(call -> {
            stored.put(call.getArgument(0).toString(), JSON.readTree((String) call.getArgument(1)).path("fields"));
            return CompletableFuture.completedFuture(result);
        });
        when(feedClient.remove(any(DocumentId.class), any(OperationParameters.class))).thenAnswer(call -> {
            stored.remove(call.getArgument(0).toString());
            return CompletableFuture.completedFuture(result);
        });
        return feedClient;
    }

    /** Answers a chunk lookup like Vespa: the stored ids whose field holds the value. */
    private void startVespa() throws Exception {
        vespa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vespa.createContext("/search/", exchange -> {
            String yql = JSON.readTree(exchange.getRequestBody().readAllBytes()).path("yql").asText();
            String field = yql.substring(yql.indexOf(" where ") + 7, yql.indexOf(" contains "));
            String value = yql.substring(yql.indexOf("contains \"") + 10, yql.length() - 1);
            List<String> ids = new ArrayList<>();
            stored.forEach((id, fields) -> {
                if (value.equals(fields.path(field).asText(null))) {
                    ids.add("{\"id\":\"" + id + "\"}");
                }
            });
            byte[] answer = ("{\"root\":{\"children\":[" + String.join(",", ids) + "]}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        vespa.start();
    }
}
