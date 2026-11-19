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
package org.opencrawling.zammad;

import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps Zammad group permissions, customer principals, shared organization visibility, and Knowledge Base category
 * permissions into OIS {@link SecurityConfig} and flat ACL strings.
 *
 * <p>Enforces strict security boundaries between public ticket conversations ({@code internal: false}) and internal
 * agent notes ({@code internal: true}):
 * <ul>
 *   <li><b>Public ticket thread / public article &amp; its attachments</b>: grants read to the owning Zammad group
 *       ({@code zammad-group-<groupName>}), the ticket customer ({@code user:<customerEmail>}, when
 *       {@code includeCustomerInPublicAcl} is true), and the shared organization
 *       ({@code zammad-org-<orgName>}, only when {@code includeSharedOrganizationInPublicAcl} is true and
 *       {@code organization.shared == true}).</li>
 *   <li><b>Internal agent notes / internal article &amp; its attachments</b>: grants read <em>exclusively</em> to the
 *       owning Zammad group ({@code zammad-group-<groupName>}). Customer and organization principals are never included.</li>
 *   <li><b>Fail-closed default</b>: if permissions cannot be resolved (for example, missing group on a ticket or
 *       unresolvable internal KB roles), returns an empty {@link SecurityConfig} so no unauthorized caller can read it.</li>
 * </ul>
 */
public class ZammadSecurityMapper {

    public record Mapped(SecurityConfig security, String acl, List<String> users, List<String> groups) {
        public String fingerprint() {
            return acl + "|u:" + String.join(",", users) + "|g:" + String.join(",", groups);
        }
    }

    private final ZammadProperties settings;

    public ZammadSecurityMapper(ZammadProperties settings) {
        this.settings = settings;
    }

    public static Mapped publicAccess() {
        return new Mapped(SecurityConfig.createPublic(), "public", List.of(), List.of());
    }

    public static Mapped failClosed() {
        return new Mapped(new SecurityConfig(false, List.of()), "", List.of(), List.of());
    }

    /**
     * Computes effective OIS security for a ticket conversation view, article, or attachment.
     *
     * @param groupName          the Zammad group name owning the ticket
     * @param customerEmail      the ticket customer's email/login (may be {@code null})
     * @param organizationName   the ticket customer's organization name (may be {@code null})
     * @param organizationShared whether the organization has {@code shared: true} in Zammad
     * @param orgMemberEmails    optional resolved member emails of a shared organization
     * @param internal           {@code true} for internal agent notes or attachments on internal articles
     */
    public Mapped forTicket(
            String groupName,
            String customerEmail,
            String organizationName,
            boolean organizationShared,
            List<String> orgMemberEmails,
            boolean internal
    ) {
        if (!settings.includeAcls()) {
            return publicAccess();
        }
        String sanitizedGroup = sanitizeToken(groupName);
        if (sanitizedGroup == null) {
            return failClosed();
        }

        List<PermissionRule> rules = new ArrayList<>();
        Set<String> users = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();

        String groupIdentity = "zammad-group-" + sanitizedGroup;
        rules.add(new PermissionRule(groupIdentity, "group", groupIdentity, "read"));
        groups.add(groupIdentity);

        if (!internal) {
            if (settings.includeCustomerInPublicAcl() && customerEmail != null && !customerEmail.isBlank()) {
                String cleanCustomer = customerEmail.strip();
                rules.add(new PermissionRule(cleanCustomer, "user", cleanCustomer, "read"));
                users.add(cleanCustomer);
            }
            if (settings.includeSharedOrganizationInPublicAcl() && organizationShared) {
                String sanitizedOrg = sanitizeToken(organizationName);
                if (sanitizedOrg != null) {
                    String orgIdentity = "zammad-org-" + sanitizedOrg;
                    rules.add(new PermissionRule(orgIdentity, "group", orgIdentity, "read"));
                    groups.add(orgIdentity);
                }
                if (orgMemberEmails != null) {
                    for (String member : orgMemberEmails) {
                        if (member != null && !member.isBlank()) {
                            String cleanMember = member.strip();
                            if (users.add(cleanMember)) {
                                rules.add(new PermissionRule(cleanMember, "user", cleanMember, "read"));
                            }
                        }
                    }
                }
            }
        }

        List<String> readers = new ArrayList<>(groups);
        readers.addAll(users);
        return new Mapped(
                new SecurityConfig(true, List.copyOf(rules)),
                String.join(",", readers),
                List.copyOf(users),
                List.copyOf(groups)
        );
    }

