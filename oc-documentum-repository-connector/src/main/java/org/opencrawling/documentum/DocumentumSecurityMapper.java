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
package org.opencrawling.documentum;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Maps a Documentum permission set ({@code dm_acl}) to an OIS {@link SecurityConfig}, the flat {@code acl} string and the
 * {@code documentum_identity_users} / {@code documentum_identity_groups} lists.
 *
 * <p>Documentum gives a user the most permissive basic permit of the entries that match them (their own, their groups',
 * {@code dm_world}, {@code dm_owner}, {@code dm_group}), then lowers it by any access restriction that matches them; required
 * groups and required group sets remove access from anyone outside them. The mapping, entry by entry
 * ({@code r_permit_type}):
 * <ul>
 *   <li>an access permit (0) of READ (3) or more makes its accessor a reader;</li>
 *   <li>an access restriction (3) of READ or less, which takes READ away, becomes an OIS {@code deny} for its accessor,
 *       which wins over every grant, as the restriction does; a restriction whose accessor cannot be named (with
 *       {@code principal-format: login}, a user without a login name of their own) leaves the fallback principals
 *       only, as dropping it would grant that user;</li>
 *   <li>{@code dm_owner} and {@code dm_group} become the document's {@code owner_name} and {@code group_name};
 *       {@code dm_world} becomes the configured group, or {@code public} when configured;</li>
 *   <li>required groups (6) and required group sets (7) are an AND of groups, which OIS rules cannot express: such
 *       documents are excluded by default, or indexed for the fallback principals only;</li>
 *   <li>extended permits and restrictions (1, 4) and application permits and restrictions (2, 5) never grant READ and are
 *       ignored, as are superusers (they read everything) and unresolved alias accessors ({@code %alias}).</li>
 * </ul>
 * Nothing readable means the configured fallback principals, or nobody: never public by accident.
 */
public class DocumentumSecurityMapper {

    static final int READ = 3;
    static final int ACCESS_PERMIT = 0;
    static final int ACCESS_RESTRICTION = 3;
    static final int REQUIRED_GROUP = 6;
    static final int REQUIRED_GROUP_SET = 7;
    static final String WORLD = "dm_world";
    static final String OWNER = "dm_owner";
    static final String GROUP = "dm_group";

    /** The mapped security: the OIS rules, the flat {@code acl} string, the readers, and a fingerprint of the rules. */
    public record Mapped(SecurityConfig security, String acl, List<String> users, List<String> groups, String fingerprint,
                         boolean excluded) {
    }

    /** One entry of a permission set. */
    public record Entry(String accessor, int permit, int permitType, boolean group) {
    }

    /** A permission set: its id, {@code i_vstamp} and entries. */
    public record Acl(String id, String domain, String name, int vstamp, List<Entry> entries) {

        /** A permission set from a {@code dm_acl} row: the repeating attributes are parallel arrays. */
        static Acl of(JsonNode row) {
            JsonNode names = row.path("r_accessor_name");
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                entries.add(new Entry(names.path(i).asText(""), row.path("r_accessor_permit").path(i).asInt(1),
                        row.path("r_permit_type").path(i).asInt(ACCESS_PERMIT), row.path("r_is_group").path(i).asBoolean(false)));
            }
            return new Acl(row.path("r_object_id").asText(), row.path("owner_name").asText(), row.path("object_name").asText(),
                    row.path("i_vstamp").asInt(), List.copyOf(entries));
        }

        String key() {
            return key(domain, name);
        }

