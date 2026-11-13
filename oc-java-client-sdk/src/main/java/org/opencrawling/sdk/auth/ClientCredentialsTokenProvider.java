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
package org.opencrawling.sdk.auth;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Gets tokens with the OAuth 2.0 client credentials grant (RFC 6749, section 4.4), for services and automation that
 * act as themselves. The token is cached and renewed shortly before it expires, or when the server refuses it.
 */
public class ClientCredentialsTokenProvider implements TokenProvider {

    private final OAuth2Client oauth;
    private final String tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final String audience;
    private final Clock clock;

    private ExpiringToken current;

    /**
     * @param tokenEndpoint the identity provider's token endpoint
     * @param scope         optional scopes to ask for
     * @param audience      optional {@code audience} parameter, for providers that use it (Auth0); others ignore it
     */
    public ClientCredentialsTokenProvider(OAuth2Client oauth, String tokenEndpoint, String clientId, String clientSecret,
                                          String scope, String audience, Clock clock) {
        this.oauth = Objects.requireNonNull(oauth, "oauth");
        this.tokenEndpoint = requireText(tokenEndpoint, "tokenEndpoint");
        this.clientId = requireText(clientId, "clientId");
        this.clientSecret = requireText(clientSecret, "clientSecret");
        this.scope = scope;
        this.audience = audience;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public ClientCredentialsTokenProvider(String tokenEndpoint, String clientId, String clientSecret, String scope) {
        this(new OAuth2Client(), tokenEndpoint, clientId, clientSecret, scope, null, null);
    }

    @Override
    public synchronized String accessToken() {
        if (current == null || !current.usable(clock)) {
            Map<String, String> grant = new LinkedHashMap<>();
            grant.put("grant_type", "client_credentials");
            if (scope != null && !scope.isBlank()) {
                grant.put("scope", scope);
            }
            if (audience != null && !audience.isBlank()) {
                grant.put("audience", audience);
            }
            OAuth2Client.TokenResponse response = oauth.token(tokenEndpoint, clientId, clientSecret, grant);
            current = ExpiringToken.of(response.accessToken(), response.expiresIn(), clock);
        }
        return current.value();
    }

    @Override
    public synchronized boolean invalidate() {
        current = null;
        return true;
    }

    @Override
    public String toString() {
        return "ClientCredentialsTokenProvider[client=" + clientId + ", tokenEndpoint=" + tokenEndpoint + "]";
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
