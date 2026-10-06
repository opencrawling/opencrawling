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
package org.opencrawling.doxis;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.doxis.client.DoxisClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole scans against a mock CSB (responses in {@code src/test/resources/doxis-mock-responses/}): search paging, e-file
 * traversal, tombstones, content download, ACLs, version modes, and exactly one logout per scan.
 */
class DoxisRepositoryConnectorTest {

    private static final String BASE = "/restws/publicws/rest/api/v1";
    private static final String DOCS = "/dmsRepositories/DB1/documents/";
    private static final String RECORDS = "/dmsRepositories/DB1/records/";

    private MockWebServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();

    static String fixture(String name) {
        try (InputStream is = DoxisRepositoryConnectorTest.class.getResourceAsStream("/doxis-mock-responses/" + name)) {
            if (is == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String key = request.getMethod() + " " + request.getPath().substring(BASE.length());
                requests.add(key);
                Function<RecordedRequest, MockResponse> route = routes.get(key);
                return route != null ? route.apply(request)
                        : new MockResponse().setResponseCode(404).setBody("{\"message\":\"no route " + key + "\"}");
            }
        });
        server.start();

        json("POST /login", "login.json");
        routes.put("POST /logout", r -> new MockResponse().setResponseCode(204));
        json("GET /dmsRepositories/DB1", "repository.json");
        json("GET /attributeDefinitions", "attribute-definitions.json");
        json("GET /documentTypes", "document-types.json");
        json("GET /roles", "roles.json");
        json("GET /groups", "groups.json");
        json("GET /users", "users.json");
        json("GET " + DOCS + "d1/versions?initializeRepresentations=true", "document-d1-versions.json");
        json("GET " + DOCS + "d3/versions?initializeRepresentations=true", "document-d3-versions.json");
        json("GET " + DOCS + "d4/versions?initializeRepresentations=true", "document-d4-versions.json");
        json("GET " + DOCS + "d1/permissions", "permissions-empty.json");
        json("GET " + DOCS + "d3/permissions", "permissions-d3.json");
        json("GET " + DOCS + "d4/permissions", "permissions-empty.json");
        json("GET " + RECORDS + "e1/permissions", "record-e1-permissions.json");
        json("GET " + RECORDS + "e2/permissions", "permissions-empty.json");
        json("GET " + RECORDS + "e1", "record-e1.json");
        routes.put("GET " + DOCS + "d1/versions/2/representations/r1/contentObjects/c1",
                r -> new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/pdf").setBody("%PDF-1.7 NDA."));
        routes.put("GET " + DOCS + "d1/versions/1/representations/r0/contentObjects/c0",
                r -> new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/pdf").setBody("%PDF old"));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void json(String key, String fixture) {
        routes.put(key, r -> new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(fixture(fixture)));
    }

    private void searchFixtures() {
        json("POST /documents/search", "search-page-1.json");
        json("GET /documents/searchResults/s-1?offset=3&limit=2", "search-page-2.json");
        routes.put("DELETE /documents/searchResults/s-1", r -> new MockResponse().setResponseCode(204));
    }

    private void folderFixtures() {
        json("GET " + RECORDS + "e1?initializeNodeHierarchy=true", "record-e1.json");
        json("GET " + RECORDS + "e2?initializeNodeHierarchy=true", "record-e2.json");
        json("GET " + RECORDS + "e1/nodes/n-docs/referencedInformationObjects", "node-docs-objects.json");
        json("GET " + RECORDS + "e1/nodes/n-sub/referencedInformationObjects", "node-sub-objects.json");
        json("GET " + RECORDS + "e2/nodes/n-e2/referencedInformationObjects", "node-e2-objects.json");
    }

    private DoxisRepositoryConnector connector(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "url", server.url(BASE).toString(), "customerName", "DX4", "username", "crawler", "password", "secret",
                "repositoryId", "DB1", "batchSize", "2"));
        config.putAll(extra);
        DoxisRepositorySettings settings = DoxisRepositorySettings.fromConfiguration(config);
        DoxisClient client = new DoxisClient(settings.url(), settings.customerName(), settings.username(), settings.password(),
                settings.role(), "OpenCrawling-Test", Duration.ofSeconds(5), 0, Duration.ofMillis(1), HttpClient.newHttpClient(),
                new ObjectMapper());
        return new DoxisRepositoryConnector(settings, client);
    }

    private long count(String key) {
        return requests.stream().filter(key::equals).count();
    }

    private static Map<String, RepositoryDocument> byId(List<RepositoryDocument> docs) {
        Map<String, RepositoryDocument> map = new HashMap<>();
        docs.forEach(d -> map.put(d.id(), d));
        return map;
    }

    // ------------------------------------------------------------------ SEARCH mode

    @Test
    void searchScanPagesEmitsDocumentsTombstonesAndLinksAndLogsOutOnce() throws Exception {
        searchFixtures();

        List<RepositoryDocument> docs = connector(Map.of("searchQuery", "OBJECTNUMBER2 LIKE 'contracts-2026*'"))
                .scan("default").collectList().block();

        assertNotNull(docs);
        assertEquals(3, docs.size());
        Map<String, RepositoryDocument> byId = byId(docs);

        RepositoryDocument d1 = byId.get("doxis://DX4/DB1/documents/d1");
        assertEquals(DocumentAction.UPSERT, d1.action());
        Map<String, List<String>> m = d1.metadata();
        assertEquals(List.of("Mutual NDA"), m.get("ObjectName"));
        assertEquals(List.of("contracts-2026/NDA-001"), m.get("ObjectNumberExternal"));
        assertEquals(List.of("Acme Energy", "Legal Ops"), m.get("ObjectAuthors"));
        assertEquals(List.of("d1"), m.get("doxis.documentId"));
        assertEquals(List.of("TX_MigratedDocument"), m.get("doxis.documentClass"));
        assertEquals(List.of("2"), m.get("doxis.version"));
        assertEquals(List.of("true"), m.get("doxis.isLatestVersion"));
        assertEquals(List.of("Supervisor"), m.get("doxis.createdBy"));
        assertEquals(List.of("maya.collins"), m.get("doxis.modifiedBy"));
        assertEquals(List.of("e1"), m.get("doxis.parentFolderId"));
        assertEquals(List.of("Acme Energy Master File"), m.get("doxis.parentFolderName"));
        assertEquals(List.of("NDA-001.pdf"), m.get("doxis.fileName"));
        assertEquals(List.of("13"), m.get("doxis.fileSize"));
        assertTrue(d1.security().inheritanceEnabled());
        assertTrue(d1.security().permissions().contains(new PermissionRule("Legal", "group", "Legal", "read")));
        assertTrue(d1.security().permissions().contains(new PermissionRule("maya.collins", "user", "maya.collins", "deny")));
        try (InputStream in = d1.contentStream()) {
            assertEquals("%PDF-1.7 NDA.", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        RepositoryDocument d2 = byId.get("doxis://DX4/DB1/documents/d2");
        assertEquals(DocumentAction.DELETE, d2.action());
        assertEquals(List.of("DELETED"), d2.metadata().get("doxis.status"));

        RepositoryDocument d3 = byId.get("doxis://DX4/DB1/documents/d3");
        assertNull(d3.contentStream());
        assertEquals(List.of("\\\\fs01\\share\\big.pdf"), d3.metadata().get("doxis.contentLink"));
        assertEquals(List.of(new PermissionRule("public", "public", "Public Access", "read")), d3.security().permissions());

        // one search, one further page, the result closed; the tombstone and the content link need no further reads
        assertEquals(1, count("POST /documents/search"));
        assertEquals(1, count("GET /documents/searchResults/s-1?offset=3&limit=2"));
        assertEquals(1, count("DELETE /documents/searchResults/s-1"));
        assertEquals(0, count("GET " + DOCS + "d2/versions?initializeRepresentations=true"));
        assertEquals(0, requests.stream().filter(r -> r.contains("/contentObjects/c3")).count());
        assertEquals(1, count("GET " + RECORDS + "e1"), "e-file name read once");
        assertEquals(1, count("POST /login"));
        assertEquals(1, count("POST /logout"));
        assertEquals("POST /logout", requests.getLast());
    }

    @Test
    void aPageThatRepeatsAHitStillDeliversEveryDocumentOnce() {
        // a server that restarts a page one hit early (page 2 repeats d2) must not duplicate or drop documents
        json("POST /documents/search", "search-page-1.json");
        routes.put("GET /documents/searchResults/s-1?offset=3&limit=2", r -> new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(
                        "{\"searchId\":\"s-1\",\"totalHitCount\":3,\"searchHits\":[{\"uuid\":\"d2\",\"documentTypeUUID\":\"t-mig\","
                                + "\"logicalDeleted\":true},{\"uuid\":\"d3\",\"documentTypeUUID\":\"t-link\"}]}"));
        routes.put("DELETE /documents/searchResults/s-1", r -> new MockResponse().setResponseCode(204));

        List<RepositoryDocument> docs = connector(Map.of("includeContentStream", "false")).scan("default").collectList().block();

        assertNotNull(docs);
        assertEquals(List.of("doxis://DX4/DB1/documents/d1", "doxis://DX4/DB1/documents/d2", "doxis://DX4/DB1/documents/d3"),
                docs.stream().map(RepositoryDocument::id).sorted().toList(), "each document once, the last one included");
        assertEquals(1, requests.stream().filter(r -> r.startsWith("GET /documents/searchResults/")).count());
        assertEquals(1, count("POST /logout"));
    }

    @Test
    void aPageWithNothingNewEndsTheListing() {
        json("POST /documents/search", "search-page-1.json");
        routes.put("GET /documents/searchResults/s-1?offset=3&limit=2", r -> new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(
                        "{\"searchId\":\"s-1\",\"totalHitCount\":3,\"searchHits\":[{\"uuid\":\"d2\",\"logicalDeleted\":true}]}"));
        routes.put("DELETE /documents/searchResults/s-1", r -> new MockResponse().setResponseCode(204));

        StepVerifier.create(connector(Map.of("includeContentStream", "false")).scan("default").then()).verifyComplete();

        assertEquals(1, requests.stream().filter(r -> r.startsWith("GET /documents/searchResults/")).count());
        assertEquals(1, count("DELETE /documents/searchResults/s-1"));
    }

    @Test
    void searchSendsTheConfiguredCqlAndAnyObjectsFilter() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        routes.put("POST /documents/search", r -> {
            bodies.add(r.getBody().readUtf8());
            return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody(fixture("search-empty.json"));
        });

        connector(Map.of("searchQuery", "OBJECTNUMBER2 LIKE 'contracts-2026*'")).scan("default").collectList().block();

        var body = new ObjectMapper().readTree(bodies.getFirst());
        assertEquals("SELECT * FROM DB1 WHERE (OBJECTNUMBER2 LIKE 'contracts-2026*')", body.path("cqlStatement").asText());
        assertEquals("ANY_OBJECTS", body.path("logicallyDeletedFilter").asText());
        assertEquals(2, body.path("fetchResultLimitation").asInt());
    }

    @Test
    void aSelectBasePathIsUsedVerbatim() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        routes.put("POST /documents/search", r -> {
            bodies.add(r.getBody().readUtf8());
            return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody(fixture("search-empty.json"));
        });

        connector(Map.of()).scan("SELECT * FROM DB1 WHERE OBJECTNAME LIKE 'NDA*'").collectList().block();

        assertEquals("SELECT * FROM DB1 WHERE OBJECTNAME LIKE 'NDA*'",
                new ObjectMapper().readTree(bodies.getFirst()).path("cqlStatement").asText());
    }

    @Test
    void anEmptyServerRestrictedResultEndsTheScanWithoutPaging() {
        json("POST /documents/search", "search-empty-restricted.json");
        routes.put("DELETE /documents/searchResults/s-9", r -> new MockResponse().setResponseCode(204));

        StepVerifier.create(connector(Map.of()).scan("default")).verifyComplete();

        assertEquals(0, requests.stream().filter(r -> r.startsWith("GET /documents/searchResults/")).count());
        assertEquals(1, count("DELETE /documents/searchResults/s-9"));
        assertEquals(1, count("POST /logout"));
    }

    @Test
    void aFailedSearchErrorsTheFluxAndStillLogsOut() {
        routes.put("POST /documents/search", r -> new MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json").setBody(fixture("error-unknown-repository.json")));

        StepVerifier.create(connector(Map.of()).scan("default"))
                .expectErrorMatches(e -> String.valueOf(e.getMessage()).contains("unknown"))
                .verify();

        assertEquals(1, count("POST /logout"));
    }

    @Test
    void anInvalidConfigurationFailsBeforeAnyRequest() {
        DoxisRepositoryConnector connector = new DoxisRepositoryConnector(DoxisRepositorySettings.fromConfiguration(Map.of(
                "url", server.url(BASE).toString(), "authType", "oauth2")));

        StepVerifier.create(connector.scan("default"))
                .expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("auth-type 'oauth2'")
                        && e.getMessage().contains("repository-id"))
                .verify();

        assertTrue(requests.isEmpty());
    }

    @Test
    void aFailingDocumentIsSkippedAndTheScanContinues() {
        searchFixtures();
        routes.put("GET " + DOCS + "d1/versions?initializeRepresentations=true",
                r -> new MockResponse().setResponseCode(500).setBody("{\"errorCode\":\"SEDNA9999\",\"message\":\"boom\"}"));

        List<RepositoryDocument> docs = connector(Map.of()).scan("default").collectList().block();

        assertNotNull(docs);
        assertEquals(List.of("doxis://DX4/DB1/documents/d2", "doxis://DX4/DB1/documents/d3"),
                docs.stream().map(RepositoryDocument::id).sorted().toList());
        assertEquals(1, count("POST /logout"));
    }

    @Test
    void documentClassFilterSkipsOtherClasses() {
        searchFixtures();

        List<RepositoryDocument> docs = connector(Map.of("documentClasses", "TX_LargeExternalDocument"))
                .scan("default").collectList().block();

        assertNotNull(docs);
        assertEquals(List.of("doxis://DX4/DB1/documents/d3"), docs.stream().map(RepositoryDocument::id).toList());
        assertEquals(0, count("GET " + DOCS + "d1/versions?initializeRepresentations=true"));
    }

    @Test
    void maxDocumentsStopsThePagingAndStillClosesAndLogsOut() {
        searchFixtures();

        List<RepositoryDocument> docs = connector(Map.of("maxDocuments", "1")).scan("default").collectList().block();

        assertNotNull(docs);
        assertEquals(List.of("doxis://DX4/DB1/documents/d1"), docs.stream().map(RepositoryDocument::id).toList());
        assertEquals(0, requests.stream().filter(r -> r.startsWith("GET /documents/searchResults/")).count());
        assertEquals(1, count("DELETE /documents/searchResults/s-1"));
        assertEquals(1, count("POST /logout"));
    }

    @Test
    void withoutContentDescriptorsOrAclsOnlyTheEssentialsAreRead() {
        searchFixtures();

        List<RepositoryDocument> docs = connector(Map.of("includeContentStream", "false", "includeAcls", "false",
                "includeDescriptors", "false")).scan("default").collectList().block();

        assertNotNull(docs);
        assertTrue(docs.stream().allMatch(d -> d.contentStream() == null));
        RepositoryDocument d1 = byId(docs).get("doxis://DX4/DB1/documents/d1");
        assertFalse(d1.metadata().containsKey("ObjectNumberExternal"));
        assertEquals(List.of("Mutual NDA"), d1.metadata().get("title"));
        assertEquals(0, requests.stream().filter(r -> r.contains("/contentObjects/") || r.contains("/permissions")).count());
    }

    @Test
    void theDescriptorPrefixGivesTheIssueStyleKeys() {
        searchFixtures();

        List<RepositoryDocument> docs = connector(Map.of("descriptorPrefix", "doxis_desc_")).scan("default").collectList().block();

        Map<String, List<String>> m = byId(docs).get("doxis://DX4/DB1/documents/d1").metadata();
        assertEquals(List.of("contracts-2026/NDA-001"), m.get("doxis_desc_ObjectNumberExternal"));
        assertFalse(m.containsKey("ObjectNumberExternal"));
    }

    @Test
    void allVersionsEmitsEveryVersionWithItsOwnIdAndContent() throws Exception {
        searchFixtures();

        List<RepositoryDocument> docs = connector(Map.of("versionMode", "all_versions", "documentClasses", "TX_MigratedDocument"))
                .scan("default").collectList().block();

        Map<String, RepositoryDocument> byId = byId(docs);
        RepositoryDocument v1 = byId.get("doxis://DX4/DB1/documents/d1/versions/1");
        RepositoryDocument v2 = byId.get("doxis://DX4/DB1/documents/d1");
        assertEquals(List.of("false"), v1.metadata().get("doxis.isLatestVersion"));
        assertEquals(List.of("Mutual NDA (draft)"), v1.metadata().get("ObjectName"));
        assertEquals(List.of("Supervisor"), v1.metadata().get("doxis.modifiedBy"));
        assertEquals(List.of("true"), v2.metadata().get("doxis.isLatestVersion"));
        try (InputStream in = v1.contentStream()) {
            assertEquals("%PDF old", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        v2.contentStream().close();
    }

    // ------------------------------------------------------------------ FOLDER mode

    @Test
    void folderModeTraversesNodesChildNodesAndSubEFilesOnce() {
        folderFixtures();

        List<RepositoryDocument> docs = connector(Map.of("crawlMode", "folder", "rootFolderId", "e1"))
                .scan("default").collectList().block();

        assertNotNull(docs);
        Map<String, RepositoryDocument> byId = byId(docs);
        assertEquals(List.of("doxis://DX4/DB1/documents/d1", "doxis://DX4/DB1/documents/d3", "doxis://DX4/DB1/documents/d4"),
                byId.keySet().stream().sorted().toList(), "d1 listed in two nodes is emitted once");
        assertEquals(3, docs.size());
        RepositoryDocument d4 = byId.get("doxis://DX4/DB1/documents/d4");
        assertEquals(List.of("e2"), d4.metadata().get("doxis.parentFolderId"));
        assertEquals(List.of("Acme Energy 2026"), d4.metadata().get("doxis.parentFolderName"));
        RepositoryDocument d3 = byId.get("doxis://DX4/DB1/documents/d3");
        assertEquals(List.of("e1"), d3.metadata().get("doxis.parentFolderId"));
        assertEquals(0, count("POST /documents/search"));
        assertEquals(1, count("GET " + RECORDS + "e1?initializeNodeHierarchy=true"));
        assertEquals(1, count("POST /logout"));
        docs.forEach(d -> {
            try {
                if (d.contentStream() != null) {
                    d.contentStream().close();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Test
    void folderModeWithoutSubfoldersStaysInTheTopNodes() {
        folderFixtures();

        List<RepositoryDocument> docs = connector(Map.of("crawlMode", "folder", "rootFolderId", "e1", "includeSubfolders", "false",
                "includeContentStream", "false")).scan("default").collectList().block();

        assertNotNull(docs);
        assertEquals(List.of("doxis://DX4/DB1/documents/d1"), docs.stream().map(RepositoryDocument::id).toList());
        assertEquals(0, count("GET " + RECORDS + "e1/nodes/n-sub/referencedInformationObjects"));
        assertEquals(0, count("GET " + RECORDS + "e2?initializeNodeHierarchy=true"));
    }

    @Test
    void aJobPathOverridesTheRootFolderInFolderMode() {
        folderFixtures();

        List<RepositoryDocument> docs = connector(Map.of("crawlMode", "folder", "rootFolderId", "e1", "includeContentStream", "false"))
                .scan("e2").collectList().block();

        assertNotNull(docs);
        assertEquals(List.of("doxis://DX4/DB1/documents/d4"), docs.stream().map(RepositoryDocument::id).toList());
        assertEquals(0, count("GET " + RECORDS + "e1?initializeNodeHierarchy=true"));
    }
}
