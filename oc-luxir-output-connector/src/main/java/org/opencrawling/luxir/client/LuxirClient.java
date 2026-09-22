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
package org.opencrawling.luxir.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Lightweight HTTP client for interacting with the Luxir Search Engine REST API.
 */
public class LuxirClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LuxirClient.class);

    private final String endpoint;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LuxirClient(String endpoint, Duration timeout) {
        this(endpoint, timeout, HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), new ObjectMapper());
    }

    public LuxirClient(String endpoint, Duration timeout, HttpClient httpClient, ObjectMapper objectMapper) {
        String base = endpoint.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = base;
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Checks if Luxir node is reachable.
     */
    public boolean ping() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/_stats"))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            try {
                HttpRequest fallbackReq = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/collections"))
                        .timeout(timeout)
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(fallbackReq, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() >= 200 && response.statusCode() < 300;
            } catch (Exception ex) {
                log.debug("Luxir ping failed: {}", ex.getMessage());
                return false;
            }
        }
    }

    /**
     * Verifies if a given collection exists in Luxir.
     */
    public boolean collectionExists(String collection) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/collections/" + collection + "/_stats"))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            if (response.statusCode() == 404) {
                return false;
            }
            // Secondary probe via schema read
            HttpRequest schemaReq = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/collections/" + collection + "/_schema"))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> schemaResp = httpClient.send(schemaReq, HttpResponse.BodyHandlers.ofString());
            return schemaResp.statusCode() == 200;
        } catch (Exception e) {
            log.warn("Error checking existence of collection '{}': {}", collection, e.getMessage());
            return false;
        }
    }

    /**
     * Creates a new collection with optional schema definition.
     */
    public void createCollection(String collection, Map<String, Object> schema) throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", collection);
        if (schema != null && !schema.isEmpty()) {
            payload.put("schema", schema);
        }

        String body = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/collections/_create"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 409) {
            throw new IOException("Failed to create collection '" + collection + "' in Luxir: HTTP "
                    + response.statusCode() + " - " + response.body());
        }
        log.info("Collection '{}' ensured or created in Luxir (HTTP {}).", collection, response.statusCode());
    }

    /**
     * Sets or updates the schema definition for an existing collection.
     */
    public void setSchema(String collection, Map<String, Object> schema) throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(schema);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/collections/" + collection + "/_schema"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to set schema for collection '" + collection + "' in Luxir: HTTP "
                    + response.statusCode() + " - " + response.body());
        }
        log.info("Schema configured for collection '{}' in Luxir.", collection);
    }

    /**
     * Ingests a batch of documents into the collection via POST /collections/{collection}/_update.
     */
    public void updateDocuments(String collection, List<Map<String, Object>> docs, boolean commit) throws IOException, InterruptedException {
        if (docs == null || docs.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("docs", docs);
        if (commit) {
            payload.put("commit", Collections.emptyMap());
        }

        String body = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/collections/" + collection + "/_update"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to ingest " + docs.size() + " documents into Luxir collection '"
                    + collection + "': HTTP " + response.statusCode() + " - " + response.body());
        }
        if (response.body() != null && response.body().contains("\"status\":\"error\"")) {
            throw new IOException("Failed to ingest " + docs.size() + " documents into Luxir collection '"
                    + collection + "': " + response.body());
        }
        log.debug("Ingested {} documents into Luxir collection '{}' (commit: {}).", docs.size(), collection, commit);
    }

    /**
     * Deletes documents by ID via POST /collections/{collection}/_update with delete_ids.
     */
    public void deleteDocuments(String collection, List<String> deleteIds, boolean commit) throws IOException, InterruptedException {
        if (deleteIds == null || deleteIds.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delete_ids", deleteIds);
        if (commit) {
            payload.put("commit", Collections.emptyMap());
        }

        String body = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/collections/" + collection + "/_update"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to delete IDs " + deleteIds + " from Luxir collection '"
                    + collection + "': HTTP " + response.statusCode() + " - " + response.body());
        }
        if (response.body() != null && response.body().contains("\"status\":\"error\"")) {
            throw new IOException("Failed to delete IDs " + deleteIds + " from Luxir collection '"
                    + collection + "': " + response.body());
        }
        log.debug("Deleted IDs {} from Luxir collection '{}' (commit: {}).", deleteIds, collection, commit);
    }

    /**
     * Explicit commit on collection.
     */
    public void commit(String collection) throws IOException, InterruptedException {
        Map<String, Object> payload = Map.of("commit", Collections.emptyMap());
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/collections/" + collection + "/_update"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to commit Luxir collection '" + collection + "': HTTP "
                    + response.statusCode() + " - " + response.body());
        }
    }

    @Override
    public void close() {
        // HttpClient manages internal thread pools
    }
}