        static String key(String domain, String name) {
            return domain + "\u0000" + name;
        }
    }

    /** What the mapping needs to know about a user name: whether it is a group, and its login name. */
    public record Principal(boolean group, String login) {
    }

    private record Identity(String name, String type) {
    }

    private final DocumentumProperties settings;

    public DocumentumSecurityMapper(DocumentumProperties settings) {
        this.settings = settings;
    }

    public static Mapped publicAccess() {
        return new Mapped(SecurityConfig.createPublic(), "public", List.of(), List.of(), "public", false);
    }

    /**
     * The security of a document.
     *
     * @param acl        its permission set, or null when it cannot be found (nobody is a reader then)
     * @param owner      its {@code owner_name}
     * @param group      its {@code group_name}
     * @param principals resolves a user name (a named accessor or the owner) to its kind and login; null when unknown
     */
    public Mapped map(Acl acl, String owner, String group, Function<String, Principal> principals) {
        if (!settings.includeAcls()) {
            return publicAccess();
        }
        Map<Identity, Boolean> rules = new LinkedHashMap<>(); // identity -> granted (true) or denied (false)
        boolean everyone = false;
        boolean required = false;
        boolean worldDenied = false;
        boolean unresolvedDeny = false;
        for (Entry e : acl == null ? List.<Entry>of() : acl.entries()) {
            if (e.permitType() == REQUIRED_GROUP || e.permitType() == REQUIRED_GROUP_SET) {
                required = true;
                continue;
            }
            boolean grant = e.permitType() == ACCESS_PERMIT && e.permit() >= READ;
            boolean deny = e.permitType() == ACCESS_RESTRICTION && e.permit() <= READ;
            if (!grant && !deny) {
                continue;
            }
            if (e.accessor().equalsIgnoreCase(WORLD)) {
                if (deny) {
                    worldDenied = true; // nobody but superusers reads it
                } else if (settings.worldPublic()) {
                    everyone = true;
                } else if (!settings.worldIdentity().isBlank()) {
                    put(rules, new Identity(settings.worldIdentity(), "group"), grant);
                }
                continue;
            }
            Identity identity = identity(e, owner, group, principals);
            if (identity != null) {
                put(rules, identity, grant);
            } else if (deny && !e.accessor().strip().startsWith("%")) {
                unresolvedDeny = true; // a restriction on someone the index cannot name: dropping it would grant them
            }
        }
        List<PermissionRule> denies = new ArrayList<>();
        rules.forEach((id, granted) -> {
            if (!granted) {
                denies.add(new PermissionRule(id.name(), id.type(), id.name(), "deny"));
            }
        });
        if (required) {
            if (settings.requiredGroupDocuments() == DocumentumProperties.RequiredGroupDocuments.EXCLUDE) {
                return new Mapped(null, "", List.of(), List.of(), "excluded", true);
            }
            return fallback(denies);
        }
        if (worldDenied || unresolvedDeny) {
            return fallback(denies);
        }
        if (everyone) {
            // public, but restrictions still exclude their accessors
            List<PermissionRule> all = new ArrayList<>(SecurityConfig.createPublic().permissions());
            all.addAll(denies);
            return new Mapped(new SecurityConfig(false, List.copyOf(all)), "public", List.of(), List.of(), fingerprint(all),
                    false);
        }
        List<PermissionRule> all = new ArrayList<>();
        List<String> users = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        rules.forEach((id, granted) -> {
            if (granted) {
                all.add(new PermissionRule(id.name(), id.type(), id.name(), "read"));
                (id.type().equals("user") ? users : groups).add(id.name());
            }
        });
        if (all.isEmpty()) {
            return fallback(denies);
        }
        all.addAll(denies);
        List<String> readers = new ArrayList<>(users);
        readers.addAll(groups);
        return new Mapped(new SecurityConfig(false, List.copyOf(all)), String.join(",", readers), List.copyOf(users),
                List.copyOf(groups), fingerprint(all), false);
    }

    /** A deny always wins over a grant of the same identity, whatever the entry order. */
    private static void put(Map<Identity, Boolean> rules, Identity identity, boolean grant) {
        rules.merge(identity, grant, (a, b) -> a && b);
    }

    /** No reader: the fallback principals, or nobody (an empty rule list, which the MCP server treats as closed). */
    private Mapped fallback(List<PermissionRule> denies) {
        List<PermissionRule> rules = new ArrayList<>();
        for (String principal : settings.fallbackPrincipals()) {
            rules.add(new PermissionRule(principal, "group", principal, "read"));
        }
        rules.addAll(denies);
        return new Mapped(new SecurityConfig(false, List.copyOf(rules)), String.join(",", settings.fallbackPrincipals()),
                List.of(), List.copyOf(settings.fallbackPrincipals()), fingerprint(rules), false);
    }

    private Identity identity(Entry e, String owner, String group, Function<String, Principal> principals) {
        String accessor = e.accessor().strip();
        if (accessor.isEmpty() || accessor.startsWith("%")) {
            return null; // an alias of an ACL template, never resolved on a document's own permission set
        }
        if (accessor.equalsIgnoreCase(GROUP)) {
            return group == null || group.isBlank() ? null : new Identity(lower(group), "group");
        }
        if (accessor.equalsIgnoreCase(OWNER)) {
            if (owner == null || owner.isBlank()) {
                return null;
            }
            return named(owner, null, principals); // the owner can be a user or a group
        }
        return named(accessor, e.group(), principals);
    }

    /** A user or group name as an identity; with {@code principal-format: login} a user becomes its login name. */
    private Identity named(String name, Boolean isGroup, Function<String, Principal> principals) {
        Principal p = principals == null ? null : principals.apply(name);
        boolean group = isGroup != null ? isGroup : p != null && p.group();
        if (group) {
            return new Identity(lower(name), "group");
        }
        if (settings.principalFormat() == DocumentumProperties.PrincipalFormat.LOGIN) {
            if (p == null || p.login() == null || p.login().isBlank()) {
                return null; // an unknown user cannot be matched by login: grant nothing
            }
            return new Identity(lower(p.login()), "user");
        }
        return new Identity(lower(name), "user");
    }

    private static String lower(String s) {
        return s.strip().toLowerCase(Locale.ROOT);
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
