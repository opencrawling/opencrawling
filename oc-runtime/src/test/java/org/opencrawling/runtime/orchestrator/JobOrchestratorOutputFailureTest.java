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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A document a direct output connector does not take is recorded as failed, with the reason, never as a success: the
 * ledger and the run's counts must say what the output really holds.
 */
class JobOrchestratorOutputFailureTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ClaimCheckStore claimCheckStore = mock(ClaimCheckStore.class);
    private final JobLedgerAccumulator ledger = mock(JobLedgerAccumulator.class);
    private final TelemetryTraceStore traces = mock(TelemetryTraceStore.class);
    private JobOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new JobOrchestrator(kafkaTemplate, claimCheckStore, traces);
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
    void aDocumentTheOutputRefusesIsRecordedAsAnOutputErrorWithTheReason() {
        RepositoryConnector repository = mock(RepositoryConnector.class);
        when(repository.scan(anyString())).thenReturn(Flux.range(1, 3).map(JobOrchestratorOutputFailureTest::document));
        OutputConnector output = mock(OutputConnector.class);
        when(output.getName()).thenReturn("PGVector_Output");
        when(output.send(any())).thenAnswer(invocation -> {
            RepositoryDocument document = invocation.getArgument(0);
            return "doc-2".equals(document.id())
                    ? Mono.error(new RuntimeException("Failed to process document: doc-2",
                            new IllegalStateException("Embedding failed for document doc-2; nothing was stored: "
                                    + "model 'mxbai-embed-large' not found")))
                    : Mono.empty();
        });

        orchestrator.runJob(repository, output, "/data", null, "job-1", "Job 1", null, PipelineMode.RAG, "exec-1",
                () -> false);

        ArgumentCaptor<DocumentLedgerEntry> entries = ArgumentCaptor.forClass(DocumentLedgerEntry.class);
        verify(ledger, atLeastOnce()).recordDocument(entries.capture());
        Map<String, DocumentLedgerEntry> byDocument = new java.util.HashMap<>();
        entries.getAllValues().forEach(entry -> byDocument.put(entry.documentId(), entry));

        assertThat(byDocument.get("doc-1").status()).isEqualTo("SUCCESS");
        assertThat(byDocument.get("doc-3").status()).isEqualTo("SUCCESS");
        DocumentLedgerEntry failed = byDocument.get("doc-2");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("OUTPUT_ERROR");
        assertThat(failed.errorMessage()).as("the reason, not just that it failed")
                .contains("PGVector_Output", "model 'mxbai-embed-large' not found");
        assertThat(entries.getAllValues()).filteredOn(entry -> "doc-2".equals(entry.documentId()))
                .as("never also recorded as a success").hasSize(1);
        verify(traces).recordError(eq("job-1"), eq("ERROR"), eq("OutputConnector"), any(), any());
    }

    @Test
    void theInnermostMessageIsTheReason() {
        assertThat(JobOrchestrator.rootMessage(new RuntimeException("wrapper", new IllegalStateException("the cause"))))
                .isEqualTo("the cause");
        assertThat(JobOrchestrator.rootMessage(new IllegalStateException())).contains("IllegalStateException");
    }
}
