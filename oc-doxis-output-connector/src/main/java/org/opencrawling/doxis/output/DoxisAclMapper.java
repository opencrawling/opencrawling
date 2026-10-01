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
package org.opencrawling.doxis.output;

import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.output.schema.DoxisSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * Maps the OIS security model onto Doxis {@code DocumentAceParams}:
 * {@code read} → GRANT {@code VIEW_DOCUMENT_CONTENTS}; {@code write} → GRANT view + {@code UPDATE_DOCUMENT} +
 * {@code VERSION_DOCUMENT}; {@code deny} → DENY {@code VIEW_DOCUMENT_CONTENTS}. Identities are resolved to Doxis users or
 * groups by name; unresolvable identities are skipped with a warning.
 */
public class DoxisAclMapper {

    private static final Logger log = LoggerFactory.getLogger(DoxisAclMapper.class);

    static final String VIEW = "VIEW_DOCUMENT_CONTENTS";
    static final List<String> WRITE = List.of(VIEW, "UPDATE_DOCUMENT", "VERSION_DOCUMENT");

    private final DoxisSchema schema;

    public DoxisAclMapper(DoxisSchema schema) {
        this.schema = schema;
    }

    public List<Map<String, Object>> aces(SecurityConfig security) throws IOException, InterruptedException {
        List<Map<String, Object>> aces = new ArrayList<>();
        if (security == null || security.permissions() == null) {
            return aces;
        }
        Set<String> seen = new HashSet<>();
        for (PermissionRule rule : security.permissions()) {
            Optional<String> principal = schema.principalId(rule.identity());
            if (principal.isEmpty()) {
                log.warn("OIS identity '{}' has no matching Doxis user or group; permission skipped.", rule.identity());
                continue;
            }
            String access = rule.access() == null ? "" : rule.access().toLowerCase(Locale.ROOT);
            switch (access) {
                case "deny" -> add(aces, seen, principal.get(), VIEW, "DENY");
                case "write" -> WRITE.forEach(permission -> add(aces, seen, principal.get(), permission, "GRANT"));
                case "read" -> add(aces, seen, principal.get(), VIEW, "GRANT");
                default -> log.warn("Unsupported OIS access '{}' for identity '{}'; skipped.", rule.access(), rule.identity());
            }
        }
        return aces;
    }

    private static void add(List<Map<String, Object>> aces, Set<String> seen, String principal, String permission, String variant) {
        if (seen.add(principal + "|" + permission + "|" + variant)) {
            Map<String, Object> ace = new LinkedHashMap<>();
            ace.put("organizationalElementId", principal);
            ace.put("permission", permission);
            ace.put("authorizationVariant", variant);
            aces.add(ace);
        }
    }
}
