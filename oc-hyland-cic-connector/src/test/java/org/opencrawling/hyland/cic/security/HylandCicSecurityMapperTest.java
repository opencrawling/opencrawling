/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.hyland.cic.security;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.hyland.cic.model.HylandCicPermission;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HylandCicSecurityMapperTest {

    @Test
    void mapsStandardAces() {
        HylandCicSecurityMapper mapper = new HylandCicSecurityMapper();
        List<HylandCicPermission> docAces = List.of(
                new HylandCicPermission("alice", "USER", "READ", true),
                new HylandCicPermission("bob", "USER", "WRITE", true),
                new HylandCicPermission("admins", "GROUP", "ADMIN", true),
                new HylandCicPermission("bad_actor", "USER", "READ", false)
        );

        SecurityConfig config = mapper.map(docAces, List.of());

        assertThat(config.inheritanceEnabled()).isFalse();
        assertThat(config.permissions()).hasSize(4);

        PermissionRule aliceRule = findRule(config, "alice");
        assertThat(aliceRule.access()).isEqualTo("read");
        assertThat(aliceRule.identityType()).isEqualTo("user");

        PermissionRule bobRule = findRule(config, "bob");
        assertThat(bobRule.access()).isEqualTo("write");

        PermissionRule adminRule = findRule(config, "admins");
        assertThat(adminRule.access()).isEqualTo("admin");
        assertThat(adminRule.identityType()).isEqualTo("group");

        PermissionRule badActorRule = findRule(config, "bad_actor");
        assertThat(badActorRule.access()).isEqualTo("deny");
    }

    @Test
    void mapsEveryoneToPublicWithoutTenant() {
        HylandCicSecurityMapper mapper = new HylandCicSecurityMapper();
        List<HylandCicPermission> aces = List.of(
                new HylandCicPermission("everyone", "GROUP", "READ", true)
        );

        SecurityConfig config = mapper.map(aces, null);
        assertThat(config.permissions()).hasSize(1);
        PermissionRule rule = config.permissions().get(0);
        assertThat(rule.identity()).isEqualTo("public");
        assertThat(rule.identityType()).isEqualTo("public");
        assertThat(rule.access()).isEqualTo("read");
    }

    @Test
    void tenantQualifiesIdentities() {
        HylandCicSecurityMapper mapper = new HylandCicSecurityMapper("TenantX");
        List<HylandCicPermission> aces = List.of(
                new HylandCicPermission("charlie", "USER", "READ", true),
                new HylandCicPermission("everyone", "GROUP", "READ", true)
        );

        SecurityConfig config = mapper.map(aces, null);
        PermissionRule charlie = findRule(config, "TenantX/charlie");
        assertThat(charlie).isNotNull();
        assertThat(charlie.identity()).isEqualTo("TenantX/charlie");
        assertThat(charlie.displayName()).isEqualTo("charlie");

        PermissionRule everyone = findRule(config, "TenantX/everyone");
        assertThat(everyone).isNotNull();
        assertThat(everyone.identity()).isEqualTo("TenantX/everyone");
    }

    @Test
    void inheritsFolderAcesAndMerges() {
        HylandCicSecurityMapper mapper = new HylandCicSecurityMapper();
        List<HylandCicPermission> folderAces = List.of(
                new HylandCicPermission("finance-team", "GROUP", "READ", true)
        );
        List<HylandCicPermission> docAces = List.of(
                new HylandCicPermission("auditor", "USER", "READ", true)
        );

        SecurityConfig config = mapper.map(docAces, folderAces);
        assertThat(config.inheritanceEnabled()).isTrue();
        assertThat(config.permissions()).hasSize(2);
        assertThat(findRule(config, "finance-team")).isNotNull();
        assertThat(findRule(config, "auditor")).isNotNull();
    }

    @Test
    void emptyAcesReturnsPublic() {
        HylandCicSecurityMapper mapper = new HylandCicSecurityMapper();
        SecurityConfig config = mapper.map(List.of(), List.of());
        assertThat(config.permissions()).hasSize(1);
        assertThat(config.permissions().get(0).identity()).isEqualTo("public");
    }

    private PermissionRule findRule(SecurityConfig config, String identity) {
        return config.permissions().stream()
                .filter(r -> r.identity().equalsIgnoreCase(identity))
                .findFirst()
                .orElse(null);
    }
}
