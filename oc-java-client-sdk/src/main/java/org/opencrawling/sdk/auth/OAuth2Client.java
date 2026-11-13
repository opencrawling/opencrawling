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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The few OAuth 2.0 / OpenID Connect calls the client needs: discovery, the token endpoint, device authorization
 * (RFC 8628) and revocation (RFC 7009). Plain {@link HttpClient}, no extra dependencies.
 */
public class OAuth2Client {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final Duration timeout;

    public OAuth2Client(HttpClient httpClient, Duration timeout) {
        this.httpClient = httpClient != null ? httpClient : HttpClient.newHttpClient();
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
    }

    public OAuth2Client() {
        this(null, null);
    }

    /** The endpoints an issuer publishes; {@code deviceAuthorizationEndpoint} and {@code revocationEndpoint} may be null. */
    public record Endpoints(String issuer, String tokenEndpoint, String deviceAuthorizationEndpoint,
                            String revocationEndpoint) {}

    /** A successful token response. {@code expiresIn} is in seconds, or 0 when the provider did not say. */
    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, String scope) {}

    /** A started device authorization (RFC 8628, section 3.2). */
    public record DeviceAuthorization(String deviceCode, String userCode, String verificationUri,
                                      String verificationUriComplete, long expiresIn, long interval) {}

    /**
     * Reads {@code <issuer>/.well-known/openid-configuration}. The document must name the same issuer (OpenID Connect
     * Discovery, section 4.3), so a misconfigured or spoofed URL is not trusted.
     */
    public Endpoints discover(String issuer) {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        JsonNode doc = getJson(base + "/.well-known/openid-configuration");
        String declared = text(doc, "issuer");
        if (declared == null || !stripSlash(declared).equals(base)) {
            throw new OAuth2Exception(null, "The discovery document at " + base + " names another issuer: " + declared);
        }
        String tokenEndpoint = text(doc, "token_endpoint");
        if (tokenEndpoint == null) {
            throw new OAuth2Exception(null, "The issuer " + base + " publishes no token endpoint.");
        }
        return new Endpoints(declared, tokenEndpoint, text(doc, "device_authorization_endpoint"),
                text(doc, "revocation_endpoint"));
    }

    /**
     * Posts a grant to the token endpoint. With a {@code clientSecret} the client authenticates with HTTP Basic
     * ({@code client_secret_basic}, RFC 6749 section 2.3.1); without one, as a public client by {@code client_id}.
     */
    public TokenResponse token(String tokenEndpoint, String clientId, String clientSecret, Map<String, String> grant) {
        Map<String, String> form = new LinkedHashMap<>(grant);
        HttpRequest.Builder request = formRequest(tokenEndpoint, clientId, clientSecret, form);
        HttpResponse<String> response = send(request.build(), tokenEndpoint);
        JsonNode body = parse(response.body());
        if (response.statusCode() >= 400 || body.hasNonNull("error")) {
            throw error(body, response.statusCode(), tokenEndpoint);
        }
        String accessToken = text(body, "access_token");
        if (accessToken == null) {
            throw new OAuth2Exception(null, "The token endpoint " + tokenEndpoint + " returned no access token.");
        }
        return new TokenResponse(accessToken, text(body, "refresh_token"),
                body.path("expires_in").asLong(0), text(body, "scope"));
    }

    /** Starts the device authorization grant for a public client. */
    public DeviceAuthorization startDeviceAuthorization(String endpoint, String clientId, String scope) {
        Map<String, String> form = new LinkedHashMap<>();
        if (scope != null && !scope.isBlank()) {
            form.put("scope", scope);
        }
        HttpResponse<String> response = send(formRequest(endpoint, clientId, null, form).build(), endpoint);
        JsonNode body = parse(response.body());
        if (response.statusCode() >= 400 || body.hasNonNull("error")) {
            throw error(body, response.statusCode(), endpoint);
        }
        return new DeviceAuthorization(text(body, "device_code"), text(body, "user_code"),
                text(body, "verification_uri"), text(body, "verification_uri_complete"),
                body.path("expires_in").asLong(600), Math.max(1, body.path("interval").asLong(5)));
    }

    /** Revokes a token (RFC 7009). Best effort: the caller decides what a failure means. */
    public void revoke(String endpoint, String clientId, String token, String tokenTypeHint) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", token);
        if (tokenTypeHint != null) {
            form.put("token_type_hint", tokenTypeHint);
        }
        HttpResponse<String> response = send(formRequest(endpoint, clientId, null, form).build(), endpoint);
        if (response.statusCode() >= 400) {
            throw error(parse(response.body()), response.statusCode(), endpoint);
        }
    }

    private HttpRequest.Builder formRequest(String endpoint, String clientId, String clientSecret, Map<String, String> form) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        if (clientSecret != null && !clientSecret.isEmpty()) {
            String credentials = encode(clientId) + ":" + encode(clientSecret);
            request.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        } else {
            form.put("client_id", clientId);
        }
        StringJoiner body = new StringJoiner("&");
        form.forEach((key, value) -> body.add(encode(key) + "=" + encode(value)));
        return request.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    }

    private JsonNode getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response = send(request, url);
        if (response.statusCode() >= 400) {
            throw new OAuth2Exception(null, "GET " + url + " answered HTTP " + response.statusCode() + ".");
        }
        return parse(response.body());
    }

    private HttpResponse<String> send(HttpRequest request, String url) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new OAuth2Exception("Could not reach the identity provider at " + url + ".", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuth2Exception("Interrupted while calling the identity provider at " + url + ".", e);
        }
    }

    private static OAuth2Exception error(JsonNode body, int status, String url) {
        String error = text(body, "error");
        String description = text(body, "error_description");
        // Never echo the response body: it may contain tokens.
        return new OAuth2Exception(error, "The identity provider at " + url + " refused the request (HTTP " + status
                + (error != null ? ", " + error : "") + (description != null ? ": " + description : "") + ").");
    }

    private static JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
