/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.hyland.cic.security;

import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.hyland.cic.model.HylandCicPermission;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps Hyland Content Innovation Cloud (HXP) Access Control Entries (ACEs)
 * into Open Ingestion Standard (OIS) {@link SecurityConfig}.
 */
public class HylandCicSecurityMapper {

    private final String tenantId;

    public HylandCicSecurityMapper() {
        this(null);
    }

    public HylandCicSecurityMapper(String tenantId) {
        this.tenantId = (tenantId != null && !tenantId.isBlank()) ? tenantId.strip() : null;
    }

    /**
     * Maps document and folder permissions into an OIS {@link SecurityConfig}.
     *
     * @param documentAces document specific permissions
     * @param folderAces   inherited folder permissions (if any)
     * @return populated {@link SecurityConfig}
     */
    public SecurityConfig map(List<HylandCicPermission> documentAces, List<HylandCicPermission> folderAces) {
        Map<String, PermissionRule> rules = new LinkedHashMap<>();
        boolean inherited = folderAces != null && !folderAces.isEmpty();

        if (folderAces != null) {
            for (HylandCicPermission ace : folderAces) {
                applyAce(ace, rules);
            }
        }

        if (documentAces != null) {
            for (HylandCicPermission ace : documentAces) {
                applyAce(ace, rules);
            }
        }

        if (rules.isEmpty()) {
            return SecurityConfig.createPublic();
        }

        return new SecurityConfig(inherited, List.copyOf(rules.values()));
    }

    private void applyAce(HylandCicPermission ace, Map<String, PermissionRule> rules) {
        if (ace == null || ace.identity() == null || ace.identity().isBlank()) {
            return;
        }

        String rawIdentity = ace.identity().strip();
        String lowerIdentity = rawIdentity.toLowerCase(Locale.ROOT);
        String type = ace.type() != null ? ace.type().toLowerCase(Locale.ROOT) : "user";
        String permission = ace.permission() != null ? ace.permission().toUpperCase(Locale.ROOT) : "READ";

        String access;
        if (!ace.granted()) {
            access = "deny";
        } else {
            access = switch (permission) {
                case "WRITE", "MODIFY" -> "write";
                case "DELETE" -> "delete";
                case "ADMIN", "ALL", "FULL_CONTROL" -> "admin";
                default -> "read";
            };
        }

        PermissionRule rule;
        if ("everyone".equals(lowerIdentity) || "all".equals(lowerIdentity) || "public".equals(lowerIdentity)) {
            if (tenantId == null) {
                rule = new PermissionRule("public", "public", "Public Access", access);
            } else {
                String id = tenantId + "/everyone";
                rule = new PermissionRule(id, "group", id, access);
            }
        } else {
            String identity = tenantId == null ? rawIdentity : tenantId + "/" + rawIdentity;
            rule = new PermissionRule(identity, type, rawIdentity, access);
        }

        String key = rule.identityType() + "|" + rule.identity().toLowerCase(Locale.ROOT);
        PermissionRule existing = rules.get(key);
        if (existing == null || rank(access) > rank(existing.access())) {
            rules.put(key, rule);
        }
    }

    private static int rank(String access) {
        return switch (access) {
            case "deny" -> 4;
            case "admin" -> 3;
            case "delete" -> 2;
            case "write" -> 2;
            default -> 1;
        };
    }
}
