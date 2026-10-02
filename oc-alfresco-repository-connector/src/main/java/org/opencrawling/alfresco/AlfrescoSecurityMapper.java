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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Maps Alfresco Content Services REST API Access Control Lists (permissions block)
 * into Open Ingestion Standard (OIS) SecurityConfig and zero-trust ACL identities.
 */
public class AlfrescoSecurityMapper {

    public record SecurityResult(
        SecurityConfig securityConfig,
        String aclString,
        List<String> identityUsers,
        List<String> identityGroups,
        boolean isInherited
    ) {}

    public static SecurityResult mapSecurity(JsonNode permissionsNode) {
        if (permissionsNode == null || permissionsNode.isMissingNode() || permissionsNode.isNull()) {
            return new SecurityResult(SecurityConfig.createPublic(), "public", List.of(), List.of(), true);
        }

        boolean isInheritanceEnabled = permissionsNode.path("isInheritanceEnabled").asBoolean(true);
        List<PermissionRule> rules = new ArrayList<>();
        Set<String> users = new HashSet<>();
        Set<String> groups = new HashSet<>();
        boolean hasPublicAccess = false;

        // Process locally set permissions
        JsonNode locallySet = permissionsNode.path("locallySet");
        if (locallySet.isArray()) {
            for (JsonNode ace : locallySet) {
                if (processAce(ace, rules, users, groups)) {
                    hasPublicAccess = true;
                }
            }
        }

        // Process inherited permissions if inheritance is enabled
        if (isInheritanceEnabled) {
            JsonNode inherited = permissionsNode.path("inherited");
            if (inherited.isArray()) {
                for (JsonNode ace : inherited) {
                    if (processAce(ace, rules, users, groups)) {
                        hasPublicAccess = true;
                    }
                }
            }
        }

        if (hasPublicAccess || rules.isEmpty()) {
            return new SecurityResult(
                SecurityConfig.createPublic(),
                "public",
                new ArrayList<>(users),
                new ArrayList<>(groups),
                isInheritanceEnabled
            );
        }

        List<String> allIdentities = new ArrayList<>(users);
        allIdentities.addAll(groups);
        String aclString = String.join(",", allIdentities);

        return new SecurityResult(
            new SecurityConfig(true, rules),
            aclString,
            new ArrayList<>(users),
            new ArrayList<>(groups),
            isInheritanceEnabled
        );
    }

    private static boolean processAce(JsonNode ace, List<PermissionRule> rules, Set<String> users, Set<String> groups) {
        String status = ace.path("accessStatus").asText("ALLOWED");
        if (!"ALLOWED".equalsIgnoreCase(status)) {
            return false;
        }

        String authorityId = ace.path("authorityId").asText();
        if (authorityId == null || authorityId.isBlank()) {
            return false;
        }

        String roleName = ace.path("name").asText("Consumer");
        String accessLevel = mapAccessLevel(roleName);

        String lower = authorityId.toLowerCase();
        if (lower.equals("group_everyone") || lower.equals("guest") || lower.equals("role_anonymous") || lower.equals("everyone")) {
            return true;
        }

        if (authorityId.startsWith("GROUP_") || authorityId.startsWith("ROLE_") || lower.startsWith("group:") || lower.startsWith("role:")) {
            groups.add(authorityId);
            rules.add(new PermissionRule(authorityId, "group", authorityId, accessLevel));
        } else {
            users.add(authorityId);
            rules.add(new PermissionRule(authorityId, "user", authorityId, accessLevel));
        }

        return false;
    }

    private static String mapAccessLevel(String roleName) {
        if (roleName == null) return "read";
        String lower = roleName.toLowerCase();
        if (lower.contains("coordinator") || lower.contains("admin")) {
            return "admin";
        }
        if (lower.contains("collaborator") || lower.contains("contributor") || lower.contains("editor") || lower.contains("write")) {
            return "write";
        }
        return "read";
    }
}
