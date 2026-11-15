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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole-connector scenarios against the Graph mock ({@code graph-mock/graph_mock.py}) over real HTTP: a full crawl
 * reconciled with the mock's own counts, delta increments (edit, delete, folder delete, new file), the permission-only
 * change that only a tree crawl notices, a delta resync, throttling, an expired download URL and a wrong secret. Runs only
 * when {@code GRAPH_MOCK_URL} points at a running mock (e.g. {@code http://localhost:18180});
 * {@code scripts/test-sharepoint-repository-connector.sh} starts one. Every test resets the mock first.
 */
@EnabledIfEnvironmentVariable(named = "GRAPH_MOCK_URL", matches = "https?://.+")
class SharePointMockServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = env("GRAPH_MOCK_URL", "");
    /** Where the connector sends Graph and token requests: the mock itself, or real host names routed to it by Dev Proxy. */
    private static final String GRAPH_URL = env("GRAPH_MOCK_GRAPH_URL", BASE + "/v1.0");
    private static final String AUTHORITY_URL = env("GRAPH_MOCK_AUTHORITY_URL", BASE);
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path state;

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.replaceAll("/+$", "");
    }

    @BeforeEach
    void reset() throws Exception {
        admin("/mock/reset", "{}");
    }

    private JsonNode admin(String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BASE + path));
        request = body == null ? request.GET() : request.POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 300, path + " -> " + response.statusCode() + " " + response.body());
        return MAPPER.readTree(response.body());
    }

    private SharePointRepositoryConnector connector(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of("tenantId", "contoso-tenant", "clientId", "opencrawling-test-app",
                "clientSecret", "test-secret", "authorityUrl", AUTHORITY_URL, "graphUrl", GRAPH_URL,
                "siteUrl", "https://contoso.sharepoint.com/sites/engineering", "stateDirectory", state.toString(),
                "initialBackoffMs", "50"));
        config.putAll(extra);
        return new SharePointRepositoryConnector(SharePointProperties.fromConfiguration(config));
    }

    private static List<RepositoryDocument> scan(SharePointRepositoryConnector connector) throws IOException {
        List<RepositoryDocument> documents = connector.scan("default").collectList().block();
        assertNotNull(documents);
        for (RepositoryDocument d : documents) {
            if (d.contentStream() != null) {
                try (InputStream in = d.contentStream()) {
                    in.transferTo(OutputStream.nullOutputStream());
                }
            }
        }
        return documents;
    }

    private static Set<String> ids(List<RepositoryDocument> documents, DocumentAction action) {
        return documents.stream().filter(d -> d.action() == action).map(RepositoryDocument::id).collect(Collectors.toSet());
    }

    private static String id(String drive, String item) {
        return "sharepoint://contoso.sharepoint.com/drives/" + drive + "/items/" + item;
    }

    /** The mock's files of one permission pattern (0 everyone-except-external, 1 group+user, 2 owners+link, 3 links, 4 user, 5 nobody). */
    private List<JsonNode> files(int pattern) throws IOException, InterruptedException {
        List<JsonNode> out = new ArrayList<>();
        admin("/mock/files", null).forEach(f -> {
            if (f.path("pattern").asInt(-1) == pattern) {
                out.add(f);
            }
        });
        return out;
    }

    @Test
    void fullCrawlMatchesTheTenantAndFailsClosed() throws Exception {
        JsonNode stats = admin("/mock/stats", null);
        List<RepositoryDocument> documents = scan(connector(Map.of()));
        assertEquals(stats.path("liveFiles").asInt(), ids(documents, DocumentAction.UPSERT).size(),
                "every file of both libraries, nothing from the OneDrive drive");
        Map<String, RepositoryDocument> byId = documents.stream().collect(Collectors.toMap(RepositoryDocument::id, d -> d));
        for (int nobody : new int[]{0, 3, 5}) {
            JsonNode file = files(nobody).getFirst();
            RepositoryDocument d = byId.get(id(file.path("drive").asText(), file.path("id").asText()));
            assertTrue(d.security().permissions().isEmpty(),
                    "pattern " + nobody + " grants nothing without configured broad identities: " + d.security());
        }
        JsonNode groupFile = files(1).getFirst();
        RepositoryDocument d = byId.get(id(groupFile.path("drive").asText(), groupFile.path("id").asText()));
        assertEquals("0d1e6b2a-0000-4000-8000-00000000e001,a1d4e1e0-0000-4000-8000-0000000000a1,adele.vance@contoso.com",
                String.join(",", d.security().permissions().stream().map(r -> r.identity()).sorted().toList()),
                "the Entra group, and Adele by e-mail (from her membership claim) and by object id");
        JsonNode documented = files(4).getFirst(); // the shape of Microsoft's "list permissions" example
        assertEquals(List.of("a1d4e1e0-0000-4000-8000-0000000000a2"),
                byId.get(id(documented.path("drive").asText(), documented.path("id").asText())).security().permissions().stream()
                        .map(r -> r.identity()).toList(), "Megan by object id: her loginName there is a display name, not a claim");
        assertTrue(d.metadata().get("sharepoint.path").getFirst().startsWith("/"));
        assertNotNull(d.metadata().get("sharepoint_field_ProjectCode"));
    }

    @Test
    void deltaIncrementsCarryEditsDeletesAndNewFiles() throws Exception {
        Map<String, String> config = Map.of("driveName", "Documents");
        scan(connector(config));
        List<JsonNode> docs = files(4).stream().filter(f -> f.path("drive").asText().equals("b!docs")).toList();
        String edited = docs.get(0).path("id").asText();
        String removed = docs.get(1).path("id").asText();
        admin("/mock/items/" + edited + "/modify", "{\"appendText\":\"Second edition.\"}");
        admin("/mock/items/" + removed + "/delete", "{}");
        JsonNode created = admin("/mock/files", "{\"drive\":\"b!docs\",\"name\":\"Release notes.txt\",\"text\":\"Release notes for 2026.\"}");

        List<RepositoryDocument> second = scan(connector(config));
        assertEquals(Set.of(id("b!docs", edited), id("b!docs", created.path("id").asText())), ids(second, DocumentAction.UPSERT));
        assertEquals(Set.of(id("b!docs", removed)), ids(second, DocumentAction.DELETE));
        assertTrue(scan(connector(config)).isEmpty(), "no change since: nothing emitted");
    }

    @Test
    void deletedFolderTakesItsFiles() throws Exception {
        // small pages: files arrive on pages before their folders, so paths need the parent lookup
        Map<String, String> config = Map.of("driveName", "Documents", "batchSize", "5");
        List<RepositoryDocument> first = scan(connector(config));
        JsonNode folder = admin("/mock/items/01SPX000003", null); // Documents/Policies
        assertEquals("Policies", folder.path("name").asText());
        Set<String> inPolicies = first.stream().filter(d -> d.metadata().get("sharepoint.path").getFirst().startsWith("/Policies/"))
                .map(RepositoryDocument::id).collect(Collectors.toSet());
        assertFalse(inPolicies.isEmpty());
        admin("/mock/items/01SPX000003/delete", "{}");
        List<RepositoryDocument> second = scan(connector(config));
        assertEquals(inPolicies, ids(second, DocumentAction.DELETE), "the delta reports only the folder; its files are purged");
    }

    @Test
    void permissionOnlyChangeNeedsTheTreeReconciliation() throws Exception {
        Map<String, String> delta = Map.of("driveName", "Documents");
        Map<String, String> tree = Map.of("driveName", "Documents", "crawlMode", "tree");
        scan(connector(delta));
        scan(connector(tree));
        JsonNode file = files(4).stream().filter(f -> f.path("drive").asText().equals("b!docs")).findFirst().orElseThrow();
        admin("/mock/items/" + file.path("id").asText() + "/permissions",
                "{\"permissions\":[{\"id\":\"p\",\"roles\":[\"read\"],\"grantedToV2\":{\"group\":{\"id\":\"NEW-GROUP\"}}}]}");
        assertTrue(scan(connector(delta)).isEmpty(), "Graph delta does not report permission-only changes");
        List<RepositoryDocument> reconciled = scan(connector(tree));
        assertEquals(1, reconciled.size());
        assertEquals("new-group", reconciled.getFirst().security().permissions().getFirst().identity());
    }

    @Test
    void treeCrawlAfterDeltaReusesTheState() throws Exception {
        scan(connector(Map.of("driveName", "Documents")));
        List<RepositoryDocument> tree = scan(connector(Map.of("driveName", "Documents", "crawlMode", "tree")));
        assertEquals(List.of(), tree.stream().map(RepositoryDocument::id).toList(),
                "a tree crawl right after a delta crawl finds nothing new");
        List<RepositoryDocument> specs = scan(connector(Map.of("driveName", "Documents", "crawlMode", "tree",
                "rootFolderPath", "/Specs")));
        assertTrue(ids(specs, DocumentAction.DELETE).isEmpty(), "a narrower tree crawl keeps its own state: no false deletes");
        assertFalse(ids(specs, DocumentAction.UPSERT).isEmpty());
        assertTrue(specs.stream().allMatch(d -> d.metadata().get("sharepoint.path").getFirst().startsWith("/Specs/")));
        assertTrue(scan(connector(Map.of("driveName", "Documents"))).isEmpty(), "and the library's delta state is untouched");
    }

    @Test
    void resyncReEnumeratesAndPurgesWhatVanished() throws Exception {
        Map<String, String> config = Map.of("driveName", "Documents");
        scan(connector(config));
        String removed = files(4).stream().filter(f -> f.path("drive").asText().equals("b!docs")).findFirst().orElseThrow().path("id").asText();
        admin("/mock/items/" + removed + "/delete", "{}");
        admin("/mock/drives/b!docs/resync", "{}");
        List<RepositoryDocument> second = scan(connector(config));
        assertEquals(Set.of(id("b!docs", removed)), ids(second, DocumentAction.DELETE));
        assertTrue(ids(second, DocumentAction.UPSERT).isEmpty(), "unchanged files are recognised after the re-enumeration");
    }

    @Test
    void throttlingAndExpiredDownloadsAreAbsorbed() throws Exception {
        admin("/mock/faults", "{\"path\":\"/v1.0/drives/b!docs/root/delta\",\"status\":429,\"retryAfter\":1,\"count\":1}");
        admin("/mock/faults", "{\"path\":\"/sites/engineering/_layouts/15/download.aspx\",\"status\":401,\"count\":1}");
        admin("/mock/faults", "{\"path\":\"/v1.0/drives/b!docs/items/\",\"status\":503,\"count\":1}");
        JsonNode stats = admin("/mock/stats", null);
        List<RepositoryDocument> documents = scan(connector(Map.of("driveName", "Documents")));
        assertEquals(stats.path("filesPerDrive").path("b!docs").asInt(), ids(documents, DocumentAction.UPSERT).size());
        assertTrue(admin("/mock/stats", null).path("requests").has("GET /v1.0/drives/{id}/items/{id}/content"),
                "rejected download URLs fell back to /content");
    }

    @Test
    void wrongSecretFailsWithEntrasMessage() {
        Throwable error = assertThrows(RuntimeException.class,
                () -> connector(Map.of("clientSecret", "wrong")).scan("default").collectList().block());
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        assertTrue(cause.getMessage().contains("AADSTS7000215"), cause.getMessage());
        assertFalse(cause.getMessage().contains("wrong"));
    }
}
