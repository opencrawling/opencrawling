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
package org.opencrawling.mfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole scans against a mock MFWS (responses in {@code src/test/resources/mfiles-mock-responses/}): search, class filters,
 * time-window splitting, single- and multi-file documents, typed properties, ACLs, tombstones, version modes, incremental
 * state, views, the VAF change feed, and exactly one session per scan.
 */
class MFilesRepositoryConnectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NDA = "mfiles://" + MockMFiles.VAULT_KEY + "/objects/0/48192";
    private static final String MSA = "mfiles://" + MockMFiles.VAULT_KEY + "/objects/0/48193";

    private MockMFiles mfiles;

    @TempDir
    Path stateDir;

    @BeforeEach
    void setUp() throws Exception {
        mfiles = new MockMFiles();
        mfiles.session();
        mfiles.json("GET /objects/0/48192/3/properties", "properties-48192.json");
        mfiles.json("GET /objects/0/48193/2/properties", "properties-48193.json");
        mfiles.json("GET /objects/103/5/1/properties", "properties-5.json");
        mfiles.json("GET /objects/0/48192/3/permissions", "permissions-48192.json");
        mfiles.body("GET /objects/0/48192/3/files/7/content", "application/pdf", "%PDF-1.7 NDA");
        mfiles.body("GET /objects/0/48193/2/files/11/content", "application/octet-stream", "MSA body");
        mfiles.body("GET /objects/0/48193/2/files/12/content", "application/pdf", "%PDF Exhibit A");
    }

    @AfterEach
    void tearDown() throws Exception {
        mfiles.close();
    }

    private MFilesRepositoryConnector connector(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of("url", mfiles.url(), "vaultGuid", MockMFiles.VAULT,
                "username", "crawler", "password", "secret", "stateDirectory", stateDir.toString()));
        config.putAll(extra);
        return new MFilesRepositoryConnector(MFilesProperties.fromConfiguration(config));
    }

    private static List<RepositoryDocument> scan(MFilesRepositoryConnector connector, String path) {
        List<RepositoryDocument> documents = connector.scan(path).collectList().block();
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

    @Test
    void searchMapsFilesPropertiesAclsAndTombstones() throws Exception {
        mfiles.json("GET /objects?d=include&limit=500", "search-all.json");
        List<RepositoryDocument> documents = scan(connector(Map.of("objectType", "-1")), "default");
        Map<String, RepositoryDocument> docs = byId(documents);
        assertEquals(5, documents.size(), docs.keySet().toString());

        // single-file document: the file under the object id, typed properties, intersected ACL components
        RepositoryDocument nda = docs.get(NDA + "|UPSERT");
        assertEquals("%PDF-1.7 NDA", content(nda));
        Map<String, List<String>> m = nda.metadata();
        assertEquals(List.of("Global Vendor NDA - Final Signed.pdf"), m.get("name"));
        assertEquals(List.of("application/pdf"), m.get("mimeType"));
        assertEquals(List.of("48192"), m.get("mfiles.objId"));
        assertEquals(List.of("3"), m.get("mfiles.version"));
        assertEquals(List.of("14"), m.get("mfiles.classId"));
        assertEquals(List.of("Non-Disclosure Agreement"), m.get("mfiles.className"));
        assertEquals(List.of("Contracts"), m.get("mfiles.vaultName"));
        assertEquals(List.of("sarah.connor"), m.get("mfiles.createdBy"));
        assertEquals(List.of("alex.vance"), m.get("mfiles.lastModifiedBy"));
        assertEquals(List.of("Approved"), m.get("mfiles.state"));
        assertEquals(List.of("2026-09-27T15:20:00Z"), m.get("mfiles.lastModified"));
        assertEquals(List.of("true"), m.get("mfiles.singleFile"));
        assertEquals(List.of("12"), m.get("mfiles.fileSize"));
        assertEquals(List.of("Cyberdyne Systems"), m.get("mfiles_prop_customer"));
        assertEquals(List.of("5"), m.get("mfiles_prop_customer_id"));
        assertEquals(List.of("150000"), m.get("mfiles_prop_contractValue"));
        assertEquals(List.of("2026-05-01"), m.get("mfiles_prop_effectiveDate"));
        assertEquals(List.of("true"), m.get("mfiles_prop_signed"));
        assertEquals(List.of("Legal", "Vendor"), m.get("mfiles_prop_tags"));
        assertFalse(m.containsKey("mfiles_prop_p1099"), "a property without value is not emitted");
        assertEquals(Instant.parse("2026-09-27T15:20:00Z"), nda.lastModified());
        assertEquals("mfiles:user:12,mfiles:group:5", nda.acl());
        assertTrue(nda.security().permissions().contains(new PermissionRule("mfiles:user:12", "user", "mfiles:user:12", "read")));
        assertTrue(nda.security().permissions().contains(new PermissionRule("mfiles:group:5", "group", "mfiles:group:5", "read")));
        assertTrue(nda.security().permissions().contains(new PermissionRule("mfiles:user:9", "user", "mfiles:user:9", "deny")));
        assertTrue(nda.security().permissions().stream().noneMatch(r -> r.identity().equals("mfiles:group:7")),
                "group 7 is not allowed by the named ACL component");

        // multi-file document: one document per file, linked to the object; no readable permissions -> nobody
        RepositoryDocument file11 = docs.get(MSA + "/files/11|UPSERT");
        RepositoryDocument file12 = docs.get(MSA + "/files/12|UPSERT");
        assertEquals("MSA body", content(file11));
        assertEquals("%PDF Exhibit A", content(file12));
        assertEquals(List.of("MSA.docx"), file11.metadata().get("name"));
        assertEquals(List.of(MSA), file12.metadata().get("mfiles.parentId"));
        assertEquals(List.of("2"), file12.metadata().get("mfiles.fileCount"));
        assertTrue(file12.security().permissions().isEmpty(), "deny by default, never public");
        assertEquals("", file12.acl());

        // deleted object -> tombstone; object without files -> metadata only
        assertEquals(DocumentAction.DELETE, docs.get("mfiles://" + MockMFiles.VAULT_KEY + "/objects/0/48194|DELETE").action());
        RepositoryDocument customer = docs.get("mfiles://" + MockMFiles.VAULT_KEY + "/objects/103/5|UPSERT");
        assertNull(customer.contentStream());
        assertEquals(List.of("Cyberdyne Systems"), customer.metadata().get("title"));

        assertEquals(1, mfiles.count("POST /server/authenticationtokens"));
        assertEquals(1, mfiles.count("DELETE /session"), "exactly one session per scan, always ended");
        closeAll(documents);
    }

    @Test
    void classFilterSearchesEachClassAndObjectType() {
        mfiles.body("GET /objects?o=0&p100=14&d=include&limit=500", "application/json", "{\"Items\":[],\"MoreResults\":false}");
        mfiles.body("GET /objects?o=0&p100=22&d=include&limit=500", "application/json", "{\"Items\":[],\"MoreResults\":false}");
        assertTrue(scan(connector(Map.of("classIds", "14,22")), "default").isEmpty());
        assertEquals(1, mfiles.count("GET /objects?o=0&p100=14&d=include&limit=500"));
        assertEquals(1, mfiles.count("GET /objects?o=0&p100=22&d=include&limit=500"));
    }

    @Test
    void jobPathRunsAQuickSearch() {
        mfiles.body("GET /objects?q=master agreement&o=0&limit=500", "application/json", "{\"Items\":[]}");
        scan(connector(Map.of("emitDeletes", "false")), "master agreement");
        assertEquals(1, mfiles.count("GET /objects?q=master agreement&o=0&limit=500"), mfiles.requests.toString());
    }

    /** MFWS cuts a search at the limit and has no offset: the connector splits the last-modified window until each fits. */
    @Test
    void searchOverTheLimitIsSplitIntoTimeWindows() throws Exception {
        List<ObjectNode> vault = new ArrayList<>();
        String[] modified = {"2019-03-01T10:00:00Z", "2022-07-15T08:30:00Z", "2024-01-01T00:00:00Z", "2026-02-02T12:00:00Z",
                "2026-09-30T23:59:59Z"};
        for (int i = 0; i < modified.length; i++) {
            ObjectNode object = MAPPER.createObjectNode();
            object.putObject("ObjVer").put("ID", 900 + i).put("Type", 0).put("Version", 1);
            object.put("Title", "Doc " + i).put("Class", 0).put("LastModifiedUtc", modified[i]).putArray("Files");
            vault.add(object);
            mfiles.body("GET /objects/0/" + (900 + i) + "/1/properties", "application/json", "[]");
        }
        Pattern from = Pattern.compile("p21>=([^&]+)");
        Pattern to = Pattern.compile("p21<=([^&]+)");
        mfiles.routes.put("GET /objects?*", request -> {
            String query = MockMFiles.decode(request.getPath());
            Instant lower = bound(from.matcher(query), Instant.MIN);
            Instant upper = bound(to.matcher(query), Instant.MAX);
            List<ObjectNode> hits = vault.stream().filter(o -> {
                Instant lm = Instant.parse(o.path("LastModifiedUtc").asText());
                return !lm.isBefore(lower) && !lm.isAfter(upper);
            }).collect(Collectors.toList());
            ObjectNode result = MAPPER.createObjectNode();
            ArrayNode items = result.putArray("Items");
            hits.stream().limit(2).forEach(items::add);
            result.put("MoreResults", hits.size() > 2);
            return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(result.toString());
        });

        List<RepositoryDocument> documents = scan(connector(Map.of("batchSize", "2", "includeAcls", "false",
                "emitDeletes", "false")), "default");
        assertEquals(5, documents.size(), "every object exactly once");
        assertEquals(5, byId(documents).size());
        assertTrue(mfiles.requests.stream().filter(r -> r.startsWith("GET /objects?")).count() > 3);
    }

    private static Instant bound(Matcher matcher, Instant fallback) {
        return matcher.find() ? Instant.parse(matcher.group(1)) : fallback;
    }

    @Test
    void incrementalSkipsUnchangedObjectsAndTombstonesFilesThatDisappeared() throws Exception {
        Map<String, String> config = Map.of("incrementalEnabled", "true", "includeAcls", "false");
        mfiles.body("GET /objects?o=0&d=include&limit=500", "application/json",
                "{\"Items\":[" + MockMFiles.fixture("object-48193.json") + "]}");
        List<RepositoryDocument> first = scan(connector(config), "default");
        assertEquals(List.of(MSA + "/files/11", MSA + "/files/12"), first.stream().map(RepositoryDocument::id).sorted().toList());
        closeAll(first);

        JsonNode state = MAPPER.readTree(Files.list(stateDir).findFirst().orElseThrow().toFile());
        String watermark = state.path("watermark").asText();
        assertFalse(watermark.isBlank());
        assertEquals(2, state.path("objects").path("0/48193").path("ids").size());

        // version 3 of the agreement dropped file 11
        ObjectNode changed = (ObjectNode) MAPPER.readTree(MockMFiles.fixture("object-48193.json"));
        ((ObjectNode) changed.path("ObjVer")).put("Version", 3);
        changed.put("LastModifiedUtc", "2026-10-19T10:00:00Z");
        ((ArrayNode) changed.path("Files")).remove(0);
        String nextQuery = "GET /objects?o=0&d=include&p21>=" + watermark + "&limit=500";
        mfiles.body(nextQuery, "application/json", "{\"Items\":[" + changed + "]}");
        mfiles.json("GET /objects/0/48193/3/properties", "properties-48193.json");
        mfiles.body("GET /objects/0/48193/3/files/12/content", "application/pdf", "%PDF Exhibit A v3");

        List<RepositoryDocument> second = scan(connector(config), "default");
        Map<String, RepositoryDocument> docs = byId(second);
        assertEquals(2, second.size(), docs.keySet().toString());
        assertEquals("%PDF Exhibit A v3", content(docs.get(MSA + "/files/12|UPSERT")));
        assertNotNull(docs.get(MSA + "/files/11|DELETE"), "the removed file is purged downstream");
        assertEquals(1, mfiles.count(nextQuery), "the second run searches from the watermark");

        // third run: the same object again, unchanged -> nothing emitted, no per-object calls
        String thirdWatermark = MAPPER.readTree(Files.list(stateDir).findFirst().orElseThrow().toFile()).path("watermark").asText();
        mfiles.body("GET /objects?o=0&d=include&p21>=" + thirdWatermark + "&limit=500", "application/json",
                "{\"Items\":[" + changed + "]}");
        long propertyCalls = mfiles.count("GET /objects/0/48193/3/properties");
        assertTrue(scan(connector(config), "default").isEmpty());
        assertEquals(propertyCalls, mfiles.count("GET /objects/0/48193/3/properties"));
    }

    @Test
    void failedObjectKeepsTheWatermark() throws Exception {
        mfiles.json("GET /objects?o=0&d=include&limit=500", "search-all.json");
        mfiles.status("GET /objects/0/48193/2/properties", 500);
        Map<String, String> config = Map.of("incrementalEnabled", "true", "includeAcls", "false", "maxRetries", "0");
        List<RepositoryDocument> documents = scan(connector(config), "default");
        closeAll(documents);
        JsonNode state = MAPPER.readTree(Files.list(stateDir).findFirst().orElseThrow().toFile());
        assertTrue(state.path("watermark").isNull() || state.path("watermark").isMissingNode(),
                "a failed object must be listed again next run");
        assertTrue(state.path("objects").has("0/48192"));
        assertFalse(state.path("objects").has("0/48193"));
    }

    @Test
    void allVersionsEmitsOlderVersionsUnderVersionIds() throws Exception {
        mfiles.body("GET /objects?o=0&d=include&limit=500", "application/json",
                "{\"Items\":[" + MockMFiles.fixture("object-48192.json") + "]}");
        mfiles.json("GET /objects/0/48192/history", "history-48192.json");
        mfiles.json("GET /objects/0/48192/2/properties", "properties-48192.json");
        mfiles.body("GET /objects/0/48192/2/files/7/content", "application/pdf", "%PDF draft");
        List<RepositoryDocument> documents = scan(connector(Map.of("versionMode", "all_versions")), "default");
        Map<String, RepositoryDocument> docs = byId(documents);
        assertEquals(2, documents.size());
        assertEquals(List.of("true"), docs.get(NDA + "|UPSERT").metadata().get("mfiles.isLatestVersion"));
        RepositoryDocument draft = docs.get(NDA + "/versions/2|UPSERT");
        assertEquals("%PDF draft", content(draft));
        assertEquals(List.of("false"), draft.metadata().get("mfiles.isLatestVersion"));
        assertEquals(List.of("2"), draft.metadata().get("mfiles.version"));
        closeAll(documents);
    }

    @Test
    void fileOverTheSizeLimitIsIndexedByMetadataOnly() throws Exception {
        mfiles.body("GET /objects?o=0&d=include&limit=500", "application/json",
                "{\"Items\":[" + MockMFiles.fixture("object-48192.json") + "]}");
        List<RepositoryDocument> documents = scan(connector(Map.of("maxFileSizeBytes", "4")), "default");
        assertEquals(1, documents.size());
        assertNull(documents.getFirst().contentStream());
        assertEquals(List.of("Global Vendor NDA - Final Signed.pdf"), documents.getFirst().metadata().get("mfiles.fileName"));
    }

    @Test
    void viewModeWalksSubViewsAndPropertyFolders() throws Exception {
        mfiles.json("GET /views/v101/items", "view-root.json");
        mfiles.json("GET /views/v101/v102/items", "view-102.json");
        mfiles.json("GET /views/v101/L5/items", "view-L5.json");
        List<RepositoryDocument> documents = scan(connector(Map.of("crawlMode", "view", "viewId", "101", "includeFiles", "false")),
                "default");
        Map<String, RepositoryDocument> docs = byId(documents);
        assertEquals(3, documents.size(), docs.keySet().toString());
        assertNotNull(docs.get(NDA + "|UPSERT"), "seen twice, emitted once");
        assertNotNull(docs.get(MSA + "/files/11|UPSERT"));
        assertNull(docs.get(NDA + "|UPSERT").contentStream());
    }

    @Test
    void vafModeDrainsTheChangeFeedAndNeverSendsCredentialsElsewhere() throws Exception {
        String changes = "GET /vaf/indexer/changes?vaultGuid=" + MockMFiles.VAULT + "&since=";
        mfiles.json(changes + "&pageSize=500", "vaf-changes-1.json");
        mfiles.json(changes + "c-2&pageSize=500", "vaf-changes-2.json");
        String normalized = "/normalized?vaultGuid=" + MockMFiles.VAULT;
        mfiles.json("GET /vaf/indexer/object/0/48192/3" + normalized + "&type=0&id=48192&version=3", "vaf-normalized-48192.json");
        mfiles.json("GET /vaf/indexer/object/0/48196/4" + normalized + "&type=0&id=48196&version=4", "vaf-normalized-48196.json");

        List<RepositoryDocument> documents = scan(connector(Map.of("crawlMode", "vaf", "vafBasePath", "/vaf/indexer",
                "incrementalEnabled", "true")), "default");
        Map<String, RepositoryDocument> docs = byId(documents);
        assertEquals(2, documents.size(), docs.keySet().toString());

        RepositoryDocument nda = docs.get(NDA + "|UPSERT");
        assertEquals("%PDF-1.7 NDA", content(nda));
        assertEquals(List.of("Legal", "Vendor"), nda.metadata().get("mfiles_prop_tags"));
        assertEquals(List.of("application/pdf"), nda.metadata().get("mimeType"));
        assertEquals("mfiles:user:12", nda.metadata().get("mfiles_identity_users").getFirst());
        assertEquals("mfiles:group:5,mfiles:role:3", nda.metadata().get("mfiles_identity_groups").getFirst());
        assertTrue(nda.security().permissions().contains(new PermissionRule("mfiles:user:9", "user", "mfiles:user:9", "deny")));

        // created and deleted within the same feed page: only the delete counts
        assertNotNull(docs.get("mfiles://" + MockMFiles.VAULT_KEY + "/objects/0/48195|DELETE"));
        assertTrue(mfiles.requests.stream().noneMatch(r -> r.contains("/48195/")));

        // the normalized view of 48196 points its download at another host: refused, the object counts as failed,
        // so the cursor is not advanced and the next run retries the feed
        assertNull(docs.get("mfiles://" + MockMFiles.VAULT_KEY + "/objects/0/48196|UPSERT"));
        JsonNode state = MAPPER.readTree(Files.list(stateDir).findFirst().orElseThrow().toFile());
        assertEquals("", state.path("vafCursor").asText());
        assertTrue(state.path("objects").has("0/48192"));
        closeAll(documents);
    }

    @Test
    void nameFormatResolvesPrincipalsThroughTheValueListsOncePerScan() throws Exception {
        mfiles.body("GET /objects?o=0&d=include&limit=500", "application/json",
                "{\"Items\":[" + MockMFiles.fixture("object-48192.json") + "]}");
        mfiles.json("GET /valuelists/6/items", "users.json");
        mfiles.json("GET /valuelists/16/items", "user-groups.json");
        List<RepositoryDocument> documents = scan(connector(Map.of("principalFormat", "name", "includeFiles", "false")), "default");
        RepositoryDocument nda = documents.getFirst();
        assertEquals("sarah.connor,legal-department", nda.acl());
        assertTrue(nda.security().permissions().contains(new PermissionRule("mfiles:user:9", "user", "mfiles:user:9", "deny")),
                "an unknown user keeps its token");
        assertEquals(1, mfiles.count("GET /valuelists/6/items"));
        assertEquals(1, mfiles.count("GET /valuelists/16/items"));
    }

    @Test
    void connectRefusesASessionScopedToAnotherVault() {
        mfiles.body("GET /session/vault", "application/json", "{\"Name\":\"Other\",\"GUID\":\"{11111111-2222-3333-4444-555555555555}\"}");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> connector(Map.of()).connect());
        assertTrue(e.getMessage().contains("11111111-2222-3333-4444-555555555555"));
    }

    @Test
    void scanOfAnUnconfiguredConnectorFailsWithTheProblems() {
        MFilesRepositoryConnector connector = new MFilesRepositoryConnector(MFilesProperties.fromConfiguration(Map.of()));
        Function<Throwable, Boolean> mentionsVault = t -> t.getMessage().contains("vault-guid");
        Throwable error = assertThrows(RuntimeException.class, () -> connector.scan("default").collectList().block());
        assertTrue(mentionsVault.apply(error.getCause() != null ? error.getCause() : error), error.getMessage());
    }

    @Test
    void sourceInstanceIsTheConfiguredUrl() {
        MFilesRepositoryConnector connector = new MFilesRepositoryConnector(MFilesProperties.fromConfiguration(
                Map.of("url", "https://mfiles.example.com/REST", "vaultGuid", MockMFiles.VAULT)));
        assertEquals("https://mfiles.example.com/REST", connector.sourceInstance());
    }
}
