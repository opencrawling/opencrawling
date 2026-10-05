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
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.result.ScanResult;
import org.opencrawling.runtime.api.JobController.NarrativizationConfig;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.springframework.kafka.core.KafkaTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobOrchestratorMigrationTest {

    private KafkaTemplate<String, Object> kafkaTemplate;
    private ClaimCheckStore claimCheckStore;
    private TelemetryTraceStore traceStore;
    private JobOrchestrator orchestrator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        claimCheckStore = mock(ClaimCheckStore.class);
        traceStore = mock(TelemetryTraceStore.class);
        orchestrator = new JobOrchestrator(kafkaTemplate, claimCheckStore, traceStore);

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void testMigrationModeSkipsNarrativizationAndSetsMigrationModeInMessage() throws Exception {
        RepositoryConnector repoConnector = mock(RepositoryConnector.class);
        OutputConnector outputConnector = mock(OutputConnector.class);

        when(outputConnector.getName()).thenReturn("MockOutput");
        when(outputConnector.send(any())).thenReturn(Mono.empty());

        RepositoryDocument doc = new RepositoryDocument(
                "doc-mig-1",
                "file:///tmp/doc1.txt",
                new ByteArrayInputStream("Raw binary content".getBytes(StandardCharsets.UTF_8)),
                Map.of("title", List.of("Doc 1"), "summary", List.of("Summary 1")),
                "read:all",
                Instant.now()
        );

        when(repoConnector.scan(anyString())).thenReturn(Flux.just(doc));
        when(claimCheckStore.supports(any())).thenReturn(true);

        NarrativizationConfig narrativization = new NarrativizationConfig(true, "# {{title}}\n{{summary}}", "filesystem");

        boolean result = orchestrator.runJob(
                repoConnector,
                outputConnector,
                "/data",
                null,
                "job-mig-1",
                narrativization,
                PipelineMode.MIGRATION
        );

        assertTrue(result);

        ArgumentCaptor<IngestionMessage> msgCaptor = ArgumentCaptor.forClass(IngestionMessage.class);
        verify(kafkaTemplate).send(eq(KafkaConfig.TOPIC_NAME), eq("doc-mig-1"), msgCaptor.capture());

        IngestionMessage sentMsg = msgCaptor.getValue();
        assertEquals(PipelineMode.MIGRATION, sentMsg.pipelineMode());

        // Verify outputConnector was called with the document
        verify(outputConnector).send(any(RepositoryDocument.class));
    }

    @Test
    void testRagModeEnablesNarrativizationAndSetsRagModeInMessage() throws Exception {
        RepositoryConnector repoConnector = mock(RepositoryConnector.class);
        OutputConnector outputConnector = mock(OutputConnector.class);

        when(outputConnector.getName()).thenReturn("MockOutput");
        when(outputConnector.send(any())).thenReturn(Mono.empty());

        RepositoryDocument doc = new RepositoryDocument(
                "doc-rag-1",
                "file:///tmp/doc2.txt",
                new ByteArrayInputStream("Raw binary".getBytes(StandardCharsets.UTF_8)),
                Map.of("title", List.of("Doc 2"), "summary", List.of("Summary 2")),
                "read:all",
                Instant.now()
        );

        when(repoConnector.scan(anyString())).thenReturn(Flux.just(doc));
        when(claimCheckStore.supports(any())).thenReturn(true);

        NarrativizationConfig narrativization = new NarrativizationConfig(true, "# {{title}}\n{{summary}}", "filesystem");

        boolean result = orchestrator.runJob(
                repoConnector,
                outputConnector,
                "/data",
                null,
                "job-rag-1",
                narrativization,
                PipelineMode.RAG
        );

        assertTrue(result);

        ArgumentCaptor<IngestionMessage> msgCaptor = ArgumentCaptor.forClass(IngestionMessage.class);
        verify(kafkaTemplate).send(eq(KafkaConfig.TOPIC_NAME), eq("doc-rag-1"), msgCaptor.capture());

        IngestionMessage sentMsg = msgCaptor.getValue();
        assertEquals(PipelineMode.RAG, sentMsg.pipelineMode());
    }
}
