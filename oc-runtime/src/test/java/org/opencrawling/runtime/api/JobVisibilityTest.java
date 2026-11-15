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
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.reporting.JobReportSummary;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.dynamicjob.DynamicJobService;
import org.opencrawling.runtime.observability.ObservabilityMcpTools;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.opencrawling.runtime.reporting.JobReportService;
import org.opencrawling.runtime.security.ManagementAuthorizationProperties;
import org.opencrawling.runtime.security.CallerResolver;
import org.opencrawling.runtime.security.IdentityProperties;
import org.opencrawling.runtime.security.JwtCallerMapper;
import org.opencrawling.runtime.security.ManagementAuthorization;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may read a job's reports, traces and errors with management authorization on: the job's access for jobs in the
 * job list; admins, and the user it ran for, for dynamic jobs and jobs deleted since.
 */
class JobVisibilityTest {

    private static final JobAccess HR = new JobAccess("hr.lead", null, List.of(), List.of("hr-team"));

    private JobController jobs;
    private DynamicJobService dynamic;
    private JobVisibility visibility;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        IdentityProperties identity = new IdentityProperties();
        identity.setMode("jwt");
        identity.setProtectApi(true);
        CallerResolver resolver = CallerResolver.verified(new JwtCallerMapper(identity.getJwt()));
        ManagementAuthorizationProperties properties = new ManagementAuthorizationProperties();
        properties.setEnabled(true);
        ManagementAuthorization authorization = new ManagementAuthorization(properties, resolver, identity);

