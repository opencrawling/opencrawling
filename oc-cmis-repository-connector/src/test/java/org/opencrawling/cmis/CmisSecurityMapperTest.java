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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class CmisSecurityMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testNullEmptyOrUnusableAclReturnsNoRules() throws Exception {
        SecurityConfig configNull = CmisSecurityMapper.mapAcl(null);
        assertThat(configNull).isEqualTo(SecurityConfig.none());
        assertThat(EffectiveAcl.resolve(configNull, "").allowedRead()).isEmpty();

        JsonNode emptyObj = mapper.createObjectNode();
        SecurityConfig configEmpty = CmisSecurityMapper.mapAcl(emptyObj);
        assertThat(configEmpty).isEqualTo(SecurityConfig.none());
        assertThat(EffectiveAcl.resolve(configEmpty, "").allowedRead()).isEmpty();

        JsonNode emptyAces = mapper.readTree("{\"aces\":[]}");
        SecurityConfig configEmptyAces = CmisSecurityMapper.mapAcl(emptyAces);
        assertThat(configEmptyAces).isEqualTo(SecurityConfig.none());
        assertThat(EffectiveAcl.resolve(configEmptyAces, "").allowedRead()).isEmpty();

        JsonNode unusableAces = mapper.readTree("""
            {
              "aces": [
                { "permissions": ["cmis:read"] },
                { "principal": { "principalId": "   " }, "permissions": ["cmis:all"] }
              ]
            }
            """);
        SecurityConfig configUnusable = CmisSecurityMapper.mapAcl(unusableAces);
        assertThat(configUnusable).isEqualTo(SecurityConfig.none());
        assertThat(EffectiveAcl.resolve(configUnusable, "").allowedRead()).isEmpty();
    }

    @Test
    void testAnyonePrincipalGrantsPublic() throws Exception {
        for (String principal : List.of(
                "anonymous", "public", "everyone", "GROUP_EVERYONE", "group:everyone", "cmis:anyone", "CMIS:ANYONE")) {
            JsonNode aclNode = mapper.readTree("""
                {
                  "aces": [
                    {
                      "principal": { "principalId": "%s" },
                      "permissions": ["cmis:read"]
                    }
                  ]
                }
                """.formatted(principal));
            SecurityConfig config = CmisSecurityMapper.mapAcl(aclNode);
            assertThat(config.permissions()).singleElement().satisfies(rule -> {
                assertThat(rule.identity()).isEqualTo(principal);
                assertThat(rule.identityType()).isEqualTo("public");
                assertThat(rule.access()).isEqualTo("read");
            });
            assertThat(EffectiveAcl.resolve(config, "").isPublic()).isTrue();
        }

        JsonNode customAnyoneAcl = mapper.readTree("""
            {
              "aces": [
                {
                  "principal": { "principalId": "repo:everybody" },
                  "permissions": ["cmis:read"]
                }
              ]
            }
            """);
        assertThat(CmisSecurityMapper.mapAcl(customAnyoneAcl).permissions().get(0).identityType()).isEqualTo("user");
        SecurityConfig withRepoAnyone = CmisSecurityMapper.mapAcl(customAnyoneAcl, "repo:everybody");
        assertThat(withRepoAnyone.permissions()).singleElement().satisfies(rule -> {
            assertThat(rule.identity()).isEqualTo("repo:everybody");
            assertThat(rule.identityType()).isEqualTo("public");
        });
        assertThat(EffectiveAcl.resolve(withRepoAnyone, "").isPublic()).isTrue();
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
