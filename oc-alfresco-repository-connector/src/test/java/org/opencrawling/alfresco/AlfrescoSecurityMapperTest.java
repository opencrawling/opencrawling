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
package org.opencrawling.alfresco;

import static org.assertj.core.api.Assertions.assertThat;

import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.SecurityConfig;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;

import com.fasterxml.jackson.databind.ObjectMapper;

class AlfrescoSecurityMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingPermissionsAreNeverPublic() {
        AlfrescoSecurityMapper.SecurityResult res1 = AlfrescoSecurityMapper.mapSecurity(null);
        assertThat(res1.securityConfig().permissions()).isEmpty();
        assertThat(res1.aclString()).isEmpty();
        assertThat(EffectiveAcl.resolve(res1.securityConfig(), res1.aclString()).allowedRead()).isEmpty();
        assertThat(res1.identityUsers()).isEmpty();
        assertThat(res1.identityGroups()).isEmpty();
        assertThat(res1.isInherited()).isTrue();

        AlfrescoSecurityMapper.SecurityResult res2 = AlfrescoSecurityMapper.mapSecurity(mapper.createObjectNode());
        assertThat(res2.aclString()).isEmpty();
        assertThat(EffectiveAcl.resolve(res2.securityConfig(), res2.aclString()).allowedRead()).isEmpty();
    }

    @Test
    void testPublicEveryonePermissions() throws Exception {
        String json = """
            {
              "isInheritanceEnabled": true,
              "inherited": [
                {
                  "authorityId": "GROUP_EVERYONE",
                  "name": "Consumer",
                  "accessStatus": "ALLOWED"
                }
              ],
              "locallySet": [
                {
                  "authorityId": "mjackson",
                  "name": "Collaborator",
                  "accessStatus": "ALLOWED"
                }
              ]
            }
            """;
        var node = mapper.readTree(json);
        var result = AlfrescoSecurityMapper.mapSecurity(node);

        assertThat(result.aclString()).isEqualTo("public");
        assertThat(result.isInherited()).isTrue();
        assertThat(result.identityUsers()).contains("mjackson");
        // The other rules are kept next to the public one.
        assertThat(EffectiveAcl.resolve(result.securityConfig(), result.aclString()).allowedRead())
                .containsExactlyInAnyOrder("public", "mjackson");
    }

    @Test
    void testFineGrainedZeroTrustPermissions() throws Exception {
        String json = """
            {
              "isInheritanceEnabled": false,
              "locallySet": [
                {
                  "authorityId": "alice",
                  "name": "Coordinator",
                  "accessStatus": "ALLOWED"
                },
                {
                  "authorityId": "bob",
                  "name": "Consumer",
                  "accessStatus": "ALLOWED"
                },
                {
                  "authorityId": "GROUP_FINANCE",
                  "name": "Editor",
                  "accessStatus": "ALLOWED"
                },
                {
                  "authorityId": "charlie",
                  "name": "Consumer",
                  "accessStatus": "DENIED"
                }
              ]
            }
            """;
        var node = mapper.readTree(json);
        var result = AlfrescoSecurityMapper.mapSecurity(node);

        assertThat(result.isInherited()).isFalse();
        assertThat(result.identityUsers()).containsExactlyInAnyOrder("alice", "bob");
        assertThat(result.identityGroups()).containsExactly("GROUP_FINANCE");
        assertThat(result.aclString()).contains("alice", "bob", "GROUP_FINANCE");
        assertThat(result.aclString()).doesNotContain("charlie");

        assertThat(result.securityConfig().permissions()).hasSize(4);
        assertThat(EffectiveAcl.resolve(result.securityConfig(), result.aclString()).deniedRead()).containsExactly("charlie");

        PermissionRule aliceRule = result.securityConfig().permissions().stream()
                .filter(r -> r.identity().equals("alice"))
                .findFirst().orElseThrow();
        assertThat(aliceRule.identityType()).isEqualTo("user");
        assertThat(aliceRule.access()).isEqualTo("admin");

        PermissionRule groupRule = result.securityConfig().permissions().stream()
                .filter(r -> r.identity().equals("GROUP_FINANCE"))
                .findFirst().orElseThrow();
        assertThat(groupRule.identityType()).isEqualTo("group");
        assertThat(groupRule.access()).isEqualTo("write");
    }

    @Test
    void aDeniedUserIsDeniedEvenWhenTheirGroupMayRead() throws Exception {
        var result = AlfrescoSecurityMapper.mapSecurity(mapper.readTree("""
            {"isInheritanceEnabled": false, "locallySet": [
              {"authorityId": "carol", "name": "Consumer", "accessStatus": "DENIED"},
              {"authorityId": "GROUP_finance", "name": "Consumer", "accessStatus": "ALLOWED"}]}
            """));

        EffectiveAcl acl = EffectiveAcl.resolve(result.securityConfig(), result.aclString());
        assertThat(acl.allowedRead()).containsExactly("GROUP_finance");
        assertThat(acl.deniedRead()).containsExactly("carol");
    }

    @Test
    void denialsStillApplyNextToAnEveryoneGrant() throws Exception {
        var result = AlfrescoSecurityMapper.mapSecurity(mapper.readTree("""
            {"isInheritanceEnabled": true,
             "locallySet": [{"authorityId": "mallory", "name": "Consumer", "accessStatus": "DENIED"}],
             "inherited": [{"authorityId": "GROUP_EVERYONE", "name": "Consumer", "accessStatus": "ALLOWED"}]}
            """));

        EffectiveAcl acl = EffectiveAcl.resolve(result.securityConfig(), result.aclString());
        assertThat(acl.allowedRead()).containsExactly("public");
        assertThat(acl.deniedRead()).containsExactly("mallory");
        assertThat(acl.isPublic()).isFalse();
    }

    @Test
    void entriesThatGrantNothingAreNotPublic() throws Exception {
        var result = AlfrescoSecurityMapper.mapSecurity(mapper.readTree("""
            {"isInheritanceEnabled": false, "locallySet": [
              {"authorityId": "bob", "name": "Consumer", "accessStatus": "DENIED"},
              {"authorityId": "GROUP_EVERYONE", "name": "Consumer", "accessStatus": "DENIED"}]}
            """));

        assertThat(result.aclString()).isEmpty();
        assertThat(EffectiveAcl.resolve(result.securityConfig(), result.aclString()).allowedRead()).isEmpty();
    }

    @Test
    void inheritedEntriesAreIgnoredWhenInheritanceIsOff() throws Exception {
        var result = AlfrescoSecurityMapper.mapSecurity(mapper.readTree("""
            {"isInheritanceEnabled": false,
             "locallySet": [{"authorityId": "alice", "name": "Consumer", "accessStatus": "ALLOWED"}],
             "inherited": [{"authorityId": "GROUP_EVERYONE", "name": "Consumer", "accessStatus": "ALLOWED"}]}
            """));

        assertThat(EffectiveAcl.resolve(result.securityConfig(), result.aclString()).allowedRead()).containsExactly("alice");
    }

    /** Search (this mapping) and discovery ({@code discoverySecurity}) must grant and deny the same identities. */
    @Test
    void theCrawlerAndDiscoveryAgree() throws Exception {
        String[] authorities = {"alice", "bob", "GROUP_finance", "ROLE_ADMIN", "GROUP_EVERYONE", "guest", ""};
        String[] roles = {"Consumer", "Editor", "Coordinator"};
        java.util.Random random = new java.util.Random(203);
        for (int i = 0; i < 2000; i++) {
            var root = mapper.createObjectNode();
            root.put("isInheritanceEnabled", random.nextBoolean());
            for (String list : new String[] {"locallySet", "inherited"}) {
                var aces = root.putArray(list);
                for (int k = random.nextInt(4); k > 0; k--) {
                    aces.addObject().put("authorityId", authorities[random.nextInt(authorities.length)])
                            .put("name", roles[random.nextInt(roles.length)])
                            .put("accessStatus", random.nextInt(4) == 0 ? "DENIED" : "ALLOWED");
                }
            }
            var crawled = AlfrescoSecurityMapper.mapSecurity(root);
            EffectiveAcl search = EffectiveAcl.resolve(crawled.securityConfig(), crawled.aclString());
            SecurityConfig discovered = AlfrescoRepositoryConnector.discoverySecurity(root);
            EffectiveAcl discovery = EffectiveAcl.resolve(discovered, null);
            assertThat(java.util.Set.copyOf(search.allowedRead())).as("allowed for %s", root)
                    .isEqualTo(java.util.Set.copyOf(discovery.allowedRead()));
            if (!search.allowedRead().isEmpty()) {
                assertThat(java.util.Set.copyOf(search.deniedRead())).as("denied for %s", root)
                        .isEqualTo(java.util.Set.copyOf(discovery.deniedRead()));
            }
        }
    }
}
