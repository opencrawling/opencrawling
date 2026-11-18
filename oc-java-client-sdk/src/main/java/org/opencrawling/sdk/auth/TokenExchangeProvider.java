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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static org.opencrawling.sdk.auth.ClientCredentialsTokenProvider.requireText;

/**
 * Exchanges a caller's subject token for a delegated access token with the OAuth 2.0 Token Exchange grant
 * (RFC 8693, {@code urn:ietf:params:oauth:grant-type:token-exchange}), enabling AI agents and middle-tier services to
 * act on behalf of end users ({@code act.sub}).
 *
 * <p>The exchanged token is cached for the current subject/actor token pair and renewed shortly before it expires, when
 * the supplied subject token changes, or when the server refuses it ({@code 401}).
 */
public class TokenExchangeProvider implements TokenProvider {

    public static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    public static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    private final OAuth2Client oauth;
    private final String tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final Supplier<String> subjectTokenSupplier;
    private final String subjectTokenType;
    private final Supplier<String> actorTokenSupplier;
    private final String actorTokenType;
    private final String scope;
    private final String audience;
    private final Clock clock;

    private ExpiringToken current;
    private String currentInputDigest;

    public TokenExchangeProvider(
            OAuth2Client oauth,
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            Supplier<String> subjectTokenSupplier,
            String subjectTokenType,
            Supplier<String> actorTokenSupplier,
            String actorTokenType,
            String scope,
            String audience,
            Clock clock) {
        this.oauth = Objects.requireNonNull(oauth, "oauth");
        this.tokenEndpoint = requireText(tokenEndpoint, "tokenEndpoint");
        this.clientId = requireText(clientId, "clientId");
        this.clientSecret = clientSecret;
        this.subjectTokenSupplier = Objects.requireNonNull(subjectTokenSupplier, "subjectTokenSupplier");
        this.subjectTokenType = (subjectTokenType == null || subjectTokenType.isBlank())
                ? ACCESS_TOKEN_TYPE
                : subjectTokenType.trim();
        this.actorTokenSupplier = actorTokenSupplier;
        this.actorTokenType = (actorTokenType == null || actorTokenType.isBlank())
                ? ACCESS_TOKEN_TYPE
                : actorTokenType.trim();
        this.scope = scope;
        this.audience = audience;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public TokenExchangeProvider(
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            Supplier<String> subjectTokenSupplier,
            String scope) {
        this(new OAuth2Client(), tokenEndpoint, clientId, clientSecret, subjectTokenSupplier, ACCESS_TOKEN_TYPE,
                null, null, scope, null, null);
    }

    public TokenExchangeProvider(
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            String subjectToken,
            String scope) {
        this(tokenEndpoint, clientId, clientSecret, () -> requireText(subjectToken, "subjectToken"), scope);
    }

    @Override
    public synchronized String accessToken() {
        String subjectToken = requireText(subjectTokenSupplier.get(), "subjectToken");
        String actorToken = actorTokenSupplier != null ? actorTokenSupplier.get() : null;
        if (actorToken != null && actorToken.isBlank()) {
            actorToken = null;
        }
        String digest = digest(subjectToken, actorToken);
        if (current == null || !digest.equals(currentInputDigest) || !current.usable(clock)) {
            Map<String, String> grant = new LinkedHashMap<>();
            grant.put("grant_type", GRANT_TYPE);
            grant.put("subject_token", subjectToken);
            grant.put("subject_token_type", subjectTokenType);
            if (actorToken != null) {
                grant.put("actor_token", actorToken.trim());
                grant.put("actor_token_type", actorTokenType);
            }
            if (scope != null && !scope.isBlank()) {
                grant.put("scope", scope);
            }
            if (audience != null && !audience.isBlank()) {
                grant.put("audience", audience);
            }
            OAuth2Client.TokenResponse response = oauth.token(tokenEndpoint, clientId, clientSecret, grant);
            current = ExpiringToken.of(response.accessToken(), response.expiresIn(), clock);
            currentInputDigest = digest;
        }
        return current.value();
    }

    @Override
    public synchronized boolean invalidate() {
        current = null;
        currentInputDigest = null;
        return true;
    }

    @Override
    public String toString() {
        return "TokenExchangeProvider[client=" + clientId + ", tokenEndpoint=" + tokenEndpoint + "]";
    }

    private static String digest(String subjectToken, String actorToken) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(subjectToken.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) 0);
            if (actorToken != null) {
                sha256.update(actorToken.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
