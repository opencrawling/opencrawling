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
package org.opencrawling.doxis.output.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DoxisClientTest {

    private static final String API_KEY = "test-api-key";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;
    private DoxisClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DoxisClient(server.url("/").toString(), API_KEY, Duration.ofSeconds(5), 2,
                Duration.ofMillis(1), HttpClient.newHttpClient(), objectMapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        server.shutdown();
    }

    static String fixture(String name) throws IOException {
        try (InputStream is = DoxisClientTest.class.getResourceAsStream("/doxis-mock-responses/" + name)) {
            assertNotNull(is, "missing fixture " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void enqueue(int status, String fixture) throws IOException {
        server.enqueue(new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(fixture(fixture)));
    }

    @Test
    void authInfoSendsApiKeyHeader() throws Exception {
        enqueue(200, "auth-info.json");

        JsonNode info = client.authInfo();

        assertEquals("Contoso", info.path("organization").path("name").asText());
        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/api/services/auth/v1/info", req.getPath());
        assertEquals(API_KEY, req.getHeader("x-api-key"));
    }

    @Test
    void unauthorizedSurfacesPlatformErrorEnvelope() throws Exception {
        enqueue(401, "error-unauthorized.json");

        DoxisApiException e = assertThrows(DoxisApiException.class, () -> client.authInfo());

        assertEquals(401, e.getStatusCode());
        assertEquals(10003, e.getErrorCode());
        assertEquals("1235", e.getRequestId());
        assertTrue(e.getMessage().contains("API key invalid"));
    }

    @Test
    void listDatasetsFollowsCursorPagination() throws Exception {
        enqueue(200, "datasets-page-1.json");
        enqueue(200, "datasets-page-2.json");

        List<JsonNode> datasets = client.listDatasets();

        assertEquals(List.of("ds-other", "ds-1"), datasets.stream().map(d -> d.path("id").asText()).toList());
        assertEquals("/api/services/datasets/v3/datasets?limit=100", server.takeRequest().getPath());
        assertEquals("/api/services/datasets/v3/datasets?limit=100&cursor=cur-2", server.takeRequest().getPath());
    }

    @Test
    void createDatasetPostsTypedColumns() throws Exception {
        enqueue(201, "dataset-created.json");

        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("external_id", "text");
        columns.put("document", "document");
        String id = client.createDataset("OpenCrawling Ingestion", columns);

        assertEquals("ds-new", id);
        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/api/services/datasets/v3/datasets", req.getPath());
        assertEquals("application/json", req.getHeader("Content-Type"));
        JsonNode body = objectMapper.readTree(req.getBody().readUtf8());
        assertEquals("OpenCrawling Ingestion", body.path("name").asText());
        assertEquals("external_id", body.path("columns").get(0).path("slug").asText());
        assertEquals("text", body.path("columns").get(0).path("data_type").asText());
        assertEquals("document", body.path("columns").get(1).path("data_type").asText());
    }

    @Test
    void addColumnPostsToColumnsEndpoint() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{\"result\":\"success\",\"data\":{}}"));

        client.addColumn("ds-1", "chunk_text", "text");

        RecordedRequest req = server.takeRequest();
        assertEquals("/api/services/datasets/v3/datasets/ds-1/columns", req.getPath());
        JsonNode body = objectMapper.readTree(req.getBody().readUtf8());
        assertEquals("chunk_text", body.path("slug").asText());
        assertEquals("text", body.path("data_type").asText());
    }

    @Test
    void insertRowsUsesAbortModeAndReturnsIds() throws Exception {
        enqueue(201, "rows-created.json");

        List<String> ids = client.insertRows("ds-1", List.of(Map.of("external_id", "doc-1",
                "document", Map.of("data", "aGVsbG8=", "filename", "hello.txt"))));

        assertEquals(List.of("row-new"), ids);
        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/api/services/datasets/v3/datasets/ds-1/rows?on_error=abort", req.getPath());
        JsonNode row = objectMapper.readTree(req.getBody().readUtf8()).path("rows").get(0);
        assertEquals("doc-1", row.path("external_id").asText());
        assertEquals("aGVsbG8=", row.path("document").path("data").asText());
    }

    @Test
    void insertRowsRejectsPartialRows() throws Exception {
        enqueue(207, "rows-partial.json");

        IOException e = assertThrows(IOException.class, () -> client.insertRows("ds-1", List.of(Map.of("external_id", "doc-1"))));
        assertTrue(e.getMessage().contains("partial"));
    }

    @Test
    void findRowIdsFiltersByColumnAndPaginates() throws Exception {
        enqueue(200, "search-page-1.json");
        enqueue(200, "search-page-2.json");

        List<String> ids = client.findRowIds("ds-1", "external_id", "doc-1");

        assertEquals(List.of("row-1", "row-2"), ids);
        RecordedRequest first = server.takeRequest();
        assertEquals("/api/services/datasets/v3/datasets/ds-1/rows/views/detailed/search", first.getPath());
        JsonNode firstBody = objectMapper.readTree(first.getBody().readUtf8());
        assertEquals("external_id", firstBody.path("filter").path("column_slug").asText());
        assertEquals("equals", firstBody.path("filter").path("operator").asText());
        assertEquals("doc-1", firstBody.path("filter").path("value").asText());
        assertFalse(firstBody.has("cursor"));
        JsonNode secondBody = objectMapper.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("s-cur-2", secondBody.path("cursor").asText());
    }

    @Test
    void patchRowSendsCellUpdates() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        client.patchRow("ds-1", "row-1", Map.of("title", "New title"));

        RecordedRequest req = server.takeRequest();
        assertEquals("PATCH", req.getMethod());
        assertEquals("/api/services/datasets/v3/datasets/ds-1/rows/row-1", req.getPath());
        JsonNode cell = objectMapper.readTree(req.getBody().readUtf8()).path("cells").get(0);
        assertEquals("title", cell.path("column_slug").asText());
        assertEquals("New title", cell.path("value").asText());
    }

    @Test
    void deleteRowToleratesNotFound() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        enqueue(404, "error-not-found.json");

        client.deleteRow("ds-1", "row-1");
        client.deleteRow("ds-1", "row-gone");

        RecordedRequest req = server.takeRequest();
        assertEquals("DELETE", req.getMethod());
        assertEquals("/api/services/datasets/v3/datasets/ds-1/rows/row-1", req.getPath());
    }

    @Test
    void retriesThrottledRequestsWithBackoff() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(503));
        enqueue(200, "auth-info.json");

        client.authInfo();

        assertEquals(3, server.getRequestCount());
    }

    @Test
    void stopsRetryingAfterMaxRetries() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        DoxisApiException e = assertThrows(DoxisApiException.class, () -> client.authInfo());
        assertEquals(503, e.getStatusCode());
        assertEquals(3, server.getRequestCount());
    }
}
