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
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.opencrawling.runtime.reporting.JobLedgerAccumulator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A run writes each document through one path: the Kafka pipeline for outputs whose pipeline writer stores it, the
 * output connector directly otherwise. Writing through both stored every document twice.
 */
class JobOrchestratorWritePathTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ClaimCheckStore claimCheckStore = mock(ClaimCheckStore.class);
    private final JobLedgerAccumulator ledger = mock(JobLedgerAccumulator.class);
    private final TelemetryTraceStore traces = mock(TelemetryTraceStore.class);
    private final RepositoryConnector repository = mock(RepositoryConnector.class);
    private final OutputConnector output = mock(OutputConnector.class);
    private JobOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new JobOrchestrator(kafkaTemplate, claimCheckStore, traces);
        ReflectionTestUtils.setField(orchestrator, "ledgerAccumulator", ledger);
        orchestrator.setCrawlerConcurrency(1);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(claimCheckStore.supports(any())).thenReturn(true);
        when(repository.scan(anyString())).thenAnswer(invocation -> Flux.just(new RepositoryDocument("doc-1",
                "file:///tmp/doc-1.txt", new ByteArrayInputStream("text".getBytes(StandardCharsets.UTF_8)),
                Map.of("title", List.of("Doc 1")), "public", Instant.now())));
        when(output.getName()).thenReturn("Store");
        when(output.send(any())).thenReturn(Mono.empty());
    }

    private void run(PipelineMode mode) {
        orchestrator.runJob(repository, output, "/data", null, "job-1", "Job 1", null, mode, "exec-1", () -> false);
    }

    @Test
    void aRagRunWritesAnOutputItsPipelineWriterStoresThroughThePipelineOnly() {
        when(output.writtenByPipeline(PipelineMode.RAG)).thenReturn(true);

        run(PipelineMode.RAG);

        verify(kafkaTemplate).send(eq(KafkaConfig.TOPIC_NAME), eq("doc-1"), any());
        verify(output, never()).send(any());
        assertThat(ledgerStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void anOutputNoPipelineWriterStoresIsStillWrittenDirectly() {
        // The default: Hyland CIC, the migration and document outputs, third-party outputs.
        run(PipelineMode.RAG);

        verify(kafkaTemplate).send(eq(KafkaConfig.TOPIC_NAME), eq("doc-1"), any());
        verify(output).send(any());
    }

    @Test
    void theModeDecides() {
        // A vector store's writer stores RAG chunks; in a migration run its documents are still sent directly.
        when(output.writtenByPipeline(PipelineMode.RAG)).thenReturn(true);

        run(PipelineMode.MIGRATION);

        verify(output).send(any());
    }

    @Test
    void whenThePipelineIsTheOnlyPathAFailedPublishFailsTheDocument() {
        when(output.writtenByPipeline(PipelineMode.RAG)).thenReturn(true);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        run(PipelineMode.RAG);

        assertThat(ledgerStatus()).isEqualTo("FAILED");
        verify(output, never()).send(any());
    }

    @Test
    void aRunWithoutAnOutputNeverSendsDirectly() {
        assertThat(JobOrchestrator.sendsDirectly(null, PipelineMode.RAG)).isFalse();
        assertThat(JobOrchestrator.sendsDirectly(output, PipelineMode.RAG)).isTrue();
        when(output.writtenByPipeline(PipelineMode.RAG)).thenReturn(true);
        assertThat(JobOrchestrator.sendsDirectly(output, PipelineMode.RAG)).isFalse();
    }

    private String ledgerStatus() {
        ArgumentCaptor<DocumentLedgerEntry> entry = ArgumentCaptor.forClass(DocumentLedgerEntry.class);
        verify(ledger).recordDocument(entry.capture());
        return entry.getValue().status();
    }
}
