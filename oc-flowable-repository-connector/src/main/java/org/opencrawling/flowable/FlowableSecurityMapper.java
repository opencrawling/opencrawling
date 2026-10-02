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
package org.opencrawling.flowable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Maps Flowable Historic/Runtime Identity Links and Start User into Open Ingestion Standard (OIS) SecurityConfig.
 */
public class FlowableSecurityMapper {

    public static SecurityConfig mapSecurity(JsonNode identityLinksNode, String startUserId) {
        List<PermissionRule> rules = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Add starter as user rule if available
        if (startUserId != null && !startUserId.isBlank()) {
            String idKey = "user:" + startUserId;
            if (seen.add(idKey)) {
                rules.add(new PermissionRule(startUserId, "user", startUserId, "read"));
            }
        }

        JsonNode linksArray = resolveLinksArray(identityLinksNode);
        if (linksArray != null && linksArray.isArray()) {
            for (JsonNode link : linksArray) {
                String userId = extractStringField(link, "userId", "user");
                if (userId != null && !userId.isBlank()) {
                    String idKey = "user:" + userId;
                    if (seen.add(idKey)) {
                        rules.add(new PermissionRule(userId, "user", userId, "read"));
                    }
                }

                String groupId = extractStringField(link, "groupId", "group");
                if (groupId != null && !groupId.isBlank()) {
                    String idKey = "group:" + groupId;
                    if (seen.add(idKey)) {
                        rules.add(new PermissionRule(groupId, "group", groupId, "read"));
                    }
                }
            }
        }

        if (rules.isEmpty()) {
            return SecurityConfig.createPublic();
        }

        return new SecurityConfig(false, rules);
    }

    public static List<String> extractAllowedUsers(JsonNode identityLinksNode, String startUserId) {
        Set<String> users = new LinkedHashSet<>();
        if (startUserId != null && !startUserId.isBlank()) {
            users.add(startUserId);
        }

        JsonNode linksArray = resolveLinksArray(identityLinksNode);
        if (linksArray != null && linksArray.isArray()) {
            for (JsonNode link : linksArray) {
                String userId = extractStringField(link, "userId", "user");
                if (userId != null && !userId.isBlank()) {
                    users.add(userId);
                }
            }
        }
        return new ArrayList<>(users);
    }

    public static List<String> extractAllowedGroups(JsonNode identityLinksNode) {
        Set<String> groups = new LinkedHashSet<>();
        JsonNode linksArray = resolveLinksArray(identityLinksNode);
        if (linksArray != null && linksArray.isArray()) {
            for (JsonNode link : linksArray) {
                String groupId = extractStringField(link, "groupId", "group");
                if (groupId != null && !groupId.isBlank()) {
                    groups.add(groupId);
                }
            }
        }
        return new ArrayList<>(groups);
    }

    private static JsonNode resolveLinksArray(JsonNode identityLinksNode) {
        if (identityLinksNode == null || identityLinksNode.isNull() || identityLinksNode.isMissingNode()) {
            return null;
        }
        if (identityLinksNode.isArray()) {
            return identityLinksNode;
        }
        if (identityLinksNode.has("data") && identityLinksNode.path("data").isArray()) {
            return identityLinksNode.path("data");
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
