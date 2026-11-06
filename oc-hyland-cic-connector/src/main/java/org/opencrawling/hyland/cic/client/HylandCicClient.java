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
package org.opencrawling.hyland.cic.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.opencrawling.hyland.cic.config.HylandCicProperties;
import org.opencrawling.hyland.cic.model.HylandCicDocument;
import org.opencrawling.hyland.cic.model.HylandCicFolder;
import org.opencrawling.hyland.cic.model.HylandCicPagedResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST client for the Hyland Content Innovation Cloud (HXP) microservices and IdP OAuth2 token exchange.
 */
public class HylandCicClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HylandCicClient.class);
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private final HylandCicProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private String cachedToken;
    private Instant tokenExpiresAt = Instant.MIN;

    public HylandCicClient(HylandCicProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectionTimeoutSeconds()))
                .build(), Clock.systemUTC());
    }

    public HylandCicClient(HylandCicProperties properties, HttpClient httpClient, Clock clock) {
        this.properties = properties != null ? properties : new HylandCicProperties();
        this.httpClient = httpClient;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public HylandCicProperties getProperties() {
        return properties;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Checks service health and endpoint connectivity.
     */
    public boolean checkHealth() {
        try {
            String url = normalizedUrl(properties.getBaseUrl()) + "/api/content/health";
            HttpRequest request = newRequestBuilder(url)
                    .GET()
                    .build();
            HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            log.warn("Hyland CIC health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Retrieves folder metadata by ID or path.
     */
    public Optional<HylandCicFolder> findFolder(String folderIdOrPath) {
        if (folderIdOrPath == null || folderIdOrPath.isBlank()) {
            return Optional.empty();
        }
        String ref = folderIdOrPath.strip();
        String url;
        if (ref.startsWith("/") || ref.contains("/")) {
            url = normalizedUrl(properties.getBaseUrl()) + "/api/content/folders?path="
                    + URLEncoder.encode(ref, StandardCharsets.UTF_8);
        } else {
            url = normalizedUrl(properties.getBaseUrl()) + "/api/content/folders/" + ref;
        }

        try {
            HttpRequest request = newRequestBuilder(url)
                    .GET()
                    .build();
            HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HylandCicException("Failed to get folder " + ref + " (HTTP " + response.statusCode() + ")", response.statusCode());
            }
            return Optional.of(objectMapper.readValue(response.body(), HylandCicFolder.class));
        } catch (HylandCicException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            throw e;
        } catch (IOException e) {
            throw new HylandCicException("IO error reading folder " + ref + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HylandCicException("Folder retrieval interrupted: " + ref, e);
        }
    }

    /**
     * Lists child items of a folder with pagination.
     */
    public HylandCicPagedResult getFolderItems(String folderId, int offset, int limit) {
        String url = normalizedUrl(properties.getBaseUrl()) + "/api/content/folders/" + folderId
                + "/items?offset=" + Math.max(0, offset) + "&limit=" + (limit > 0 ? limit : properties.getBatchSize());
        try {
            HttpRequest request = newRequestBuilder(url)
                    .GET()
                    .build();
            HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new HylandCicException("Folder not found: " + folderId, 404);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HylandCicException("Failed to list folder items for " + folderId
                        + " (HTTP " + response.statusCode() + ")", response.statusCode());
            }
            return objectMapper.readValue(response.body(), HylandCicPagedResult.class);
        } catch (HylandCicException e) {
            throw e;
        } catch (IOException e) {
            throw new HylandCicException("IO error listing folder items for " + folderId + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HylandCicException("Listing folder items interrupted: " + folderId, e);
        }
    }

    /**
     * Executes a search query in Hyland CIC.
     */
    public HylandCicPagedResult query(String queryString, int offset, int limit) {
        String url = normalizedUrl(properties.getBaseUrl()) + "/api/content/query";
        Map<String, Object> queryPayload = Map.of(
                "query", queryString != null ? queryString : "",
                "offset", Math.max(0, offset),
                "limit", limit > 0 ? limit : properties.getBatchSize()
        );

        try {
            String jsonBody = objectMapper.writeValueAsString(queryPayload);
            HttpRequest request = newRequestBuilder(url)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HylandCicException("Query execution failed (HTTP " + response.statusCode() + "): "
                        + queryString, response.statusCode());
            }
            return objectMapper.readValue(response.body(), HylandCicPagedResult.class);
        } catch (HylandCicException e) {
            throw e;
        } catch (IOException e) {
            throw new HylandCicException("IO error during query execution: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HylandCicException("Query execution interrupted", e);
        }
    }

    /**
     * Retrieves document metadata by ID.
     */
    public Optional<HylandCicDocument> findDocument(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return Optional.empty();
        }
        String id = documentId.strip();
        String url = normalizedUrl(properties.getBaseUrl()) + "/api/content/documents/" + id;
        try {
            HttpRequest request = newRequestBuilder(url)
                    .GET()
                    .build();
            HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HylandCicException("Failed to get document " + id + " (HTTP " + response.statusCode() + ")", response.statusCode());
            }
            return Optional.of(objectMapper.readValue(response.body(), HylandCicDocument.class));
        } catch (HylandCicException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            throw e;
        } catch (IOException e) {
            throw new HylandCicException("IO error reading document " + id + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HylandCicException("Document retrieval interrupted: " + id, e);
        }
    }

    /**
     * Downloads document binary content stream.
     */
    public InputStream downloadContent(String documentId) {
        String id = documentId.strip();
        String url = normalizedUrl(properties.getBaseUrl()) + "/api/content/documents/" + id + "/content";
        try {
            HttpRequest request = newRequestBuilder(url)
                    .header("Accept", "*/*")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 404) {
                throw new HylandCicException("Document content not found: " + id, 404);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HylandCicException("Failed to download document content for " + id
                        + " (HTTP " + response.statusCode() + ")", response.statusCode());
            }
            return response.body();
        } catch (HylandCicException e) {
            throw e;
        } catch (IOException e) {
            throw new HylandCicException("IO error downloading document " + id + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HylandCicException("Document download interrupted: " + id, e);
        }
    }

    /**
     * Creates or updates a document in Hyland CIC (Output connector operation).
     */
    public HylandCicDocument createDocument(String folderId, String name, String contentType,
                                           byte[] contentBytes, String mimeType,
                                           Map<String, Object> customFields) {
        String url = normalizedUrl(properties.getBaseUrl()) + "/api/content/documents";
        String boundary = "----HXPBoundary" + UUID.randomUUID().toString().replace("-", "");

        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            // Metadata Part
            byteStream.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            byteStream.write("Content-Disposition: form-data; name=\"metadata\"\r\n".getBytes(StandardCharsets.UTF_8));
            byteStream.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".getBytes(StandardCharsets.UTF_8));

            Map<String, Object> metadata = Map.of(
                    "folderId", folderId != null ? folderId : "",
                    "name", name != null ? name : "Untitled",
                    "contentType", contentType != null ? contentType : properties.getDefaultContentType(),
                    "customFields", customFields != null ? customFields : Map.of()
            );
            byteStream.write(objectMapper.writeValueAsBytes(metadata));
            byteStream.write("\r\n".getBytes(StandardCharsets.UTF_8));

            // File Content Part (if binary content exists)
            if (contentBytes != null && contentBytes.length > 0) {
                byteStream.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                byteStream.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                byteStream.write(("Content-Type: " + (mimeType != null ? mimeType : "application/octet-stream") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                byteStream.write(contentBytes);
                byteStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }

            // End boundary
            byteStream.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest request = newRequestBuilder(url)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(byteStream.toByteArray()))
                    .build();

            HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HylandCicException("Failed to create document " + name + " (HTTP " + response.statusCode() + "): "
                        + response.body(), response.statusCode());
            }
            return objectMapper.readValue(response.body(), HylandCicDocument.class);
        } catch (HylandCicException e) {
            throw e;
        } catch (IOException e) {
            throw new HylandCicException("IO error uploading document " + name + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HylandCicException("Document upload interrupted: " + name, e);
        }
    }

    /**
     * Deletes a document by ID.
     */
    public boolean deleteDocument(String documentId) {
        String id = documentId.strip();
        String url = normalizedUrl(properties.getBaseUrl()) + "/api/content/documents/" + id;
        try {
            HttpRequest request = newRequestBuilder(url)
                    .DELETE()
                    .build();
            HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return false;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HylandCicException("Failed to delete document " + id + " (HTTP " + response.statusCode() + ")", response.statusCode());
            }
            return true;
        } catch (HylandCicException e) {
            if (e.isNotFound()) {
                return false;
            }
            throw e;
        } catch (IOException e) {
            throw new HylandCicException("IO error deleting document " + id + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HylandCicException("Document deletion interrupted: " + id, e);
        }
    }

    /**
     * Acquires or returns cached OAuth2 Bearer token.
     */
    public synchronized String getValidToken() {
        if (properties.getBearerToken() != null && !properties.getBearerToken().isBlank()) {
            return properties.getBearerToken();
        }
        if (cachedToken != null && clock.instant().isBefore(tokenExpiresAt)) {
            return cachedToken;
        }

        String idpUrl = properties.getIdpTokenUrl();
        if (idpUrl == null || idpUrl.isBlank()) {
            throw new HylandCicException("Hyland IdP token URL is not configured");
        }

        StringBuilder form = new StringBuilder("grant_type=client_credentials")
                .append("&client_id=").append(URLEncoder.encode(properties.getClientId(), StandardCharsets.UTF_8))
                .append("&client_secret=").append(URLEncoder.encode(properties.getClientSecret(), StandardCharsets.UTF_8));
        if (properties.getScopes() != null && !properties.getScopes().isEmpty()) {
            form.append("&scope=").append(URLEncoder.encode(String.join(" ", properties.getScopes()), StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(idpUrl))
                .timeout(Duration.ofSeconds(properties.getConnectionTimeoutSeconds()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HylandCicException("Hyland IdP OAuth2 token endpoint answered HTTP " + response.statusCode(), response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            String token = body.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new HylandCicException("Hyland IdP token endpoint returned no access_token");
            }
            long expiresIn = body.path("expires_in").asLong(3600);
            this.cachedToken = token;
            this.tokenExpiresAt = clock.instant().plusSeconds(expiresIn).minus(EXPIRY_MARGIN);
            return token;
        } catch (IOException e) {
            throw new HylandCicException("OAuth2 token request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HylandCicException("OAuth2 token request interrupted", e);
        }
    }

    private HttpRequest.Builder newRequestBuilder(String url) {
        String token = getValidToken();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getConnectionTimeoutSeconds()))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");

        if (properties.getTenantId() != null && !properties.getTenantId().isBlank()) {
            builder.header("X-Tenant-Id", properties.getTenantId());
        }
        return builder;
    }

    private <T> HttpResponse<T> sendWithRetry(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler)
            throws IOException, InterruptedException {
        int attempts = 0;
        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        long backoffMs = 500L;

        while (true) {
            attempts++;
            try {
                HttpResponse<T> response = httpClient.send(request, responseHandler);
                int code = response.statusCode();

                if (code == 429 || (code >= 502 && code <= 504)) {
                    if (attempts < maxAttempts) {
                        long sleepMs = backoffMs;
                        Optional<String> retryAfter = response.headers().firstValue("Retry-After");
                        if (retryAfter.isPresent()) {
                            try {
                                sleepMs = Long.parseLong(retryAfter.get()) * 1000L;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        log.warn("HXP endpoint answered HTTP {} on attempt {}/{}. Backing off for {} ms...",
                                code, attempts, maxAttempts, sleepMs);
                        Thread.sleep(sleepMs);
                        backoffMs = Math.min(backoffMs * 2, 10000L);
                        continue;
                    }
                }
                return response;
            } catch (IOException e) {
                if (attempts >= maxAttempts) {
                    throw e;
                }
                log.warn("IO failure on attempt {}/{}: {}. Retrying in {} ms...", attempts, maxAttempts, e.getMessage(), backoffMs);
                Thread.sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 10000L);
            }
        }
    }

    private static String normalizedUrl(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.content.hylandcloud.com";
        }
        String stripped = url.strip();
        return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
    }

    @Override
    public void close() {
        // No heavy background resources to tear down for java.net.http.HttpClient
    }
}
