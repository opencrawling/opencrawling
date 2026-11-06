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
package org.opencrawling.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.exceptions.OpenCrawlingApiException;
import org.opencrawling.sdk.models.DiscoveryConnectorInfo;
import org.opencrawling.sdk.models.DiscoveryDryRunRequest;
import org.opencrawling.sdk.models.DiscoveryDryRunView;
import org.opencrawling.sdk.models.DiscoveryTreeRequest;
import org.opencrawling.sdk.models.DiscoveryTreeView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryClientTest {

    private static HttpServer server;
    private static OpenCrawlingClient client;

    @BeforeAll
    static void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/api/discovery/connectors", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            String query = exchange.getRequestURI().getQuery();
            String response = """
                [
                  {
                    "name": "cmis-repo",
                    "description": "Corporate CMIS Archive"
                  },
                  {
                    "name": "alfresco-repo",
                    "description": "Alfresco Content Services"
                  }
                ]
                """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/api/discovery/cmis-repo/tree", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            String query = exchange.getRequestURI().getRawQuery();
            assertThat(query).contains("start=%2FCompany+Home");
            assertThat(query).contains("depth=2");
            assertThat(query).contains("maxNodes=50");
            assertThat(query).contains("includeDocuments=true");
            assertThat(query).contains("userPrincipal=alice");
            assertThat(query).contains("userRoles=finance");

            String response = """
                {
                  "connector": "cmis-repo",
                  "root": "/Company Home",
                  "nodes": [
                    {
                      "id": "cmis://101",
                      "sourceId": "101",
                      "parentId": null,
                      "name": "Company Home",
                      "path": "/Company Home",
                      "type": "FOLDER",
                      "depth": 0,
                      "mimeType": null,
                      "sizeBytes": null,
                      "lastModified": "2026-11-06T09:00:00Z"
                    },
                    {
                      "id": "cmis://102",
                      "sourceId": "102",
                      "parentId": "101",
                      "name": "report.pdf",
                      "path": "/Company Home/report.pdf",
                      "type": "DOCUMENT",
                      "depth": 1,
                      "mimeType": "application/pdf",
                      "sizeBytes": 20480,
                      "lastModified": "2026-11-06T09:30:00Z"
                    }
                  ],
                  "hiddenCount": 1,
                  "truncated": false,
                  "truncationReason": null
                }
                """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/api/discovery/cmis-repo/dry-run", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            String query = exchange.getRequestURI().getRawQuery();
            assertThat(query).contains("userPrincipal=alice");
            assertThat(query).contains("userRoles=finance");

            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertThat(body).contains("\"scope\":\"/Company Home\"");
            assertThat(body).contains("\"sampleSize\":5");
            assertThat(body).contains("\"maxScan\":100");

            String response = """
                {
                  "connector": "cmis-repo",
                  "matchCount": 42,
                  "countAccuracy": "EXACT",
                  "samples": [
                    {
                      "id": "cmis://102",
                      "sourceId": "102",
                      "parentId": "101",
                      "name": "report.pdf",
                      "path": "/Company Home/report.pdf",
                      "type": "DOCUMENT",
                      "depth": 1,
                      "mimeType": "application/pdf",
                      "sizeBytes": 20480,
                      "lastModified": "2026-11-06T09:30:00Z"
                    }
                  ],
                  "hiddenCount": 3,
                  "truncated": false,
                  "truncationReason": null,
                  "note": null
                }
                """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/api/discovery/missing-repo/tree", exchange -> {
            String error = "{\"error\":\"No repository connector 'missing-repo'.\"}";
            byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.start();
        client = OpenCrawlingClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testListConnectors() {
        List<DiscoveryConnectorInfo> connectors = client.discovery().listConnectors();
        assertThat(connectors).hasSize(2);
        assertThat(connectors.get(0).name()).isEqualTo("cmis-repo");
        assertThat(connectors.get(0).description()).isEqualTo("Corporate CMIS Archive");
        assertThat(connectors.get(1).name()).isEqualTo("alfresco-repo");
    }

    @Test
    void testListConnectorsWithIdentity() {
        List<DiscoveryConnectorInfo> connectors = client.discovery().listConnectors("alice", "finance");
        assertThat(connectors).hasSize(2);
    }

    @Test
    void testExploreTree() {
        DiscoveryTreeRequest request = DiscoveryTreeRequest.builder()
                .start("/Company Home")
                .depth(2)
                .maxNodes(50)
                .includeDocuments(true)
                .userPrincipal("alice")
                .userRoles("finance")
                .build();

        DiscoveryTreeView tree = client.discovery().exploreTree("cmis-repo", request);
        assertThat(tree).isNotNull();
        assertThat(tree.connector()).isEqualTo("cmis-repo");
        assertThat(tree.root()).isEqualTo("/Company Home");
        assertThat(tree.nodes()).hasSize(2);
        assertThat(tree.hiddenCount()).isEqualTo(1L);
        assertThat(tree.truncated()).isFalse();

        assertThat(tree.nodes().get(0).name()).isEqualTo("Company Home");
        assertThat(tree.nodes().get(0).type()).isEqualTo("FOLDER");
        assertThat(tree.nodes().get(1).name()).isEqualTo("report.pdf");
        assertThat(tree.nodes().get(1).type()).isEqualTo("DOCUMENT");
        assertThat(tree.nodes().get(1).mimeType()).isEqualTo("application/pdf");
        assertThat(tree.nodes().get(1).sizeBytes()).isEqualTo(20480L);
    }

    @Test
    void testDryRun() {
        DiscoveryDryRunRequest request = DiscoveryDryRunRequest.builder()
                .scope("/Company Home")
                .sampleSize(5)
                .maxScan(100)
                .userPrincipal("alice")
                .userRoles("finance")
                .build();

        DiscoveryDryRunView dryRun = client.discovery().dryRun("cmis-repo", request);
        assertThat(dryRun).isNotNull();
        assertThat(dryRun.connector()).isEqualTo("cmis-repo");
        assertThat(dryRun.matchCount()).isEqualTo(42L);
        assertThat(dryRun.countAccuracy()).isEqualTo("EXACT");
        assertThat(dryRun.samples()).hasSize(1);
        assertThat(dryRun.samples().get(0).name()).isEqualTo("report.pdf");
        assertThat(dryRun.hiddenCount()).isEqualTo(3L);
        assertThat(dryRun.truncated()).isFalse();
    }

    @Test
    void testExploreTreeNotFound() {
        assertThatThrownBy(() -> client.discovery().exploreTree("missing-repo", "/Company Home", 2, 50, true))
                .isInstanceOf(OpenCrawlingApiException.class)
                .satisfies(e -> assertThat(((OpenCrawlingApiException) e).getStatusCode()).isEqualTo(404));
    }
}
