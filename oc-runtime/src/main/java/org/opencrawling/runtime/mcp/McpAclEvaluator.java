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
package org.opencrawling.runtime.mcp;

import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Server-side Zero-Trust Access Control List (ACL) evaluator.
 * Evaluates access rules authoritatively before returning any document matches to LLMs:
 * 1. Explicit DENY always takes precedence and rejects the hit immediately.
 * 2. Explicit READ or WRITE grants access if the caller's identity (userPrincipal, any userRoles, or public) matches.
 * 3. Falls back to flat ACL rules (e.g. "acl" or "acl_ss") when structured security is absent. An empty permission/ACL list denies access by default.
 */
public final class McpAclEvaluator {

    private static final String PUBLIC_IDENTITY = "public";
    private static final Pattern USER_PREFIX = Pattern.compile("^(?i)user:");
    private static final Pattern GROUP_PREFIX = Pattern.compile("^(?i)group:");

    private McpAclEvaluator() {
        // Utility class
    }

    /**
     * Splits a comma-separated list of roles into normalized trimmed non-empty strings.
     */
    public static List<String> parseRoles(String userRoles) {
        if (userRoles == null || userRoles.trim().isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(userRoles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Authoritatively evaluates access for a document based on its metadata map and CSV roles.
     */
    public static boolean isAccessibleWithRolesCsv(Map<String, Object> metadata, String userPrincipal, String userRolesCsv) {
        return isAccessible(metadata, userPrincipal, parseRoles(userRolesCsv));
    }

    /**
     * Authoritatively evaluates access for a document based on its metadata map and parsed roles.
     */
    public static boolean isAccessible(Map<String, Object> metadata, String userPrincipal, Collection<String> userRoles) {
        if (metadata == null) {
            return false;
        }

        // 1. Check for structured "security" object (SecurityConfig or Map)
        Object securityObj = metadata.get("security");
        if (securityObj instanceof SecurityConfig sc) {
            return evaluateSecurityConfig(sc, userPrincipal, userRoles);
        } else if (securityObj instanceof Map<?, ?> sm) {
            return evaluateSecurityMap(sm, userPrincipal, userRoles);
        }

        // 2. Check for flattened security fields: security_allowed_read / security_denied_read
        boolean hasStructuredAllowed = metadata.containsKey("security_allowed_read") || metadata.containsKey("security_allowed_read_ss");
        List<String> allowedRead = extractStringList(metadata.getOrDefault("security_allowed_read", metadata.get("security_allowed_read_ss")));
        List<String> deniedRead = extractStringList(metadata.getOrDefault("security_denied_read", metadata.get("security_denied_read_ss")));

        if (hasStructuredAllowed && allowedRead.isEmpty()) {
            return false;
        }

        List<String> acls = extractStringList(metadata.getOrDefault("acl", metadata.get("acl_ss")));

        return isAccessible(acls, allowedRead, deniedRead, userPrincipal, userRoles);
    }

    /**
     * Evaluates access with explicit single ACL string, allowedRead, and deniedRead lists.
     */
    public static boolean isAccessible(String acl, Collection<String> allowedRead, Collection<String> deniedRead,
                                        String userPrincipal, Collection<String> userRoles) {
        List<String> acls = extractStringList(acl);
        return isAccessible(acls, allowedRead, deniedRead, userPrincipal, userRoles);
    }

    /**
     * Evaluates access with explicit allowedRead / deniedRead lists and fallback ACL list.
     */
    public static boolean isAccessible(Collection<String> acls, Collection<String> allowedRead, Collection<String> deniedRead,
                                        String userPrincipal, Collection<String> userRoles) {
        // 1. Explicit DENY overrides everything
        if (deniedRead != null && matchesAnyIdentity(deniedRead, userPrincipal, userRoles)) {
            return false;
        }

        // 2. Structured ALLOW rules take precedence over flat fallback
        if (allowedRead != null && !allowedRead.isEmpty()) {
            return matchesAnyIdentity(allowedRead, userPrincipal, userRoles);
        }

        // 3. Flat ACL fallback
        if (acls == null || acls.isEmpty()) {
            return false;
        }
        return matchesAnyIdentity(acls, userPrincipal, userRoles);
    }

    private static boolean evaluateSecurityConfig(SecurityConfig sc, String userPrincipal, Collection<String> userRoles) {
        if (sc == null || sc.permissions() == null || sc.permissions().isEmpty()) {
            return false;
        }

        boolean hasReadAccess = false;
        for (PermissionRule rule : sc.permissions()) {
            if (rule == null || rule.identity() == null || rule.access() == null) continue;
            boolean matches = matchesRule(rule.identity(), rule.identityType(), userPrincipal, userRoles);
            if (matches) {
                if ("deny".equalsIgnoreCase(rule.access())) {
                    return false; // Explicit deny overrides everything
                } else if ("read".equalsIgnoreCase(rule.access()) || "write".equalsIgnoreCase(rule.access()) || "admin".equalsIgnoreCase(rule.access())) {
                    hasReadAccess = true;
                }
            }
        }
        return hasReadAccess;
    }

    private static boolean evaluateSecurityMap(Map<?, ?> sm, String userPrincipal, Collection<String> userRoles) {
        Object permissionsObj = sm.get("permissions");
        if (!(permissionsObj instanceof List<?> permissionsList) || permissionsList.isEmpty()) {
            return false;
        }

        boolean hasReadAccess = false;
        for (Object permObj : permissionsList) {
            if (permObj instanceof Map<?, ?> perm) {
                String identity = perm.get("identity") != null ? String.valueOf(perm.get("identity")) : null;
                String identityType = perm.get("identityType") != null ? String.valueOf(perm.get("identityType")) : null;
                String access = perm.get("access") != null ? String.valueOf(perm.get("access")) : null;

                if (identity == null || access == null) continue;

                boolean matches = matchesRule(identity, identityType, userPrincipal, userRoles);
                if (matches) {
                    if ("deny".equalsIgnoreCase(access)) {
                        return false;
                    } else if ("read".equalsIgnoreCase(access) || "write".equalsIgnoreCase(access) || "admin".equalsIgnoreCase(access)) {
                        hasReadAccess = true;
                    }
                }
            }
        }
        return hasReadAccess;
    }

    private static boolean matchesRule(String identity, String identityType, String userPrincipal, Collection<String> userRoles) {
        if (PUBLIC_IDENTITY.equalsIgnoreCase(identity) || PUBLIC_IDENTITY.equalsIgnoreCase(identityType)) {
            return true;
        }
        return matchesIdentity(identity, userPrincipal, userRoles);
    }

    public static boolean matchesAnyIdentity(Collection<String> identities, String userPrincipal, Collection<String> userRoles) {
        if (identities == null || identities.isEmpty()) return false;
        for (String identity : identities) {
            if (matchesIdentity(identity, userPrincipal, userRoles)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an ACL identity matches the caller. Names are compared case-insensitively, without a leading
     * {@code user:} on the principal or {@code group:} on a role. The identity's own prefix decides which side it
     * can match: {@code user:x} only the principal, {@code group:x} only a role, and an identity without a prefix
     * either of them. A user is therefore never granted (or denied) as a group of the same name.
     */
    public static boolean matchesIdentity(String identity, String userPrincipal, Collection<String> userRoles) {
        if (identity == null) return false;
        String trimmed = identity.trim();
        if (PUBLIC_IDENTITY.equalsIgnoreCase(trimmed)) {
            return true;
        }
        boolean userOnly = USER_PREFIX.matcher(trimmed).lookingAt();
        boolean groupOnly = GROUP_PREFIX.matcher(trimmed).lookingAt();
        String name = (userOnly || groupOnly) ? trimmed.substring(trimmed.indexOf(':') + 1) : trimmed;
        if (!groupOnly && userPrincipal != null && !userPrincipal.isBlank()) {
            String principalName = USER_PREFIX.matcher(userPrincipal.trim()).replaceFirst("");
            if (principalName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        if (!userOnly && userRoles != null) {
            for (String role : userRoles) {
                if (role != null) {
                    String roleName = GROUP_PREFIX.matcher(role.trim()).replaceFirst("");
                    if (roleName.equalsIgnoreCase(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<String> extractStringList(Object obj) {
        if (obj == null) return Collections.emptyList();
        // Items of a list are whole identities: an identity may itself contain commas (an LDAP DN such as
        // cn=bob,ou=users,dc=corp), so only a single legacy string is split on commas.
        if (obj instanceof Collection<?> col) {
            List<String> list = new ArrayList<>();
            for (Object item : col) {
                if (item != null) {
                    addToken(String.valueOf(item), list);
                }
            }
            return list;
        } else if (obj instanceof String[] arr) {
            List<String> list = new ArrayList<>();
            for (String item : arr) {
                if (item != null) {
                    addToken(item, list);
                }
            }
            return list;
        } else if (obj instanceof String s && !s.isBlank()) {
            List<String> list = new ArrayList<>();
            addSplitTokens(s, list);
            return list;
        }
        return Collections.emptyList();
    }

    private static void addToken(String raw, List<String> target) {
        String trimmed = raw.trim();
        if (!trimmed.isEmpty()) {
            target.add(trimmed);
        }
    }

    private static void addSplitTokens(String raw, List<String> target) {
        if (raw == null || raw.isBlank()) return;
        if (raw.contains(",")) {
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    target.add(trimmed);
                }
            }
        } else {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }
}
