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
package org.opencrawling.zammad;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Lightweight HTTP client for the Zammad REST API v1 ({@code /api/v1/...}) built on Java 25 {@link HttpClient}.
 *
 * <p>Supports Zammad HTTP Access Token ({@code Authorization: Token token=...}), OAuth2 Bearer
 * ({@code Authorization: Bearer ...}), and Basic authentication, plus optional {@code X-On-Behalf-Of} user
 * delegation. Transient failures and HTTP {@code 429}/{@code 502}/{@code 503}/{@code 504} responses are retried
 * honoring {@code Retry-After} with exponential backoff.
 */
public class ZammadClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZammadClient.class);

    private final ZammadProperties settings;
    private final String baseUrl;
    private final Duration timeout;
    private final int maxRetries;
    private final long initialBackoffMs;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String authorizationHeader;

    public ZammadClient(ZammadProperties settings) {
        this(settings,
                HttpClient.newBuilder()
                        .connectTimeout(settings.timeout())
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                new ObjectMapper());
    }

    public ZammadClient(ZammadProperties settings, HttpClient http, ObjectMapper mapper) {
        this.settings = settings;
        this.baseUrl = settings.baseUrl();
        this.timeout = settings.timeout();
        this.maxRetries = settings.maxRetries();
        this.initialBackoffMs = settings.initialBackoffMs();
        this.http = http;
        this.mapper = mapper;
        this.authorizationHeader = buildAuthorizationHeader(settings);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    private static String buildAuthorizationHeader(ZammadProperties settings) {
        return switch (settings.authType()) {
            case TOKEN -> "Token token=" + (settings.accessToken() == null ? "" : settings.accessToken());
            case OAUTH2 -> "Bearer " + (settings.accessToken() == null ? "" : settings.accessToken());
            case BASIC -> {
                String raw = (settings.username() == null ? "" : settings.username())
                        + ":" + (settings.password() == null ? "" : settings.password());
                yield "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    // ------------------------------------------------------------------ Identity & Structure

    /** {@code GET /api/v1/users/me}: validates authentication and returns the current user profile. */
    public JsonNode getCurrentUser() throws IOException, InterruptedException {
        return getJson("/api/v1/users/me");
    }

    /** {@code GET /api/v1/groups}: lists all Zammad groups. */
    public List<JsonNode> listGroups() throws IOException, InterruptedException {
        return items(getJson("/api/v1/groups"));
    }

    /** {@code GET /api/v1/roles}: lists all Zammad roles and their group permissions. */
    public List<JsonNode> listRoles() throws IOException, InterruptedException {
        try {
            return items(getJson("/api/v1/roles"));
        } catch (ZammadApiException e) {
            if (e.isUnauthorized() || e.isNotFound()) {
                log.debug("Zammad /api/v1/roles not readable (HTTP {}); relying on group and ticket metadata.", e.status());
                return List.of();
            }
            throw e;
        }
    }

    /** {@code GET /api/v1/ticket_states}: lists ticket state definitions ({@code id}, {@code name}). */
    public List<JsonNode> listTicketStates() throws IOException, InterruptedException {
        try {
            return items(getJson("/api/v1/ticket_states"));
        } catch (ZammadApiException e) {
            if (e.isUnauthorized() || e.isNotFound()) {
                return List.of();
            }
            throw e;
        }
    }

    /** {@code GET /api/v1/ticket_priorities}: lists ticket priority definitions ({@code id}, {@code name}). */
    public List<JsonNode> listTicketPriorities() throws IOException, InterruptedException {
        try {
            return items(getJson("/api/v1/ticket_priorities"));
        } catch (ZammadApiException e) {
            if (e.isUnauthorized() || e.isNotFound()) {
                return List.of();
            }
            throw e;
        }
    }

    /** {@code GET /api/v1/users/{id}}: retrieves a Zammad user (e.g. customer or owner). */
    public Optional<JsonNode> getUser(long userId) throws IOException, InterruptedException {
        if (userId <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(getJson("/api/v1/users/" + userId));
        } catch (ZammadApiException e) {
            if (e.isNotFound() || e.isUnauthorized()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** {@code GET /api/v1/organizations/{id}}: retrieves a Zammad organization (including {@code shared} flag). */
    public Optional<JsonNode> getOrganization(long orgId) throws IOException, InterruptedException {
        if (orgId <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(getJson("/api/v1/organizations/" + orgId));
        } catch (ZammadApiException e) {
            if (e.isNotFound() || e.isUnauthorized()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ Tickets, Articles, Tags & Attachments

    /** {@code GET /api/v1/tickets?page={page}&per_page={perPage}&expand=true}: lists a page of tickets. */
    public List<JsonNode> listTicketsPage(int page, int perPage) throws IOException, InterruptedException {
        JsonNode result = getJson("/api/v1/tickets?page=" + page + "&per_page=" + perPage + "&expand=true");
        return extractTickets(result);
    }

    /**
     * {@code GET /api/v1/tickets/search?query=...&sort_by=updated_at&order_by=asc&page=...&per_page=...&expand=true}:
     * searches tickets matching a Zammad search expression.
     *
     * <p>Supports both direct array responses and Zammad's {@code assets.Ticket} envelope response.
     */
    public List<JsonNode> searchTicketsPage(String query, int page, int perPage) throws IOException, InterruptedException {
        String q = (query == null || query.isBlank()) ? "*" : query.strip();
        String path = "/api/v1/tickets/search?query=" + enc(q)
                + "&sort_by=updated_at&order_by=asc&page=" + page + "&per_page=" + perPage + "&expand=true";
        JsonNode result = getJson(path);
        return extractTickets(result);
    }

    private List<JsonNode> extractTickets(JsonNode result) throws IOException, InterruptedException {
        if (result == null || result.isNull() || result.isMissingNode()) {
            return List.of();
        }
        if (result.isArray()) {
            return items(result);
        }
        List<JsonNode> tickets = new ArrayList<>();
        JsonNode assetsTickets = result.path("assets").path("Ticket");
        JsonNode ticketIds = result.path("tickets");
        if (ticketIds.isArray()) {
            for (JsonNode idNode : ticketIds) {
                if (idNode.isObject()) {
                    tickets.add(idNode);
                } else {
                    String idStr = idNode.asText("");
                    if (!idStr.isBlank() && assetsTickets.has(idStr)) {
                        tickets.add(assetsTickets.path(idStr));
                    } else if (idNode.canConvertToLong()) {
                        getTicket(idNode.asLong()).ifPresent(tickets::add);
                    }
                }
            }
            return tickets;
        }
        if (assetsTickets.isObject()) {
            assetsTickets.forEach(tickets::add);
            return tickets;
        }
        return items(result);
    }

    /** {@code GET /api/v1/tickets/{id}?expand=true}: retrieves a single ticket by ID. */
    public Optional<JsonNode> getTicket(long ticketId) throws IOException, InterruptedException {
        try {
            return Optional.of(getJson("/api/v1/tickets/" + ticketId + "?expand=true"));
        } catch (ZammadApiException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** {@code GET /api/v1/ticket_articles/by_ticket/{ticketId}}: retrieves all articles for a ticket. */
    public List<JsonNode> getTicketArticles(long ticketId) throws IOException, InterruptedException {
        try {
            return items(getJson("/api/v1/ticket_articles/by_ticket/" + ticketId));
        } catch (ZammadApiException e) {
            if (e.isNotFound()) {
                return List.of();
            }
            throw e;
        }
    }

    /** {@code GET /api/v1/tags?object=Ticket&o_id={ticketId}}: retrieves the tag names assigned to a ticket. */
    public List<String> getTicketTags(long ticketId) throws IOException, InterruptedException {
        try {
            JsonNode node = getJson("/api/v1/tags?object=Ticket&o_id=" + ticketId);
            JsonNode array = node.isArray() ? node : node.path("tags");
            if (!array.isArray()) {
                return List.of();
            }
            List<String> tags = new ArrayList<>();
            for (JsonNode tag : array) {
                String value = tag.isObject() ? tag.path("name").asText("") : tag.asText("");
                if (!value.isBlank()) {
                    tags.add(value.strip());
                }
            }
            return List.copyOf(tags);
        } catch (ZammadApiException e) {
            if (e.isNotFound() || e.isUnauthorized()) {
                return List.of();
            }
            throw e;
        }
    }

    /**
     * Streams {@code GET /api/v1/ticket_attachment/{ticketId}/{articleId}/{attachmentId}} into {@code target}.
     *
     * @return the number of bytes written, or {@code -1} if the attachment exceeds {@code maxBytes}
     */
    public long downloadAttachment(long ticketId, long articleId, long attachmentId, Path target, long maxBytes)
            throws IOException, InterruptedException {
        URI uri = uri("/api/v1/ticket_attachment/" + ticketId + "/" + articleId + "/" + attachmentId);
        HttpResponse<InputStream> response = sendWithRetries(request(uri, "*/*"), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ZammadApiException(response.statusCode(),
                        "GET " + uri.getRawPath() + " answered HTTP " + response.statusCode()
                                + " " + excerpt(new String(in.readNBytes(4096), StandardCharsets.UTF_8)));
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (maxBytes > 0 && declared > maxBytes) {
                return -1L;
            }
            long written = 0L;
            byte[] buffer = new byte[64 * 1024];
            try (OutputStream out = Files.newOutputStream(target)) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    written += n;
                    if (maxBytes > 0 && written > maxBytes) {
                        out.close();
                        Files.deleteIfExists(target);
                        return -1L;
                    }
                    out.write(buffer, 0, n);
                }
            }
            return written;
        }
    }

    // ------------------------------------------------------------------ Knowledge Base

    /**
     * {@code GET /api/v1/knowledge_bases/init}: retrieves the Zammad Knowledge Base structure (knowledge bases,
     * categories, permissions, locales, and answer descriptors). Returns empty when Knowledge Base is not enabled.
     */
    public Optional<JsonNode> getKnowledgeBaseInit() throws IOException, InterruptedException {
        try {
            JsonNode json = getJson("/api/v1/knowledge_bases/init");
            if (json == null || json.isNull() || json.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(json);
        } catch (ZammadApiException e) {
            if (e.isNotFound() || e.isUnauthorized()) {
                log.debug("Zammad Knowledge Base endpoint /api/v1/knowledge_bases/init answered HTTP {}; skipping KB.", e.status());
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * {@code GET /api/v1/knowledge_bases/{kbId}/answers/{answerId}?include_contents=true}: retrieves a Knowledge Base
     * answer including its localized translations and body content.
     */
    public Optional<JsonNode> getKnowledgeBaseAnswer(long kbId, long answerId) throws IOException, InterruptedException {
        try {
            return Optional.of(getJson("/api/v1/knowledge_bases/" + kbId + "/answers/" + answerId + "?include_contents=true"));
        } catch (ZammadApiException e) {
            if (e.isNotFound() || e.isUnauthorized()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ HTTP Plumbing

    public JsonNode getJson(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = sendWithRetries(
                request(uri(path), "application/json"),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ZammadApiException(response.statusCode(),
                    "GET " + stripQuery(path) + " answered HTTP " + response.statusCode() + " " + excerpt(response.body()));
        }
        String body = response.body();
        return (body == null || body.isBlank()) ? mapper.createObjectNode() : mapper.readTree(body);
    }

    private HttpRequest request(URI uri, String accept) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", accept)
                .header("Authorization", authorizationHeader)
                .GET();
        if (settings.onBehalfOf() != null && !settings.onBehalfOf().isBlank()) {
            builder.header("X-On-Behalf-Of", settings.onBehalfOf());
        }
        return builder.build();
    }

    private <T> HttpResponse<T> sendWithRetries(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<T> response = http.send(request, handler);
                int status = response.statusCode();
                if ((status == 429 || status == 502 || status == 503 || status == 504) && attempt < maxRetries) {
                    long retryAfterSeconds = response.headers().firstValueAsLong("Retry-After").orElse(-1L);
                    discard(response);
                    pause(attempt, retryAfterSeconds);
                    continue;
                }
                return response;
            } catch (IOException e) {
                if (attempt >= maxRetries) {
                    throw new IOException(request.method() + " " + stripQuery(request.uri().getRawPath())
                            + " failed after " + (attempt + 1) + " attempt(s): " + e.getMessage(), e);
                }
                pause(attempt, -1L);
            }
        }
    }

    private void pause(int attempt, long retryAfterSeconds) throws InterruptedException {
        long delayMs;
        if (retryAfterSeconds > 0) {
            delayMs = Math.min(retryAfterSeconds * 1000L, 60_000L);
        } else {
            long exp = initialBackoffMs * (1L << Math.min(attempt, 5));
            delayMs = Math.min(exp, 30_000L);
        }
        Thread.sleep(delayMs);
    }

    private static void discard(HttpResponse<?> response) {
        if (response.body() instanceof InputStream in) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static List<JsonNode> items(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        JsonNode array = node.isArray() ? node : node.has("items") ? node.path("items") : node.path("tickets");
        if (!array.isArray()) {
            return List.of();
        }
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    public static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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

    @Override
    public void close() {
        // Stateless HTTP token/bearer/basic auth; nothing to close.
    }
}
