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
package org.opencrawling.mfiles;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maps M-Files object permissions to an OIS {@link SecurityConfig} plus the flat {@code acl} string and the
 * {@code mfiles_identity_users} / {@code mfiles_identity_groups} lists.
 *
 * <p>Two sources:
 * <ul>
 *   <li><b>MFWS</b> ({@code /objects/{type}/{id}/{version}/permissions}, not in the public MFWS reference). The object's
 *       effective {@code AccessControlList} holds a {@code CustomComponent} and zero or more {@code AutomaticComponents}
 *       (workflow-state or metadata-driven), each with {@code AccessControlEntries}; inactive components are skipped, and
 *       the {@code NamedACL} block (the named ACL's definition, already reflected in the effective list) is not read.
 *       M-Files combines components restrictively, so an identity gets read only when <em>every</em> component allows it;
 *       a deny in any component wins. A payload without an {@code AccessControlList} is read defensively: every array of
 *       entries carrying {@code UserOrGroupID} counts as one component.
 *       <br>{@code ReadPermission} is numeric on real servers: {@code 1} grants read (verified against an M-Files vault,
 *       where the built-in "Full control for all internal users" ACL has {@code 1} for every permission). The other
 *       {@code MFPermission} codes are not documented for MFWS, so any other number is treated as a deny for that identity:
 *       an unverified code can only restrict access, never widen it. Textual {@code "Allow"} / {@code "Deny"} and bit 1 of
 *       {@code AccessFlags} are understood too. Pseudo-users (metadata-driven entries such as "Project manager", flagged
 *       {@code IsPsudoUser}, M-Files' spelling, or without a concrete user or group id) cannot be resolved here and grant
 *       nothing.</li>
 *   <li><b>Vault Application Framework</b>: {@code aclTokens} / {@code denyTokens} ({@code mfiles:user:<id>},
 *       {@code mfiles:group:<id>}, {@code mfiles:role:<id>}), already resolved server-side to effective identities.</li>
 * </ul>
 * Nothing readable means the configured fallback principals, or nobody: never public by accident.
 */
public class MFilesSecurityMapper {

    private static final Logger log = LoggerFactory.getLogger(MFilesSecurityMapper.class);
    private static final AtomicBoolean NUMERIC_WARNED = new AtomicBoolean();

    /** The {@code MFPermission} value that grants read, verified against a real vault. */
    static final int PERMISSION_ALLOW = 1;

    /** Resolves a user ({@code group=false}) or user group id to a name, for {@code principal-format: name}. */
    @FunctionalInterface
    public interface NameResolver {
        Optional<String> name(int id, boolean group);
    }

    /** The mapped security: the OIS rules, the flat {@code acl} string, and the users and groups granted read. */
    public record Mapped(SecurityConfig security, String acl, List<String> users, List<String> groups) {
    }

    /** A user, user group or (VAF) role; only users are of identity type {@code user}. */
    private record Principal(int id, String kind) {
        boolean group() {
            return !"user".equals(kind);
        }
    }

    private final MFilesProperties settings;
    private final NameResolver names;

    public MFilesSecurityMapper(MFilesProperties settings, NameResolver names) {
        this.settings = settings;
        this.names = names == null ? (id, group) -> Optional.empty() : names;
    }

    public static Mapped publicAccess() {
        return new Mapped(SecurityConfig.createPublic(), "public", List.of(), List.of());
    }

    /** Maps an MFWS permissions payload; an empty one (resource unavailable) gives the fallback. */
    public Mapped fromPermissions(Optional<JsonNode> permissions) {
        if (permissions.isEmpty()) {
            return fallback();
        }
        List<List<JsonNode>> components = components(permissions.get());

        Set<Principal> denied = new LinkedHashSet<>();
        Set<Principal> allowed = null;
        int pseudo = 0;
        for (List<JsonNode> component : components) {
            Set<Principal> componentAllowed = new LinkedHashSet<>();
            for (JsonNode entry : component) {
                if (isPseudoUser(entry)) {
                    pseudo++;
                    continue;
                }
                Principal principal = new Principal(entry.path("UserOrGroupID").asInt(), isGroup(entry) ? "group" : "user");
                switch (read(entry)) {
                    case ALLOW -> componentAllowed.add(principal);
                    case DENY -> denied.add(principal);
                    case NONE -> {
                    }
                }
            }
            if (allowed == null) {
                allowed = componentAllowed;
            } else {
                allowed.retainAll(componentAllowed);
            }
        }
        if (pseudo > 0) {
            log.debug("{} pseudo-user entries (metadata-driven permissions) cannot be resolved through MFWS and grant nothing; "
                    + "use the VAF crawl mode for effective permissions.", pseudo);
        }
        if (allowed != null) {
            allowed.removeAll(denied);
        }
        return build(allowed == null ? Set.of() : allowed, denied);
    }

    /** Maps VAF {@code aclTokens} and {@code denyTokens}. */
    public Mapped fromTokens(JsonNode aclTokens, JsonNode denyTokens) {
        Set<Principal> allowed = new LinkedHashSet<>();
        Set<Principal> denied = new LinkedHashSet<>();
        List<String> rawAllowed = new ArrayList<>();
        List<String> rawDenied = new ArrayList<>();
        tokens(aclTokens, allowed, rawAllowed);
        tokens(denyTokens, denied, rawDenied);
        allowed.removeAll(denied);
        if (allowed.isEmpty() && rawAllowed.isEmpty()) {
            return fallback();
        }
        Mapped mapped = build(allowed, denied);
        if (rawAllowed.isEmpty() && rawDenied.isEmpty()) {
            return mapped;
        }
        // tokens in a format other than mfiles:<kind>:<id> are kept verbatim as group identities
        List<PermissionRule> rules = new ArrayList<>(mapped.security().permissions());
        List<String> groups = new ArrayList<>(mapped.groups());
        for (String raw : rawAllowed) {
            rules.add(rule(raw, "group", "read"));
            groups.add(qualify(raw));
        }
        for (String raw : rawDenied) {
            rules.add(rule(raw, "group", "deny"));
        }
        List<String> readers = new ArrayList<>(mapped.users());
        readers.addAll(groups);
        return new Mapped(new SecurityConfig(false, List.copyOf(rules)), String.join(",", readers), mapped.users(),
                List.copyOf(groups));
    }

    private void tokens(JsonNode array, Set<Principal> principals, List<String> raw) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode node : array) {
            String token = node.asText("").strip();
            String[] parts = token.split(":");
            if (parts.length == 3 && "mfiles".equalsIgnoreCase(parts[0]) && parts[2].matches("-?\\d+")) {
                principals.add(new Principal(Integer.parseInt(parts[2]), parts[1].toLowerCase(Locale.ROOT)));
            } else if (!token.isEmpty()) {
                raw.add(token);
            }
        }
    }

    private Mapped build(Set<Principal> allowed, Set<Principal> denied) {
        if (allowed.isEmpty()) {
            Mapped fallback = fallback();
            if (denied.isEmpty()) {
                return fallback;
            }
            List<PermissionRule> rules = new ArrayList<>(fallback.security().permissions());
            denied.forEach(p -> rules.add(rule(p, "deny")));
            return new Mapped(new SecurityConfig(false, List.copyOf(rules)), fallback.acl(), fallback.users(), fallback.groups());
        }
        Map<String, PermissionRule> rules = new LinkedHashMap<>();
        List<String> users = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        boolean everybody = false;
        for (Principal p : allowed) {
            if (p.group() && settings.publicGroupIds().contains(p.id())) {
                everybody = true;
                continue;
            }
            PermissionRule rule = rule(p, "read");
            rules.putIfAbsent(rule.identityType() + "|" + rule.identity(), rule);
            (p.group() ? groups : users).add(rule.identity());
        }
        for (Principal p : denied) {
            PermissionRule rule = rule(p, "deny");
            rules.put(rule.identityType() + "|" + rule.identity(), rule);
        }
        List<PermissionRule> list = new ArrayList<>();
        if (everybody) {
            list.add(new PermissionRule("public", "public", "Public Access", "read"));
        }
        list.addAll(rules.values());
        List<String> readers = new ArrayList<>(users);
        readers.addAll(groups);
        String acl = everybody ? "public" : String.join(",", readers);
        return new Mapped(new SecurityConfig(false, List.copyOf(list)), acl, List.copyOf(users), List.copyOf(groups));
    }

    /** The configured fallback principals ({@code group:x}, {@code user:x}, a bare name = group, {@code public}), or nobody. */
    Mapped fallback() {
        List<PermissionRule> rules = new ArrayList<>();
        List<String> users = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        boolean everybody = false;
        for (String principal : settings.fallbackPrincipals()) {
            String key = principal.strip();
            String lower = key.toLowerCase(Locale.ROOT);
            if ("public".equals(lower) || "everybody".equals(lower)) {
                everybody = true;
                rules.add(new PermissionRule("public", "public", "Public Access", "read"));
            } else if (lower.startsWith("user:")) {
                rules.add(rule(key.substring(5), "user", "read"));
                users.add(qualify(key.substring(5)));
            } else {
                String name = lower.startsWith("group:") ? key.substring(6) : key;
                rules.add(rule(name, "group", "read"));
                groups.add(qualify(name));
            }
        }
        List<String> readers = new ArrayList<>(users);
        readers.addAll(groups);
        return new Mapped(new SecurityConfig(false, List.copyOf(rules)), everybody ? "public" : String.join(",", readers),
                List.copyOf(users), List.copyOf(groups));
    }

    private PermissionRule rule(Principal p, String access) {
        String type = p.group() ? "group" : "user";
        String identity = "mfiles:" + p.kind() + ":" + p.id();
        if (settings.principalFormat() == MFilesProperties.PrincipalFormat.NAME && !"role".equals(p.kind())) {
            identity = names.name(p.id(), p.group()).orElse(identity);
        }
        return rule(identity, type, access);
    }

    private PermissionRule rule(String identity, String type, String access) {
        String qualified = qualify(identity);
        return new PermissionRule(qualified, type, qualified, access);
    }

    private String qualify(String identity) {
        return settings.qualifyPrincipals() ? settings.vaultKey() + "/" + identity : identity;
    }

    private enum Read { ALLOW, DENY, NONE }

    private static Read read(JsonNode entry) {
        for (String field : List.of("ReadPermission", "Read", "ReadAccess")) {
            JsonNode value = entry.path(field);
            if (value.isTextual()) {
                return switch (value.asText().toLowerCase(Locale.ROOT)) {
                    case "allow", "allowed", "true" -> Read.ALLOW;
                    case "deny", "denied", "false" -> Read.DENY;
                    default -> Read.NONE;
                };
            }
            if (value.isBoolean()) {
                return value.asBoolean() ? Read.ALLOW : Read.NONE;
            }
            if (value.isNumber()) {
                if (value.asInt() == PERMISSION_ALLOW) {
                    return Read.ALLOW;
                }
                // only the allow code is verified; any other code may be a deny, so it can only restrict
                if (NUMERIC_WARNED.compareAndSet(false, true)) {
                    log.warn("M-Files permission entry with {}={} (not the verified allow code {}): treated as a deny for that "
                            + "identity.", field, value.asInt(), PERMISSION_ALLOW);
                }
                return Read.DENY;
            }
        }
        JsonNode flags = entry.path("AccessFlags");
        if (flags.isNumber()) {
            return (flags.asInt() & 1) != 0 ? Read.ALLOW : Read.NONE;
        }
        return Read.NONE;
    }

    private static boolean isGroup(JsonNode entry) {
        if (entry.has("IsGroup")) {
            return entry.path("IsGroup").asBoolean(false);
        }
        String type = entry.path("UserOrGroupType").asText("");
        return type.equalsIgnoreCase("group") || type.equals("2");
    }

    /**
     * The access-control components of a permissions payload: the effective {@code AccessControlList}'s custom component
     * and active automatic components, or, for a payload of another shape, every array of permission entries.
     */
    static List<List<JsonNode>> components(JsonNode permissions) {
        List<List<JsonNode>> components = new ArrayList<>();
        JsonNode acl = permissions.path("AccessControlList");
        if (!acl.isObject()) {
            collectComponents(permissions, components);
            return components;
        }
        List<JsonNode> parts = new ArrayList<>();
        parts.add(acl.path("CustomComponent"));
        acl.path("AutomaticComponents").forEach(parts::add);
        for (JsonNode component : parts) {
            if (!component.isObject() || !component.path("IsActive").asBoolean(true)) {
                continue;
            }
            List<JsonNode> entries = new ArrayList<>();
            component.path("AccessControlEntries").forEach(e -> {
                if (e.isObject() && e.has("UserOrGroupID")) {
                    entries.add(e);
                }
            });
            if (!entries.isEmpty()) {
                components.add(entries);
            }
        }
        if (components.isEmpty() && !acl.has("CustomComponent") && !acl.has("AutomaticComponents")) {
            // another layout of the effective list: walk it (only it, never the NamedACL definition)
            collectComponents(acl, components);
        }
        return components;
    }

    /** A pseudo-user entry: {@code IsPsudoUser} (M-Files' spelling) or {@code IsPseudoUser}, or no concrete user or group id. */
    static boolean isPseudoUser(JsonNode entry) {
        return entry.path("IsPsudoUser").asBoolean(false) || entry.path("IsPseudoUser").asBoolean(false)
                || entry.has("PseudoUserID")
                || !entry.path("HasConcreteUserOrGruopID").asBoolean(entry.path("HasConcreteUserOrGroupID").asBoolean(true));
    }

    /** Every array whose elements are permission entries ({@code UserOrGroupID}) is one component. */
    private static void collectComponents(JsonNode node, List<List<JsonNode>> components) {
        if (node.isArray()) {
            List<JsonNode> entries = new ArrayList<>();
            for (JsonNode element : node) {
                if (element.isObject() && element.has("UserOrGroupID")) {
                    entries.add(element);
                } else {
                    collectComponents(element, components);
                }
            }
            if (!entries.isEmpty()) {
                components.add(entries);
            }
        } else if (node.isObject()) {
            node.forEach(child -> collectComponents(child, components));
        }
    }
}
