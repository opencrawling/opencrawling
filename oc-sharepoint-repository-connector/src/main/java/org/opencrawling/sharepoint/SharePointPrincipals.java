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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The identity tokens the connector emits, in one place, so whatever maps a caller to its identities (an MCP client, an
 * authority) can produce exactly the same strings:
 *
 * <ul>
 *   <li><b>Entra ID user</b>: the e-mail / user principal name in lower case ({@code user-identity: email}, default), or
 *       the Entra object id ({@code user-identity: id}). Identity type {@code user}.</li>
 *   <li><b>Entra ID group</b> (security or Microsoft 365 group): the Entra object id, which is what Entra puts in a
 *       token's {@code groups} claim. Identity type {@code group}.</li>
 *   <li><b>SharePoint group</b> (site group, e.g. "Engineering Members"): {@code sharepoint-group:<siteId>:<groupId>}.
 *       Microsoft Graph exposes no member list for these, so they are emitted as group tokens and must be resolved to
 *       their members outside the connector. Identity type {@code group}.</li>
 *   <li><b>Everyone</b> and <b>Everyone except external users</b> (SharePoint claims {@code c:0(.s|true} and
 *       {@code c:0-.f|rolemanager|spo-grid-all-users/<tenant>}): the configured {@code everyone-identity} /
 *       {@code everyone-except-external-identity}; nothing when not configured. Never {@code public} unless configured.</li>
 * </ul>
 */
public final class SharePointPrincipals {

    /** A resolved principal: the token, its OIS identity type and a display name. */
    public record Principal(String identity, String type, String displayName) {
    }

    private static final String EVERYONE_CLAIM = "c:0(.s|true";
    private static final String ALL_USERS_CLAIM = "spo-grid-all-users";
    private static final String USER_CLAIM_PREFIX = "i:0#.f|membership|";
    private static final String TENANT_GROUP_CLAIM = "c:0t.c|tenant|";
    private static final String M365_GROUP_CLAIM = "c:0o.c|federateddirectoryclaimprovider|";

    private final SharePointProperties settings;
    private final String siteId;

    public SharePointPrincipals(SharePointProperties settings, String siteId) {
        this.settings = settings;
        this.siteId = siteId;
    }

    /** The token of a SharePoint site group. */
    public static String sharePointGroup(String siteId, String groupId) {
        return "sharepoint-group:" + siteId + ":" + groupId;
    }

    /** An Entra ID user ({@code identity} resource: {@code id}, {@code email}, {@code userPrincipalName}, {@code displayName}). */
    public Optional<Principal> user(JsonNode user) {
        String id = GraphClient.text(user, "id");
        String name = user.path("displayName").asText(null);
        if (settings.userIdentity() == SharePointProperties.UserIdentity.ID && id != null) {
            return Optional.of(new Principal(id.toLowerCase(Locale.ROOT), "user", name));
        }
        String email = firstNonBlank(GraphClient.text(user, "email"), GraphClient.text(user, "userPrincipalName"),
                fromLoginName(GraphClient.text(user, "loginName")));
        if (email != null) {
            return Optional.of(new Principal(email.toLowerCase(Locale.ROOT), "user", name));
        }
        return id == null ? Optional.empty() : Optional.of(new Principal(id.toLowerCase(Locale.ROOT), "user", name));
    }

    /** An Entra ID group: its object id. */
    public Optional<Principal> group(JsonNode group) {
        String id = GraphClient.text(group, "id");
        return id == null ? Optional.empty()
                : Optional.of(new Principal(id.toLowerCase(Locale.ROOT), "group", group.path("displayName").asText(null)));
    }

    /** A SharePoint site group ({@code siteGroup} / {@code sharePointGroup}). */
    public Optional<Principal> siteGroup(JsonNode group) {
        String id = firstNonBlank(GraphClient.text(group, "id"), GraphClient.text(group, "principalId"));
        return id == null || siteId == null ? Optional.empty()
                : Optional.of(new Principal(sharePointGroup(siteId, id), "group",
                        firstNonBlank(group.path("displayName").asText(null), GraphClient.text(group, "title"))));
    }

    /**
     * A SharePoint site user ({@code siteUser}): a person, or a claim standing for a group or for everybody, told apart by
     * its {@code loginName}.
     */
    public Optional<Principal> siteUser(JsonNode siteUser) {
        String login = GraphClient.text(siteUser, "loginName");
        String name = siteUser.path("displayName").asText(null);
        if (login == null) {
            return user(siteUser);
        }
        String lower = login.toLowerCase(Locale.ROOT);
        if (lower.equals(EVERYONE_CLAIM)) {
            return configured(settings.everyoneIdentity(), name);
        }
        if (lower.contains(ALL_USERS_CLAIM)) {
            return configured(settings.everyoneExceptExternalIdentity(), name);
        }
        if (lower.startsWith(TENANT_GROUP_CLAIM)) {
            return Optional.of(new Principal(lower.substring(TENANT_GROUP_CLAIM.length()), "group", name));
        }
        if (lower.startsWith(M365_GROUP_CLAIM)) {
            // "<groupId>_o" is the group's owners; both are granted through the group in OpenCrawling
            String id = lower.substring(M365_GROUP_CLAIM.length()).replaceFirst("_o$", "");
            return Optional.of(new Principal(id, "group", name));
        }
        if (lower.startsWith(USER_CLAIM_PREFIX)) {
            return user(siteUser);
        }
        if (lower.contains("|")) {
            // other claim types (Windows accounts, other providers) are kept verbatim so they can still be matched
            return Optional.of(new Principal(lower, "user", name));
        }
        // not a claim: Microsoft's documented examples carry the person's display name here, which identifies nobody
        return Optional.ofNullable(fromLoginName(login)).map(mail -> new Principal(mail.toLowerCase(Locale.ROOT), "user", name));
    }

    /** Whether a {@code siteUser} stands for a person (a membership claim, or no claim at all) rather than a group claim. */
    static boolean isPerson(JsonNode siteUser) {
        String login = GraphClient.text(siteUser, "loginName");
        return login == null || login.toLowerCase(Locale.ROOT).startsWith(USER_CLAIM_PREFIX) || !login.contains("|");
    }

    /**
     * A person granted through an identity set, from both of its facets: the e-mail / UPN comes from the {@code siteUser}
     * membership claim or from the {@code user} facet, the Entra object id from {@code user.id}. SharePoint's own numeric
     * {@code siteUser.id} and a {@code loginName} that isn't a claim are never used. With {@code user-identity: email} the
     * person gets both identifiers when both are known, so a caller matches by either; Microsoft's documented permissions
     * often carry only the object id. With {@code id}, the object id alone (the e-mail when there's no id).
     */
    public List<Principal> person(JsonNode siteUser, JsonNode user) {
        String name = firstNonBlank(user.path("displayName").asText(null), siteUser.path("displayName").asText(null));
        String email = firstNonBlank(fromLoginName(GraphClient.text(siteUser, "loginName")), GraphClient.text(user, "email"),
                GraphClient.text(user, "userPrincipalName"), fromLoginName(GraphClient.text(user, "loginName")),
                GraphClient.text(siteUser, "email"));
        String id = GraphClient.text(user, "id");
        List<Principal> out = new ArrayList<>(2);
        if (settings.userIdentity() == SharePointProperties.UserIdentity.ID && id != null) {
            out.add(new Principal(id.toLowerCase(Locale.ROOT), "user", name));
            return out;
        }
        if (email != null) {
            out.add(new Principal(email.toLowerCase(Locale.ROOT), "user", name));
        }
        if (id != null) {
            out.add(new Principal(id.toLowerCase(Locale.ROOT), "user", name));
        }
        return out;
    }

    /** The configured identity for a broad claim (everyone, organization or anonymous links); nothing when not configured. */
    Optional<Principal> configured(String identity, String displayName) {
        if (identity == null || identity.isBlank()) {
            return Optional.empty();
        }
        String id = identity.strip();
        if ("public".equalsIgnoreCase(id)) {
            return Optional.of(new Principal("public", "public", "Public Access"));
        }
        return Optional.of(new Principal(id, "group", displayName != null ? displayName : id));
    }

    /** {@code i:0#.f|membership|adele@contoso.com} → {@code adele@contoso.com}. */
    static String fromLoginName(String login) {
        if (login == null) {
            return null;
        }
        int bar = login.lastIndexOf('|');
        String tail = bar >= 0 ? login.substring(bar + 1) : login;
        return tail.contains("@") ? tail : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
