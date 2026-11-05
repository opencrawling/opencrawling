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
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.security.CallerResolver;
import org.opencrawling.runtime.security.IdentityProperties;
import org.opencrawling.runtime.security.JwtCallerMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Through the job API, the per-caller cap counts runs by the verified caller: a caller at its cap gets {@code 429},
 * while other callers, the same name in another tenant, and runs without a verified caller still start.
 */
class JobControllerCallerCapTest {

    private final List<String> createdJobs = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private JobController controller;
    private JobRunRegistry registry;

    @BeforeEach
    void setUp() {
        JobOrchestrator orchestrator = mock(JobOrchestrator.class);
        // Every run holds its slot until the test ends.
        doAnswer(invocation -> {
            release.await();
            return true;
        }).when(orchestrator).runJob(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        ConnectorFactory factory = mock(ConnectorFactory.class);
        when(factory.repository(any())).thenReturn(mock(RepositoryConnector.class));
        when(factory.output(any(), any())).thenReturn(mock(OutputConnector.class));
        registry = new JobRunRegistry(0, 1);
        controller = new JobController(orchestrator, factory, registry, mock(JdbcTemplate.class));
        IdentityProperties identity = new IdentityProperties();
        identity.getJwt().setTenantClaim("tenant");
        controller.setCallerResolver(CallerResolver.verified(new JwtCallerMapper(identity.getJwt())));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        release.countDown();
        createdJobs.forEach(id -> {
            registry.stop(id);
            controller.deleteJob(id);
        });
    }

    private String newJob() {
        String name = "caller-cap-test-" + UUID.randomUUID();
        controller.saveJob(new JobController.JobDTO("new", name, "No_Such_Connector", "No_Such_Output", "", "/tmp",
                null, null, 0, null, null, null, "rag"));
        String id = controller.getAllJobs().stream().filter(job -> job.name().equals(name)).findFirst().orElseThrow().id();
        createdJobs.add(id);
        return id;
    }

    private static void as(Map<String, Object> claims) {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300), Map.of("alg", "RS256"), claims);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private int start(String jobId) {
        return controller.startJob(jobId).getStatusCode().value();
    }

    @Test
    void aCallerAtItsCapGets429AndTheJobStaysReady() {
        String first = newJob();
        String second = newJob();
        as(Map.of("preferred_username", "alice"));
        assertThat(start(first)).isEqualTo(200);

        assertThat(start(second)).isEqualTo(429);

        assertThat(controller.getJob(second).getBody().status()).isEqualTo("Ready");
        assertThat(registry.run(first)).get().extracting(JobRunRegistry.RunInfo::caller).isEqualTo("alice");
    }

    @Test
    void otherCallersAndTenantsHaveTheirOwnCap() {
        as(Map.of("preferred_username", "alice"));
        assertThat(start(newJob())).isEqualTo(200);

        as(Map.of("preferred_username", "bob"));
        assertThat(start(newJob())).isEqualTo(200);

        as(Map.of("preferred_username", "alice", "tenant", "acme"));
        assertThat(start(newJob())).isEqualTo(200);
        assertThat(start(newJob())).isEqualTo(429);
    }

    @Test
    void anAgentIsCappedAcrossTheUsersItActsFor() {
        as(Map.of("preferred_username", "alice", "act", Map.of("sub", "ingestion-agent")));
        assertThat(start(newJob())).isEqualTo(200);

        as(Map.of("preferred_username", "bob", "act", Map.of("sub", "ingestion-agent")));
        assertThat(start(newJob())).isEqualTo(429);
    }

    @Test
    void runsWithoutAVerifiedCallerAreNotCappedPerCaller() {
        assertThat(start(newJob())).isEqualTo(200);
        assertThat(start(newJob())).isEqualTo(200);
    }

    @Test
    void theCallerKeyIsTheVerifiedTriggerQualifiedByTenant() {
        assertThat(JobController.callerKey(ExecutionProvenance.verified("alice", null, List.of(), null, "api", null)))
                .isEqualTo("alice");
        assertThat(JobController.callerKey(ExecutionProvenance.verified("alice", null, List.of(), "acme", "api", null)))
                .isEqualTo("acme/alice");
        assertThat(JobController.callerKey(ExecutionProvenance.verified("alice", "agent", List.of(), null, "api", null)))
                .isEqualTo("agent");
        assertThat(JobController.callerKey(ExecutionProvenance.anonymous("api", null))).isNull();
        assertThat(JobController.callerKey(ExecutionProvenance.verified(null, null, List.of("finance"), null, "api", null)))
                .isNull();
    }
}
