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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The connector under Microsoft's own Graph error preset (Dev Proxy's {@code microsoft-graph.json}: 50% of Graph requests
 * fail with 429, 500, 502, 503, 504 or 507). Nothing can be expected to succeed there, so the test checks what must hold
 * anyway: no crawl tombstones a live file, whether it ends with failures or aborts; once the errors stop, the next crawl
 * brings every live file in; and the crawl after that finds nothing, so the delta link never moved past a failure.
 *
 * <p>Runs only from {@code scripts/test-sharepoint-devproxy.sh}: the JVM's {@code https.proxyPort} points at a Dev Proxy
 * without errors, and {@code GRAPH_MOCK_CHAOS_PROXY_PORT} at one with Microsoft's error preset; both route the real Graph
 * host names to the Graph mock.
 */
@EnabledIfEnvironmentVariable(named = "GRAPH_MOCK_CHAOS_PROXY_PORT", matches = "\\d+")
class SharePointDevProxyChaosTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MOCK = System.getenv().getOrDefault("GRAPH_MOCK_URL", "").replaceAll("/+$", "");
    private static final String CHAOS_PORT = System.getenv("GRAPH_MOCK_CHAOS_PROXY_PORT");
    private final String calmPort = System.getProperty("https.proxyPort");

    @TempDir
    Path state;

    @AfterEach
    void calm() {
        if (calmPort != null) {
            System.setProperty("https.proxyPort", calmPort);
        }
    }

    private JsonNode admin(String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(MOCK + path));
        request = body == null ? request.GET() : request.POST(HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient http = HttpClient.newHttpClient()) {
            return MAPPER.readTree(http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body());
        }
    }

    private SharePointRepositoryConnector connector() {
        return new SharePointRepositoryConnector(SharePointProperties.fromConfiguration(Map.of("tenantId", "contoso-tenant",
                "clientId", "opencrawling-test-app", "clientSecret", "test-secret",
                "authorityUrl", "https://login.microsoftonline.com", "graphUrl", "https://graph.microsoft.com/v1.0",
                "siteUrl", "https://contoso.sharepoint.com/sites/engineering", "stateDirectory", state.toString(),
                "maxRetries", "3", "initialBackoffMs", "50")));
    }

    /** A crawl that may end in an error: the documents it emitted before that, with their content read. */
    private static List<RepositoryDocument> crawl(SharePointRepositoryConnector connector, List<String> outcome) {
        List<RepositoryDocument> emitted = new ArrayList<>();
        try {
            connector.scan("default").doOnNext(d -> {
                emitted.add(d);
                if (d.contentStream() != null) {
                    try (InputStream in = d.contentStream()) {
                        in.transferTo(OutputStream.nullOutputStream());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            }).blockLast();
            outcome.add("completed");
        } catch (RuntimeException e) {
            outcome.add("aborted: " + e.getMessage());
        }
        return emitted;
    }

    @Test
    void microsoftsErrorPresetNeverDeletesAndTheIndexCatchesUp() throws Exception {
        admin("/mock/reset", "{}");
        Set<String> live = new HashSet<>();
        admin("/mock/files", null).forEach(f -> live.add(SharePointDocumentBuilder.documentId("contoso.sharepoint.com",
                f.path("drive").asText(), f.path("id").asText())));

        Set<String> indexed = new HashSet<>();
        List<String> outcomes = new ArrayList<>();
        System.setProperty("https.proxyPort", CHAOS_PORT);
        for (int run = 0; run < 3; run++) {
            for (RepositoryDocument d : crawl(connector(), outcomes)) {
                assertNotEquals(DocumentAction.DELETE, d.action(), "a live file was tombstoned under errors: " + d.id());
                indexed.add(d.id());
            }
        }
        System.setProperty("https.proxyPort", calmPort);
        List<RepositoryDocument> recovery = crawl(connector(), outcomes);
        assertEquals("completed", outcomes.getLast(), "without errors the crawl completes: " + outcomes);
        recovery.forEach(d -> {
            assertEquals(DocumentAction.UPSERT, d.action());
            indexed.add(d.id());
        });
        assertEquals(live, indexed, "every live file is indexed once the errors stop; runs: " + outcomes);
        assertTrue(crawl(connector(), outcomes).isEmpty(), "the delta link only advanced after a clean run: " + outcomes);
        System.out.println("Crawls under Microsoft's error preset, then without: " + outcomes);
    }
}
