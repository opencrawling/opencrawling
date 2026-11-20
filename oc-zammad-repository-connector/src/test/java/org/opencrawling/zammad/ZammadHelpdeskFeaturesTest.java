/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.connector.discovery.FetchedDocument;
import org.opencrawling.core.connector.discovery.VisibilityFilter;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ticket-level documents (#411), article filters (#412), selection filters (#413), participants and privacy (#414)
 * and periodic full scans (#415) against an in-process Zammad API.
 */
class ZammadHelpdeskFeaturesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final List<Map<String, Object>> tickets = new CopyOnWriteArrayList<>();
    private final Map<Long, List<Map<String, Object>>> articles = new ConcurrentHashMap<>();
    private final Map<Long, List<String>> tags = new ConcurrentHashMap<>();
    private final List<String> ticketRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/api/v1/users/me", ex -> respond(ex, 200,
                Map.of("id", 2, "login", "crawler@example.com", "email", "crawler@example.com")));
        server.createContext("/api/v1/users/", ex -> {
            String id = ex.getRequestURI().getPath().substring("/api/v1/users/".length());
            switch (id) {
                case "101" -> respond(ex, 200, Map.of("id", 101, "email", "jane.doe@example.com",
                        "firstname", "Jane", "lastname", "Doe"));
                case "202" -> respond(ex, 200, Map.of("id", 202, "email", "mark.agent@example.com",
                        "firstname", "Mark", "lastname", "Agent"));
                default -> respond(ex, 404, Map.of("error", "not found"));
            }
        });
        server.createContext("/api/v1/groups", ex -> respond(ex, 200,
                List.of(Map.of("id", 4, "name", "Support", "active", true))));
        server.createContext("/api/v1/ticket_states", ex -> respond(ex, 200,
                List.of(Map.of("id", 2, "name", "open"), Map.of("id", 4, "name", "closed"))));
        server.createContext("/api/v1/ticket_priorities", ex -> respond(ex, 200,
                List.of(Map.of("id", 2, "name", "2 normal"), Map.of("id", 3, "name", "3 high"))));
        server.createContext("/api/v1/roles", ex -> respond(ex, 200,
                List.of(Map.of("id", 1, "name", "Agent", "active", true))));
        server.createContext("/api/v1/organizations/301", ex -> respond(ex, 200,
                Map.of("id", 301, "name", "Example Org", "shared", true)));
        server.createContext("/api/v1/tickets", ex -> {
            String path = ex.getRequestURI().getPath();
            ticketRequests.add(path + (ex.getRequestURI().getRawQuery() == null ? "" : "?"
                    + URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8)));
            if (path.equals("/api/v1/tickets") || path.equals("/api/v1/tickets/search")) {
                String query = ex.getRequestURI().getRawQuery();
                boolean firstPage = query == null || query.contains("page=1&") || query.endsWith("page=1");
                respond(ex, 200, firstPage ? new ArrayList<>(tickets) : List.of());
            } else {
                long id = Long.parseLong(path.substring("/api/v1/tickets/".length()));
                tickets.stream().filter(t -> ((Number) t.get("id")).longValue() == id).findFirst()
                        .ifPresentOrElse(t -> respond(ex, 200, t), () -> respond(ex, 404, Map.of("error", "not found")));
            }
        });
        server.createContext("/api/v1/ticket_articles/by_ticket/", ex -> {
            long id = Long.parseLong(ex.getRequestURI().getPath().substring("/api/v1/ticket_articles/by_ticket/".length()));
            respond(ex, 200, articles.getOrDefault(id, List.of()));
        });
        server.createContext("/api/v1/tags", ex -> {
            String query = ex.getRequestURI().getRawQuery();
            long id = Long.parseLong(query.substring(query.indexOf("o_id=") + 5));
            respond(ex, 200, Map.of("tags", tags.getOrDefault(id, List.of())));
        });
        server.createContext("/api/v1/ticket_attachment/", ex -> {
            byte[] body = "ATTACHMENT".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        tickets.add(ticket(4821, "VPN drops every hour", "2026-10-18T08:00:00Z", "2026-10-19T10:00:00Z"));
        articles.put(4821L, new CopyOnWriteArrayList<>(List.of(
                article(1001, "Customer", "web", false, 101, "Jane Doe <jane.doe@example.com>",
                        "The VPN disconnects every hour.", "2026-10-18T08:00:00Z"),
                article(1002, "Agent", "email", false, 202, "Mark Agent <mark.agent@example.com>",
                        "Please send the client log.", "2026-10-18T09:00:00Z"),
                article(1003, "Agent", "note", true, 202, "Mark Agent",
                        "Root cause: DHCP lease of 60 minutes on the gateway.", "2026-10-18T10:00:00Z"),
                article(1004, "System", "note", false, 1, "System",
                        "Ticket escalated: SLA breached.", "2026-10-18T11:00:00Z"),
                withAutoReply(article(1005, "Customer", "email", false, 101, "Jane Doe <jane.doe@example.com>",
                        "I am out of office until Monday.", "2026-10-18T12:00:00Z")))));
        tags.put(4821L, List.of("vpn"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void ticketGranularityEmitsOneGroupOnlyConversationWithRolesAndParticipants(@TempDir Path tempDir) throws Exception {
        List<RepositoryDocument> docs = scan(config(tempDir, "articleGranularity", "TICKET"));

        assertThat(docs).extracting(RepositoryDocument::id)
                .containsExactly("zammad://127.0.0.1:" + server.getAddress().getPort() + "/tickets/4821");
        RepositoryDocument doc = docs.getFirst();
        assertThat(doc.security().permissions()).extracting(PermissionRule::identity)
                .containsExactly("zammad-group-Support");
        assertThat(doc.metadata().get("zammad.entityType")).containsExactly("ticket_conversation");
        assertThat(doc.metadata().get("zammad.hasInternalNotes")).containsExactly("true");
        assertThat(doc.metadata().get("zammad.articleCount")).containsExactly("3");
        assertThat(doc.metadata().get("zammad.participants")).containsExactly("Jane Doe", "Mark Agent");

        String html = html(doc);
        assertThat(html).contains("data-role=\"customer\"", "data-role=\"agent\"", "data-role=\"internal\"")
                .contains("[Customer]", "[Agent]", "[Internal note]")
                .contains("Root cause: DHCP lease")
                .doesNotContain("Ticket escalated", "out of office");
        assertThat(html.indexOf("The VPN disconnects")).isLessThan(html.indexOf("Please send the client log"));
        assertThat(html.indexOf("Please send the client log")).isLessThan(html.indexOf("Root cause"));
    }

    @Test
    void ticketLevelDocumentCanBeFetchedAndRoleLabelsAreLocalizable(@TempDir Path tempDir) {
        ZammadRepositoryConnector connector = new ZammadRepositoryConnector(ZammadProperties.fromConfiguration(config(tempDir,
                "articleGranularity", "TICKET",
                "articleRoleLabels", "customer=Kunde,agent=Mitarbeiter,internal=Interne Notiz")));
        FetchedDocument fetched = connector.fetchDocument("ticket:4821", VisibilityFilter.ALL);
        assertThat(fetched).isInstanceOf(FetchedDocument.Found.class);
        String html = html(((FetchedDocument.Found) fetched).document());
        assertThat(html).contains("[Kunde]", "[Mitarbeiter]", "[Interne Notiz]");
    }

    @Test
    void switchingToTicketGranularityTombstonesTheIdsOfTheSplitDocuments(@TempDir Path tempDir) {
        String host = "zammad://127.0.0.1:" + server.getAddress().getPort() + "/tickets/4821";
        List<RepositoryDocument> split = scan(config(tempDir));
        assertThat(split).extracting(RepositoryDocument::id).containsExactlyInAnyOrder(host + "/public", host + "/internal");

        List<RepositoryDocument> ticket = scan(config(tempDir, "articleGranularity", "TICKET"));
        assertThat(ticket).filteredOn(d -> d.action() == DocumentAction.UPSERT)
                .extracting(RepositoryDocument::id).containsExactly(host);
        assertThat(ticket).filteredOn(d -> d.action() == DocumentAction.DELETE)
                .extracting(RepositoryDocument::id).containsExactlyInAnyOrder(host + "/public", host + "/internal");
    }

    @Test
    void aNewSystemArticleAloneDoesNotReindexTheTicket(@TempDir Path tempDir) {
        Map<String, String> config = config(tempDir);
        assertThat(scan(config)).hasSize(2);

        articles.get(4821L).add(article(1006, "System", "note", false, 1, "System",
                "Reminder: pending since 24 hours.", "2026-10-19T12:00:00Z"));
        tickets.set(0, ticket(4821, "VPN drops every hour", "2026-10-18T08:00:00Z", "2026-10-19T12:00:00Z"));

        assertThat(scan(config)).isEmpty();
    }

    @Test
    void systemArticlesAndAutoRepliesCanBeKeptOnRequest(@TempDir Path tempDir) throws Exception {
        List<RepositoryDocument> docs = scan(config(tempDir,
                "excludedArticleSenders", "",
                "skipAutoReplies", "false"));
        RepositoryDocument publicDoc = docs.stream().filter(d -> d.id().endsWith("/public")).findFirst().orElseThrow();
        assertThat(html(publicDoc)).contains("Ticket escalated", "out of office", "data-role=\"system\"");
    }

    @Test
    void selectionFiltersKeepStateAndTombstoneTicketsThatStopMatching(@TempDir Path tempDir) {
        tickets.add(ticket(4822, "Old printer issue", "2020-01-01T08:00:00Z", "2020-01-02T08:00:00Z"));
        tickets.add(ticket(4823, "[ALERT] disk full on srv-01", "2026-10-18T08:00:00Z", "2026-10-18T08:05:00Z"));
        tickets.add(ticket(4824, "Win a prize", "2026-10-18T08:00:00Z", "2026-10-18T08:05:00Z"));
        for (long id : List.of(4822L, 4823L, 4824L)) {
            articles.put(id, List.of(article(id * 10, "Customer", "email", false, 101, "x", "Body " + id, "2026-10-18T08:00:00Z")));
        }
        tags.put(4824L, List.of("Spam"));
        Map<String, String> config = config(tempDir,
                "createdAfter", "2025-01-01",
                "excludedTitlePatterns", "^\\[ALERT\\]",
                "excludedTags", "spam");

        List<RepositoryDocument> first = scan(config);
        assertThat(first).extracting(RepositoryDocument::id).allMatch(id -> id.contains("/tickets/4821/"));

        tags.put(4821L, List.of("vpn", "spam"));
        tickets.set(0, ticket(4821, "VPN drops every hour", "2026-10-18T08:00:00Z", "2026-10-20T08:00:00Z"));
        List<RepositoryDocument> second = scan(config);
        assertThat(second).isNotEmpty().allMatch(d -> d.action() == DocumentAction.DELETE);
        assertThat(second).extracting(RepositoryDocument::id).allMatch(id -> id.contains("/tickets/4821/"));
    }

    @Test
    void baseQueryIsCombinedWithTheWatermark(@TempDir Path tempDir) {
        Map<String, String> config = config(tempDir, "baseQuery", "group.name:\"Support\"");
        scan(config);
        assertThat(ticketRequests).anyMatch(r -> r.startsWith("/api/v1/tickets/search?query=group.name:\"Support\"&"));

        ticketRequests.clear();
        scan(config);
        assertThat(ticketRequests).anyMatch(r -> r.startsWith("/api/v1/tickets/search?query=(group.name:\"Support\") AND updated_at:["));
    }

    @Test
    void strictPrivacyProfileKeepsEmailsOutOfTextMetadataAndPermissions(@TempDir Path tempDir) throws Exception {
        List<RepositoryDocument> docs = scan(config(tempDir, "privacyProfile", "strict"));
        assertThat(docs).hasSize(2);
        Map<String, String> htmlById = new HashMap<>();
        for (RepositoryDocument doc : docs) {
            htmlById.put(doc.id(), html(doc));
            assertThat(htmlById.get(doc.id())).doesNotContain("@");
            assertThat(doc.metadata().values()).allSatisfy(values -> assertThat(values).noneMatch(v -> v.contains("@")));
            assertThat(doc.security().permissions()).extracting(PermissionRule::identity)
                    .containsExactly("zammad-group-Support");
            assertThat(doc.acl()).doesNotContain("@");
        }
        RepositoryDocument publicDoc = docs.stream().filter(d -> d.id().endsWith("/public")).findFirst().orElseThrow();
        assertThat(publicDoc.metadata().get("zammad.customer")).containsExactly("Jane Doe");
        assertThat(htmlById.get(publicDoc.id())).contains("[Customer] Jane Doe", "[Agent] Mark Agent");
    }

    @Test
    void customerIdentityByIdKeepsTheCustomerReadableWithoutAnEmail(@TempDir Path tempDir) {
        List<RepositoryDocument> docs = scan(config(tempDir,
                "customerIdentity", "id",
                "participantMetadata", "names-and-ids"));
        RepositoryDocument publicDoc = docs.stream().filter(d -> d.id().endsWith("/public")).findFirst().orElseThrow();
        assertThat(publicDoc.security().permissions()).extracting(PermissionRule::identity)
                .containsExactly("zammad-group-Support", "zammad-user-101", "zammad-org-Example-Org");
        assertThat(publicDoc.metadata().get("zammad.participantIds")).containsExactly("zammad-user-101", "zammad-user-202");
    }

    @Test
    void periodicFullScanTombstonesVanishedTicketsOnlyWhenDue(@TempDir Path tempDir) throws Exception {
        tickets.add(ticket(4822, "Second ticket", "2026-10-18T08:00:00Z", "2026-10-18T09:00:00Z"));
        articles.put(4822L, List.of(article(48220, "Customer", "email", false, 101, "x", "Second", "2026-10-18T08:00:00Z")));
        Map<String, String> config = config(tempDir, "fullScanInterval", "PT1H");
        assertThat(scan(config)).hasSize(3);

        tickets.removeIf(t -> ((Number) t.get("id")).longValue() == 4822L);
        ticketRequests.clear();
        assertThat(scan(config)).isEmpty();
        assertThat(ticketRequests).allMatch(r -> !r.startsWith("/api/v1/tickets?"));

        Path stateFile = ZammadCrawlState.file(ZammadProperties.fromConfiguration(config));
        ObjectNode state = (ObjectNode) JSON.readTree(stateFile.toFile());
        state.put("lastFullScanAt", Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString());
        JSON.writeValue(stateFile.toFile(), state);

        ticketRequests.clear();
        List<RepositoryDocument> due = scan(config);
        assertThat(ticketRequests).anyMatch(r -> r.startsWith("/api/v1/tickets?"));
        assertThat(due).isNotEmpty().allMatch(d -> d.action() == DocumentAction.DELETE);
        assertThat(due).extracting(RepositoryDocument::id).allMatch(id -> id.contains("/tickets/4822/"));
        assertThat(JSON.readTree(stateFile.toFile()).path("lastFullScanAt").asText())
                .isNotEqualTo(state.path("lastFullScanAt").asText());
    }

    @Test
    void legacyStateFileIsMigratedAndItsStaleIdsAreTombstoned(@TempDir Path tempDir) throws Exception {
        Map<String, String> config = config(tempDir);
        ZammadProperties props = ZammadProperties.fromConfiguration(config);
        String stale = "zammad://127.0.0.1:" + server.getAddress().getPort() + "/tickets/4821/articles/999";
        Path legacy = ZammadCrawlState.legacyFile(props);
        ZammadCrawlState.save(legacy, new ZammadCrawlState.State("2026-10-01T00:00:00Z",
                new LinkedHashMap<>(Map.of("4821", new ZammadCrawlState.Entry("2026-10-19T10:00:00Z", "x", List.of(stale)))),
                new LinkedHashMap<>()));
        assertThat(ZammadCrawlState.file(props)).isNotEqualTo(legacy);

        List<RepositoryDocument> docs = scan(config);
        assertThat(docs).filteredOn(d -> d.action() == DocumentAction.DELETE)
                .extracting(RepositoryDocument::id).containsExactly(stale);
        assertThat(Files.exists(ZammadCrawlState.file(props))).isTrue();
        assertThat(Files.exists(legacy)).isFalse();
    }

    @Test
    void emptyFullEnumerationDoesNotTombstoneIndexedTickets(@TempDir Path tempDir) {
        Map<String, String> config = config(tempDir, "crawlMode", "full");
        assertThat(scan(config)).hasSize(2);
        tickets.clear();
        assertThat(scan(config)).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, String> config(Path stateDir, String... pairs) {
        Map<String, String> config = new HashMap<>();
        config.put("baseUrl", baseUrl);
        config.put("authType", "token");
        config.put("accessToken", "zammad-test-token");
        config.put("includeKnowledgeBase", "false");
        config.put("stateFile", stateDir.toString());
        for (int i = 0; i < pairs.length; i += 2) {
            config.put(pairs[i], pairs[i + 1]);
        }
        return config;
    }

    private static List<RepositoryDocument> scan(Map<String, String> config) {
        return new ZammadRepositoryConnector(ZammadProperties.fromConfiguration(config)).scan("").collectList().block();
    }

    private static String html(RepositoryDocument doc) {
        try (var in = doc.contentStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> ticket(long id, String title, String createdAt, String updatedAt) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("number", "94" + id);
        t.put("title", title);
        t.put("group_id", 4);
        t.put("state_id", 2);
        t.put("priority_id", 2);
        t.put("customer_id", 101);
        t.put("organization_id", 301);
        t.put("owner_id", 202);
        t.put("created_at", createdAt);
        t.put("updated_at", updatedAt);
        return t;
    }

    private static Map<String, Object> article(long id, String sender, String type, boolean internal, long createdBy,
                                               String from, String body, String createdAt) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", id);
        a.put("sender", sender);
        a.put("type", type);
        a.put("internal", internal);
        a.put("created_by_id", createdBy);
        a.put("from", from);
        a.put("body", body);
        a.put("content_type", "text/plain");
        a.put("created_at", createdAt);
        a.put("attachments", List.of());
        return a;
    }

    private static Map<String, Object> withAutoReply(Map<String, Object> article) {
        Map<String, Object> copy = new LinkedHashMap<>(article);
        copy.put("preferences", Map.of("is-auto-response", true));
        return copy;
    }

    private static void respond(HttpExchange ex, int status, Object body) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(body);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.getResponseHeaders().add("Date", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                    .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)));
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
