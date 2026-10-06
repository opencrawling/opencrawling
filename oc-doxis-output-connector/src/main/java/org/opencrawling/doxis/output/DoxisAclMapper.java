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
import org.opencrawling.doxis.client.schema.DoxisSchema;
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
    static final String RECORD_VIEW = "VIEW_FOLDER_CONTENTS";
    static final List<String> RECORD_WRITE = List.of(RECORD_VIEW, "UPDATE_FOLDER", "EDIT_FOLDER_DESCRIPTORS");

    /** Record permissions granted to the connector's own user on e-files it creates ({@code security.grant-connector-user}). */
    public static final List<String> CONNECTOR_RECORD_PERMISSIONS = List.of(RECORD_VIEW, "UPDATE_FOLDER", "EDIT_FOLDER_DESCRIPTORS",
            "DELETE_FOLDER", "CREATE_FOLDER", "SET_PRIMARY_PARENT", "VIEW_REMOVED_FOLDER");

    /** Permissions the connector manages on documents (the only ones {@code security.remove-stale} may remove). */
    public static final Set<String> MANAGED_DOCUMENT_PERMISSIONS = Set.of(VIEW, "UPDATE_DOCUMENT", "VERSION_DOCUMENT");

    private final DoxisSchema schema;

    public DoxisAclMapper(DoxisSchema schema) {
        this.schema = schema;
    }

    public List<Map<String, Object>> aces(SecurityConfig security) throws IOException, InterruptedException {
        return aces(security, false);
    }

    /**
     * ACEs for an e-file (record): {@code read} → {@code VIEW_FOLDER_CONTENTS}; {@code write} → view + {@code UPDATE_FOLDER} +
     * {@code EDIT_FOLDER_DESCRIPTORS}; {@code deny} → DENY view. Documents inherit them when the class passes permissions down.
     */
    public List<Map<String, Object>> recordAces(SecurityConfig security) throws IOException, InterruptedException {
        return aces(security, true);
    }

    /** Identities of {@code security} that have no matching Doxis user or group. */
    public List<String> unresolvedIdentities(SecurityConfig security) throws IOException, InterruptedException {
        List<String> unresolved = new ArrayList<>();
        if (security != null && security.permissions() != null) {
            for (PermissionRule rule : security.permissions()) {
                if (schema.principalId(rule.identity()).isEmpty()) {
                    unresolved.add(rule.identity());
                }
            }
        }
        return unresolved;
    }

    private List<Map<String, Object>> aces(SecurityConfig security, boolean record) throws IOException, InterruptedException {
        String view = record ? RECORD_VIEW : VIEW;
        List<String> write = record ? RECORD_WRITE : WRITE;
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
                case "deny" -> add(aces, seen, principal.get(), view, "DENY");
                case "write" -> write.forEach(permission -> add(aces, seen, principal.get(), permission, "GRANT"));
                case "read" -> add(aces, seen, principal.get(), view, "GRANT");
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
