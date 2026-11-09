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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Maps OASIS CMIS Access Control Lists (ACLs) and Access Control Entries (ACEs)
 * into Open Ingestion Standard (OIS) SecurityConfig.
 */
public class CmisSecurityMapper {

    public static final String DEFAULT_ANYONE_PRINCIPAL = "cmis:anyone";

    public static SecurityConfig mapAcl(JsonNode aclNode) {
        return mapAcl(aclNode, DEFAULT_ANYONE_PRINCIPAL);
    }

    public static SecurityConfig mapAcl(JsonNode aclNode, String anyonePrincipal) {
        if (aclNode == null || aclNode.isNull() || aclNode.isEmpty()) {
            return SecurityConfig.none();
        }

        JsonNode acesNode = aclNode.path("aces");
        if (!acesNode.isArray() || acesNode.isEmpty()) {
            return SecurityConfig.none();
        }

        List<PermissionRule> rules = new ArrayList<>();
        boolean isExact = aclNode.path("isExact").asBoolean(true);

        for (JsonNode ace : acesNode) {
            String principalId = extractPrincipalId(ace);
            if (principalId == null || principalId.isBlank()) {
                continue;
            }

            String identityType = determineIdentityType(principalId, anyonePrincipal);
            String access = determineAccessLevel(ace.path("permissions"));

            rules.add(new PermissionRule(
                principalId,
                identityType,
                principalId,
                access
            ));
        }

        if (rules.isEmpty()) {
            return SecurityConfig.none();
        }

        return new SecurityConfig(isExact, rules);
    }

    static String extractPrincipalId(JsonNode ace) {
        JsonNode principalNode = ace.path("principal");
        if (principalNode.isObject() && principalNode.has("principalId")) {
            return principalNode.path("principalId").asText();
        } else if (principalNode.isTextual()) {
            return principalNode.asText();
        } else if (ace.has("principalId")) {
            return ace.path("principalId").asText();
        }
        return null;
    }

    private static String determineIdentityType(String principalId, String anyonePrincipal) {
        String trimmed = principalId.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("anonymous")
                || lower.equals("public")
                || lower.equals("everyone")
                || lower.equals("group_everyone")
                || lower.equals("group:everyone")
                || lower.equals(DEFAULT_ANYONE_PRINCIPAL)
                || (anyonePrincipal != null && !anyonePrincipal.isBlank() && trimmed.equalsIgnoreCase(anyonePrincipal.trim()))) {
            return "public";
        }
        if (lower.startsWith("group:") || lower.startsWith("group_") || lower.contains("group") || lower.startsWith("role:")) {
            return "group";
        }
        return "user";
    }

    private static String determineAccessLevel(JsonNode permissionsNode) {
        if (!permissionsNode.isArray()) {
            String perm = permissionsNode.asText("").toLowerCase();
            if (perm.contains("all")) return "admin";
            if (perm.contains("write")) return "write";
            return "read";
        }

        boolean hasWrite = false;
        for (JsonNode p : permissionsNode) {
            String perm = p.asText("").toLowerCase();
            if (perm.equals("cmis:all") || perm.equals("all") || perm.contains("admin")) {
                return "admin";
            }
            if (perm.equals("cmis:write") || perm.equals("write")) {
                hasWrite = true;
            }
        }

        return hasWrite ? "write" : "read";
    }
}
