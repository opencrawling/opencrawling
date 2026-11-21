/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.googledrive;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Translates Google Drive v3 item permissions ({@code /drive/v3/files/{fileId}/permissions}) into an OIS
 * {@link SecurityConfig}, flat {@code acl} string, identity metadata lists, and a stable fingerprint for detecting
 * permission-only updates.
 *
 * <p>Security guarantees (fail-closed):
 * <ul>
 *   <li>Deleted or expired permissions are ignored.</li>
 *   <li>{@code user} permissions map to {@code identityType = "user"} (lowercased email).</li>
 *   <li>{@code group} permissions map to {@code identityType = "group"} (lowercased group email).</li>
 *   <li>{@code domain} permissions map to {@code identityType = "group"} with {@code domainIdentityPrefix + domain}.</li>
 *   <li>{@code anyone} permissions grant public access ONLY when {@code allowAnyonePublic = true}; otherwise ignored.</li>
 *   <li>When no readable grant exists, {@link SecurityConfig#none()} and an empty {@code acl} are returned (never public).</li>
 * </ul>
 */
public class GoogleDriveSecurityMapper {

    /** Mapped OIS security plus flattened helpers and a canonical SHA-256 fingerprint. */
    public record Mapped(
            SecurityConfig security,
            String acl,
            List<String> users,
            List<String> groups,
            List<String> domains,
            String fingerprint
    ) {
    }

    private static final Set<String> WRITE_ROLES = Set.of("owner", "organizer", "fileorganizer", "writer");
    private static final Set<String> READ_ROLES = Set.of("commenter", "reader");

    private final GoogleDriveProperties settings;

    public GoogleDriveSecurityMapper(GoogleDriveProperties settings) {
        this.settings = settings;
    }

    public static Mapped publicAccess() {
        SecurityConfig security = SecurityConfig.createPublic();
        return new Mapped(security, "public", List.of(), List.of(), List.of(), fingerprint(security));
    }

    public static Mapped deniedAccess() {
        SecurityConfig security = SecurityConfig.none();
        return new Mapped(security, "", List.of(), List.of(), List.of(), fingerprint(security));
    }

    public Mapped map(List<JsonNode> permissions) {
        return map(permissions, Instant.now());
    }

    Mapped map(List<JsonNode> permissions, Instant now) {
        if (permissions == null || permissions.isEmpty()) {
            return deniedAccess();
        }

        Map<String, PermissionRule> rules = new LinkedHashMap<>();
        boolean inherited = false;

        for (JsonNode perm : permissions) {
            if (perm == null || !perm.isObject()) {
                continue;
            }
            if (perm.path("deleted").asBoolean(false)) {
                continue;
            }
            if (isExpired(perm.path("expirationTime").asText(null), now)) {
                continue;
            }
            String access = mapRole(perm.path("role").asText(""));
            if (access == null) {
                continue;
            }
            if (hasInheritedDetail(perm)) {
                inherited = true;
            }

            String type = perm.path("type").asText("").strip().toLowerCase(Locale.ROOT);
            PermissionRule rule = switch (type) {
                case "user" -> {
                    String email = normalizeIdentity(perm.path("emailAddress").asText(null));
                    if (email == null) {
                        yield null;
                    }
                    String display = nonBlankOr(perm.path("displayName").asText(null), email);
                    yield new PermissionRule(email, "user", display, access);
                }
                case "group" -> {
                    String email = normalizeIdentity(perm.path("emailAddress").asText(null));
                    if (email == null) {
                        yield null;
                    }
                    String display = nonBlankOr(perm.path("displayName").asText(null), email);
                    yield new PermissionRule(email, "group", display, access);
                }
                case "domain" -> {
                    String domain = normalizeIdentity(perm.path("domain").asText(null));
                    if (domain == null) {
                        yield null;
                    }
                    String prefix = settings.domainIdentityPrefix() == null ? "" : settings.domainIdentityPrefix();
                    String identity = prefix + domain;
                    String display = nonBlankOr(perm.path("displayName").asText(null), domain);
                    yield new PermissionRule(identity, "group", display, access);
                }
                case "anyone" -> settings.allowAnyonePublic()
                        ? new PermissionRule("public", "public", "Public Access", "read")
                        : null;
                default -> null;
            };

            if (rule == null) {
                continue;
            }
            String key = rule.identityType() + "|" + rule.identity();
            PermissionRule existing = rules.get(key);
            if (existing == null || ("write".equals(rule.access()) && !"write".equals(existing.access()))) {
                rules.put(key, rule);
            }
        }

        if (rules.isEmpty()) {
            return deniedAccess();
        }

        List<PermissionRule> ruleList = List.copyOf(rules.values());
        List<String> users = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        List<String> domains = new ArrayList<>();
        boolean isPublic = false;
        String domPrefix = settings.domainIdentityPrefix() == null ? "" : settings.domainIdentityPrefix();

        for (PermissionRule r : ruleList) {
            switch (r.identityType()) {
                case "public" -> isPublic = true;
                case "user" -> users.add(r.identity());
                default -> {
                    groups.add(r.identity());
                    if (!domPrefix.isEmpty() && r.identity().startsWith(domPrefix)) {
                        domains.add(r.identity().substring(domPrefix.length()));
                    }
                }
            }
        }

        List<String> flatReaders = new ArrayList<>(users);
        flatReaders.addAll(groups);
        String acl = isPublic ? "public" : String.join(",", flatReaders);
        SecurityConfig security = new SecurityConfig(inherited, ruleList);
        return new Mapped(security, acl, List.copyOf(users), List.copyOf(groups), List.copyOf(domains),
                fingerprint(security));
    }

    private static String mapRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        String lower = role.strip().toLowerCase(Locale.ROOT);
        if (WRITE_ROLES.contains(lower)) {
            return "write";
        }
        if (READ_ROLES.contains(lower)) {
            return "read";
        }
        return null;
    }

    private static boolean hasInheritedDetail(JsonNode perm) {
        JsonNode details = perm.path("permissionDetails");
        if (details.isArray()) {
            for (JsonNode d : details) {
                if (d.path("inherited").asBoolean(false)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isExpired(String expirationTime, Instant now) {
        if (expirationTime == null || expirationTime.isBlank()) {
            return false;
        }
        try {
            Instant expiry = OffsetDateTime.parse(expirationTime.strip()).toInstant();
            return !expiry.isAfter(now);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String normalizeIdentity(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate.strip() : fallback;
    }

    static String fingerprint(SecurityConfig security) {
        List<String> canonical = new ArrayList<>();
        canonical.add("inherit=" + security.inheritanceEnabled());
        for (PermissionRule r : security.permissions()) {
            canonical.add(r.identityType() + "|" + r.identity() + "|" + r.access());
        }
        canonical.sort(String::compareTo);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
