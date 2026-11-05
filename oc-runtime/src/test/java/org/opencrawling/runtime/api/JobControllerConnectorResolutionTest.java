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
package org.opencrawling.runtime.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.connector.ConnectorResolutionException;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code startJob} builds its connectors through the {@link ConnectorFactory}; a connector that cannot be built
 * stops the job with {@code 409} and an error status instead of crawling another source.
 */
class JobControllerConnectorResolutionTest {

    private JobOrchestrator orchestrator;
    private ConnectorFactory factory;
    private JobController controller;
    private String jobId;

    @BeforeEach
    void setUp() {
        orchestrator = mock(JobOrchestrator.class);
        factory = mock(ConnectorFactory.class);
        controller = new JobController(orchestrator, factory, mock(JdbcTemplate.class));
        String name = "resolution-test-" + UUID.randomUUID();
        controller.saveJob(new JobController.JobDTO("new", name, "No_Such_Connector", "No_Such_Output", "", "/tmp",
                null, null, 0, null, null, null, "rag"));
        jobId = controller.getAllJobs().stream().filter(job -> job.name().equals(name)).findFirst().orElseThrow().id();
    }

    @AfterEach
    void removeJob() {
        controller.deleteJob(jobId);
    }

    @Test
    void aConnectorThatCannotBeBuiltRejectsTheJob() {
        when(factory.repository(any())).thenThrow(new ConnectorResolutionException("Repository connector 'x' has an unknown class 'y'."));

        ResponseEntity<Void> response = controller.startJob(jobId);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        JobController.JobDTO job = controller.getJob(jobId).getBody();
        assertThat(job.status()).isEqualTo("Error");
        assertThat(job.currentStage()).startsWith("Rejected: ").contains("unknown class");
        verifyNoInteractions(orchestrator);
    }

    @Test
    void theFactorysConnectorsAreTheOnesTheJobRunsWith() {
        RepositoryConnector repository = mock(RepositoryConnector.class);
        OutputConnector output = mock(OutputConnector.class);
        when(factory.repository(any())).thenReturn(repository);
        when(factory.output(any(), eq(PipelineMode.RAG))).thenReturn(output);

        assertThat(controller.startJob(jobId).getStatusCode().value()).isEqualTo(200);

        verify(orchestrator, timeout(5000)).runJob(eq(repository), eq(output), eq("/tmp"), any(), eq(jobId),
                anyString(), any(), eq(PipelineMode.RAG), anyString());
        // The job names connectors that are not saved: the factory is asked with no configuration, as before.
        verify(factory).repository(isNull());
        verify(factory).output(isNull(), eq(PipelineMode.RAG));
    }

    @Test
    void aConnectorFactorySpiBuildsTheConnectorAndRunsTheJob() {
        PersistenceHelper.save("connectors.json", java.util.List.of(new ConnectorController.ConnectorDTO(
                "Web_Crawler", "Web Crawler", "repository", "org.opencrawling.web.WebRepositoryConnector", 10, java.util.Map.of(
                "userAgent", "OpenCrawlingBot/1.0 (+https://opencrawling.github.io)"
        ))));
        String name = "web-test-" + UUID.randomUUID();
        controller.saveJob(new JobController.JobDTO("new", name, "Web_Crawler", "No_Such_Output", "", "https://example.com",
                null, null, 0, null, null, null, "rag", null));
        String webJobId = controller.getAllJobs().stream().filter(job -> job.name().equals(name)).findFirst().orElseThrow().id();
        try {
            OutputConnector output = mock(OutputConnector.class);
            when(factory.output(any(), eq(PipelineMode.RAG))).thenReturn(output);

            assertThat(controller.startJob(webJobId).getStatusCode().value()).isEqualTo(200);

            verify(orchestrator, timeout(5000)).runJob(
                    any(org.opencrawling.web.WebRepositoryConnector.class),
                    eq(output),
                    eq("https://example.com"),
                    any(),
                    eq(webJobId),
                    anyString(),
                    any(),
                    eq(PipelineMode.RAG),
                    anyString());
            verify(factory, never()).repository(any());
        } finally {
            controller.deleteJob(webJobId);
            PersistenceHelper.save("connectors.json", java.util.List.of());
        }
    }
}
