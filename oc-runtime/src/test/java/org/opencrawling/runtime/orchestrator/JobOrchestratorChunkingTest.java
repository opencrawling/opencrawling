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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.ChunkingSpec;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.runtime.api.JobController.NarrativizationConfig;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.springframework.kafka.core.KafkaTemplate;
import reactor.core.publisher.Flux;

class JobOrchestratorChunkingTest {

    private KafkaTemplate<String, Object> kafkaTemplate;
    private JobOrchestrator orchestrator;
    private RepositoryConnector connector;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        kafkaTemplate = mock(KafkaTemplate.class);
        ClaimCheckStore claimCheckStore = mock(ClaimCheckStore.class);
        when(claimCheckStore.put(anyString(), any(), anyLong(), any())).thenReturn(URI.create("file:///claims/c1"));
        orchestrator = new JobOrchestrator(kafkaTemplate, claimCheckStore, mock(TelemetryTraceStore.class));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        connector = mock(RepositoryConnector.class);
        when(connector.scan(anyString())).thenAnswer(invocation -> Flux.just(new RepositoryDocument("doc-1",
                "file:///tmp/doc1.txt", new ByteArrayInputStream("text".getBytes(StandardCharsets.UTF_8)),
                Map.of("title", List.of("Doc 1")), "read:all", Instant.now())));
    }

    @Test
    void aJobsChunkingTravelsWithEachDocument() {
        ChunkingSpec chunking = new ChunkingSpec(300, null, null, false);

        boolean result = orchestrator.runJob(connector, null, "/data", null, "job-1", "Job", NarrativizationConfig.disabled(),
                PipelineMode.RAG, "exec-1", () -> false, null, chunking);

        assertThat(result).isTrue();
        assertThat(sent().chunking()).isEqualTo(chunking);
    }

    @Test
    void otherJobsKeepTheDefaultChunking() {
        boolean result = orchestrator.runJob(connector, null, "/data", null, "job-2", "Job", NarrativizationConfig.disabled(),
                PipelineMode.RAG, "exec-2", () -> false, null);

        assertThat(result).isTrue();
        assertThat(sent().chunking()).isNull();
    }

    private IngestionMessage sent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaConfig.TOPIC_NAME), eq("doc-1"), captor.capture());
        return (IngestionMessage) captor.getValue();
    }
}
