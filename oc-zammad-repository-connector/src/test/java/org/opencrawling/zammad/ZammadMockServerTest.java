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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Over-HTTP connector scenarios against the Zammad REST API v1 mock ({@code zammad-mock/zammad_mock.py}):
 * full paginated crawl, public vs. internal ACL isolation, shared vs. non-shared organizations, fail-closed orphan
 * tickets, Knowledge Base inline and on-demand fetching, incremental {@code search_delta} updates and tombstones,
 * full reconciliation deletions, authentication modes, HTTP 429 retry, and discovery visibility filtering.
 *
 * <p>Runs only when {@code ZAMMAD_MOCK_URL} points at a running mock (e.g. {@code http://localhost:18390});
 * {@code scripts/test-zammad-connector.sh} starts one automatically.
 */
@EnabledIfEnvironmentVariable(named = "ZAMMAD_MOCK_URL", matches = "https?://.+")
class ZammadMockServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = System.getenv("ZAMMAD_MOCK_URL") == null
            ? ""
            : System.getenv("ZAMMAD_MOCK_URL").replaceAll("/+$", "");

    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path stateDir;

    @BeforeEach
    void resetMock() throws Exception {
        admin("/mock/reset", "{}");
    }

    private JsonNode admin(String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Accept", "application/json");
        if (body == null) {
            req.GET();
        } else {
            req.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("%s -> %d %s", path, response.statusCode(), response.body())
                .isLessThan(300);
        return MAPPER.readTree(response.body());
    }

    private ZammadRepositoryConnector connector(Map<String, String> overrides) {
        Map<String, String> config = new HashMap<>(Map.of(
                "baseUrl", BASE,
                "authType", "token",
                "accessToken", "zammad-test-token",
                "stateFile", stateDir.resolve("zammad-state.json").toString(),
                "pageSize", "2",
                "initialBackoffMs", "10"
        ));
        config.putAll(overrides);
        return new ZammadRepositoryConnector(ZammadProperties.fromConfiguration(config));
    }

    private record MaterializedDoc(RepositoryDocument doc, String text) {
    }

    private static List<MaterializedDoc> scanAll(ZammadRepositoryConnector connector) throws IOException {
        List<RepositoryDocument> docs = connector.scan("default").collectList().block();
        assertThat(docs).isNotNull();
        return docs.stream().map(d -> {
            String text = "";
            if (d.contentStream() != null) {
                try (InputStream in = d.contentStream()) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return new MaterializedDoc(d, text);
        }).toList();
    }

    private static MaterializedDoc findBySuffix(List<MaterializedDoc> docs, String suffix) {
        return docs.stream()
                .filter(m -> m.doc().id().endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No document ending with " + suffix));
    }

    @Test
    void fullCrawlMatchesMockDatasetAndEnforcesAclBoundaries() throws Exception {
        JsonNode beforeStats = admin("/mock/stats", null);
        int expectedDocs = beforeStats.path("expectedDocuments").asInt();
        assertThat(expectedDocs).isEqualTo(10);

        List<MaterializedDoc> docs = scanAll(connector(Map.of()));
        assertThat(docs).hasSize(expectedDocs);
        assertThat(docs).allSatisfy(m -> assertThat(m.doc().action()).isEqualTo(DocumentAction.UPSERT));

        // 1. Ticket 4821 public thread: group + customer + shared org, no internal content
        MaterializedDoc pub4821 = findBySuffix(docs, "/tickets/4821/public");
        assertThat(pub4821.text()).contains("ZMD-4821-PUB").doesNotContain("ZMD-4821-INT");
        assertThat(pub4821.doc().metadata().get("zammad.tags")).containsExactly("saml,sso,certificate");
        assertThat(pub4821.doc().security().permissions())
                .extracting(PermissionRule::identity)
                .containsExactly(
                        "zammad-group-Tier-2-Identity-Security",
                        "claire.dupont@clientcorp.eu",
                        "zammad-org-ClientCorp-Europe"
                );

        // 2. Ticket 4821 internal notes + internal attachment: group agents ONLY
        MaterializedDoc int4821 = findBySuffix(docs, "/tickets/4821/internal");
        assertThat(int4821.text()).contains("ZMD-4821-INT");
        assertThat(int4821.doc().metadata()).doesNotContainKeys("zammad.customer", "zammad.organization");
        assertThat(int4821.doc().security().permissions())
                .extracting(PermissionRule::identity)
                .containsExactly("zammad-group-Tier-2-Identity-Security");

        MaterializedDoc att501 = findBySuffix(docs, "/attachments/501");
        assertThat(att501.text()).contains("ZMD-4821-ATT-PUB");
        assertThat(att501.doc().security().permissions())
                .extracting(PermissionRule::identity)
                .contains("claire.dupont@clientcorp.eu", "zammad-org-ClientCorp-Europe");

        MaterializedDoc att502 = findBySuffix(docs, "/attachments/502");
        assertThat(att502.text()).contains("ZMD-4821-ATT-INT");
        assertThat(att502.doc().security().permissions())
                .extracting(PermissionRule::identity)
                .containsExactly("zammad-group-Tier-2-Identity-Security");

        // Synthetic content-alternative email attachment (599) must be skipped
        assertThat(docs).noneMatch(m -> m.doc().id().endsWith("/attachments/599"));

        // 3. Ticket 4822 (non-shared organization PrivateBank AG): omits organization group
        MaterializedDoc pub4822 = findBySuffix(docs, "/tickets/4822/public");
        assertThat(pub4822.doc().security().permissions())
                .extracting(PermissionRule::identity)
                .containsExactly("zammad-group-Tier-2-Identity-Security", "lukas.meyer@privatebank.ch");

        // 4. Orphan ticket 4824 (missing group 999): fails closed with zero read permissions
        MaterializedDoc orphan4824 = findBySuffix(docs, "/tickets/4824/public");
        assertThat(orphan4824.doc().security().permissions()).isEmpty();
        assertThat(orphan4824.doc().acl()).isEmpty();

        // 5. Knowledge Base: Answer 77 (inline) & Answer 78 (fetched via /answers/78?include_contents=true); Answer 79 archived
        MaterializedDoc kb77 = findBySuffix(docs, "/kb/1/answers/77/en");
        assertThat(kb77.text()).contains("ZMD-KB-77-PUB");
        assertThat(kb77.doc().security().permissions())
                .extracting(PermissionRule::identity)
                .containsExactly("zammad-role-Agent");

        MaterializedDoc kb78 = findBySuffix(docs, "/kb/1/answers/78/en");
        assertThat(kb78.text()).contains("ZMD-KB-78-INT");
        assertThat(kb78.doc().metadata().get("zammad.internal")).containsExactly("true");
        assertThat(kb78.doc().security().permissions())
                .extracting(PermissionRule::identity)
                .containsExactly("zammad-role-Security-Auditor");

        assertThat(docs).noneMatch(m -> m.doc().id().contains("/kb/1/answers/79/"));

        JsonNode afterStats = admin("/mock/stats", null);
        assertThat(afterStats.path("authFailures").asInt()).isZero();
        assertThat(afterStats.path("requests").path("GET /api/v1/knowledge_bases/1/answers/78").asInt()).isGreaterThan(0);
    }

    @Test
    void incrementalSearchDeltaAndFullReconciliationEmitUpdatesAndTombstones() throws Exception {
        // Run 1: initial crawl
        ZammadRepositoryConnector deltaConnector = connector(Map.of("crawlMode", "search_delta"));
        List<MaterializedDoc> first = scanAll(deltaConnector);
        assertThat(first).hasSize(10);

        // Mutate mock: add ticket 4825, remove internal note & attachments on 4821, archive KB 78
        admin("/mock/tickets", """
                {
                  "id": 4825,
                  "title": "Incremental OAuth2 token refresh failure",
                  "group_id": 4,
                  "state_id": 2,
                  "customer_id": 101,
                  "organization_id": 301,
                  "public_body": "Public incremental update ZMD-4825-INCR-PUB",
                  "internal_body": "Internal agent note ZMD-4825-INCR-INT"
                }
                """);
        admin("/mock/tickets/4821/update", """
                {
                  "remove_internal": true,
                  "clear_attachments": true,
                  "public_body": "SAML issue resolved after IdP metadata refresh. Reference ZMD-4821-V2."
                }
                """);
        admin("/mock/kb/78/archive", "{}");

        // Run 2: incremental search_delta crawl
        List<MaterializedDoc> second = scanAll(deltaConnector);
        Set<String> upserts = second.stream()
                .filter(m -> m.doc().action() == DocumentAction.UPSERT)
                .map(m -> m.doc().id())
                .collect(Collectors.toSet());
        Set<String> deletes = second.stream()
                .filter(m -> m.doc().action() == DocumentAction.DELETE)
                .map(m -> m.doc().id())
                .collect(Collectors.toSet());

        assertThat(upserts).hasSize(3);
        assertThat(upserts).anyMatch(id -> id.endsWith("/tickets/4821/public"));
        assertThat(upserts).anyMatch(id -> id.endsWith("/tickets/4825/public"));
        assertThat(upserts).anyMatch(id -> id.endsWith("/tickets/4825/internal"));
        assertThat(findBySuffix(second, "/tickets/4821/public").text()).contains("ZMD-4821-V2");

        assertThat(deletes).hasSize(4);
        assertThat(deletes).anyMatch(id -> id.endsWith("/tickets/4821/internal"));
        assertThat(deletes).anyMatch(id -> id.endsWith("/attachments/501"));
        assertThat(deletes).anyMatch(id -> id.endsWith("/attachments/502"));
        assertThat(deletes).anyMatch(id -> id.endsWith("/kb/1/answers/78/en"));

        // Run 3: hard-delete ticket 4823 and run full reconciliation crawl using the same state file
        admin("/mock/tickets/4823/delete", "{}");
        ZammadRepositoryConnector fullReconcile = connector(Map.of("crawlMode", "full"));
        List<MaterializedDoc> third = scanAll(fullReconcile);
        assertThat(third).singleElement().satisfies(m -> {
            assertThat(m.doc().action()).isEqualTo(DocumentAction.DELETE);
            assertThat(m.doc().id()).endsWith("/tickets/4823/public");
        });
    }

    @Test
    void authenticationModesOnBehalfOfAndRateLimitRetryOverHttp() throws Exception {
        // 1. OAuth2 Bearer + X-On-Behalf-Of
        ZammadProperties oauthProps = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", BASE,
                "authType", "oauth2",
                "accessToken", "zammad-oauth-token",
                "onBehalfOf", "audit@acme.com"
        ));
        try (ZammadClient client = new ZammadClient(oauthProps)) {
            assertThat(client.getCurrentUser().path("email").asText()).isEqualTo("crawler@acme.com");
        }
        assertThat(admin("/mock/stats", null).path("lastOnBehalfOf").asText()).isEqualTo("audit@acme.com");

        // 2. Basic Auth
        ZammadProperties basicProps = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", BASE,
                "authType", "basic",
                "username", "crawler@acme.com",
                "password", "secret"
        ));
        try (ZammadClient client = new ZammadClient(basicProps)) {
            assertThat(client.listGroups()).hasSize(3);
        }

        // 3. Transient HTTP 429 retry
        admin("/mock/faults", "{\"status\":429,\"count\":1,\"retryAfter\":0}");
        ZammadProperties retryProps = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", BASE,
                "authType", "token",
                "accessToken", "zammad-test-token",
                "maxRetries", "3",
                "initialBackoffMs", "10"
        ));
        try (ZammadClient client = new ZammadClient(retryProps)) {
            assertThat(client.getCurrentUser().path("id").asInt()).isEqualTo(2);
        }

        // 4. Invalid credentials rejected with HTTP 401
        ZammadProperties badProps = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", BASE,
                "authType", "token",
                "accessToken", "wrong-token",
                "maxRetries", "0"
        ));
        try (ZammadClient client = new ZammadClient(badProps)) {
            assertThatThrownBy(client::getCurrentUser)
                    .isInstanceOf(ZammadApiException.class)
                    .hasMessageContaining("401");
        }
        assertThat(admin("/mock/stats", null).path("authFailures").asInt()).isEqualTo(1);
    }

    @Test
    void discoveryExploreDryRunAndFetchDocumentEnforceVisibilityFilter() throws Exception {
        ZammadRepositoryConnector conn = connector(Map.of());

        VisibilityFilter customerFilter = nodes -> {
            boolean[] visible = new boolean[nodes.size()];
            for (int i = 0; i < nodes.size(); i++) {
                var sec = nodes.get(i).security();
                visible[i] = sec != null && sec.permissions().stream()
                        .anyMatch(r -> "claire.dupont@clientcorp.eu".equals(r.identity()));
            }
            return visible;
        };

        // Explore from root with ALL visibility finds active groups (4 and 5) and their tickets
        DiscoveryResult explore = conn.explore(
                new ExploreRequest("/", 2, 50, true, Duration.ofSeconds(10)),
                VisibilityFilter.ALL);
        assertThat(explore.nodes())
                .anyMatch(n -> "group:4".equals(n.sourceId()))
                .anyMatch(n -> "group:5".equals(n.sourceId()))
                .anyMatch(n -> n.id().endsWith("/tickets/4821/public"))
                .anyMatch(n -> n.id().endsWith("/tickets/4821/internal"));

        // Dry run for customer sees only her public ticket (4821/public); hides internal notes, other tickets, and fail-closed 4824
        DryRunResult dryRun = conn.dryRun(
                new DryRunRequest("/", 10, 100, Duration.ofSeconds(10)),
                customerFilter);
        assertThat(dryRun.matchCount()).isEqualTo(1);
        assertThat(dryRun.hiddenCount()).isGreaterThanOrEqualTo(5);
        assertThat(dryRun.samples()).singleElement()
                .satisfies(node -> assertThat(node.id()).endsWith("/tickets/4821/public"));

        // crawlStart resolves active group folder
        DiscoveryNode group4 = conn.crawlStart("group:4", VisibilityFilter.ALL);
        assertThat(group4.name()).isEqualTo("Tier-2-Identity-Security");

        // fetchDocument enforces visibility and reports Gone for deleted tickets
        assertThat(conn.fetchDocument("4821:public", customerFilter)).isInstanceOf(FetchedDocument.Found.class);
        assertThatThrownBy(() -> conn.fetchDocument("4821:internal", customerFilter))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conn.fetchDocument("4824:public", customerFilter))
                .isInstanceOf(IllegalArgumentException.class);

        admin("/mock/tickets/4823/delete", "{}");
        assertThat(conn.fetchDocument("4823:public", VisibilityFilter.ALL))
                .isInstanceOf(FetchedDocument.Gone.class);
        conn.disconnect();
    }

    @Test
    void granularityAndScopeFiltersHonorGroupStateAndPerArticleModes() throws Exception {
        // PER_ARTICLE restricted to Tier-2-Identity-Security + open tickets, no KB/attachments
        ZammadRepositoryConnector scoped = connector(Map.of(
                "groupNames", "Tier-2-Identity-Security",
                "ticketStates", "open",
                "articleGranularity", "PER_ARTICLE",
                "includeAttachments", "false",
                "includeKnowledgeBase", "false"
        ));
        List<MaterializedDoc> first = scanAll(scoped);
        // Tickets 4821 (2 articles) and 4822 (2 articles) -> 4 article documents
        assertThat(first).hasSize(4);
        assertThat(first).allSatisfy(m -> assertThat(m.doc().id()).contains("/articles/"));

        // Moving ticket 4822 to closed (state_id=4) must emit DELETE tombstones for its two article documents
        admin("/mock/tickets/4822/update", "{\"state_id\":4}");
        List<MaterializedDoc> second = scanAll(scoped);
        assertThat(second).hasSize(2);
        assertThat(second).allSatisfy(m -> {
            assertThat(m.doc().action()).isEqualTo(DocumentAction.DELETE);
            assertThat(m.doc().id()).contains("/tickets/4822/articles/");
        });
    }
}
