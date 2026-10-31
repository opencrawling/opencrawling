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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios against the DCTM REST mock (oc-documentum-repository-connector/documentum-mock): a REST server over typed
 * object tables with a DQL engine and Documentum's own permission evaluation. Runs when {@code DCTM_MOCK_URL} is set
 * (e.g. {@code http://localhost:18290}); the test script starts the mock, with a non-UTC server time zone.
 */
@EnabledIfEnvironmentVariable(named = "DCTM_MOCK_URL", matches = ".+")
class DocumentumMockServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String MOCK = System.getenv("DCTM_MOCK_URL");
    private static final Set<String> EXCLUDED_ACLS = Set.of("oc_legal_required", "oc_region_set");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)$");

    @TempDir
    Path state;

    @BeforeEach
    void reset() throws Exception {
        admin("/mock/reset", "{}");
        admin("/mock/faults", "{\"clear\":true}");
    }

    static JsonNode admin(String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(MOCK + path.replace(" ", "%20")))
                .header("Content-Type", "application/json");
        HttpResponse<String> r = HTTP.send(body == null ? b.GET().build() : b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), path + ": " + r.body());
        return MAPPER.readTree(r.body());
    }

    private DocumentumProperties settings(Map<String, String> extra) {
        Map<String, String> c = new HashMap<>(Map.of("url", MOCK + "/dctm-rest", "repositoryName", "corp",
                "username", "dctm_crawler", "password", "secret", "stateDirectory", state.toString(), "batchSize", "7",
                "incrementalEnabled", "false", "clockSkewSeconds", "0"));
        c.putAll(extra);
        return DocumentumProperties.fromConfiguration(c);
    }

    /** A crawl's documents by id, with each content stream read into the {@code bytes} map. */
    private record Result(Map<String, RepositoryDocument> docs, Map<String, byte[]> bytes) {
        List<RepositoryDocument> deletes() {
            return docs.values().stream().filter(d -> d.action() == DocumentAction.DELETE).toList();
        }

        List<RepositoryDocument> upserts() {
            return docs.values().stream().filter(d -> d.action() == DocumentAction.UPSERT).toList();
        }

        String text(String id) {
            return bytes.containsKey(id) ? new String(bytes.get(id), StandardCharsets.ISO_8859_1) : null;
        }
    }

    private Result crawl(DocumentumProperties settings) throws IOException {
        Map<String, RepositoryDocument> docs = new LinkedHashMap<>();
        Map<String, byte[]> bytes = new HashMap<>();
        for (RepositoryDocument d : new DocumentumRepositoryConnector(settings).scan(null).collectList().block()) {
            if (docs.put(d.id(), d) != null) {
                throw new AssertionError("emitted twice: " + d.id());
            }
            if (d.contentStream() != null) {
                try (InputStream in = d.contentStream()) {
                    bytes.put(d.id(), in.readAllBytes());
                }
            }
        }
        return new Result(docs, bytes);
    }

    private static String id(JsonNode mockDoc) {
        return "documentum://corp/documents/" + mockDoc.path("chronicleId").asText();
    }

    private static List<JsonNode> mockDocs() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        admin("/mock/documents", null).forEach(out::add);
        return out;
    }

    private static boolean excluded(JsonNode mockDoc) {
        return EXCLUDED_ACLS.contains(mockDoc.path("acl").path(1).asText());
    }

    private static JsonNode current(JsonNode mockDoc) {
        for (JsonNode v : mockDoc.path("versions")) {
            if (v.path("id").asText().equals(mockDoc.path("currentId").asText())) {
                return v;
            }
        }
        throw new AssertionError("no current version: " + mockDoc);
    }

    private static String label(JsonNode version) {
        return version.path("labels").path(0).asText();
    }

    private static int number(JsonNode mockDoc) {
        Matcher m = NUMBER.matcher(mockDoc.path("objectName").asText());
        assertTrue(m.find());
        return Integer.parseInt(m.group(1));
    }

    /** The reference code a version's content carries (the mock writes it into every format). */
    private static String code(JsonNode mockDoc, JsonNode version) {
        return "DCTM-" + number(mockDoc) + "-v" + label(version);
    }

    // ------------------------------------------------------------------ full crawls

    @Test
    void fullCrawlIndexesEveryCurrentVersionWithContentMetadataAndFolders() throws Exception {
        Result r = crawl(settings(Map.of()));
        List<JsonNode> mock = mockDocs();
        Set<String> expected = mock.stream().filter(d -> !excluded(d)).map(DocumentumMockServerTest::id)
                .collect(Collectors.toSet());
        assertEquals(expected, r.docs().keySet(), "one OIS document per chronicle, required-group documents excluded");
        int checked = 0;
        for (JsonNode m : mock) {
            if (excluded(m)) {
                continue;
            }
            RepositoryDocument d = r.docs().get(id(m));
            JsonNode cur = current(m);
            assertEquals(List.of(cur.path("id").asText()), d.metadata().get("documentum.objectId"));
            assertEquals(List.of(m.path("chronicleId").asText()), d.metadata().get("documentum.chronicleId"));
            assertEquals(List.of(m.path("type").asText()), d.metadata().get("documentum.type"));
            assertTrue(d.metadata().get("documentum.versionLabels").contains("CURRENT"));
            Set<String> folders = new HashSet<>();
            m.path("folders").forEach(f -> folders.add(f.asText()));
            assertEquals(folders, new HashSet<>(d.metadata().getOrDefault("documentum.folderPath", List.of())));
            List<String> formats = new ArrayList<>();
            cur.path("formats").forEach(f -> formats.add(f.asText()));
            if (formats.isEmpty()) {
                assertFalse(r.bytes().containsKey(d.id()), "no content: metadata only " + d.id());
                continue;
            }
            assertEquals(List.of(formats.getFirst()), d.metadata().get("documentum.indexedFormat"));
            byte[] content = r.bytes().get(d.id());
            assertNotNull(content, d.id());
            switch (formats.getFirst()) {
                case "msw12" -> assertEquals("PK", new String(content, 0, 2, StandardCharsets.ISO_8859_1));
                default -> {
                    assertTrue(r.text(d.id()).contains(code(m, cur)), d.id() + " carries " + code(m, cur));
                    checked++;
                }
            }
        }
        assertTrue(checked > 10, "content of pdf and text documents checked: " + checked);
        RepositoryDocument contract = r.upserts().stream()
                .filter(d -> d.metadata().get("documentum.type").equals(List.of("oc_contract"))).findFirst().orElseThrow();
        assertNotNull(contract.metadata().get("documentum_attr_contract_number"));
        assertFalse(contract.metadata().get("documentum_attr_regions").isEmpty());
        assertTrue(contract.metadata().get("documentum_attr_signed_date").getFirst().endsWith("Z"), "dates as instants");
        assertFalse(contract.metadata().containsKey("documentum_attr_i_vstamp"), "system attributes left out");
    }

    @Test
    void preferredRenditionReplacesThePrimaryContentWhenPresent() throws Exception {
        Result r = crawl(settings(Map.of("preferRendition", "pdf")));
        int renditions = 0;
        for (JsonNode m : mockDocs()) {
            JsonNode cur = current(m);
            if (excluded(m) || cur.path("formats").size() < 2) {
                continue;
            }
            RepositoryDocument d = r.docs().get(id(m));
            assertEquals(List.of("pdf"), d.metadata().get("documentum.renditionFormat"));
            // the primary content is a DOCX: a PDF carrying this version's code can only be the rendition
            assertEquals(List.of("msw12"), d.metadata().get("documentum.contentFormat"));
            assertTrue(r.text(d.id()).startsWith("%PDF") && r.text(d.id()).contains(code(m, cur)), d.id());
            renditions++;
        }
        assertTrue(renditions > 0);
    }

    @Test
    void securityMatchesDocumentumsOwnEvaluationForEveryUser() throws Exception {
        assertSecurityMatches(crawl(settings(Map.of())), false);
    }

    @Test
    void loginPrincipalFormatMatchesToo() throws Exception {
        assertSecurityMatches(crawl(settings(Map.of("principalFormat", "login"))), true);
    }

    @Test
    void theGroundTruthComparisonCatchesALostRestriction() throws Exception {
        // the same crawl without its deny rules would let restricted users read: the comparison must notice
        Result right = crawl(settings(Map.of()));
        Map<String, RepositoryDocument> withoutDenies = new LinkedHashMap<>();
        right.docs().forEach((id, d) -> withoutDenies.put(id, new RepositoryDocument(d.id(), d.uri(), null, d.metadata(),
                d.acl(), new org.opencrawling.core.security.SecurityConfig(false, d.security().permissions().stream()
                        .filter(r -> !r.access().equals("deny")).toList()), d.lastModified(), d.action())));
        assertTrue(right.upserts().stream().anyMatch(d -> d.security().permissions().stream()
                .anyMatch(r -> r.access().equals("deny"))), "the repository has restrictions");
        assertThrows(org.opentest4j.AssertionFailedError.class,
                () -> assertSecurityMatches(new Result(withoutDenies, Map.of()), false));
    }

    /**
     * For each non-superuser, the documents the OIS rules let them read (their name or login, their groups as Documentum
     * resolves them, and dm_world) are exactly those Documentum lets them READ.
     */
    private void assertSecurityMatches(Result r, boolean login) throws Exception {
        Map<String, String> currentToId = new HashMap<>();
        for (JsonNode m : mockDocs()) {
            if (!excluded(m)) {
                currentToId.put(m.path("currentId").asText(), id(m));
            }
        }
        int users = 0;
        Map<String, Long> logins = new HashMap<>();
        admin("/mock/principals", null).forEach(p -> logins.merge(p.path("login").asText(), 1L, Long::sum));
        for (JsonNode p : admin("/mock/principals", null)) {
            if (p.path("superuser").asBoolean()) {
                continue;
            }
            JsonNode access = admin("/mock/access?user=" + p.path("userName").asText(), null);
            Set<String> principals = new HashSet<>();
            principals.add((login ? p.path("login").asText() : p.path("userName").asText()).toLowerCase(Locale.ROOT));
            p.path("groups").forEach(g -> principals.add(g.asText().toLowerCase(Locale.ROOT)));
            principals.add("dm_world");
            Set<String> expected = new HashSet<>();
            access.path("readable").fields().forEachRemaining(e -> {
                if (e.getValue().asBoolean() && currentToId.containsKey(e.getKey())) {
                    expected.add(currentToId.get(e.getKey()));
                }
            });
            Set<String> actual = new HashSet<>();
            for (RepositoryDocument d : r.upserts()) {
                if (readable(d.security().permissions(), principals)) {
                    actual.add(d.id());
                }
            }
            if (login && logins.get(p.path("login").asText()) > 1) {
                // a login several login domains share is never an identity: such a user may read less, never more
                assertTrue(expected.containsAll(actual), "documents readable by " + p.path("userName").asText()
                        + " beyond Documentum's: " + actual.stream().filter(a -> !expected.contains(a)).toList());
            } else {
                assertEquals(expected, actual, "documents readable by " + p.path("userName").asText());
            }
            users++;
        }
        assertTrue(users >= 4);
    }

    private static boolean readable(List<PermissionRule> rules, Set<String> principals) {
        boolean allowed = false;
        for (PermissionRule rule : rules) {
            boolean matches = principals.contains(rule.identity()) || "public".equals(rule.identity())
                    || "*".equals(rule.identity());
            if (matches && rule.access().equals("deny") && !"public".equals(rule.identity())) {
                return false;
            }
            allowed |= matches && rule.access().equals("read");
        }
        return allowed;
    }

    @Test
    void folderModeCrawlsTheTreeOrOneFolder() throws Exception {
        Result tree = crawl(settings(Map.of("crawlMode", "folder", "rootCabinetPath", "/Regulatory")));
        Result one = crawl(settings(Map.of("crawlMode", "folder", "rootCabinetPath", "/Regulatory/Submissions",
                "includeSubfolders", "false")));
        Set<String> inTree = new HashSet<>();
        Set<String> inOne = new HashSet<>();
        for (JsonNode m : mockDocs()) {
            if (excluded(m)) {
                continue;
            }
            for (JsonNode f : m.path("folders")) {
                if (f.asText().startsWith("/Regulatory")) {
                    inTree.add(id(m));
                }
                if (f.asText().equals("/Regulatory/Submissions")) {
                    inOne.add(id(m));
                }
            }
        }
        assertEquals(inTree, tree.docs().keySet());
        assertEquals(inOne, one.docs().keySet());
        assertTrue(inOne.size() < inTree.size());
        Exception e = assertThrows(Exception.class,
                () -> crawl(settings(Map.of("crawlMode", "folder", "rootCabinetPath", "/Nowhere"))));
        assertTrue(e.getMessage().contains("/Nowhere"), e.getMessage());
    }

    @Test
    void latestMajorModeIndexesTheHighestMajorVersion() throws Exception {
        Result r = crawl(settings(Map.of("versionMode", "latest_major")));
        for (JsonNode m : mockDocs()) {
            if (excluded(m)) {
                continue;
            }
            JsonNode major = null;
            for (JsonNode v : m.path("versions")) {
                if (label(v).endsWith(".0")) {
                    major = v; // versions are in creation order
                }
            }
            RepositoryDocument d = r.docs().get(id(m));
            assertEquals(List.of(major.path("id").asText()), d.metadata().get("documentum.objectId"), id(m));
        }
        assertEquals(mockDocs().stream().filter(m -> !excluded(m)).count(), r.docs().size());
    }

    @Test
    void allVersionsModeIndexesEachVersion() throws Exception {
        Result r = crawl(settings(Map.of("versionMode", "all_versions")));
        Set<String> expected = new HashSet<>();
        for (JsonNode m : mockDocs()) {
            if (!excluded(m)) {
                m.path("versions").forEach(v -> expected.add(id(m) + "/versions/" + v.path("id").asText()));
            }
        }
        assertEquals(expected, r.docs().keySet());
    }

    // ------------------------------------------------------------------ incremental crawls

    private Map<String, String> incremental(Map<String, String> extra) {
        Map<String, String> c = new HashMap<>(Map.of("incrementalEnabled", "true"));
        c.putAll(extra);
        return c;
    }

    @Test
    void incrementalCrawlSkipsUnchangedAndPicksUpNewVersions() throws Exception {
        DocumentumProperties s = settings(incremental(Map.of()));
        int first = crawl(s).upserts().size();
        assertTrue(first > 30);
        Result idle = crawl(s);
        assertTrue(idle.docs().isEmpty(), "nothing changed: " + idle.docs().keySet());
        JsonNode target = mockDocs().stream().filter(m -> !excluded(m) && current(m).path("formats").size() > 0
                && current(m).path("formats").path(0).asText().equals("crtext")).findFirst().orElseThrow();
        JsonNode after = admin("/mock/documents/" + target.path("chronicleId").asText() + "/checkin",
                "{\"major\":true,\"text\":\"Second edition.\"}");
        Result next = crawl(s);
        assertEquals(Set.of(id(target)), next.docs().keySet(), "only the new version, under the same id");
        assertTrue(next.text(id(target)).contains(code(after, current(after))));
        assertTrue(next.text(id(target)).contains("Second edition."));
    }

    @Test
    void newDocumentsAreFoundByTheNextIncrementalCrawl() throws Exception {
        DocumentumProperties s = settings(incremental(Map.of()));
        crawl(s);
        JsonNode created = admin("/mock/documents", "{\"type\":\"oc_contract\",\"folders\":[\"/Corporate/Contracts\"],"
                + "\"pattern\":1,\"format\":\"crtext\",\"text\":\"Incremental NDA, reference INCR-DCTM-0001.\"}");
        Result r = crawl(s);
        assertEquals(Set.of(id(created)), r.docs().keySet());
        assertTrue(r.text(id(created)).contains("INCR-DCTM-0001"));
        assertEquals(List.of(String.format("C-%05d", number(created))), r.docs().get(id(created)).metadata().get("documentum_attr_contract_number"));
    }

    @Test
    void editedPermissionSetIsDetectedWithoutDocumentChanges() throws Exception {
        DocumentumProperties s = settings(incremental(Map.of()));
        crawl(s);
        Set<String> affected = mockDocs().stream().filter(m -> m.path("acl").path(1).asText().equals("oc_public_read"))
                .map(DocumentumMockServerTest::id).collect(Collectors.toSet());
        admin("/mock/acls/dmadmin/oc_public_read", "{\"entries\":[{\"name\":\"dm_world\",\"permit\":1},"
                + "{\"name\":\"dm_owner\",\"permit\":7},{\"name\":\"legal\",\"permit\":3,\"isGroup\":true}]}");
        Result r = crawl(s);
        assertEquals(affected, r.docs().keySet());
        for (RepositoryDocument d : r.upserts()) {
            assertTrue(d.metadata().get("documentum_identity_groups").contains("legal"));
            assertFalse(d.metadata().get("documentum_identity_groups").contains("dm_world"));
        }
        assertTrue(crawl(s).docs().isEmpty());
    }

    @Test
    void requiredGroupAddedLaterRemovesTheDocument() throws Exception {
        DocumentumProperties s = settings(incremental(Map.of()));
        crawl(s);
        JsonNode m = mockDocs().stream().filter(d -> d.path("acl").path(1).asText().equals("oc_finance_read")).findFirst()
                .orElseThrow();
        admin("/mock/documents/" + m.path("chronicleId").asText() + "/acl",
                "{\"domain\":\"dmadmin\",\"name\":\"oc_legal_required\"}");
        Result r = crawl(s);
        assertEquals(Set.of(id(m)), r.docs().keySet());
        assertEquals(DocumentAction.DELETE, r.docs().get(id(m)).action());
    }

    @Test
    void reconcileTombstonesDestroyedDocumentsAndFallsBackToThePreviousVersion() throws Exception {
        DocumentumProperties s = settings(incremental(Map.of()));
        crawl(s);
        List<JsonNode> mock = mockDocs().stream().filter(m -> !excluded(m)).toList();
        JsonNode gone = mock.getFirst();
        JsonNode versioned = mock.stream().filter(m -> m.path("versions").size() > 1).findFirst().orElseThrow();
        admin("/mock/documents/" + gone.path("chronicleId").asText() + "/destroy", "{}");
        admin("/mock/documents/" + versioned.path("chronicleId").asText() + "/destroy",
                "{\"currentVersionOnly\":true}");
        Result r = crawl(s);
        assertEquals(Set.of(id(gone), id(versioned)), r.docs().keySet());
        assertEquals(DocumentAction.DELETE, r.docs().get(id(gone)).action());
        RepositoryDocument back = r.docs().get(id(versioned));
        assertEquals(DocumentAction.UPSERT, back.action());
        JsonNode versions = versioned.path("versions");
        assertEquals(List.of(versions.get(versions.size() - 2).path("id").asText()), back.metadata().get("documentum.objectId"));
    }

    @Test
    void auditTrackingReadsDestroyEvents() throws Exception {
        DocumentumProperties s = settings(incremental(Map.of("deletionTracking", "audit")));
        crawl(s);
        JsonNode gone = mockDocs().stream().filter(m -> !excluded(m)).toList().get(3);
        admin("/mock/documents/" + gone.path("chronicleId").asText() + "/destroy", "{}");
        Result r = crawl(s);
        assertEquals(Set.of(id(gone)), r.docs().keySet());
        assertEquals(DocumentAction.DELETE, r.docs().get(id(gone)).action());
        assertTrue(crawl(s).docs().isEmpty(), "events are not replayed");
    }

    @Test
    void auditTrackingNeedsAnAccountThatCanReadTheAuditTrail() {
        Exception e = assertThrows(Exception.class, () -> crawl(settings(incremental(Map.of("deletionTracking", "audit",
                "username", "jdoe")))));
        assertTrue(e.getMessage().contains("View Audit"), e.getMessage());
    }

    @Test
    void auditTrackingAlsoFallsBackToThePreviousVersion() throws Exception {
        DocumentumProperties s = settings(incremental(Map.of("deletionTracking", "audit")));
        crawl(s);
        JsonNode versioned = mockDocs().stream().filter(m -> !excluded(m) && m.path("versions").size() > 1).toList().get(1);
        admin("/mock/documents/" + versioned.path("chronicleId").asText() + "/destroy", "{\"currentVersionOnly\":true}");
        Result r = crawl(s);
        JsonNode versions = versioned.path("versions");
        assertEquals(List.of(versions.get(versions.size() - 2).path("id").asText()),
                r.docs().get(id(versioned)).metadata().get("documentum.objectId"));
    }

    @Test
    void movedOutOfTheFolderScopeIsTombstonedByReconcile() throws Exception {
        DocumentumProperties s = settings(incremental(Map.of("crawlMode", "folder", "rootCabinetPath",
                "/Corporate/Invoices")));
        Result first = crawl(s);
        JsonNode m = mockDocs().stream().filter(d -> !excluded(d) && d.path("folders").size() == 1
                && d.path("folders").path(0).asText().equals("/Corporate/Invoices")).findFirst().orElseThrow();
        assertTrue(first.docs().containsKey(id(m)));
        admin("/mock/documents/" + m.path("chronicleId").asText() + "/unlink", "{\"folder\":\"/Corporate/Invoices\"}");
        admin("/mock/documents/" + m.path("chronicleId").asText() + "/link", "{\"folder\":\"/Regulatory\"}");
        Result r = crawl(s);
        assertEquals(DocumentAction.DELETE, r.docs().get(id(m)).action());
    }

    // ------------------------------------------------------------------ protocol details

    @Test
    void keysetPagingNeitherMissesNorRepeatsObjectsSharingASecond() throws Exception {
        for (String size : List.of("1", "2", "3", "7", "1000")) {
            Result r = crawl(settings(Map.of("batchSize", size, "includeContentStream", "false")));
            assertEquals(mockDocs().stream().filter(m -> !excluded(m)).count(), r.docs().size(), "batch size " + size);
        }
    }

    @Test
    void aWrongServerTimeZoneFailsLoudlyInsteadOfLoopingOrSkipping() throws Exception {
        DocumentumProperties s = settings(Map.of());
        java.time.ZoneOffset actual = DocumentumRepositoryConnector.connectionInfo(
                DocumentumRepositoryConnector.newClient(s), s).serverOffset();
        String wrong = java.time.ZoneOffset.ofTotalSeconds(actual.getTotalSeconds() + 3 * 3600).getId();
        Exception e = assertThrows(Exception.class, () -> crawl(settings(Map.of("serverTimeZone", wrong,
                "includeContentStream", "false"))));
        assertTrue(e.getMessage().contains("server-time-zone") || e.getMessage().contains("progress"), e.getMessage());
    }

    @Test
    void transientFailuresAreRetried() throws Exception {
        admin("/mock/faults", "{\"op\":\"dql\",\"status\":503,\"count\":2,\"retryAfter\":0}");
        admin("/mock/faults", "{\"op\":\"download\",\"status\":502,\"count\":1}");
        Result r = crawl(settings(Map.of()));
        assertEquals(mockDocs().stream().filter(m -> !excluded(m)).count(), r.docs().size());
    }

    @Test
    void contentOverTheLimitIsIndexedByMetadataOnly() throws Exception {
        Result r = crawl(settings(Map.of("maxContentSizeBytes", "200")));
        assertTrue(r.bytes().values().stream().allMatch(b -> b.length <= 200));
        assertTrue(r.docs().size() > 30);
    }

    @Test
    void oauth2ClientCredentialsAuthenticate() throws Exception {
        Result r = crawl(settings(Map.of("authType", "oauth2", "oauth2TokenUrl", MOCK + "/oauth/token",
                "oauth2ClientId", "opencrawling", "oauth2ClientSecret", "client-secret", "includeContentStream", "false")));
        assertTrue(r.docs().size() > 30);
    }

    @Test
    void wrongCredentialsAndKerberosAreRefused() {
        Exception bad = assertThrows(Exception.class, () -> crawl(settings(Map.of("password", "nope"))));
        assertTrue(bad.getMessage().contains("401"), bad.getMessage());
        Exception kerberos = assertThrows(Exception.class, () -> crawl(settings(Map.of("authType", "kerberos"))));
        assertTrue(kerberos.getMessage().contains("kerberos"), kerberos.getMessage());
    }

    @Test
    void connectionInfoReportsTheRepositoryAndTheServerOffset() throws Exception {
        DocumentumProperties s = settings(Map.of());
        DocumentumRepositoryConnector.Info info = DocumentumRepositoryConnector.connectionInfo(
                DocumentumRepositoryConnector.newClient(s), s);
        assertEquals("corp", info.repository().path("name").asText());
        assertNotNull(info.serverOffset());
        assertNull(s.serverTimeZone(), "automatic by default");
    }

    // ------------------------------------------------------------------ what a real server may do differently

    @Test
    void contentTheAccountMayNotReadIsMetadataOnlyAndIncrementalRunsStillAdvance() throws Exception {
        DocumentumProperties s = settings(Map.of("username", "jdoe", "incrementalEnabled", "true"));
        Result r = crawl(s);
        // everyone may BROWSE oc_browse_only documents: jdoe lists them, but their content answers 403
        List<JsonNode> browseOnly = mockDocs().stream()
                .filter(m -> m.path("acl").path(1).asText().equals("oc_browse_only") && !current(m).path("formats").isEmpty())
                .toList();
        assertFalse(browseOnly.isEmpty());
        for (JsonNode m : browseOnly) {
            assertTrue(r.docs().containsKey(id(m)), "listed: " + id(m));
            assertNull(r.text(id(m)), "no content: " + id(m));
        }
        assertNotNull(DocumentumCrawlState.load(DocumentumCrawlState.file(s)).watermark(),
                "unreadable content is a property of the document, not a failed run that holds the watermark back");
    }

    @Test
    void aSmallerServerPageMaximumIsReportedAtConnectAndRespected() throws Exception {
        admin("/mock/config", "{\"maxPageSize\":5,\"pageOversize\":\"error\"}");
        DocumentumProperties big = settings(Map.of("batchSize", "7"));
        Exception e = assertThrows(Exception.class, () -> DocumentumRepositoryConnector.connectionInfo(
                DocumentumRepositoryConnector.newClient(big), big));
        assertTrue(e.getMessage().contains("batch-size"), e.getMessage());
        Result r = crawl(settings(Map.of("batchSize", "5", "principalFormat", "login")));
        assertEquals(mockDocs().stream().filter(m -> !excluded(m)).count(), r.docs().size());
    }

    @Test
    void contentIsDownloadedBehindAProxyAndThroughSameServerRedirects() throws Exception {
        Result direct = crawl(settings(Map.of()));
        admin("/mock/config", "{\"publicUrl\":\"https://dctm-internal.invalid:8443/dctm-rest\",\"contentRedirect\":true}");
        Result proxied = crawl(settings(Map.of()));
        assertFalse(direct.bytes().isEmpty());
        assertEquals(direct.bytes().keySet(), proxied.bytes().keySet());
        assertTrue(admin("/mock/stats", null).path("downloads").asInt() >= direct.bytes().size() * 2);
    }

    @Test
    void aLoginNameSeveralDomainsShareIsNeverAnIdentity() throws Exception {
        Result r = crawl(settings(Map.of("principalFormat", "login", "includeContentStream", "false")));
        for (RepositoryDocument d : r.upserts()) {
            assertFalse(d.security().permissions().stream().anyMatch(rule -> rule.identity().equals("klee")),
                    "Kim Lee (CORP) and Kevin Lee (EXT) are both klee: " + d.id());
        }
        Result byName = crawl(settings(Map.of("includeContentStream", "false")));
        assertTrue(byName.upserts().stream().anyMatch(d -> d.security().permissions().stream()
                .anyMatch(rule -> rule.identity().equals("kevin lee"))), "by user name both stay distinct");
    }

    @Test
    void aFolderMovedIntoTheScopeBringsItsDocumentsInTheNextIncrementalRun() throws Exception {
        DocumentumProperties s = settings(Map.of("crawlMode", "folder", "rootCabinetPath", "/Corporate",
                "incrementalEnabled", "true", "includeContentStream", "false"));
        Result first = crawl(s);
        List<JsonNode> archived = mockDocs().stream().filter(m -> !excluded(m)
                && m.path("folders").toString().contains("/Regulatory/Submissions/Archive")
                && !first.docs().containsKey(id(m))).toList();
        assertFalse(archived.isEmpty());
        admin("/mock/folders/move", "{\"folder\":\"/Regulatory/Submissions/Archive\",\"to\":\"/Corporate\"}");
        Result second = crawl(s);
        for (JsonNode m : archived) {
            assertTrue(second.docs().containsKey(id(m)), "moved in with its folder, unmodified: " + id(m));
        }
    }

    @Test
    void aServerOffsetChangeBetweenRunsMissesNothing() throws Exception {
        // no deletion tracking: reconcile would find the new version by its object id, hiding a watermark that skips it
        DocumentumProperties s = settings(Map.of("incrementalEnabled", "true", "includeContentStream", "false",
                "deletionTracking", "none"));
        crawl(s);
        Path file = DocumentumCrawlState.file(s);
        DocumentumCrawlState.State saved = DocumentumCrawlState.load(file);
        assertNotNull(saved.watermarkOffset(), "server-time-zone auto records the offset of the watermark");
        // as if daylight saving had moved the server's clock since: the last run was an hour further east
        String then = java.time.ZoneOffset.ofTotalSeconds(java.time.ZoneOffset.of(saved.watermarkOffset())
                .getTotalSeconds() + 3600).getId();
        DocumentumCrawlState.save(file, new DocumentumCrawlState.State(saved.watermark(), saved.eventWatermark(),
                saved.objects(), saved.acls(), then));
        JsonNode target = mockDocs().stream().filter(m -> !excluded(m)).findFirst().orElseThrow();
        admin("/mock/documents/" + target.path("chronicleId").asText() + "/checkin", "{\"major\":false}");
        Result r = crawl(s);
        assertTrue(r.docs().containsKey(id(target)), "saved minutes after the watermark, read an hour off without care");
    }

    @Test
    void aRegionTimeZoneIsAcceptedAndAnInvalidOneRefused() throws Exception {
        admin("/mock/config", "{\"timeZone\":\"Europe/Lisbon\"}");
        assertTrue(crawl(settings(Map.of("serverTimeZone", "Europe/Lisbon", "includeContentStream", "false")))
                .docs().size() > 30, "dates of winter and summer are written with their own offsets");
        Exception e = assertThrows(Exception.class, () -> settings(Map.of("serverTimeZone", "CEST")));
        assertTrue(e.getMessage().contains("server-time-zone"), e.getMessage());
    }
}
