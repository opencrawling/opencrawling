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

import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The Graph client: token caching and renewal, throttling, error messages, downloads. */
class GraphClientTest {

    private MockGraph graph;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        graph = new MockGraph();
        graph.json("POST /tenant-1/oauth2/v2.0/token", "token.json");
    }

    @AfterEach
    void tearDown() throws Exception {
        graph.close();
    }

    private GraphClient client(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of("tenantId", "tenant-1", "clientId", "client-1", "clientSecret", "s3cret",
                "authorityUrl", graph.base(), "graphUrl", graph.base() + "/v1.0", "siteUrl", "https://contoso.sharepoint.com",
                "initialBackoffMs", "10", "maxRetries", "3"));
        config.putAll(extra);
        return new GraphClient(SharePointProperties.fromConfiguration(config));
    }

    @Test
    void tokenIsReusedAndRenewedAfter401() throws Exception {
        AtomicInteger tokens = new AtomicInteger();
        graph.routes.put("POST /tenant-1/oauth2/v2.0/token", r -> new MockResponse().setResponseCode(200)
                .setBody("{\"access_token\":\"tok-" + tokens.incrementAndGet() + "\",\"expires_in\":3599}"));
        graph.routes.put("GET /v1.0/drives/d", r -> "Bearer tok-1".equals(r.getHeader("Authorization"))
                ? new MockResponse().setResponseCode(401).setBody("{\"error\":{\"code\":\"InvalidAuthenticationToken\"}}")
                : new MockResponse().setResponseCode(200).setBody("{\"id\":\"d\"}"));
        graph.body("GET /v1.0/drives/e", "application/json", "{\"id\":\"e\"}");
        GraphClient client = client(Map.of());
        assertEquals("d", client.getDrive("d").path("id").asText());
        assertEquals("e", client.getDrive("e").path("id").asText());
        assertEquals(2, tokens.get(), "one renewal after the 401, then reused");
    }

    @Test
    void tokenErrorsExplainWithoutTheSecret() {
        graph.routes.put("POST /tenant-1/oauth2/v2.0/token", r -> new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client secret provided.\\r\\nTrace ID: x\"}"));
        GraphApiException e = assertThrows(GraphApiException.class, () -> client(Map.of()).getDrive("d"));
        assertEquals("invalid_client", e.code());
        assertTrue(e.getMessage().contains("AADSTS7000215"));
        assertFalse(e.getMessage().contains("s3cret"));
    }

    @Test
    void serviceUnavailableIsRetriedAndHttpDateRetryAfterIsHonoured() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String soon = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        graph.routes.put("GET /v1.0/drives/d", r -> switch (calls.incrementAndGet()) {
            case 1 -> new MockResponse().setResponseCode(503);
            case 2 -> new MockResponse().setResponseCode(429).setHeader("Retry-After", soon);
            default -> new MockResponse().setResponseCode(200).setBody("{\"id\":\"d\"}");
        });
        assertEquals("d", client(Map.of()).getDrive("d").path("id").asText());
        assertEquals(3, calls.get());
    }

    @Test
    void retryAfterZeroNeverBecomesABurstOfRetries() throws Exception {
        // what a client hears when it came back a little early: Retry-After rounded down to 0
        AtomicInteger calls = new AtomicInteger();
        List<Long> at = new java.util.concurrent.CopyOnWriteArrayList<>();
        graph.routes.put("GET /v1.0/drives/d", r -> {
            at.add(System.nanoTime());
            return calls.incrementAndGet() <= 2 ? new MockResponse().setResponseCode(429).setHeader("Retry-After", "0")
                    : new MockResponse().setResponseCode(200).setBody("{\"id\":\"d\"}");
        });
        assertEquals("d", client(Map.of()).getDrive("d").path("id").asText());
        assertEquals(3, calls.get());
        for (int i = 1; i < at.size(); i++) {
            assertTrue(at.get(i) - at.get(i - 1) >= 950_000_000L, "a retry after Retry-After: 0 still waits about a second");
        }
    }

    @Test
    void giveUpAfterMaxRetriesWithGraphsErrorCode() {
        graph.routes.put("GET /v1.0/drives/d", r -> new MockResponse().setResponseCode(503)
                .setBody("{\"error\":{\"code\":\"serviceNotAvailable\",\"message\":\"Try later\"}}"));
        GraphApiException e = assertThrows(GraphApiException.class, () -> client(Map.of("maxRetries", "1")).getDrive("d"));
        assertEquals(503, e.status());
        assertEquals("serviceNotAvailable", e.code());
        assertEquals(2, graph.count("GET /v1.0/drives/d"));
    }

    @Test
    void everyGraphRequestCarriesAClientRequestIdAndErrorsNameGraphsIds() throws Exception {
        graph.routes.put("GET /v1.0/drives/d", r -> new MockResponse().setResponseCode(404).setBody("{\"error\":{\"code\":\"itemNotFound\","
                + "\"message\":\"The resource could not be found.\",\"innerError\":{\"request-id\":\"rid-1\",\"client-request-id\":\""
                + r.getHeader("client-request-id") + "\"}}}"));
        GraphApiException e = assertThrows(GraphApiException.class, () -> client(Map.of()).getDrive("d"));
        String sent = graph.request("GET /v1.0/drives/d").getHeader("client-request-id");
        assertDoesNotThrow(() -> java.util.UUID.fromString(sent));
        assertTrue(e.getMessage().contains("request-id rid-1, client-request-id " + sent), e.getMessage());
    }

    @Test
    void forbiddenNamesTheMissingPermission() {
        graph.routes.put("GET /v1.0/sites/s", r -> new MockResponse().setResponseCode(403)
                .setBody("{\"error\":{\"code\":\"accessDenied\",\"message\":\"Access denied\"}}"));
        GraphApiException e = assertThrows(GraphApiException.class, () -> client(Map.of()).getSite("s"));
        assertTrue(e.getMessage().contains("Sites.Read.All"), e.getMessage());
    }

    @Test
    void downloadIsCappedAndRemovesThePartialFile() throws Exception {
        graph.body("GET /blob", "application/octet-stream", "0123456789");
        GraphClient client = client(Map.of());
        Path ok = dir.resolve("ok");
        assertEquals(10, client.download("d", "i", graph.base() + "/blob", ok, 100));
        assertEquals("0123456789", Files.readString(ok));
        Path big = dir.resolve("big");
        assertEquals(-1, client.download("d", "i", graph.base() + "/blob", big, 5));
        assertFalse(Files.exists(big) && Files.size(big) > 5);
    }

    @Test
    void expiredDownloadUrlFallsBackToContent() throws Exception {
        graph.routes.put("GET /expired", r -> new MockResponse().setResponseCode(401));
        graph.routes.put("GET /v1.0/drives/d/items/i/content", r -> new MockResponse().setResponseCode(302)
                .setHeader("Location", graph.base() + "/fresh"));
        graph.body("GET /fresh", "text/plain", "fresh");
        Path target = dir.resolve("f");
        assertEquals(5, client(Map.of()).download("d", "i", graph.base() + "/expired", target, 100));
        assertNull(graph.request("GET /expired").getHeader("Authorization"));
        assertNull(graph.request("GET /fresh").getHeader("Authorization"));
    }

    @Test
    void linksOffTheGraphHostAreRefused() {
        IOException e = assertThrows(IOException.class, () -> client(Map.of()).page("https://evil.example/v1.0/x"));
        assertTrue(e.getMessage().contains("evil.example"));
        assertEquals(0, graph.count("POST /tenant-1/oauth2/v2.0/token"), "refused before any token is fetched");
    }
}
