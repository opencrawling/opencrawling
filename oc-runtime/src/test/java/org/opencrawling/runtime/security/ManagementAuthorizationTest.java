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
import org.junit.jupiter.api.Test;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.api.JobAccess;
import org.opencrawling.runtime.security.ManagementAuthorization.JobAction;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagementAuthorizationTest {

    private static final JobAccess HR_JOB = new JobAccess("hr.lead", null, List.of("payroll.clerk"), List.of("hr-team"),
            List.of("internal.auditor"), List.of("auditors"));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    static IdentityProperties jwtIdentity(boolean protectApi) {
        IdentityProperties identity = new IdentityProperties();
        identity.setMode("jwt");
        identity.setProtectApi(protectApi);
        identity.getJwt().setTenantClaim("tenant");
        return identity;
    }

    static ManagementAuthorization enabled(List<String> creatorGroups) {
        ManagementAuthorizationProperties properties = new ManagementAuthorizationProperties();
        properties.setEnabled(true);
        properties.setJobCreatorGroups(creatorGroups);
        IdentityProperties identity = jwtIdentity(true);
        return new ManagementAuthorization(properties, CallerResolver.verified(new JwtCallerMapper(identity.getJwt())), identity);
    }

    static void authenticate(String user, List<String> groups, String tenant) {
        Map<String, Object> claims = new HashMap<>(Map.of("sub", user + "-id", "preferred_username", user, "groups", groups));
        if (tenant != null) {
            claims.put("tenant", tenant);
        }
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"), claims);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private static Caller caller(String user, String... groups) {
        return new Caller(user, List.of(groups), null, true, Map.of());
    }

    @Test
    void offAllowsEverythingAsBefore() {
        ManagementAuthorization off = new ManagementAuthorization(new ManagementAuthorizationProperties(), (CallerResolver) null, (IdentityProperties) null);

        assertThat(off.isEnabled()).isFalse();
        assertThat(off.isAdmin()).isTrue();
        assertThat(off.canCreateJobs()).isTrue();
        assertThat(off.can(null, JobAction.MANAGE)).isTrue();
        assertThat(off.ranFor(null)).isTrue();
        assertThatCode(off::requireAdmin).doesNotThrowAnyException();
    }

    @Test
    void onNeedsJwtModeAndAProtectedApi() {
        ManagementAuthorizationProperties properties = new ManagementAuthorizationProperties();
        properties.setEnabled(true);

        assertThatThrownBy(() -> new ManagementAuthorization(properties, CallerResolver.asserted(), new IdentityProperties()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("protect-api=true");
        IdentityProperties unprotected = jwtIdentity(false);
        assertThatThrownBy(() -> new ManagementAuthorization(properties,
                CallerResolver.verified(new JwtCallerMapper(unprotected.getJwt())), unprotected))
                .isInstanceOf(IllegalStateException.class);
        IdentityProperties protectedApi = jwtIdentity(true);
        assertThatThrownBy(() -> new ManagementAuthorization(properties, CallerResolver.asserted(), protectedApi))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onWithoutAWebApiStartsAndRefusesEverything() {
        ManagementAuthorizationProperties properties = new ManagementAuthorizationProperties();
        properties.setEnabled(true);
        ManagementAuthorization worker = new ManagementAuthorization(properties, (CallerResolver) null, (IdentityProperties) null);

        assertThat(worker.currentCaller()).isEmpty();
        assertThat(worker.can(HR_JOB, JobAction.READ)).isFalse();
    }

    @Test
    void aRequestWithoutATokenMayDoNothing() {
        ManagementAuthorization authorization = enabled(List.of("hr-team"));

        assertThat(authorization.isAdmin()).isFalse();
        assertThat(authorization.canCreateJobs()).isFalse();
        assertThat(authorization.can(HR_JOB, JobAction.READ)).isFalse();
        assertThat(authorization.ranFor(ExecutionProvenance.verified("hr.lead", null, List.of(), null, "api", null))).isFalse();
        assertThatThrownBy(authorization::requireAdmin).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void adminsMayDoEverythingIncludingJobsWithoutAccess() {
        ManagementAuthorization authorization = enabled(List.of());
        Caller admin = caller("ops", "opencrawling-admin");

        for (JobAction action : JobAction.values()) {
            assertThat(authorization.permits(admin, null, action)).isTrue();
            assertThat(authorization.permits(admin, HR_JOB, action)).isTrue();
        }
        authenticate("ops", List.of("OpenCrawling-Admin"), null);
        assertThat(authorization.isAdmin()).as("group names match case-insensitively").isTrue();
        assertThat(authorization.canCreateJobs()).isTrue();
    }

    @Test
    void aJobWithoutAccessIsForAdminsOnly() {
        ManagementAuthorization authorization = enabled(List.of());

        assertThat(authorization.permits(caller("hr.lead", "hr-team"), null, JobAction.READ)).isFalse();
    }

    @Test
    void theOwnerMayDoEverything() {
        ManagementAuthorization authorization = enabled(List.of());
        Caller owner = caller("HR.Lead");

        for (JobAction action : JobAction.values()) {
            assertThat(authorization.permits(owner, HR_JOB, action)).isTrue();
        }
    }

    @Test
    void allowedUsersAndGroupsMaySeeAndRunButNotManage() {
        ManagementAuthorization authorization = enabled(List.of());

        for (Caller runner : List.of(caller("payroll.clerk"), caller("someone", "HR-TEAM"))) {
            assertThat(authorization.permits(runner, HR_JOB, JobAction.READ)).isTrue();
            assertThat(authorization.permits(runner, HR_JOB, JobAction.EXECUTE)).isTrue();
            assertThat(authorization.permits(runner, HR_JOB, JobAction.MANAGE)).isFalse();
        }
    }

    @Test
    void viewersMayOnlySee() {
        ManagementAuthorization authorization = enabled(List.of());

        for (Caller viewer : List.of(caller("internal.auditor"), caller("someone", "auditors"))) {
            assertThat(authorization.permits(viewer, HR_JOB, JobAction.READ)).isTrue();
            assertThat(authorization.permits(viewer, HR_JOB, JobAction.EXECUTE)).isFalse();
            assertThat(authorization.permits(viewer, HR_JOB, JobAction.MANAGE)).isFalse();
        }
    }

    @Test
    void anyoneElseMayDoNothing() {
        ManagementAuthorization authorization = enabled(List.of());
        Caller finance = caller("finance.lead", "finance-team");

        for (JobAction action : JobAction.values()) {
            assertThat(authorization.permits(finance, HR_JOB, action)).isFalse();
        }
        assertThat(authorization.permits(null, HR_JOB, JobAction.READ)).isFalse();
    }

    @Test
    void aTenantJobIsInvisibleToOtherTenantsWhateverTheLists() {
        ManagementAuthorization authorization = enabled(List.of());
        JobAccess acmeJob = new JobAccess("hr.lead", "acme", List.of(), List.of("hr-team"));

        assertThat(authorization.permits(new Caller("hr.lead", List.of(), "globex", true, Map.of()), acmeJob, JobAction.READ)).isFalse();
        assertThat(authorization.permits(new Caller("x", List.of("hr-team"), null, true, Map.of()), acmeJob, JobAction.READ)).isFalse();
        assertThat(authorization.permits(new Caller("x", List.of("hr-team"), "acme", true, Map.of()), acmeJob, JobAction.EXECUTE)).isTrue();
    }

    @Test
    void creatorGroupsMayCreateJobs() {
        ManagementAuthorization authorization = enabled(List.of("hr-team"));

        authenticate("hr.lead", List.of("hr-team"), null);
        assertThat(authorization.canCreateJobs()).isTrue();
        assertThat(authorization.isAdmin()).isFalse();
        authenticate("finance.lead", List.of("finance-team"), null);
        assertThat(authorization.canCreateJobs()).isFalse();
    }

    @Test
    void aRunIsTheCallersWhenItRanForThemInTheirTenant() {
        ManagementAuthorization authorization = enabled(List.of());
        authenticate("alice", List.of(), "acme");

        assertThat(authorization.ranFor(ExecutionProvenance.verified("alice", "agent", List.of(), "acme", "mcp", null)))
                .as("an agent acting for the caller").isTrue();
        assertThat(authorization.ranFor(ExecutionProvenance.verified("ALICE", null, List.of(), "acme", "api", null)))
                .as("without onBehalfOf, who triggered it").isTrue();
        assertThat(authorization.ranFor(ExecutionProvenance.verified("bob", "alice", List.of(), "acme", "mcp", null)))
                .as("the caller as the agent acting for someone else").isFalse();
        assertThat(authorization.ranFor(ExecutionProvenance.verified("alice", null, List.of(), "globex", "api", null))).isFalse();
        assertThat(authorization.ranFor(ExecutionProvenance.verified("alice", null, List.of(), null, "api", null))).isFalse();
        assertThat(authorization.ranFor(null)).isFalse();
    }
}
