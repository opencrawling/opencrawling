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
package org.opencrawling.openarch.output.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Duration;
import java.util.*;

/**
 * Modern, high-performance REST client for Hyland OpenArch API.
 */
public class OpenArchClient {

    private static final Logger log = LoggerFactory.getLogger(OpenArchClient.class);
    private static final String HEADER_REPOSITORY = "X-HXCS-Repository";

    private final String baseUrl;
    private final String username;
    private final String password;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    public OpenArchClient(String endpointUrl, String username, String password) {
        this(endpointUrl, username, password, Duration.ofSeconds(30), new ObjectMapper());
    }

    public OpenArchClient(String endpointUrl, String username, String password, Duration requestTimeout, ObjectMapper objectMapper) {
        this.baseUrl = (endpointUrl.endsWith("/")) ? endpointUrl.substring(0, endpointUrl.length() - 1) : endpointUrl;
        this.username = username;
        this.password = password;
        this.requestTimeout = requestTimeout;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private String getAuthHeader() {
        if (username == null || username.isBlank()) {
            return null;
        }
        String creds = username + ":" + (password != null ? password : "");
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder newRequestBuilder(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json");

        String auth = getAuthHeader();
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        return builder;
    }

    /**
     * Creates or updates a document under the specified parent document ID in OpenArch.
     */
    public JsonNode createOrUpdateDocument(String repoId, String parentId, Map<String, Object> document, boolean upsert) throws IOException, InterruptedException {
        String parent = (parentId != null && !parentId.isBlank()) ? parentId : "00000000-0000-0000-0000-000000000000";
        URI uri = URI.create(baseUrl + "/api/documents/" + parent + "?upsert=" + upsert);

        byte[] body = objectMapper.writeValueAsBytes(document);
        HttpRequest request = newRequestBuilder(uri)
                .header(HEADER_REPOSITORY, repoId != null ? repoId : "default")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenArch document upsert failed (HTTP " + response.statusCode() + "): " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * Uploads a binary blob to OpenArch and returns the upload record JSON.
     */
    public JsonNode uploadBlob(InputStream inputStream, String fileName, String mimeType) throws IOException, InterruptedException {
        String fName = (fileName != null && !fileName.isBlank()) ? fileName : "document.bin";
        String mType = (mimeType != null && !mimeType.isBlank()) ? mimeType : "application/octet-stream";

        String encodedFileName = URLEncoder.encode(fName, StandardCharsets.UTF_8);
        String encodedMimeType = URLEncoder.encode(mType, StandardCharsets.UTF_8);
        URI uri = URI.create(baseUrl + "/api/upload?fileName=" + encodedFileName + "&mimeType=" + encodedMimeType);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        inputStream.transferTo(baos);
        byte[] bytes = baos.toByteArray();

        HttpRequest request = newRequestBuilder(uri)
                .header("Content-Type", mType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 202) {
            throw new IOException("OpenArch binary upload failed (HTTP " + response.statusCode() + "): " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * Executes a HXQL query against OpenArch (/api/query).
     */
    public JsonNode query(String repoId, String hxql, int limit, long offset, String searchAfter) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/api/query");

        Map<String, Object> queryBody = new LinkedHashMap<>();
        queryBody.put("repositoryId", repoId != null ? repoId : "default");
        queryBody.put("query", hxql);
        queryBody.put("limit", (long) limit);
        queryBody.put("offset", offset);
        if (searchAfter != null && !searchAfter.isBlank()) {
            queryBody.put("searchAfter", searchAfter);
        }

        byte[] body = objectMapper.writeValueAsBytes(queryBody);
        HttpRequest request = newRequestBuilder(uri)
                .header(HEADER_REPOSITORY, repoId != null ? repoId : "default")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenArch query failed (HTTP " + response.statusCode() + "): " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * Executes a vector search query against OpenArch (/api/query/embedding).
     */
    public JsonNode queryEmbedding(String repoId, String embeddingType, List<Double> vector, String chunkFTS, String filterQuery, int limit, long offset) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/api/query/embedding");

        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("repositoryId", repoId != null ? repoId : "default");
        bodyMap.put("embeddingType", embeddingType != null ? embeddingType : "*");
        bodyMap.put("vector", vector != null ? vector : Collections.emptyList());
        if (chunkFTS != null && !chunkFTS.isBlank()) {
            bodyMap.put("chunkFTS", chunkFTS);
        }
        if (filterQuery != null && !filterQuery.isBlank()) {
            bodyMap.put("query", filterQuery);
        }
        bodyMap.put("limit", (long) limit);
        bodyMap.put("offset", offset);

        byte[] body = objectMapper.writeValueAsBytes(bodyMap);
        HttpRequest request = newRequestBuilder(uri)
                .header(HEADER_REPOSITORY, repoId != null ? repoId : "default")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenArch embedding search failed (HTTP " + response.statusCode() + "): " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * Retrieves document metadata by ID.
     */
    public JsonNode getDocument(String repoId, String docId) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/api/documents/" + docId);
        HttpRequest request = newRequestBuilder(uri)
                .header(HEADER_REPOSITORY, repoId != null ? repoId : "default")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenArch getDocument failed (HTTP " + response.statusCode() + "): " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * Downloads document binary stream.
     */
    public InputStream downloadBlob(String repoId, String docId) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/api/download/" + docId);
        HttpRequest request = newRequestBuilder(uri)
                .header(HEADER_REPOSITORY, repoId != null ? repoId : "default")
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenArch downloadBlob failed (HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    /**
     * Connectivity check.
     */
    public boolean ping(String repoId) {
        try {
            URI uri = URI.create(baseUrl + "/api/query");
            Map<String, Object> pingQuery = Map.of(
                    "repositoryId", repoId != null ? repoId : "default",
                    "query", "SELECT * FROM SysContent",
                    "limit", 1
            );
            HttpRequest request = newRequestBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header(HEADER_REPOSITORY, repoId != null ? repoId : "default")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(pingQuery)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() == 200 || response.statusCode() == 404;
        } catch (Exception e) {
            log.debug("OpenArch ping failed: {}", e.getMessage());
            return false;
        }
    }
}
