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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencrawling.core.security.Caller;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What the admin UI needs to sign users in and to show what they may do.
 *
 * <p>{@code GET /api/auth/config} is public (it is outside the JWT filter chain): the UI calls it before it has a
 * token. It only names the issuer and the UI's public client, which the browser sees during sign-in anyway.
 * {@code GET /api/auth/me} describes the verified caller of the request.
 */
@RestController
@RequestMapping(AuthController.PATH)
@Tag(name = "Authentication", description = "Sign-in settings for the admin UI and the current caller")
public class AuthController {

    public static final String PATH = "/api/auth";
    public static final String CONFIG_PATH = PATH + "/config";

    static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    private final IdentityProperties identity;
    private final ManagementAuthorization authorization;
    private final Environment environment;

    public AuthController(IdentityProperties identity, ManagementAuthorization authorization, Environment environment) {
        this.identity = identity;
        this.authorization = authorization;
        this.environment = environment;
    }

    /**
     * How the UI signs in. {@code oidc} is null when it should not: identity mode {@code none}, or no
     * {@code admin-ui.client-id}, or no issuer to sign in with.
     */
    public record AuthConfig(String mode, boolean protectApi, Oidc oidc) {}

    public record Oidc(String issuer, String clientId, String scope) {}

    /**
     * The current caller. {@code authenticated} is false without a verified token. {@code admin} and
     * {@code canCreateJobs} are true for everyone when management authorization is off.
     */
    public record Me(boolean authenticated, String principal, List<String> groups, String tenant,
                     boolean managementAuthorization, boolean admin, boolean canCreateJobs) {}

    @Operation(summary = "Admin UI sign-in settings", description = "Public. The issuer and public client the admin UI signs in with, if any.")
    @GetMapping("/config")
    public AuthConfig config() {
        return new AuthConfig(identity.getMode().name().toLowerCase(Locale.ROOT), identity.isProtectApi(), oidc());
    }

    @Operation(summary = "The current caller", description = "Who the bearer token names, and what they may do on the management plane.")
    @GetMapping("/me")
    public Me me() {
        Optional<Caller> caller = authorization.currentCaller();
        return new Me(caller.isPresent(),
                caller.map(Caller::principal).orElse(null),
                caller.map(Caller::groups).orElse(List.of()),
                caller.map(Caller::tenant).orElse(null),
                authorization.isEnabled(),
                authorization.isAdmin(),
                authorization.canCreateJobs());
    }

    private Oidc oidc() {
        IdentityProperties.AdminUi ui = identity.getAdminUi();
        if (!identity.isJwt() || ui.getClientId().isBlank()) {
            return null;
        }
        String issuer = !ui.getIssuer().isBlank() ? ui.getIssuer() : environment.getProperty(ISSUER_PROPERTY, "").trim();
        if (issuer.isBlank()) {
            return null;
        }
        return new Oidc(issuer, ui.getClientId(), ui.getScope());
    }
}
