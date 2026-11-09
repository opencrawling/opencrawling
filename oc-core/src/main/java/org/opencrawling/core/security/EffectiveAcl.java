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
package org.opencrawling.core.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one place a document's permissions are turned into the fields every store indexes
 * ({@code security_allowed_read}, {@code security_denied_read}, {@code security_inheritance}) and into the
 * {@code security} object stored next to them.
 *
 * <p>Rules, matching how {@code McpAclEvaluator} reads a stored {@link SecurityConfig}:
 * <ul>
 *   <li>When the document has permission rules, they decide: {@code read}, {@code write} and {@code admin}
 *       grant read, {@code deny} denies it, and the legacy {@code acl} string is ignored (connectors put
 *       values there that are not identities, such as {@code user:a;group:b} or {@code secured}).</li>
 *   <li>A rule whose identity type is {@code public} (for example a CMIS {@code anonymous} or
 *       {@code GROUP_EVERYONE} entry) grants {@code public}, not the literal repository name.</li>
 *   <li>A rule with a known user type ({@code user}, case-insensitive) resolves to {@code user:<identity>},
 *       and a known group type ({@code group}, {@code role}) resolves to {@code group:<identity>}, unless
 *       the identity already has a prefix. Rules without a type or with an unknown type keep their bare
 *       identity.</li>
 *   <li>When the rules are only the default public placeholder ({@link SecurityConfig#createPublic()}) and the
 *       legacy {@code acl} names identities, the {@code acl} wins: a connector that could not read the source
 *       permissions but did set an {@code acl} must not make the document public.</li>
 *   <li>Without rules, the legacy {@code acl} tokens grant read; a {@code read} token is an access level, not
 *       an identity, and is dropped.</li>
 * </ul>
 *
 * @param inheritanceEnabled whether the source permissions are inherited
 * @param allowedRead        identities that may read the document ({@code public} for everyone)
 * @param deniedRead         identities explicitly denied, which wins over any grant
 * @param security           the permission rules to store with the document, or {@code null} when it has
 *                           none; replaces a default public placeholder that the {@code acl} overrode
 */
public record EffectiveAcl(
        boolean inheritanceEnabled,
        List<String> allowedRead,
        List<String> deniedRead,
        SecurityConfig security
) {

    public static final String PUBLIC = "public";

    private static final String LEGACY_ACL_IDENTITY_TYPE = "acl";
    private static final Pattern USER_PREFIX = Pattern.compile("^(?i)user:");
    private static final Pattern GROUP_PREFIX = Pattern.compile("^(?i)group:");

    public EffectiveAcl {
        allowedRead = List.copyOf(allowedRead);
        deniedRead = List.copyOf(deniedRead);
    }

    /**
     * Resolves the effective permissions of a document.
     *
     * @param security  a {@link SecurityConfig}, the {@code Map} shape one takes after a JSON round trip through
     *                  Kafka, or {@code null}
     * @param legacyAcl the legacy comma-separated {@code acl} string, or {@code null}
     */
    public static EffectiveAcl resolve(Object security, Object legacyAcl) {
        SecurityConfig rules = toSecurityConfig(security);
        List<String> aclTokens = legacyTokens(legacyAcl);
        boolean aclNamesIdentities = aclTokens.stream().anyMatch(token -> !PUBLIC.equals(token));
        boolean hasRules = rules != null && rules.permissions() != null && !rules.permissions().isEmpty();
        boolean placeholderOverridden = hasRules && isDefaultPublic(rules) && aclNamesIdentities;

        if (hasRules && !placeholderOverridden) {
            Set<String> allowed = new LinkedHashSet<>();
            Set<String> denied = new LinkedHashSet<>();
            List<PermissionRule> normalizedRules = new ArrayList<>();
            for (PermissionRule rule : rules.permissions()) {
                if (rule == null || rule.identity() == null || rule.access() == null) {
                    continue;
                }
                String identity = identityOf(rule);
                if (identity.isEmpty()) {
                    // A blank identity matches no caller unless its type makes it public.
                    continue;
                }
                normalizedRules.add(new PermissionRule(identity, rule.identityType(), rule.displayName(), rule.access()));
                switch (rule.access().toLowerCase(Locale.ROOT)) {
                    case "read", "write", "admin" -> allowed.add(identity);
                    case "deny" -> denied.add(identity);
                    default -> {
                        // Unknown access levels grant nothing.
                    }
                }
            }
            SecurityConfig stored = new SecurityConfig(rules.inheritanceEnabled(), normalizedRules);
            return new EffectiveAcl(rules.inheritanceEnabled(), new ArrayList<>(allowed), new ArrayList<>(denied), stored);
        }

        boolean inheritance = rules != null && rules.inheritanceEnabled();
        SecurityConfig stored = rules;
        if (placeholderOverridden || (!hasRules && !aclTokens.isEmpty())) {
            List<PermissionRule> fromAcl = new ArrayList<>();
            for (String token : aclTokens) {
                fromAcl.add(PUBLIC.equals(token)
                        ? new PermissionRule(PUBLIC, PUBLIC, "Public Access", "read")
                        : new PermissionRule(token, LEGACY_ACL_IDENTITY_TYPE, token, "read"));
            }
            stored = new SecurityConfig(inheritance, fromAcl);
        }
        return new EffectiveAcl(inheritance, aclTokens, List.of(), stored);
    }

    /**
     * Whether everyone may read the document.
     */
    public boolean isPublic() {
        return allowedRead.contains(PUBLIC) && deniedRead.isEmpty();
    }

    /**
     * The placeholder connectors send when they could not read the source permissions: only grants to an
     * identity literally named {@code public} (as {@link SecurityConfig#createPublic()} does). A real
     * public-typed rule such as a CMIS {@code GROUP_EVERYONE} entry is not a placeholder and is never
     * overridden by the {@code acl} string.
     */
    private static boolean isDefaultPublic(SecurityConfig rules) {
        return rules.permissions().stream().allMatch(rule -> rule != null
                && rule.identity() != null
                && PUBLIC.equalsIgnoreCase(rule.identity().trim())
                && "read".equalsIgnoreCase(rule.access()));
    }

    private static String identityOf(PermissionRule rule) {
        String identity = rule.identity().trim();
        if (PUBLIC.equalsIgnoreCase(identity)
                || PUBLIC.equalsIgnoreCase(rule.identityType())) {
            return PUBLIC;
        }
        if (identity.isEmpty()) {
            return "";
        }
        if (USER_PREFIX.matcher(identity).lookingAt() || GROUP_PREFIX.matcher(identity).lookingAt()) {
            return identity;
        }
        if (rule.identityType() != null) {
            String type = rule.identityType().trim().toLowerCase(Locale.ROOT);
            if ("user".equals(type)) {
                return "user:" + identity;
            }
            if ("group".equals(type) || "role".equals(type)) {
                return "group:" + identity;
            }
        }
        return identity;
    }

    private static List<String> legacyTokens(Object legacyAcl) {
        Set<String> tokens = new LinkedHashSet<>();
        if (legacyAcl instanceof Iterable<?> values) {
            for (Object value : values) {
                addTokens(value, tokens);
            }
        } else {
            addTokens(legacyAcl, tokens);
        }
        return new ArrayList<>(tokens);
    }

    private static void addTokens(Object value, Set<String> tokens) {
        if (value == null) {
            return;
        }
        for (String part : String.valueOf(value).split(",")) {
            String token = part.trim();
            if (token.isEmpty() || "read".equalsIgnoreCase(token)) {
                continue;
            }
            tokens.add(PUBLIC.equalsIgnoreCase(token) ? PUBLIC : token);
        }
    }

    private static SecurityConfig toSecurityConfig(Object security) {
        if (security instanceof SecurityConfig config) {
            return config;
        }
        if (!(security instanceof Map<?, ?> map)) {
            return null;
        }
        boolean inheritance = Boolean.TRUE.equals(map.get("inheritanceEnabled"))
                || "true".equalsIgnoreCase(String.valueOf(map.get("inheritanceEnabled")));
        List<PermissionRule> permissions = new ArrayList<>();
        if (map.get("permissions") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof PermissionRule rule) {
                    permissions.add(rule);
                } else if (item instanceof Map<?, ?> rule) {
                    permissions.add(new PermissionRule(text(rule.get("identity")), text(rule.get("identityType")),
                            text(rule.get("displayName")), text(rule.get("access"))));
                }
            }
        }
        return new SecurityConfig(inheritance, permissions);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
