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
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.security.ManagementAuthorizationProperties;
import org.opencrawling.runtime.security.CallerResolver;
import org.opencrawling.runtime.security.IdentityProperties;
import org.opencrawling.runtime.security.JwtCallerMapper;
import org.opencrawling.runtime.security.ManagementAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * With management authorization on, the job API shows each caller only the jobs it may see and lets it do only what
 * the job's access allows. The decision rules themselves are in {@code ManagementAuthorizationTest}.
 */
class JobControllerAuthorizationTest {

    private JobController controller;
    private JobRunRegistry registry;
    private final List<String> created = new ArrayList<>();
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() {
        ConnectorFactory factory = mock(ConnectorFactory.class);
        when(factory.repository(any())).thenReturn(mock(RepositoryConnector.class));
        when(factory.output(any(), any())).thenReturn(mock(OutputConnector.class));
        registry = new JobRunRegistry(0);
        controller = new JobController(mock(JobOrchestrator.class), factory, registry, mock(JdbcTemplate.class));
        IdentityProperties identity = new IdentityProperties();
        identity.setMode("jwt");
        identity.setProtectApi(true);
        identity.getJwt().setTenantClaim("tenant");
        CallerResolver resolver = CallerResolver.verified(new JwtCallerMapper(identity.getJwt()));
        ManagementAuthorizationProperties properties = new ManagementAuthorizationProperties();
        properties.setEnabled(true);
        properties.setJobCreatorGroups(List.of("hr-team", "finance-team"));
        controller.setCallerResolver(resolver);
        controller.setAuthorization(new ManagementAuthorization(properties, resolver, identity));
    }

    @AfterEach
    void cleanUp() {
        asAdmin();
        created.forEach(controller::deleteJob);
        SecurityContextHolder.clearContext();
    }

    private static void as(String user, String tenant, String... groups) {
        Map<String, Object> claims = new HashMap<>(Map.of("sub", user + "-id", "preferred_username", user, "groups", List.of(groups)));
        if (tenant != null) {
            claims.put("tenant", tenant);
        }
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"), claims);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private static void asAdmin() {
        as("ops", null, "opencrawling-admin");
    }

    private static JobController.JobDTO job(String id, String name, JobAccess access) {
        return new JobController.JobDTO(id, name, "No_Such_Connector", "No_Such_Output", "", "/tmp", null, null, 0, null,
                null, null, "rag", null, access);
    }

    /** Creates a job as the current caller and returns it as stored. */
    private JobController.JobDTO create(String name, JobAccess access) {
        assertThat(controller.saveJob(job("new", name + "-" + run, access)).getStatusCode().value()).isEqualTo(201);
        JobController.JobDTO stored = controller.getAllJobs().stream().filter(j -> j.name().equals(name + "-" + run))
                .findFirst().orElseThrow();
        created.add(stored.id());
        return stored;
    }

    private List<String> visibleNames() {
        return controller.getAllJobs().stream().map(JobController.JobDTO::name).filter(n -> n.endsWith(run)).toList();
    }

    @Test
    void aCreatorOwnsItsJobInItsTenantWhateverTheRequestSays() {
        as("hr.lead", "acme", "hr-team");

        JobController.JobDTO stored = create("payroll", new JobAccess("someone.else", "globex", List.of(), List.of("hr-team")));

        assertThat(stored.access().owner()).isEqualTo("hr.lead");
        assertThat(stored.access().tenantId()).isEqualTo("acme");
        assertThat(stored.access().allowedGroups()).containsExactly("hr-team");
    }

    @Test
    void anAdminMayCreateAJobForSomeoneElse() {
        asAdmin();

        JobController.JobDTO stored = create("assigned", new JobAccess("hr.lead", "acme", List.of(), List.of()));
        JobController.JobDTO own = create("own", null);

        assertThat(stored.access().owner()).isEqualTo("hr.lead");
        assertThat(stored.access().tenantId()).isEqualTo("acme");
        assertThat(own.access().owner()).isEqualTo("ops");
    }

