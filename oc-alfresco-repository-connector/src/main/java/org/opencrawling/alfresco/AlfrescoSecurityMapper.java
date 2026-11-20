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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.opencrawling.alfresco.AlfrescoProperties.PrincipalFormat;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Maps Alfresco Content Services REST API Access Control Lists ({@code permissions} block)
 * into Open Ingestion Standard (OIS) {@link SecurityConfig} and zero-trust ACL identities.
 *
 * <p>Used identically by both crawling and pre-crawl discovery so search and discovery always enforce
 * the exact same authorization rules.
 */
public class AlfrescoSecurityMapper {

    public record SecurityResult(
            SecurityConfig securityConfig,
            String aclString,
            List<String> identityUsers,
            List<String> identityGroups,
            boolean isInherited
    ) {
        /** Deterministic SHA-256 fingerprint of effective permissions for incremental ACL-change detection. */
        public String fingerprint() {
            StringBuilder sb = new StringBuilder();
            sb.append("inh=").append(isInherited).append("|acl=").append(aclString == null ? "" : aclString);
            if (securityConfig != null && securityConfig.permissions() != null) {
                securityConfig.permissions().stream()
                        .sorted(Comparator.comparing(PermissionRule::identityType)
                                .thenComparing(PermissionRule::identity)
                                .thenComparing(PermissionRule::access))
                        .forEach(r -> sb.append('|').append(r.identityType()).append(':')
                                .append(r.identity()).append('=').append(r.access()));
            }
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Configurable policy controlling how {@code GROUP_EVERYONE}, unreadable permissions, and authority names are mapped.
     */
    public record Policy(
            boolean everyonePublic,
            String everyoneIdentity,
            List<String> fallbackPrincipals,
            PrincipalFormat principalFormat
    ) {
        public static final Policy DEFAULT = new Policy(true, "", List.of(), PrincipalFormat.RAW);

        public Policy {
            everyoneIdentity = everyoneIdentity == null ? "" : everyoneIdentity.strip();
            fallbackPrincipals = fallbackPrincipals == null ? List.of() : List.copyOf(fallbackPrincipals);
            principalFormat = principalFormat == null ? PrincipalFormat.RAW : principalFormat;
        }
    }

    private static final Set<String> EVERYONE = Set.of("group_everyone", "everyone", "guest", "role_anonymous");

    /**
     * Maps a node's permissions block using the default backward-compatible policy.
     */
    public static SecurityResult mapSecurity(JsonNode permissionsNode) {
        return mapSecurity(permissionsNode, Policy.DEFAULT);
    }

    /**
     * Maps a node's permissions block (locally set entries, plus inherited ones when inheritance is on).
     *
     * <ul>
     *   <li>A {@code DENIED} entry becomes a {@code deny} rule, which wins over any grant, so a user Alfresco denies
     *       is never shown the document because a group they belong to may read it.</li>
     *   <li>An {@code EVERYONE} grant becomes a {@code public} rule when {@code policy.everyonePublic()} is true,
     *       or a group rule for {@code policy.everyoneIdentity()} when configured, or is omitted otherwise.</li>
     *   <li>Missing permissions, or entries that grant nothing, are never read as public: unless
     *       {@code policy.fallbackPrincipals()} is configured, the document gets no rules and a blank {@code acl}
     *       (fail-closed, #261).</li>
     * </ul>
     */
    public static SecurityResult mapSecurity(JsonNode permissionsNode, Policy policy) {
        Policy effective = policy != null ? policy : Policy.DEFAULT;
        if (permissionsNode == null || permissionsNode.isMissingNode() || permissionsNode.isNull()) {
            return fallbackOrEmpty(true, effective);
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
            String rawAuthority = ace.path("authorityId").asText("").strip();
            if (rawAuthority.isEmpty()) {
                continue;
            }
            boolean denied = "DENIED".equalsIgnoreCase(ace.path("accessStatus").asText("ALLOWED"));
            boolean isEveryone = EVERYONE.contains(rawAuthority.toLowerCase(Locale.ROOT));
            boolean isGroup = isGroupAuthority(rawAuthority);

            if (denied) {
                if (!isEveryone) {
                    String identity = formatAuthority(rawAuthority, isGroup, effective.principalFormat());
                    rules.add(new PermissionRule(identity, isGroup ? "group" : "user", rawAuthority, "deny"));
                }
                continue;
            }

            String accessLevel = mapAccessLevel(ace.path("name").asText("Consumer"));
            if (isEveryone) {
                if (effective.everyonePublic()) {
                    everyone = true;
                    grants = true;
                    rules.add(new PermissionRule("public", "public", rawAuthority, accessLevel));
                } else if (!effective.everyoneIdentity().isEmpty()) {
                    String identity = effective.everyoneIdentity();
                    groups.add(identity);
                    grants = true;
                    rules.add(new PermissionRule(identity, "group", rawAuthority, accessLevel));
                }
            } else if (isGroup) {
                String identity = formatAuthority(rawAuthority, true, effective.principalFormat());
                groups.add(identity);
                grants = true;
                rules.add(new PermissionRule(identity, "group", rawAuthority, accessLevel));
            } else {
                String identity = formatAuthority(rawAuthority, false, effective.principalFormat());
                users.add(identity);
                grants = true;
                rules.add(new PermissionRule(identity, "user", rawAuthority, accessLevel));
            }
        }

        if (!grants) {
            if (rules.isEmpty() && !effective.fallbackPrincipals().isEmpty()) {
                return fallbackOrEmpty(isInheritanceEnabled, effective);
            }
            return new SecurityResult(
                    rules.isEmpty() ? SecurityConfig.none() : new SecurityConfig(isInheritanceEnabled, rules),
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

    private static SecurityResult fallbackOrEmpty(boolean inherited, Policy policy) {
        if (policy.fallbackPrincipals().isEmpty()) {
            return new SecurityResult(SecurityConfig.none(), "", List.of(), List.of(), inherited);
        }
        List<PermissionRule> rules = new ArrayList<>();
        Set<String> users = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        for (String principal : policy.fallbackPrincipals()) {
            String trimmed = principal == null ? "" : principal.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("group:")) {
                String id = trimmed.substring("group:".length()).strip();
                if (!id.isEmpty()) {
                    groups.add(id);
                    rules.add(new PermissionRule(id, "group", id, "read"));
                }
            } else if (lower.startsWith("user:")) {
                String id = trimmed.substring("user:".length()).strip();
                if (!id.isEmpty()) {
                    users.add(id);
                    rules.add(new PermissionRule(id, "user", id, "read"));
                }
            } else if (isGroupAuthority(trimmed)) {
                String id = formatAuthority(trimmed, true, policy.principalFormat());
                groups.add(id);
                rules.add(new PermissionRule(id, "group", trimmed, "read"));
            } else {
                String id = formatAuthority(trimmed, false, policy.principalFormat());
                users.add(id);
                rules.add(new PermissionRule(id, "user", trimmed, "read"));
            }
        }
        if (rules.isEmpty()) {
            return new SecurityResult(SecurityConfig.none(), "", List.of(), List.of(), inherited);
        }
        List<String> all = new ArrayList<>(users);
        all.addAll(groups);
        return new SecurityResult(
                new SecurityConfig(inherited, rules),
                String.join(",", all),
                new ArrayList<>(users),
                new ArrayList<>(groups),
                inherited
        );
    }

    private static boolean isGroupAuthority(String authorityId) {
        String lower = authorityId.toLowerCase(Locale.ROOT);
        return authorityId.startsWith("GROUP_") || authorityId.startsWith("ROLE_")
                || lower.startsWith("group:") || lower.startsWith("role:");
    }

    private static String formatAuthority(String raw, boolean isGroup, PrincipalFormat format) {
        if (format != PrincipalFormat.NORMALIZED) {
            return raw;
        }
        String value = raw.strip();
        if (isGroup) {
            if (value.startsWith("GROUP_")) {
                value = value.substring("GROUP_".length());
            } else if (value.startsWith("ROLE_")) {
                value = value.substring("ROLE_".length());
            } else if (value.toLowerCase(Locale.ROOT).startsWith("group:")) {
                value = value.substring("group:".length());
            } else if (value.toLowerCase(Locale.ROOT).startsWith("role:")) {
                value = value.substring("role:".length());
            }
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static String mapAccessLevel(String roleName) {
        if (roleName == null) {
            return "read";
        }
        String lower = roleName.toLowerCase(Locale.ROOT);
        if (lower.contains("coordinator") || lower.contains("admin")) {
            return "admin";
        }
        if (lower.contains("collaborator") || lower.contains("contributor") || lower.contains("editor") || lower.contains("write")) {
            return "write";
        }
        return "read";
    }
}
