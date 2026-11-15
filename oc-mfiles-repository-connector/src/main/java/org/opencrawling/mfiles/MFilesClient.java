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
package org.opencrawling.mfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opencrawling.mfiles.MFilesProperties.LoginMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A client for the M-Files Web Service (MFWS) REST API, scoped to one vault.
 *
 * <p>Authentication follows {@link MFilesProperties#loginMode()}:
 * <ul>
 *   <li>{@code TOKEN}: {@code POST /server/authenticationtokens} with the vault GUID and a session id, the token sent as
 *       {@code X-Authentication}; a 401/403 re-authenticates once (tokens have no fixed lifetime), and {@link #logout()}
 *       ends the session with {@code DELETE /session};</li>
 *   <li>{@code HEADERS}: {@code X-Username}/{@code X-Password}/{@code X-Vault} on every request;</li>
 *   <li>{@code BEARER}: {@code Authorization: Bearer} and {@code X-Vault}.</li>
 * </ul>
 * Connection failures, 429 and 502-504 are retried with a growing back-off. Credentials and tokens are never logged and
 * never sent to a host other than the MFWS host.
 */
public class MFilesClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MFilesClient.class);

    /** A page of search or view results: the items and whether MFWS cut the result at the limit. */
    public record Page(List<JsonNode> items, boolean moreResults) {
    }

    private final String baseUrl;
    private final URI baseUri;
    private final String vaultGuid;
    private final MFilesProperties settings;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration backoff;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Supplier<String> bearer;
    private final String sessionId = UUID.randomUUID().toString();
    private volatile String token;

    public MFilesClient(MFilesProperties settings, Duration timeout, int maxRetries, Duration backoff, HttpClient http,
                        ObjectMapper mapper, Supplier<String> bearer) {
        this.settings = settings;
        this.baseUrl = settings.url();
        this.baseUri = URI.create(settings.url() + "/");
        this.vaultGuid = withBraces(settings.vaultGuid());
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.backoff = backoff;
        this.http = http;
        this.mapper = mapper;
        this.bearer = bearer;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // ------------------------------------------------------------------ authentication

    /**
     * Opens a vault-scoped session ({@code TOKEN} mode); a no-op for the other modes, which authenticate every request.
     */
    public synchronized void login() throws IOException, InterruptedException {
        if (settings.loginMode() != LoginMode.TOKEN) {
            return;
        }
        if (settings.username() == null || settings.username().isBlank() || settings.password() == null) {
            // M-Files hands out an anonymous token for empty credentials; every vault call then fails with HTTP 403
            throw new IOException("M-Files authentication needs a user name and password.");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("Username", settings.username());
        body.put("Password", settings.password());
        if (settings.domain() != null) {
            body.put("Domain", settings.domain());
        }
        body.put("VaultGuid", vaultGuid);
        body.put("SessionID", sessionId);
        HttpRequest request = HttpRequest.newBuilder(uri("/server/authenticationtokens"))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = sendWithRetries(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MFilesApiException(response.statusCode(), "M-Files authentication for user '" + settings.username()
                    + "' on vault " + vaultGuid + " failed: HTTP " + response.statusCode() + " " + excerpt(response.body()));
        }
        String value = tokenValue(response.body());
        if (value == null || value.isBlank()) {
            throw new IOException("M-Files answered the authentication with an empty token for vault " + vaultGuid
                    + "; check the vault GUID and that the user can access that vault.");
        }
        token = value;
    }

    /** The token from {@code {"Value": "…"}} (what MFWS returns) or from a bare JSON string. */
    private String tokenValue(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode json = mapper.readTree(body);
        if (json.isTextual()) {
            return json.asText();
        }
        JsonNode value = json.path("Value");
        return value.isTextual() ? value.asText() : null;
    }

    /** Ends the session ({@code TOKEN} mode). Failures are logged, never thrown: the crawl result does not depend on them. */
    public synchronized void logout() {
        String current = token;
        token = null;
        if (settings.loginMode() != LoginMode.TOKEN || current == null) {
            return;
        }
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri("/session")).timeout(timeout)
                    .header("X-Authentication", current).DELETE().build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 405) {
                // IIS may refuse DELETE; MFWS accepts the method override
                response = http.send(HttpRequest.newBuilder(uri("/session?_method=DELETE")).timeout(timeout)
                        .header("X-Authentication", current).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.discarding());
            }
            log.debug("M-Files session ended (HTTP {}).", response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("M-Files logout failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        logout();
    }

    // ------------------------------------------------------------------ vault and structure

    /** {@code GET /session/vault}: the vault the session is scoped to ({@code Name}, {@code GUID}). */
    public JsonNode getVault() throws IOException, InterruptedException {
        return getJson("/session/vault");
    }

    public JsonNode getObjectTypes() throws IOException, InterruptedException {
        return getJson("/structure/objecttypes");
    }

    /** {@code GET /structure/properties}: the property definitions ({@code ID}, {@code Name}, {@code DataType}). */
    public JsonNode getPropertyDefinitions() throws IOException, InterruptedException {
        return getJson("/structure/properties");
    }

    /** The items of a value list ({@code /valuelists/{id}/items}); used to name users and user groups. */
    public List<JsonNode> getValueListItems(int valueListId) throws IOException, InterruptedException {
        return items(getJson("/valuelists/" + valueListId + "/items"));
    }

    // ------------------------------------------------------------------ objects

    /**
     * {@code GET /objects?<query>}: one search request. MFWS returns at most {@code limit} results and sets
     * {@code MoreResults} when it cut the result; there is no offset to page with.
     *
     * @param query the query string without {@code ?}, already URL-encoded
     */
    public Page search(String query) throws IOException, InterruptedException {
        JsonNode result = getJson("/objects" + (query == null || query.isBlank() ? "" : "?" + query));
        return new Page(items(result), result.path("MoreResults").asBoolean(result.path("moreResults").asBoolean(false)));
    }

    /** {@code GET /views/<path>/items}: the contents of a view folder ({@code FolderContentItems}). */
    public Page getViewItems(String viewPath) throws IOException, InterruptedException {
        String path = viewPath == null || viewPath.isBlank() ? "" : "/" + viewPath.replaceAll("^/+|/+$", "");
        JsonNode result = getJson("/views" + path + "/items");
        return new Page(items(result), result.path("MoreResults").asBoolean(false));
    }

    /** {@code GET /objects/{type}/{id}/{version}/properties}: the object version's {@code PropertyValue}s. */
    public List<JsonNode> getProperties(int type, int id, int version) throws IOException, InterruptedException {
        return items(getJson(objectPath(type, id, version) + "/properties"));
    }

    /** {@code GET /objects/{type}/{id}/history}: every version of an object ({@code ObjectVersion}s). */
    public List<JsonNode> getHistory(int type, int id) throws IOException, InterruptedException {
        return items(getJson("/objects/" + type + "/" + id + "/history"));
    }

    /**
     * {@code GET /objects/{type}/{id}/{version}/permissions}. The resource is not in the public MFWS reference, so a server
     * that does not offer it (404, 405, 501) gives an empty result instead of an error.
     */
    public Optional<JsonNode> getPermissions(int type, int id, int version) throws IOException, InterruptedException {
        try {
            return Optional.of(getJson(objectPath(type, id, version) + "/permissions"));
        } catch (MFilesApiException e) {
            if (e.isUnsupported()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Streams {@code GET /objects/{type}/{id}/{version}/files/{fileId}/content} into {@code target}.
     *
     * @return the bytes written, or {@code -1} when the file is larger than {@code maxBytes} (the partial file is removed)
     */
    public long downloadFile(int type, int id, int version, int fileId, Path target, long maxBytes)
            throws IOException, InterruptedException {
        return download(uri(objectPath(type, id, version) + "/files/" + fileId + "/content"), target, maxBytes);
    }

    // ------------------------------------------------------------------ Vault Application Framework endpoints

    /** The VAF change feed: {@code {changes: [...], nextCursor, hasMore}}. */
    public JsonNode getChanges(String cursor, int pageSize) throws IOException, InterruptedException {
        return getJson(path(settings.vafBasePath()) + path(settings.vafChangeFeedPath()) + "?vaultGuid=" + enc(vaultGuid)
                + "&since=" + enc(cursor == null ? "" : cursor) + "&pageSize=" + pageSize);
    }

    /** The VAF normalized object: metadata, {@code aclTokens}/{@code denyTokens}, file name, download URL, version string. */
    public JsonNode getNormalized(int type, int id, int version) throws IOException, InterruptedException {
        String template = path(settings.vafNormalizedPath())
                .replace("{objType}", Integer.toString(type))
                .replace("{objId}", Integer.toString(id))
                .replace("{version}", Integer.toString(version));
        return getJson(path(settings.vafBasePath()) + template + "?vaultGuid=" + enc(vaultGuid) + "&type=" + type + "&id=" + id
                + "&version=" + version);
    }

    /**
     * Streams a download URL a VAF payload gave. Relative URLs resolve against the MFWS base; absolute URLs must point at
     * the MFWS host, because the request carries the session's credentials.
     */
    public long downloadUrl(String url, Path target, long maxBytes) throws IOException, InterruptedException {
        URI resolved = baseUri.resolve(url);
        if (!Objects.equals(resolved.getScheme(), baseUri.getScheme()) || !Objects.equals(resolved.getHost(), baseUri.getHost())
                || resolved.getPort() != baseUri.getPort()) {
            throw new IOException("Refusing to send M-Files credentials to " + resolved.getScheme() + "://" + resolved.getHost()
                    + ": the download URL is not on the MFWS host " + baseUri.getHost() + ".");
        }
        return download(resolved, target, maxBytes);
    }

    // ------------------------------------------------------------------ plumbing

    private long download(URI uri, Path target, long maxBytes) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = authorized(uri, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MFilesApiException(response.statusCode(), "GET " + uri.getRawPath() + " answered HTTP "
                        + response.statusCode() + " " + excerpt(new String(in.readNBytes(4096), StandardCharsets.UTF_8)));
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maxBytes) {
                return -1;
            }
            long written = 0;
            byte[] buffer = new byte[64 * 1024];
            try (OutputStream out = Files.newOutputStream(target)) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    written += n;
                    if (written > maxBytes) {
                        out.close();
                        Files.deleteIfExists(target);
                        return -1;
                    }
                    out.write(buffer, 0, n);
                }
            }
            return written;
        }
    }

    JsonNode getJson(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = authorized(uri(path), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = "GET " + stripQuery(path) + " answered HTTP " + response.statusCode() + " " + excerpt(response.body());
            if (response.statusCode() == 403) {
                message += " (check that the account can read vault " + vaultGuid + ")";
            }
            throw new MFilesApiException(response.statusCode(), message);
        }
        String body = response.body();
        return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
    }

    /** A GET with this client's credentials; in token mode a 401/403 re-authenticates once and repeats the request. */
    private <T> HttpResponse<T> authorized(URI uri, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        if (settings.loginMode() == LoginMode.TOKEN && token == null) {
            login();
        }
        String used = token;
        HttpResponse<T> response = sendWithRetries(request(uri), handler);
        if (settings.loginMode() == LoginMode.TOKEN && (response.statusCode() == 401 || response.statusCode() == 403)) {
            discard(response);
            synchronized (this) {
                if (Objects.equals(token, used)) {
                    log.debug("M-Files answered HTTP {}; renewing the authentication token.", response.statusCode());
                    login();
                }
            }
            response = sendWithRetries(request(uri), handler);
        }
        return response;
    }

    private HttpRequest request(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET();
        switch (settings.loginMode()) {
            case TOKEN -> builder.header("X-Authentication", Objects.requireNonNullElse(token, "")).header("X-Vault", vaultGuid);
            case HEADERS -> builder.header("X-Username", qualifiedUser()).header("X-Password", settings.password())
                    .header("X-Vault", vaultGuid);
            case BEARER -> builder.header("Authorization", "Bearer " + bearer.get()).header("X-Vault", vaultGuid);
        }
        return builder.build();
    }

    private String qualifiedUser() {
        return settings.domain() == null ? settings.username() : settings.domain() + "\\" + settings.username();
    }

    private <T> HttpResponse<T> sendWithRetries(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<T> response = http.send(request, handler);
                int status = response.statusCode();
                if ((status == 429 || status == 502 || status == 503 || status == 504) && attempt < maxRetries) {
                    discard(response);
                    pause(attempt);
                    continue;
                }
                return response;
            } catch (IOException e) {
                if (attempt >= maxRetries) {
                    throw new IOException(request.method() + " " + stripQuery(request.uri().getRawPath()) + " failed after "
                            + (attempt + 1) + " attempt(s): " + e.getMessage(), e);
                }
                pause(attempt);
            }
        }
    }

    private void pause(int attempt) throws InterruptedException {
        Thread.sleep(Math.min(backoff.toMillis() * (attempt + 1), 30_000L));
    }

    private static void discard(HttpResponse<?> response) {
        if (response.body() instanceof InputStream in) {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to release
            }
        }
    }

    /** The {@code Items} of a {@code Results<T>} / {@code FolderContentItems}, or the elements of a bare array. */
    public static List<JsonNode> items(JsonNode node) {
        JsonNode array = node.isArray() ? node : node.has("Items") ? node.path("Items") : node.path("items");
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items;
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static String objectPath(int type, int id, int version) {
        return "/objects/" + type + "/" + id + "/" + (version < 0 ? "latest" : Integer.toString(version));
    }

    private static String path(String segment) {
        if (segment == null || segment.isBlank()) {
            return "";
        }
        String s = segment.strip();
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static String withBraces(String guid) {
        String g = MFilesProperties.stripBraces(guid);
        return g == null ? null : "{" + g + "}";
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        String b = body.strip();
        return b.length() > 500 ? b.substring(0, 500) + "…" : b;
    }
}
