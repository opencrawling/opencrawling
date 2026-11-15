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
package org.opencrawling.runtime.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.opencrawling.runtime.security.IdentityTestApplication.PUBLIC_KEY_FILE;
import static org.opencrawling.runtime.security.IdentityTestApplication.validToken;

/**
 * Over HTTP, in {@code jwt} mode with {@code protect-api}: a job started with a bearer token records the token's
 * caller and the request's {@code X-Correlation-Id}, although the run itself executes on another thread.
 */
@SpringBootTest(classes = {IdentityTestApplication.class, JobStartProvenanceHttpTest.JobApi.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "opencrawling.security.identity.mode=JWT",
                "opencrawling.security.identity.protect-api=true",
                "opencrawling.security.identity.jwt.tenant-claim=tenant",
                "spring.security.oauth2.resourceserver.jwt.audiences=" + IdentityTestApplication.AUDIENCE,
                "spring.ai.mcp.server.protocol=STREAMABLE"
        })
class JobStartProvenanceHttpTest {

    @Configuration
    @Import(JobController.class)
    static class JobApi {
        @Bean
        JobOrchestrator jobOrchestrator() {
            return mock(JobOrchestrator.class);
        }

        @Bean
        ConnectorFactory connectorFactory() {
            ConnectorFactory factory = mock(ConnectorFactory.class);
            when(factory.repository(any())).thenReturn(mock(RepositoryConnector.class));
            when(factory.output(any(), any())).thenReturn(mock(OutputConnector.class));
            return factory;
        }

        @Bean
        JobRunRegistry jobRunRegistry() {
            return new JobRunRegistry(0);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JobController controller;

    @Autowired
    JobOrchestrator orchestrator;

    private String jobId;

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
                () -> "file:" + PUBLIC_KEY_FILE.toAbsolutePath());
    }

    @BeforeEach
    void createJob() {
        clearInvocations(orchestrator);
        String name = "http-provenance-test-" + UUID.randomUUID();
        controller.saveJob(new JobController.JobDTO("new", name, "No_Such_Connector", "No_Such_Output", "", "/tmp",
                null, null, 0, null, null, null, "rag"));
        jobId = controller.getAllJobs().stream().filter(job -> job.name().equals(name)).findFirst().orElseThrow().id();
    }

    @AfterEach
    void deleteJob() {
        controller.deleteJob(jobId);
    }

    private HttpResponse<String> start(String bearer, String correlationId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/jobs/" + jobId + "/start"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aJobStartedWithATokenRecordsTheTokensCaller() throws Exception {
        String token = validToken(Map.of("preferred_username", "alice", "groups", List.of("finance"), "tenant", "acme"));

        assertThat(start(token, "req-42").statusCode()).isEqualTo(200);

        ArgumentCaptor<ExecutionProvenance> provenance = ArgumentCaptor.forClass(ExecutionProvenance.class);
        verify(orchestrator, timeout(5000)).runJob(any(), any(), anyString(), any(), eq(jobId), anyString(), any(), any(),
                anyString(), any(), provenance.capture());
        assertThat(provenance.getValue()).isEqualTo(ExecutionProvenance.verified("alice", null, List.of("finance"), "acme",
                ExecutionProvenance.SOURCE_API, "req-42"));
    }

    @Test
    void withoutATokenTheJobIsNotStarted() throws Exception {
        assertThat(start(null, "req-43").statusCode()).isEqualTo(401);
    }
}
