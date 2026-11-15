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
package org.opencrawling.sharepoint;

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
import java.util.Set;

/**
 * Maps the permissions of a drive item ({@code GET /drives/{id}/items/{id}/permissions}) to an OIS {@link SecurityConfig},
 * the flat {@code acl} string and the {@code sharepoint_identity_users} / {@code sharepoint_identity_groups} lists.
 *
 * <ul>
 *   <li>A permission grants only through the roles {@code read}, {@code write} and {@code owner} (write and owner map to
 *       {@code write}, which includes read); any other role grants nothing.</li>
 *   <li>Direct grants name their principals in {@code grantedToV2} / {@code grantedToIdentitiesV2} (the deprecated
 *       {@code grantedTo} / {@code grantedToIdentities} are read only when the V2 fields are absent). See
 *       {@link SharePointPrincipals} for the tokens.</li>
 *   <li>Sharing links: a {@code users} link grants the people it names; an {@code organization} or {@code anonymous} link
 *       grants only through the configured {@code organization-link-identity} / {@code anonymous-link-identity}, because
 *       holding a link is not the same as being allowed to find the file in search. {@code existingAccess} links grant
 *       nothing new.</li>
 *   <li>SharePoint has no deny entries. No readable grant means the configured fallback principals, or nobody: an item is
 *       never made public by accident.</li>
 * </ul>
 */
public class SharePointSecurityMapper {

    /** The mapped security and a fingerprint of it, so an ACL-only change can be detected. */
    public record Mapped(SecurityConfig security, String acl, List<String> users, List<String> groups, String fingerprint) {
    }

    private static final Set<String> READ_ROLES = Set.of("read");
    private static final Set<String> WRITE_ROLES = Set.of("write", "owner");

    private final SharePointProperties settings;
    private final SharePointPrincipals principals;

    public SharePointSecurityMapper(SharePointProperties settings, SharePointPrincipals principals) {
        this.settings = settings;
        this.principals = principals;
    }

    public static Mapped publicAccess() {
        SecurityConfig security = SecurityConfig.createPublic();
        return new Mapped(security, "public", List.of(), List.of(), fingerprint(security.permissions()));
    }

    public Mapped map(List<JsonNode> permissions) {
        Map<String, PermissionRule> rules = new LinkedHashMap<>();
        for (JsonNode permission : permissions) {
            String access = access(permission.path("roles"));
            if (access == null) {
                continue;
            }
            for (SharePointPrincipals.Principal p : grantees(permission)) {
                PermissionRule rule = new PermissionRule(p.identity(), p.type(), p.displayName(), access);
                String key = rule.identityType() + "|" + rule.identity();
                PermissionRule existing = rules.get(key);
                if (existing == null || ("write".equals(access) && !"write".equals(existing.access()))) {
                    rules.put(key, rule);
                }
            }
        }
        return rules.isEmpty() ? fallback() : build(new ArrayList<>(rules.values()));
    }

    /** The principals a permission grants to; empty for links that grant nothing under the configuration. */
    private List<SharePointPrincipals.Principal> grantees(JsonNode permission) {
        List<SharePointPrincipals.Principal> out = new ArrayList<>();
        JsonNode link = permission.path("link");
        if (link.isObject()) {
            String scope = link.path("scope").asText("").toLowerCase(Locale.ROOT);
            switch (scope) {
                case "users" -> identitySets(permission).forEach(set -> addIdentitySet(set, out));
                case "organization" -> principals.configured(settings.organizationLinkIdentity(), "Organization sharing link").ifPresent(out::add);
                case "anonymous" -> principals.configured(settings.anonymousLinkIdentity(), "Anonymous sharing link").ifPresent(out::add);
                default -> {
                    // existingAccess and unknown scopes grant nothing new
                }
            }
            return out;
        }
        identitySets(permission).forEach(set -> addIdentitySet(set, out));
        return out;
    }

