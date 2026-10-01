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
package org.opencrawling.cmis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class CmisSecurityMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testNullOrEmptyAclReturnsPublic() {
        SecurityConfig configNull = CmisSecurityMapper.mapAcl(null);
        assertThat(configNull.permissions()).hasSize(1);
        assertThat(configNull.permissions().get(0).identity()).isEqualTo("public");

        JsonNode emptyObj = mapper.createObjectNode();
        SecurityConfig configEmpty = CmisSecurityMapper.mapAcl(emptyObj);
        assertThat(configEmpty.permissions()).hasSize(1);
        assertThat(configEmpty.permissions().get(0).identity()).isEqualTo("public");
    }

    @Test
    void testAclWithUsersAndGroups() throws Exception {
        String json = """
            {
              "isExact": true,
              "aces": [
                {
                  "principal": { "principalId": "mario.rossi" },
                  "permissions": ["cmis:read"],
                  "isDirect": true
                },
                {
                  "principal": { "principalId": "group:engineering-leads" },
                  "permissions": ["cmis:write", "cmis:read"],
                  "isDirect": false
                },
                {
                  "principal": { "principalId": "GROUP_ADMINS" },
                  "permissions": ["cmis:all"],
                  "isDirect": true
                }
              ]
            }
            """;

        JsonNode aclNode = mapper.readTree(json);
        SecurityConfig config = CmisSecurityMapper.mapAcl(aclNode);

        assertThat(config.inheritanceEnabled()).isTrue();
        assertThat(config.permissions()).hasSize(3);

        PermissionRule userRule = config.permissions().get(0);
        assertThat(userRule.identity()).isEqualTo("mario.rossi");
        assertThat(userRule.identityType()).isEqualTo("user");
        assertThat(userRule.access()).isEqualTo("read");

        PermissionRule groupRule = config.permissions().get(1);
        assertThat(groupRule.identity()).isEqualTo("group:engineering-leads");
        assertThat(groupRule.identityType()).isEqualTo("group");
        assertThat(groupRule.access()).isEqualTo("write");

        PermissionRule adminRule = config.permissions().get(2);
        assertThat(adminRule.identity()).isEqualTo("GROUP_ADMINS");
        assertThat(adminRule.identityType()).isEqualTo("group");
        assertThat(adminRule.access()).isEqualTo("admin");
    }

    @Test
    void testTextualPrincipalHandling() throws Exception {
        String json = """
            {
              "aces": [
                {
                  "principal": "piergiorgio.lucidi",
                  "permissions": ["cmis:write"]
                },
                {
                  "principal": "GROUP_EVERYONE",
                  "permissions": ["cmis:read"]
                }
              ]
            }
            """;

        JsonNode aclNode = mapper.readTree(json);
        SecurityConfig config = CmisSecurityMapper.mapAcl(aclNode);

        assertThat(config.permissions()).hasSize(2);
        assertThat(config.permissions().get(0).identity()).isEqualTo("piergiorgio.lucidi");
        assertThat(config.permissions().get(0).access()).isEqualTo("write");
        assertThat(config.permissions().get(1).identity()).isEqualTo("GROUP_EVERYONE");
        assertThat(config.permissions().get(1).identityType()).isEqualTo("public");
    }
}
