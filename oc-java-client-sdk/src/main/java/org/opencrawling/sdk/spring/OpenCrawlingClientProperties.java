/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.sdk.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Spring Boot Configuration Properties for OpenCrawling Java Client SDK.
 */
@ConfigurationProperties(prefix = "opencrawling.client")
public class OpenCrawlingClientProperties {

    /**
     * OpenCrawling REST API base URL endpoint (e.g. http://localhost:8080).
     */
    private String baseUrl = "http://localhost:8080";

    /**
     * Optional API Key header (X-API-Key).
     */
    private String apiKey;

    /**
     * Optional OAuth2 / JWT Bearer token header.
     */
    private String bearerToken;

    /**
     * HTTP connection establishment timeout duration.
     */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * HTTP request socket read timeout duration.
     */
    private Duration readTimeout = Duration.ofSeconds(30);

    /**
     * OAuth 2.0 client credentials: when {@code client-id} is set, the client gets and renews its own tokens instead
     * of using {@code bearer-token}.
     */
    private final OAuth2 oauth2 = new OAuth2();

    /**
     * Retry policy for repeatable Platform API requests ({@code opencrawling.client.retry.*}).
     */
    private final Retry retry = new Retry();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public OAuth2 getOauth2() {
        return oauth2;
    }

    public Retry getRetry() {
        return retry;
    }

    /** OAuth 2.0 client credentials ({@code opencrawling.client.oauth2.*}). */
    public static class OAuth2 {

        /** The identity provider's token endpoint. */
        private String tokenUri;

        /** The client's id. Blank: no client credentials. */
        private String clientId;

        /** The client's secret. Take it from the environment or a secret store, never from a committed file. */
        private String clientSecret;

        /** Optional scopes to ask for. */
        private String scope;

        /** Optional audience parameter for providers that use it. */
        private String audience;

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public String getTokenEndpoint() {
            return tokenUri;
        }

        public void setTokenEndpoint(String tokenEndpoint) {
            this.tokenUri = tokenEndpoint;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank();
        }
    }

    /** Retry policy settings ({@code opencrawling.client.retry.*}). */
    public static class Retry {

        private int maxAttempts = 3;
        private Duration baseDelay = Duration.ofMillis(200);
        private Duration maxDelay = Duration.ofSeconds(10);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getBaseDelay() {
            return baseDelay;
        }

        public void setBaseDelay(Duration baseDelay) {
            this.baseDelay = baseDelay;
        }

        public Duration getInitialBackoff() {
            return baseDelay;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.baseDelay = initialBackoff;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        public Duration getMaxBackoff() {
            return maxDelay;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxDelay = maxBackoff;
        }

        public org.opencrawling.sdk.v1.RetryPolicy toRetryPolicy() {
            return new org.opencrawling.sdk.v1.RetryPolicy(maxAttempts, baseDelay, maxDelay);
        }
    }
}
