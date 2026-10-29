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
package org.opencrawling.filenet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios against the CSGQL mock (oc-filenet-repository-connector/filenet-mock), a GraphQL server over a subset of IBM's
 * schema. Runs when {@code CSGQL_MOCK_URL} is set (e.g. {@code http://localhost:18190}); the test script starts the mock.
 */
@EnabledIfEnvironmentVariable(named = "CSGQL_MOCK_URL", matches = ".+")
class FileNetMockServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String MOCK = System.getenv("CSGQL_MOCK_URL");

    @TempDir
    Path state;

    @BeforeEach
    void reset() throws Exception {
        admin("/mock/reset", "{}");
        admin("/mock/faults", "{\"clear\":true}");
    }

    static JsonNode admin(String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(MOCK + path.replace("{", "%7B").replace("}", "%7D"))).header("Content-Type", "application/json");
        HttpResponse<String> r = HTTP.send(body == null ? b.GET().build() : b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), path + ": " + r.body());
        return MAPPER.readTree(r.body());
    }

    private FileNetProperties settings(Map<String, String> extra) {
        Map<String, String> c = new HashMap<>(Map.of("url", MOCK + "/content-services-graphql/graphql", "objectStore", "OS1",
                "username", "p8crawler", "password", "secret", "stateDirectory", state.toString(), "batchSize", "7",
                "incrementalEnabled", "false", "clockSkewSeconds", "0"));
        c.putAll(extra);
        return FileNetProperties.fromConfiguration(c);
    }

    /** A crawl's documents by id, with each content stream read into the {@code text} map. */
    private record Result(Map<String, RepositoryDocument> docs, Map<String, String> text) {
        List<RepositoryDocument> deletes() {
            return docs.values().stream().filter(d -> d.action() == DocumentAction.DELETE).toList();
        }

        List<RepositoryDocument> upserts() {
            return docs.values().stream().filter(d -> d.action() == DocumentAction.UPSERT).toList();
        }
    }

    private Result crawl(FileNetProperties settings) throws IOException {
        Map<String, RepositoryDocument> docs = new LinkedHashMap<>();
        Map<String, String> text = new HashMap<>();
        for (RepositoryDocument d : new FileNetRepositoryConnector(settings).scan(null).collectList().block()) {
            if (docs.put(d.id(), d) != null) {
                throw new AssertionError("emitted twice: " + d.id());
            }
            if (d.contentStream() != null) {
                try (InputStream in = d.contentStream()) {
                    text.put(d.id(), new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
                }
            }
        }
        return new Result(docs, text);
    }

    private static String id(JsonNode mockDoc) {
        return "filenet://OS1/documents/" + FileNetProperties.stripBraces(mockDoc.path("seriesId").asText());
    }

    private static List<JsonNode> mockDocs() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        admin("/mock/documents", null).forEach(out::add);
        return out;
    }

    private static List<String> rules(RepositoryDocument d, String access) {
        return d.security().permissions().stream().filter(r -> r.access().equals(access)).map(PermissionRule::identity).toList();
    }

    @Test
    void fullCrawlReconcilesWithTheObjectStore() throws Exception {
        Result r = crawl(settings(Map.of()));
        List<JsonNode> expected = mockDocs();
        int elements = 0;
        for (JsonNode d : expected) {
            elements += Math.max(1, d.path("elements").size());
        }
        assertEquals(elements, r.docs().size(), "one OIS document per content element, or one per document without");
        for (JsonNode d : expected) {
            RepositoryDocument doc = r.docs().get(id(d));
            assertTrue(doc != null, "missing " + d.path("title").asText());
            assertEquals(d.path("currentVersionId").asText(), doc.metadata().get("filenet.id").getFirst());
            assertEquals(d.path("class").asText(), doc.metadata().get("filenet.class").getFirst());
            if (!d.path("folders").isEmpty()) {
                assertEquals(MAPPER.convertValue(d.path("folders"), List.class), doc.metadata().get("filenet.folderPath"));
            }
            String type = d.path("elements").isEmpty() ? "none" : d.path("elements").get(0).path("type").asText();
            switch (type) {
                case "pdf", "docx", "txt" -> {
                    String content = r.text().get(id(d));
                    assertTrue(content != null && !content.isEmpty(), "content of " + d.path("title").asText());
                    if (!type.equals("docx")) { // a DOCX is zipped
                        assertTrue(content.contains(d.path("code").asText()), d.path("code").asText());
                    }
                }
                case "reference" -> {
                    assertFalse(r.text().containsKey(id(d)));
                    assertTrue(doc.metadata().get("filenet.contentLocation").getFirst().startsWith("https://"));
                }
                default -> assertFalse(r.text().containsKey(id(d)));
            }
            if (d.path("elements").size() > 1) {
                RepositoryDocument annex = r.docs().get(id(d) + "/elements/1");
                assertEquals(List.of(id(d)), annex.metadata().get("filenet.parentId"));
                assertTrue(r.text().get(annex.id()).contains(d.path("code").asText() + "-A"));
            }
        }
        RepositoryDocument contract = r.upserts().stream()
                .filter(d -> "Contract".equals(d.metadata().get("filenet.class").getFirst())).findFirst().orElseThrow();
        assertTrue(contract.metadata().containsKey("filenet_prop_contractNumber"));
        assertTrue(contract.metadata().get("filenet_prop_regions").size() >= 1);
        assertFalse(contract.metadata().containsKey("filenet_prop_dateLastModified"));
        assertTrue(admin("/mock/stats", null).path("xsrfFailures").asInt() == 0);
    }

    @Test
    void permissionPatternsMapAsDesigned() throws Exception {
        Result r = crawl(settings(Map.of()));
        Map<Integer, RepositoryDocument> byPattern = new HashMap<>();
        Map<Integer, JsonNode> mock = new HashMap<>();
        for (JsonNode d : mockDocs()) {
            if (!d.path("folders").isEmpty() && !d.path("folders").get(0).asText().startsWith("/Contracts")) {
                byPattern.putIfAbsent(d.path("pattern").asInt(), r.docs().get(id(d)));
                mock.putIfAbsent(d.path("pattern").asInt(), d);
            }
        }
        assertEquals(List.of("contract readers", "jdoe"), rules(byPattern.get(0), "read"));
        assertEquals(List.of("#authenticated-users"), rules(byPattern.get(1), "read"));
        assertEquals(List.of("legal", "asmith"), rules(byPattern.get(2), "read"));
        assertEquals(List.of("mallory"), rules(byPattern.get(2), "deny"), "a deny of view content alone is a deny");
        assertEquals(List.of("asmith"), rules(byPattern.get(3), "read"));
        assertEquals(List.of("ceadmin"), rules(byPattern.get(4), "read"), "read without view content grants nothing");
        assertTrue(byPattern.get(5).security().permissions().isEmpty(), "no readers: closed, not public");
        assertEquals(List.of("s-1-5-21-1004336348-1177238915-682003330-512"), rules(byPattern.get(6), "read"));
        assertEquals(List.of("legal"), rules(byPattern.get(6), "deny"), "a marking deny wins over the direct allow");
        // inherited from /Contracts to everything below it
        RepositoryDocument underContracts = r.upserts().stream().filter(d -> d.metadata().getOrDefault("filenet.folderPath",
                List.of()).stream().anyMatch(p -> p.startsWith("/Contracts/"))).findFirst().orElseThrow();
        assertTrue(rules(underContracts, "read").contains("cn=finance,ou=groups,dc=example,dc=com"));
        assertTrue(rules(crawl(settings(Map.of("principalFormat", "short"))).docs().get(underContracts.id()), "read")
                .contains("finance"));
    }

    @Test
    void folderModeClassesAndVersionModes() throws Exception {
        List<JsonNode> mock = mockDocs();
        Set<String> inContracts = mock.stream().filter(d -> d.path("folders").toString().contains("/Contracts/"))
                .map(FileNetMockServerTest::id).collect(Collectors.toSet());
        Result folder = crawl(settings(Map.of("crawlMode", "folder", "rootFolderPath", "/Contracts")));
        assertEquals(inContracts, folder.upserts().stream().map(d -> d.id().replaceFirst("/elements/\\d+$", ""))
                .collect(Collectors.toSet()));
        assertTrue(crawl(settings(Map.of("crawlMode", "folder", "rootFolderPath", "/Contracts", "includeSubfolders", "false")))
                .docs().isEmpty(), "nothing is filed directly in /Contracts");
        Result invoices = crawl(settings(Map.of("documentClasses", "Invoice")));
        assertTrue(invoices.upserts().stream().allMatch(d -> d.metadata().get("filenet.class").getFirst().equals("Invoice")));
        Result sql = crawl(settings(Map.of("sqlQuery", "SELECT Id FROM Invoice WHERE [Paid] = True")));
        assertTrue(!sql.docs().isEmpty() && sql.docs().size() < invoices.docs().size());
        Result where = crawl(settings(Map.of("documentClasses", "Contract", "where", "[Counterparty] LIKE 'Acme%'")));
        assertTrue(where.upserts().stream().allMatch(d -> d.metadata().get("filenet_prop_counterparty").getFirst()
                .startsWith("Acme")) && !where.docs().isEmpty());

        JsonNode minor = mock.stream().filter(d -> d.path("versions").size() > 1 && d.path("versions")
                .get(d.path("versions").size() - 1).path("status").asText().equals("IN_PROCESS")).findFirst().orElseThrow();
        assertEquals(minor.path("currentVersionId").asText(),
                crawl(settings(Map.of())).docs().get(id(minor)).metadata().get("filenet.id").getFirst());
        assertEquals(minor.path("releasedVersionId").asText(),
                crawl(settings(Map.of("versionMode", "released"))).docs().get(id(minor)).metadata().get("filenet.id").getFirst());
        int versions = 0;
        for (JsonNode d : mock) {
            versions += d.path("versions").size();
        }
        Result all = crawl(settings(Map.of("versionMode", "all", "includeContentStream", "false")));
        assertEquals(versions, all.upserts().stream().filter(d -> !d.id().contains("/elements/")).count());
    }

    @Test
    void incrementalCrawlFollowsChangesAndReconcilesDeletions() throws Exception {
        FileNetProperties s = settings(Map.of("incrementalEnabled", "true"));
        int first = crawl(s).upserts().size();
        assertTrue(first > 0);
        assertTrue(crawl(s).docs().isEmpty(), "nothing changed");

        List<JsonNode> mock = mockDocs();
        JsonNode edited = mock.get(0);
        JsonNode deleted = mock.get(1);
        JsonNode restricted = mock.get(2);
        JsonNode withAnnexAndVersions = mock.stream().filter(d -> d.path("versions").size() > 1).findFirst().orElseThrow();
        admin("/mock/documents/" + edited.path("seriesId").asText() + "/checkin", "{\"text\":\"Second edition\"}");
        admin("/mock/documents/" + deleted.path("seriesId").asText() + "/delete", "{}");
        admin("/mock/documents/" + restricted.path("seriesId").asText() + "/permissions",
                "{\"permissions\":[{\"granteeName\":\"Auditors\",\"granteeType\":\"GROUP\",\"accessMask\":131201}]}");
        admin("/mock/documents/" + withAnnexAndVersions.path("seriesId").asText() + "/delete", "{\"currentVersionOnly\":true}");
        JsonNode created = admin("/mock/documents", "{\"class\":\"Document\",\"title\":\"Release notes\",\"text\":\"SPX\"}");

        Result r = crawl(s);
        RepositoryDocument e = r.docs().get(id(edited));
        assertEquals(DocumentAction.UPSERT, e.action());
        assertTrue(r.text().getOrDefault(id(edited), "").contains("Second edition") || e.metadata().get("mimeType")
                .getFirst().contains("word"), "the new version replaces the old one under the same id");
        assertEquals(DocumentAction.DELETE, r.docs().get(id(deleted)).action(), "reconciled deletion");
        List<String> readers = rules(r.docs().get(id(restricted)), "read");
        assertTrue(readers.contains("auditors"), "permission change re-indexed: " + readers);
        assertTrue(readers.stream().allMatch(x -> x.equals("auditors") || x.startsWith("cn=finance")),
                "only the new direct grant, plus what the folder still passes down: " + readers);
        RepositoryDocument restored = r.docs().get(id(withAnnexAndVersions));
        assertEquals(DocumentAction.UPSERT, restored.action(), "the previous version became current again");
        assertTrue(r.docs().containsKey(id(created)));
        assertEquals(1, r.deletes().stream().filter(d -> !d.id().contains("/elements/")).count(), r.deletes().toString());

        assertTrue(crawl(s).docs().isEmpty(), "settled again");
    }

    @Test
    void auditDeletionTracking() throws Exception {
        FileNetProperties s = settings(Map.of("incrementalEnabled", "true", "deletionTracking", "audit"));
        crawl(s);
        JsonNode deleted = mockDocs().get(3);
        admin("/mock/documents/" + deleted.path("seriesId").asText() + "/delete", "{}");
        Result r = crawl(s);
        assertEquals(DocumentAction.DELETE, r.docs().get(id(deleted)).action());
        assertTrue(admin("/mock/stats", null).path("requests").path("repositoryRows").asInt() >= 1);
        assertTrue(crawl(s).docs().isEmpty(), "the event watermark moved on");
    }

    @Test
    void throttlingIsRetriedAndBadCredentialsFail() throws Exception {
        admin("/mock/faults", "{\"op\":\"documents\",\"status\":503,\"count\":2,\"retryAfter\":0}");
        admin("/mock/faults", "{\"op\":\"download\",\"status\":429,\"count\":1,\"retryAfter\":0}");
        assertFalse(crawl(settings(Map.of())).docs().isEmpty());
        assertThrows(Exception.class, () -> crawl(settings(Map.of("password", "wrong"))));
        assertThrows(Exception.class, () -> crawl(settings(Map.of("objectStore", "NOPE"))));
        assertThrows(Exception.class, () -> crawl(settings(Map.of("where", "[NoSuchProperty] = 1"))));
    }
}
