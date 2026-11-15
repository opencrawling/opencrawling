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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Uses a signed-in user's access token and renews it with the refresh token (RFC 6749, section 6), as the CLI does
 * after {@code oc auth login}. Every renewal is handed to a listener so the caller can save it; a provider that
 * rotates refresh tokens returns a new one each time.
 */
public class RefreshTokenProvider implements TokenProvider {

    /** What a sign-in yields and what is saved between runs. */
    public record Tokens(String accessToken, String refreshToken, Instant expiresAt) {}

    private final OAuth2Client oauth;
    private final String tokenEndpoint;
    private final String clientId;
    private final Consumer<Tokens> onRenewed;
    private final Clock clock;

    private Tokens tokens;
    private ExpiringToken access;

    public RefreshTokenProvider(OAuth2Client oauth, String tokenEndpoint, String clientId, Tokens tokens,
                                Consumer<Tokens> onRenewed, Clock clock) {
        this.oauth = Objects.requireNonNull(oauth, "oauth");
        this.tokenEndpoint = ClientCredentialsTokenProvider.requireText(tokenEndpoint, "tokenEndpoint");
        this.clientId = ClientCredentialsTokenProvider.requireText(clientId, "clientId");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.access = tokens.expiresAt() == null ? null : ExpiringToken.saved(tokens.accessToken(), tokens.expiresAt());
        this.onRenewed = onRenewed != null ? onRenewed : renewed -> {};
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public synchronized String accessToken() {
        if (access == null || !access.usable(clock)) {
            renew();
        }
        return access.value();
    }

    @Override
    public synchronized boolean invalidate() {
        if (tokens.refreshToken() == null) {
            return false;
        }
        access = null;
        return true;
    }

    private void renew() {
        if (tokens.refreshToken() == null) {
            throw new OAuth2Exception("login_required", "The sign-in has expired; sign in again.");
        }
        Map<String, String> grant = new LinkedHashMap<>();
        grant.put("grant_type", "refresh_token");
        grant.put("refresh_token", tokens.refreshToken());
        OAuth2Client.TokenResponse response;
        try {
            response = oauth.token(tokenEndpoint, clientId, null, grant);
        } catch (OAuth2Exception e) {
            if ("invalid_grant".equals(e.error())) {
                throw new OAuth2Exception("login_required", "The sign-in has expired or was revoked; sign in again.");
            }
            throw e;
        }
        access = ExpiringToken.of(response.accessToken(), response.expiresIn(), clock);
        String refresh = response.refreshToken() != null ? response.refreshToken() : tokens.refreshToken();
        tokens = new Tokens(access.value(), refresh, access.expiresAt());
        onRenewed.accept(tokens);
    }

    @Override
    public String toString() {
        return "RefreshTokenProvider[client=" + clientId + ", tokenEndpoint=" + tokenEndpoint + "]";
    }
}