    /**
     * Computes effective OIS security for a Zammad Group folder during discovery.
     */
    public Mapped forGroup(String groupName) {
        if (!settings.includeAcls()) {
            return publicAccess();
        }
        String sanitizedGroup = sanitizeToken(groupName);
        if (sanitizedGroup == null) {
            return failClosed();
        }
        String groupIdentity = "zammad-group-" + sanitizedGroup;
        PermissionRule rule = new PermissionRule(groupIdentity, "group", groupIdentity, "read");
        return new Mapped(
                new SecurityConfig(true, List.of(rule)),
                groupIdentity,
                List.of(),
                List.of(groupIdentity)
        );
    }

    /**
     * Computes effective OIS security for a Zammad Knowledge Base category or answer.
     *
     * @param readerRoleNames role names granted {@code reader} or {@code editor} access on the KB category
     * @param fallbackGroups  active agent group names when category permissions are not explicitly restricted
     * @param internal        {@code true} when the KB answer is marked internal (or unpublished)
     */
    public Mapped forKnowledgeBase(List<String> readerRoleNames, List<String> fallbackGroups, boolean internal) {
        return forKnowledgeBase(readerRoleNames, false, fallbackGroups, internal);
    }

    /**
     * Computes effective OIS security for a Zammad Knowledge Base category or answer, distinguishing categories
     * with no granular permissions from categories whose explicit permissions deny all roles ({@code access: "none"}).
     *
     * @param readerRoleNames     role names granted {@code reader} or {@code editor} access on the KB category
     * @param explicitPermissions {@code true} when granular permission entries exist on the category/KB; if
     *                            {@code readerRoleNames} is empty under explicit permissions, fails closed instead
     *                            of falling back to all active agent groups
     * @param fallbackGroups      active agent group names when no granular permissions are configured
     * @param internal            {@code true} when the KB answer is marked internal (or unpublished)
     */
    public Mapped forKnowledgeBase(
            List<String> readerRoleNames,
            boolean explicitPermissions,
            List<String> fallbackGroups,
            boolean internal
    ) {
        if (!settings.includeAcls()) {
            return publicAccess();
        }
        if (!internal && settings.allowPublicKb()) {
            return publicAccess();
        }

        List<PermissionRule> rules = new ArrayList<>();
        Set<String> groups = new LinkedHashSet<>();

        if (readerRoleNames != null) {
            for (String role : readerRoleNames) {
                String sanitized = sanitizeToken(role);
                if (sanitized != null) {
                    String roleIdentity = "zammad-role-" + sanitized;
                    if (groups.add(roleIdentity)) {
                        rules.add(new PermissionRule(roleIdentity, "group", roleIdentity, "read"));
                    }
                }
            }
        }

        if (groups.isEmpty() && !explicitPermissions && fallbackGroups != null) {
            for (String group : fallbackGroups) {
                String sanitized = sanitizeToken(group);
                if (sanitized != null) {
                    String groupIdentity = "zammad-group-" + sanitized;
                    if (groups.add(groupIdentity)) {
                        rules.add(new PermissionRule(groupIdentity, "group", groupIdentity, "read"));
                    }
                }
            }
        }

        if (groups.isEmpty()) {
            return failClosed();
        }

        return new Mapped(
                new SecurityConfig(true, List.copyOf(rules)),
                String.join(",", groups),
                List.of(),
                List.copyOf(groups)
        );
    }

    /**
     * Normalizes a Zammad group, role, or organization name into a stable token without spaces or commas
     * (e.g. {@code "ClientCorp Europe"} -> {@code "ClientCorp-Europe"}).
     */
    public static String sanitizeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.strip()
                .replaceAll("[\\s,/\\\\]+", "-")
                .replaceAll("[^A-Za-z0-9._-]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }
}
