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
package org.opencrawling.stormcrawler.bolt.dispatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP REST implementation of PayloadDispatcher for transmitting OIS document
 * and deletion tombstone payloads directly to OpenCrawling runtime endpoints.
 */
public class HttpPayloadDispatcher implements PayloadDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HttpPayloadDispatcher.class);

    public static final String CONF_ENDPOINT = "opencrawling.target.endpoint";
    public static final String CONF_TIMEOUT_MS = "opencrawling.http.timeout.ms";
    public static final String CONF_AUTH_HEADER = "opencrawling.http.auth.header";

    private String endpoint = "http://localhost:8080/api/v1/ingest/ois";
    private int timeoutMs = 5000;
    private String authHeader = null;
    private transient HttpClient httpClient;

    public HttpPayloadDispatcher() {
    }

    public HttpPayloadDispatcher(String endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public void init(Map<String, Object> conf) {
        if (conf != null) {
            Object ep = conf.get(CONF_ENDPOINT);
            if (ep != null) {
                this.endpoint = ep.toString();
            }
            Object timeout = conf.get(CONF_TIMEOUT_MS);
            if (timeout != null) {
                this.timeoutMs = Integer.parseInt(timeout.toString());
            }
            Object auth = conf.get(CONF_AUTH_HEADER);
            if (auth != null) {
                this.authHeader = auth.toString();
            }
        }

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        log.info("Initialized HttpPayloadDispatcher with target endpoint: {}", endpoint);
    }

    @Override
    public void dispatch(String documentId, String action, String jsonPayload) throws Exception {
        if (httpClient == null) {
            init(Map.of());
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("X-OIS-Action", action)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

        if (authHeader != null && !authHeader.isBlank()) {
            builder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to dispatch OIS payload for document " + documentId +
                    " (action=" + action + "). HTTP status: " + response.statusCode() + ", body: " + response.body());
        }

        log.debug("Successfully dispatched OIS payload for document {} (action={}, HTTP {})",
                documentId, action, response.statusCode());
    }

    public String getEndpoint() {
        return endpoint;
    }

    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public void close() {
        this.httpClient = null;
    }
}
