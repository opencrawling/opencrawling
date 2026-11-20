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
package org.opencrawling.alfresco;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.alfresco.AlfrescoProperties.AuthType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lightweight Java 25 HTTP client for Alfresco Content Services REST API v1 (Core, Search, and Authentication APIs).
 *
 * <p>Supports {@code basic}, {@code ticket} (with automatic 401 renewal and logout on close), {@code bearer}, and
 * {@code oauth2} (client-credentials grant or static bearer token) authentication, plus HTTP {@code 429}/{@code 502}/
 * {@code 503}/{@code 504} and transient I/O retries honoring {@code Retry-After} with jittered exponential backoff.
 */
public class AlfrescoClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoClient.class);

    private final AlfrescoProperties settings;
    private final HttpClient http;
    private final boolean ownsHttpClient;
    private final ObjectMapper mapper;
    private final AlfrescoOAuth2TokenSource oauth2TokenSource;
    private volatile String activeTicket;
    private volatile boolean ownsTicket;
    private volatile Instant lastServerTime;

    public AlfrescoClient(AlfrescoProperties settings) {
        this(settings,
                HttpClient.newBuilder()
                        .connectTimeout(settings.timeout())
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                new ObjectMapper(),
                true);
    }

    AlfrescoClient(AlfrescoProperties settings, HttpClient http, ObjectMapper mapper) {
        this(settings, http, mapper, false);
    }

    private AlfrescoClient(AlfrescoProperties settings, HttpClient http, ObjectMapper mapper, boolean ownsHttpClient) {
        this.settings = settings;
        this.http = http;
        this.ownsHttpClient = ownsHttpClient;
        this.mapper = mapper;
        if (settings.authType() == AuthType.OAUTH2
                && settings.oauth2AccessToken() == null
                && settings.oauth2TokenUrl() != null) {
            this.oauth2TokenSource = new AlfrescoOAuth2TokenSource(
                    settings.oauth2TokenUrl(),
                    settings.oauth2ClientId(),
                    settings.oauth2ClientSecret(),
                    settings.oauth2Scope(),
                    settings.timeout(),
                    http,
                    java.time.Clock.systemUTC());
        } else {
            this.oauth2TokenSource = null;
        }
        if (settings.authType() == AuthType.TICKET && settings.ticket() != null) {
            this.activeTicket = settings.ticket();
            this.ownsTicket = false;
        }
    }

    /** Server timestamp captured from the HTTP {@code Date} response header, or {@link Instant#now()} when absent. */
    public Instant serverTimeOrNow() {
        Instant t = lastServerTime;
        return t != null ? t : Instant.now();
    }

    /** Verifies connectivity to Alfresco by fetching {@code GET /nodes/-root-}. */
    public JsonNode checkConnection() throws IOException, InterruptedException {
        HttpResponse<String> response = sendString(
                HttpRequest.newBuilder(URI.create(settings.coreApiUrl() + "/nodes/-root-"))
                        .timeout(settings.timeout())
                        .header("Accept", "application/json")
                        .GET(),
                true);
        if (response.statusCode() != 200) {
            throw new AlfrescoApiException(response.statusCode(),
                    "Failed to connect to Alfresco Content Services. Status code: " + response.statusCode()
                            + ", Response: " + response.body(),
                    response.body());
        }
        return mapper.readTree(response.body());
    }

    /** Fetches a single node by ID (or alias {@code -root-}) with optional {@code relativePath}; returns empty on 404. */
    public Optional<JsonNode> getNode(String nodeId, String include, String relativePath)
            throws IOException, InterruptedException {
        StringBuilder uri = new StringBuilder(settings.coreApiUrl())
                .append("/nodes/").append(encodePathSegment(nodeId));
        String sep = "?";
        if (include != null && !include.isBlank()) {
            uri.append(sep).append("include=").append(include);
            sep = "&";
        }
        if (relativePath != null && !relativePath.isBlank()) {
            uri.append(sep).append("relativePath=").append(encodeQuery(relativePath));
        }
        HttpResponse<String> response = sendString(
                HttpRequest.newBuilder(URI.create(uri.toString()))
                        .timeout(settings.timeout())
                        .header("Accept", "application/json")
                        .GET(),
                true);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new AlfrescoApiException(response.statusCode(),
                    "Alfresco returned HTTP " + response.statusCode() + " for node '" + nodeId + "'.",
                    response.body());
        }
        return Optional.of(mapper.readTree(response.body()).path("entry"));
    }

    /** Fetches a page of children of {@code nodeId}. */
    public JsonNode listChildren(String nodeId, String relativePath, int skipCount, int maxItems, String include)
            throws IOException, InterruptedException {
        StringBuilder uri = new StringBuilder(settings.coreApiUrl())
                .append("/nodes/").append(encodePathSegment(nodeId))
                .append("/children?skipCount=").append(skipCount)
                .append("&maxItems=").append(maxItems);
        if (include != null && !include.isBlank()) {
            uri.append("&include=").append(include);
        }
        if (relativePath != null && !relativePath.isBlank()) {
            uri.append("&relativePath=").append(encodeQuery(relativePath));
        }
        HttpResponse<String> response = sendString(
                HttpRequest.newBuilder(URI.create(uri.toString()))
                        .timeout(settings.timeout())
                        .header("Accept", "application/json")
                        .GET(),
                true);
        if (response.statusCode() != 200) {
            throw new AlfrescoApiException(response.statusCode(),
                    "Failed to fetch children for node: " + nodeId + ", relativePath: " + relativePath
                            + ". Status code: " + response.statusCode(),
                    response.body());
        }
        return mapper.readTree(response.body());
    }

    /** Executes an Alfresco Search REST API v1 query ({@code POST /public/search/versions/1/search}). */
    public JsonNode search(String query, String language, int skipCount, int maxItems,
                           List<String> include, List<Map<String, Object>> sort)
            throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("query", Map.of("language", language, "query", query));
        body.put("paging", Map.of("maxItems", maxItems, "skipCount", skipCount));
        if (include != null && !include.isEmpty()) {
            body.put("include", include);
        }
        if (sort != null && !sort.isEmpty()) {
            body.put("sort", sort);
        }
        String jsonBody = mapper.writeValueAsString(body);
        HttpResponse<String> response = sendString(
                HttpRequest.newBuilder(URI.create(settings.resolveSearchUrl()))
                        .timeout(settings.timeout())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)),
                true);
        if (response.statusCode() != 200) {
            throw new AlfrescoApiException(response.statusCode(),
                    "Alfresco search query failed (status " + response.statusCode() + "): " + response.body(),
                    response.body());
        }
        return mapper.readTree(response.body());
    }

    /** Lists archived nodes in the Trashcan ({@code GET /deleted-nodes}), returning empty on 403/404. */
    public Optional<JsonNode> listDeletedNodes(int skipCount, int maxItems) throws IOException, InterruptedException {
        String uri = settings.coreApiUrl() + "/deleted-nodes?skipCount=" + skipCount + "&maxItems=" + maxItems;
        HttpResponse<String> response = sendString(
                HttpRequest.newBuilder(URI.create(uri))
                        .timeout(settings.timeout())
                        .header("Accept", "application/json")
                        .GET(),
                true);
        if (response.statusCode() == 403 || response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new AlfrescoApiException(response.statusCode(),
                    "Alfresco deleted-nodes query failed (status " + response.statusCode() + ").",
                    response.body());
        }
        return Optional.of(mapper.readTree(response.body()));
    }

    /** Lists audit entries from an Alfresco audit application, returning empty on 403/404/501. */
    public Optional<JsonNode> listAuditEntries(String applicationId, String whereClause, int skipCount, int maxItems)
            throws IOException, InterruptedException {
        StringBuilder uri = new StringBuilder(settings.coreApiUrl())
                .append("/audit-applications/").append(encodePathSegment(applicationId))
                .append("/audit-entries?skipCount=").append(skipCount)
                .append("&maxItems=").append(maxItems)
                .append("&include=values");
        if (whereClause != null && !whereClause.isBlank()) {
            uri.append("&where=").append(encodeQuery(whereClause));
        }
        HttpResponse<String> response = sendString(
                HttpRequest.newBuilder(URI.create(uri.toString()))
                        .timeout(settings.timeout())
                        .header("Accept", "application/json")
                        .GET(),
                true);
        if (response.statusCode() == 403 || response.statusCode() == 404 || response.statusCode() == 501) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new AlfrescoApiException(response.statusCode(),
                    "Alfresco audit query failed (status " + response.statusCode() + ").",
                    response.body());
        }
        return Optional.of(mapper.readTree(response.body()));
    }

    /** Lists all versions of a node ({@code GET /nodes/{nodeId}/versions}). */
    public List<JsonNode> listVersions(String nodeId) throws IOException, InterruptedException {
        List<JsonNode> all = new ArrayList<>();
        int skipCount = 0;
        boolean hasMore = true;
        while (hasMore) {
            String uri = settings.coreApiUrl() + "/nodes/" + encodePathSegment(nodeId)
                    + "/versions?skipCount=" + skipCount + "&maxItems=" + settings.batchSize()
                    + "&include=properties";
            HttpResponse<String> response = sendString(
                    HttpRequest.newBuilder(URI.create(uri))
                            .timeout(settings.timeout())
                            .header("Accept", "application/json")
                            .GET(),
                    true);
            if (response.statusCode() == 404) {
                return List.of();
            }
            if (response.statusCode() != 200) {
                throw new AlfrescoApiException(response.statusCode(),
                        "Alfresco versions query failed for node '" + nodeId + "' (status " + response.statusCode() + ").",
                        response.body());
            }
            JsonNode list = mapper.readTree(response.body()).path("list");
            JsonNode entries = list.path("entries");
            int count = 0;
            if (entries.isArray()) {
                for (JsonNode wrapper : entries) {
                    all.add(wrapper.path("entry"));
                    count++;
                }
            }
            hasMore = count > 0 && list.path("pagination").path("hasMoreItems").asBoolean(false);
            skipCount += count;
        }
        return all;
    }

    /** Lists tags for a node ({@code GET /nodes/{nodeId}/tags}). */
    public List<String> listTags(String nodeId) throws IOException, InterruptedException {
        String uri = settings.coreApiUrl() + "/nodes/" + encodePathSegment(nodeId) + "/tags?maxItems=200";
        HttpResponse<String> response = sendString(
                HttpRequest.newBuilder(URI.create(uri))
                        .timeout(settings.timeout())
                        .header("Accept", "application/json")
                        .GET(),
                true);
        if (response.statusCode() == 404 || response.statusCode() == 403) {
            return List.of();
        }
        if (response.statusCode() != 200) {
            throw new AlfrescoApiException(response.statusCode(),
                    "Alfresco tags query failed for node '" + nodeId + "' (status " + response.statusCode() + ").",
                    response.body());
        }
        List<String> tags = new ArrayList<>();
        JsonNode entries = mapper.readTree(response.body()).path("list").path("entries");
        if (entries.isArray()) {
            for (JsonNode wrapper : entries) {
                String tag = wrapper.path("entry").path("tag").asText("");
                if (!tag.isBlank()) {
                    tags.add(tag);
                }
            }
        }
        return tags;
    }

    /** Lists comments on a node ({@code GET /nodes/{nodeId}/comments}). */
    public List<String> listComments(String nodeId) throws IOException, InterruptedException {
        String uri = settings.coreApiUrl() + "/nodes/" + encodePathSegment(nodeId) + "/comments?maxItems=200";
        HttpResponse<String> response = sendString(
                HttpRequest.newBuilder(URI.create(uri))
                        .timeout(settings.timeout())
                        .header("Accept", "application/json")
                        .GET(),
                true);
        if (response.statusCode() == 404 || response.statusCode() == 403) {
            return List.of();
        }
        if (response.statusCode() != 200) {
            throw new AlfrescoApiException(response.statusCode(),
                    "Alfresco comments query failed for node '" + nodeId + "' (status " + response.statusCode() + ").",
                    response.body());
        }
        List<String> comments = new ArrayList<>();
        JsonNode entries = mapper.readTree(response.body()).path("list").path("entries");
        if (entries.isArray()) {
            for (JsonNode wrapper : entries) {
                String text = wrapper.path("entry").path("content").asText("");
                if (!text.isBlank()) {
                    comments.add(text);
                }
            }
        }
        return comments;
    }

    /**
     * Checks whether a rendition is available ({@code status == CREATED}) and returns its MIME type if known.
     */
    public Optional<String> renditionMimeTypeIfCreated(String nodeId, String renditionId)
            throws IOException, InterruptedException {
        String uri = settings.coreApiUrl() + "/nodes/" + encodePathSegment(nodeId)
                + "/renditions/" + encodePathSegment(renditionId);
        HttpResponse<String> response = sendString(
                HttpRequest.newBuilder(URI.create(uri))
                        .timeout(settings.timeout())
                        .header("Accept", "application/json")
                        .GET(),
                true);
        if (response.statusCode() != 200) {
            return Optional.empty();
        }
        JsonNode entry = mapper.readTree(response.body()).path("entry");
        if (!"CREATED".equalsIgnoreCase(entry.path("status").asText(""))) {
            return Optional.empty();
        }
        String mime = entry.path("content").path("mimeType").asText("");
        return Optional.of(mime.isBlank() ? defaultRenditionMime(renditionId) : mime);
    }

    /**
     * Downloads primary content, a specific version's content, or a rendition into a size-capped
     * {@link TempFileInputStream} so the Alfresco HTTP connection is released immediately.
     */
    public InputStream downloadContent(String nodeId, String versionId, String renditionId, long maxBytes)
            throws IOException, InterruptedException {
        String uri;
        if (renditionId != null && !renditionId.isBlank()) {
            uri = settings.coreApiUrl() + "/nodes/" + encodePathSegment(nodeId)
                    + "/renditions/" + encodePathSegment(renditionId) + "/content";
        } else if (versionId != null && !versionId.isBlank()) {
            uri = settings.coreApiUrl() + "/nodes/" + encodePathSegment(nodeId)
                    + "/versions/" + encodePathSegment(versionId) + "/content";
        } else {
            uri = settings.coreApiUrl() + "/nodes/" + encodePathSegment(nodeId) + "/content";
        }

        HttpResponse<InputStream> response = sendStream(
                HttpRequest.newBuilder(URI.create(uri))
                        .timeout(settings.timeout())
                        .GET(),
                true);
        if (response.statusCode() != 200) {
            try (InputStream err = response.body()) {
                // drain/close error stream
            } catch (IOException ignored) {
            }
            throw new AlfrescoApiException(response.statusCode(),
                    "Failed to download document content for node: " + nodeId + ", status code: " + response.statusCode(),
                    "");
        }
        InputStream body = response.body();
        if (body == null) {
            return null;
        }
        return TempFileInputStream.copyBounded(body, maxBytes);
    }

    // ------------------------------------------------------------------ HTTP Send, Auth & Retry

    private HttpResponse<String> sendString(HttpRequest.Builder template, boolean allowAuthRefresh)
            throws IOException, InterruptedException {
        int attempts = 0;
        int maxAttempts = Math.max(1, settings.maxRetries() + 1);
        boolean refreshedAuth = false;
        while (true) {
            attempts++;
            HttpRequest request = withAuth(template).build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                captureServerTime(response);
                int status = response.statusCode();
                if (status == 401 && allowAuthRefresh && !refreshedAuth && canRefreshAuth()) {
                    invalidateAuth();
                    refreshedAuth = true;
                    attempts--;
                    continue;
                }
                if (isRetryableStatus(status) && attempts < maxAttempts) {
                    long delayMs = computeRetryDelayMs(response, attempts);
                    log.warn("Alfresco API returned HTTP {} on {} (attempt {}/{}); retrying in {} ms.",
                            status, request.uri().getPath(), attempts, maxAttempts, delayMs);
                    Thread.sleep(delayMs);
                    continue;
                }
                return response;
            } catch (IOException e) {
                if (attempts < maxAttempts) {
                    long delayMs = backoffMs(attempts);
                    log.warn("Alfresco API I/O error on {} (attempt {}/{}): {}; retrying in {} ms.",
                            request.uri().getPath(), attempts, maxAttempts, e.getMessage(), delayMs);
                    Thread.sleep(delayMs);
                    continue;
                }
                throw e;
            }
        }
    }

    private HttpResponse<InputStream> sendStream(HttpRequest.Builder template, boolean allowAuthRefresh)
            throws IOException, InterruptedException {
        int attempts = 0;
        int maxAttempts = Math.max(1, settings.maxRetries() + 1);
        boolean refreshedAuth = false;
        while (true) {
            attempts++;
            HttpRequest request = withAuth(template).build();
            try {
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                captureServerTime(response);
                int status = response.statusCode();
                if (status == 401 && allowAuthRefresh && !refreshedAuth && canRefreshAuth()) {
                    closeQuietly(response.body());
                    invalidateAuth();
                    refreshedAuth = true;
                    attempts--;
                    continue;
                }
                if (isRetryableStatus(status) && attempts < maxAttempts) {
                    closeQuietly(response.body());
                    long delayMs = computeRetryDelayMs(response, attempts);
                    log.warn("Alfresco binary download returned HTTP {} on {} (attempt {}/{}); retrying in {} ms.",
                            status, request.uri().getPath(), attempts, maxAttempts, delayMs);
                    Thread.sleep(delayMs);
                    continue;
                }
                return response;
            } catch (IOException e) {
                if (attempts < maxAttempts) {
                    long delayMs = backoffMs(attempts);
                    log.warn("Alfresco binary download I/O error on {} (attempt {}/{}): {}; retrying in {} ms.",
                            request.uri().getPath(), attempts, maxAttempts, e.getMessage(), delayMs);
                    Thread.sleep(delayMs);
                    continue;
                }
                throw e;
            }
        }
    }

    private HttpRequest.Builder withAuth(HttpRequest.Builder template) throws IOException, InterruptedException {
        HttpRequest.Builder copy = template.copy();
        String header = resolveAuthHeader();
        if (header != null && !header.isBlank()) {
            copy.setHeader("Authorization", header);
        }
        return copy;
    }

    private String resolveAuthHeader() throws IOException, InterruptedException {
        return switch (settings.authType()) {
            case BASIC -> {
                String creds = (settings.username() == null ? "" : settings.username()) + ":"
                        + (settings.password() == null ? "" : settings.password());
                yield "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
            }
            case TICKET -> {
                String t = ensureTicket();
                yield "Basic " + Base64.getEncoder().encodeToString(t.getBytes(StandardCharsets.UTF_8));
            }
            case BEARER -> "Bearer " + settings.oauth2AccessToken();
            case OAUTH2 -> {
                String token = oauth2TokenSource != null ? oauth2TokenSource.get() : settings.oauth2AccessToken();
                yield "Bearer " + token;
            }
        };
    }

    private synchronized String ensureTicket() throws IOException, InterruptedException {
        if (activeTicket != null && !activeTicket.isBlank()) {
            return activeTicket;
        }
        String payload = mapper.writeValueAsString(Map.of(
                "userId", settings.username() == null ? "" : settings.username(),
                "password", settings.password() == null ? "" : settings.password()));
        HttpRequest req = HttpRequest.newBuilder(URI.create(settings.resolveTicketsUrl()))
                .timeout(settings.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new AlfrescoApiException(resp.statusCode(),
                    "Failed to obtain Alfresco authentication ticket (status " + resp.statusCode() + ").",
                    resp.body());
        }
        String id = mapper.readTree(resp.body()).path("entry").path("id").asText("");
        if (id.isBlank()) {
            throw new IOException("Alfresco ticket endpoint returned no ticket id.");
        }
        this.activeTicket = id;
        this.ownsTicket = true;
        return id;
    }

    private boolean canRefreshAuth() {
        return oauth2TokenSource != null
                || (settings.authType() == AuthType.TICKET && settings.username() != null && settings.password() != null);
    }

    private synchronized void invalidateAuth() {
        if (oauth2TokenSource != null) {
            oauth2TokenSource.invalidate();
        }
        if (settings.authType() == AuthType.TICKET) {
            activeTicket = null;
        }
    }

    private void captureServerTime(HttpResponse<?> response) {
        if (response == null || response.headers() == null) {
            return;
        }
        response.headers().firstValue("Date").ifPresent(raw -> {
            try {
                lastServerTime = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeParseException ignored) {
            }
        });
    }

    private static boolean isRetryableStatus(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private long computeRetryDelayMs(HttpResponse<?> response, int attempt) {
        Optional<String> retryAfter = response.headers() != null
                ? response.headers().firstValue("Retry-After")
                : Optional.empty();
        if (retryAfter.isPresent()) {
            String raw = retryAfter.get().strip();
            try {
                long seconds = Long.parseLong(raw);
                if (seconds >= 0) {
                    return Math.min(seconds * 1000L, 60_000L);
                }
            } catch (NumberFormatException ignored) {
                try {
                    Instant target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    long diff = Duration.between(Instant.now(), target).toMillis();
                    if (diff > 0) {
                        return Math.min(diff, 60_000L);
                    }
                } catch (DateTimeParseException ignored2) {
                }
            }
        }
        return backoffMs(attempt);
    }

    private long backoffMs(int attempt) {
        long base = settings.initialBackoffMs() * (1L << Math.min(attempt - 1, 6));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1L, base / 2));
        return Math.min(base + jitter, 30_000L);
    }

    private static String defaultRenditionMime(String renditionId) {
        return switch (renditionId.toLowerCase()) {
            case "pdf" -> "application/pdf";
            case "imgpreview", "doclib" -> "image/png";
            default -> "";
        };
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String encodePathSegment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (ownsTicket && activeTicket != null) {
            try {
                String encoded = Base64.getEncoder().encodeToString(activeTicket.getBytes(StandardCharsets.UTF_8));
                HttpRequest logout = HttpRequest.newBuilder(URI.create(settings.resolveTicketsUrl() + "/-me-"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Basic " + encoded)
                        .DELETE()
                        .build();
                http.send(logout, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
            } finally {
                activeTicket = null;
                ownsTicket = false;
            }
        }
        if (oauth2TokenSource != null) {
            oauth2TokenSource.close();
        }
        if (ownsHttpClient && http != null) {
            http.close();
        }
    }
}
