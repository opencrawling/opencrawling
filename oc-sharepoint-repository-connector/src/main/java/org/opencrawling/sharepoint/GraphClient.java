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
package org.opencrawling.sharepoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A Microsoft Graph v1.0 client for SharePoint document libraries, authenticated app-only with the OAuth2
 * client-credentials grant.
 *
 * <ul>
 *   <li>The access token is cached and renewed shortly before it expires; a 401 renews it once.</li>
 *   <li>429, 503 and 504 honour {@code Retry-After} (seconds or an HTTP date) through one gate shared by every thread, as
 *       Microsoft asks of multi-threaded clients. The header is a floor, not a moment: it is whole seconds and may be
 *       rounded down (Microsoft Dev Proxy's throttling plugins truncate it), so the client waits one second more, and never
 *       less than the exponential back-off with jitter it uses without the header. A {@code Retry-After: 0} therefore
 *       can't turn into a burst of immediate retries.</li>
 *   <li>The bearer token is only ever sent to the Graph host, also when following {@code @odata.nextLink} /
 *       {@code @odata.deltaLink}. Redirects are not followed automatically; a download redirect is followed without
 *       credentials, and {@code @microsoft.graph.downloadUrl} is pre-authenticated and called without them.</li>
 * </ul>
 * Secrets and tokens are never logged.
 */
public class GraphClient {

    private static final Logger log = LoggerFactory.getLogger(GraphClient.class);
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(2);
    static final String USER_AGENT = "ISV|OpenCrawling|OpenCrawling-SharePoint-Connector/1.0";

    /** One page of a Graph collection: its items, and the next page or (last page of a delta) the delta link. */
    public record Page(List<JsonNode> items, String nextLink, String deltaLink) {
    }

    private final SharePointProperties settings;
    private final URI graphBase;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Clock clock;
    private String token;
    private Instant tokenExpiry = Instant.MIN;
    /** Requests wait until this instant after a throttled response (shared by all threads). */
    private volatile Instant throttledUntil = Instant.MIN;

    public GraphClient(SharePointProperties settings) {
        this(settings, HttpClient.newBuilder().connectTimeout(settings.timeout()).followRedirects(HttpClient.Redirect.NEVER).build(),
                new ObjectMapper(), Clock.systemUTC());
    }

    GraphClient(SharePointProperties settings, HttpClient http, ObjectMapper mapper, Clock clock) {
        this.settings = settings;
        this.graphBase = URI.create(settings.graphUrl() + "/");
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ authentication

    /** The app-only access token, from the cache or a new client-credentials request. */
    synchronized String accessToken() throws IOException, InterruptedException {
        if (token != null && clock.instant().isBefore(tokenExpiry)) {
            return token;
        }
        String form = "grant_type=client_credentials"
                + "&client_id=" + enc(settings.clientId())
                + "&client_secret=" + enc(settings.clientSecret())
                + "&scope=" + enc(scopeFor(graphBase));
        URI tokenUri = URI.create(settings.authorityUrl() + "/" + enc(settings.tenantId()) + "/oauth2/v2.0/token");
        HttpRequest request = HttpRequest.newBuilder(tokenUri).timeout(settings.timeout())
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .header("User-Agent", USER_AGENT).POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = parse(response.body());
        if (response.statusCode() != 200 || !body.path("access_token").isTextual()) {
            // Entra's error_description names the problem (wrong secret, unknown tenant, missing consent) without secrets
            throw new GraphApiException(response.statusCode(), body.path("error").asText(null),
                    "Entra ID token request for client " + settings.clientId() + " in tenant " + settings.tenantId()
                            + " failed: HTTP " + response.statusCode() + " " + firstLine(body.path("error_description").asText("")),
                    null);
        }
        token = body.path("access_token").asText();
        tokenExpiry = clock.instant().plusSeconds(body.path("expires_in").asLong(3599)).minus(TOKEN_MARGIN);
        return token;
    }

    /** {@code https://graph.microsoft.com/.default}, or the sovereign cloud's Graph host. */
    static String scopeFor(URI graphBase) {
        return graphBase.getScheme() + "://" + graphBase.getAuthority() + "/.default";
    }

    private synchronized void invalidateToken(String used) {
        if (Objects.equals(token, used)) {
            token = null;
        }
    }

    // ------------------------------------------------------------------ sites, drives, items

    /** {@code GET /sites/{hostname}:/{path}}, or {@code /sites/{hostname}} for the root site. */
    public JsonNode getSiteByPath(String hostname, String serverRelativePath) throws IOException, InterruptedException {
        String path = serverRelativePath == null || serverRelativePath.isBlank() ? "" : ":" + encodePath(serverRelativePath);
        return getJson("sites/" + hostname + path);
    }

    public JsonNode getSite(String siteId) throws IOException, InterruptedException {
        return getJson("sites/" + siteId);
    }

    /** The document libraries of a site ({@code GET /sites/{id}/drives}), every page. */
    public List<JsonNode> listDrives(String siteId) throws IOException, InterruptedException {
        List<JsonNode> drives = new ArrayList<>();
        String next = "sites/" + siteId + "/drives";
        while (next != null) {
            Page page = page(next);
            drives.addAll(page.items());
            next = page.nextLink();
        }
        return drives;
    }

    public JsonNode getDrive(String driveId) throws IOException, InterruptedException {
        return getJson("drives/" + driveId);
    }

    /** {@code GET /drives/{id}/items/{id}}: one item, with {@code parentReference.path} (which delta pages omit). */
    public JsonNode getItem(String driveId, String itemId) throws IOException, InterruptedException {
        return getJson("drives/" + driveId + "/items/" + itemId + "?$select=id,name,parentReference,root");
    }

    /** The item at a path relative to the library root ({@code /drives/{id}/root:/{path}}), or the root for {@code "/"}. */
    public JsonNode getItemByPath(String driveId, String path) throws IOException, InterruptedException {
        String p = path == null ? "" : path.strip().replaceAll("^/+|/+$", "");
        return getJson("drives/" + driveId + "/root" + (p.isEmpty() ? "" : ":/" + encodePath(p) + ":"));
    }

    /** The first page of a folder's children, with the list item columns when {@code fields} is true. */
    public String childrenUrl(String driveId, String itemId, boolean fields) {
        return "drives/" + driveId + "/items/" + itemId + "/children?$top=" + settings.batchSize()
                + (fields ? "&$expand=" + enc("listItem($expand=fields)") : "");
    }

    /** The start of a full delta enumeration of a library. */
    public String deltaUrl(String driveId, boolean fields) {
        return "drives/" + driveId + "/root/delta?$top=" + settings.batchSize()
                + (fields ? "&$expand=" + enc("listItem($expand=fields)") : "");
    }

    /** One page of a collection: a path relative to the Graph base, or a next/delta link Graph returned. */
    public Page page(String pathOrLink) throws IOException, InterruptedException {
        JsonNode body = getJson(pathOrLink);
        List<JsonNode> items = new ArrayList<>();
        body.path("value").forEach(items::add);
        return new Page(items, text(body, "@odata.nextLink"), text(body, "@odata.deltaLink"));
    }

    /** {@code GET /drives/{id}/items/{id}/permissions}: every page of the item's permissions. */
    public List<JsonNode> getPermissions(String driveId, String itemId) throws IOException, InterruptedException {
        List<JsonNode> permissions = new ArrayList<>();
        String next = "drives/" + driveId + "/items/" + itemId + "/permissions";
        while (next != null) {
            Page page = page(next);
            permissions.addAll(page.items());
            next = page.nextLink();
        }
        return permissions;
    }

    /** {@code GET /drives/{id}/items/{id}/listItem?$expand=fields}: the item's list columns. */
    public JsonNode getListItemFields(String driveId, String itemId) throws IOException, InterruptedException {
        return getJson("drives/" + driveId + "/items/" + itemId + "/listItem?$expand=fields").path("fields");
    }

    // ------------------------------------------------------------------ content

    /**
     * Streams a file into {@code target}: from its pre-authenticated {@code downloadUrl} when the item carried one (called
     * without credentials), otherwise from {@code /content}, whose redirect is followed without credentials.
     *
     * @return the bytes written, or {@code -1} when the file is larger than {@code maxBytes} (the partial file is removed)
     */
    public long download(String driveId, String itemId, String downloadUrl, Path target, long maxBytes)
            throws IOException, InterruptedException {
        if (downloadUrl != null && !downloadUrl.isBlank()) {
            try {
                return stream(HttpRequest.newBuilder(URI.create(downloadUrl)).timeout(settings.timeout())
                        .header("User-Agent", USER_AGENT).GET().build(), target, maxBytes);
            } catch (GraphApiException e) {
                // the pre-authenticated URL lives about an hour; past that, ask Graph again
                if (e.status() != 401 && e.status() != 403 && e.status() != 404) {
                    throw e;
                }
                log.debug("downloadUrl of item {} was rejected (HTTP {}); using /content.", itemId, e.status());
            }
        }
        URI content = graphBase.resolve("drives/" + driveId + "/items/" + itemId + "/content");
        HttpResponse<InputStream> response = authorized(content, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status == 302 || status == 301 || status == 307) {
            response.body().close();
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("Graph redirected /content of item " + itemId + " without a Location."));
            return stream(HttpRequest.newBuilder(content.resolve(location)).timeout(settings.timeout())
                    .header("User-Agent", USER_AGENT).GET().build(), target, maxBytes);
        }
        return copy(response, "GET /drives/…/items/" + itemId + "/content", target, maxBytes);
    }

    private long stream(HttpRequest request, Path target, long maxBytes) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = sendWithRetries(request, HttpResponse.BodyHandlers.ofInputStream());
        return copy(response, "GET " + request.uri().getHost() + " (download)", target, maxBytes);
    }

    private long copy(HttpResponse<InputStream> response, String what, Path target, long maxBytes) throws IOException {
        try (InputStream in = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw error(response.statusCode(), what, new String(in.readNBytes(4096), StandardCharsets.UTF_8), null);
            }
            if (response.headers().firstValueAsLong("Content-Length").orElse(-1) > maxBytes) {
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

    // ------------------------------------------------------------------ plumbing

    JsonNode getJson(String pathOrLink) throws IOException, InterruptedException {
        URI uri = resolve(pathOrLink);
        HttpResponse<String> response = authorized(uri, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw error(response.statusCode(), "GET " + uri.getRawPath(), response.body(),
                    response.headers().firstValue("Location").orElse(null));
        }
        return parse(response.body());
    }

    /** A path relative to the Graph base, or an absolute link that must point at the Graph host. */
    URI resolve(String pathOrLink) throws IOException {
        URI uri = graphBase.resolve(pathOrLink);
        if (!Objects.equals(uri.getScheme(), graphBase.getScheme()) || !Objects.equals(uri.getAuthority(), graphBase.getAuthority())) {
            throw new IOException("Refusing to send the Graph access token to " + uri.getScheme() + "://" + uri.getAuthority()
                    + ": the link is not on " + graphBase.getAuthority() + ".");
        }
        return uri;
    }

    private <T> HttpResponse<T> authorized(URI uri, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        String used = accessToken();
        HttpResponse<T> response = sendWithRetries(request(uri, used), handler);
        if (response.statusCode() == 401) {
            discard(response);
            invalidateToken(used);
            response = sendWithRetries(request(uri, accessToken()), handler);
        }
        return response;
    }

    private static HttpRequest request(URI uri, String token) {
        // client-request-id: Microsoft asks for a GUID per request so that its support can trace a failing call
        return HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .header("User-Agent", USER_AGENT).header("client-request-id", java.util.UUID.randomUUID().toString()).GET().build();
    }

    private <T> HttpResponse<T> sendWithRetries(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            awaitThrottle();
            try {
                HttpResponse<T> response = send(request, handler);
                int status = response.statusCode();
                if ((status == 429 || status == 503 || status == 504) && attempt < settings.maxRetries()) {
                    Duration wait = waitAfter(response, attempt);
                    throttledUntil = later(throttledUntil, clock.instant().plus(wait));
                    log.info("Graph answered HTTP {}; pausing all requests for {} ms (attempt {} of {}).", status,
                            wait.toMillis(), attempt + 1, settings.maxRetries());
                    discard(response);
                    continue;
                }
                return response;
            } catch (IOException e) {
                if (attempt >= settings.maxRetries()) {
                    throw new IOException(request.method() + " " + request.uri().getHost() + request.uri().getRawPath()
                            + " failed after " + (attempt + 1) + " attempt(s): " + e.getMessage(), e);
                }
                Thread.sleep(backoff(attempt).toMillis());
            }
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        HttpRequest timed = HttpRequest.newBuilder(request, (name, value) -> true).timeout(settings.timeout()).build();
        return http.send(timed, handler);
    }

    private void awaitThrottle() throws InterruptedException {
        Duration wait = Duration.between(clock.instant(), throttledUntil);
        if (!wait.isNegative() && !wait.isZero()) {
            Thread.sleep(wait.toMillis());
        }
    }

    /** How long to pause after a throttled or unavailable answer: {@code Retry-After} plus a second, at least the back-off. */
    Duration waitAfter(HttpResponse<?> response, int attempt) {
        Duration backoff = backoff(attempt);
        return retryAfter(response).map(d -> d.plusSeconds(1)).filter(d -> d.compareTo(backoff) > 0).orElse(backoff);
    }

    /** {@code Retry-After} as seconds or an HTTP date. */
    java.util.Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(value -> {
            try {
                return java.util.Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip()))));
            } catch (NumberFormatException e) {
                try {
                    Instant at = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    Duration d = Duration.between(clock.instant(), at);
                    return java.util.Optional.of(d.isNegative() ? Duration.ZERO : d);
                } catch (DateTimeParseException ignored) {
                    return java.util.Optional.empty();
                }
            }
        }).map(d -> d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d);
    }

    /** Exponential back-off from {@code initial-backoff-ms}, with ±25% jitter, capped at two minutes. */
    Duration backoff(int attempt) {
        long base = settings.initialBackoff().toMillis() * (1L << Math.min(attempt, 16));
        long capped = Math.min(base, MAX_BACKOFF.toMillis());
        long jitter = (long) (capped * 0.25 * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
        return Duration.ofMillis(Math.max(1, capped + jitter));
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private GraphApiException error(int status, String what, String body, String location) {
        String code = null;
        String message = body == null ? "" : body.strip();
        try {
            JsonNode error = parse(body).path("error");
            if (error.isObject()) {
                code = text(error, "code");
                message = error.path("message").asText(message);
                JsonNode inner = error.path("innerError");
                String requestId = text(inner, "request-id");
                if (requestId != null) {
                    message = firstLine(message) + " (request-id " + requestId
                            + (text(inner, "client-request-id") != null ? ", client-request-id " + text(inner, "client-request-id") : "") + ")";
                }
            }
        } catch (IOException ignored) {
            // not JSON: keep the start of the body
        }
        if (message.length() > 300) {
            message = message.substring(0, 300) + "…";
        }
        String hint = status == 403 ? " (check that the app registration has Sites.Read.All or Sites.Selected access to this site)" : "";
        return new GraphApiException(status, code, what + " answered HTTP " + status + (code != null ? " " + code : "") + ": "
                + firstLine(message) + hint, location);
    }

    private JsonNode parse(String body) throws IOException {
        return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
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

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** Encodes every path segment, keeping the slashes. */
    static String encodePath(String path) {
        String p = path.startsWith("/") ? path : "/" + path;
        StringBuilder out = new StringBuilder();
        for (String segment : p.substring(1).split("/", -1)) {
            out.append('/').append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
