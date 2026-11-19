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
package org.opencrawling.zammad;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ZammadSecurityMapperTest {

    @Test
    void publicTicketIncludesGroupCustomerAndSharedOrganization() {
        ZammadProperties props = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", "https://support.acme.com",
                "accessToken", "test-token"
        ));
        ZammadSecurityMapper mapper = new ZammadSecurityMapper(props);

        ZammadSecurityMapper.Mapped mapped = mapper.forTicket(
                "Tier-2 Identity Security",
                "claire.dupont@clientcorp.eu",
                "ClientCorp Europe",
                true,
                List.of("colleague@clientcorp.eu"),
                false
        );

        assertThat(mapped.groups()).containsExactly(
                "zammad-group-Tier-2-Identity-Security",
                "zammad-org-ClientCorp-Europe"
        );
        assertThat(mapped.users()).containsExactly(
                "claire.dupont@clientcorp.eu",
                "colleague@clientcorp.eu"
        );
        assertThat(mapped.security().permissions())
                .extracting(PermissionRule::identity)
                .containsExactly(
                        "zammad-group-Tier-2-Identity-Security",
                        "claire.dupont@clientcorp.eu",
                        "zammad-org-ClientCorp-Europe",
                        "colleague@clientcorp.eu"
                );
    }

    @Test
    void internalTicketStrictlyExcludesCustomerAndSharedOrganization() {
        ZammadProperties props = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", "https://support.acme.com",
                "accessToken", "test-token"
        ));
        ZammadSecurityMapper mapper = new ZammadSecurityMapper(props);

        ZammadSecurityMapper.Mapped mapped = mapper.forTicket(
                "Tier-2 Identity Security",
                "claire.dupont@clientcorp.eu",
                "ClientCorp Europe",
                true,
                List.of("colleague@clientcorp.eu"),
                true
        );

        assertThat(mapped.groups()).containsExactly("zammad-group-Tier-2-Identity-Security");
        assertThat(mapped.users()).isEmpty();
        assertThat(mapped.acl()).isEqualTo("zammad-group-Tier-2-Identity-Security");
        assertThat(mapped.security().permissions())
                .extracting(PermissionRule::identity)
                .containsExactly("zammad-group-Tier-2-Identity-Security");
    }

    @Test
    void nonSharedOrganizationIsOmittedFromPublicTicketAcl() {
        ZammadProperties props = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", "https://support.acme.com",
                "accessToken", "test-token"
        ));
        ZammadSecurityMapper mapper = new ZammadSecurityMapper(props);

        ZammadSecurityMapper.Mapped mapped = mapper.forTicket(
                "Support",
                "alice@client.com",
                "PrivateOrg",
                false,
                List.of(),
                false
        );

        assertThat(mapped.groups()).containsExactly("zammad-group-Support");
        assertThat(mapped.users()).containsExactly("alice@client.com");
    }

    @Test
    void missingGroupFailsClosedAndGrantsNothing() {
        ZammadProperties props = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", "https://support.acme.com",
                "accessToken", "test-token"
        ));
        ZammadSecurityMapper mapper = new ZammadSecurityMapper(props);

        ZammadSecurityMapper.Mapped mapped = mapper.forTicket(
                "   ",
                "alice@client.com",
                "ClientCorp",
                true,
                List.of(),
                false
        );

        assertThat(mapped.acl()).isEmpty();
        assertThat(mapped.security().permissions()).isEmpty();
        assertThat(mapped.users()).isEmpty();
        assertThat(mapped.groups()).isEmpty();
    }

    @Test
    void knowledgeBaseRespectsAllowPublicKbAndInternalFlag() {
        ZammadProperties allowPublicProps = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", "https://support.acme.com",
                "accessToken", "test-token",
                "allowPublicKb", "true"
        ));
        ZammadSecurityMapper mapper = new ZammadSecurityMapper(allowPublicProps);

        ZammadSecurityMapper.Mapped published = mapper.forKnowledgeBase(List.of("Agent"), List.of("Support"), false);
        assertThat(published.acl()).isEqualTo("public");

        ZammadSecurityMapper.Mapped internal = mapper.forKnowledgeBase(List.of("Agent", "Security Team"), List.of("Support"), true);
        assertThat(internal.acl()).isEqualTo("zammad-role-Agent,zammad-role-Security-Team");
        assertThat(internal.groups()).containsExactly("zammad-role-Agent", "zammad-role-Security-Team");
    }

    @Test
    void explicitKbPermissionsDenyingAllRolesFailClosedInsteadOfFallingBackToAgentGroups() {
        ZammadProperties props = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", "https://support.acme.com",
                "accessToken", "test-token",
                "allowPublicKb", "false"
        ));
        ZammadSecurityMapper mapper = new ZammadSecurityMapper(props);

        // No explicit permissions configured -> falls back to active agent groups
        ZammadSecurityMapper.Mapped fallback = mapper.forKnowledgeBase(List.of(), false, List.of("Support", "Ops"), true);
        assertThat(fallback.groups()).containsExactly("zammad-group-Support", "zammad-group-Ops");

        // Explicit granular permissions present (e.g. all entries access="none") -> fails closed
        ZammadSecurityMapper.Mapped denied = mapper.forKnowledgeBase(List.of(), true, List.of("Support", "Ops"), true);
        assertThat(denied.acl()).isEmpty();
        assertThat(denied.groups()).isEmpty();
        assertThat(denied.security().permissions()).isEmpty();
    }
}
