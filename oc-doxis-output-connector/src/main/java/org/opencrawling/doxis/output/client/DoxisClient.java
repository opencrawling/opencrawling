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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Client for the Doxis 4 CSB REST API ({@code /restws/publicws/rest/api/v1}).
 *
 * <p>Authenticates with {@code POST /login} (customer, user, password, optional role) and sends the returned JWT as
 * {@code Authorization: Bearer}. An expired session (HTTP 401) triggers one transparent re-login. Throttling (HTTP 429,
 * honouring {@code Retry-After}), 502/503/504 and connection failures are retried with exponential backoff, but only for
 * requests whose body can be rebuilt — a multipart upload whose stream cannot be reopened is sent exactly once.
 */
public class DoxisClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DoxisClient.class);
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 502, 503, 504);
    private static final int SEARCH_LIMIT = 100;

    private final String baseUrl;
    private final String customerName;
    private final String username;
    private final String password;
    private final String role;
    private final String clientId;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryBackoff;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReentrantLock sessionLock = new ReentrantLock();
    private volatile String token;

    public DoxisClient(String baseUrl, String customerName, String username, String password, String role,
                       String clientId, Duration timeout, int maxRetries) {
        this(baseUrl, customerName, username, password, role, clientId, timeout, maxRetries, Duration.ofMillis(500),
                HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build(),
                new ObjectMapper());
    }

    public DoxisClient(String baseUrl, String customerName, String username, String password, String role,
                       String clientId, Duration timeout, int maxRetries, Duration retryBackoff,
                       HttpClient httpClient, ObjectMapper objectMapper) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.customerName = customerName;
        this.username = username;
        this.password = password;
        this.role = role;
        this.clientId = clientId;
        this.timeout = timeout;
        this.maxRetries = Math.max(0, maxRetries);
        this.retryBackoff = retryBackoff;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    // ------------------------------------------------------------------ session

    /**
     * Logs in (if not already logged in) and returns the session JWT.
     */
    public String login() throws IOException, InterruptedException {
        String current = token;
        if (current != null) {
            return current;
        }
        sessionLock.lock();
        try {
            if (token == null) {
                token = doLogin();
            }
            return token;
        } finally {
            sessionLock.unlock();
        }
    }

    private String doLogin() throws IOException, InterruptedException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("customerName", customerName);
        params.put("userName", username);
        params.put("password", password);
        if (role != null && !role.isBlank()) {
            params.put("role", role);
        }
        params.put("clientImplementationId", clientId);
        JsonNode jwt = execute("Login as " + username + "@" + customerName,
                () -> jsonRequest("/login", "POST", params, false), true, false);
        String value = jwt.isTextual() ? jwt.textValue() : jwt.toString();
        value = value.strip();
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        log.info("Logged in to Doxis CSB at {} as {} (customer {}, role {}).", baseUrl, username, customerName,
                role != null ? role : "-");
        return value;
    }

    /**
     * Invalidates the session. The license counts technical sessions, so this is called on shutdown.
     */
    public void logout() {
        String current = token;
        if (current == null) {
            return;
        }
        token = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/logout"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + current)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("Doxis logout failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        logout();
        httpClient.close();
    }

    // ------------------------------------------------------------------ schema / orga reads

    public JsonNode getRepository(String repository) throws IOException, InterruptedException {
        return get("Get repository " + repository, "/dmsRepositories/" + enc(repository));
    }

    public JsonNode getLoggedInUser() throws IOException, InterruptedException {
        return get("Get session user", "/session/user");
    }

    public List<JsonNode> listDocumentTypes() throws IOException, InterruptedException {
        return list(get("List document types", "/documentTypes"));
    }

    public List<JsonNode> listAttributeDefinitions() throws IOException, InterruptedException {
        return list(get("List attribute definitions", "/attributeDefinitions"));
    }

    public List<JsonNode> listMimeTypes() throws IOException, InterruptedException {
        return list(get("List mime types", "/mimeTypes"));
    }

    public List<JsonNode> listUsers() throws IOException, InterruptedException {
        return list(get("List users", "/users"));
    }

    public List<JsonNode> listGroups() throws IOException, InterruptedException {
        return list(get("List groups", "/groups"));
    }

    // ------------------------------------------------------------------ documents

    /**
     * Runs a CQL search and returns the UUIDs of the hits (current versions, including logically deleted ones only when
     * {@code includeLogicallyDeleted}). A follow-up search result is always closed.
     */
    public List<String> searchDocumentIds(String cqlStatement, boolean includeLogicallyDeleted)
            throws IOException, InterruptedException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cqlStatement", cqlStatement);
        params.put("currentVersionOnly", true);
        params.put("fetchResultLimitation", SEARCH_LIMIT);
        params.put("logicallyDeletedFilter", includeLogicallyDeleted ? "ANY_OBJECTS" : "NON_DELETED_OBJECTS");
        JsonNode result = execute("Search documents", () -> jsonRequest("/documents/search", "POST", params, true), true, true);
        List<String> ids = new ArrayList<>();
        for (JsonNode hit : result.path("searchHits")) {
            String id = hit.path("uuid").asText(null);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        String searchId = result.path("searchId").asText(null);
        if (searchId != null && !searchId.isBlank() && !"null".equals(searchId)) {
            try {
                execute("Close search " + searchId, () -> plainRequest("/documents/searchResults/" + enc(searchId), "DELETE"), true, true);
            } catch (IOException e) {
                log.debug("Closing Doxis search {} failed: {}", searchId, e.getMessage());
            }
        }
        return ids;
    }

    /**
     * {@code POST /dmsRepositories/{repo}/documents} with a {@code documentParams} part and, optionally, the binary as
     * {@code inputStream}. Returns the created {@code DocumentWsTO}.
     */
    public JsonNode createDocument(String repository, Map<String, Object> documentParams, ContentBody content)
            throws IOException, InterruptedException {
        return createDocument(repository, documentParams, null, content);
    }

    /**
     * As {@link #createDocument(String, Map, ContentBody)}, additionally sending {@code relationshipParams} so the new
     * document is filed into a record (e-file) or one of its folder nodes in the same transaction.
     */
    public JsonNode createDocument(String repository, Map<String, Object> documentParams, Map<String, Object> relationshipParams,
                                   ContentBody content) throws IOException, InterruptedException {
        String path = "/dmsRepositories/" + enc(repository) + "/documents";
        JsonNode response = execute("Create document in " + repository,
                () -> multipartRequest(path, "documentParams", documentParams, relationshipParams, content),
                content == null || content.reopenable(), true);
        return response.has("documentWsTO") ? response.path("documentWsTO") : response;
    }

    /**
     * {@code GET /dmsRepositories/{repo}/records/{uuid}}: a record (e-file), used to resolve its repository and instance date.
     */
    public JsonNode getRecord(String repository, String recordId) throws IOException, InterruptedException {
        return get("Get record " + recordId, "/dmsRepositories/" + enc(repository) + "/records/" + enc(recordId));
    }

    /**
     * {@code GET …/documents/{uuid}/permissions}: the document's current ACEs ({@code RestAce}).
     */
    public List<JsonNode> getPermissions(String repository, String documentId) throws IOException, InterruptedException {
        return list(get("Get permissions of " + documentId, "/dmsRepositories/" + enc(repository) + "/documents/"
                + enc(documentId) + "/permissions"));
    }

    /**
     * {@code POST /dmsRepositories/{repo}/documents/{uuid}/versions} with a {@code documentVersionParams} part and, optionally,
     * the binary. Returns the updated {@code DocumentWsTO}.
     */
    public JsonNode addVersion(String repository, String documentId, Map<String, Object> versionParams, ContentBody content)
            throws IOException, InterruptedException {
        String path = "/dmsRepositories/" + enc(repository) + "/documents/" + enc(documentId) + "/versions";
        return execute("Add version to " + documentId,
                () -> multipartRequest(path, "documentVersionParams", versionParams, null, content),
                content == null || content.reopenable(), true);
    }

    /**
     * {@code GET …/documents/{uuid}/versions?initializeRepresentations=true}: every version with its representations and
     * content objects (length, hash, file name) — unwrapped from the returned {@code DocumentWsTO}.
     */
    public List<JsonNode> getVersions(String repository, String documentId) throws IOException, InterruptedException {
        JsonNode response = get("Get versions of " + documentId, "/dmsRepositories/" + enc(repository) + "/documents/"
                + enc(documentId) + "/versions?initializeRepresentations=true");
        // CSB 14.4.1 answers with the DocumentWsTO, whose "versions" array holds the versions
        return list(response != null && response.has("versions") ? response.path("versions") : response);
    }

    /**
     * {@code PATCH …/versions/{versionNr}/attributes}: adds, updates or clears descriptors of a version.
     */
    public void updateAttributes(String repository, String documentId, String versionNr, List<Map<String, Object>> attributes)
            throws IOException, InterruptedException {
        String path = "/dmsRepositories/" + enc(repository) + "/documents/" + enc(documentId) + "/versions/" + enc(versionNr)
                + "/attributes";
        execute("Update attributes of " + documentId, () -> jsonRequest(path, "PATCH", attributes, true), true, true);
    }

    /**
     * {@code POST …/documents/{uuid}/permissions} with a list of {@code DocumentAceParams}.
     */
    public void addPermissions(String repository, String documentId, List<Map<String, Object>> aces)
            throws IOException, InterruptedException {
        if (aces.isEmpty()) {
            return;
        }
        String path = "/dmsRepositories/" + enc(repository) + "/documents/" + enc(documentId) + "/permissions";
        execute("Add permissions to " + documentId, () -> jsonRequest(path, "POST", aces, true), true, true);
    }

    /**
     * {@code POST …/documents/{uuid}/remove}: logical (reversible) delete.
     */
    public void removeDocumentLogically(String repository, String documentId) throws IOException, InterruptedException {
        String path = "/dmsRepositories/" + enc(repository) + "/documents/" + enc(documentId) + "/remove";
        executeTolerating404("Remove document " + documentId, () -> jsonRequest(path, "POST", Map.of(), true));
    }

    /**
     * {@code DELETE …/documents/{uuid}}: physical, irrevocable delete.
     */
    public void deleteDocumentPhysically(String repository, String documentId) throws IOException, InterruptedException {
        String path = "/dmsRepositories/" + enc(repository) + "/documents/" + enc(documentId);
        executeTolerating404("Delete document " + documentId, () -> plainRequest(path, "DELETE"));
    }

    // ------------------------------------------------------------------ plumbing

    private JsonNode get(String operation, String path) throws IOException, InterruptedException {
        return execute(operation, () -> plainRequest(path, "GET"), true, true);
    }

    private void executeTolerating404(String operation, Supplier<HttpRequest> request) throws IOException, InterruptedException {
        try {
            execute(operation, request, true, true);
        } catch (DoxisApiException e) {
            if (e.getStatusCode() != 404) {
                throw e;
            }
            log.debug("{}: already absent.", operation);
        }
    }

    private static List<JsonNode> list(JsonNode node) {
        List<JsonNode> items = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(items::add);
        }
        return items;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpRequest.Builder builder(String path, boolean authenticated) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json");
        if (authenticated) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private HttpRequest plainRequest(String path, String method) {
        return builder(path, true).method(method, HttpRequest.BodyPublishers.noBody()).build();
    }

    private HttpRequest jsonRequest(String path, String method, Object payload, boolean authenticated) {
        try {
            return builder(path, authenticated)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot serialize Doxis request payload", e);
        }
    }

    private HttpRequest multipartRequest(String path, String paramsPartName, Map<String, Object> params,
                                         Map<String, Object> relationshipParams, ContentBody content) {
        String boundary = "----OpenCrawlingDoxis" + UUID.randomUUID().toString().replace("-", "");
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(params);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot serialize Doxis document parameters", e);
        }
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        parts.add(HttpRequest.BodyPublishers.ofByteArray(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + paramsPartName + "\"\r\n"
                + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8)));
        parts.add(HttpRequest.BodyPublishers.ofByteArray(json));
        parts.add(HttpRequest.BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.UTF_8)));
        if (relationshipParams != null) {
            try {
                parts.add(HttpRequest.BodyPublishers.ofByteArray(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"relationshipParams\"\r\n"
                        + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8)));
                parts.add(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(relationshipParams)));
                parts.add(HttpRequest.BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot serialize Doxis relationship parameters", e);
            }
        }
        if (content != null) {
            String fileName = content.fileName() == null ? "content" : content.fileName().replace("\"", "_");
            parts.add(HttpRequest.BodyPublishers.ofByteArray(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"inputStream\"; filename=\"" + fileName + "\"\r\n"
                    + "Content-Type: " + (content.mimeType() != null ? content.mimeType() : "application/octet-stream")
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8)));
            parts.add(HttpRequest.BodyPublishers.ofInputStream(content.stream()));
            parts.add(HttpRequest.BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.UTF_8)));
        }
        parts.add(HttpRequest.BodyPublishers.ofByteArray(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
        return builder(path, true)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new)))
                .build();
    }

    /**
     * Sends a request built by {@code requestFactory}. Authenticated requests log in first and re-login once on 401;
     * retryable failures are retried only when {@code rebuildable}.
     */
    private JsonNode execute(String operation, Supplier<HttpRequest> requestFactory, boolean rebuildable, boolean authenticated)
            throws IOException, InterruptedException {
        if (authenticated) {
            login();
        }
        int attempt = 0;
        boolean reloggedIn = false;
        while (true) {
            HttpResponse<String> response;
            try {
                response = httpClient.send(requestFactory.get(), HttpResponse.BodyHandlers.ofString());
            } catch (ConnectException e) {
                if (!rebuildable || attempt >= maxRetries) {
                    throw e;
                }
                backoff(operation, ++attempt, "connection refused", null);
                continue;
            }

            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                String body = response.body();
                return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
            }
            if (status == 401 && authenticated && !reloggedIn && rebuildable) {
                log.info("Doxis session expired during {}, logging in again.", operation);
                sessionLock.lock();
                try {
                    token = doLogin();
                } finally {
                    sessionLock.unlock();
                }
                reloggedIn = true;
                continue;
            }
            if (RETRYABLE_STATUS.contains(status) && rebuildable && attempt < maxRetries) {
                backoff(operation, ++attempt, "HTTP " + status, response.headers().firstValue("Retry-After").orElse(null));
                continue;
            }
            throw toException(operation, response);
        }
    }

    private void backoff(String operation, int attempt, String reason, String retryAfter) throws InterruptedException {
        long delay = retryBackoff.toMillis() * (1L << (attempt - 1));
        if (retryAfter != null) {
            try {
                delay = Math.max(delay, Long.parseLong(retryAfter.trim()) * 1000L);
            } catch (NumberFormatException ignored) {
                // HTTP-date form is not used by CSB
            }
        }
        log.warn("Doxis {} failed ({}), retrying in {} ms (attempt {}/{}).", operation, reason, delay, attempt, maxRetries);
        Thread.sleep(delay);
    }

    private DoxisApiException toException(String operation, HttpResponse<String> response) {
        String errorCode = null;
        String message = response.body();
        try {
            JsonNode error = objectMapper.readTree(response.body());
            errorCode = error.path("errorCode").asText(null);
            message = error.path("message").asText(message);
        } catch (Exception ignored) {
            // non-JSON error body
        }
        if (message != null && message.length() > 500) {
            message = message.substring(0, 500) + "…";
        }
        return new DoxisApiException("Doxis " + operation, response.statusCode(), errorCode, message);
    }
}
