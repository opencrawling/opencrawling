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
package org.opencrawling.doxis.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DoxisClientTest {

    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJTdXBlcnZpc29yIiwiY3VzdG9tZXIiOiJmYXN0c3RhcnRlciJ9.c2lnbmF0dXJl";
    private static final String BASE = "/restws/publicws/rest/api/v1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;
    private DoxisClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DoxisClient(server.url(BASE).toString(), "faststarter", "Supervisor", "secret#1", "admins",
                "OpenCrawling-Test", Duration.ofSeconds(5), 2, Duration.ofMillis(1), HttpClient.newHttpClient(), objectMapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void enqueueJson(int status, String body) {
        server.enqueue(new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body));
    }

    private void enqueueLogin() {
        enqueueJson(200, Fixtures.text("login.json"));
    }

    @Test
    void loginSendsLoginParamsWithRoleAndStripsQuotedJwt() throws Exception {
        enqueueLogin();

        assertEquals(JWT, client.login());

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals(BASE + "/login", req.getPath());
        assertNull(req.getHeader("Authorization"));
        JsonNode body = objectMapper.readTree(req.getBody().readUtf8());
        assertEquals("faststarter", body.path("customerName").asText());
        assertEquals("Supervisor", body.path("userName").asText());
        assertEquals("secret#1", body.path("password").asText());
        assertEquals("admins", body.path("role").asText());
    }

    @Test
    void authenticatedCallsUseBearerJwtAndLoginOnce() throws Exception {
        enqueueLogin();
        enqueueJson(200, Fixtures.text("repository.json"));
        enqueueJson(200, Fixtures.text("document-types.json"));

        assertEquals("D_TEXTER", client.getRepository("D_TEXTER").path("name").asText());
        assertEquals(2, client.listDocumentTypes().size());

        server.takeRequest(); // login
        RecordedRequest repo = server.takeRequest();
        assertEquals(BASE + "/dmsRepositories/D_TEXTER", repo.getPath());
        assertEquals("Bearer " + JWT, repo.getHeader("Authorization"));
        assertEquals(BASE + "/documentTypes", server.takeRequest().getPath());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void expiredSessionTriggersOneRelogin() throws Exception {
        enqueueLogin();
        server.enqueue(new MockResponse().setResponseCode(401));
        enqueueLogin();
        enqueueJson(200, Fixtures.text("repository.json"));

        assertEquals("D_TEXTER", client.getRepository("D_TEXTER").path("name").asText());

        assertEquals(4, server.getRequestCount());
    }

    @Test
    void searchUsesCqlAndClosesFollowUpSearch() throws Exception {
        enqueueLogin();
        enqueueJson(200, Fixtures.text("search-hit.json"));
        server.enqueue(new MockResponse().setResponseCode(204));

        List<String> ids = client.searchDocumentIds("SELECT * FROM D_TEXTER WHERE OBJECTNUMBER = 'doc-1'", false);

        assertEquals(List.of("doc-0001"), ids);
        server.takeRequest();
        RecordedRequest search = server.takeRequest();
        assertEquals(BASE + "/documents/search", search.getPath());
        JsonNode body = objectMapper.readTree(search.getBody().readUtf8());
        assertEquals("SELECT * FROM D_TEXTER WHERE OBJECTNUMBER = 'doc-1'", body.path("cqlStatement").asText());
        assertEquals("NON_DELETED_OBJECTS", body.path("logicallyDeletedFilter").asText());
        RecordedRequest close = server.takeRequest();
        assertEquals("DELETE", close.getMethod());
        assertEquals(BASE + "/documents/searchResults/8b066885-7e73-4bca-b257-b1807012cdea", close.getPath());
    }

    @Test
    void createDocumentStreamsMultipartWithParamsAndContent() throws Exception {
        enqueueLogin();
        enqueueJson(200, Fixtures.text("document-created.json"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("documentTypeUUID", "b89f3e49-ff11-467f-aba0-03740736646f");
        params.put("mimeTypeName", "application/pdf");
        ContentBody content = new ContentBody("msa.pdf", "application/pdf",
                () -> new ByteArrayInputStream("%PDF-1.7 contract".getBytes(StandardCharsets.UTF_8)), true);

        JsonNode document = client.createDocument("D_TEXTER", params, content);

        assertEquals("doc-0001", document.path("uuid").asText());
        server.takeRequest();
        RecordedRequest req = server.takeRequest();
        assertEquals(BASE + "/dmsRepositories/D_TEXTER/documents", req.getPath());
        assertTrue(req.getHeader("Content-Type").startsWith("multipart/form-data; boundary="));
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("Content-Disposition: form-data; name=\"documentParams\""));
        assertTrue(body.contains("\"documentTypeUUID\":\"b89f3e49-ff11-467f-aba0-03740736646f\""));
        assertTrue(body.contains("Content-Disposition: form-data; name=\"inputStream\"; filename=\"msa.pdf\""));
        assertTrue(body.contains("%PDF-1.7 contract"));
    }

    @Test
    void createDocumentCanFileIntoARecordViaRelationshipParams() throws Exception {
        enqueueLogin();
        enqueueJson(200, Fixtures.text("document-created.json"));

        client.createDocument("D_TEXTER", Map.of("mimeTypeName", "text/plain"),
                Map.of("sourceObjectUUID", "efile-4711", "sourceObjectType", "RECORD"), null);

        server.takeRequest();
        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("Content-Disposition: form-data; name=\"relationshipParams\""));
        assertTrue(body.contains("\"sourceObjectUUID\":\"efile-4711\""));
    }

    @Test
    void createDocumentWithPredefinedLocatorSendsNoContentPart() throws Exception {
        enqueueLogin();
        enqueueJson(200, Fixtures.text("document-created.json"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("documentTypeUUID", "b89f3e49-ff11-467f-aba0-03740736646f");
        params.put("predefinedLocator", "2026/04/21/5a1e2f34.dat");
        params.put("contentLength", 5_000_000_000_000L);
        client.createDocument("D_TEXTER", params, null);

        server.takeRequest();
        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"predefinedLocator\":\"2026/04/21/5a1e2f34.dat\""));
        assertTrue(body.contains("\"contentLength\":5000000000000"));
        assertFalse(body.contains("name=\"inputStream\""));
    }

    @Test
    void singleUseUploadIsNotRetried() throws Exception {
        enqueueLogin();
        server.enqueue(new MockResponse().setResponseCode(503));
        AtomicInteger opened = new AtomicInteger();
        ContentBody content = new ContentBody("a.txt", "text/plain", () -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(new byte[]{1, 2, 3});
        }, false);

        DoxisApiException e = assertThrows(DoxisApiException.class,
                () -> client.createDocument("D_TEXTER", Map.of("mimeTypeName", "text/plain"), content));

        assertEquals(503, e.getStatusCode());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void reopenableUploadIsRetriedAfterThrottling() throws Exception {
        enqueueLogin();
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "0"));
        enqueueJson(200, Fixtures.text("document-created.json"));
        ContentBody content = new ContentBody("a.txt", "text/plain", () -> new ByteArrayInputStream(new byte[]{1}), true);

        client.createDocument("D_TEXTER", Map.of("mimeTypeName", "text/plain"), content);

        assertEquals(3, server.getRequestCount());
    }

    @Test
    void errorEnvelopeIsSurfaced() {
        enqueueLogin();
        enqueueJson(500, Fixtures.text("error-type-not-allowed.json"));

        DoxisApiException e = assertThrows(DoxisApiException.class,
                () -> client.createDocument("D_TEXTER", Map.of("mimeTypeName", "text/plain"), null));

        assertEquals(500, e.getStatusCode());
        assertEquals("INSTANCE0014", e.getErrorCode());
        assertTrue(e.getMessage().contains("can't be stored within content repository"));
    }

    @Test
    void storageSystemRefusingPredefinedLocatorsIsSurfaced() {
        enqueueLogin();
        enqueueJson(500, Fixtures.text("error-predefined-locator-not-allowed.json"));

        DoxisApiException e = assertThrows(DoxisApiException.class,
                () -> client.createDocument("D_TEXTER", Map.of("predefinedLocator", "oc-test/e1.pdf"), null));

        assertTrue(e.getMessage().contains("predefined locators are not allowed for repository sb1"));
    }

    @Test
    void logicalRemoveAndPhysicalDeleteTolerateMissingDocuments() throws Exception {
        enqueueLogin();
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse().setResponseCode(404));

        client.removeDocumentLogically("D_TEXTER", "doc-0001");
        client.deleteDocumentPhysically("D_TEXTER", "doc-gone");

        server.takeRequest();
        RecordedRequest remove = server.takeRequest();
        assertEquals("POST", remove.getMethod());
        assertEquals(BASE + "/dmsRepositories/D_TEXTER/documents/doc-0001/remove", remove.getPath());
        RecordedRequest delete = server.takeRequest();
        assertEquals("DELETE", delete.getMethod());
        assertEquals(BASE + "/dmsRepositories/D_TEXTER/documents/doc-gone", delete.getPath());
    }

    @Test
    void versionsAreRequestedWithRepresentations() throws Exception {
        enqueueLogin();
        enqueueJson(200, Fixtures.text("versions.json"));

        List<JsonNode> versions = client.getVersions("D_TEXTER", "doc-0001");

        assertEquals(1, versions.size());
        assertEquals("co-0001", versions.getFirst().path("representations").get(0).path("contentObjects").get(0).path("uuid").asText());
        server.takeRequest();
        assertEquals(BASE + "/dmsRepositories/D_TEXTER/documents/doc-0001/versions?initializeRepresentations=true",
                server.takeRequest().getPath());
    }

    @Test
    void logoutSendsJsonBodyAndForgetsSession() throws Exception {
        enqueueLogin();
        server.enqueue(new MockResponse().setResponseCode(204));
        client.login();

        client.logout();

        server.takeRequest();
        RecordedRequest logout = server.takeRequest();
        assertEquals(BASE + "/logout", logout.getPath());
        assertEquals("application/json", logout.getHeader("Content-Type"));
        assertEquals("Bearer " + JWT, logout.getHeader("Authorization"));
    }

    @Test
    void recordNodeHierarchyIsRequestedExplicitly() throws Exception {
        enqueueLogin();
        enqueueJson(200, "{\"uuid\":\"efile-4711\",\"versions\":[{\"folderNodes\":[{\"uuid\":\"node-root\"}]}]}");

        JsonNode record = client.getRecordWithNodes("D_TEXTER", "efile-4711");

        assertEquals("node-root", record.path("versions").get(0).path("folderNodes").get(0).path("uuid").asText());
        server.takeRequest(); // login
        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals(BASE + "/dmsRepositories/D_TEXTER/records/efile-4711?initializeNodeHierarchy=true", req.getPath());
    }

    @Test
    void createFolderNodePostsFolderNodeParamsAndAcceptsQuotedOrBareIds() throws Exception {
        enqueueLogin();
        enqueueJson(200, "\"node-quoted\"");
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "text/plain").setBody("node-bare"));

        Map<String, Object> params = Map.of("nodeName", "Documents", "parentFolderNodeUUID", "node-root", "nodeMetaType", "STATIC");
        assertEquals("node-quoted", client.createFolderNode("D_TEXTER", "efile-4711", params));
        assertEquals("node-bare", client.createFolderNode("D_TEXTER", "efile-4711", params));

        server.takeRequest(); // login
        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals(BASE + "/dmsRepositories/D_TEXTER/records/efile-4711/nodes", req.getPath());
        JsonNode body = objectMapper.readTree(req.getBody().readUtf8());
        assertEquals("Documents", body.path("nodeName").asText());
        assertEquals("node-root", body.path("parentFolderNodeUUID").asText());
        assertEquals("STATIC", body.path("nodeMetaType").asText());
    }
}
