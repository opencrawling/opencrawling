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

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.Caller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtCallerMapperTest {

    private final JwtCallerMapper defaults = new JwtCallerMapper(new IdentityProperties().getJwt());

    @Test
    void aKeycloakTokenGivesTheUsernameAndTheRealmRolesAndGroups() {
        Map<String, Object> claims = Map.of(
                "sub", "5f1c-uuid",
                "preferred_username", "alice",
                "iss", "https://idp.example/realms/corp",
                "azp", "agent-client",
                "realm_access", Map.of("roles", List.of("finance", "offline_access")),
                "groups", List.of("auditors"));

        Caller caller = defaults.toCaller(claims);

        assertThat(caller.principal()).isEqualTo("alice");
        assertThat(caller.groups()).containsExactly("finance", "offline_access", "auditors");
        assertThat(caller.authenticated()).isTrue();
        assertThat(caller.tenant()).isNull();
        assertThat(caller.attributes())
                .containsEntry(JwtCallerMapper.SUBJECT, "5f1c-uuid")
                .containsEntry(JwtCallerMapper.ISSUER, "https://idp.example/realms/corp")
                .containsEntry(JwtCallerMapper.CLIENT, "agent-client")
                .doesNotContainKey(JwtCallerMapper.ACTOR);
    }

    @Test
    void scopesComeFromScopeAndScpAndNeverBecomeGroups() {
        Caller caller = defaults.toCaller(Map.of("preferred_username", "alice", "scope", " openid  oc:jobs:write ",
                "scp", List.of("oc:events:write", "openid", 7), "groups", List.of("finance")));

        assertThat(JwtCallerMapper.scopes(caller)).containsExactly("openid", "oc:jobs:write", "oc:events:write");
        assertThat(caller.groups()).containsExactly("finance");
        assertThat(JwtCallerMapper.scopes(defaults.toCaller(Map.of("sub", "x", "scp", "a b")))).containsExactly("a", "b");
        assertThat(JwtCallerMapper.scopes(defaults.toCaller(Map.of("sub", "x")))).isEmpty();
        assertThat(defaults.toCaller(Map.of("sub", "x")).attributes()).doesNotContainKey(JwtCallerMapper.SCOPES);
        assertThat(JwtCallerMapper.scopes(Caller.asserted("alice", List.of("oc:jobs:write")))).isEmpty();
    }

    @Test
    void theSubjectIsUsedWhenThereIsNoUsername() {
        Caller caller = defaults.toCaller(Map.of("sub", "service-account-crawler", "preferred_username", "  "));

        assertThat(caller.principal()).isEqualTo("service-account-crawler");
        assertThat(caller.groups()).isEmpty();
    }

    @Test
    void aTokenWithoutAnyPrincipalClaimHasNoPrincipal() {
        Caller caller = defaults.toCaller(Map.of("groups", List.of("finance")));

        assertThat(caller.principal()).isNull();
        assertThat(caller.groups()).containsExactly("finance");
        assertThat(caller.authenticated()).isTrue();
    }

    @Test
    void groupValuesAreTrimmedDeduplicatedAndNonStringsAndBlanksDropped() {
        Map<String, Object> realmAccess = new HashMap<>();
        realmAccess.put("roles", List.of(" finance ", "", 42, "finance"));
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "bob");
        claims.put("realm_access", realmAccess);
        claims.put("groups", "legal");

        Caller caller = defaults.toCaller(claims);

        assertThat(caller.groups()).containsExactly("finance", "legal");
    }

    @Test
    void groupClaimsNeverBecomeThePrincipalAndThePrincipalNeverBecomesAGroup() {
        Caller caller = defaults.toCaller(Map.of(
                "preferred_username", "finance",
                "groups", List.of("alice")));

        assertThat(caller.principal()).isEqualTo("finance");
        assertThat(caller.groups()).containsExactly("alice");
    }

    @Test
    void configuredClaimsAreUsedIncludingNamesThatContainDots() {
        JwtCallerMapper mapper = new JwtCallerMapper(
                List.of("upn", "email"),
                List.of("https://example.com/groups", "resource_access.opencrawling.roles"),
                "tid");

        Caller caller = mapper.toCaller(Map.of(
                "sub", "x",
                "email", "carol@example.com",
                "https://example.com/groups", List.of("hr"),
                "resource_access", Map.of("opencrawling", Map.of("roles", List.of("crawler-admin"))),
                "tid", "tenant-a"));

        assertThat(caller.principal()).isEqualTo("carol@example.com");
        assertThat(caller.groups()).containsExactly("hr", "crawler-admin");
        assertThat(caller.tenant()).isEqualTo("tenant-a");
    }

    @Test
    void aDelegatedTokenKeepsTheUserAsPrincipalAndRecordsTheActor() {
        Caller caller = defaults.toCaller(Map.of(
                "sub", "alice-id",
                "preferred_username", "alice",
                "act", Map.of("sub", "ingestion-agent")));

        assertThat(caller.principal()).isEqualTo("alice");
        assertThat(caller.attributes()).containsEntry(JwtCallerMapper.ACTOR, "ingestion-agent");
    }

    @Test
    void claimPathsDoNotWalkThroughNonObjects() {
        assertThat(JwtCallerMapper.claim(Map.of("realm_access", "roles"), "realm_access.roles")).isNull();
        assertThat(JwtCallerMapper.claim(Map.of(), "")).isNull();
    }
}