    @Test
    void onlyCreatorsAndAdminsMayCreateJobs() {
        as("intern", null, "interns");

        assertThat(controller.saveJob(job("new", "nope-" + run, null)).getStatusCode().value()).isEqualTo(403);
        SecurityContextHolder.clearContext();
        assertThat(controller.saveJob(job("new", "nope-" + run, null)).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void eachCallerSeesOnlyTheJobsItMaySee() {
        as("hr.lead", null, "hr-team");
        JobController.JobDTO hr = create("hr", new JobAccess(null, null, List.of(), List.of(), List.of(), List.of("auditors")));
        as("finance.lead", null, "finance-team");
        JobController.JobDTO finance = create("finance", null);

        as("hr.lead", null, "hr-team");
        assertThat(visibleNames()).containsExactly(hr.name());
        assertThat(controller.getJob(finance.id()).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.getJob(hr.id()).getBody().id()).isEqualTo(hr.id());
        as("auditor", null, "auditors");
        assertThat(visibleNames()).containsExactly(hr.name());
        asAdmin();
        assertThat(visibleNames()).containsExactlyInAnyOrder(hr.name(), finance.name());
        SecurityContextHolder.clearContext();
        assertThat(visibleNames()).isEmpty();
    }

    @Test
    void aJobFromBeforeAuthorizationIsForAdminsUntilOneAssignsIt() {
        asAdmin();
        JobController.JobDTO legacy = create("legacy", null);
        replaceAccess(legacy.id(), null);

        as("hr.lead", null, "hr-team");
        assertThat(controller.getJob(legacy.id()).getStatusCode().value()).isEqualTo(404);

        asAdmin();
        assertThat(controller.saveJob(job(legacy.id(), legacy.name(), new JobAccess("hr.lead", null, List.of(), List.of())))
                .getStatusCode().value()).isEqualTo(201);
        as("hr.lead", null, "hr-team");
        assertThat(controller.getJob(legacy.id()).getStatusCode().value()).isEqualTo(200);
    }

    /** Puts a job back in the state it had before authorization existed: no access block. */
    private void replaceAccess(String id, JobAccess access) {
        @SuppressWarnings("unchecked")
        List<JobController.JobDTO> jobs = (List<JobController.JobDTO>) ReflectionTestUtils
                .getField(controller, "jobs");
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).id().equals(id)) {
                jobs.set(i, jobs.get(i).withAccess(access));
            }
        }
    }

    @Test
    void runnersMayRunButNotEditOrDelete() {
        as("hr.lead", null, "hr-team");
        JobController.JobDTO hr = create("hr", new JobAccess(null, null, List.of("payroll.clerk"), List.of()));

        as("payroll.clerk", null);
        assertThat(controller.stopJob(hr.id()).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.pauseJob(hr.id()).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.saveJob(job(hr.id(), "renamed", null)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.deleteJob(hr.id()).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.findJobById(hr.id()).name()).isEqualTo(hr.name());
    }

    @Test
    void viewersMaySeeButNotRun() {
        as("hr.lead", null, "hr-team");
        JobController.JobDTO hr = create("hr", new JobAccess(null, null, List.of(), List.of(), List.of(), List.of("auditors")));

        as("auditor", null, "auditors");
        assertThat(controller.getJob(hr.id()).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.startJob(hr.id(), null).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.stopJob(hr.id()).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.pauseJob(hr.id()).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.getRun(hr.id()).getStatusCode().value()).as("no run, but visible").isEqualTo(404);
    }

    @Test
    void runsAreListedToWhoeverMaySeeTheirJob() {
        as("hr.lead", null, "hr-team");
        JobController.JobDTO hr = create("hr", new JobAccess(null, null, List.of(), List.of(), List.of(), List.of("auditors")));
        String dynamicJob = "dyn-" + run;
        registry.reserve(hr.id(), "exec-hr-" + run);
        registry.reserve(dynamicJob, "exec-dyn-" + run);

        as("auditor", null, "auditors");
        assertThat(controller.getRuns()).extracting(JobRunRegistry.RunInfo::jobId).containsExactly(hr.id());
        as("finance.lead", null, "finance-team");
        assertThat(controller.getRuns()).isEmpty();
        asAdmin();
        assertThat(controller.getRuns()).extracting(JobRunRegistry.RunInfo::jobId)
                .as("runs of jobs outside the list are for admins").containsExactlyInAnyOrder(hr.id(), dynamicJob);
    }

    @Test
    void strangersGetNotFoundForEverything() {
        as("hr.lead", null, "hr-team");
        JobController.JobDTO hr = create("hr", null);

        as("finance.lead", null, "finance-team");
        assertThat(controller.getJob(hr.id()).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.startJob(hr.id(), null).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.stopJob(hr.id()).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.pauseJob(hr.id()).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.saveJob(job(hr.id(), "renamed", null)).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.deleteJob(hr.id()).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.deleteJob("no-such-job-" + run).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.findJobById(hr.id()).name()).isEqualTo(hr.name());
    }

    @Test
    void theOwnerMayEditTheListsButNotHandTheJobOverOrChangeTenant() {
        as("hr.lead", "acme", "hr-team");
        JobController.JobDTO hr = create("hr", null);

        assertThat(controller.saveJob(job(hr.id(), hr.name(), new JobAccess("finance.lead", "globex", List.of(), List.of("auditors"))))
                .getStatusCode().value()).isEqualTo(201);

        JobAccess access = controller.findJobById(hr.id()).access();
        assertThat(access.owner()).isEqualTo("hr.lead");
        assertThat(access.tenantId()).isEqualTo("acme");
        assertThat(access.allowedGroups()).containsExactly("auditors");
        assertThat(controller.saveJob(job(hr.id(), hr.name(), null)).getStatusCode().value()).isEqualTo(201);
        assertThat(controller.findJobById(hr.id()).access()).as("an edit without access keeps it").isEqualTo(access);
    }

    @Test
    void anAdminMayReassignAJobAndKeepsWhatItLeavesOut() {
        as("hr.lead", "acme", "hr-team");
        JobController.JobDTO hr = create("hr", null);

        asAdmin();
        controller.saveJob(job(hr.id(), hr.name(), new JobAccess("hr.deputy", null, List.of(), List.of("hr-team"))));

        JobAccess access = controller.findJobById(hr.id()).access();
        assertThat(access.owner()).isEqualTo("hr.deputy");
        assertThat(access.tenantId()).as("left out, so kept").isEqualTo("acme");
        assertThat(access.allowedGroups()).containsExactly("hr-team");
    }

    @Test
    void theOwnerMayDeleteItsJob() {
        as("hr.lead", null, "hr-team");
        JobController.JobDTO hr = create("hr", null);

        assertThat(controller.deleteJob(hr.id()).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.findJobById(hr.id())).isNull();
    }

    @Test
    void statusUpdatesKeepTheAccess() {
        as("hr.lead", null, "hr-team");
        JobController.JobDTO hr = create("hr", new JobAccess(null, null, List.of(), List.of("hr-team")));

        controller.stopJob(hr.id());
        controller.recordPushExecution(hr.id(), 3, true);

        JobController.JobDTO stored = controller.findJobById(hr.id());
        assertThat(stored.status()).isEqualTo("Finished");
        assertThat(stored.access()).isEqualTo(hr.access());
    }

    @Test
    void offEveryoneSeesAndDoesEverythingAsBefore() {
        controller.setAuthorization(new ManagementAuthorization(new ManagementAuthorizationProperties(), (CallerResolver) null, (IdentityProperties) null));
        SecurityContextHolder.clearContext();

        JobController.JobDTO open = create("open", null);

        assertThat(open.access()).isNull();
        assertThat(visibleNames()).containsExactly(open.name());
        assertThat(controller.stopJob(open.id()).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.getRuns()).isNotNull();
        assertThat(controller.deleteJob("no-such-job-" + run).getStatusCode().value()).isEqualTo(200);
    }
}
