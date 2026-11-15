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
package org.opencrawling.documentum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The REST protocol against MockWebServer, with responses in the shapes a REST 16.7 server returns
 * ({@code src/test/resources/documentum-mock-responses/}).
 */
class DocumentumClientTest {

    private MockWebServer server;
    private String base;

    @TempDir
    Path state;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        base = server.url("/dctm-rest").toString();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/documentum-mock-responses/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("{base}", base);
        }
    }

    private MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/vnd.emc.documentum+json;charset=UTF-8").setBody(body);
    }

    private DocumentumProperties settings(Map<String, String> extra) {
        Map<String, String> c = new HashMap<>(Map.of("url", base, "repositoryName", "corp", "username", "dctm_crawler",
                "password", "s3cret", "stateDirectory", state.toString(), "incrementalEnabled", "false"));
        c.putAll(extra);
        return DocumentumProperties.fromConfiguration(c);
    }

    private DocumentumClient client(DocumentumProperties settings) {
        return new DocumentumClient(settings, Duration.ofSeconds(5), 2, Duration.ofMillis(1),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), new ObjectMapper(),
                settings.oauth2AccessToken() != null ? settings::oauth2AccessToken : null);
    }

    private static String dql(RecordedRequest request) {
        String q = request.getRequestUrl().queryParameter("dql");
        return q == null ? "" : q;
    }

    @Test
    void dqlIsAGetOnTheRepositoryWithPagingAndDocumentumJson() throws Exception {
        server.enqueue(json(200, fixture("dql-listing-page.json")));
        server.enqueue(json(200, fixture("dql-empty-page.json")));
        DocumentumClient client = client(settings(Map.of()));
        DocumentumClient.Page page = client.dql("SELECT r_object_id FROM dm_document WHERE object_name = 'a b'", 1, 2);
        assertEquals(2, page.rows().size());
        assertTrue(page.hasNext());
        assertEquals("090277b680000105", page.rows().get(1).path("r_object_id").asText());
        assertEquals("2026-10-31T11:00:00.123+02:00", page.updated());
        RecordedRequest r = server.takeRequest();
        assertEquals("GET", r.getMethod());
        assertEquals("/dctm-rest/repositories/corp", r.getRequestUrl().encodedPath());
        assertEquals("SELECT r_object_id FROM dm_document WHERE object_name = 'a b'", dql(r));
        assertEquals("1", r.getRequestUrl().queryParameter("page"));
        assertEquals("2", r.getRequestUrl().queryParameter("items-per-page"));
        assertTrue(r.getHeader("Accept").contains("application/vnd.emc.documentum+json"));
        assertEquals("Basic " + Base64.getEncoder().encodeToString("dctm_crawler:s3cret".getBytes(StandardCharsets.UTF_8)),
                r.getHeader("Authorization"));
        DocumentumClient.Page empty = client.dql("SELECT r_object_id FROM dm_document", 2, 2);
        assertTrue(empty.rows().isEmpty(), "a page past the end has no entries key");
        assertFalse(empty.hasNext());
    }

    @Test
    void errorsCarryTheRestCodeButNeverTheCredentials() throws Exception {
        server.enqueue(json(401, fixture("error-unauthorized.json")));
        DocumentumApiException e = assertThrows(DocumentumApiException.class, () -> client(settings(Map.of())).repository());
        assertEquals(401, e.status());
        assertEquals("E_BAD_CREDENTIALS", e.code());
        assertFalse(e.getMessage().contains("s3cret"));
        server.enqueue(json(404, fixture("error-not-found.json")));
        assertTrue(client(settings(Map.of())).object("0900000000000000").isEmpty());
    }

    @Test
    void transientFailuresAreRetriedHonouringRetryAfter() throws Exception {
        server.enqueue(json(503, "{}").setHeader("Retry-After", "0"));
        server.enqueue(json(502, "{}"));
        server.enqueue(json(200, fixture("repository.json")));
        assertEquals("corp", client(settings(Map.of())).repository().path("name").asText());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void contentIsDownloadedFromTheLocalEnclosureOnly() throws Exception {
        server.enqueue(json(200, fixture("content-local.json")));
        server.enqueue(new MockResponse().setBody("hello world"));
        DocumentumClient client = client(settings(Map.of()));
        JsonNode content = client.content("090277b680000105", null).orElseThrow();
        RecordedRequest meta = server.takeRequest();
        assertEquals("local", meta.getRequestUrl().queryParameter("media-url-policy"));
        assertEquals("0", meta.getRequestUrl().queryParameter("page"));
        Path file = client.download(DocumentumClient.enclosure(content).orElseThrow(), 0).orElseThrow();
        try {
            assertEquals("hello world", Files.readString(file));
            assertTrue(server.takeRequest().getHeader("Authorization").startsWith("Basic "));
        } finally {
            Files.deleteIfExists(file);
        }
        JsonNode acs = new ObjectMapper().readTree(fixture("content-acs-only.json"));
        DocumentumApiException e = assertThrows(DocumentumApiException.class,
                () -> client.download(DocumentumClient.enclosure(acs).orElseThrow(), 0));
        assertTrue(e.getMessage().contains("acs.example.invalid"));
        assertEquals(2, server.getRequestCount(), "nothing sent to the ACS host");
    }

    @Test
    void ownLinksNamingAnotherHostAreFollowedOnTheConfiguredServerAndRedirectsStayOnIt() throws Exception {
        DocumentumClient client = client(settings(Map.of()));
        // behind a proxy or TLS offloading the server writes its links with the host it believes it has
        assertEquals(base + "/repositories/corp/objects/090277b680000105/content-media?format=pdf&page=0",
                client.checked("https://dctm-internal.invalid:8443/dctm-rest/repositories/corp/objects/090277b680000105/"
                        + "content-media?format=pdf&page=0").toString());
        for (String foreign : List.of("https://acs.example.invalid:9080/ACS/servlet/ACS?command=read",
                "https://other.invalid/another-app/repositories/corp/objects/090277b680000105/content-media")) {
            DocumentumApiException e = assertThrows(DocumentumApiException.class, () -> client.checked(foreign));
            assertEquals(DocumentumClient.FOREIGN_LINK, e.code());
        }
        String media = base + "/repositories/corp/objects/090277b680000105/content-media";
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", media + "?redirected=true"));
        server.enqueue(new MockResponse().setBody("redirected content"));
        Path file = client.download(media, 0).orElseThrow();
        try {
            assertEquals("redirected content", Files.readString(file));
        } finally {
            Files.deleteIfExists(file);
        }
        server.takeRequest();
        assertTrue(server.takeRequest().getPath().endsWith("redirected=true"));
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.invalid/c"));
        DocumentumApiException e = assertThrows(DocumentumApiException.class, () -> client.download(media, 0));
        assertEquals(DocumentumClient.FOREIGN_LINK, e.code(), "credentials never follow a redirect to another host");
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void oversizedDownloadsAreAbandoned() throws Exception {
        server.enqueue(new MockResponse().setBody("x".repeat(5000)));
        assertTrue(client(settings(Map.of())).download(base + "/repositories/corp/objects/x/content-media", 100).isEmpty());
    }

    @Test
    void missingRenditionIsEmpty() throws Exception {
        server.enqueue(json(404, fixture("error-not-found.json")));
        assertEquals(Optional.empty(), client(settings(Map.of())).content("090277b680000105", "pdf"));
        assertEquals("pdf", server.takeRequest().getRequestUrl().queryParameter("format"));
    }

    @Test
    void oauth2TokensAreFetchedAndRenewedOnce() throws Exception {
        List<String> tokens = new CopyOnWriteArrayList<>();
        server.setDispatcher(new Dispatcher() {
            int issued;

            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().startsWith("/oauth/token")) {
                    issued++;
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("{\"access_token\":\"t" + issued + "\",\"expires_in\":3600}");
                }
                String auth = request.getHeader("Authorization");
                tokens.add(auth);
                return "Bearer t2".equals(auth) ? json(200, "{\"name\":\"corp\"}") : json(401, "{\"status\":401}");
            }
        });
        DocumentumProperties s = settings(Map.of("authType", "oauth2", "oauth2TokenUrl", server.url("/oauth/token").toString(),
                "oauth2ClientId", "oc", "oauth2ClientSecret", "cs"));
        DocumentumClient client = DocumentumRepositoryConnector.newClient(s);
        assertEquals("corp", client.repository().path("name").asText());
        assertEquals(List.of("Bearer t1", "Bearer t2"), tokens);
    }

    /** A whole crawl over MockWebServer: listing, permission sets, users, object, folders, content. */
    @Test
    void crawlBuildsDocumentsFromTheRestResponses() throws Exception {
        List<String> queries = new CopyOnWriteArrayList<>();
        String listing = fixture("dql-listing-page.json");
        String acl = fixture("acl-finance-restricted.json");
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    String path = request.getRequestUrl().encodedPath();
                    String q = dql(request);
                    if (!q.isEmpty()) {
                        queries.add(q);
                    }
                    if (path.endsWith("/content-media")) {
                        return new MockResponse().setBody("contract text");
                    }
                    if (path.endsWith("/contents/content")) {
                        return json(200, fixture("content-local.json"));
                    }
                    if (path.contains("/objects/")) {
                        String id = path.substring(path.lastIndexOf('/') + 1);
                        String chronicle = id.equals("090277b680000103") ? "090277b680000102" : "090277b680000104";
                        return json(200, fixture("object-contract.json").replace("090277b680000104", chronicle)
                                .replace("090277b680000105", id));
                    }
                    if (q.isEmpty()) {
                        return json(200, fixture("repository.json"));
                    }
                    if (q.contains("FROM dm_cabinet")) {
                        return json(200, fixture("dql-empty-page.json"));
                    }
                    if (q.contains("FROM dm_acl")) {
                        return feed(List.of(acl.replace("oc_finance_restricted", "oc_public_read")
                                .replace("[1,7,3,3,3]", "[3,7,1,1,1]"), acl));
                    }
                    if (q.contains("FROM dm_user")) {
                        return feed(List.of("{\"user_name\":\"Rui Costa\",\"user_login_name\":\"rcosta\",\"r_is_group\":false}",
                                "{\"user_name\":\"Anna Smith\",\"user_login_name\":\"asmith\",\"r_is_group\":false}"));
                    }
                    if (q.contains("FROM dm_folder")) {
                        return feed(List.of("{\"r_object_id\":\"0b0277b680000105\",\"r_folder_path\":[\"/Corporate/Contracts\"]}",
                                "{\"r_object_id\":\"0b0277b680000106\",\"r_folder_path\":[\"/Regulatory/Submissions\"]}"));
                    }
                    if (q.contains("r_object_id > '090277b680000105'")) {
                        return json(200, fixture("dql-empty-page.json"));
                    }
                    return json(200, listing);
                } catch (IOException e) {
                    return new MockResponse().setResponseCode(500);
                }
            }

            private MockResponse feed(List<String> rows) {
                List<String> entries = new ArrayList<>();
                rows.forEach(r -> entries.add("{\"content\":{\"properties\":" + r + "}}"));
                return json(200, "{\"links\":[],\"entries\":[" + String.join(",", entries) + "]}");
            }
        });
        DocumentumProperties s = settings(Map.of("batchSize", "2"));
        Map<String, RepositoryDocument> docs = new HashMap<>();
        Map<String, String> text = new HashMap<>();
        for (RepositoryDocument d : new DocumentumRepositoryConnector(s).scan(null).collectList().block()) {
            docs.put(d.id(), d);
            if (d.contentStream() != null) {
                try (InputStream in = d.contentStream()) {
                    text.put(d.id(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        assertEquals(2, docs.size());
        RepositoryDocument contract = docs.get("documentum://corp/documents/090277b680000104");
        assertEquals(DocumentAction.UPSERT, contract.action());
        assertEquals("contract text", text.get(contract.id()));
        assertEquals(List.of("C-00001"), contract.metadata().get("documentum_attr_contract_number"));
        assertEquals(List.of("EMEA", "APAC"), contract.metadata().get("documentum_attr_regions"));
        assertEquals(List.of("4500000"), contract.metadata().get("documentum_attr_contract_value"));
        assertEquals(List.of("2025-02-02T10:00:00Z"), contract.metadata().get("documentum_attr_signed_date"));
        assertEquals(List.of("/Corporate/Contracts", "/Regulatory/Submissions"), contract.metadata().get("documentum.folderPath"));
        assertEquals(List.of("msw12"), contract.metadata().get("documentum.indexedFormat"));
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                contract.metadata().get("mimeType").getFirst());
        List<PermissionRule> rules = contract.security().permissions();
        assertTrue(rules.contains(new PermissionRule("finance", "group", "finance", "read")));
        assertTrue(rules.contains(new PermissionRule("mallory grey", "user", "mallory grey", "deny")));
        assertTrue(rules.contains(new PermissionRule("rui costa", "user", "rui costa", "read")), "dm_owner is the owner");
        // the keyset cursor: second page continues after the last row, in the offset the server returned (+02:00)
        String second = queries.stream().filter(q -> q.contains("r_object_id > '090277b680000105'")).findFirst().orElseThrow();
        assertTrue(second.contains("r_modify_date > DATE('2026/01/05 12:14:54','yyyy/mm/dd hh:mi:ss')"), second);
        assertTrue(second.contains("ORDER BY r_modify_date, r_object_id"), second);
        assertTrue(URLDecoder.decode(server.takeRequest().getPath(), StandardCharsets.UTF_8).startsWith("/dctm-rest/repositories/corp"));
    }
}
