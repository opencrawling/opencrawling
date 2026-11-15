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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole-connector scenarios against the MFWS mock ({@code mfiles-mock/mfws_mock.py}) over real HTTP: a full crawl
 * reconciled against the mock's own counts, an incremental run after the vault changed, the VAF change feed, injected
 * server errors, expired tokens, and the header and bearer logins. Runs only when {@code MFILES_MOCK_URL} points at a
 * running mock (e.g. {@code http://localhost:18080}); {@code scripts/test-mfiles-repository-connector.sh} starts one.
 * Every test resets the mock's vault first.
 */
@EnabledIfEnvironmentVariable(named = "MFILES_MOCK_URL", matches = "https?://.+")
class MFilesMockServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VAULT = "{C0B3B52E-6512-4BD8-8F2B-2A8E9A349201}";
    private static final String BASE = System.getenv("MFILES_MOCK_URL") == null ? "" : System.getenv("MFILES_MOCK_URL").replaceAll("/+$", "");
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path state;

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

    private MFilesRepositoryConnector connector(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of("url", BASE + "/REST", "vaultGuid", VAULT, "username", "crawler",
                "password", "secret", "objectType", "-1", "batchSize", "100", "stateDirectory", state.toString()));
        config.putAll(extra);
        return new MFilesRepositoryConnector(MFilesProperties.fromConfiguration(config));
    }

    /** Scans and drains every content stream (deleting the temp files), returning the documents. */
    private static List<RepositoryDocument> scan(MFilesRepositoryConnector connector) throws IOException {
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

    private static String id(int type, int objectId) {
        return "mfiles://" + VAULT.substring(1, VAULT.length() - 1) + "/objects/" + type + "/" + objectId;
    }

    @Test
    void fullCrawlMatchesTheVault() throws Exception {
        JsonNode stats = admin("/mock/stats", null);
        List<RepositoryDocument> documents = scan(connector(Map.of()));
        long customers = stats.path("liveObjects").asLong() - documents.stream()
                .filter(d -> d.action() == DocumentAction.UPSERT && "0".equals(d.metadata().get("mfiles.objType").getFirst()))
                .map(d -> d.metadata().get("mfiles.objId").getFirst()).distinct().count();
        assertEquals(stats.path("liveFiles").asLong() + customers, ids(documents, DocumentAction.UPSERT).size(),
                "one document per live file plus one per object without files");
        assertEquals(stats.path("deletedObjects").asLong(), ids(documents, DocumentAction.DELETE).size());
        JsonNode after = admin("/mock/stats", null);
        assertEquals(0, after.path("activeSessions").asInt(), "the scan ends its session");
    }

    @Test
    void incrementalRunPicksUpEditsDeletesAndNewObjects() throws Exception {
        Map<String, String> config = Map.of("incrementalEnabled", "true", "clockSkewSeconds", "0");
        List<RepositoryDocument> first = scan(connector(config));
        assertFalse(first.isEmpty());

        JsonNode multi = MAPPER.readTree(MockMFilesHttp.get(http, BASE + "/mock/objects/0/1513")).path("objectVersion");
        int removed = multi.path("Files").get(0).path("ID").asInt();
        admin("/mock/objects/0/1513/modify", "{\"removeFileIds\":[" + removed + "],\"appendText\":\"Amended by the incremental test.\"}");
        admin("/mock/objects/0/1001/delete", "{}");
        JsonNode created = admin("/mock/objects", "{\"classId\":14,\"title\":\"Incremental NDA\",\"text\":\"A brand new NDA.\"}");
        int newId = created.path("ObjVer").path("ID").asInt();
        Thread.sleep(1_100); // the next scan's watermark (whole seconds) must lie after these changes

        List<RepositoryDocument> second = scan(connector(config));
        Set<String> upserts = ids(second, DocumentAction.UPSERT);
        Set<String> deletes = ids(second, DocumentAction.DELETE);
        String agreement = id(0, 1513);
        assertEquals(Set.of(agreement + "/files/" + multi.path("Files").get(1).path("ID").asInt(),
                agreement + "/files/" + multi.path("Files").get(2).path("ID").asInt(), id(0, newId)), upserts,
                "only the changed and the new objects are crawled again");
        assertEquals(Set.of(agreement + "/files/" + removed, id(0, 1001)), deletes,
                "the removed file and the deleted object are purged");

        assertTrue(scan(connector(config)).isEmpty(), "nothing changed since: nothing emitted");
    }

    @Test
    void vafModeDeliversEffectivePermissions() throws Exception {
        List<RepositoryDocument> documents = scan(connector(Map.of("crawlMode", "vaf", "vafBasePath", "/vaf/indexer",
                "incrementalEnabled", "true")));
        JsonNode stats = admin("/mock/stats", null);
        Set<String> deleted = ids(documents, DocumentAction.DELETE);
        Set<String> live = ids(documents, DocumentAction.UPSERT);
        live.removeAll(deleted); // a feed page boundary can deliver an object's update and its later delete separately
        assertEquals(stats.path("deletedObjects").asLong(), deleted.size());
        assertEquals(stats.path("liveObjects").asLong(), live.size());
        // pattern 3: finance plus the pseudo-user "Project manager", which only the vault application can resolve
        RepositoryDocument pseudo = documents.stream().filter(d -> d.id().equals(id(0, 1003))).findFirst().orElseThrow();
        JsonNode expected = MAPPER.readTree(MockMFilesHttp.get(http, BASE + "/mock/objects/0/1003"))
                .path("effectiveTokens").path(0);
        Set<String> readers = pseudo.security().permissions().stream().map(r -> r.identity()).collect(Collectors.toSet());
        expected.forEach(token -> assertTrue(readers.contains(token.asText()), token + " missing from " + readers));
        assertEquals(2, readers.size(), "finance and the resolved project manager");

        admin("/mock/objects/0/1005/delete", "{}");
        List<RepositoryDocument> next = scan(connector(Map.of("crawlMode", "vaf", "vafBasePath", "/vaf/indexer",
                "incrementalEnabled", "true")));
        assertEquals(Set.of(id(0, 1005)), ids(next, DocumentAction.DELETE));
        assertTrue(ids(next, DocumentAction.UPSERT).isEmpty(), "the cursor resumes after the first run");
    }

    @Test
    void serverErrorsAreRetriedAndARejectedTokenIsRenewed() throws Exception {
        admin("/mock/faults", "{\"path\":\"/REST/objects?\",\"status\":503,\"count\":2}");
        admin("/mock/faults", "{\"path\":\"/REST/objects/0/\",\"status\":403,\"count\":1,\"expireTokens\":true}");
        List<RepositoryDocument> documents = scan(connector(Map.of("maxDocuments", "40", "parallelism", "1")));
        assertTrue(documents.size() >= 40, "the crawl survived the faults: " + documents.size());
        JsonNode requests = admin("/mock/stats", null).path("requests");
        assertEquals(2, requests.path("POST /REST/server/authenticationtokens").asInt(), "one login, one renewal after the 403");
        assertEquals(0, admin("/mock/stats", null).path("activeSessions").asInt());
    }

    @Test
    void headerAndBearerLoginsCrawl() throws Exception {
        assertFalse(scan(connector(Map.of("authType", "basic", "maxDocuments", "5"))).isEmpty());
        String bearer = System.getenv().getOrDefault("MFILES_MOCK_BEARER", "tok-oauth");
        assertFalse(scan(connector(Map.of("authType", "oauth2", "oauth2AccessToken", bearer, "maxDocuments", "5"))).isEmpty());
    }

    @Test
    void wrongPasswordFailsWithoutCrawling() {
        Throwable error = assertThrows(RuntimeException.class,
                () -> connector(Map.of("password", "wrong")).scan("default").collectList().block());
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        assertTrue(cause.getMessage().contains("HTTP 403"), cause.getMessage());
    }

    /** Minimal GET helper for the mock's admin endpoints. */
    static final class MockMFilesHttp {
        static String get(HttpClient http, String url) throws IOException, InterruptedException {
            return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        }
    }
}
