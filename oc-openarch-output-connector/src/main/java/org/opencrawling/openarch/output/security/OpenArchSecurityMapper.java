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
package org.opencrawling.openarch.output.security;

import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.util.*;

/**
 * Maps Open Ingestion Standard (OIS) Zero-Trust ACLs to Hyland OpenArch Access Control Entries (ACEs).
 */
public class OpenArchSecurityMapper {

    /**
     * Maps OIS SecurityConfig to OpenArch ACE DTO structures for sys_acl.
     */
    public static List<Map<String, Object>> toOpenArchAcl(SecurityConfig securityConfig) {
        if (securityConfig == null || securityConfig.permissions() == null || securityConfig.permissions().isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> aceList = new ArrayList<>();
        for (PermissionRule rule : securityConfig.permissions()) {
            Map<String, Object> ace = new LinkedHashMap<>();
            ace.put("creator", "opencrawling");
            
            boolean granted = !"deny".equalsIgnoreCase(rule.access());
            ace.put("granted", granted);

            String openArchPermission = mapToOpenArchPermission(rule.access());
            ace.put("permission", openArchPermission);

            String identity = rule.identity();
            String identityType = rule.identityType();
            boolean isGroup = "group".equalsIgnoreCase(identityType)
                    || (identity != null && (identity.startsWith("group:") || identity.startsWith("GROUP_")));

            if (identity != null && !identity.isBlank()) {
                if (isGroup) {
                    String groupName = identity.replaceFirst("(?i)^group:", "");
                    ace.put("group", Map.of("groupname", groupName));
                } else {
                    String username = identity.replaceFirst("(?i)^user:", "");
                    ace.put("user", Map.of("username", username));
                }
            }
            aceList.add(ace);
        }
        return aceList;
    }

    /**
     * Maps OpenArch sys_acl to OpenCrawling SecurityConfig.
     */
    @SuppressWarnings("unchecked")
    public static SecurityConfig fromOpenArchAcl(List<Map<String, Object>> openArchAcl, boolean inheritanceEnabled) {
        if (openArchAcl == null || openArchAcl.isEmpty()) {
            return new SecurityConfig(inheritanceEnabled, Collections.emptyList());
        }

        List<PermissionRule> rules = new ArrayList<>();
        for (Map<String, Object> ace : openArchAcl) {
            boolean granted = !Boolean.FALSE.equals(ace.get("granted"));
            String permission = Objects.toString(ace.get("permission"), "Read");
            String oisAccess = granted ? mapFromOpenArchPermission(permission) : "deny";

            String identity = null;
            String identityType = "user";
            String displayName = null;

            if (ace.get("user") instanceof Map) {
                Map<String, Object> userMap = (Map<String, Object>) ace.get("user");
                identity = Objects.toString(userMap.get("username"), Objects.toString(userMap.get("id"), "anonymous"));
                identityType = "user";
                displayName = identity;
            } else if (ace.get("group") instanceof Map) {
                Map<String, Object> groupMap = (Map<String, Object>) ace.get("group");
                identity = "group:" + Objects.toString(groupMap.get("groupname"), Objects.toString(groupMap.get("id"), "everyone"));
                identityType = "group";
                displayName = identity;
            } else if (ace.get("username") != null) {
                identity = ace.get("username").toString();
                identityType = "user";
                displayName = identity;
            }

            if (identity != null && !identity.isBlank()) {
                rules.add(new PermissionRule(identity, identityType, displayName != null ? displayName : identity, oisAccess));
            }
        }
        return new SecurityConfig(inheritanceEnabled, rules);
    }

    public static String mapToOpenArchPermission(String oisAccess) {
        if (oisAccess == null) {
            return "Read";
        }
        return switch (oisAccess.toUpperCase(Locale.ROOT)) {
            case "READ" -> "Read";
            case "WRITE" -> "Write";
            case "DELETE" -> "Delete";
            case "ADMIN" -> "ManageSecurity";
            default -> "Read";
        };
    }

    public static String mapFromOpenArchPermission(String openArchPermission) {
        if (openArchPermission == null) {
            return "read";
        }
        return switch (openArchPermission.toLowerCase(Locale.ROOT)) {
            case "read" -> "read";
            case "write", "readwrite" -> "write";
            case "delete", "deletechild" -> "delete";
            case "managesecurity", "managelock", "all" -> "admin";
            default -> "read";
        };
    }
}
