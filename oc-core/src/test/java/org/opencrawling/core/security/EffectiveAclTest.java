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
package org.opencrawling.core.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveAclTest {

    private static PermissionRule rule(String identity, String type, String access) {
        return new PermissionRule(identity, type, identity, access);
    }

    @Test
    void readWriteAndAdminAllGrantRead() {
        EffectiveAcl acl = EffectiveAcl.resolve(new SecurityConfig(true, List.of(
                rule("alice", "user", "read"), rule("editors", "group", "write"),
                rule("admins", "group", "admin"), rule("bob", "user", "deny"),
                rule("carol", "user", "none"))), null);

        assertThat(acl.allowedRead()).containsExactly("user:alice", "group:editors", "group:admins");
        assertThat(acl.deniedRead()).containsExactly("user:bob");
        assertThat(acl.inheritanceEnabled()).isTrue();
        assertThat(acl.isPublic()).isFalse();
    }

    @Test
    void publicTypedRulesGrantPublicNotTheRepositoryName() {
        EffectiveAcl acl = EffectiveAcl.resolve(new SecurityConfig(false, List.of(
                rule("GROUP_EVERYONE", "public", "read"), rule("anonymous", "PUBLIC", "read"))), null);

        assertThat(acl.allowedRead()).containsExactly("public");
        assertThat(acl.isPublic()).isTrue();
    }

    @Test
    void aRealPublicRuleIsNotThePlaceholderEvenWhenTheAclNamesItsIdentity() {
        // The OIS endpoint derives acl from the same rules, so acl holds the raw repository name.
        EffectiveAcl acl = EffectiveAcl.resolve(new SecurityConfig(false, List.of(
                rule("GROUP_EVERYONE", "public", "read"))), "GROUP_EVERYONE");

        assertThat(acl.allowedRead()).containsExactly("public");
        assertThat(acl.isPublic()).isTrue();
    }

    @Test
    void rulesDecideAndTheLegacyAclIsIgnored() {
        EffectiveAcl acl = EffectiveAcl.resolve(new SecurityConfig(false, List.of(rule("alice", "user", "read"))),
                "user:alice;group:finance,secured");

        assertThat(acl.allowedRead()).containsExactly("user:alice");
        assertThat(acl.security().permissions()).extracting(PermissionRule::identity).containsExactly("user:alice");
    }

    @Test
    void restrictiveAclOverridesTheDefaultPublicPlaceholder() {
        EffectiveAcl acl = EffectiveAcl.resolve(SecurityConfig.createPublic(), "finance, alice");

        assertThat(acl.allowedRead()).containsExactly("finance", "alice");
        assertThat(acl.isPublic()).isFalse();
        assertThat(acl.security().permissions()).extracting(PermissionRule::identity)
                .containsExactly("finance", "alice");
    }

    @Test
    void defaultPublicStaysPublicWhenTheAclAddsNoIdentity() {
        assertThat(EffectiveAcl.resolve(SecurityConfig.createPublic(), "read").isPublic()).isTrue();
        assertThat(EffectiveAcl.resolve(SecurityConfig.createPublic(), "Public").isPublic()).isTrue();
        assertThat(EffectiveAcl.resolve(SecurityConfig.createPublic(), null).security())
                .isEqualTo(SecurityConfig.createPublic());
    }

    @Test
    void legacyAclAloneGrantsItsTokensAndDropsTheReadKeyword() {
        EffectiveAcl acl = EffectiveAcl.resolve(null, "finance, read ,PUBLIC,finance");

        assertThat(acl.allowedRead()).containsExactly("finance", "public");
        assertThat(acl.deniedRead()).isEmpty();
        assertThat(acl.security().permissions()).hasSize(2);
    }

    @Test
    void noRulesAndNoAclGrantsNothing() {
        EffectiveAcl acl = EffectiveAcl.resolve(null, "  ");

        assertThat(acl.allowedRead()).isEmpty();
        assertThat(acl.security()).isNull();
    }

    @Test
    void readsTheMapShapeASecurityConfigTakesAfterKafka() {
        Map<String, Object> security = Map.of(
                "inheritanceEnabled", true,
                "permissions", List.of(
                        Map.of("identity", "finance", "identityType", "group", "access", "admin"),
                        Map.of("identity", "everyone", "identityType", "public", "access", "read"),
                        Map.of("identity", "mallory", "identityType", "user", "access", "deny")));

        EffectiveAcl acl = EffectiveAcl.resolve(security, List.of("ignored"));

        assertThat(acl.allowedRead()).containsExactly("group:finance", "public");
        assertThat(acl.deniedRead()).containsExactly("user:mallory");
        assertThat(acl.inheritanceEnabled()).isTrue();
        assertThat(acl.isPublic()).isFalse();
    }

    @Test
    void resolvesIdentityTypeToUserAndGroupPrefixes() {
        EffectiveAcl acl = EffectiveAcl.resolve(new SecurityConfig(true, List.of(
                rule("alice", "USER", "read"),
                rule("finance", "GROUP", "read"),
                rule("ops", "role", "read"),
                rule("contractors", "ROLE", "deny"),
                rule("user:bob", "user", "read"),
                rule("group:devs", "group", "read"),
                rule("user:carol", "group", "read"),
                rule("legacy-token", "acl", "read"),
                rule("bare-identity", null, "read"),
                rule("custom-type", "other", "read")
        )), null);

        assertThat(acl.allowedRead()).containsExactly(
                "user:alice",
                "group:finance",
                "group:ops",
                "user:bob",
                "group:devs",
                "user:carol",
                "legacy-token",
                "bare-identity",
                "custom-type"
        );
        assertThat(acl.deniedRead()).containsExactly("group:contractors");
        assertThat(acl.security().permissions()).extracting(PermissionRule::identity).containsExactly(
                "user:alice",
                "group:finance",
                "group:ops",
                "group:contractors",
                "user:bob",
                "group:devs",
                "user:carol",
                "legacy-token",
                "bare-identity",
                "custom-type"
        );
    }

    @Test
    void testIdentityOf() {
        assertThat(EffectiveAcl.identityOf(null, null)).isNull();
        assertThat(EffectiveAcl.identityOf((PermissionRule) null)).isNull();
        assertThat(EffectiveAcl.identityOf("  ", "user")).isEmpty();
        assertThat(EffectiveAcl.identityOf("public", "user")).isEqualTo("public");
        assertThat(EffectiveAcl.identityOf("EVERYONE", "public")).isEqualTo("public");
        assertThat(EffectiveAcl.identityOf("alice", "user")).isEqualTo("user:alice");
        assertThat(EffectiveAcl.identityOf("user:alice", "user")).isEqualTo("user:alice");
        assertThat(EffectiveAcl.identityOf("finance", "group")).isEqualTo("group:finance");
        assertThat(EffectiveAcl.identityOf("group:finance", "group")).isEqualTo("group:finance");
        assertThat(EffectiveAcl.identityOf("admin", "role")).isEqualTo("group:admin");
        assertThat(EffectiveAcl.identityOf("bare", null)).isEqualTo("bare");
        assertThat(EffectiveAcl.identityOf("bare", "unknown")).isEqualTo("bare");
    }
}
