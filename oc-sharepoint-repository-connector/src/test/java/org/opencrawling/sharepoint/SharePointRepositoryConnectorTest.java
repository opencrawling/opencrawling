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
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole scans against a mock Entra ID and Microsoft Graph: delta enumeration and continuation, deletions (files, folders),
 * delta resync (410), tree crawls with permission reconciliation, list columns, ACL mapping, downloads without credentials,
 * throttling, and links that must not receive the token.
 */
class SharePointRepositoryConnectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOST = "contoso.sharepoint.com";
    private static final String I1 = "sharepoint://" + HOST + "/drives/b!lib1/items/i1";
    private static final String I2 = "sharepoint://" + HOST + "/drives/b!lib1/items/i2";
    private static final String I3 = "sharepoint://" + HOST + "/drives/b!lib1/items/i3";

    private MockGraph graph;

    @TempDir
    Path stateDir;

    @BeforeEach
    void setUp() throws Exception {
        graph = new MockGraph();
        graph.standard();
    }

    @AfterEach
    void tearDown() throws Exception {
        graph.close();
    }

    private SharePointRepositoryConnector connector(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of("tenantId", "tenant-1", "clientId", "client-1", "clientSecret", "s3cret",
                "authorityUrl", graph.base(), "graphUrl", graph.base() + "/v1.0",
                "siteUrl", "https://contoso.sharepoint.com/sites/engineering", "driveName", "Documents",
                "stateDirectory", stateDir.toString(), "maxFileSizeBytes", "1000000"));
        config.put("initialBackoffMs", "10");
        config.putAll(extra);
        return new SharePointRepositoryConnector(SharePointProperties.fromConfiguration(config));
    }

    private static List<RepositoryDocument> scan(SharePointRepositoryConnector connector) {
        List<RepositoryDocument> documents = connector.scan("default").collectList().block();
        assertNotNull(documents);
        return documents;
    }

    private static Map<String, RepositoryDocument> byId(List<RepositoryDocument> documents) {
        Map<String, RepositoryDocument> map = new HashMap<>();
        documents.forEach(d -> assertNull(map.put(d.id() + "|" + d.action(), d), "duplicate " + d.id()));
        return map;
    }

    private static String content(RepositoryDocument document) throws IOException {
        try (InputStream in = document.contentStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void closeAll(List<RepositoryDocument> documents) throws IOException {
        for (RepositoryDocument d : documents) {
            if (d.contentStream() != null) {
                d.contentStream().close();
            }
        }
    }

    private JsonNode state() throws IOException {
        try (var files = Files.list(stateDir)) {
            return MAPPER.readTree(files.findFirst().orElseThrow().toFile());
        }
    }

    private static List<String> rules(RepositoryDocument d, String access) {
        return d.security().permissions().stream().filter(r -> r.access().equals(access)).map(PermissionRule::identity).toList();
    }

    @Test
    void firstDeltaRunMapsFilesFieldsAndPermissions() throws Exception {
        List<RepositoryDocument> documents = scan(connector(Map.of()));
        Map<String, RepositoryDocument> docs = byId(documents);
        assertEquals(3, documents.size(), docs.keySet().toString());

        RepositoryDocument spec = docs.get(I1 + "|UPSERT");
        assertEquals("Architecture specification body", content(spec));
        Map<String, List<String>> m = spec.metadata();
        assertEquals(List.of("Architecture_Spec.docx"), m.get("name"));
        assertEquals(List.of("Architecture Specification"), m.get("title"));
        assertEquals(List.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"), m.get("mimeType"));
        assertEquals(List.of("/Specs/Architecture_Spec.docx"), m.get("sharepoint.path"), "path built although the folder came later");
        assertEquals(List.of("Documents"), m.get("sharepoint.driveName"));
        assertEquals(List.of(MockGraph.SITE_ID), m.get("sharepoint.siteId"));
        assertEquals(List.of("adele.vance@contoso.com"), m.get("sharepoint.createdBy"));
        assertEquals(List.of("megan.bowen@contoso.com"), m.get("sharepoint.lastModifiedBy"));
        assertEquals(List.of("31"), m.get("sharepoint.size"));
        assertEquals(List.of("PRJ-CLOUD-2026"), m.get("sharepoint_field_ProjectCode"));
        assertEquals(List.of("Approved"), m.get("sharepoint_field_DocumentStatus"));
        assertEquals(List.of("Internal-Confidential"), m.get("sharepoint_field_Classification"), "managed metadata by label");
        assertEquals(List.of("Adele Vance", "Megan Bowen"), m.get("sharepoint_field_Reviewers"), "people by name");
        assertEquals(List.of("150000.5"), m.get("sharepoint_field_Budget"));
        assertEquals(List.of("true"), m.get("sharepoint_field_Signed"));
        assertFalse(m.containsKey("sharepoint_field_FileLeafRef") || m.containsKey("sharepoint_field__UIVersionString")
                || m.containsKey("sharepoint_field_ContentType"), "system columns are skipped");

        assertEquals(List.of("adele.vance@contoso.com", "megan.bowen@contoso.com", "u-megan", "5f0c1a2b-m365"), rules(spec, "read"),
                "a person with a known Entra object id is also granted by that id");
        assertEquals(List.of("0d1e6b2a-eng", "sharepoint-group:" + MockGraph.SITE_ID + ":3"), rules(spec, "write"));
        assertTrue(spec.security().permissions().stream().noneMatch(r -> r.identity().equals("public")),
                "everyone-except-external, organization and anonymous links grant nothing unless configured");
        assertTrue(spec.security().permissions().stream().noneMatch(r -> r.identity().equals("limited@contoso.com")),
                "an unknown role grants nothing");

        // no readers at all: nobody, never public
        RepositoryDocument readme = docs.get(I2 + "|UPSERT");
        assertEquals("Read me first.", content(readme));
        assertTrue(readme.security().permissions().isEmpty());
        assertEquals("", readme.acl());

        RepositoryDocument huge = docs.get(I3 + "|UPSERT");
        assertNull(huge.contentStream(), "over max-file-size-bytes: metadata only");

        // downloads never carry the Graph token: neither the pre-authenticated URL nor the /content redirect target
        assertNull(graph.request("GET /download/i1?tempauth=signed").getHeader("Authorization"));
        assertNull(graph.request("GET /download/i2?tempauth=signed2").getHeader("Authorization"));
        assertEquals("Bearer tok-1", graph.request("GET /v1.0/drives/b!lib1/items/i2/content").getHeader("Authorization"));
        assertEquals(1, graph.count("POST /tenant-1/oauth2/v2.0/token"));
        String tokenForm = graph.request("POST /tenant-1/oauth2/v2.0/token").getBody().readUtf8();
        assertTrue(tokenForm.contains("grant_type=client_credentials") && tokenForm.contains("scope=http"));
        assertTrue(graph.request(MockGraph.DELTA_START).getHeader("User-Agent").startsWith("ISV|OpenCrawling|"));

        JsonNode state = state();
        assertTrue(state.path("deltaLink").asText().endsWith("token=d1"));
        assertTrue(state.path("items").path("f1").path("folder").asBoolean());
        closeAll(documents);
    }

    @Test
    void configuredIdentitiesOpenBroadGrants() {
        List<RepositoryDocument> documents = scan(connector(Map.of("everyoneExceptExternalIdentity", "contoso-employees",
                "organizationLinkIdentity", "contoso-employees", "anonymousLinkIdentity", "public")));
        RepositoryDocument spec = byId(documents).get(I1 + "|UPSERT");
        List<String> read = rules(spec, "read");
        assertTrue(read.contains("contoso-employees"));
        assertTrue(read.contains("public"));
        assertEquals("public", spec.acl());
    }

    @Test
    void nextRunFollowsTheDeltaLink() throws Exception {
        closeAll(scan(connector(Map.of())));
        graph.json("GET /v1.0/drives/b!lib1/root/delta?token=d1", "delta-2.json");
        List<RepositoryDocument> documents = scan(connector(Map.of()));
        Map<String, RepositoryDocument> docs = byId(documents);
        assertEquals(2, documents.size(), docs.keySet().toString());
        assertNotNull(docs.get(I1 + "|UPSERT"), "modified file");
        assertEquals(DocumentAction.DELETE, docs.get(I2 + "|DELETE").action(), "deleted facet");
        assertEquals(1, graph.count(MockGraph.DELTA_START), "the enumeration ran only once");
        assertTrue(state().path("deltaLink").asText().endsWith("token=d2"));
        assertFalse(state().path("items").has("i2"));
        closeAll(documents);
    }

    @Test
    void deletedFolderTakesItsKnownFilesWithIt() throws Exception {
        closeAll(scan(connector(Map.of())));
        graph.json("GET /v1.0/drives/b!lib1/root/delta?token=d1", "delta-folder-deleted.json");
        List<RepositoryDocument> documents = scan(connector(Map.of()));
        assertEquals(List.of(I1 + "|DELETE"), documents.stream().map(d -> d.id() + "|" + d.action()).toList());
        assertFalse(state().path("items").has("f1"));
        assertFalse(state().path("items").has("i1"));
    }

    @Test
    void goneDeltaLinkReEnumeratesAndTombstonesWhatVanished() throws Exception {
        closeAll(scan(connector(Map.of())));
        graph.routes.put("GET /v1.0/drives/b!lib1/root/delta?token=d1", r -> new MockResponse().setResponseCode(410)
                .setHeader("Location", graph.base() + "/v1.0/drives/b!lib1/root/delta?token=restart")
                .setBody("{\"error\":{\"code\":\"resyncChangesApplyDifferences\",\"message\":\"Resync required.\"}}"));
        graph.json("GET /v1.0/drives/b!lib1/root/delta?token=restart", "delta-restart.json");
        List<RepositoryDocument> documents = scan(connector(Map.of()));
        Map<String, RepositoryDocument> docs = byId(documents);
        assertNotNull(docs.get(I2 + "|DELETE"));
        assertNotNull(docs.get(I3 + "|DELETE"));
        assertNull(docs.get(I1 + "|UPSERT"), "unchanged eTag and permissions: not emitted again");
        assertTrue(state().path("deltaLink").asText().endsWith("token=d4"));
    }

    @Test
    void failedFileKeepsTheDeltaLink() throws Exception {
        graph.routes.put("GET /v1.0/drives/b!lib1/items/i2/permissions", r -> new MockResponse().setResponseCode(500));
        closeAll(scan(connector(Map.of("maxRetries", "0"))));
        assertTrue(state().path("deltaLink").isNull() || state().path("deltaLink").isMissingNode(),
                "a failed file must be seen again in the next run");
    }

    @Test
    void treeModeReconcilesPermissionOnlyChanges() throws Exception {
        graph.json("GET /v1.0/drives/b!lib1/root", "root-item.json");
        graph.json("GET /v1.0/drives/b!lib1/items/root1/children?$top=100&$expand=listItem($expand=fields)", "children-root.json");
        graph.json("GET /v1.0/drives/b!lib1/items/root1/children?$skiptoken=2", "children-root-2.json");
        graph.json("GET /v1.0/drives/b!lib1/items/f1/children?$top=100&$expand=listItem($expand=fields)", "children-f1.json");
        Map<String, String> tree = Map.of("crawlMode", "tree");

        List<RepositoryDocument> first = scan(connector(tree));
        assertEquals(3, first.size());
        assertEquals(List.of("/Specs/Architecture_Spec.docx"), byId(first).get(I1 + "|UPSERT").metadata().get("sharepoint.path"));
        closeAll(first);

        assertTrue(scan(connector(tree)).isEmpty(), "nothing changed: nothing emitted");

        graph.json("GET /v1.0/drives/b!lib1/items/i1/permissions", "permissions-i1-changed.json");
        List<RepositoryDocument> third = scan(connector(tree));
        assertEquals(List.of(I1 + "|UPSERT"), third.stream().map(d -> d.id() + "|" + d.action()).toList(),
                "a permission-only change re-emits the file");
        assertEquals(List.of("adele.vance@contoso.com"), rules(third.getFirst(), "read"));
        closeAll(third);

        graph.json("GET /v1.0/drives/b!lib1/items/root1/children?$skiptoken=2", "delta-empty.json");
        List<RepositoryDocument> fourth = scan(connector(tree));
        assertEquals(List.of(I3 + "|DELETE"), fourth.stream().map(d -> d.id() + "|" + d.action()).toList(),
                "a file that left the listing is purged");
    }

    @Test
    void refusedColumnExpansionFallsBackToPerItemReads() throws Exception {
        graph.routes.put(MockGraph.DELTA_START, r -> new MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"code\":\"invalidRequest\",\"message\":\"expand not supported\"}}"));
        // without $expand Graph returns no listItem: serve the pages stripped of it
        graph.body("GET /v1.0/drives/b!lib1/root/delta?$top=100", "application/json", withoutListItems("delta-1a.json"));
        graph.body("GET /v1.0/drives/b!lib1/root/delta?token=page2", "application/json", withoutListItems("delta-1b.json"));
        graph.json("GET /v1.0/drives/b!lib1/items/i2/listItem?$expand=fields", "listitem-i2.json");
        graph.json("GET /v1.0/drives/b!lib1/items/i1/listItem?$expand=fields", "listitem-i2.json");
        graph.json("GET /v1.0/drives/b!lib1/items/i3/listItem?$expand=fields", "listitem-i2.json");
        List<RepositoryDocument> documents = scan(connector(Map.of()));
        RepositoryDocument readme = byId(documents).get(I2 + "|UPSERT");
        assertEquals(List.of("Draft"), readme.metadata().get("sharepoint_field_DocumentStatus"));
        assertEquals(List.of("Read me"), readme.metadata().get("title"));
        closeAll(documents);
    }

    @Test
    void unreadableColumnsDoNotCostTheFiles() throws Exception {
        // found against an independent Graph mock without the listItem endpoint: columns are metadata, the file still counts
        graph.routes.put(MockGraph.DELTA_START, r -> new MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"code\":\"invalidRequest\",\"message\":\"expand not supported\"}}"));
        graph.body("GET /v1.0/drives/b!lib1/root/delta?$top=100", "application/json", withoutListItems("delta-1a.json"));
        graph.body("GET /v1.0/drives/b!lib1/root/delta?token=page2", "application/json", withoutListItems("delta-1b.json"));
        List<RepositoryDocument> documents = scan(connector(Map.of()));
        RepositoryDocument readme = byId(documents).get(I2 + "|UPSERT");
        assertNotNull(readme, "indexed without its columns: " + byId(documents).keySet());
        assertNull(readme.metadata().get("sharepoint_field_DocumentStatus"));
        assertEquals(1, graph.requests.stream().filter(k -> k.contains("/listItem?")).count(),
                "after the first refusal the library's columns aren't requested again");
        closeAll(documents);
    }

    private String withoutListItems(String fixture) throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode page = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(graph.fixture(fixture));
        page.path("value").forEach(item -> ((com.fasterxml.jackson.databind.node.ObjectNode) item).remove("listItem"));
        return page.toString();
    }

    @Test
    void throttledRequestsWaitForRetryAfter() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        graph.routes.put(MockGraph.DELTA_START, r -> calls.incrementAndGet() == 1
                ? new MockResponse().setResponseCode(429).setHeader("Retry-After", "1")
                : new MockResponse().setResponseCode(200).setBody(graph.fixture("delta-1a.json")));
        long start = System.nanoTime();
        closeAll(scan(connector(Map.of())));
        assertEquals(2, calls.get());
        assertTrue(System.nanoTime() - start >= 900_000_000L, "Retry-After: 1 honoured");
    }

    @Test
    void foreignNextLinkNeverReceivesTheToken() {
        graph.body(MockGraph.DELTA_START, "application/json",
                "{\"value\":[],\"@odata.nextLink\":\"https://attacker.example.com/v1.0/steal\"}");
        Throwable error = assertThrows(RuntimeException.class, () -> connector(Map.of()).scan("default").collectList().block());
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        assertTrue(cause.getMessage().contains("attacker.example.com"), cause.getMessage());
    }

    @Test
    void unknownLibraryNameIsAnError() {
        Throwable error = assertThrows(RuntimeException.class,
                () -> connector(Map.of("driveName", "Nope")).scan("default").collectList().block());
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        assertTrue(cause.getMessage().contains("Nope") && cause.getMessage().contains("Contracts"), cause.getMessage());
    }

    @Test
    void everyDocumentLibraryIsCrawledWhenNoneIsNamed() {
        graph.json("GET /v1.0/drives/b!lib2/root/delta?$top=100&$expand=listItem($expand=fields)", "delta-empty.json");
        Map<String, String> config = new HashMap<>(Map.of("driveName", ""));
        scan(connector(config));
        assertEquals(1, graph.count("GET /v1.0/drives/b!lib2/root/delta?$top=100&$expand=listItem($expand=fields)"));
        assertTrue(graph.requests.stream().noneMatch(r -> r.contains("b!od")), "OneDrive (business) drives are not libraries");
    }

    @Test
    void unconfiguredConnectorNamesTheProblems() {
        Throwable error = assertThrows(RuntimeException.class,
                () -> new SharePointRepositoryConnector(SharePointProperties.fromConfiguration(Map.of())).scan("default")
                        .collectList().block());
        String message = (error.getCause() != null ? error.getCause() : error).getMessage();
        assertTrue(message.contains("tenant-id") && message.contains("client-secret") && message.contains("site-url"), message);
        assertEquals(Map.of(), graph.requests.stream().collect(Collectors.groupingBy(r -> r)), "nothing was called");
    }

    @Test
    void sourceInstanceIsTheConfiguredSiteUrl() {
        assertEquals("https://contoso.sharepoint.com/sites/engineering", connector(Map.of()).sourceInstance());
    }
}
