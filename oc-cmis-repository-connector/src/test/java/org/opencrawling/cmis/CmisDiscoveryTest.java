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
package org.opencrawling.cmis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Discovery and dry run against a small in-memory CMIS repository served over the Browser Binding:
 *
 * <pre>
 * /                      root
 *   Finance/             f1   (GROUP_finance)
 *     budget.pdf         d1   (GROUP_finance)
 *     Archive/           f2   (GROUP_finance)
 *       old.pdf          d2   (GROUP_finance)
 *   Public/              f3   (GROUP_EVERYONE)
 *     a.txt              d3
 *     b.txt              d4
 *   System/              sys  (excluded)
 *     secret.txt         d5
 *   noacl.txt            d6   (no ACL returned)
 *   draft.txt            d7   (not the latest major version)
 * </pre>
 */
class CmisDiscoveryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private record Item(String id, String parent, String name, boolean folder, String acl, boolean latestMajor) {}

    private final Map<String, Item> items = new LinkedHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void setUp() throws IOException {
        add("root", null, "", true, "GROUP_EVERYONE", true);
        add("f1", "root", "Finance", true, "GROUP_finance", true);
        add("d1", "f1", "budget.pdf", false, "GROUP_finance", true);
        add("f2", "f1", "Archive", true, "GROUP_finance", true);
        add("d2", "f2", "old.pdf", false, "GROUP_finance", true);
        add("f3", "root", "Public", true, "GROUP_EVERYONE", true);
        add("d3", "f3", "a.txt", false, "GROUP_EVERYONE", true);
        add("d4", "f3", "b.txt", false, "GROUP_EVERYONE", true);
        add("sys", "root", "System", true, "admin", true);
        add("d5", "sys", "secret.txt", false, "admin", true);
        add("d6", "root", "noacl.txt", false, null, true);
        add("d7", "root", "draft.txt", false, "GROUP_EVERYONE", false);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/browser", this::handle);
        server.start();
        endpoint = "http://localhost:" + server.getAddress().getPort() + "/browser";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void add(String id, String parent, String name, boolean folder, String acl, boolean latestMajor) {
        items.put(id, new Item(id, parent, name, folder, acl, latestMajor));
    }

    private String pathOf(Item item) {
        if (item.parent() == null) {
            return "/";
        }
        String parentPath = pathOf(items.get(item.parent()));
        return (parentPath.equals("/") ? "" : parentPath) + "/" + item.name();
    }

    private ObjectNode json(Item item, boolean withAcl) {
        ObjectNode object = JSON.createObjectNode();
        ObjectNode props = object.putObject("properties");
        props.putObject("cmis:objectId").put("value", item.id());
        props.putObject("cmis:name").put("value", item.name());
        props.putObject("cmis:baseTypeId").put("value", item.folder() ? "cmis:folder" : "cmis:document");
        props.putObject("cmis:lastModificationDate").put("value", 1_700_000_000_000L);
        if (item.folder()) {
            props.putObject("cmis:path").put("value", pathOf(item));
        } else {
            props.putObject("cmis:isLatestMajorVersion").put("value", item.latestMajor());
            props.putObject("cmis:contentStreamMimeType").put("value", "text/plain");
            props.putObject("cmis:contentStreamLength").put("value", 42);
        }
        if (withAcl && item.acl() != null) {
            ObjectNode acl = object.putObject("acl");
            ObjectNode ace = acl.putArray("aces").addObject();
            ace.putObject("principal").put("principalId", item.acl());
            ace.putArray("permissions").add("cmis:read");
            acl.put("isExact", true);
        }
        return object;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
        Map<String, String> params = new HashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                String[] kv = pair.split("=", 2);
                params.put(kv[0], kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        requests.add(path + "?" + rawQuery);
        String selector = params.getOrDefault("cmisselector", "");
        boolean withAcl = Boolean.parseBoolean(params.getOrDefault("includeACL", "false"));

        if (selector.equals("query")) {
            List<Item> docs = items.values().stream().filter(item -> !item.folder()).toList();
            send(exchange, 200, page(docs, params, "results", false));
            return;
        }
        Item target;
        if (params.containsKey("objectId")) {
            target = items.get(params.get("objectId"));
        } else {
            String objectPath = path.substring("/browser/repo/root".length());
            String wanted = objectPath.isEmpty() ? "/" : objectPath;
            target = items.values().stream().filter(item -> pathOf(item).equals(wanted)).findFirst().orElse(null);
        }
        if (target == null) {
            send(exchange, 404, "{\"exception\":\"objectNotFound\"}");
            return;
        }
        if (selector.equals("object")) {
            send(exchange, 200, JSON.writeValueAsString(json(target, withAcl)));
        } else if (selector.equals("children")) {
            List<Item> children = items.values().stream().filter(item -> target.id().equals(item.parent())).toList();
            send(exchange, 200, page(children, params, "objects", withAcl));
        } else {
            send(exchange, 400, "{}");
        }
    }

    private String page(List<Item> all, Map<String, String> params, String field, boolean withAcl) throws IOException {
        int skip = Integer.parseInt(params.getOrDefault("skipCount", "0"));
        int max = Integer.parseInt(params.getOrDefault("maxItems", "100"));
        List<Item> slice = all.subList(Math.min(skip, all.size()), Math.min(skip + max, all.size()));
        ObjectNode body = JSON.createObjectNode();
        ArrayNode array = body.putArray(field);
        for (Item item : slice) {
            if (field.equals("objects")) {
                array.addObject().set("object", json(item, withAcl));
            } else {
                array.add(json(item, false));
            }
        }
        body.put("hasMoreItems", skip + max < all.size());
        body.put("numItems", all.size());
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

    private CmisRepositoryConnector connector(String rootPath, boolean includeSubfolders, CmisCrawlMode mode,
                                              boolean changeLog, int batchSize) {
        CmisRepositoryConnector connector = new CmisRepositoryConnector(endpoint, "repo", "user", "secret",
                CmisBindingType.BROWSER, mode, rootPath, "", includeSubfolders, Set.of("/System"),
                "SELECT * FROM cmis:document", CmisVersionsMode.LATEST_MAJOR, false, 1024, false, false,
                changeLog, "", batchSize, 5);
        connector.setCmisClient(new CmisClient(endpoint, "user", "secret"), "repo");
        return connector;
    }

    private CmisRepositoryConnector connector() {
        return connector("/", true, CmisCrawlMode.FOLDER, false, 100);
    }

    private static List<String> names(List<DiscoveryNode> nodes) {
        return nodes.stream().map(DiscoveryNode::name).toList();
    }

    @Test
    void exploreListsTheFirstLevelOfFoldersAndSaysDeeperOnesWereNotOpened() {
        DiscoveryResult result = connector().explore(new ExploreRequest(null, 1, 100, false, TIMEOUT));

        assertThat(names(result.nodes())).containsExactly("Finance", "Public");
        assertThat(result.truncationReason()).isEqualTo(TruncationReason.MAX_DEPTH);
        DiscoveryNode finance = result.nodes().get(0);
        assertThat(finance.id()).isEqualTo("cmis://repo/folders/f1");
        assertThat(finance.sourceId()).isEqualTo("f1");
        assertThat(finance.parentId()).isEqualTo("root");
        assertThat(finance.path()).isEqualTo("/Finance");
        assertThat(finance.depth()).isEqualTo(1);
    }

    @Test
    void exploreWalksBreadthFirstWithTheCrawlersRules() {
        DiscoveryResult result = connector().explore(new ExploreRequest(null, 5, 100, true, TIMEOUT));

        // System is excluded and draft.txt is not the latest major version, as when crawling.
        assertThat(names(result.nodes())).containsExactly(
                "Finance", "Public", "noacl.txt", "budget.pdf", "Archive", "a.txt", "b.txt", "old.pdf");
        assertThat(result.truncated()).isFalse();
        DiscoveryNode budget = result.nodes().get(3);
        assertThat(budget.id()).isEqualTo("cmis://repo/documents/d1");
        assertThat(budget.type()).isEqualTo(DiscoveryNode.NodeType.DOCUMENT);
        assertThat(budget.depth()).isEqualTo(2);
        assertThat(budget.mimeType()).isEqualTo("text/plain");
        assertThat(budget.sizeBytes()).isEqualTo(42L);
        assertThat(budget.lastModified()).isNotNull();
    }

    @Test
    void documentIdsMatchTheIdsTheCrawlerIndexes() {
        DiscoveryNode budget = connector().explore(new ExploreRequest("/Finance", 1, 100, true, TIMEOUT)).nodes().stream()
                .filter(node -> node.name().equals("budget.pdf")).findFirst().orElseThrow();

        String crawledId = CmisDocumentBuilder.buildDocument("repo", json(items.get("d1"), true), null, true).id();
        assertThat(budget.id()).isEqualTo(crawledId);
    }

    @Test
    void exploreStopsAtTheNodeLimit() {
        DiscoveryResult result = connector().explore(new ExploreRequest(null, 5, 3, true, TIMEOUT));

        assertThat(names(result.nodes())).containsExactly("Finance", "Public", "noacl.txt");
        assertThat(result.truncationReason()).isEqualTo(TruncationReason.MAX_NODES);
    }

    @Test
    void exploreReadsEveryPageOfChildren() {
        DiscoveryResult result = connector("/", true, CmisCrawlMode.FOLDER, false, 1)
                .explore(new ExploreRequest(null, 5, 100, true, TIMEOUT));

        assertThat(result.nodes()).hasSize(8);
        assertThat(requests).anyMatch(request -> request.contains("skipCount=2"));
    }

    @Test
    void exploreReportsATimeout() {
        DiscoveryResult result = connector().explore(new ExploreRequest(null, 5, 100, true, Duration.ofNanos(1)));

        assertThat(result.nodes()).isEmpty();
        assertThat(result.truncationReason()).isEqualTo(TruncationReason.TIMEOUT);
    }

    @Test
    void eachNodeCarriesItsSourceAclAndAMissingAclStaysUnknown() {
        List<DiscoveryNode> nodes = connector().explore(new ExploreRequest(null, 1, 100, true, TIMEOUT)).nodes();

        DiscoveryNode finance = nodes.stream().filter(node -> node.name().equals("Finance")).findFirst().orElseThrow();
        assertThat(finance.security().permissions()).singleElement().satisfies(rule -> {
            assertThat(rule.identity()).isEqualTo("GROUP_finance");
            assertThat(rule.identityType()).isEqualTo("group");
            assertThat(rule.access()).isEqualTo("read");
        });
        DiscoveryNode noAcl = nodes.stream().filter(node -> node.name().equals("noacl.txt")).findFirst().orElseThrow();
        assertThat(noAcl.security()).isNull();
        assertThat(noAcl.toResourceRef().metadata()).containsOnlyKeys("documentId");
    }

    @Test
    void aclsAreRequestedEvenWhenTheCrawlerDoesNotIndexThem() {
        connector().explore(new ExploreRequest(null, 1, 100, true, TIMEOUT));

        assertThat(requests).filteredOn(request -> request.contains("cmisselector=children"))
                .isNotEmpty().allMatch(request -> request.contains("includeACL=true"));
    }

    @Test
    void anEmptyAclIsUnknownNotPublic() throws IOException {
        ObjectNode object = json(items.get("d3"), false);
        object.putObject("acl").putArray("aces");

        assertThat(CmisRepositoryConnector.discoverySecurity(object)).isNull();
        assertThat(CmisRepositoryConnector.discoverySecurity(json(items.get("d3"), false))).isNull();
        assertThat(CmisRepositoryConnector.discoverySecurity(json(items.get("d3"), true))).isNotNull();
    }

    @Test
    void exploreStartsFromAFolderByPathOrId() {
        assertThat(names(connector().explore(new ExploreRequest("/Finance", 1, 100, true, TIMEOUT)).nodes()))
                .containsExactly("budget.pdf", "Archive");
        assertThat(names(connector().explore(new ExploreRequest("f2", 1, 100, true, TIMEOUT)).nodes()))
                .containsExactly("old.pdf");
    }

    @Test
    void exploreRefusesStartsOutsideTheConnectorsScope() {
        CmisRepositoryConnector financeOnly = connector("/Finance", true, CmisCrawlMode.FOLDER, false, 100);

        assertThatThrownBy(() -> financeOnly.explore(new ExploreRequest("/Public", 1, 10, true, TIMEOUT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No CMIS folder");
        assertThatThrownBy(() -> financeOnly.explore(new ExploreRequest("f3", 1, 10, true, TIMEOUT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No CMIS folder");
        assertThatThrownBy(() -> connector().explore(new ExploreRequest("/System", 1, 10, true, TIMEOUT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No CMIS folder");
        assertThatThrownBy(() -> connector().explore(new ExploreRequest("d1", 1, 10, true, TIMEOUT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No CMIS folder");
        assertThatThrownBy(() -> connector().explore(new ExploreRequest("/Nowhere", 1, 10, true, TIMEOUT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No CMIS folder");
        assertThat(names(financeOnly.explore(new ExploreRequest(null, 1, 10, true, TIMEOUT)).nodes()))
                .containsExactly("budget.pdf", "Archive");
    }

    @Test
    void aSiblingWithTheSamePrefixIsNotInsideTheRoot() {
        assertThat(CmisRepositoryConnector.isWithin("/Finance2", "/Finance")).isFalse();
        assertThat(CmisRepositoryConnector.isWithin("/Finance/Archive", "/Finance")).isTrue();
        assertThat(CmisRepositoryConnector.isWithin("/finance", "/Finance/")).isTrue();
        assertThat(CmisRepositoryConnector.isWithin("/anything", "/")).isTrue();
    }

    @Test
    void aFolderDryRunCountsWhatTheCrawlerWouldTake() {
        DryRunResult all = connector().dryRun(new DryRunRequest(null, 2, 100, TIMEOUT));

        // budget, old, a, b and noacl; not secret (excluded) nor draft (older version).
        assertThat(all.matchCount()).isEqualTo(5);
        assertThat(all.countAccuracy()).isEqualTo(CountAccuracy.EXACT);
        assertThat(all.truncated()).isFalse();
        assertThat(all.samples()).hasSize(2).allMatch(node -> node.type() == DiscoveryNode.NodeType.DOCUMENT);

        DryRunResult finance = connector().dryRun(new DryRunRequest("/Finance", 10, 100, TIMEOUT));
        assertThat(names(finance.samples())).containsExactlyInAnyOrder("budget.pdf", "old.pdf");
        assertThat(finance.samples()).allMatch(node -> node.security() != null);
    }

    @Test
    void aFolderDryRunWithoutSubfoldersCountsOnlyTheFolder() {
        DryRunResult result = connector("/", false, CmisCrawlMode.FOLDER, false, 100)
                .dryRun(new DryRunRequest("/Finance", 10, 100, TIMEOUT));

        assertThat(names(result.samples())).containsExactly("budget.pdf");
        assertThat(result.matchCount()).isEqualTo(1);
    }

    @Test
    void aDryRunStopsAtTheScanLimitWithALowerBound() {
        DryRunResult result = connector().dryRun(new DryRunRequest(null, 10, 2, TIMEOUT));

        assertThat(result.matchCount()).isEqualTo(2);
        assertThat(result.countAccuracy()).isEqualTo(CountAccuracy.LOWER_BOUND);
        assertThat(result.truncationReason()).isEqualTo(TruncationReason.MAX_SCAN);
    }

    @Test
    void aQueryDryRunCountsEveryResultAndReadsEachSampleWithItsAcl() {
        DryRunResult result = connector().dryRun(new DryRunRequest("  SELECT * FROM cmis:document  ", 1, 100, TIMEOUT));

        // A query crawl takes every result, so the dry run counts them all.
        assertThat(result.matchCount()).isEqualTo(7);
        assertThat(result.countAccuracy()).isEqualTo(CountAccuracy.EXACT);
        assertThat(result.hiddenCount()).isZero();
        assertThat(result.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.id()).isEqualTo("cmis://repo/documents/d1");
            assertThat(sample.security()).isNotNull();
        });
        assertThat(requests).anyMatch(request -> request.contains("objectId=d1") && request.contains("includeACL=true"));
    }

    @Test
    void aDryRunWithoutAScopeUsesTheConfiguredQuery() {
        DryRunResult result = connector("/", true, CmisCrawlMode.QUERY, false, 100)
                .dryRun(new DryRunRequest(null, 0, 100, TIMEOUT));

        assertThat(result.matchCount()).isEqualTo(7);
        assertThat(requests).anyMatch(request -> request.contains("cmisselector=query"));
    }

    @Test
    void aChangeLogConnectorNeedsAnExplicitScope() {
        CmisRepositoryConnector changeLog = connector("/", true, CmisCrawlMode.FOLDER, true, 100);

        assertThatThrownBy(() -> changeLog.dryRun(new DryRunRequest(null, 1, 10, TIMEOUT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("change log");
        assertThat(changeLog.dryRun(new DryRunRequest("/Public", 1, 10, TIMEOUT)).matchCount()).isEqualTo(2);
    }

    @Test
    void onlyASingleSelectIsAcceptedAsAQuery() {
        assertThat(CmisRepositoryConnector.readOnlyQuery("select * from cmis:document")).isEqualTo("select * from cmis:document");
        assertThatThrownBy(() -> CmisRepositoryConnector.readOnlyQuery("SELECT * FROM cmis:document; SELECT * FROM cmis:folder"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CmisRepositoryConnector.readOnlyQuery("SELECT " + "x".repeat(CmisRepositoryConnector.MAX_QUERY_LENGTH)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CmisRepositoryConnector.readOnlyQuery("DELETE FROM cmis:document"))
                .isInstanceOf(IllegalArgumentException.class);
        // A scope that is not a SELECT is taken as a folder, and a folder that does not exist is refused.
        assertThatThrownBy(() -> connector().dryRun(new DryRunRequest("DELETE FROM cmis:document", 1, 10, TIMEOUT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No CMIS folder");
    }

    // ---- Visibility filter ----

    /** Shows items whose ACL names one of the identities; an unknown ACL is hidden. */
    private static VisibilityFilter readableBy(String... identities) {
        Set<String> allowed = Set.of(identities);
        return nodes -> {
            boolean[] visible = new boolean[nodes.size()];
            for (int i = 0; i < visible.length; i++) {
                DiscoveryNode node = nodes.get(i);
                visible[i] = node.security() != null
                        && node.security().permissions().stream().anyMatch(rule -> allowed.contains(rule.identity()));
            }
            return visible;
        };
    }

    @Test
    void hiddenFoldersAreNeitherListedNorOpened() {
        DiscoveryResult result = connector().explore(new ExploreRequest(null, 5, 100, true, TIMEOUT), readableBy("GROUP_EVERYONE"));

        assertThat(names(result.nodes())).containsExactly("Public", "a.txt", "b.txt");
        // Finance and noacl.txt; nothing below Finance was even read.
        assertThat(result.hiddenCount()).isEqualTo(2);
        assertThat(requests).noneMatch(request -> request.contains("objectId=f1&cmisselector=children"));
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void theNodeLimitCountsOnlyVisibleNodes() {
        DiscoveryResult result = connector().explore(new ExploreRequest(null, 5, 3, true, TIMEOUT), readableBy("GROUP_EVERYONE"));

        assertThat(names(result.nodes())).containsExactly("Public", "a.txt", "b.txt");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void aHiddenStartFolderReadsLikeOneThatDoesNotExist() {
        VisibilityFilter everyone = readableBy("GROUP_EVERYONE");

        Throwable hidden = catchThrowable(() -> connector().explore(new ExploreRequest("/Finance", 1, 10, true, TIMEOUT), everyone));
        Throwable absent = catchThrowable(() -> connector().explore(new ExploreRequest("/Nowhere", 1, 10, true, TIMEOUT), everyone));

        assertThat(hidden).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No CMIS folder '/Finance' in this connector's scope.");
        assertThat(absent).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No CMIS folder '/Nowhere' in this connector's scope.");
        assertThatThrownBy(() -> connector().dryRun(new DryRunRequest("/Finance", 1, 10, TIMEOUT), everyone))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(hidden.getMessage());
    }

    @Test
    void outOfScopeAndNonFolderStartsReadLikeMissingOnes() {
        CmisRepositoryConnector financeOnly = connector("/Finance", true, CmisCrawlMode.FOLDER, false, 100);

        assertThatThrownBy(() -> financeOnly.explore(new ExploreRequest("/Public", 1, 10, true, TIMEOUT)))
                .hasMessage("No CMIS folder '/Public' in this connector's scope.");
        assertThatThrownBy(() -> connector().explore(new ExploreRequest("d1", 1, 10, true, TIMEOUT)))
                .hasMessage("No CMIS folder 'd1' in this connector's scope.");
    }

    // ---- Crawl start ----

    @Test
    void aCrawlStartIsAVisibleFolderInScopeAndTheScanTakesItsId() {
        DiscoveryNode start = connector().crawlStart("/Finance", readableBy("GROUP_finance"));

        assertThat(start.sourceId()).isEqualTo("f1");
        assertThat(start.type()).isEqualTo(DiscoveryNode.NodeType.FOLDER);
        assertThat(connector().scan(start.sourceId()).map(doc -> doc.id()).collectList().block())
                .containsExactlyInAnyOrder("cmis://repo/documents/d1", "cmis://repo/documents/d2");
        assertThat(connector().crawlStart(null, VisibilityFilter.ALL).sourceId()).isEqualTo("root");
    }

    @Test
    void aCrawlStartTheCallerCannotSeeOrOutOfScopeReadsLikeAMissingFolder() {
        VisibilityFilter everyone = readableBy("GROUP_EVERYONE");
        CmisRepositoryConnector financeOnly = connector("/Finance", true, CmisCrawlMode.FOLDER, false, 100);

        assertThatThrownBy(() -> connector().crawlStart("/Finance", everyone))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No CMIS folder '/Finance' in this connector's scope.");
        assertThatThrownBy(() -> connector().crawlStart("/Nowhere", everyone))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No CMIS folder '/Nowhere' in this connector's scope.");
        assertThatThrownBy(() -> financeOnly.crawlStart("/Public", VisibilityFilter.ALL))
                .hasMessage("No CMIS folder '/Public' in this connector's scope.");
        assertThatThrownBy(() -> connector().crawlStart("/System", VisibilityFilter.ALL))
                .hasMessage("No CMIS folder '/System' in this connector's scope.");
        assertThatThrownBy(() -> connector().crawlStart("d1", VisibilityFilter.ALL))
                .hasMessage("No CMIS folder 'd1' in this connector's scope.");
    }

    @Test
    void aCrawlStartIsNeverAQuery() {
        assertThatThrownBy(() -> connector().crawlStart("SELECT * FROM cmis:document", VisibilityFilter.ALL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("No CMIS folder");
    }

    @Test
    void aQueryOrChangeLogConnectorHasNoCrawlStart() {
        assertThatThrownBy(() -> connector("/", true, CmisCrawlMode.QUERY, false, 100).crawlStart("/Public", VisibilityFilter.ALL))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(CmisRepositoryConnector.NOT_A_FOLDER_CRAWL);
        assertThatThrownBy(() -> connector("/", true, CmisCrawlMode.FOLDER, true, 100).crawlStart("/Public", VisibilityFilter.ALL))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(CmisRepositoryConnector.NOT_A_FOLDER_CRAWL);
    }

    @Test
    void aFilterThatCannotDecideStopsDiscovery() {
        VisibilityFilter broken = nodes -> { throw new IllegalStateException("authorization unavailable"); };
        VisibilityFilter miscounting = nodes -> new boolean[] {true, true, true, true, true, true, true, true, true};

        assertThatThrownBy(() -> connector().explore(new ExploreRequest(null, 1, 10, true, TIMEOUT), broken))
                .hasMessageContaining("authorization unavailable");
        assertThatThrownBy(() -> connector().explore(new ExploreRequest(null, 1, 10, true, TIMEOUT), miscounting))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("decisions");
        assertThatThrownBy(() -> connector().dryRun(new DryRunRequest(null, 1, 10, TIMEOUT), broken))
                .hasMessageContaining("authorization unavailable");
    }

    @Test
    void aFolderDryRunCountsOnlyWhatTheCallerMayRead() {
        DryRunResult result = connector().dryRun(new DryRunRequest(null, 10, 100, TIMEOUT), readableBy("GROUP_EVERYONE"));

        assertThat(names(result.samples())).containsExactlyInAnyOrder("a.txt", "b.txt");
        assertThat(result.matchCount()).isEqualTo(2);
        assertThat(result.countAccuracy()).isEqualTo(CountAccuracy.EXACT);
        // budget.pdf and old.pdf (finance only) and noacl.txt.
        assertThat(result.hiddenCount()).isEqualTo(3);
        assertThat(result.samples()).allMatch(sample -> sample.parentId() == null);
    }

    @Test
    void aQueryDryRunEstimatesTheCountAndChecksEachSample() {
        DryRunResult result = connector().dryRun(new DryRunRequest("SELECT * FROM cmis:document", 1, 100, TIMEOUT),
                readableBy("GROUP_EVERYONE"));

        assertThat(result.matchCount()).isEqualTo(7);
        assertThat(result.countAccuracy()).isEqualTo(CountAccuracy.ESTIMATE);
        assertThat(result.hiddenCount()).isNull();
        // budget.pdf and old.pdf come first but are hidden; a.txt is the first visible match.
        assertThat(names(result.samples())).containsExactly("a.txt");
    }

    @Test
    void aQueryDryRunReadsAtMostFiveCandidatesPerSample() {
        DryRunResult result = connector().dryRun(new DryRunRequest("SELECT * FROM cmis:document", 1, 100, TIMEOUT),
                readableBy("nobody"));

        assertThat(result.samples()).isEmpty();
        assertThat(requests).filteredOn(request -> request.contains("cmisselector=object")).hasSize(
                CmisRepositoryConnector.SAMPLE_CANDIDATES_PER_SAMPLE);
    }
}
