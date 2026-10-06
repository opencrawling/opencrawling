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
package org.opencrawling.doxis.client;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
    private final LoginMode loginMode;
    private final Supplier<String> credential;
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
        this(baseUrl, customerName, username, password, role, clientId, timeout, maxRetries, retryBackoff, httpClient,
                objectMapper, LoginMode.PASSWORD, null);
    }

    /**
     * As above, choosing how the session is opened. {@code credential} supplies the session ticket
     * ({@link LoginMode#SESSION_TICKET}) or the OIDC access token ({@link LoginMode#OIDC_ACCESS_TOKEN}); it is asked again on
     * every (re-)login, so a token source can hand out a fresh token. Ignored for {@link LoginMode#PASSWORD}.
     */
    public DoxisClient(String baseUrl, String customerName, String username, String password, String role,
                       String clientId, Duration timeout, int maxRetries, Duration retryBackoff,
                       HttpClient httpClient, ObjectMapper objectMapper, LoginMode loginMode, Supplier<String> credential) {
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
        this.loginMode = loginMode == null ? LoginMode.PASSWORD : loginMode;
        this.credential = credential;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    /**
     * How the CSB session is opened (Doxis REST 14.4.1): {@code POST /login} with user name and password,
     * {@code POST /loginBySessionTicket} with a session ticket, or {@code POST /loginOIDCWithAccessToken} with an OIDC/OAuth2
     * access token issued by the identity provider the CSB customer is configured for.
     */
    public enum LoginMode { PASSWORD, SESSION_TICKET, OIDC_ACCESS_TOKEN }

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
        String path;
        String who;
        switch (loginMode) {
            case SESSION_TICKET -> {
                path = "/loginBySessionTicket";
                params.put("sessionTicket", requiredCredential("session ticket"));
                params.put("createOwnSession", true);
                who = "session ticket";
            }
            case OIDC_ACCESS_TOKEN -> {
                path = "/loginOIDCWithAccessToken";
                params.put("accessToken", requiredCredential("OIDC access token"));
                if (role != null && !role.isBlank()) {
                    params.put("roleName", role);
                }
                who = "OIDC access token";
            }
            default -> {
                path = "/login";
                params.put("userName", username);
                params.put("password", password);
                if (role != null && !role.isBlank()) {
                    params.put("role", role);
                }
                who = username;
            }
        }
        params.put("clientImplementationId", clientId);
        JsonNode jwt = execute("Login (" + who + ") to " + customerName, () -> jsonRequest(path, "POST", params, false), true, false);
        String value = jwt.isTextual() ? jwt.textValue() : jwt.toString();
        value = value.strip();
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        log.info("Logged in to Doxis CSB at {} via {} (customer {}, role {}).", baseUrl, who, customerName,
                role != null ? role : "-");
        return value;
    }

    private String requiredCredential(String what) {
        String value = credential == null ? null : credential.get();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("No " + what + " available for the Doxis login.");
        }
        return value.strip();
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

    /**
     * {@code GET /informationObjectTypes}: all object types incl. record (e-file) classes ({@code schemaMetaType RECORD}).
     */
    public List<JsonNode> listInformationObjectTypes() throws IOException, InterruptedException {
        return list(get("List information object types", "/informationObjectTypes"));
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
     * {@code GET /dmsRepositories/{repo}/records/{uuid}?initializeNodeHierarchy=true}: the record with the folder-node tree of
     * its versions ({@code versions[].folderNodes[].childrenFolderNodes}).
     */
    public JsonNode getRecordWithNodes(String repository, String recordId) throws IOException, InterruptedException {
        return get("Get folder nodes of record " + recordId, "/dmsRepositories/" + enc(repository) + "/records/" + enc(recordId)
                + "?initializeNodeHierarchy=true");
    }

    /**
     * {@code POST /dmsRepositories/{repo}/records/{uuid}/nodes} ({@code FolderNodeParams}): creates a local folder node in the
     * record and returns its UUID.
     */
    public String createFolderNode(String repository, String recordId, Map<String, Object> folderNodeParams)
            throws IOException, InterruptedException {
        String path = "/dmsRepositories/" + enc(repository) + "/records/" + enc(recordId) + "/nodes";
        JsonNode result = execute("Create folder node in record " + recordId, () -> jsonRequest(path, "POST", folderNodeParams, true),
                true, true);
        String id = result.isTextual() ? result.asText() : result.path("uuid").asText(null);
        if (id == null || id.isBlank()) {
            throw new IOException("Doxis returned no folder node id for record " + recordId + ": " + result);
        }
        return id;
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
     * {@code POST /records/search} with a CQL statement; returns record UUIDs (search result closed).
     */
    public List<String> searchRecordIds(String cqlStatement) throws IOException, InterruptedException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cqlStatement", cqlStatement);
        params.put("currentVersionOnly", true);
        params.put("fetchResultLimitation", SEARCH_LIMIT);
        params.put("logicallyDeletedFilter", "NON_DELETED_OBJECTS");
        JsonNode result = execute("Search records", () -> jsonRequest("/records/search", "POST", params, true), true, true);
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
                execute("Close record search " + searchId, () -> plainRequest("/records/searchResults/" + enc(searchId), "DELETE"), true, true);
            } catch (IOException e) {
                log.debug("Closing Doxis record search {} failed: {}", searchId, e.getMessage());
            }
        }
        return ids;
    }

    /**
     * {@code POST /dmsRepositories/{repo}/records} ({@code RecordParamsBase}); returns the created record (e-file).
     */
    public JsonNode createRecord(String repository, Map<String, Object> recordParams) throws IOException, InterruptedException {
        String path = "/dmsRepositories/" + enc(repository) + "/records";
        return execute("Create record in " + repository, () -> jsonRequest(path, "POST", recordParams, true), true, true);
    }

    /**
     * {@code POST …/records/{uuid}/permissions} with a list of {@code RecordAceParams}.
     */
    public void addRecordPermissions(String repository, String recordId, List<Map<String, Object>> aces)
            throws IOException, InterruptedException {
        if (aces.isEmpty()) {
            return;
        }
        String path = "/dmsRepositories/" + enc(repository) + "/records/" + enc(recordId) + "/permissions";
        execute("Add permissions to record " + recordId, () -> jsonRequest(path, "POST", aces, true), true, true);
    }

    public List<JsonNode> getRecordPermissions(String repository, String recordId) throws IOException, InterruptedException {
        return list(get("Get permissions of record " + recordId, "/dmsRepositories/" + enc(repository) + "/records/"
                + enc(recordId) + "/permissions"));
    }

    /**
     * {@code PUT …/documents/{uuid}/primaryParent}: files the document into the record (e-file) {@code parentId}.
     */
    public void setDocumentPrimaryParent(String repository, String documentId, String parentId) throws IOException, InterruptedException {
        String path = "/dmsRepositories/" + enc(repository) + "/documents/" + enc(documentId) + "/primaryParent";
        execute("Set primary parent of " + documentId, () -> jsonRequest(path, "PUT", parentId, true), true, true);
    }

    /**
     * {@code DELETE …/documents/{uuid}/permissions/{permission}/organizationalElements/{orgElem}/authorizationVariants/{variant}}.
     */
    public void deleteDocumentPermission(String repository, String documentId, String permission, String organizationalElementId,
                                         String authorizationVariant) throws IOException, InterruptedException {
        String path = "/dmsRepositories/" + enc(repository) + "/documents/" + enc(documentId) + "/permissions/" + enc(permission)
                + "/organizationalElements/" + enc(organizationalElementId) + "/authorizationVariants/" + enc(authorizationVariant);
        executeTolerating404("Remove permission " + permission + " of " + documentId, () -> plainRequest(path, "DELETE"));
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

    // ------------------------------------------------------------------ crawling reads (repository connector)

    /**
     * One page of a CQL document search. A non-null {@code searchId} is a server-side result set that must be closed with
     * {@link #closeSearch(String)}; {@code restrictionMode} is CSB's {@code searchResultRestrictionMode}.
     */
    public record SearchPage(String searchId, int totalHitCount, String restrictionMode, int maxSearchResults, int start,
                             List<JsonNode> hits) {
    }

    public List<JsonNode> listRoles() throws IOException, InterruptedException {
        return list(get("List roles", "/roles"));
    }

    /**
     * {@code POST /documents/search} returning the first page of current versions ({@code DocumentWsTO} hits).
     * {@code logicallyDeletedFilter} is {@code NON_DELETED_OBJECTS}, {@code ONLY_DELETED_OBJECTS} or {@code ANY_OBJECTS}.
     */
    public SearchPage searchDocuments(String cqlStatement, String logicallyDeletedFilter, int pageSize)
            throws IOException, InterruptedException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cqlStatement", cqlStatement);
        params.put("currentVersionOnly", true);
        params.put("fetchResultLimitation", pageSize);
        params.put("logicallyDeletedFilter", logicallyDeletedFilter);
        return toSearchPage(execute("Search documents", () -> jsonRequest("/documents/search", "POST", params, true), true, true));
    }

    /**
     * {@code GET /documents/searchResults/{searchId}?offset&limit}: a further page of an open search result.
     */
    public SearchPage nextSearchResults(String searchId, int offset, int limit) throws IOException, InterruptedException {
        return toSearchPage(get("Get search results " + searchId + " from " + offset,
                "/documents/searchResults/" + enc(searchId) + "?offset=" + offset + "&limit=" + limit));
    }

    /**
     * {@code DELETE /documents/searchResults/{searchId}}; failures are logged, not thrown.
     */
    public void closeSearch(String searchId) throws InterruptedException {
        if (searchId == null || searchId.isBlank() || "null".equals(searchId)) {
            return;
        }
        try {
            execute("Close search " + searchId, () -> plainRequest("/documents/searchResults/" + enc(searchId), "DELETE"), true, true);
        } catch (IOException e) {
            log.debug("Closing Doxis search {} failed: {}", searchId, e.getMessage());
        }
    }

    /**
     * {@code GET …/documents/{uuid}/versions?initializeRepresentations=true}: the whole {@code DocumentWsTO} (primary parent,
     * dates, type, logical-delete state) with its {@code versions[]}.
     */
    public JsonNode getDocumentWithVersions(String repository, String documentId) throws IOException, InterruptedException {
        return get("Get versions of " + documentId, "/dmsRepositories/" + enc(repository) + "/documents/"
                + enc(documentId) + "/versions?initializeRepresentations=true");
    }

    /**
     * {@code GET …/versions/{v}/representations/{rep}/contentObjects/{id}}: streams the binary into {@code target} and returns
     * its length. Each attempt writes a truncated sibling {@code .part} file that is moved onto {@code target} only on success,
     * so a failed or retried attempt never leaves a partial file. Content-link documents cannot be downloaded through REST
     * ({@code SEDNA0104}); callers skip them.
     */
    public long downloadContentObject(String repository, String documentId, String versionNr, String representationId,
                                      String contentObjectId, Path target) throws IOException, InterruptedException {
        String path = "/dmsRepositories/" + enc(repository) + "/documents/" + enc(documentId) + "/versions/" + enc(versionNr)
                + "/representations/" + enc(representationId) + "/contentObjects/" + enc(contentObjectId);
        String operation = "Download content object " + contentObjectId + " of " + documentId;
        Path part = target.resolveSibling(target.getFileName() + ".part");
        login();
        int attempt = 0;
        boolean reloggedIn = false;
        try {
            while (true) {
                HttpRequest request = builder(path, true).setHeader("Accept", "*/*").GET().build();
                HttpResponse<Path> response;
                try {
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(part,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
                } catch (ConnectException e) {
                    if (attempt >= maxRetries) {
                        throw e;
                    }
                    backoff(operation, ++attempt, "connection refused", null);
                    continue;
                }
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    return Files.size(target);
                }
                String body = Files.readString(part, StandardCharsets.UTF_8);
                if (status == 401 && !reloggedIn) {
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
                if (RETRYABLE_STATUS.contains(status) && attempt < maxRetries) {
                    backoff(operation, ++attempt, "HTTP " + status, response.headers().firstValue("Retry-After").orElse(null));
                    continue;
                }
                throw toException(operation, status, body);
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * {@code GET …/records/{uuid}/nodes/{nodeId}/referencedInformationObjects}: what a folder node of an e-file holds —
     * documents in {@code searchHitsDocumentWsTO[]}, sub-e-files in {@code searchHitsCompoundEntityWsTO[]}.
     */
    public JsonNode getNodeReferencedObjects(String repository, String recordId, String nodeId) throws IOException, InterruptedException {
        return get("Get objects in node " + nodeId + " of record " + recordId, "/dmsRepositories/" + enc(repository) + "/records/"
                + enc(recordId) + "/nodes/" + enc(nodeId) + "/referencedInformationObjects");
    }

    /**
     * {@code POST /auditTrail/search} with an {@code AuditQueryWsTO} (operation types, date range, content repository ids,
     * {@code maxHits}); returns the audit records. Empty when auditing is switched off on the CSB.
     */
    public List<JsonNode> searchAuditTrail(Map<String, Object> auditQuery) throws IOException, InterruptedException {
        return list(execute("Search audit trail", () -> jsonRequest("/auditTrail/search", "POST", auditQuery, true), true, true));
    }

    private SearchPage toSearchPage(JsonNode result) {
        String searchId = result.path("searchId").asText(null);
        if (searchId != null && (searchId.isBlank() || "null".equals(searchId))) {
            searchId = null;
        }
        return new SearchPage(searchId, result.path("totalHitCount").asInt(-1), result.path("searchResultRestrictionMode").asText(null),
                result.path("maxSearchResults").asInt(-1), result.path("start").asInt(-1), list(result.path("searchHits")));
    }

    // ------------------------------------------------------------------ plumbing

    /** JSON bodies as a tree; operations declared as {@code string} may answer with a bare, unquoted value. */
    private JsonNode parseBody(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return objectMapper.getNodeFactory().textNode(body.strip());
        }
    }

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
                return body == null || body.isBlank() ? objectMapper.createObjectNode() : parseBody(body);
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
        return toException(operation, response.statusCode(), response.body());
    }

    private DoxisApiException toException(String operation, int statusCode, String body) {
        String errorCode = null;
        String message = body;
        try {
            JsonNode error = objectMapper.readTree(body);
            errorCode = error.path("errorCode").asText(null);
            message = error.path("message").asText(message);
        } catch (Exception ignored) {
            // non-JSON error body
        }
        if (message != null && message.length() > 500) {
            message = message.substring(0, 500) + "…";
        }
        return new DoxisApiException("Doxis " + operation, statusCode, errorCode, message);
    }
}
