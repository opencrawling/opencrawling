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
package org.opencrawling.sdk.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.opencrawling.sdk.auth.TokenProvider;
import org.opencrawling.sdk.exceptions.OpenCrawlingApiException;
import org.opencrawling.sdk.exceptions.OpenCrawlingNetworkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Low-level HTTP transport client utilizing Java's HttpClient and Jackson serialization.
 */
public class HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpTransport.class);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final TokenProvider tokenProvider;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;

    public HttpTransport(
            HttpClient httpClient,
            String baseUrl,
            String apiKey,
            String bearerToken,
            Duration requestTimeout,
            ObjectMapper objectMapper) {
        this(httpClient, baseUrl, apiKey, TokenProvider.of(bearerToken), requestTimeout, objectMapper);
    }

    /**
     * @param tokenProvider supplies the bearer token for each request; on a {@code 401} the request is repeated once
     *                      if the provider could get a new token
     */
    public HttpTransport(
            HttpClient httpClient,
            String baseUrl,
            String apiKey,
            TokenProvider tokenProvider,
            Duration requestTimeout,
            ObjectMapper objectMapper) {
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder().build();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.tokenProvider = tokenProvider != null ? tokenProvider : TokenProvider.of(null);
        this.requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(30);
        if (objectMapper != null) {
            this.objectMapper = objectMapper;
        } else {
            this.objectMapper = new ObjectMapper();
            this.objectMapper.registerModule(new JavaTimeModule());
            this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }
    }

    private String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:8080";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public <T> T execute(String method, String path, Object body, Class<T> responseType) {
        String jsonBody = serializeBody(body);
        HttpResponse<String> response = sendRequest(method, path, jsonBody);
        return deserializeResponse(response.body(), responseType);
    }

    public <T> T execute(String method, String path, Object body, TypeReference<T> responseType) {
        String jsonBody = serializeBody(body);
        HttpResponse<String> response = sendRequest(method, path, jsonBody);
        return deserializeResponse(response.body(), responseType);
    }

    public void executeVoid(String method, String path, Object body) {
        String jsonBody = serializeBody(body);
        sendRequest(method, path, jsonBody);
    }

    public byte[] executeBytes(String method, String path) {
        String fullUrl = path.startsWith("http://") || path.startsWith("https://") 
                ? path 
                : baseUrl + (path.startsWith("/") ? path : "/" + path);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(requestTimeout);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-API-Key", apiKey);
        }

        switch (method.toUpperCase()) {
            case "GET" -> builder.GET();
            case "DELETE" -> builder.DELETE();
            default -> builder.method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody());
        }

        try {
            log.debug("Executing HTTP {} (binary) to {}", method, fullUrl);
            HttpResponse<byte[]> response = sendWithToken(builder, HttpResponse.BodyHandlers.ofByteArray());
            log.debug("Received HTTP {} from {}", response.statusCode(), fullUrl);

            if (response.statusCode() >= 400) {
                String errorBody = response.body() != null ? new String(response.body()) : "";
                throw new OpenCrawlingApiException(response.statusCode(), errorBody);
            }

            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new OpenCrawlingNetworkException("Network error while communicating with OpenCrawling endpoint: " + fullUrl, e);
        }
    }

    /** Sends with the current token; after a {@code 401}, once more with a new one if the provider could get it. */
    private <T> HttpResponse<T> sendWithToken(HttpRequest.Builder builder, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        HttpResponse<T> response = httpClient.send(withToken(builder).build(), handler);
        if (response.statusCode() == 401 && tokenProvider.invalidate()) {
            log.debug("Token refused; retrying once with a new one");
            response = httpClient.send(withToken(builder).build(), handler);
        }
        return response;
    }

    private HttpRequest.Builder withToken(HttpRequest.Builder builder) {
        String token = tokenProvider.accessToken();
        if (token != null && !token.isBlank()) {
            builder.setHeader("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private String serializeBody(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize request body to JSON", e);
        }
    }

    private HttpResponse<String> sendRequest(String method, String path, String jsonBody) {
        String fullUrl = path.startsWith("http://") || path.startsWith("https://") 
                ? path 
                : baseUrl + (path.startsWith("/") ? path : "/" + path);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(requestTimeout)
                .header("Accept", "application/json");

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-API-Key", apiKey);
        }

        HttpRequest.BodyPublisher publisher = jsonBody != null 
                ? HttpRequest.BodyPublishers.ofString(jsonBody) 
                : HttpRequest.BodyPublishers.noBody();

        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }

        switch (method.toUpperCase()) {
            case "GET" -> builder.GET();
            case "POST" -> builder.POST(publisher);
            case "PUT" -> builder.PUT(publisher);
            case "DELETE" -> builder.DELETE();
            default -> builder.method(method.toUpperCase(), publisher);
        }

        try {
            log.debug("Executing HTTP {} to {}", method, fullUrl);
            HttpResponse<String> response = sendWithToken(builder, HttpResponse.BodyHandlers.ofString());
            log.debug("Received HTTP {} from {}", response.statusCode(), fullUrl);

            if (response.statusCode() >= 400) {
                throw new OpenCrawlingApiException(response.statusCode(), response.body());
            }

            return response;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new OpenCrawlingNetworkException("Network error while communicating with OpenCrawling endpoint: " + fullUrl, e);
        }
    }

    private <T> T deserializeResponse(String body, Class<T> responseType) {
        if (body == null || body.isBlank() || responseType == Void.class) {
            return null;
        }
        try {
            return objectMapper.readValue(body, responseType);
        } catch (Exception e) {
            throw new OpenCrawlingNetworkException("Failed to deserialize response body to " + responseType.getSimpleName(), e);
        }
    }

    private <T> T deserializeResponse(String body, TypeReference<T> responseType) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, responseType);
        } catch (Exception e) {
            throw new OpenCrawlingNetworkException("Failed to deserialize response body", e);
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
