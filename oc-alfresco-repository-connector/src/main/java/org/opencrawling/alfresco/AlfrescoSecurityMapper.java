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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    private static final Set<String> EVERYONE = Set.of("group_everyone", "everyone", "guest", "role_anonymous");

    /**
     * Maps a node's permissions block (locally set entries, plus inherited ones when inheritance is on).
     *
     * <ul>
     *   <li>A {@code DENIED} entry becomes a {@code deny} rule, which wins over any grant, so a user Alfresco denies
     *       is never shown the document because a group they belong to may read it.</li>
     *   <li>An {@code EVERYONE} grant becomes a {@code public} rule next to the other rules: the denies still
     *       apply.</li>
     *   <li>Missing permissions, or entries that grant nothing, are never read as public: the document gets no
     *       rules and a blank {@code acl}, so nobody may read it (#261).</li>
     * </ul>
     *
     * Discovery decides with the same rules ({@code AlfrescoRepositoryConnector.discoverySecurity}), so search and
     * discovery agree on what a user may see.
     */
    public static SecurityResult mapSecurity(JsonNode permissionsNode) {
        if (permissionsNode == null || permissionsNode.isMissingNode() || permissionsNode.isNull()) {
            return new SecurityResult(SecurityConfig.none(), "", List.of(), List.of(), true);
        }

        boolean isInheritanceEnabled = permissionsNode.path("isInheritanceEnabled").asBoolean(true);
        List<JsonNode> aces = new ArrayList<>();
        permissionsNode.path("locallySet").forEach(aces::add);
        if (isInheritanceEnabled) {
            permissionsNode.path("inherited").forEach(aces::add);
        }

        List<PermissionRule> rules = new ArrayList<>();
        Set<String> users = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        boolean everyone = false;
        boolean grants = false;
        for (JsonNode ace : aces) {
            String authorityId = ace.path("authorityId").asText("").strip();
            if (authorityId.isEmpty()) {
                continue;
            }
            boolean denied = "DENIED".equalsIgnoreCase(ace.path("accessStatus").asText("ALLOWED"));
            boolean isEveryone = EVERYONE.contains(authorityId.toLowerCase(Locale.ROOT));
            boolean isGroup = authorityId.startsWith("GROUP_") || authorityId.startsWith("ROLE_")
                    || authorityId.toLowerCase(Locale.ROOT).startsWith("group:")
                    || authorityId.toLowerCase(Locale.ROOT).startsWith("role:");
            if (denied) {
                // A denial of everyone denies no one in particular; it cannot be expressed as a rule.
                if (!isEveryone) {
                    rules.add(new PermissionRule(authorityId, isGroup ? "group" : "user", authorityId, "deny"));
                }
                continue;
            }
            String accessLevel = mapAccessLevel(ace.path("name").asText("Consumer"));
            grants = true;
            if (isEveryone) {
                everyone = true;
                rules.add(new PermissionRule("public", "public", authorityId, accessLevel));
            } else if (isGroup) {
                groups.add(authorityId);
                rules.add(new PermissionRule(authorityId, "group", authorityId, accessLevel));
            } else {
                users.add(authorityId);
                rules.add(new PermissionRule(authorityId, "user", authorityId, accessLevel));
            }
        }

        if (!grants) {
            return new SecurityResult(rules.isEmpty() ? SecurityConfig.none() : new SecurityConfig(isInheritanceEnabled, rules),
                    "", List.of(), List.of(), isInheritanceEnabled);
        }

        List<String> allIdentities = new ArrayList<>(users);
        allIdentities.addAll(groups);
        String aclString = everyone ? "public" : String.join(",", allIdentities);
        return new SecurityResult(
            new SecurityConfig(isInheritanceEnabled, rules),
            aclString,
            new ArrayList<>(users),
            new ArrayList<>(groups),
            isInheritanceEnabled
        );
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