        jobs = mock(JobController.class);
        JobController.JobDTO hrJob = new JobController.JobDTO("hr-1", "hr", "r", "o", "", "/", null, null, 0, null, null,
                null, "rag", null, HR);
        when(jobs.findJobById("hr-1")).thenReturn(hrJob);
        when(jobs.canRead(hrJob)).thenAnswer(call -> authorization.can(HR, ManagementAuthorization.JobAction.READ));
        dynamic = mock(DynamicJobService.class);
        ObjectProvider<DynamicJobService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dynamic);
        visibility = new JobVisibility(authorization, jobs, provider);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void as(String user, String... groups) {
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"),
                Map.of("sub", user + "-id", "preferred_username", user, "groups", List.of(groups)));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private static JobReportSummary report(String jobId, ExecutionProvenance provenance) {
        JobReportSummary summary = mock(JobReportSummary.class);
        when(summary.jobId()).thenReturn(jobId);
        when(summary.provenance()).thenReturn(provenance);
        return summary;
    }

    @Test
    void aListedJobIsReadByWhoeverItsAccessAllows() {
        as("clerk", "hr-team");
        assertThat(visibility.canReadJob("hr-1")).isTrue();
        assertThat(visibility.canReadReport(report("hr-1", null))).isTrue();

        as("finance.lead", "finance-team");
        assertThat(visibility.canReadJob("hr-1")).isFalse();
        assertThat(visibility.canReadReport(report("hr-1", ExecutionProvenance.verified("finance.lead", null, List.of(), null, "api", null))))
                .as("having started a run of someone else's job is not enough").isFalse();
    }

    @Test
    void aDynamicJobIsReadByAdminsAndTheUserItWasStartedFor() {
        when(dynamic.isStartedFor(any(Caller.class), eq("dyn-1"))).thenAnswer(call -> "alice".equals(((Caller) call.getArgument(0)).principal()));

        as("alice");
        assertThat(visibility.canReadJob("dyn-1")).isTrue();
        as("bob");
        assertThat(visibility.canReadJob("dyn-1")).isFalse();
        as("ops", "opencrawling-admin");
        assertThat(visibility.canReadJob("dyn-1")).isTrue();
        SecurityContextHolder.clearContext();
        assertThat(visibility.canReadJob("dyn-1")).isFalse();
    }

    @Test
    void theReportOfAForgottenRunIsReadByTheUserItRanFor() {
        JobReportSummary agentRun = report("dyn-gone", ExecutionProvenance.verified("alice", "agent", List.of(), null, "mcp", null));

        as("alice");
        assertThat(visibility.canReadReport(agentRun)).isTrue();
        as("agent");
        assertThat(visibility.canReadReport(agentRun)).as("the agent acted for alice; the run is hers").isFalse();
        as("ops", "opencrawling-admin");
        assertThat(visibility.canReadReport(agentRun)).isTrue();
        assertThat(visibility.canReadReport(null)).isFalse();
    }

    @Test
    void offEverythingIsVisible() {
        ManagementAuthorization off = new ManagementAuthorization(new ManagementAuthorizationProperties(), (CallerResolver) null,
                (IdentityProperties) null);
        @SuppressWarnings("unchecked")
        JobVisibility open = new JobVisibility(off, jobs, mock(ObjectProvider.class));

        assertThat(open.canReadJob("anything")).isTrue();
        assertThat(open.canReadReport(null)).isTrue();
    }

    @Test
    void reportsListAndLedgersFollowTheVisibility() {
        JobReportService service = mock(JobReportService.class);
        JobReportSummary hrRun = report("hr-1", null);
        JobReportSummary otherRun = report("dyn-gone", ExecutionProvenance.verified("bob", null, List.of(), null, "api", null));
        when(service.getAllSummaries()).thenReturn(List.of(hrRun, otherRun));
        when(service.getSummary("exec-hr")).thenReturn(Optional.of(hrRun));
        when(service.getSummary("exec-other")).thenReturn(Optional.of(otherRun));
        when(service.getSummary("exec-none")).thenReturn(Optional.empty());
        when(service.getExportFile(anyString(), anyString())).thenReturn(Optional.empty());
        ReportController controller = new ReportController(service);
        controller.setVisibility(visibility);

        as("clerk", "hr-team");
        assertThat(controller.getAllReports().getBody()).containsExactly(hrRun);
        assertThat(controller.getReport("hr-1", "exec-hr").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.getReport("hr-1", "exec-other").getStatusCode().value())
                .as("the path's job id decides nothing").isEqualTo(404);
        assertThat(controller.getDocumentLedger("hr-1", "exec-other", "ALL", null, 0, 50).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.getDocumentLedger("hr-1", "exec-none", "ALL", null, 0, 50).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.exportReport("hr-1", "exec-other", "json").getStatusCode().value()).isEqualTo(404);
        verify(service, never()).getDocumentEntries(anyString(), any(), any(), anyInt(), anyInt());
        verify(service, never()).getExportFile(anyString(), anyString());

        as("ops", "opencrawling-admin");
        assertThat(controller.getAllReports().getBody()).containsExactly(hrRun, otherRun);
        controller.getDocumentLedger("hr-1", "exec-none", "ALL", null, 0, 50);
        verify(service).getDocumentEntries(eq("exec-none"), any(), any(), anyInt(), anyInt());
    }

    @Test
    void tracesAndErrorsOfAnInvisibleJobLookEmptyAndMetricsAreForAdmins() {
        TelemetryTraceStore store = mock(TelemetryTraceStore.class);
        ObservabilityMcpTools tools = new ObservabilityMcpTools(store);
        tools.setVisibility(visibility);

        as("finance.lead", "finance-team");
        assertThat(tools.fetchJobTraces("hr-1").spans()).isEmpty();
        assertThat(tools.getErrorLogs("hr-1", "all").errorCount()).isZero();
        verify(store, never()).getSpansForJob(anyString());
        verify(store, never()).getErrorsForJob(anyString());
        assertThatThrownBy(() -> tools.queryThroughputMetrics(null)).isInstanceOf(ResponseStatusException.class);

        ObservabilityController observability = new ObservabilityController(null, tools, store, jobs);
        observability.setVisibility(visibility);
        assertThat(observability.diagnoseJob("hr-1").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void creatorsSeeConnectorsWithoutTheirConfiguration() {
        IdentityProperties identity = new IdentityProperties();
        identity.setMode("jwt");
        identity.setProtectApi(true);
        CallerResolver resolver = CallerResolver.verified(new JwtCallerMapper(identity.getJwt()));
        ManagementAuthorizationProperties properties = new ManagementAuthorizationProperties();
        properties.setEnabled(true);
        properties.setJobCreatorGroups(List.of("hr-team"));
        ConnectorController connectors = new ConnectorController(null);
        ReflectionTestUtils.setField(connectors, "authorization",
                new ManagementAuthorization(properties, resolver, identity));

        as("hr.lead", "hr-team");
        assertThat(connectors.getAllConnectors()).isNotEmpty().allSatisfy(c -> assertThat(c.configuration()).isNull());
        assertThat(connectors.getConnectors("repository")).allSatisfy(c -> assertThat(c.configuration()).isNull());
        as("ops", "opencrawling-admin");
        assertThat(connectors.getAllConnectors()).anySatisfy(c -> assertThat(c.configuration()).isNotNull());
    }
}
