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

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;

import com.fasterxml.jackson.databind.ObjectMapper;

class AlfrescoSecurityMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testNullOrMissingPermissionsNodeReturnsPublic() {
        AlfrescoSecurityMapper.SecurityResult res1 = AlfrescoSecurityMapper.mapSecurity(null);
        assertThat(res1.securityConfig().permissions()).hasSize(1);
        assertThat(res1.securityConfig().permissions().get(0).identity()).isEqualTo("public");
        assertThat(res1.aclString()).isEqualTo("public");
        assertThat(res1.identityUsers()).isEmpty();
        assertThat(res1.identityGroups()).isEmpty();
        assertThat(res1.isInherited()).isTrue();

        AlfrescoSecurityMapper.SecurityResult res2 = AlfrescoSecurityMapper.mapSecurity(mapper.createObjectNode());
        assertThat(res2.aclString()).isEqualTo("public");
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

        assertThat(result.securityConfig().permissions()).hasSize(3);

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
}
