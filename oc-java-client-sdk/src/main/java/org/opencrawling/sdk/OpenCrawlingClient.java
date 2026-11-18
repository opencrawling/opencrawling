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
package org.opencrawling.sdk;

import org.opencrawling.sdk.auth.ClientCredentialsTokenProvider;
import org.opencrawling.sdk.auth.TokenExchangeProvider;
import org.opencrawling.sdk.auth.TokenProvider;
import org.opencrawling.sdk.ingest.OisIngestClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Main entrance client for interacting programmatically with OpenCrawling Runtime REST APIs.
 */
public interface OpenCrawlingClient extends AutoCloseable {

    /**
     * Client for managing crawler jobs.
     */
    JobClient jobs();

    /**
     * Client for managing repository, output, and transformation connectors.
     */
    ConnectorClient connectors();

    /**
     * Client for querying system status, throughput, logs, and settings.
     */
    SystemClient system();

    /**
     * Client for AIOps diagnostics, OpenTelemetry traces, error logs, and metrics.
     */
    ObservabilityClient observability();

    /**
     * Client for Auto-Narrativization Copilot template generation.
     */
    NarrativizationCopilotClient narrativization();

    /**
     * Client for post-job audit reporting, Parquet downloads, and DuckDB analytical queries.
     */
    ReportClient reports();

    /**
     * Client for OpenAPI 3.1 contract retrieval, endpoint discovery, and Swagger UI health checks.
     */
    OpenApiClient openApi();

    /**
     * Client for pre-crawl repository discovery and dry-run inspection (/api/discovery/**).
     */
    DiscoveryClient discovery();

    /**
     * Client for who this client is to the runtime and what it may do (/api/auth/me, /api/jobs/permissions).
     */
    AuthClient auth();

    /**
     * The Platform API ({@code /api/v1}): every management operation over one versioned API, with ETags,
     * idempotency keys, cursor paging and problem-detail errors. Prefer it to the other clients, which use the
     * earlier, unversioned endpoints.
     */
    org.opencrawling.sdk.v1.PlatformClient platform();

    /**
     * Client for pushing Open Ingestion Standard (OIS) documents and deletion tombstones directly into an active
     * RAG job via {@code POST /api/v1/ingest/ois/{jobId}}.
     */
    OisIngestClient ingest();

    @Override
    default void close() {
        // No-op default close for resources
    }

    /**
     * Creates a new builder instance to configure and create an OpenCrawlingClient.
     */
    static Builder builder() {
        return new Builder();
    }

    class Builder {
        private String baseUrl = "http://localhost:8080";
        private String apiKey;
        private String bearerToken;
        private TokenProvider tokenProvider;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(30);
        private HttpClient httpClient;
        private org.opencrawling.sdk.v1.RetryPolicy retryPolicy = org.opencrawling.sdk.v1.RetryPolicy.defaults();
        private ObjectMapper objectMapper;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** A fixed bearer token. For tokens that expire, use {@link #tokenProvider} or {@link #clientCredentials}. */
        public Builder bearerToken(String bearerToken) {
            this.bearerToken = bearerToken;
            return this;
        }

        /** Supplies the bearer token for each request, and a new one when the server refuses it. */
        public Builder tokenProvider(TokenProvider tokenProvider) {
            this.tokenProvider = tokenProvider;
            return this;
        }

        /**
         * Gets tokens with the OAuth 2.0 client credentials grant, for services acting as themselves. The token is
         * cached and renewed before it expires.
         *
         * @param tokenEndpoint the identity provider's token endpoint
         * @param scope         optional scopes to ask for
         */
        public Builder clientCredentials(String tokenEndpoint, String clientId, String clientSecret, String scope) {
            this.tokenProvider = new ClientCredentialsTokenProvider(tokenEndpoint, clientId, clientSecret, scope);
            return this;
        }

        /**
         * Exchanges a user's subject token for a delegated access token with the OAuth 2.0 Token Exchange grant
         * (RFC 8693, {@code urn:ietf:params:oauth:grant-type:token-exchange}), for AI agents and services acting on
         * behalf of users ({@code act.sub}).
         */
        public Builder tokenExchange(String tokenEndpoint, String clientId, String clientSecret,
                                     Supplier<String> subjectTokenSupplier, String scope) {
            this.tokenProvider = new TokenExchangeProvider(tokenEndpoint, clientId, clientSecret, subjectTokenSupplier, scope);
            return this;
        }

        /**
         * Exchanges a fixed user subject token for a delegated access token with the OAuth 2.0 Token Exchange grant
         * (RFC 8693).
         */
        public Builder tokenExchange(String tokenEndpoint, String clientId, String clientSecret,
                                     String subjectToken, String scope) {
            this.tokenProvider = new TokenExchangeProvider(tokenEndpoint, clientId, clientSecret, subjectToken, scope);
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /** When the Platform API client repeats a request; {@link org.opencrawling.sdk.v1.RetryPolicy#defaults()} unless set. */
        public Builder retryPolicy(org.opencrawling.sdk.v1.RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public OpenCrawlingClient build() {
            HttpClient client = httpClient != null ? httpClient : HttpClient.newBuilder()
                    .connectTimeout(connectTimeout != null ? connectTimeout : Duration.ofSeconds(10))
                    .build();

            if (tokenProvider != null && bearerToken != null && !bearerToken.isBlank()) {
                throw new IllegalStateException("Set either a bearer token or a token provider, not both.");
            }
            return new DefaultOpenCrawlingClient(
                    client,
                    baseUrl,
                    apiKey,
                    tokenProvider != null ? tokenProvider : TokenProvider.of(bearerToken),
                    readTimeout,
                    objectMapper,
                    retryPolicy
            );
        }
    }
}
