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
package org.opencrawling.filenet;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a FileNet object's {@code permissions} to an OIS {@link SecurityConfig}, the flat {@code acl} string and the
 * {@code filenet_identity_users} / {@code filenet_identity_groups} lists.
 *
 * <p>FileNet's {@code Permissions} list is already the object's effective list: its direct and default entries, those
 * inherited from its security parent ({@code SOURCE_PARENT}), a security proxy ({@code PROXY}), a security template
 * ({@code SOURCE_TEMPLATE}) and markings ({@code MARKING}). For every grantee the {@code ALLOW} masks and the {@code DENY}
 * masks are combined:
 * <ul>
 *   <li>a grantee is a reader when its allowed rights cover {@code read-access-mask} (by default FileNet's "view content"
 *       level, 131201 = read, view content, read permissions) and none of those rights is denied;</li>
 *   <li>a grantee denied any of those rights gets an OIS {@code deny} rule, which wins over every grant. FileNet ranks
 *       entries by source (a direct allow can override an inherited deny); the mapping does not, so it can only restrict
 *       access, never widen it. Unlike the ManifoldCF FileNet connector, a deny of one right (e.g. only view content) is
 *       never ignored;</li>
 *   <li>{@code #AUTHENTICATED-USERS} becomes the configured identity (a group), or {@code public} when configured;
 *       {@code #CREATOR-OWNER} and entries without a grantee name grant nothing;</li>
 *   <li>a grantee of type {@code UNKNOWN} (an unresolved account, often shown as a SID) is kept verbatim as a group.</li>
 * </ul>
 * Nothing readable means the configured fallback principals, or nobody: never public by accident.
 */
public class FileNetSecurityMapper {

    private static final Logger log = LoggerFactory.getLogger(FileNetSecurityMapper.class);
    static final String AUTHENTICATED_USERS = "#AUTHENTICATED-USERS";
    static final String CREATOR_OWNER = "#CREATOR-OWNER";

    /** The mapped security: the OIS rules, the flat {@code acl} string, the readers, and a fingerprint of the rules. */
    public record Mapped(SecurityConfig security, String acl, List<String> users, List<String> groups, String fingerprint) {
    }

    private record Grantee(String identity, String type) {
    }

    private final FileNetProperties settings;

    public FileNetSecurityMapper(FileNetProperties settings) {
        this.settings = settings;
    }

    /** Whether a document carries markings: none, at least one, or not reported (treated as marked by the caller). */
    public enum Markings { NONE, MARKED, UNKNOWN }

    /**
     * A document's markings, from its {@code ActiveMarkings} property (the {@code markings} alias of the document query) or
     * a {@code MARKING} entry in its permissions. A missing or unreadable {@code ActiveMarkings} is {@code UNKNOWN}: a
     * marking can restrict access without showing in the permissions, so a document whose markings cannot be read is not
     * assumed to have none.
     */
    public static Markings markings(JsonNode doc) {
        for (JsonNode p : doc.path("permissions")) {
            if ("MARKING".equals(p.path("permissionSource").asText())) {
                return Markings.MARKED;
            }
        }
        for (JsonNode property : doc.path("markings")) {
            if ("ActiveMarkings".equalsIgnoreCase(property.path("id").asText())) {
                JsonNode list = property.path("objectListValue");
                if (!list.isArray()) {
                    return Markings.UNKNOWN;
                }
                return list.isEmpty() ? Markings.NONE : Markings.MARKED;
            }
        }
        return Markings.UNKNOWN;
    }

    public static Mapped publicAccess() {
        return new Mapped(SecurityConfig.createPublic(), "public", List.of(), List.of(), "public");
    }

    public Mapped map(JsonNode permissions) {
        if (!settings.includeAcls()) {
            return publicAccess();
        }
        int mask = settings.readAccessMask();
        Map<Grantee, int[]> rights = new LinkedHashMap<>(); // grantee -> {allowed, denied}
        if (permissions != null && permissions.isArray()) {
            for (JsonNode p : permissions) {
                String name = p.path("granteeName").asText("").strip();
                String accessType = p.path("accessType").asText("");
                if (name.isEmpty() || !p.path("accessMask").isNumber()
                        || !(accessType.equals("ALLOW") || accessType.equals("DENY"))) {
                    continue; // not an access permission (a role or delegated permission), or unreadable
                }
                Grantee grantee = grantee(name, p.path("granteeType").asText("UNKNOWN"));
                if (grantee == null) {
                    continue;
                }
                int[] r = rights.computeIfAbsent(grantee, g -> new int[2]);
                r[accessType.equals("ALLOW") ? 0 : 1] |= p.path("accessMask").asInt();
            }
        }
        List<PermissionRule> rules = new ArrayList<>();
        List<String> users = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        boolean everyone = false;
        for (Map.Entry<Grantee, int[]> e : rights.entrySet()) {
            Grantee g = e.getKey();
            int allowed = e.getValue()[0];
            int denied = e.getValue()[1];
            if ((denied & mask) != 0) {
                if (!g.identity().equals("public")) {
                    rules.add(new PermissionRule(g.identity(), g.type(), g.identity(), "deny"));
                }
            } else if ((allowed & mask) == mask) {
                if (g.identity().equals("public")) {
                    everyone = true;
                    continue;
                }
                rules.add(new PermissionRule(g.identity(), g.type(), g.identity(), "read"));
                (g.type().equals("user") ? users : groups).add(g.identity());
            }
        }
        if (everyone) {
            // public, but explicit denies still exclude their grantees
            List<PermissionRule> all = new ArrayList<>(SecurityConfig.createPublic().permissions());
            rules.stream().filter(r -> r.access().equals("deny")).forEach(all::add);
            return new Mapped(new SecurityConfig(false, List.copyOf(all)), "public", List.of(), List.of(), fingerprint(all));
        }
        if (users.isEmpty() && groups.isEmpty()) {
            return fallback(rules);
        }
        List<String> readers = new ArrayList<>(users);
        readers.addAll(groups);
        return new Mapped(new SecurityConfig(false, List.copyOf(rules)), String.join(",", readers), List.copyOf(users),
                List.copyOf(groups), fingerprint(rules));
    }

    /** No reader: the fallback principals, or nobody (an empty rule list, which the MCP server treats as closed). */
    private Mapped fallback(List<PermissionRule> denies) {
        List<PermissionRule> rules = new ArrayList<>();
        for (String principal : settings.fallbackPrincipals()) {
            rules.add(new PermissionRule(principal, "group", principal, "read"));
        }
        rules.addAll(denies);
        return new Mapped(new SecurityConfig(false, List.copyOf(rules)), String.join(",", settings.fallbackPrincipals()),
                List.of(), List.copyOf(settings.fallbackPrincipals()), fingerprint(rules));
    }

    private Grantee grantee(String name, String granteeType) {
        if (name.equalsIgnoreCase(AUTHENTICATED_USERS)) {
            if (settings.authenticatedUsersPublic()) {
                return new Grantee("public", "group");
            }
            String identity = settings.authenticatedUsersIdentity();
            return identity == null || identity.isBlank() ? null : new Grantee(identity.toLowerCase(Locale.ROOT), "group");
        }
        if (name.equalsIgnoreCase(CREATOR_OWNER)) {
            log.debug("{} grants nothing: it is a template placeholder, not a principal.", CREATOR_OWNER);
            return null;
        }
        String identity = identity(name);
        return identity.isEmpty() ? null : new Grantee(identity, "USER".equals(granteeType) ? "user" : "group");
    }

    /** A grantee name as an identity: lower-cased, and with {@code principal-format: short} reduced to the short name. */
    String identity(String name) {
        String n = name.strip();
        if (settings.principalFormat() == FileNetProperties.PrincipalFormat.SHORT) {
            if (n.regionMatches(true, 0, "cn=", 0, 3) || n.regionMatches(true, 0, "uid=", 0, 4)) {
                int comma = n.indexOf(',');
                n = n.substring(n.indexOf('=') + 1, comma < 0 ? n.length() : comma);
            } else if (n.contains("\\")) {
                n = n.substring(n.lastIndexOf('\\') + 1);
            }
        }
        return n.strip().toLowerCase(Locale.ROOT);
    }

    static String fingerprint(List<PermissionRule> rules) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            rules.stream().map(r -> r.identityType() + "|" + r.identity() + "|" + r.access()).sorted()
                    .forEach(s -> digest.update((s + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
