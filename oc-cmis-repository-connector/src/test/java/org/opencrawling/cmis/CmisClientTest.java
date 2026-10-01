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
package org.opencrawling.cmis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class CmisClientTest {

    private HttpServer server;
    private CmisClient client;
    private String serverUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/browser", this::handleRequest);
        server.start();

        int port = server.getAddress().getPort();
        serverUrl = "http://localhost:" + port + "/browser";
        client = new CmisClient(serverUrl, "admin", "admin", null, 5);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().toString();
        String query = exchange.getRequestURI().getQuery() != null ? exchange.getRequestURI().getQuery() : "";

        // Basic Auth check
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Basic ")) {
            sendResponse(exchange, 401, "application/json", "{\"error\":\"unauthorized\"}");
            return;
        }

        if (uri.endsWith("/error")) {
            sendResponse(exchange, 404, "text/plain", "Not Found");
        } else if (query.contains("cmisselector=children")) {
            String childrenJson = """
                {
                  "objects": [
                    {
                      "object": {
                        "properties": {
                          "cmis:objectId": { "value": "doc-01" },
                          "cmis:name": { "value": "test.txt" },
                          "cmis:baseTypeId": { "value": "cmis:document" }
                        }
                      }
                    },
                    {
                      "object": {
                        "properties": {
                          "cmis:objectId": { "value": "folder-01" },
                          "cmis:name": { "value": "SubFolder" },
                          "cmis:baseTypeId": { "value": "cmis:folder" }
                        }
                      }
                    }
                  ],
                  "hasMoreItems": false,
                  "numItems": 2
                }
                """;
            sendResponse(exchange, 200, "application/json", childrenJson);
        } else if (query.contains("cmisselector=object")) {
            String objJson = """
                {
                  "properties": {
                    "cmis:objectId": { "value": "doc-01" },
                    "cmis:name": { "value": "test.txt" }
                  }
                }
                """;
            sendResponse(exchange, 200, "application/json", objJson);
        } else if (query.contains("cmisselector=query")) {
            String queryJson = """
                {
                  "results": [
                    {
                      "properties": {
                        "cmis:objectId": { "value": "doc-q1" },
                        "cmis:name": { "value": "query-result.pdf" }
                      }
                    }
                  ],
                  "hasMoreItems": false,
                  "numItems": 1
                }
                """;
            sendResponse(exchange, 200, "application/json", queryJson);
        } else if (query.contains("cmisselector=contentChanges")) {
            String changesJson = """
                {
                  "changeEvents": [
                    {
                      "changeType": "deleted",
                      "objectId": "doc-deleted-1",
                      "changeTime": 1710000000000
                    },
                    {
                      "changeType": "created",
                      "objectId": "doc-created-2",
                      "changeTime": 1710000001000
                    }
                  ],
                  "hasMoreItems": false,
                  "latestChangeLogToken": "token-999"
                }
                """;
            sendResponse(exchange, 200, "application/json", changesJson);
        } else if (query.contains("cmisselector=content")) {
            sendResponse(exchange, 200, "text/plain", "Hello CMIS World");
        } else {
            // Service document (Repositories)
            String serviceDocJson = """
                {
                  "-default-": {
                    "repositoryId": "-default-",
                    "repositoryName": "Alfresco Community",
                    "vendorName": "Alfresco",
                    "cmisVersionSupported": "1.1"
                  }
                }
                """;
            sendResponse(exchange, 200, "application/json", serviceDocJson);
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void testGetRepositories() throws Exception {
        Map<String, CmisClient.CmisRepositoryInfo> repos = client.getRepositories();

        assertThat(repos).containsKey("-default-");
        CmisClient.CmisRepositoryInfo info = repos.get("-default-");
        assertThat(info.repositoryName()).isEqualTo("Alfresco Community");
        assertThat(info.vendorName()).isEqualTo("Alfresco");
    }

    @Test
    void testDiscoverRepositoryId() throws Exception {
        String repoId = client.discoverRepositoryId();
        assertThat(repoId).isEqualTo("-default-");
    }

    @Test
    void testGetChildren() throws Exception {
        CmisClient.CmisChildrenResult result = client.getChildren("-default-", "/", 0, 50, true);

        assertThat(result.hasMoreItems()).isFalse();
        assertThat(result.numItems()).isEqualTo(2);
        assertThat(result.objects()).hasSize(2);
    }

    @Test
    void testGetObject() throws Exception {
        JsonNode obj = client.getObject("-default-", "doc-01", true);
        assertThat(CmisDocumentBuilder.getPropertyValue(obj, "cmis:name")).isEqualTo("test.txt");
    }

    @Test
    void testQuery() throws Exception {
        CmisClient.CmisQueryResult queryResult = client.query("-default-", "SELECT * FROM cmis:document", false, 10, 0);

        assertThat(queryResult.results()).hasSize(1);
        assertThat(CmisDocumentBuilder.getPropertyValue(queryResult.results().get(0), "cmis:name")).isEqualTo("query-result.pdf");
    }

    @Test
    void testGetContentStream() throws Exception {
        try (InputStream stream = client.getContentStream("-default-", "doc-01")) {
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).isEqualTo("Hello CMIS World");
        }
    }

    @Test
    void testGetContentChanges() throws Exception {
        CmisClient.CmisChangesResult changes = client.getContentChanges("-default-", "token-001", 50);

        assertThat(changes.events()).hasSize(2);
        assertThat(changes.events().get(0).changeType()).isEqualTo("deleted");
        assertThat(changes.events().get(0).objectId()).isEqualTo("doc-deleted-1");
        assertThat(changes.latestChangeLogToken()).isEqualTo("token-999");
    }

    @Test
    void testHttpErrorHandling() {
        CmisClient errorClient = new CmisClient(serverUrl + "/error", "admin", "admin", null, 5);

        assertThatThrownBy(errorClient::getRepositories)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void testUrlResolutionAlfrescoEndpoint() {
        // Alfresco endpoint already contains the network/repository identifier in the URL path
        CmisClient alfrescoClient = new CmisClient(
                "http://alfresco:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser",
                "admin", "admin"
        );

        assertThat(alfrescoClient.getRepositoryUrl("-default-"))
                .isEqualTo("http://alfresco:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser");
        assertThat(alfrescoClient.getRootFolderUrl("-default-"))
                .isEqualTo("http://alfresco:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser/root");
    }

    @Test
    void testUrlResolutionGenericEndpoint() {
        // Generic CMIS endpoint where repositoryId must be appended
        CmisClient genericClient = new CmisClient(
                "http://cmis.example.com/cmis/browser",
                "admin", "admin"
        );

        assertThat(genericClient.getRepositoryUrl("repoA"))
                .isEqualTo("http://cmis.example.com/cmis/browser/repoA");
        assertThat(genericClient.getRootFolderUrl("repoA"))
                .isEqualTo("http://cmis.example.com/cmis/browser/repoA/root");
    }
}
