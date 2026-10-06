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
package org.opencrawling.stormcrawler;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.stormcrawler.config.StormCrawlerProperties;
import org.opencrawling.stormcrawler.nimbus.NimbusClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StormCrawlerRepositoryConnectorTest {

    private StormCrawlerRepositoryConnector connector;
    private StormCrawlerProperties properties;
    private HttpClient mockHttpClient;

    @BeforeEach
    void setUp() {
        properties = new StormCrawlerProperties();
        properties.setNimbusRestUrl("http://127.0.0.1:8080");
        properties.setDelayMs(0); // zero delay for fast unit tests
        properties.setSeeds(List.of("https://example.com/docs/page1", "https://example.com/docs/page2"));

        connector = new StormCrawlerRepositoryConnector(properties);
        mockHttpClient = mock(HttpClient.class);
        connector.setHttpClient(mockHttpClient);
    }

    @Test
    @DisplayName("Connector Metadata & Schema Integrity")
    void testConnectorNameAndSchema() {
        assertEquals("StormCrawlerConnector", connector.getName());

        ConnectorSchema schema = connector.getSchema("");
        assertNotNull(schema);
        assertFalse(schema.fields().isEmpty());
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("id")));
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("canonical.url")));
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("stormcrawler.status")));
    }

    @Test
    @DisplayName("URL Regex Inclusion and Exclusion Filters")
    void testUrlFiltering() {
        List<Pattern> includes = List.of(Pattern.compile("^https://example\\.com/docs/.*"));
        List<Pattern> excludes = List.of(Pattern.compile(".*\\.(pdf|zip|gz)$"));

        assertTrue(connector.isUrlAllowed("https://example.com/docs/intro", includes, excludes));
        assertFalse(connector.isUrlAllowed("https://example.com/docs/archive.zip", includes, excludes));
        assertFalse(connector.isUrlAllowed("https://otherdomain.com/docs/intro", includes, excludes));
    }

    @Test
    @DisplayName("Fetch Document: HTTP 200 Returns OIS UPSERT Document")
    @SuppressWarnings("unchecked")
    void testFetchDocumentUpsert() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("<html><head><title>Enterprise Policy</title></head><body>Zero Trust</body></html>");

        HttpHeaders mockHeaders = HttpHeaders.of(
                Map.of("content-type", List.of("text/html; charset=UTF-8"), "server", List.of("nginx/1.24")),
                (k, v) -> true
        );
        when(mockResponse.headers()).thenReturn(mockHeaders);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        RepositoryDocument doc = connector.fetchDocument("https://example.com/docs/policy");

        assertNotNull(doc);
        assertEquals("https://example.com/docs/policy", doc.id());
        assertEquals(DocumentAction.UPSERT, doc.action());
        assertEquals("Enterprise Policy", doc.metadata().get("title").get(0));
        assertEquals("text/html; charset=UTF-8", doc.metadata().get("mimeType").get(0));
        assertEquals("200", doc.metadata().get("http.status").get(0));
        assertNotNull(doc.contentStream());

        String content = new String(doc.contentStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(content.contains("Zero Trust"));
    }

    @Test
    @DisplayName("Fetch Document: HTTP 404 Returns OIS DELETE Tombstone")
    @SuppressWarnings("unchecked")
    void testFetchDocumentHttp404ReturnsDeleteTombstone() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(404);
        when(mockResponse.body()).thenReturn("Not Found");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        RepositoryDocument tombstone = connector.fetchDocument("https://example.com/docs/deprecated");

        assertNotNull(tombstone);
        assertEquals("https://example.com/docs/deprecated", tombstone.id());
        assertEquals(DocumentAction.DELETE, tombstone.action());
        assertNull(tombstone.contentStream());
    }

    @Test
    @DisplayName("Fetch Document: HTTP 410 Gone Returns OIS DELETE Tombstone")
    @SuppressWarnings("unchecked")
    void testFetchDocumentHttp410ReturnsDeleteTombstone() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(410);
        when(mockResponse.body()).thenReturn("Gone");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        RepositoryDocument tombstone = connector.fetchDocument("https://example.com/docs/permanently-removed");

        assertNotNull(tombstone);
        assertEquals(DocumentAction.DELETE, tombstone.action());
        assertNull(tombstone.contentStream());
    }

    @Test
    @DisplayName("Scan Flux: Emits Both UPSERT and DELETE Documents")
    @SuppressWarnings("unchecked")
    void testScanFluxDualStreamEmission() throws Exception {
        HttpResponse<String> okResponse = mock(HttpResponse.class);
        when(okResponse.statusCode()).thenReturn(200);
        when(okResponse.body()).thenReturn("<html><title>Page 1</title></html>");
        when(okResponse.headers()).thenReturn(HttpHeaders.of(Map.of("content-type", List.of("text/html")), (k, v) -> true));

        HttpResponse<String> notFoundResponse = mock(HttpResponse.class);
        when(notFoundResponse.statusCode()).thenReturn(404);
        when(notFoundResponse.body()).thenReturn("Not Found");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    HttpRequest req = inv.getArgument(0);
                    if (req.uri().toString().contains("page2")) {
                        return notFoundResponse;
                    }
                    return okResponse;
                });

        // scan() fetches the URLs concurrently: documents arrive in completion order, not input order
        StepVerifier.create(connector.scan("https://example.com/docs/page1, https://example.com/docs/page2")
                        .collectMap(RepositoryDocument::id, RepositoryDocument::action))
                .assertNext(actions -> assertEquals(Map.of(
                        "https://example.com/docs/page1", DocumentAction.UPSERT,
                        "https://example.com/docs/page2", DocumentAction.DELETE), actions))
                .verifyComplete();
    }

    @Test
    @DisplayName("Nimbus Client: Cluster & Topology Management REST Calls")
    @SuppressWarnings("unchecked")
    void testNimbusClientRestOperations() throws Exception {
        HttpClient nimbusHttpClient = mock(HttpClient.class);
        NimbusClient nimbusClient = new NimbusClient("http://localhost:8080", nimbusHttpClient);

        HttpResponse<String> clusterResp = mock(HttpResponse.class);
        when(clusterResp.statusCode()).thenReturn(200);
        when(clusterResp.body()).thenReturn("{\"stormVersion\":\"2.8.4\",\"supervisors\":2,\"slotsTotal\":8}");

        HttpResponse<String> topoResp = mock(HttpResponse.class);
        when(topoResp.statusCode()).thenReturn(200);
        when(topoResp.body()).thenReturn("{\"topologies\":[{\"id\":\"opencrawling-1-12345\",\"name\":\"opencrawling-web-crawler\",\"status\":\"ACTIVE\"}]}");

        HttpResponse<String> actionResp = mock(HttpResponse.class);
        when(actionResp.statusCode()).thenReturn(200);
        when(actionResp.body()).thenReturn("{\"status\":\"success\"}");

        when(nimbusHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    HttpRequest req = inv.getArgument(0);
                    String path = req.uri().getPath();
                    if (path.contains("/cluster/summary")) return clusterResp;
                    if (path.contains("/topology/summary")) return topoResp;
                    return actionResp;
                });

        assertTrue(nimbusClient.ping());

        JsonNode cluster = nimbusClient.getClusterSummary();
        assertEquals("2.8.4", cluster.path("stormVersion").asText());

        JsonNode topos = nimbusClient.getTopologySummary();
        assertEquals("opencrawling-1-12345", topos.path("topologies").get(0).path("id").asText());

        assertTrue(nimbusClient.activateTopology("opencrawling-1-12345"));
        assertTrue(nimbusClient.deactivateTopology("opencrawling-1-12345"));
        assertTrue(nimbusClient.killTopology("opencrawling-1-12345", 10));
    }
}
