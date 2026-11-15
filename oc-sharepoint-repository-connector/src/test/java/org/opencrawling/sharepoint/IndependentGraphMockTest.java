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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The connector against an independently written Graph mock, sergey-shulyak/mock-graph-server (MIT), reached through
 * {@code graph-mock/conformance/record_proxy.py}, which records the traffic for {@code check_conformance.py} and stands
 * in for the token endpoint that mock doesn't have. Every site is crawled in delta and in tree mode, and the crawled
 * permissions are exported for {@code compare_acl_oracle.py}, which holds them against that mock's own RBAC oracle.
 * Changes go through its admin API. Runs only from {@code scripts/test-sharepoint-independent-mock.sh}.
 */
@EnabledIfEnvironmentVariable(named = "MOCK_GRAPH_SERVER_URL", matches = "https?://.+")
class IndependentGraphMockTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN = env("MOCK_GRAPH_SERVER_URL");
    private static final String PROXY = env("MOCK_GRAPH_SERVER_PROXY");
    private static final Path OUT = Path.of(System.getenv().getOrDefault("MOCK_GRAPH_SERVER_OUT", "target"));
    private static final String HOST = "contoso-mock.sharepoint.com";
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path state;

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    @BeforeEach
    void reset() throws Exception {
        send("POST", ADMIN + "/_admin/reset", null);
    }

    private JsonNode send(String method, String url, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer app")
                .header("client-request-id", UUID.randomUUID().toString());
        request = json == null ? request.method(method, HttpRequest.BodyPublishers.noBody())
                : request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 300, method + " " + url + " -> " + response.statusCode() + " " + response.body());
        return response.body().isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(response.body());
    }

    /** The admin API's multipart upload: adds or replaces a file. */
    private void upload(String driveId, String path, String text) throws Exception {
        String boundary = "----oc" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"path\"\r\n\r\n" + path + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"driveId\"\r\n\r\n" + driveId + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + path.substring(path.lastIndexOf('/') + 1)
                + "\"\r\nContent-Type: text/markdown\r\n\r\n" + text + "\r\n--" + boundary + "--\r\n";
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(ADMIN + "/_admin/files"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 300, "upload " + path + " -> " + response.statusCode() + " " + response.body());
    }

    private SharePointRepositoryConnector connector(String siteUrl, String stateDir, Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of("tenantId", "contoso-mock", "clientId", "opencrawling", "clientSecret", "s",
                "authorityUrl", PROXY, "graphUrl", PROXY + "/v1.0", "siteUrl", siteUrl,
                "stateDirectory", state.resolve(stateDir).toString(), "initialBackoffMs", "50"));
        config.putAll(extra);
        return new SharePointRepositoryConnector(SharePointProperties.fromConfiguration(config));
    }

    private static List<RepositoryDocument> scan(SharePointRepositoryConnector connector) throws Exception {
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

    private static String meta(RepositoryDocument d, String key) {
        List<String> values = d.metadata().get(key);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static Map<String, RepositoryDocument> byPath(List<RepositoryDocument> documents) {
        return documents.stream().filter(d -> d.action() == DocumentAction.UPSERT)
                .collect(Collectors.toMap(d -> meta(d, "sharepoint.driveName") + ":" + meta(d, "sharepoint.path"), d -> d));
    }

    /** Sites with document libraries, as the mock lists them. */
    private List<String> siteUrls() throws Exception {
        List<String> urls = new ArrayList<>();
        for (JsonNode site : send("GET", PROXY + "/v1.0/sites?search=*&$top=999", null).path("value")) {
            String id = site.path("id").asText();
            if (send("GET", PROXY + "/v1.0/sites/" + id + "/drives", null).path("value").size() > 0) {
                urls.add(site.path("webUrl").asText());
            }
        }
        return urls;
    }

    @Test
    void everySiteCrawlsTheSameInDeltaAndTreeModeAndExportsItsPermissions() throws Exception {
        ArrayNode export = MAPPER.createArrayNode();
        int sites = 0;
        for (String siteUrl : siteUrls()) {
            sites++;
            String key = Integer.toString(sites);
            List<RepositoryDocument> delta = scan(connector(siteUrl, "delta-" + key, Map.of()));
            List<RepositoryDocument> tree = scan(connector(siteUrl, "tree-" + key, Map.of("crawlMode", "tree")));
            assertEquals(byPath(tree).keySet(), byPath(delta).keySet(), "delta and tree find the same files in " + siteUrl);
            Map<String, RepositoryDocument> treeByPath = byPath(tree);
            for (Map.Entry<String, RepositoryDocument> e : byPath(delta).entrySet()) {
                assertEquals(e.getValue().security().permissions().stream().map(PermissionRule::identity).sorted().toList(),
                        treeByPath.get(e.getKey()).security().permissions().stream().map(PermissionRule::identity).sorted().toList(),
                        "delta and tree map the same permissions for " + e.getKey());
                RepositoryDocument d = e.getValue();
                ObjectNode row = export.addObject();
                row.put("siteUrl", siteUrl).put("siteId", meta(d, "sharepoint.siteId")).put("driveId", meta(d, "sharepoint.driveId"))
                        .put("driveName", meta(d, "sharepoint.driveName")).put("path", meta(d, "sharepoint.path")).put("id", d.id());
                ArrayNode rules = row.putArray("rules");
                for (PermissionRule r : d.security().permissions()) {
                    rules.addObject().put("identity", r.identity()).put("type", r.identityType()).put("access", r.access())
                            .put("displayName", r.displayName());
                }
            }
        }
        assertTrue(sites > 1, "the mock's fixture has several sites with libraries");
        Files.createDirectories(OUT);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(OUT.resolve("crawled-permissions.json").toFile(), export);
        System.out.println("Crawled " + export.size() + " files in " + sites + " sites; permissions exported for the oracle check.");
    }

    @Test
    void deltaIncrementsMadeThroughTheMocksAdminApi() throws Exception {
        String site = "https://" + HOST + "/sites/kb";
        Map<String, RepositoryDocument> first = byPath(scan(connector(site, "kb", Map.of())));
        RepositoryDocument replaced = first.get("Documents:/General/office-wifi.md");
        assertNotNull(replaced, "the kb fixture has /General/office-wifi.md: " + first.keySet());
        Set<String> inSupport = first.entrySet().stream().filter(e -> e.getKey().startsWith("Documents:/Support/"))
                .map(e -> e.getValue().id()).collect(Collectors.toSet());
        assertFalse(inSupport.isEmpty());
        RepositoryDocument removed = first.entrySet().stream().filter(e -> e.getKey().startsWith("Documents:/HR/"))
                .findFirst().orElseThrow().getValue();

        upload("kb-docs", "/General/cross-check.md", "# Cross-check\nAdded through the independent mock's admin API.");
        upload("kb-docs", "/General/office-wifi.md", "# Office Wi-Fi\nReplaced through the admin API.");
        send("DELETE", ADMIN + "/_admin/files?driveId=kb-docs&path=" + meta(removed, "sharepoint.path").replace(" ", "%20"), null);
        send("DELETE", ADMIN + "/_admin/files?driveId=kb-docs&path=/Support", null);

        List<RepositoryDocument> second = scan(connector(site, "kb", Map.of()));
        Map<String, RepositoryDocument> upserts = byPath(second);
        assertEquals(Set.of("Documents:/General/cross-check.md", "Documents:/General/office-wifi.md"), upserts.keySet(),
                "exactly the new and the replaced file are re-emitted");
        Set<String> deletes = second.stream().filter(d -> d.action() == DocumentAction.DELETE).map(RepositoryDocument::id)
                .collect(Collectors.toSet());
        Set<String> expected = new java.util.HashSet<>(inSupport);
        expected.add(removed.id());
        assertEquals(expected, deletes, "the deleted file and every file of the deleted folder are tombstoned");
        assertTrue(scan(connector(site, "kb", Map.of())).isEmpty(), "nothing changed since");
    }

    @Test
    void theMocksThrottlingIsAbsorbed() throws Exception {
        String site = "https://" + HOST + "/sites/kb";
        int expected = byPath(scan(connector(site, "plain", Map.of()))).size();
        send("POST", ADMIN + "/_admin/throttle", "{\"count\":3,\"retryAfterSeconds\":1}");
        assertEquals(expected, byPath(scan(connector(site, "throttled", Map.of()))).size());
    }

    @Test
    void aResetAnswers410AndTheResyncDeletesOnlyWhatIsGone() throws Exception {
        String site = "https://" + HOST + "/sites/kb";
        Map<String, RepositoryDocument> first = byPath(scan(connector(site, "kb", Map.of())));
        send("POST", ADMIN + "/_admin/reset", null);
        List<RepositoryDocument> afterReset = scan(connector(site, "kb", Map.of()));
        assertTrue(afterReset.stream().noneMatch(d -> d.action() == DocumentAction.DELETE), "a resync of an unchanged library deletes nothing");
        assertTrue(byPath(afterReset).isEmpty(), "and re-emits nothing: eTags and permissions are unchanged");

        RepositoryDocument gone = first.get("Documents:/General/office-wifi.md");
        send("POST", ADMIN + "/_admin/reset", null);
        send("DELETE", ADMIN + "/_admin/files?driveId=kb-docs&path=/General/office-wifi.md", null);
        List<RepositoryDocument> resync = scan(connector(site, "kb", Map.of()));
        assertEquals(Set.of(gone.id()), resync.stream().filter(d -> d.action() == DocumentAction.DELETE).map(RepositoryDocument::id)
                .collect(Collectors.toSet()));
    }

    @Test
    void aPermissionGrantEndsUpInTheIndex() throws Exception {
        String site = "https://" + HOST + "/sites/kb";
        scan(connector(site, "kb", Map.of()));
        scan(connector(site, "kb", Map.of("crawlMode", "tree")));
        JsonNode erin = send("GET", PROXY + "/v1.0/users/erin", null);
        send("POST", ADMIN + "/_admin/permissions", "{\"driveId\":\"kb-docs\",\"itemPath\":\"/General/office-wifi.md\",\"principal\":\"user:erin\",\"role\":\"read\"}");
        List<RepositoryDocument> delta = scan(connector(site, "kb", Map.of()));
        List<RepositoryDocument> tree = scan(connector(site, "kb", Map.of("crawlMode", "tree")));
        List<RepositoryDocument> where = byPath(delta).containsKey("Documents:/General/office-wifi.md") ? delta : tree;
        RepositoryDocument d = byPath(where).get("Documents:/General/office-wifi.md");
        assertNotNull(d, "delta or the tree crawl re-emits the file whose permissions changed");
        Set<String> identities = d.security().permissions().stream().map(PermissionRule::identity).collect(Collectors.toSet());
        assertTrue(identities.contains(erin.path("id").asText().toLowerCase()) || identities.contains(erin.path("userPrincipalName").asText().toLowerCase()),
                "Erin's new grant is indexed: " + identities);
        System.out.println("The independent mock reported the permission-only change through "
                + (where == delta ? "plain delta (without Prefer: deltashowsharingchanges)" : "the tree crawl only") + ".");
    }
}
