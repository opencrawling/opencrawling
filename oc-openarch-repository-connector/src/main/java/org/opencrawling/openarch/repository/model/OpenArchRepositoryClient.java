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
package org.opencrawling.openarch.repository.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * HTTP Client tailored for crawling and harvesting Hyland OpenArch content and metadata.
 */
public class OpenArchRepositoryClient {

    private static final Logger log = LoggerFactory.getLogger(OpenArchRepositoryClient.class);
    private static final String HEADER_REPOSITORY = "X-HXCS-Repository";

    private final String baseUrl;
    private final String username;
    private final String password;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    public OpenArchRepositoryClient(String endpointUrl, String username, String password) {
        this(endpointUrl, username, password, Duration.ofSeconds(30), new ObjectMapper());
    }

    public OpenArchRepositoryClient(String endpointUrl, String username, String password, Duration requestTimeout, ObjectMapper objectMapper) {
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

    public InputStream downloadBlob(String repoId, String docId) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/api/download/" + docId);
        HttpRequest request = newRequestBuilder(uri)
                .header(HEADER_REPOSITORY, repoId != null ? repoId : "default")
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenArch downloadBlob failed for docId " + docId + " (HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    public JsonNode getDocument(String repoId, String docId) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/api/documents/" + docId);
        HttpRequest request = newRequestBuilder(uri)
                .header(HEADER_REPOSITORY, repoId != null ? repoId : "default")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenArch getDocument failed for docId " + docId + " (HTTP " + response.statusCode() + ")");
        }
        return objectMapper.readTree(response.body());
    }

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
            log.debug("OpenArch repository client ping failed: {}", e.getMessage());
            return false;
        }
    }
}
