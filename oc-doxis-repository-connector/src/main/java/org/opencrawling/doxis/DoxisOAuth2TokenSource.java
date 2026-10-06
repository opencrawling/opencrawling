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
package org.opencrawling.doxis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * OAuth2 client-credentials token source for {@code auth-type: oauth2}: requests an access token from the identity
 * provider's token endpoint ({@code grant_type=client_credentials}) and caches it until shortly before it expires. The CSB
 * then opens the session with {@code POST /loginOIDCWithAccessToken}; the CSB customer must be configured for that identity
 * provider. Tokens and the client secret are never logged.
 */
public final class DoxisOAuth2TokenSource implements Supplier<String> {

    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private final URI tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock;
    private String token;
    private Instant expiresAt = Instant.MIN;

    public DoxisOAuth2TokenSource(String tokenUrl, String clientId, String clientSecret, String scope, Duration timeout) {
        this(tokenUrl, clientId, clientSecret, scope, timeout,
                HttpClient.newBuilder().connectTimeout(timeout).build(), Clock.systemUTC());
    }

    DoxisOAuth2TokenSource(String tokenUrl, String clientId, String clientSecret, String scope, Duration timeout,
                           HttpClient httpClient, Clock clock) {
        this.tokenUrl = URI.create(tokenUrl);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    @Override
    public synchronized String get() {
        if (token != null && clock.instant().isBefore(expiresAt)) {
            return token;
        }
        StringBuilder form = new StringBuilder("grant_type=client_credentials")
                .append("&client_id=").append(enc(clientId))
                .append("&client_secret=").append(enc(clientSecret));
        if (scope != null && !scope.isBlank()) {
            form.append("&scope=").append(enc(scope));
        }
        HttpRequest request = HttpRequest.newBuilder(tokenUrl)
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OAuth2 token endpoint " + tokenUrl + " answered HTTP " + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            String accessToken = body.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("OAuth2 token endpoint " + tokenUrl + " returned no access_token");
            }
            long expiresIn = body.path("expires_in").asLong(300);
            token = accessToken;
            expiresAt = clock.instant().plusSeconds(expiresIn).minus(EXPIRY_MARGIN);
            return token;
        } catch (IOException e) {
            throw new UncheckedIOException("OAuth2 token request to " + tokenUrl + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OAuth2 token request interrupted", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
