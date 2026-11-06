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
package org.opencrawling.runtime.security;

import org.opencrawling.core.security.Caller;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a verified {@link Caller} from the claims of a validated token.
 *
 * <p>The user identity and the groups come from separate claims and are never mixed: a group claim value only ever
 * becomes a group, so a {@code user:x} grant cannot be matched through a role and a {@code group:x} grant cannot be
 * matched through the principal (the rule from #224).
 */
public final class JwtCallerMapper {

    /** Attribute holding the token's {@code sub} claim. */
    public static final String SUBJECT = "subject";
    /** Attribute holding the token issuer. */
    public static final String ISSUER = "issuer";
    /** Attribute holding the OAuth2 client the token was issued to ({@code azp} or {@code client_id}). */
    public static final String CLIENT = "client";
    /** Attribute holding the acting party of a delegated (RFC 8693 token exchange) token: {@code act.sub}. */
    public static final String ACTOR = "actor";
    /**
     * Attribute holding the token's OAuth2 scopes, as a list of strings: the space-separated {@code scope} claim
     * and the {@code scp} claim (a list or a space-separated string). Never mixed into the groups.
     */
    public static final String SCOPES = "scopes";

    private final List<String> principalClaims;
    private final List<String> groupClaims;
    private final String tenantClaim;

    public JwtCallerMapper(IdentityProperties.Jwt config) {
        this(config.getPrincipalClaims(), config.getGroupClaims(), config.getTenantClaim());
    }

    public JwtCallerMapper(List<String> principalClaims, List<String> groupClaims, String tenantClaim) {
        this.principalClaims = List.copyOf(principalClaims);
        this.groupClaims = List.copyOf(groupClaims);
        this.tenantClaim = tenantClaim == null ? "" : tenantClaim.trim();
    }

    /**
     * The caller described by a token's claims. The principal is {@code null} when none of the principal claims
     * holds a non-blank string; such a caller can still match its groups and {@code public}.
     */
    public Caller toCaller(Map<String, Object> claims) {
        String principal = null;
        for (String path : principalClaims) {
            if (claim(claims, path) instanceof String value && !value.isBlank()) {
                principal = value.trim();
                break;
            }
        }

        Set<String> groups = new LinkedHashSet<>();
        for (String path : groupClaims) {
            Object value = claim(claims, path);
            if (value instanceof String single) {
                addGroup(groups, single);
            } else if (value instanceof Collection<?> many) {
                for (Object item : many) {
                    if (item instanceof String group) {
                        addGroup(groups, group);
                    }
                }
            }
        }

        String tenant = null;
        if (!tenantClaim.isEmpty() && claim(claims, tenantClaim) instanceof String value && !value.isBlank()) {
            tenant = value.trim();
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        putString(attributes, SUBJECT, claims.get("sub"));
        putString(attributes, ISSUER, claims.get("iss"));
        Object client = claims.get("azp") != null ? claims.get("azp") : claims.get("client_id");
        putString(attributes, CLIENT, client);
        if (claims.get("act") instanceof Map<?, ?> act) {
            putString(attributes, ACTOR, act.get("sub"));
        }
        Set<String> scopes = new LinkedHashSet<>();
        addScopes(scopes, claims.get("scope"));
        addScopes(scopes, claims.get("scp"));
        if (!scopes.isEmpty()) {
            attributes.put(SCOPES, List.copyOf(scopes));
        }

        return new Caller(principal, List.copyOf(groups), tenant, true, attributes);
    }

    private static void addScopes(Set<String> scopes, Object claim) {
        if (claim instanceof String value) {
            for (String scope : value.trim().split("\\s+")) {
                if (!scope.isEmpty()) {
                    scopes.add(scope);
                }
            }
        } else if (claim instanceof Collection<?> values) {
            for (Object value : values) {
                if (value instanceof String scope) {
                    addScopes(scopes, scope);
                }
            }
        }
    }

    /** The scopes recorded on a caller by {@link #toCaller(Map)}; empty for a caller from anywhere else. */
    public static List<String> scopes(Caller caller) {
        if (caller == null || !(caller.attributes().get(SCOPES) instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static void addGroup(Set<String> groups, String group) {
        String trimmed = group.trim();
        if (!trimmed.isEmpty()) {
            groups.add(trimmed);
        }
    }

    private static void putString(Map<String, Object> attributes, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            attributes.put(key, String.valueOf(value));
        }
    }

    /**
     * The value at a claim path. A claim whose name itself contains dots is found before the path is split, so
     * names such as {@code https://example.com/groups} still work.
     */
    static Object claim(Map<String, ?> claims, String path) {
        if (claims == null || path == null || path.isBlank()) {
            return null;
        }
        if (claims.containsKey(path)) {
            return claims.get(path);
        }
        Object current = claims;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }
}