    /** {@code grantedToV2} and {@code grantedToIdentitiesV2}, or the deprecated V1 fields when no V2 field is present. */
    private static List<JsonNode> identitySets(JsonNode permission) {
        List<JsonNode> sets = new ArrayList<>();
        boolean v2 = permission.has("grantedToV2") || permission.has("grantedToIdentitiesV2");
        JsonNode single = permission.path(v2 ? "grantedToV2" : "grantedTo");
        if (single.isObject()) {
            sets.add(single);
        }
        permission.path(v2 ? "grantedToIdentitiesV2" : "grantedToIdentities").forEach(sets::add);
        return sets;
    }

    private void addIdentitySet(JsonNode set, List<SharePointPrincipals.Principal> out) {
        // a site user is either a claim for a group or for everybody, or a person described by both facets
        JsonNode siteUser = set.path("siteUser");
        JsonNode user = set.path("user");
        if (siteUser.isObject() && !SharePointPrincipals.isPerson(siteUser)) {
            principals.siteUser(siteUser).ifPresent(out::add);
        } else if (siteUser.isObject() || user.isObject()) {
            out.addAll(principals.person(siteUser, user));
        }
        if (set.path("sharePointGroup").isObject()) {
            principals.siteGroup(set.path("sharePointGroup")).ifPresent(out::add);
        } else if (set.path("siteGroup").isObject()) {
            principals.siteGroup(set.path("siteGroup")).ifPresent(out::add);
        }
        if (set.path("group").isObject()) {
            principals.group(set.path("group")).ifPresent(out::add);
        }
        // application and device grants do not correspond to people and are not indexed
    }

    /** {@code write} for write/owner, {@code read} for read, null when no role grants read. */
    private static String access(JsonNode roles) {
        boolean read = false;
        for (JsonNode role : roles) {
            String r = role.asText("").toLowerCase(Locale.ROOT);
            if (WRITE_ROLES.contains(r)) {
                return "write";
            }
            read |= READ_ROLES.contains(r);
        }
        return read ? "read" : null;
    }

    /** The configured fallback principals ({@code group:x}, {@code user:x}, a bare name = group, {@code public}), or nobody. */
    Mapped fallback() {
        List<PermissionRule> rules = new ArrayList<>();
        for (String principal : settings.fallbackPrincipals()) {
            String key = principal.strip();
            String lower = key.toLowerCase(Locale.ROOT);
            if ("public".equals(lower)) {
                rules.add(new PermissionRule("public", "public", "Public Access", "read"));
            } else if (lower.startsWith("user:")) {
                rules.add(new PermissionRule(key.substring(5), "user", key.substring(5), "read"));
            } else {
                String name = lower.startsWith("group:") ? key.substring(6) : key;
                rules.add(new PermissionRule(name, "group", name, "read"));
            }
        }
        return build(rules);
    }

    private static Mapped build(List<PermissionRule> rules) {
        List<String> users = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        boolean everybody = false;
        for (PermissionRule rule : rules) {
            switch (rule.identityType()) {
                case "public" -> everybody = true;
                case "user" -> users.add(rule.identity());
                default -> groups.add(rule.identity());
            }
        }
        List<String> readers = new ArrayList<>(users);
        readers.addAll(groups);
        String acl = everybody ? "public" : String.join(",", readers);
        return new Mapped(new SecurityConfig(false, List.copyOf(rules)), acl, List.copyOf(users), List.copyOf(groups),
                fingerprint(rules));
    }

    /** A stable hash of the rules (sorted), stored in the crawl state to notice permission-only changes. */
    static String fingerprint(List<PermissionRule> rules) {
        List<String> canonical = new ArrayList<>();
        for (PermissionRule r : rules) {
            canonical.add(r.identityType() + "|" + r.identity() + "|" + r.access());
        }
        canonical.sort(String::compareTo);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("\n", canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
