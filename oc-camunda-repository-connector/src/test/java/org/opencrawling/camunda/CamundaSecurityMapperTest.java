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
package org.opencrawling.camunda;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.SecurityConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class CamundaSecurityMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testMapSecurityWithUsersAndGroups() throws Exception {
        String json = """
            [
              {
                "type": "assignee",
                "userId": "approver_1",
                "groupId": null,
                "processInstanceId": "proc-100",
                "operationType": "add"
              },
              {
                "type": "candidate",
                "userId": null,
                "groupId": "finance-managers",
                "processInstanceId": "proc-100",
                "operationType": "add"
              }
            ]
            """;
        JsonNode identityLinks = objectMapper.readTree(json);
        SecurityConfig config = CamundaSecurityMapper.mapSecurity(identityLinks, "starter_user");

        assertNotNull(config);
        assertFalse(config.inheritanceEnabled());
        assertEquals(3, config.permissions().size());

        List<String> userIds = CamundaSecurityMapper.extractAllowedUsers(identityLinks, "starter_user");
        assertEquals(List.of("starter_user", "approver_1"), userIds);

        List<String> groupIds = CamundaSecurityMapper.extractAllowedGroups(identityLinks);
        assertEquals(List.of("finance-managers"), groupIds);

        assertTrue(config.permissions().stream().anyMatch(r -> r.identity().equals("starter_user") && r.identityType().equals("user")));
        assertTrue(config.permissions().stream().anyMatch(r -> r.identity().equals("approver_1") && r.identityType().equals("user")));
        assertTrue(config.permissions().stream().anyMatch(r -> r.identity().equals("finance-managers") && r.identityType().equals("group")));
    }

    @Test
    void testMapCamunda8UserTasksSecurity() throws Exception {
        String json = """
            {
              "items": [
                {
                  "userTaskKey": 2251799813685260,
                  "processInstanceKey": 2251799813685257,
                  "assignee": "clerk_1",
                  "candidateUsers": ["auditor_a", "auditor_b"],
                  "candidateGroups": ["underwriters", "claims-dept"],
                  "state": "CREATED"
                }
              ]
            }
            """;
        JsonNode tasksNode = objectMapper.readTree(json);
        SecurityConfig config = CamundaSecurityMapper.mapCamunda8Security(tasksNode, "customer_service_starter");

        assertNotNull(config);
        assertFalse(config.inheritanceEnabled());
        assertEquals(6, config.permissions().size());

        List<String> userIds = CamundaSecurityMapper.extractAllowedUsers(tasksNode, "customer_service_starter");
        assertEquals(List.of("customer_service_starter", "clerk_1", "auditor_a", "auditor_b"), userIds);

        List<String> groupIds = CamundaSecurityMapper.extractAllowedGroups(tasksNode);
        assertEquals(List.of("underwriters", "claims-dept"), groupIds);

        assertTrue(config.permissions().stream().anyMatch(r -> r.identity().equals("customer_service_starter") && r.identityType().equals("user")));
        assertTrue(config.permissions().stream().anyMatch(r -> r.identity().equals("clerk_1") && r.identityType().equals("user")));
        assertTrue(config.permissions().stream().anyMatch(r -> r.identity().equals("auditor_a") && r.identityType().equals("user")));
        assertTrue(config.permissions().stream().anyMatch(r -> r.identity().equals("auditor_b") && r.identityType().equals("user")));
        assertTrue(config.permissions().stream().anyMatch(r -> r.identity().equals("underwriters") && r.identityType().equals("group")));
        assertTrue(config.permissions().stream().anyMatch(r -> r.identity().equals("claims-dept") && r.identityType().equals("group")));
    }

    @Test
    void testMapSecurityWithNullLinksFallsBackToStarterOrPublic() {
        SecurityConfig configWithStarter = CamundaSecurityMapper.mapSecurity(null, "agent_1");
        assertNotNull(configWithStarter);
        assertEquals(1, configWithStarter.permissions().size());
        assertEquals("agent_1", configWithStarter.permissions().get(0).identity());

        SecurityConfig configPublic = CamundaSecurityMapper.mapSecurity(null, null);
        assertNotNull(configPublic);
        assertEquals(1, configPublic.permissions().size());
        assertEquals("public", configPublic.permissions().get(0).identity());
    }

    @Test
    void testMapSecurityWithDeletionLogEntries() throws Exception {
        String json = """
            [
              {"type": "candidate", "userId": "user_a", "operationType": "add"},
              {"type": "candidate", "groupId": "group_a", "operationType": "add"},
              {"type": "candidate", "userId": "user_b", "operationType": "add"},
              {"type": "candidate", "userId": "user_b", "operationType": "delete"}
            ]
            """;
        JsonNode identityLinks = objectMapper.readTree(json);
        SecurityConfig config = CamundaSecurityMapper.mapSecurity(identityLinks, "starter");

        assertEquals(3, config.permissions().size());
        assertEquals(List.of("starter", "user_a"), CamundaSecurityMapper.extractAllowedUsers(identityLinks, "starter"));
        assertEquals(List.of("group_a"), CamundaSecurityMapper.extractAllowedGroups(identityLinks));
    }

    @Test
    void testMapSecurityDeduplicatesIdentities() throws Exception {
        String json = """
            [
              {"type": "assignee", "userId": "user_a", "operationType": "add"},
              {"type": "candidate", "userId": "user_a", "operationType": "add"},
              {"type": "candidate", "groupId": "group_a", "operationType": "add"}
            ]
            """;
        JsonNode identityLinks = objectMapper.readTree(json);
        SecurityConfig config = CamundaSecurityMapper.mapSecurity(identityLinks, "user_a");

        assertEquals(2, config.permissions().size());
        assertEquals(List.of("user_a"), CamundaSecurityMapper.extractAllowedUsers(identityLinks, "user_a"));
        assertEquals(List.of("group_a"), CamundaSecurityMapper.extractAllowedGroups(identityLinks));
    }
}
