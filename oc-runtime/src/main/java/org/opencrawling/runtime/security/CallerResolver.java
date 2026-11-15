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
import org.opencrawling.runtime.mcp.McpAclEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Objects;
import java.util.Optional;

/**
 * Decides who an MCP tool call is made for.
 *
 * <p>In {@code none} mode the identity is the one the client asserts in the tool arguments, as before. In
 * {@code jwt} mode it comes only from the request's validated bearer token and the arguments are ignored; a call
 * that reaches a tool without a validated token is refused, never answered with the asserted identity.
 */
public final class CallerResolver {

    private static final Logger log = LoggerFactory.getLogger(CallerResolver.class);

    private static final CallerResolver ASSERTED = new CallerResolver(false, null);

    private final boolean verified;
    private final JwtCallerMapper mapper;

    private CallerResolver(boolean verified, JwtCallerMapper mapper) {
        this.verified = verified;
        this.mapper = mapper;
    }

    /** Takes the identity from the tool arguments ({@code none} mode). */
    public static CallerResolver asserted() {
        return ASSERTED;
    }

    /** Takes the identity from the validated token of the current request ({@code jwt} mode). */
    public static CallerResolver verified(JwtCallerMapper mapper) {
        return new CallerResolver(true, Objects.requireNonNull(mapper, "mapper"));
    }

    public boolean isVerified() {
        return verified;
    }

    /**
     * The current request's verified caller, if there is one: in {@code jwt} mode, the caller of a request that
     * carries a validated token. Empty in {@code none} mode and for requests without one (for example API requests
     * when {@code protect-api} is off). Never an asserted identity.
     */
    public Optional<Caller> verifiedCaller() {
        if (!verified) {
            return Optional.empty();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            return Optional.empty();
        }
        return Optional.of(mapper.toCaller(token.getToken().getClaims()));
    }

    /**
     * The caller for a tool call.
     *
     * @param assertedPrincipal the {@code userPrincipal} tool argument
     * @param assertedRolesCsv  the {@code userRoles} tool argument (comma-separated)
     * @throws VerifiedCallerRequiredException in {@code jwt} mode when the request carries no validated token
     */
    public Caller resolve(String assertedPrincipal, String assertedRolesCsv) {
        if (!verified) {
            return Caller.asserted(assertedPrincipal, McpAclEvaluator.parseRoles(assertedRolesCsv));
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new VerifiedCallerRequiredException();
        }
        Caller caller = mapper.toCaller(token.getToken().getClaims());
        if (log.isDebugEnabled() && (assertedPrincipal != null || assertedRolesCsv != null)) {
            log.debug("Ignoring the asserted identity in a tool call; using the token's principal '{}'.", caller.principal());
        }
        return caller;
    }
}
