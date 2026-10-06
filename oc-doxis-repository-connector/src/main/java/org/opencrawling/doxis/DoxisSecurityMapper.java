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
package org.opencrawling.doxis;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.client.schema.DoxisSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps Doxis instance ACEs ({@code RestAce}: {@code organizationalElementId}, {@code permissionName},
 * {@code authorizationVariant}) to an OIS {@link SecurityConfig}.
 *
 * <p>{@code VIEW_DOCUMENT_CONTENTS} (document) and {@code VIEW_FOLDER_CONTENTS} (e-file) GRANT → {@code read}; {@code UPDATE_DOCUMENT} / {@code UPDATE_FOLDER} GRANT →
 * {@code write}; a DENY on a view permission → {@code deny}. A filed document's effective ACL is its e-file's (pass down
 * permissions), so e-file ACEs are merged with the document's own. The built-in {@code everybody} group maps to {@code public}.
 *
 * <p>Class-level rights ("All instances") are not readable through REST. A document with no instance ACEs therefore gets
 * the configured fallback principals, or no permission at all (deny by default) — never public.
 */
public class DoxisSecurityMapper {

    private static final Logger log = LoggerFactory.getLogger(DoxisSecurityMapper.class);

    static final Set<String> VIEW = Set.of("VIEW_DOCUMENT_CONTENTS", "VIEW_FOLDER_CONTENTS");
    static final Set<String> WRITE = Set.of("UPDATE_DOCUMENT", "UPDATE_FOLDER");

    /** Resolves an {@code organizationalElementId}; normally {@link DoxisSchema#principalById(String)}. */
    @FunctionalInterface
    public interface PrincipalResolver {
        Optional<DoxisSchema.Principal> resolve(String organizationalElementId) throws IOException, InterruptedException;
    }

    private final PrincipalResolver resolver;
    private final List<String> fallbackPrincipals;

    public DoxisSecurityMapper(PrincipalResolver resolver, List<String> fallbackPrincipals) {
        this.resolver = resolver;
        this.fallbackPrincipals = fallbackPrincipals == null ? List.of() : fallbackPrincipals;
    }

    public SecurityConfig map(List<JsonNode> documentAces, List<JsonNode> recordAces) throws IOException, InterruptedException {
        // identity|type -> rule; deny wins over write wins over read
        Map<String, PermissionRule> rules = new LinkedHashMap<>();
        boolean inherited = recordAces != null && !recordAces.isEmpty();
        List<JsonNode> aces = new ArrayList<>();
        if (documentAces != null) {
            aces.addAll(documentAces);
        }
        if (recordAces != null) {
            aces.addAll(recordAces);
        }
        for (JsonNode ace : aces) {
            String permission = ace.path("permissionName").asText("").toUpperCase(Locale.ROOT);
            boolean deny = "DENY".equalsIgnoreCase(ace.path("authorizationVariant").asText("GRANT"));
            String access;
            if (VIEW.contains(permission)) {
                access = deny ? "deny" : "read";
            } else if (WRITE.contains(permission) && !deny) {
                access = "write";
            } else {
                continue;
            }
            PermissionRule rule = rule(ace.path("organizationalElementId").asText(null), access);
            if (rule != null) {
                String key = rule.identityType() + "|" + rule.identity().toLowerCase(Locale.ROOT);
                PermissionRule existing = rules.get(key);
                if (existing == null || rank(access) > rank(existing.access())) {
                    rules.put(key, rule);
                }
            }
        }
        if (rules.isEmpty()) {
            List<PermissionRule> fallback = fallbackPrincipals.stream().map(DoxisSecurityMapper::fallbackRule).toList();
            return new SecurityConfig(false, fallback);
        }
        return new SecurityConfig(inherited, List.copyOf(rules.values()));
    }

    private PermissionRule rule(String organizationalElementId, String access) throws IOException, InterruptedException {
        if (organizationalElementId == null || organizationalElementId.isBlank()) {
            return null;
        }
        Optional<DoxisSchema.Principal> principal = resolver.resolve(organizationalElementId);
        if (principal.isEmpty()) {
            log.debug("Doxis organisational element {} is not a known user, group or role; kept by UUID.", organizationalElementId);
            return new PermissionRule(organizationalElementId, "user", organizationalElementId, access);
        }
        DoxisSchema.Principal p = principal.get();
        if ("group".equals(p.type()) && "everybody".equalsIgnoreCase(p.name())) {
            return new PermissionRule("public", "public", "Public Access", access);
        }
        String type = "user".equals(p.type()) ? "user" : "group";
        return new PermissionRule(p.name(), type, p.name(), access);
    }

    private static PermissionRule fallbackRule(String identity) {
        String key = identity.strip();
        String lower = key.toLowerCase(Locale.ROOT);
        if ("public".equals(lower) || "everybody".equals(lower)) {
            return new PermissionRule("public", "public", "Public Access", "read");
        }
        if (lower.startsWith("group:")) {
            String name = key.substring(6);
            return new PermissionRule(name, "group", name, "read");
        }
        if (lower.startsWith("user:")) {
            String name = key.substring(5);
            return new PermissionRule(name, "user", name, "read");
        }
        return new PermissionRule(key, "group", key, "read");
    }

    private static int rank(String access) {
        return switch (access) {
            case "deny" -> 3;
            case "write" -> 2;
            default -> 1;
        };
    }
}
