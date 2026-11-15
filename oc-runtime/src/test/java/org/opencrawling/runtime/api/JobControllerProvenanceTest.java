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
import org.mockito.ArgumentCaptor;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A run started through the job API records who started it: the verified caller of the request when there is one,
 * never an identity the request merely asserts.
 */
class JobControllerProvenanceTest {

    private JobOrchestrator orchestrator;
    private JobController controller;
    private String jobId;

    @BeforeEach
    void setUp() {
        orchestrator = mock(JobOrchestrator.class);
        ConnectorFactory factory = mock(ConnectorFactory.class);
        when(factory.repository(any())).thenReturn(mock(RepositoryConnector.class));
        when(factory.output(any(), any())).thenReturn(mock(OutputConnector.class));
        controller = new JobController(orchestrator, factory, new JobRunRegistry(0), mock(JdbcTemplate.class));
        String name = "provenance-test-" + UUID.randomUUID();
        controller.saveJob(new JobController.JobDTO("new", name, "No_Such_Connector", "No_Such_Output", "", "/tmp",
                null, null, 0, null, null, null, "rag"));
        jobId = controller.getAllJobs().stream().filter(job -> job.name().equals(name)).findFirst().orElseThrow().id();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        controller.deleteJob(jobId);
    }

    private static void authenticate(Map<String, Object> claims) {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300), Map.of("alg", "RS256"), claims);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private ExecutionProvenance startAndCaptureProvenance(String correlationId) {
        assertThat(controller.startJob(jobId, correlationId).getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<ExecutionProvenance> provenance = ArgumentCaptor.forClass(ExecutionProvenance.class);
        verify(orchestrator, timeout(5000)).runJob(any(), any(), anyString(), any(), eq(jobId), anyString(), any(), any(),
                anyString(), any(), provenance.capture());
        return provenance.getValue();
    }

    @Test
    void aVerifiedCallerIsRecordedWithGroupsTenantAndCorrelationId() {
        IdentityProperties identity = new IdentityProperties();
        identity.getJwt().setTenantClaim("tenant");
        controller.setCallerResolver(CallerResolver.verified(new JwtCallerMapper(identity.getJwt())));
        authenticate(Map.of("sub", "u-1", "preferred_username", "alice", "groups", List.of("finance"), "tenant", "acme"));

        ExecutionProvenance provenance = startAndCaptureProvenance("req-42");

        assertThat(provenance).isEqualTo(ExecutionProvenance.verified("alice", null, List.of("finance"), "acme",
                ExecutionProvenance.SOURCE_API, "req-42"));
    }

    @Test
    void anAgentWithADelegatedTokenIsTheTriggerActingForTheUser() {
        controller.setCallerResolver(CallerResolver.verified(new JwtCallerMapper(new IdentityProperties().getJwt())));
        authenticate(Map.of("sub", "u-1", "preferred_username", "alice", "act", Map.of("sub", "ingestion-agent")));

        ExecutionProvenance provenance = startAndCaptureProvenance(null);

        assertThat(provenance.triggeredBy()).isEqualTo("ingestion-agent");
        assertThat(provenance.onBehalfOf()).isEqualTo("alice");
        assertThat(provenance.callerVerified()).isTrue();
    }

    @Test
    void withoutAValidatedTokenTheRunIsAnonymous() {
        controller.setCallerResolver(CallerResolver.verified(new JwtCallerMapper(new IdentityProperties().getJwt())));

        ExecutionProvenance provenance = startAndCaptureProvenance("req-43");

        assertThat(provenance).isEqualTo(ExecutionProvenance.anonymous(ExecutionProvenance.SOURCE_API, "req-43"));
    }

    @Test
    void inNoneModeATokenInTheContextIsNotRecorded() {
        controller.setCallerResolver(CallerResolver.asserted());
        authenticate(Map.of("sub", "u-1", "preferred_username", "alice"));

        ExecutionProvenance provenance = startAndCaptureProvenance(null);

        assertThat(provenance.triggeredBy()).isNull();
        assertThat(provenance.callerVerified()).isFalse();
        assertThat(provenance.triggerSource()).isEqualTo("api");
    }

    @Test
    void withoutACallerResolverTheRunIsAnonymous() {
        authenticate(Map.of("sub", "u-1", "preferred_username", "alice"));

        ExecutionProvenance provenance = startAndCaptureProvenance(null);

        assertThat(provenance).isEqualTo(ExecutionProvenance.anonymous(ExecutionProvenance.SOURCE_API, null));
    }
}
