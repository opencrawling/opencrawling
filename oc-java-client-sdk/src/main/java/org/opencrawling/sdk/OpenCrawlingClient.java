/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;

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
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(30);
        private HttpClient httpClient;
        private ObjectMapper objectMapper;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder bearerToken(String bearerToken) {
            this.bearerToken = bearerToken;
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

        public OpenCrawlingClient build() {
            HttpClient client = httpClient != null ? httpClient : HttpClient.newBuilder()
                    .connectTimeout(connectTimeout != null ? connectTimeout : Duration.ofSeconds(10))
                    .build();

            return new DefaultOpenCrawlingClient(
                    client,
                    baseUrl,
                    apiKey,
                    bearerToken,
                    readTimeout,
                    objectMapper
            );
        }
    }
}
