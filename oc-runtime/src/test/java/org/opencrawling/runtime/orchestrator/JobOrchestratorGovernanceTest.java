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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.runtime.api.JobController.NarrativizationConfig;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.runtime.governance.GovernanceProperties;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.springframework.kafka.core.KafkaTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** With governance on, nothing reaches an output connector without passing the gate. */
class JobOrchestratorGovernanceTest {

    private KafkaTemplate<String, Object> kafkaTemplate;
    private TelemetryTraceStore traceStore;
    private JobOrchestrator orchestrator;
    private RepositoryConnector connector;
    private OutputConnector output;
    private final GovernanceProperties governance = new GovernanceProperties();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        kafkaTemplate = mock(KafkaTemplate.class);
        ClaimCheckStore claimCheckStore = mock(ClaimCheckStore.class);
        when(claimCheckStore.put(anyString(), any(), anyLong(), any())).thenReturn(URI.create("file:///claims/c1"));
        when(claimCheckStore.supports(any())).thenReturn(true);
        traceStore = mock(TelemetryTraceStore.class);
        orchestrator = new JobOrchestrator(kafkaTemplate, claimCheckStore, traceStore);
        orchestrator.setGovernanceProperties(governance);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        connector = mock(RepositoryConnector.class);
        when(connector.scan(anyString())).thenAnswer(invocation -> Flux.just(new RepositoryDocument("doc-1",
                "file:///tmp/doc1.txt", new ByteArrayInputStream("text".getBytes(StandardCharsets.UTF_8)),
                Map.of("title", List.of("Doc 1")), "read:all", Instant.now())));
        output = mock(OutputConnector.class);
        when(output.send(any())).thenReturn(Mono.empty());
        when(output.getName()).thenReturn("Direct");
    }

    private boolean run(OutputConnector out, PipelineMode mode) {
        return orchestrator.runJob(connector, out, "/data", null, "job-1", "Job", NarrativizationConfig.disabled(),
                mode, "exec-1", () -> false, null);
    }

    @Test
    void withGovernanceOnARagRunWithADirectOutputIsRefused() {
        governance.setEnabled(true);

        assertThat(orchestrator.bypassesGovernance(output, PipelineMode.RAG)).isTrue();
        assertThat(run(output, PipelineMode.RAG)).isFalse();

        verifyNoInteractions(output);
        verify(connector, never()).scan(anyString());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(traceStore).recordError(eq("job-1"), eq("ERROR"), eq("Governance"), contains("decoupled"), any());
    }

    @Test
    void withGovernanceOnTheDecoupledPathAndMigrationsRun() {
        governance.setEnabled(true);

        assertThat(run(null, PipelineMode.RAG)).isTrue();
        verify(kafkaTemplate).send(eq(KafkaConfig.TOPIC_NAME), eq("doc-1"), any());
        assertThat(orchestrator.bypassesGovernance(null, PipelineMode.RAG)).isFalse();
        // Migrations embed nothing.
        assertThat(orchestrator.bypassesGovernance(output, PipelineMode.MIGRATION)).isFalse();
    }

    @Test
    void withGovernanceOffDirectOutputsRunAsBefore() {
        assertThat(run(output, PipelineMode.RAG)).isTrue();

        verify(output).send(any());
        assertThat(orchestrator.bypassesGovernance(output, PipelineMode.RAG)).isFalse();
        orchestrator.setGovernanceProperties(null);
        assertThat(orchestrator.bypassesGovernance(output, PipelineMode.RAG)).isFalse();
    }
}
