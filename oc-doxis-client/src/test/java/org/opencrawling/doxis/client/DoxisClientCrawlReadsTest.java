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
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The read methods used by the Doxis repository connector: paged search, closing results, content download.
 */
class DoxisClientCrawlReadsTest {

    private static final String BASE = "/restws/publicws/rest/api/v1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;
    private DoxisClient client;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DoxisClient(server.url(BASE).toString(), "DX4", "Supervisor", "secret", "admins",
                "OpenCrawling-Test", Duration.ofSeconds(5), 2, Duration.ofMillis(1), HttpClient.newHttpClient(), objectMapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void enqueueJson(int status, String body) {
        server.enqueue(new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body));
    }

    @Test
    void searchDocumentsSendsFilterAndPageSizeAndParsesThePage() throws Exception {
        enqueueJson(200, Fixtures.text("login.json"));
        enqueueJson(200, """
                {"searchId":"s-1","totalHitCount":3,"searchResultRestrictionMode":"NOT_RESTRICTED","maxSearchResults":1000,
                 "searchHits":[{"uuid":"d1"},{"uuid":"d2"}]}""");

        DoxisClient.SearchPage page = client.searchDocuments("SELECT * FROM DB1", "ANY_OBJECTS", 2);

        assertEquals("s-1", page.searchId());
        assertEquals(3, page.totalHitCount());
        assertEquals("NOT_RESTRICTED", page.restrictionMode());
        assertEquals(1000, page.maxSearchResults());
        assertEquals(2, page.hits().size());
        server.takeRequest(); // login
        RecordedRequest search = server.takeRequest();
        assertEquals(BASE + "/documents/search", search.getPath());
        JsonNode body = objectMapper.readTree(search.getBody().readUtf8());
        assertEquals("SELECT * FROM DB1", body.path("cqlStatement").asText());
        assertEquals(2, body.path("fetchResultLimitation").asInt());
        assertEquals("ANY_OBJECTS", body.path("logicallyDeletedFilter").asText());
        assertTrue(body.path("currentVersionOnly").asBoolean());
    }

    @Test
    void nullSearchIdMeansNoOpenResultSet() throws Exception {
        enqueueJson(200, Fixtures.text("login.json"));
        enqueueJson(200, """
                {"searchId":null,"totalHitCount":0,"searchResultRestrictionMode":"RESTRICTED_BY_SERVER","searchHits":[]}""");

        DoxisClient.SearchPage page = client.searchDocuments("SELECT * FROM DB1", "NON_DELETED_OBJECTS", 50);

        assertNull(page.searchId());
        assertTrue(page.hits().isEmpty());
        client.closeSearch(page.searchId()); // no request for a null id
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void nextSearchResultsPagesWithOffsetAndLimitAndCloseDeletesTheResult() throws Exception {
        enqueueJson(200, Fixtures.text("login.json"));
        enqueueJson(200, """
                {"searchId":"s-1","totalHitCount":3,"searchHits":[{"uuid":"d3"}]}""");
        server.enqueue(new MockResponse().setResponseCode(204));

        DoxisClient.SearchPage page = client.nextSearchResults("s-1", 2, 2);
        client.closeSearch("s-1");

        assertEquals("d3", page.hits().getFirst().path("uuid").asText());
        server.takeRequest();
        RecordedRequest next = server.takeRequest();
        assertEquals("GET", next.getMethod());
        assertEquals(BASE + "/documents/searchResults/s-1?offset=2&limit=2", next.getPath());
        RecordedRequest close = server.takeRequest();
        assertEquals("DELETE", close.getMethod());
        assertEquals(BASE + "/documents/searchResults/s-1", close.getPath());
    }

    @Test
    void closeSearchSwallowsErrors() throws Exception {
        enqueueJson(200, Fixtures.text("login.json"));
        enqueueJson(404, "{\"errorCode\":\"X\",\"message\":\"gone\"}");

        assertDoesNotThrow(() -> client.closeSearch("s-1"));
    }

    @Test
    void downloadContentObjectStreamsToFileAndReloginsOnce() throws Exception {
        enqueueJson(200, Fixtures.text("login.json"));
        server.enqueue(new MockResponse().setResponseCode(401).setBody("expired"));
        enqueueJson(200, Fixtures.text("login.json"));
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/pdf").setBody("%PDF-1.7 hello"));
        Path target = tmp.resolve("content.bin");

        long length = client.downloadContentObject("DB1", "d1", "1", "r1", "c1", target);

        assertEquals(14, length);
        assertEquals("%PDF-1.7 hello", Files.readString(target, StandardCharsets.UTF_8));
        server.takeRequest();
        RecordedRequest first = server.takeRequest();
        assertEquals(BASE + "/dmsRepositories/DB1/documents/d1/versions/1/representations/r1/contentObjects/c1", first.getPath());
        assertEquals("*/*", first.getHeader("Accept"));
        assertEquals(4, server.getRequestCount());
    }

    @Test
    void downloadContentObjectMapsErrorsAndLeavesNoFile() throws Exception {
        enqueueJson(200, Fixtures.text("login.json"));
        enqueueJson(500, "{\"errorCode\":\"SEDNA0104\",\"message\":\"content link\"}");
        Path target = tmp.resolve("content.bin");

        DoxisApiException e = assertThrows(DoxisApiException.class,
                () -> client.downloadContentObject("DB1", "d1", "1", "r1", "c1", target));

        assertEquals(500, e.getStatusCode());
        assertEquals("SEDNA0104", e.getErrorCode());
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(tmp.resolve("content.bin.part")));
    }

    @Test
    void downloadContentObjectRetriesThrottlingHonouringRetryAfter() throws Exception {
        enqueueJson(200, Fixtures.text("login.json"));
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "0"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        Path target = tmp.resolve("content.bin");

        assertEquals(2, client.downloadContentObject("DB1", "d1", "1", "r1", "c1", target));
        assertEquals(3, server.getRequestCount());
        assertFalse(Files.exists(tmp.resolve("content.bin.part")));
    }

    @Test
    void aRetriedDownloadNeverKeepsBytesOfAnEarlierAttempt() throws Exception {
        enqueueJson(200, Fixtures.text("login.json"));
        server.enqueue(new MockResponse().setResponseCode(503).setBody("a long service-unavailable error page body"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        Path target = tmp.resolve("content.bin");
        Files.writeString(target, "stale content from an older crawl");

        assertEquals(2, client.downloadContentObject("DB1", "d1", "1", "r1", "c1", target));
        assertEquals("ok", Files.readString(target));
        assertFalse(Files.exists(tmp.resolve("content.bin.part")));
    }

    @Test
    void aFailedDownloadLeavesAnExistingTargetUntouched() throws Exception {
        enqueueJson(200, Fixtures.text("login.json"));
        enqueueJson(403, "{\"errorCode\":\"SECU0015I\",\"message\":\"no permission\"}");
        Path target = tmp.resolve("content.bin");
        Files.writeString(target, "previous");

        assertThrows(DoxisApiException.class, () -> client.downloadContentObject("DB1", "d1", "1", "r1", "c1", target));
        assertEquals("previous", Files.readString(target));
        assertFalse(Files.exists(tmp.resolve("content.bin.part")));
    }
}
