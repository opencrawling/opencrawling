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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Maps Camunda 8 User Tasks (assignee, candidate users, candidate groups)
 * and Camunda 7 Historic Identity Links into Open Ingestion Standard (OIS) SecurityConfig.
 */
public class CamundaSecurityMapper {

    /**
     * Map security for Camunda 8 User Tasks or Camunda 7 Historic Identity Links.
     *
     * @param tasksOrLinksNode JsonNode representing tasks or identity links
     * @param startUserId initiator or starter user ID
     * @return OIS SecurityConfig
     */
    public static SecurityConfig mapSecurity(JsonNode tasksOrLinksNode, String startUserId) {
        List<PermissionRule> rules = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Add starter as user rule if available
        if (startUserId != null && !startUserId.isBlank()) {
            String idKey = "user:" + startUserId;
            if (seen.add(idKey)) {
                rules.add(new PermissionRule(startUserId, "user", startUserId, "read"));
            }
        }

        List<String> allowedUsers = extractAllowedUsers(tasksOrLinksNode, startUserId);
        for (String user : allowedUsers) {
            String idKey = "user:" + user;
            if (seen.add(idKey)) {
                rules.add(new PermissionRule(user, "user", user, "read"));
            }
        }

        List<String> allowedGroups = extractAllowedGroups(tasksOrLinksNode);
        for (String group : allowedGroups) {
            String idKey = "group:" + group;
            if (seen.add(idKey)) {
                rules.add(new PermissionRule(group, "group", group, "read"));
            }
        }

        if (rules.isEmpty()) {
            return SecurityConfig.createPublic();
        }

        return new SecurityConfig(false, rules);
    }

    /**
     * Convenience method specifically for Camunda 8 User Tasks search response.
     */
    public static SecurityConfig mapCamunda8Security(JsonNode userTasksNode, String startUserId) {
        return mapSecurity(userTasksNode, startUserId);
    }

    /**
     * Extract allowed users from Camunda 8 user tasks or Camunda 7 identity links.
     */
    public static List<String> extractAllowedUsers(JsonNode tasksOrLinksNode, String startUserId) {
        Set<String> users = new LinkedHashSet<>();
        if (startUserId != null && !startUserId.isBlank()) {
            users.add(startUserId);
        }

        JsonNode linksArray = resolveLinksArray(tasksOrLinksNode);
        if (linksArray != null && linksArray.isArray()) {
            for (JsonNode item : linksArray) {
                // Camunda 8 User Task: assignee
                String assignee = extractStringField(item, "assignee");
                if (assignee != null && !assignee.isBlank()) {
                    users.add(assignee);
                }

                // Camunda 8 User Task: candidateUsers (array or string)
                JsonNode candidateUsers = item.path("candidateUsers");
                if (candidateUsers.isArray()) {
                    for (JsonNode u : candidateUsers) {
                        if (u.isTextual() && !u.asText().isBlank()) {
                            users.add(u.asText().trim());
                        }
                    }
                } else if (candidateUsers.isTextual() && !candidateUsers.asText().isBlank()) {
                    users.add(candidateUsers.asText().trim());
                }

                // Camunda 7 Historic Identity Link Log: userId with operationType
                String opType = extractStringField(item, "operationType");
                String userId = extractStringField(item, "userId", "user");
                if (userId != null && !userId.isBlank()) {
                    if ("delete".equalsIgnoreCase(opType)) {
                        users.remove(userId);
                    } else {
                        users.add(userId);
                    }
                }
            }
        }
        return new ArrayList<>(users);
    }

    /**
     * Extract allowed groups from Camunda 8 user tasks or Camunda 7 identity links.
     */
    public static List<String> extractAllowedGroups(JsonNode tasksOrLinksNode) {
        Set<String> groups = new LinkedHashSet<>();
        JsonNode linksArray = resolveLinksArray(tasksOrLinksNode);
        if (linksArray != null && linksArray.isArray()) {
            for (JsonNode item : linksArray) {
                // Camunda 8 User Task: candidateGroups (array or string)
                JsonNode candidateGroups = item.path("candidateGroups");
                if (candidateGroups.isArray()) {
                    for (JsonNode g : candidateGroups) {
                        if (g.isTextual() && !g.asText().isBlank()) {
                            groups.add(g.asText().trim());
                        }
                    }
                } else if (candidateGroups.isTextual() && !candidateGroups.asText().isBlank()) {
                    groups.add(candidateGroups.asText().trim());
                }

                // Camunda 7 Historic Identity Link Log: groupId with operationType
                String opType = extractStringField(item, "operationType");
                String groupId = extractStringField(item, "groupId", "group");
                if (groupId != null && !groupId.isBlank()) {
                    if ("delete".equalsIgnoreCase(opType)) {
                        groups.remove(groupId);
                    } else {
                        groups.add(groupId);
                    }
                }
            }
        }
        return new ArrayList<>(groups);
    }

    private static JsonNode resolveLinksArray(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isArray()) {
            return node;
        }
        if (node.has("items") && node.path("items").isArray()) {
            return node.path("items");
        }
        if (node.has("data") && node.path("data").isArray()) {
            return node.path("data");
        }
        return null;
    }

    private static String extractStringField(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode field = node.path(fieldName);
            if (!field.isMissingNode() && !field.isNull() && !field.asText("").isBlank()) {
                return field.asText().trim();
            }
        }
        return null;
    }
}
