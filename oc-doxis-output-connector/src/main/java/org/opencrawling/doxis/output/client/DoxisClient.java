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
package org.opencrawling.doxis.output.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.doxis.output.DoxisConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Minimal client for the Doxis AI.dp REST API (Dataset API v3 + Auth API).
 * Authenticates every request with the {@code x-api-key} header.
 */
public class DoxisClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DoxisClient.class);
    private static final int PAGE_SIZE = 100;
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 502, 503, 504);

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryBackoff;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DoxisClient(String baseUrl, String apiKey, Duration timeout, int maxRetries) {
        this(baseUrl, apiKey, timeout, maxRetries, Duration.ofMillis(500), HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), new ObjectMapper());
    }

    public DoxisClient(String baseUrl, String apiKey, Duration timeout, int maxRetries, Duration retryBackoff,
                       HttpClient httpClient, ObjectMapper objectMapper) {
        String base = baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.maxRetries = Math.max(0, maxRetries);
        this.retryBackoff = retryBackoff;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * GET /api/services/auth/v1/info — verifies the API key and returns its organization, project and enabled services.
     */
    public JsonNode authInfo() throws IOException, InterruptedException {
        return send("Auth info", get(DoxisConstants.AUTH_INFO_PATH)).path("data");
    }

    /**
     * Lists every dataset visible to the API key, following cursor pagination.
     */
    public List<JsonNode> listDatasets() throws IOException, InterruptedException {
        List<JsonNode> datasets = new ArrayList<>();
        String cursor = null;
        do {
            String path = DoxisConstants.DATASETS_PATH + "?limit=" + PAGE_SIZE
                    + (cursor != null ? "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8) : "");
            JsonNode data = send("List datasets", get(path)).path("data");
            data.path("datasets").forEach(datasets::add);
            cursor = nextCursor(data.path("page_info"));
        } while (cursor != null);
        return datasets;
    }

    /**
     * GET a dataset including its typed columns.
     */
    public JsonNode getDataset(String datasetId) throws IOException, InterruptedException {
        return send("Get dataset " + datasetId, get(datasetPath(datasetId))).path("data");
    }

    /**
     * Creates a dataset with the given typed columns ({@code slug -> data_type}) and returns its id.
     */
    public String createDataset(String name, Map<String, String> columns) throws IOException, InterruptedException {
        List<Map<String, Object>> columnBodies = new ArrayList<>();
        columns.forEach((slug, type) -> columnBodies.add(columnBody(slug, type)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("columns", columnBodies);
        JsonNode data = send("Create dataset '" + name + "'", post(DoxisConstants.DATASETS_PATH, payload)).path("data");
        return data.path("id").asText();
    }

    /**
     * Adds a typed column to an existing dataset.
     */
    public void addColumn(String datasetId, String slug, String dataType) throws IOException, InterruptedException {
        send("Add column '" + slug + "'", post(datasetPath(datasetId) + "/columns", columnBody(slug, dataType)));
    }

    /**
     * Bulk-inserts rows ({@code column_slug -> value} maps) with {@code on_error=abort} and returns the created row ids
     * in request order.
     */
    public List<String> insertRows(String datasetId, List<Map<String, Object>> rows) throws IOException, InterruptedException {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        JsonNode data = send("Insert " + rows.size() + " rows",
                post(datasetPath(datasetId) + "/rows?on_error=abort", Map.of("rows", rows))).path("data");
        List<String> ids = new ArrayList<>();
        for (JsonNode row : data.path("rows")) {
            if (!"created".equals(row.path("status").asText())) {
                throw new IOException("Doxis did not fully create row " + row.path("index").asInt() + ": status="
                        + row.path("status").asText() + " " + row.path("row_error").path("message").asText(""));
            }
            ids.add(row.path("id").asText());
        }
        return ids;
    }

    /**
     * Returns the ids of every row whose {@code columnSlug} cell equals {@code value}, following cursor pagination.
     */
    public List<String> findRowIds(String datasetId, String columnSlug, String value) throws IOException, InterruptedException {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("filter", Map.of("column_slug", columnSlug, "operator", "equals", "value", value));
            payload.put("columns", List.of(columnSlug));
            payload.put("limit", PAGE_SIZE);
            if (cursor != null) {
                payload.put("cursor", cursor);
            }
            JsonNode data = send("Search rows by " + columnSlug,
                    post(datasetPath(datasetId) + "/rows/views/detailed/search", payload)).path("data");
            for (JsonNode row : data.path("rows")) {
                String id = row.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
            cursor = nextCursor(data.path("page_info"));
        } while (cursor != null);
        return ids;
    }

    /**
     * Overwrites the given cells of a row; omitted cells keep their values.
     */
    public void patchRow(String datasetId, String rowId, Map<String, Object> cells) throws IOException, InterruptedException {
        List<Map<String, Object>> updates = new ArrayList<>();
        cells.forEach((slug, value) -> {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("column_slug", slug);
            cell.put("value", value);
            updates.add(cell);
        });
        send("Patch row " + rowId, request(datasetPath(datasetId) + "/rows/" + enc(rowId))
                .header("Content-Type", "application/json")
                .method("PATCH", jsonBody(Map.of("cells", updates))).build());
    }

    /**
     * Deletes a row. A 404 (already gone) is treated as success so tombstones are idempotent.
     */
    public void deleteRow(String datasetId, String rowId) throws IOException, InterruptedException {
        try {
            send("Delete row " + rowId, request(datasetPath(datasetId) + "/rows/" + enc(rowId)).DELETE().build());
        } catch (DoxisApiException e) {
            if (e.getStatusCode() != 404) {
                throw e;
            }
            log.debug("Row {} was already absent from dataset {}.", rowId, datasetId);
        }
    }

    @Override
    public void close() {
        httpClient.close();
    }

    private Map<String, Object> columnBody(String slug, String dataType) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", slug);
        column.put("slug", slug);
        column.put("data_type", dataType);
        column.put("nullable", true);
        return column;
    }

    private String datasetPath(String datasetId) {
        return DoxisConstants.DATASETS_PATH + "/" + enc(datasetId);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String nextCursor(JsonNode pageInfo) {
        if (!pageInfo.path("has_more").asBoolean(false)) {
            return null;
        }
        String cursor = pageInfo.path("next_cursor").asText(null);
        return cursor == null || cursor.isBlank() ? null : cursor;
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header(DoxisConstants.API_KEY_HEADER, apiKey);
        }
        return builder;
    }

    private HttpRequest get(String path) {
        return request(path).GET().build();
    }

    private HttpRequest post(String path, Object payload) throws IOException {
        return request(path).header("Content-Type", "application/json").POST(jsonBody(payload)).build();
    }

    private HttpRequest.BodyPublisher jsonBody(Object payload) throws IOException {
        return HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload));
    }

    /**
     * Sends the request, retrying connection failures and 429/502/503/504 with exponential backoff,
     * and returns the parsed JSON body (an empty object for 204 responses).
     */
    private JsonNode send(String operation, HttpRequest request) throws IOException, InterruptedException {
        int attempt = 0;
        while (true) {
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (ConnectException e) {
                if (attempt >= maxRetries) {
                    throw e;
                }
                backoff(operation, ++attempt, "connection refused");
                continue;
            }

            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                String body = response.body();
                return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
            }
            if (RETRYABLE_STATUS.contains(status) && attempt < maxRetries) {
                backoff(operation, ++attempt, "HTTP " + status);
                continue;
            }
            throw toException(operation, response);
        }
    }

    private void backoff(String operation, int attempt, String reason) throws InterruptedException {
        long delay = retryBackoff.toMillis() * (1L << (attempt - 1));
        log.warn("Doxis {} failed ({}), retrying in {} ms (attempt {}/{}).", operation, reason, delay, attempt, maxRetries);
        Thread.sleep(delay);
    }

    private DoxisApiException toException(String operation, HttpResponse<String> response) {
        Integer code = null;
        String message = response.body();
        String requestId = null;
        try {
            JsonNode error = objectMapper.readTree(response.body());
            if (error.has("code")) {
                code = error.path("code").asInt();
            }
            message = error.path("message").asText(message);
            requestId = error.path("request_id").asText(null);
        } catch (Exception ignored) {
            // non-JSON error body, keep raw text
        }
        return new DoxisApiException("Doxis " + operation, response.statusCode(), code, message, requestId);
    }
}
