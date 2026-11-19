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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.connector.discovery.DiscoveryNode;
import org.opencrawling.core.connector.discovery.DiscoveryResult;
import org.opencrawling.core.connector.discovery.DryRunRequest;
import org.opencrawling.core.connector.discovery.DryRunResult;
import org.opencrawling.core.connector.discovery.ExploreRequest;
import org.opencrawling.core.connector.discovery.FetchedDocument;
import org.opencrawling.core.connector.discovery.VisibilityFilter;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZammadRepositoryConnectorTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicBoolean ticketClosedAndInternalRemoved = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/api/v1/users/me", ex -> respondJson(ex, 200,
                "{\"id\":2,\"login\":\"crawler@acme.com\",\"email\":\"crawler@acme.com\"}"));

        server.createContext("/api/v1/groups", ex -> respondJson(ex, 200,
                "[{\"id\":4,\"name\":\"Tier-2-Identity-Security\",\"active\":true}]"));

        server.createContext("/api/v1/ticket_states", ex -> respondJson(ex, 200,
                "[{\"id\":2,\"name\":\"open\"},{\"id\":4,\"name\":\"closed\"}]"));

        server.createContext("/api/v1/ticket_priorities", ex -> respondJson(ex, 200,
                "[{\"id\":3,\"name\":\"3 high\"}]"));

        server.createContext("/api/v1/roles", ex -> respondJson(ex, 200,
                "[{\"id\":1,\"name\":\"Agent\",\"active\":true},{\"id\":2,\"name\":\"Customer\",\"active\":true}]"));

        server.createContext("/api/v1/users/101", ex -> respondJson(ex, 200,
                "{\"id\":101,\"email\":\"claire.dupont@clientcorp.eu\"}"));

        server.createContext("/api/v1/users/202", ex -> respondJson(ex, 200,
                "{\"id\":202,\"email\":\"marcus.weber@acme.com\"}"));

        server.createContext("/api/v1/organizations/301", ex -> respondJson(ex, 200,
                "{\"id\":301,\"name\":\"ClientCorp Europe\",\"shared\":true}"));

        server.createContext("/api/v1/tickets", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/v1/tickets") || path.equals("/api/v1/tickets/search")) {
                String updatedAt = ticketClosedAndInternalRemoved.get()
                        ? "2026-10-20T10:00:00Z"
                        : "2026-10-19T14:30:11Z";
                String ticketJson = """
                        [
                          {
                            "id": 4821,
                            "number": "9402817",
                            "title": "SSO SAML 2.0 signature validation failure",
                            "group_id": 4,
                            "state_id": 2,
                            "priority_id": 3,
                            "customer_id": 101,
                            "organization_id": 301,
                            "owner_id": 202,
                            "created_at": "2026-10-18T08:12:40Z",
                            "updated_at": "%s"
                          }
                        ]
                        """.formatted(updatedAt);
                respondJson(ex, 200, ticketJson);
            } else if (path.equals("/api/v1/tickets/4821")) {
                respondJson(ex, 200, """
                        {
                          "id": 4821,
                          "number": "9402817",
                          "title": "SSO SAML 2.0 signature validation failure",
                          "group_id": 4,
                          "state_id": 2,
                          "priority_id": 3,
                          "customer_id": 101,
                          "organization_id": 301,
                          "owner_id": 202,
                          "created_at": "2026-10-18T08:12:40Z",
                          "updated_at": "2026-10-19T14:30:11Z"
                        }
                        """);
            } else {
                respondJson(ex, 404, "{\"error\":\"not found\"}");
            }
        });

        server.createContext("/api/v1/ticket_articles/by_ticket/4821", ex -> {
            if (ticketClosedAndInternalRemoved.get()) {
                respondJson(ex, 200, """
                        [
                          {
                            "id": 1001,
                            "ticket_id": 4821,
                            "sender": "Customer",
                            "from": "claire.dupont@clientcorp.eu",
                            "subject": "SAML failure",
                            "body": "Our SSO login fails after rotating the certificate.",
                            "content_type": "text/plain",
                            "internal": false,
                            "created_at": "2026-10-18T08:12:40Z",
                            "attachments": []
                          }
                        ]
                        """);
            } else {
                respondJson(ex, 200, """
                        [
                          {
                            "id": 1001,
                            "ticket_id": 4821,
                            "sender": "Customer",
                            "from": "claire.dupont@clientcorp.eu",
                            "subject": "SAML failure",
                            "body": "Our SSO login fails after rotating the certificate.",
                            "content_type": "text/plain",
                            "internal": false,
                            "created_at": "2026-10-18T08:12:40Z",
                            "attachments": [
                              {
                                "id": 501,
                                "filename": "saml-trace.log",
                                "size": 28,
                                "preferences": {
                                  "Mime-Type": "text/plain"
                                }
                              }
                            ]
                          },
                          {
                            "id": 1002,
                            "ticket_id": 4821,
                            "sender": "Agent",
                            "from": "marcus.weber@acme.com",
                            "subject": "Internal root cause",
                            "body": "<p>Internal note: IdP metadata cache on node-3 was stale.</p>",
                            "content_type": "text/html",
                            "internal": true,
                            "created_at": "2026-10-18T09:00:00Z",
                            "attachments": [
                              {
                                "id": 502,
                                "filename": "internal-runbook.txt",
                                "size": 31,
                                "preferences": {
                                  "Mime-Type": "text/plain"
                                }
                              }
                            ]
                          }
                        ]
                        """);
            }
        });

        server.createContext("/api/v1/tags", ex -> respondJson(ex, 200,
                "{\"tags\":[\"saml\",\"sso\",\"certificate\"]}"));

        server.createContext("/api/v1/ticket_attachment/4821/1001/501", ex ->
                respondText(ex, 200, "SAML Response Signature Error"));

        server.createContext("/api/v1/ticket_attachment/4821/1002/502", ex ->
                respondText(ex, 200, "CONFIDENTIAL AGENT RUNBOOK STEP"));

        server.createContext("/api/v1/knowledge_bases/init", ex -> respondJson(ex, 200, """
                {
                  "assets": {
                    "KnowledgeBaseLocale": {
                      "1": {"id": 1, "system_locale": "en"}
                    },
                    "KnowledgeBaseCategory": {
                      "10": {
                        "id": 10,
                        "knowledge_base_id": 1,
                        "permissions": [
                          {"role_id": 1, "access": "editor"}
                        ]
                      }
                    },
                    "KnowledgeBaseCategoryTranslation": {
                      "100": {"id": 100, "category_id": 10, "title": "Authentication & SSO"}
                    },
                    "KnowledgeBaseAnswer": {
                      "77": {
                        "id": 77,
                        "category_id": 10,
                        "published_at": "2026-10-15T12:00:00Z",
                        "updated_at": "2026-10-15T12:00:00Z"
                      }
                    },
                    "KnowledgeBaseAnswerTranslation": {
                      "770": {
                        "id": 770,
                        "answer_id": 77,
                        "kb_locale_id": 1,
                        "title": "How to rotate SAML IdP certificates",
                        "content_id": 7700
                      }
                    },
                    "KnowledgeBaseAnswerTranslationContent": {
                      "7700": {
                        "id": 7700,
                        "body": "<p>Upload the new X.509 certificate under Security Settings.</p>"
                      }
                    }
                  }
                }
                """));

        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void splitPublicAndInternalIsolatesInternalNotesAttachmentsAndEmitsIncrementalTombstones(@TempDir Path tempDir) throws Exception {
        Path stateFile = tempDir.resolve("zammad-state.json");
        ZammadProperties props = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", baseUrl,
                "authType", "token",
                "accessToken", "zammad-test-token",
                "stateFile", stateFile.toString()
        ));

        ZammadRepositoryConnector connector = new ZammadRepositoryConnector(props);
        List<RepositoryDocument> firstRun = connector.scan("").collectList().block();

        assertThat(firstRun).isNotNull();
        // Expect 5 documents:
        // 1. public ticket thread
        // 2. public attachment (501)
        // 3. internal ticket thread
        // 4. internal attachment (502)
        // 5. KB answer (77/en)
        assertThat(firstRun).hasSize(5);

        RepositoryDocument publicTicket = firstRun.stream()
                .filter(d -> d.id().endsWith("/tickets/4821/public"))
                .findFirst().orElseThrow();
        assertThat(publicTicket.metadata().get("zammad.entityType")).containsExactly("ticket");
        assertThat(publicTicket.metadata().get("zammad.ticketNumber")).containsExactly("9402817");
        assertThat(publicTicket.metadata().get("zammad.tags")).containsExactly("saml,sso,certificate");
        assertThat(publicTicket.security().permissions())
                .extracting(PermissionRule::identity)
                .containsExactly(
                        "zammad-group-Tier-2-Identity-Security",
                        "claire.dupont@clientcorp.eu",
                        "zammad-org-ClientCorp-Europe"
                );
        String publicHtml = new String(publicTicket.contentStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(publicHtml).contains("Our SSO login fails after rotating the certificate.");
        assertThat(publicHtml).doesNotContain("Internal note: IdP metadata cache on node-3 was stale.");

        RepositoryDocument internalTicket = firstRun.stream()
                .filter(d -> d.id().endsWith("/tickets/4821/internal"))
                .findFirst().orElseThrow();
        assertThat(internalTicket.metadata().get("zammad.entityType")).containsExactly("ticket_internal");
        assertThat(internalTicket.metadata().get("zammad.internal")).containsExactly("true");
        assertThat(internalTicket.metadata()).doesNotContainKey("zammad.customer");
        // Strictly restricted to Tier-2-Identity-Security group agents
        assertThat(internalTicket.security().permissions())
                .extracting(PermissionRule::identity)
                .containsExactly("zammad-group-Tier-2-Identity-Security");
        String internalHtml = new String(internalTicket.contentStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(internalHtml).contains("Internal note: IdP metadata cache on node-3 was stale.");

        RepositoryDocument internalAtt = firstRun.stream()
                .filter(d -> d.id().endsWith("/attachments/502"))
                .findFirst().orElseThrow();
        assertThat(internalAtt.security().permissions())
                .extracting(PermissionRule::identity)
                .containsExactly("zammad-group-Tier-2-Identity-Security");
        try (var in = internalAtt.contentStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("CONFIDENTIAL AGENT RUNBOOK STEP");
        }

        RepositoryDocument kbDoc = firstRun.stream()
                .filter(d -> d.id().contains("/kb/1/answers/77/en"))
                .findFirst().orElseThrow();
        assertThat(kbDoc.metadata().get("zammad.categoryTitle")).containsExactly("Authentication & SSO");
        assertThat(kbDoc.security().permissions())
                .extracting(PermissionRule::identity)
                .containsExactly("zammad-role-Agent");

        // Second run: internal note + attachments removed on ticket 4821; KB answer unchanged
        ticketClosedAndInternalRemoved.set(true);
        List<RepositoryDocument> secondRun = connector.scan("").collectList().block();
        assertThat(secondRun).isNotNull();

        List<RepositoryDocument> upserts = secondRun.stream()
                .filter(d -> d.action() == DocumentAction.UPSERT)
                .toList();
        List<RepositoryDocument> deletes = secondRun.stream()
                .filter(d -> d.action() == DocumentAction.DELETE)
                .toList();

        // The public thread renders exactly as before (only updated_at and an attachment changed), so its content hash
        // matches and it is not re-indexed (#412); the KB answer was unchanged and skipped too.
        assertThat(upserts).isEmpty();
        // The removed internal thread and both removed attachments (501, 502) get DELETE tombstones!
        assertThat(deletes).extracting(RepositoryDocument::id)
                .containsExactlyInAnyOrder(
                        internalTicket.id(),
                        firstRun.stream().filter(d -> d.id().endsWith("/attachments/501")).findFirst().orElseThrow().id(),
                        internalAtt.id()
                );
    }

    @Test
    void discoveryAndFetchDocumentEnforceVisibilityFilterForCustomerVsAgent() {
        ZammadProperties props = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", baseUrl,
                "authType", "token",
                "accessToken", "zammad-test-token"
        ));
        ZammadRepositoryConnector connector = new ZammadRepositoryConnector(props);

        // Visibility filter for customer claire.dupont@clientcorp.eu (not a member of zammad-group-Tier-2-Identity-Security)
        VisibilityFilter customerFilter = nodes -> {
            boolean[] visible = new boolean[nodes.size()];
            for (int i = 0; i < nodes.size(); i++) {
                var sec = nodes.get(i).security();
                visible[i] = sec != null && sec.permissions().stream()
                        .anyMatch(r -> "claire.dupont@clientcorp.eu".equals(r.identity()));
            }
            return visible;
        };

        // 1. Admin/ALL explore from root finds the group folder and both public & internal ticket documents
        DiscoveryResult allExplore = connector.explore(
                new ExploreRequest("/", 2, 50, true, Duration.ofSeconds(10)),
                VisibilityFilter.ALL);
        assertThat(allExplore.nodes()).hasSize(3);

        // 2. Dry-run with customerFilter counts only the 1 public ticket document and hides the internal note document
        DryRunResult customerDryRun = connector.dryRun(
                new DryRunRequest("/", 10, 100, Duration.ofSeconds(10)),
                customerFilter);
        assertThat(customerDryRun.matchCount()).isEqualTo(1);
        assertThat(customerDryRun.hiddenCount()).isEqualTo(1);
        assertThat(customerDryRun.samples()).singleElement()
                .satisfies(node -> assertThat(node.id()).endsWith("/tickets/4821/public"));

        // 3. fetchDocument allows customer to fetch the public ticket thread, but refuses the internal notes thread
        FetchedDocument foundPublic = connector.fetchDocument("4821:public", customerFilter);
        assertThat(foundPublic).isInstanceOf(FetchedDocument.Found.class);

        assertThatThrownBy(() -> connector.fetchDocument("4821:internal", customerFilter))
                .isInstanceOf(IllegalArgumentException.class);

        DiscoveryNode startGroup = connector.crawlStart("group:4", VisibilityFilter.ALL);
        assertThat(startGroup.sourceId()).isEqualTo("group:4");
        assertThat(startGroup.name()).isEqualTo("Tier-2-Identity-Security");

        DiscoveryNode startByPath = connector.crawlStart("/Groups///Tier-2-Identity-Security///", VisibilityFilter.ALL);
        assertThat(startByPath.sourceId()).isEqualTo("group:4");
        assertThat(startByPath.name()).isEqualTo("Tier-2-Identity-Security");
        connector.disconnect();
    }

    @Test
    void perArticleAndPublicOnlyModesPartitionTicketArticlesProperly(@TempDir Path tempDir) {
        ZammadProperties perArticleProps = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", baseUrl,
                "authType", "token",
                "accessToken", "zammad-test-token",
                "articleGranularity", "PER_ARTICLE",
                "includeAttachments", "false",
                "includeKnowledgeBase", "false",
                "stateFile", tempDir.resolve("per-article.json").toString()
        ));
        List<RepositoryDocument> articleDocs = new ZammadRepositoryConnector(perArticleProps).scan("").collectList().block();
        assertThat(articleDocs).hasSize(2);
        assertThat(articleDocs).extracting(RepositoryDocument::id)
                .allMatch(id -> id.contains("/tickets/4821/articles/"));

        ZammadProperties publicOnlyProps = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", baseUrl,
                "authType", "token",
                "accessToken", "zammad-test-token",
                "articleGranularity", "PUBLIC_ONLY",
                "includeAttachments", "false",
                "includeKnowledgeBase", "false",
                "stateFile", tempDir.resolve("public-only.json").toString()
        ));
        List<RepositoryDocument> publicDocs = new ZammadRepositoryConnector(publicOnlyProps).scan("").collectList().block();
        assertThat(publicDocs).singleElement()
                .satisfies(doc -> assertThat(doc.id()).endsWith("/tickets/4821/public"));
    }

    @Test
    void pageSizeAboveServerCapDoesNotTruncateOrEmitFalseTombstones(@TempDir Path tempDir) throws Exception {
        HttpServer cappedServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String cappedUrl = "http://127.0.0.1:" + cappedServer.getAddress().getPort();
        int totalTickets = 105;

        cappedServer.createContext("/api/v1/users/me", ex -> respondJson(ex, 200,
                "{\"id\":2,\"email\":\"crawler@acme.com\"}"));
        cappedServer.createContext("/api/v1/groups", ex -> respondJson(ex, 200,
                "[{\"id\":4,\"name\":\"Support\",\"active\":true}]"));
        cappedServer.createContext("/api/v1/ticket_states", ex -> respondJson(ex, 200,
                "[{\"id\":2,\"name\":\"open\"}]"));
        cappedServer.createContext("/api/v1/ticket_priorities", ex -> respondJson(ex, 200,
                "[{\"id\":2,\"name\":\"2 normal\"}]"));
        cappedServer.createContext("/api/v1/roles", ex -> respondJson(ex, 200, "[]"));
        cappedServer.createContext("/api/v1/tags", ex -> respondJson(ex, 200, "{\"tags\":[]}"));
        cappedServer.createContext("/api/v1/tickets", ex -> {
            String q = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
            int page = parseQueryInt(q, "page", 1);
            // Mirror Zammad TicketsController#index paginate_with(max: 100)
            int perPage = Math.min(100, parseQueryInt(q, "per_page", 50));
            int start = (page - 1) * perPage;
            int end = Math.min(totalTickets, start + perPage);
            StringBuilder sb = new StringBuilder("[");
            for (int i = start; i < end; i++) {
                if (i > start) {
                    sb.append(',');
                }
                int id = 1000 + i;
                sb.append("{\"id\":").append(id)
                        .append(",\"number\":\"").append(id)
                        .append("\",\"title\":\"Ticket ").append(id)
                        .append("\",\"group_id\":4,\"state_id\":2,\"priority_id\":2")
                        .append(",\"updated_at\":\"2026-10-19T12:00:00Z\"}");
            }
            sb.append(']');
            respondJson(ex, 200, sb.toString());
        });
        cappedServer.createContext("/api/v1/ticket_articles/by_ticket/", ex -> {
            String path = ex.getRequestURI().getPath();
            String tid = path.substring(path.lastIndexOf('/') + 1);
            respondJson(ex, 200, "[{\"id\":" + tid + "1,\"ticket_id\":" + tid
                    + ",\"sender\":\"Customer\",\"body\":\"Hello\",\"internal\":false}]");
        });
        cappedServer.start();
        try {
            Path stateFile = tempDir.resolve("capped-state.json");
            ZammadProperties props = ZammadProperties.fromConfiguration(Map.of(
                    "baseUrl", cappedUrl,
                    "accessToken", "tok",
                    "pageSize", "200",
                    "crawlMode", "full",
                    "includeAttachments", "false",
                    "includeKnowledgeBase", "false",
                    "includeTags", "false",
                    "stateFile", stateFile.toString()
            ));
            ZammadRepositoryConnector connector = new ZammadRepositoryConnector(props);

            List<RepositoryDocument> first = connector.scan("").collectList().block();
            assertThat(first).hasSize(totalTickets);
            assertThat(first).allSatisfy(d -> assertThat(d.action()).isEqualTo(DocumentAction.UPSERT));

            // Re-running FULL reconciliation must see all 105 unchanged and emit 0 false DELETE tombstones
            List<RepositoryDocument> second = connector.scan("").collectList().block();
            assertThat(second).isEmpty();
        } finally {
            cappedServer.stop(0);
        }
    }

    @Test
    void internalOnlyTicketDoesNotEmitPublicDocumentAndFetchReturnsGone(@TempDir Path tempDir) throws Exception {
        HttpServer internalServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String url = "http://127.0.0.1:" + internalServer.getAddress().getPort();

        internalServer.createContext("/api/v1/users/me", ex -> respondJson(ex, 200, "{\"id\":2,\"email\":\"crawler@acme.com\"}"));
        internalServer.createContext("/api/v1/groups", ex -> respondJson(ex, 200, "[{\"id\":4,\"name\":\"SecOps\",\"active\":true}]"));
        internalServer.createContext("/api/v1/ticket_states", ex -> respondJson(ex, 200, "[{\"id\":2,\"name\":\"open\"}]"));
        internalServer.createContext("/api/v1/ticket_priorities", ex -> respondJson(ex, 200, "[{\"id\":3,\"name\":\"3 high\"}]"));
        internalServer.createContext("/api/v1/roles", ex -> respondJson(ex, 200, "[]"));
        internalServer.createContext("/api/v1/users/101", ex -> respondJson(ex, 200, "{\"id\":101,\"email\":\"customer@external.com\"}"));
        internalServer.createContext("/api/v1/tags", ex -> respondJson(ex, 200, "{\"tags\":[\"incident\"]}"));
        internalServer.createContext("/api/v1/tickets", ex -> {
            String path = ex.getRequestURI().getPath();
            String ticketObj = """
                    {"id":9001,"number":"9001","title":"Confidential Zero-Day Escalation",
                     "group_id":4,"state_id":2,"priority_id":3,"customer_id":101,
                     "updated_at":"2026-10-19T12:00:00Z"}
                    """;
            if ("/api/v1/tickets/9001".equals(path)) {
                respondJson(ex, 200, ticketObj);
            } else {
                respondJson(ex, 200, "[" + ticketObj + "]");
            }
        });
        internalServer.createContext("/api/v1/ticket_articles/by_ticket/9001", ex -> respondJson(ex, 200, """
                [{"id":90011,"ticket_id":9001,"sender":"Agent","body":"Internal triage note only.","internal":true}]
                """));
        internalServer.start();
        try {
            ZammadProperties props = ZammadProperties.fromConfiguration(Map.of(
                    "baseUrl", url,
                    "accessToken", "tok",
                    "includeKnowledgeBase", "false",
                    "includeAttachments", "false",
                    "stateFile", tempDir.resolve("internal-only.json").toString()
            ));
            ZammadRepositoryConnector connector = new ZammadRepositoryConnector(props);

            List<RepositoryDocument> docs = connector.scan("").collectList().block();
            assertThat(docs).singleElement().satisfies(doc -> {
                assertThat(doc.id()).endsWith("/tickets/9001/internal");
                assertThat(doc.security().permissions())
                        .extracting(PermissionRule::identity)
                        .containsExactly("zammad-group-SecOps");
            });

            // On-demand fetch for the non-existent public view must return Gone instead of leaking metadata
            FetchedDocument publicFetch = connector.fetchDocument("9001:public", VisibilityFilter.ALL);
            assertThat(publicFetch).isInstanceOf(FetchedDocument.Gone.class);
            connector.disconnect();
        } finally {
            internalServer.stop(0);
        }
    }

    @Test
    void futureScheduledKnowledgeBaseTimestampsAndExplicitDenyCategoriesAreEnforced(@TempDir Path tempDir) throws Exception {
        HttpServer kbServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String url = "http://127.0.0.1:" + kbServer.getAddress().getPort();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String past = now.minus(2, ChronoUnit.HOURS).toString();
        String future = now.plus(7, ChronoUnit.DAYS).toString();

        kbServer.createContext("/api/v1/users/me", ex -> respondJson(ex, 200, "{\"id\":2,\"email\":\"crawler@acme.com\"}"));
        kbServer.createContext("/api/v1/groups", ex -> respondJson(ex, 200, "[{\"id\":4,\"name\":\"Support\",\"active\":true}]"));
        kbServer.createContext("/api/v1/ticket_states", ex -> respondJson(ex, 200, "[]"));
        kbServer.createContext("/api/v1/ticket_priorities", ex -> respondJson(ex, 200, "[]"));
        kbServer.createContext("/api/v1/roles", ex -> respondJson(ex, 200, "[{\"id\":1,\"name\":\"Agent\",\"active\":true}]"));
        kbServer.createContext("/api/v1/knowledge_bases/init", ex -> respondJson(ex, 200, """
                {
                  "assets": {
                    "KnowledgeBaseLocale": {"1": {"id": 1, "system_locale": "en"}},
                    "KnowledgeBaseCategory": {
                      "10": {"id": 10, "knowledge_base_id": 1, "permissions_effective": [{"role_id": 1, "access": "reader"}]},
                      "11": {"id": 11, "knowledge_base_id": 1, "permissions_effective": [{"role_id": 1, "access": "none"}]}
                    },
                    "KnowledgeBaseCategoryTranslation": {
                      "100": {"id": 100, "category_id": 10, "title": "General"},
                      "110": {"id": 110, "category_id": 11, "title": "Restricted"}
                    },
                    "KnowledgeBaseAnswer": {
                      "1": {"id": 1, "category_id": 10, "published_at": "%s", "updated_at": "%s"},
                      "2": {"id": 2, "category_id": 10, "internal_at": "%s", "published_at": "%s", "updated_at": "%s"},
                      "3": {"id": 3, "category_id": 10, "published_at": "%s", "archived_at": "%s", "updated_at": "%s"},
                      "4": {"id": 4, "category_id": 11, "internal_at": "%s", "updated_at": "%s"}
                    },
                    "KnowledgeBaseAnswerTranslation": {
                      "10": {"id": 10, "answer_id": 1, "kb_locale_id": 1, "title": "Future Draft", "content_id": 101},
                      "20": {"id": 20, "answer_id": 2, "kb_locale_id": 1, "title": "Internal Scheduled Public", "content_id": 201},
                      "30": {"id": 30, "answer_id": 3, "kb_locale_id": 1, "title": "Published Scheduled Archive", "content_id": 301},
                      "40": {"id": 40, "answer_id": 4, "kb_locale_id": 1, "title": "Denied Category Answer", "content_id": 401}
                    },
                    "KnowledgeBaseAnswerTranslationContent": {
                      "101": {"id": 101, "body": "<p>Not yet published</p>"},
                      "201": {"id": 201, "body": "<p>Internal until next week</p>"},
                      "301": {"id": 301, "body": "<p>Active until next week</p>"},
                      "401": {"id": 401, "body": "<p>Locked down</p>"}
                    }
                  }
                }
                """.formatted(future, past, past, future, past, past, future, past, past, past)));
        kbServer.start();
        try {
            ZammadProperties props = ZammadProperties.fromConfiguration(Map.of(
                    "baseUrl", url,
                    "accessToken", "tok",
                    "includeTickets", "false",
                    "includeKnowledgeBase", "true",
                    "allowPublicKb", "true",
                    "stateFile", tempDir.resolve("kb-schedule.json").toString()
            ));
            List<RepositoryDocument> docs = new ZammadRepositoryConnector(props).scan("").collectList().block();
            assertThat(docs).hasSize(3);

            // Answer 1 (future published_at) must NOT be crawled prematurely
            assertThat(docs).noneMatch(d -> d.id().contains("/answers/1/"));

            // Answer 2 (past internal_at + future published_at) must stay internal & role-restricted (not public!)
            RepositoryDocument ans2 = docs.stream().filter(d -> d.id().contains("/answers/2/")).findFirst().orElseThrow();
            assertThat(ans2.metadata().get("zammad.internal")).containsExactly("true");
            assertThat(ans2.acl()).isEqualTo("zammad-role-Agent");

            // Answer 3 (past published_at + future archived_at) must remain active & public
            RepositoryDocument ans3 = docs.stream().filter(d -> d.id().contains("/answers/3/")).findFirst().orElseThrow();
            assertThat(ans3.metadata().get("zammad.internal")).containsExactly("false");
            assertThat(ans3.acl()).isEqualTo("public");

            // Answer 4 (category with explicit access="none") must fail closed instead of falling back to Support group
            RepositoryDocument ans4 = docs.stream().filter(d -> d.id().contains("/answers/4/")).findFirst().orElseThrow();
            assertThat(ans4.security().permissions()).isEmpty();
            assertThat(ans4.acl()).isEmpty();
        } finally {
            kbServer.stop(0);
        }
    }

    @Test
    void reconnectedScanRefreshesOrganizationCacheAndCancellationSkipsStateSave(@TempDir Path tempDir) throws Exception {
        AtomicBoolean orgShared = new AtomicBoolean(true);
        HttpServer dynamicServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String url = "http://127.0.0.1:" + dynamicServer.getAddress().getPort();

        dynamicServer.createContext("/api/v1/users/me", ex -> respondJson(ex, 200, "{\"id\":2,\"email\":\"crawler@acme.com\"}"));
        dynamicServer.createContext("/api/v1/groups", ex -> respondJson(ex, 200, "[{\"id\":4,\"name\":\"Support\",\"active\":true}]"));
        dynamicServer.createContext("/api/v1/ticket_states", ex -> respondJson(ex, 200, "[{\"id\":2,\"name\":\"open\"}]"));
        dynamicServer.createContext("/api/v1/ticket_priorities", ex -> respondJson(ex, 200, "[{\"id\":2,\"name\":\"2 normal\"}]"));
        dynamicServer.createContext("/api/v1/roles", ex -> respondJson(ex, 200, "[]"));
        dynamicServer.createContext("/api/v1/users/101", ex -> respondJson(ex, 200, "{\"id\":101,\"email\":\"alice@client.eu\"}"));
        dynamicServer.createContext("/api/v1/organizations/301", ex -> respondJson(ex, 200,
                "{\"id\":301,\"name\":\"ClientCorp\",\"shared\":" + orgShared.get() + "}"));
        dynamicServer.createContext("/api/v1/tags", ex -> respondJson(ex, 200, "{\"tags\":[]}"));
        dynamicServer.createContext("/api/v1/tickets", ex -> respondJson(ex, 200, """
                [
                  {"id":5001,"number":"5001","title":"Ticket 1","group_id":4,"state_id":2,"priority_id":2,"customer_id":101,"organization_id":301,"updated_at":"2026-10-19T12:00:00Z"},
                  {"id":5002,"number":"5002","title":"Ticket 2","group_id":4,"state_id":2,"priority_id":2,"customer_id":101,"organization_id":301,"updated_at":"2026-10-19T12:01:00Z"}
                ]
                """));
        dynamicServer.createContext("/api/v1/ticket_articles/by_ticket/", ex -> {
            String path = ex.getRequestURI().getPath();
            String tid = path.substring(path.lastIndexOf('/') + 1);
            respondJson(ex, 200, "[{\"id\":" + tid + "1,\"ticket_id\":" + tid + ",\"sender\":\"Customer\",\"body\":\"Hi\",\"internal\":false}]");
        });
        dynamicServer.start();
        try {
            // 1. Downstream cancellation via take(1) must NOT save/advance stateFile
            Path cancelledState = tempDir.resolve("cancelled-state.json");
            ZammadProperties cancelProps = ZammadProperties.fromConfiguration(Map.of(
                    "baseUrl", url,
                    "accessToken", "tok",
                    "parallelism", "1",
                    "includeKnowledgeBase", "false",
                    "includeAttachments", "false",
                    "stateFile", cancelledState.toString()
            ));
            List<RepositoryDocument> partial = new ZammadRepositoryConnector(cancelProps).scan("").take(1).collectList().block();
            assertThat(partial).hasSize(1);
            assertThat(Files.exists(cancelledState)).isFalse();

            // 2. Reusing the same connector instance refreshes orgsById on reconnect when organization.shared flips to false
            Path sharedState = tempDir.resolve("shared-state.json");
            ZammadProperties reuseProps = ZammadProperties.fromConfiguration(Map.of(
                    "baseUrl", url,
                    "accessToken", "tok",
                    "crawlMode", "full",
                    "includeKnowledgeBase", "false",
                    "includeAttachments", "false",
                    "stateFile", sharedState.toString()
            ));
            ZammadRepositoryConnector reused = new ZammadRepositoryConnector(reuseProps);
            List<RepositoryDocument> run1 = reused.scan("").collectList().block();
            assertThat(run1).hasSize(2);
            assertThat(run1.getFirst().security().permissions())
                    .extracting(PermissionRule::identity)
                    .contains("zammad-org-ClientCorp");

            orgShared.set(false);
            List<RepositoryDocument> run2 = reused.scan("").collectList().block();
            // Even with unchanged updated_at, the refreshed ACL fingerprint triggers re-indexing without zammad-org-ClientCorp
            assertThat(run2).hasSize(2);
            assertThat(run2.getFirst().security().permissions())
                    .extracting(PermissionRule::identity)
                    .doesNotContain("zammad-org-ClientCorp");
        } finally {
            dynamicServer.stop(0);
        }
    }

    private static int parseQueryInt(String query, String key, int fallback) {
        if (query == null || query.isBlank()) {
            return fallback;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                try {
                    return Integer.parseInt(part.substring(eq + 1));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static void respondJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void respondText(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
