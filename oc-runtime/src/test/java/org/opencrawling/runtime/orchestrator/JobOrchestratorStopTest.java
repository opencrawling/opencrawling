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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.opencrawling.runtime.reporting.JobLedgerAccumulator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A stop ends a crawl: before the next document when the stop flag is set, and while the crawl is waiting when the
 * run's thread is interrupted. Either way the run reports {@code false} and its ledger execution is closed as stopped.
 */
class JobOrchestratorStopTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ClaimCheckStore claimCheckStore = mock(ClaimCheckStore.class);
    private final JobLedgerAccumulator ledger = mock(JobLedgerAccumulator.class);
    private JobOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new JobOrchestrator(kafkaTemplate, claimCheckStore, mock(TelemetryTraceStore.class));
        ReflectionTestUtils.setField(orchestrator, "ledgerAccumulator", ledger);
        orchestrator.setCrawlerConcurrency(1);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(claimCheckStore.supports(any())).thenReturn(true);
    }

    private static RepositoryDocument document(int i) {
        return new RepositoryDocument("doc-" + i, "file:///tmp/doc-" + i + ".txt",
                new ByteArrayInputStream(("text " + i).getBytes(StandardCharsets.UTF_8)),
                Map.of("title", List.of("Doc " + i)), "public", Instant.now());
    }

    @Test
    void theStopFlagEndsTheCrawlBeforeTheNextDocument() {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger published = new AtomicInteger();
        AtomicBoolean scanCancelled = new AtomicBoolean();
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenAnswer(invocation -> {
            if (published.incrementAndGet() == 3) {
                stop.set(true);
            }
            return CompletableFuture.completedFuture(null);
        });
        RepositoryConnector repository = mock(RepositoryConnector.class);
        when(repository.scan(anyString())).thenReturn(Flux.range(1, 1000).map(JobOrchestratorStopTest::document)
                .doOnCancel(() -> scanCancelled.set(true)));

        boolean result = orchestrator.runJob(repository, null, "/data", null, "job-1", "Job 1", null,
                PipelineMode.RAG, "exec-1", stop::get);

        assertThat(result).isFalse();
        assertThat(published).hasValue(3);
        assertThat(scanCancelled).isTrue();
        verify(ledger).completeExecution(eq("exec-1"), eq(false), eq(Map.of("error", "stopped")));
    }

    @Test
    void anInterruptEndsACrawlThatIsWaitingForTheSource() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        AtomicBoolean scanCancelled = new AtomicBoolean();
        RepositoryConnector repository = mock(RepositoryConnector.class);
        when(repository.scan(anyString())).thenReturn(Flux.<RepositoryDocument>never()
                .doOnSubscribe(s -> subscribed.countDown())
                .doOnCancel(() -> scanCancelled.set(true)));
        AtomicBoolean stop = new AtomicBoolean();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Thread run = Thread.ofVirtual().start(() -> result.complete(orchestrator.runJob(repository, null, "/data", null,
                "job-2", "Job 2", null, PipelineMode.RAG, "exec-2", stop::get)));
        assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();

        stop.set(true);
        run.interrupt();

        assertThat(result.get(10, TimeUnit.SECONDS)).isFalse();
        assertThat(scanCancelled).isTrue();
        verify(ledger).completeExecution(eq("exec-2"), eq(false), eq(Map.of("error", "stopped")));
    }

    @Test
    void anUnstoppedRunStillCompletes() {
        RepositoryConnector repository = mock(RepositoryConnector.class);
        when(repository.scan(anyString())).thenReturn(Flux.range(1, 5).map(JobOrchestratorStopTest::document));

        boolean result = orchestrator.runJob(repository, null, "/data", null, "job-3", "Job 3", null,
                PipelineMode.RAG, "exec-3", () -> false);

        assertThat(result).isTrue();
        verify(ledger).completeExecution(eq("exec-3"), eq(true), any());
    }

    @Test
    void aFailureWithoutAMessageIsRecordedInsteadOfCrashing() {
        RepositoryConnector repository = mock(RepositoryConnector.class);
        when(repository.scan(anyString())).thenReturn(Flux.error(new IllegalStateException()));

        boolean result = orchestrator.runJob(repository, null, "/data", null, "job-4", "Job 4", null,
                PipelineMode.RAG, "exec-4", () -> false);

        assertThat(result).isFalse();
        verify(ledger).completeExecution(eq("exec-4"), eq(false),
                argThat(details -> details.get("error") instanceof String error && !error.isBlank()));
    }
}
