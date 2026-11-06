/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.discovery.CountAccuracy;
import org.opencrawling.core.connector.discovery.DiscoveryNode;
import org.opencrawling.core.connector.discovery.DiscoveryResult;
import org.opencrawling.core.connector.discovery.DryRunRequest;
import org.opencrawling.core.connector.discovery.DryRunResult;
import org.opencrawling.core.connector.discovery.ExploreRequest;
import org.opencrawling.core.connector.discovery.TruncationReason;
import org.opencrawling.core.connector.discovery.VisibilityFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Discovery and dry run against a small in-memory Alfresco served over its REST and Search APIs:
 *
 * <pre>
 * Company Home                 root
 *   Sites/                     sites
 *     finance-site/            fsite
 *       documentLibrary/       flib   (GROUP_finance, inheritance off)
 *         budget.pdf           d1     (GROUP_finance)
 *         Archive/             f2     (GROUP_finance)
 *           old.pdf            d2     (GROUP_finance)
 *   Public/                    pub    (GROUP_EVERYONE)
 *     a.txt                    d3
 *     b.txt                    d4     (bob DENIED)
 *     image.png                d8
 *   Data Dictionary/           dd     (excluded)
 *     secret.txt               d5
 *   noperm.txt                 d6     (no permissions returned)
 *   denied-only.txt            d7     (only a DENIED entry)
 *   empty.txt                  d9     (no content)
 * </pre>
 */
class AlfrescoDiscoveryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private record Item(String id, String parent, String name, boolean folder, String mimeType, String permissions) {}

    private final Map<String, Item> items = new LinkedHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<JsonNode> searches = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private String url;
    private int searchStatus = 200;

    private static final String FINANCE = "{\"isInheritanceEnabled\":true,\"inherited\":[{\"authorityId\":\"GROUP_finance\",\"name\":\"Consumer\",\"accessStatus\":\"ALLOWED\"}]}";
    private static final String EVERYONE = "{\"isInheritanceEnabled\":true,\"inherited\":[{\"authorityId\":\"GROUP_EVERYONE\",\"name\":\"Consumer\",\"accessStatus\":\"ALLOWED\"}]}";

    @BeforeEach
    void setUp() throws IOException {
        add("root", null, "Company Home", true, null, EVERYONE);
        add("sites", "root", "Sites", true, null, EVERYONE);
        add("fsite", "sites", "finance-site", true, null, EVERYONE);
        add("flib", "fsite", "documentLibrary", true, null,
                "{\"isInheritanceEnabled\":false,\"inherited\":[{\"authorityId\":\"GROUP_EVERYONE\",\"name\":\"Consumer\",\"accessStatus\":\"ALLOWED\"}],"
                        + "\"locallySet\":[{\"authorityId\":\"GROUP_finance\",\"name\":\"Collaborator\",\"accessStatus\":\"ALLOWED\"}]}");
        add("d1", "flib", "budget.pdf", false, "application/pdf", FINANCE);
        add("f2", "flib", "Archive", true, null, FINANCE);
        add("d2", "f2", "old.pdf", false, "application/pdf", FINANCE);
        add("pub", "root", "Public", true, null, EVERYONE);
        add("d3", "pub", "a.txt", false, "text/plain", EVERYONE);
        add("d4", "pub", "b.txt", false, "text/plain",
                "{\"isInheritanceEnabled\":true,\"inherited\":[{\"authorityId\":\"GROUP_EVERYONE\",\"name\":\"Consumer\",\"accessStatus\":\"ALLOWED\"}],"
                        + "\"locallySet\":[{\"authorityId\":\"bob\",\"name\":\"Consumer\",\"accessStatus\":\"DENIED\"}]}");
        add("d8", "pub", "image.png", false, "image/png", EVERYONE);
        add("dd", "root", "Data Dictionary", true, null, EVERYONE);
        add("d5", "dd", "secret.txt", false, "text/plain", "{\"isInheritanceEnabled\":true,\"inherited\":[{\"authorityId\":\"admin\",\"name\":\"Coordinator\",\"accessStatus\":\"ALLOWED\"}]}");
        add("d6", "root", "noperm.txt", false, "text/plain", null);
        add("d7", "root", "denied-only.txt", false, "text/plain",
                "{\"isInheritanceEnabled\":false,\"locallySet\":[{\"authorityId\":\"carol\",\"name\":\"Consumer\",\"accessStatus\":\"DENIED\"}]}");
        add("d9", "root", "empty.txt", false, null, EVERYONE);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api", this::handle);
        server.start();
        url = "http://localhost:" + server.getAddress().getPort() + "/api";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void add(String id, String parent, String name, boolean folder, String mimeType, String permissions) {
        items.put(id, new Item(id, parent, name, folder, mimeType, permissions));
    }

    private List<Item> ancestors(Item item) {
        List<Item> chain = new ArrayList<>();
        for (Item at = item.parent() == null ? null : items.get(item.parent()); at != null;
             at = at.parent() == null ? null : items.get(at.parent())) {
            chain.add(0, at);
        }
        return chain;
    }

    private ObjectNode json(Item item, boolean withPath, boolean withPermissions) throws IOException {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("id", item.id());
        entry.put("name", item.name());
        entry.put("isFolder", item.folder());
        entry.put("isFile", !item.folder());
        entry.put("nodeType", item.folder() ? "cm:folder" : "cm:content");
        entry.put("modifiedAt", "2026-11-05T10:00:00.000+0000");
        if (item.parent() != null) {
            entry.put("parentId", item.parent());
        }
        if (!item.folder() && item.mimeType() != null) {
            entry.putObject("content").put("mimeType", item.mimeType()).put("sizeInBytes", 42);
        }
        if (withPath) {
            List<Item> chain = ancestors(item);
            ObjectNode path = entry.putObject("path");
            StringBuilder name = new StringBuilder();
            ArrayNode elements = path.putArray("elements");
            for (Item ancestor : chain) {
                name.append('/').append(ancestor.name());
                elements.addObject().put("id", ancestor.id()).put("name", ancestor.name());
            }
            path.put("name", name.toString());
        }
        if (withPermissions && item.permissions() != null) {
            entry.set("permissions", JSON.readTree(item.permissions()));
        }
        return entry;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
        String rawQuery = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = new HashMap<>();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                String[] kv = pair.split("=", 2);
                params.put(kv[0], kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        requests.add(exchange.getRequestMethod() + " " + path + "?" + rawQuery);
        String include = params.getOrDefault("include", "");
        boolean withPath = include.contains("path");
        boolean withPermissions = include.contains("permissions");

        if (path.equals("/api/search")) {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            searches.add(body);
            if (searchStatus != 200) {
                send(exchange, searchStatus, "{\"error\":{\"briefSummary\":\"bad query at internal-host\"}}");
                return;
            }
            boolean perms = body.path("include").toString().contains("permissions");
            List<Item> files = items.values().stream().filter(item -> !item.folder()).toList();
            send(exchange, 200, page(files, body.path("paging").path("skipCount").asInt(0),
                    body.path("paging").path("maxItems").asInt(100), true, perms));
            return;
        }
        String[] segments = path.substring("/api/nodes/".length()).split("/");
        String id = segments[0].equals("-root-") ? "root" : segments[0];
        Item target = items.get(id);
        if (target != null && params.containsKey("relativePath")) {
            for (String name : params.get("relativePath").split("/")) {
                Item parent = target;
                target = items.values().stream().filter(item -> parent.id().equals(item.parent()) && item.name().equals(name))
                        .findFirst().orElse(null);
                if (target == null) {
                    break;
                }
            }
        }
        if (target == null) {
            send(exchange, 404, "{\"error\":{\"statusCode\":404}}");
            return;
        }
        if (segments.length > 1 && segments[1].equals("children")) {
            Item folder = target;
            List<Item> children = items.values().stream().filter(item -> folder.id().equals(item.parent())).toList();
            send(exchange, 200, page(children, Integer.parseInt(params.getOrDefault("skipCount", "0")),
                    Integer.parseInt(params.getOrDefault("maxItems", "100")), withPath, withPermissions));
        } else {
            ObjectNode body = JSON.createObjectNode();
            body.set("entry", json(target, withPath, withPermissions));
            send(exchange, 200, JSON.writeValueAsString(body));
        }
    }

    private String page(List<Item> all, int skip, int max, boolean withPath, boolean withPermissions) throws IOException {
        List<Item> slice = all.subList(Math.min(skip, all.size()), Math.min(skip + max, all.size()));
        ObjectNode body = JSON.createObjectNode();
        ObjectNode list = body.putObject("list");
        list.putObject("pagination").put("count", slice.size()).put("hasMoreItems", skip + max < all.size())
                .put("totalItems", all.size()).put("skipCount", skip).put("maxItems", max);
        ArrayNode entries = list.putArray("entries");
        for (Item item : slice) {
            entries.addObject().set("entry", json(item, withPath, withPermissions));
        }
        return JSON.writeValueAsString(body);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private AlfrescoRepositoryConnector connector(AlfrescoCrawlMode mode, String rootPath, String rootId, String site,
                                                  boolean includeSubfolders, Set<String> mimeTypes, int batchSize) {
        AlfrescoRepositoryConnector connector = new AlfrescoRepositoryConnector(url, "user", "secret", batchSize, mode,
                rootPath, rootId, site, includeSubfolders, Set.of("Data Dictionary"), "TYPE:'cm:content'", "afts",
                false, true, 1024, mimeTypes, 5);
        connector.setHttpClient(HttpClient.newHttpClient());
        return connector;
    }

    private AlfrescoRepositoryConnector connector() {
        return connector(AlfrescoCrawlMode.FOLDER, "/", "", "", true, Set.of(), 100);
    }

    private static List<String> names(List<DiscoveryNode> nodes) {
        return nodes.stream().map(DiscoveryNode::name).toList();
    }

    /** Shows items whose permissions allow one of the identities and deny none of them; unknown is hidden. */
    private static VisibilityFilter readableBy(String... identities) {
        Set<String> who = Set.of(identities);
        return nodes -> {
            boolean[] visible = new boolean[nodes.size()];
            for (int i = 0; i < visible.length; i++) {
                DiscoveryNode node = nodes.get(i);
                visible[i] = node.security() != null
                        && node.security().permissions().stream().noneMatch(rule -> rule.access().equals("deny") && who.contains(rule.identity()))
                        && node.security().permissions().stream().anyMatch(rule -> !rule.access().equals("deny")
                        && (who.contains(rule.identity()) || rule.identity().equals("public")));
            }
            return visible;
        };
    }

    @Test
    void exploreListsTheFirstLevelOfFoldersAndSaysDeeperOnesWereNotOpened() {
        DiscoveryResult result = connector().explore(new ExploreRequest(null, 1, 100, false, TIMEOUT));

        assertThat(names(result.nodes())).containsExactly("Sites", "Public");
        assertThat(result.truncationReason()).isEqualTo(TruncationReason.MAX_DEPTH);
        DiscoveryNode sites = result.nodes().get(0);
        assertThat(sites.id()).isEqualTo("alfresco-folder:sites");
        assertThat(sites.sourceId()).isEqualTo("sites");
        assertThat(sites.parentId()).isEqualTo("root");
        assertThat(sites.path()).isEqualTo("/Company Home/Sites");
        assertThat(sites.lastModified()).isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));
    }

    @Test
    void exploreWalksBreadthFirstWithTheCrawlersRules() {
        DiscoveryResult result = connector().explore(new ExploreRequest(null, 6, 100, true, TIMEOUT));

        // Data Dictionary is excluded and empty.txt has no content, as when crawling.
        assertThat(names(result.nodes())).containsExactly(
                "Sites", "Public", "noperm.txt", "denied-only.txt",
                "finance-site", "a.txt", "b.txt", "image.png",
                "documentLibrary", "budget.pdf", "Archive", "old.pdf");
        assertThat(result.truncated()).isFalse();
        DiscoveryNode budget = result.nodes().get(9);
        assertThat(budget.type()).isEqualTo(DiscoveryNode.NodeType.DOCUMENT);
        assertThat(budget.depth()).isEqualTo(4);
        assertThat(budget.mimeType()).isEqualTo("application/pdf");
        assertThat(budget.sizeBytes()).isEqualTo(42L);
        assertThat(budget.path()).isEqualTo("/Company Home/Sites/finance-site/documentLibrary/budget.pdf");
    }

    @Test
    void documentIdsMatchTheIdsTheCrawlerIndexes() {
        DiscoveryNode budget = connector().explore(new ExploreRequest("/Sites/finance-site/documentLibrary", 1, 10, true, TIMEOUT))
                .nodes().get(0);

        assertThat(budget.name()).isEqualTo("budget.pdf");
        assertThat(budget.id()).isEqualTo("d1");
    }

    @Test
    void exploreStopsAtTheNodeLimitAndReadsEveryPage() {
        assertThat(connector().explore(new ExploreRequest(null, 6, 3, true, TIMEOUT)).truncationReason())
                .isEqualTo(TruncationReason.MAX_NODES);

        DiscoveryResult paged = connector(AlfrescoCrawlMode.FOLDER, "/", "", "", true, Set.of(), 1)
                .explore(new ExploreRequest(null, 6, 100, true, TIMEOUT));
        assertThat(paged.nodes()).hasSize(12);
        assertThat(requests).anyMatch(request -> request.contains("skipCount=3"));
    }

    @Test
    void exploreReportsATimeout() {
        DiscoveryResult result = connector().explore(new ExploreRequest(null, 6, 100, true, Duration.ofNanos(1)));

        assertThat(result.nodes()).isEmpty();
        assertThat(result.truncationReason()).isEqualTo(TruncationReason.TIMEOUT);
    }

    @Test
    void permissionsAreAlwaysRequestedAndMappedForDiscovery() {
        List<DiscoveryNode> nodes = connector().explore(new ExploreRequest(null, 2, 100, true, TIMEOUT)).nodes();

        assertThat(requests).filteredOn(request -> request.contains("/children"))
                .isNotEmpty().allMatch(request -> request.contains("include=path,permissions"));
        DiscoveryNode noPermissions = nodes.stream().filter(node -> node.name().equals("noperm.txt")).findFirst().orElseThrow();
        DiscoveryNode deniedOnly = nodes.stream().filter(node -> node.name().equals("denied-only.txt")).findFirst().orElseThrow();
        DiscoveryNode b = nodes.stream().filter(node -> node.name().equals("b.txt")).findFirst().orElseThrow();
        assertThat(noPermissions.security()).isNull();
        assertThat(deniedOnly.security()).isNull();
        assertThat(b.security().permissions()).anySatisfy(rule -> {
            assertThat(rule.identity()).isEqualTo("bob");
            assertThat(rule.access()).isEqualTo("deny");
        }).anySatisfy(rule -> assertThat(rule.identity()).isEqualTo("public"));
    }

    @Test
    void inheritedPermissionsCountOnlyWhileInheritanceIsOn() throws IOException {
        var library = AlfrescoRepositoryConnector.discoverySecurity(JSON.readTree(items.get("flib").permissions()));

        assertThat(library.permissions()).singleElement().satisfies(rule -> {
            assertThat(rule.identity()).isEqualTo("GROUP_finance");
            assertThat(rule.identityType()).isEqualTo("group");
            assertThat(rule.access()).isEqualTo("write");
        });
        assertThat(AlfrescoRepositoryConnector.discoverySecurity(JSON.readTree("{}"))).isNull();
        assertThat(AlfrescoRepositoryConnector.discoverySecurity(JSON.missingNode())).isNull();
    }

    @Test
    void exploreStartsFromAFolderByPathOrIdAndFromTheConfiguredSite() {
        assertThat(names(connector().explore(new ExploreRequest("/Public", 1, 10, true, TIMEOUT)).nodes()))
                .containsExactly("a.txt", "b.txt", "image.png");
        assertThat(names(connector().explore(new ExploreRequest("f2", 1, 10, true, TIMEOUT)).nodes()))
                .containsExactly("old.pdf");
        assertThat(names(connector(AlfrescoCrawlMode.FOLDER, "/", "", "finance-site", true, Set.of(), 100)
                .explore(new ExploreRequest(null, 1, 10, true, TIMEOUT)).nodes()))
                .containsExactly("budget.pdf", "Archive");
        assertThat(names(connector(AlfrescoCrawlMode.FOLDER, "/", "pub", "", true, Set.of(), 100)
                .explore(new ExploreRequest(null, 1, 10, true, TIMEOUT)).nodes()))
                .containsExactly("a.txt", "b.txt", "image.png");
    }

    @Test
    void startsOutsideTheScopeAllReadLikeMissingFolders() {
        AlfrescoRepositoryConnector siteOnly = connector(AlfrescoCrawlMode.FOLDER, "/", "", "finance-site", true, Set.of(), 100);

        assertThatThrownBy(() -> siteOnly.explore(new ExploreRequest("/Public", 1, 10, true, TIMEOUT)))
                .hasMessage("No Alfresco folder '/Public' in this connector's scope.");
        assertThatThrownBy(() -> siteOnly.explore(new ExploreRequest("pub", 1, 10, true, TIMEOUT)))
                .hasMessage("No Alfresco folder 'pub' in this connector's scope.");
        assertThatThrownBy(() -> connector().explore(new ExploreRequest("/Data Dictionary", 1, 10, true, TIMEOUT)))
                .hasMessage("No Alfresco folder '/Data Dictionary' in this connector's scope.");
        assertThatThrownBy(() -> connector().explore(new ExploreRequest("d1", 1, 10, true, TIMEOUT)))
                .hasMessage("No Alfresco folder 'd1' in this connector's scope.");
        assertThatThrownBy(() -> connector().explore(new ExploreRequest("/Nowhere", 1, 10, true, TIMEOUT)))
                .hasMessage("No Alfresco folder '/Nowhere' in this connector's scope.");
        assertThatThrownBy(() -> connector().explore(new ExploreRequest("pub/../dd", 1, 10, true, TIMEOUT)))
                .hasMessage("No Alfresco folder 'pub/../dd' in this connector's scope.");
        assertThat(requests).noneMatch(request -> request.contains(".."));
    }

    @Test
    void aFolderBelowAnExcludedOneIsOutOfScope() {
        add("dd-sub", "dd", "Scripts", true, null, EVERYONE);

        assertThatThrownBy(() -> connector().explore(new ExploreRequest("dd-sub", 1, 10, true, TIMEOUT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No Alfresco folder");
    }

    @Test
    void hiddenFoldersAreNeitherListedNorOpened() {
        DiscoveryResult result = connector().explore(new ExploreRequest(null, 6, 100, true, TIMEOUT), readableBy("bob"));

        // finance-site is readable by everyone, but its documentLibrary only by finance; b.txt denies bob.
        assertThat(names(result.nodes())).containsExactly("Sites", "Public", "finance-site", "a.txt", "image.png");
        // noperm.txt, denied-only.txt, b.txt and documentLibrary.
        assertThat(result.hiddenCount()).isEqualTo(4);
        assertThat(requests).noneMatch(request -> request.contains("/nodes/flib/children"));
    }

    @Test
    void aHiddenStartFolderReadsLikeOneThatDoesNotExist() {
        Throwable hidden = catchThrowable(() -> connector().explore(
                new ExploreRequest("/Sites/finance-site/documentLibrary", 1, 10, true, TIMEOUT), readableBy("bob")));

        assertThat(hidden).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No Alfresco folder '/Sites/finance-site/documentLibrary' in this connector's scope.");
        assertThatThrownBy(() -> connector().dryRun(new DryRunRequest("flib", 1, 10, TIMEOUT), readableBy("bob")))
                .hasMessage("No Alfresco folder 'flib' in this connector's scope.");
    }

    @Test
    void aFilterThatCannotDecideStopsDiscovery() {
        VisibilityFilter broken = nodes -> { throw new IllegalStateException("authorization unavailable"); };

        assertThatThrownBy(() -> connector().explore(new ExploreRequest(null, 1, 10, true, TIMEOUT), broken))
                .hasMessageContaining("authorization unavailable");
        assertThatThrownBy(() -> connector().dryRun(new DryRunRequest("TYPE:'cm:content'", 1, 10, TIMEOUT), broken))
                .hasMessageContaining("authorization unavailable");
    }

    @Test
    void aFolderDryRunCountsWhatTheCrawlerWouldTakeAndTheCallerMayRead() {
        DryRunResult all = connector().dryRun(new DryRunRequest(null, 3, 100, TIMEOUT));
        // a, b, image, noperm, denied-only, budget, old; not secret (excluded) nor empty (no content).
        assertThat(all.matchCount()).isEqualTo(7);
        assertThat(all.countAccuracy()).isEqualTo(CountAccuracy.EXACT);
        assertThat(all.samples()).hasSize(3);

        DryRunResult bob = connector().dryRun(new DryRunRequest(null, 10, 100, TIMEOUT), readableBy("bob"));
        assertThat(names(bob.samples())).containsExactlyInAnyOrder("a.txt", "image.png");
        assertThat(bob.matchCount()).isEqualTo(2);
        assertThat(bob.hiddenCount()).isEqualTo(5);
        assertThat(bob.samples()).allMatch(sample -> sample.parentId() == null);
    }

    @Test
    void aFolderDryRunFollowsTheSubfolderAndMimeTypeSettings() {
        assertThat(connector(AlfrescoCrawlMode.FOLDER, "/", "", "", false, Set.of(), 100)
                .dryRun(new DryRunRequest("/Sites/finance-site/documentLibrary", 10, 100, TIMEOUT)).matchCount()).isEqualTo(1);
        assertThat(connector(AlfrescoCrawlMode.FOLDER, "/", "", "", true, Set.of("text/plain"), 100)
                .dryRun(new DryRunRequest(null, 10, 100, TIMEOUT)).matchCount()).isEqualTo(4);
    }

    @Test
    void aDryRunStopsAtTheScanLimitWithALowerBound() {
        DryRunResult result = connector().dryRun(new DryRunRequest(null, 10, 2, TIMEOUT));

        assertThat(result.matchCount()).isEqualTo(2);
        assertThat(result.countAccuracy()).isEqualTo(CountAccuracy.LOWER_BOUND);
        assertThat(result.truncationReason()).isEqualTo(TruncationReason.MAX_SCAN);
    }

    @Test
    void aQueryDryRunChecksEveryResultSoItsCountIsExact() {
        DryRunResult result = connector().dryRun(new DryRunRequest("TYPE:'cm:content'", 10, 100, TIMEOUT), readableBy("bob"));

        // The search returns every file; bob may read a.txt, image.png and empty.txt.
        assertThat(names(result.samples())).containsExactly("a.txt", "image.png", "empty.txt");
        assertThat(result.matchCount()).isEqualTo(3);
        assertThat(result.countAccuracy()).isEqualTo(CountAccuracy.EXACT);
        assertThat(result.hiddenCount()).isEqualTo(6);
        assertThat(searches).singleElement().satisfies(body -> {
            assertThat(body.path("query").path("language").asText()).isEqualTo("afts");
            assertThat(body.path("query").path("query").asText()).isEqualTo("TYPE:'cm:content'");
            assertThat(body.path("include").toString()).contains("permissions");
        });
    }

    @Test
    void aQueryDryRunTakesTheCrawlersQueryPrefixesAndConfiguredQuery() {
        connector().dryRun(new DryRunRequest("cmis:SELECT * FROM cmis:document", 1, 100, TIMEOUT));
        connector(AlfrescoCrawlMode.QUERY, "/", "", "", true, Set.of(), 100).dryRun(new DryRunRequest(null, 1, 100, TIMEOUT));

        assertThat(searches).extracting(body -> body.path("query").path("language").asText()).containsExactly("cmis", "afts");
        assertThat(searches.get(0).path("query").path("query").asText()).isEqualTo("SELECT * FROM cmis:document");
    }

    @Test
    void onlyBoundedQueriesInTheSearchLanguagesAreAccepted() {
        assertThatThrownBy(() -> AlfrescoRepositoryConnector.readOnlyQuery(
                new AlfrescoRepositoryConnector.QueryInfo("x", "sql"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlfrescoRepositoryConnector.readOnlyQuery(
                new AlfrescoRepositoryConnector.QueryInfo(" ", "afts"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlfrescoRepositoryConnector.readOnlyQuery(new AlfrescoRepositoryConnector.QueryInfo(
                "x".repeat(AlfrescoRepositoryConnector.MAX_QUERY_LENGTH + 1), "afts"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(AlfrescoRepositoryConnector.readOnlyQuery(new AlfrescoRepositoryConnector.QueryInfo("TEXT:x", "AFTS")).language())
                .isEqualTo("afts");
    }

    @Test
    void aQueryAlfrescoRejectsIsABadRequestWithoutItsDetails() {
        searchStatus = 400;

        assertThatThrownBy(() -> connector().dryRun(new DryRunRequest("TEXT:(", 1, 10, TIMEOUT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Alfresco did not accept the query.");
    }
}
