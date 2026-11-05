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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How the runtime establishes who is calling ({@code opencrawling.security.identity.*}).
 *
 * <p>{@code mode=none} (the default) keeps today's behaviour: MCP tools take the caller's identity from their
 * {@code userPrincipal}/{@code userRoles} arguments. {@code mode=jwt} makes the MCP endpoints OAuth2 resource
 * server endpoints: each request needs a valid bearer token, and the tools take the identity from that token and
 * ignore the arguments. Token validation itself is configured with Spring Boot's standard
 * {@code spring.security.oauth2.resourceserver.jwt.*} properties (issuer, JWK set, audiences).
 */
@ConfigurationProperties(prefix = IdentityProperties.PREFIX)
public class IdentityProperties {

    public static final String PREFIX = "opencrawling.security.identity";

    public enum Mode {
        NONE, JWT;

        static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Unknown " + PREFIX + ".mode '" + value + "': use none or jwt.", e);
            }
        }
    }

    private Mode mode = Mode.NONE;

    /**
     * In {@code jwt} mode, also require a token on the REST API ({@code /api/**}). Off by default because the admin
     * UI cannot send tokens yet. The OIS ingest endpoint keeps its own token either way.
     */
    private boolean protectApi = false;

    private final Jwt jwt = new Jwt();

    public Mode getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = Mode.parse(mode);
    }

    public boolean isJwt() {
        return mode == Mode.JWT;
    }

    public boolean isProtectApi() {
        return protectApi;
    }

    public void setProtectApi(boolean protectApi) {
        this.protectApi = protectApi;
    }

    public Jwt getJwt() {
        return jwt;
    }

    /**
     * Which token claims describe the caller. A path may name a nested claim with dots, such as
     * {@code realm_access.roles}.
     */
    public static class Jwt {

        /** Claims tried in order for the caller's user identity; the first non-blank string wins. */
        private List<String> principalClaims = new ArrayList<>(List.of("preferred_username", "sub"));

        /** Claims whose values are the caller's groups or roles; values of all of them are combined. */
        private List<String> groupClaims = new ArrayList<>(List.of("realm_access.roles", "groups"));

        /** Claim holding the caller's tenant, or empty for a single-tenant deployment. */
        private String tenantClaim = "";

        public List<String> getPrincipalClaims() {
            return principalClaims;
        }

        public void setPrincipalClaims(List<String> principalClaims) {
            this.principalClaims = principalClaims == null ? new ArrayList<>() : new ArrayList<>(principalClaims);
        }

        public List<String> getGroupClaims() {
            return groupClaims;
        }

        public void setGroupClaims(List<String> groupClaims) {
            this.groupClaims = groupClaims == null ? new ArrayList<>() : new ArrayList<>(groupClaims);
        }

        public String getTenantClaim() {
            return tenantClaim;
        }

        public void setTenantClaim(String tenantClaim) {
            this.tenantClaim = tenantClaim == null ? "" : tenantClaim;
        }
    }
}
