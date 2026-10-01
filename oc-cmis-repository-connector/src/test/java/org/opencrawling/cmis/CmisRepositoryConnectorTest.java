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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import reactor.test.StepVerifier;

public class CmisRepositoryConnectorTest {

    private HttpServer server;
    private String serverUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/browser", this::handleRequest);
        server.start();

        int port = server.getAddress().getPort();
        serverUrl = "http://localhost:" + port + "/browser";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery() != null ? exchange.getRequestURI().getQuery() : "";

        if (query.contains("cmisselector=contentChanges")) {
            String changesJson = """
                {
                  "changeEvents": [
                    {
                      "changeType": "deleted",
                      "objectId": "doc-del-77"
                    },
                    {
                      "changeType": "created",
                      "objectId": "doc-new-88"
                    }
                  ],
                  "hasMoreItems": false,
                  "latestChangeLogToken": "token-final"
                }
                """;
            sendResponse(exchange, 200, "application/json", changesJson);
        } else if (query.contains("cmisselector=query")) {
            String queryResult = """
                {
                  "results": [
                    {
                      "properties": {
                        "cmis:objectId": { "value": "doc-q1" },
                        "cmis:name": { "value": "Report.xlsx" },
                        "cmis:baseTypeId": { "value": "cmis:document" },
                        "cmis:contentStreamMimeType": { "value": "application/vnd.ms-excel" },
                        "cmis:contentStreamLength": { "value": 11 }
                      }
                    }
                  ],
                  "hasMoreItems": false
                }
                """;
            sendResponse(exchange, 200, "application/json", queryResult);
        } else if (query.contains("cmisselector=content")) {
            sendResponse(exchange, 200, "application/octet-stream", "DOCUMENT-RAW-CONTENT");
        } else if (query.contains("cmisselector=object") && query.contains("objectId=doc-new-88")) {
            String objJson = """
                {
                  "properties": {
                    "cmis:objectId": { "value": "doc-new-88" },
                    "cmis:name": { "value": "NewContract.pdf" },
                    "cmis:baseTypeId": { "value": "cmis:document" },
                    "cmis:contentStreamLength": { "value": 8 }
                  }
                }
                """;
            sendResponse(exchange, 200, "application/json", objJson);
        } else if (query.contains("cmisselector=children")) {
            if (path.contains("/Engineering")) {
                String subChildren = """
                    {
                      "objects": [
                        {
                          "object": {
                            "properties": {
                              "cmis:objectId": { "value": "doc-02" },
                              "cmis:name": { "value": "Specs.md" },
                              "cmis:baseTypeId": { "value": "cmis:document" },
                              "cmis:contentStreamMimeType": { "value": "text/markdown" },
                              "cmis:contentStreamLength": { "value": 10 },
                              "cmis:path": { "value": "/Engineering/Specs.md" }
                            }
                          }
                        }
                      ],
                      "hasMoreItems": false
                    }
                    """;
                sendResponse(exchange, 200, "application/json", subChildren);
            } else if (path.contains("/Trash")) {
                String trashChildren = """
                    {
                      "objects": [
                        {
                          "object": {
                            "properties": {
                              "cmis:objectId": { "value": "doc-trash" },
                              "cmis:name": { "value": "OldTrash.txt" },
                              "cmis:baseTypeId": { "value": "cmis:document" }
                            }
                          }
                        }
                      ],
                      "hasMoreItems": false
                    }
                    """;
                sendResponse(exchange, 200, "application/json", trashChildren);
            } else {
                // Root children
                String rootChildren = """
                    {
                      "objects": [
                        {
                          "object": {
                            "properties": {
                              "cmis:objectId": { "value": "doc-01" },
                              "cmis:name": { "value": "Charter.pdf" },
                              "cmis:baseTypeId": { "value": "cmis:document" },
                              "cmis:contentStreamMimeType": { "value": "application/pdf" },
                              "cmis:contentStreamLength": { "value": 14 },
                              "cmis:path": { "value": "/Charter.pdf" }
                            }
                          }
                        },
                        {
                          "object": {
                            "properties": {
                              "cmis:objectId": { "value": "folder-eng" },
                              "cmis:name": { "value": "Engineering" },
                              "cmis:baseTypeId": { "value": "cmis:folder" },
                              "cmis:path": { "value": "/Engineering" }
                            }
                          }
                        }
                      ],
                      "hasMoreItems": false
                    }
                    """;
                sendResponse(exchange, 200, "application/json", rootChildren);
            }
        } else {
            // Service doc
            String serviceDocJson = """
                {
                  "-default-": {
                    "repositoryId": "-default-",
                    "repositoryName": "Enterprise ECM",
                    "vendorName": "OASIS CMIS 1.1 Server",
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
    void testConnectAndDisconnect() throws Exception {
        CmisRepositoryConnector connector = new CmisRepositoryConnector(
            serverUrl, "-default-", "admin", "admin",
            CmisBindingType.BROWSER, CmisCrawlMode.FOLDER,
            "/", "", true, Set.of(),
            "SELECT * FROM cmis:document", CmisVersionsMode.LATEST_MAJOR,
            true, 52428800L, true, true, false, "", 50, 5
        );

        connector.connect();
        assertThat(connector.getName()).isEqualTo("CmisRepositoryConnector");

        connector.disconnect();
    }

    @Test
    void testScanFolderHierarchy() {
        CmisRepositoryConnector connector = new CmisRepositoryConnector(
            serverUrl, "-default-", "admin", "admin",
            CmisBindingType.BROWSER, CmisCrawlMode.FOLDER,
            "/", "", true, Set.of("/Sites/trash"),
            "SELECT * FROM cmis:document", CmisVersionsMode.LATEST_MAJOR,
            true, 52428800L, true, true, false, "", 50, 5
        );

        StepVerifier.create(connector.scan("/"))
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("cmis://-default-/documents/doc-01");
                    assertThat(doc.action()).isEqualTo(DocumentAction.UPSERT);
                    assertThat(doc.metadata().get("name")).containsExactly("Charter.pdf");
                })
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("cmis://-default-/documents/doc-02");
                    assertThat(doc.action()).isEqualTo(DocumentAction.UPSERT);
                    assertThat(doc.metadata().get("name")).containsExactly("Specs.md");
                })
                .verifyComplete();
    }

    @Test
    void testScanExcludedFolder() {
        CmisRepositoryConnector connector = new CmisRepositoryConnector(
            serverUrl, "-default-", "admin", "admin",
            CmisBindingType.BROWSER, CmisCrawlMode.FOLDER,
            "/", "", true, Set.of("/Engineering"),
            "SELECT * FROM cmis:document", CmisVersionsMode.LATEST_MAJOR,
            true, 52428800L, true, true, false, "", 50, 5
        );

        // Subfolder /Engineering is excluded, so only root document doc-01 should be scanned
        StepVerifier.create(connector.scan("/"))
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("cmis://-default-/documents/doc-01");
                    assertThat(doc.metadata().get("name")).containsExactly("Charter.pdf");
                })
                .verifyComplete();
    }

    @Test
    void testScanQueryMode() {
        CmisRepositoryConnector connector = new CmisRepositoryConnector(
            serverUrl, "-default-", "admin", "admin",
            CmisBindingType.BROWSER, CmisCrawlMode.QUERY,
            "/", "", true, Set.of(),
            "SELECT * FROM cmis:document WHERE cmis:name LIKE 'Report%'", CmisVersionsMode.ALL,
            true, 52428800L, true, true, false, "", 50, 5
        );

        StepVerifier.create(connector.scan(""))
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("cmis://-default-/documents/doc-q1");
                    assertThat(doc.metadata().get("name")).containsExactly("Report.xlsx");
                })
                .verifyComplete();
    }

    @Test
    void testScanChangeLogWithTombstone() {
        CmisRepositoryConnector connector = new CmisRepositoryConnector(
            serverUrl, "-default-", "admin", "admin",
            CmisBindingType.BROWSER, CmisCrawlMode.FOLDER,
            "/", "", true, Set.of(),
            "SELECT * FROM cmis:document", CmisVersionsMode.LATEST_MAJOR,
            true, 52428800L, true, true, true, "token-init", 50, 5
        );

        StepVerifier.create(connector.scan(""))
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("cmis://-default-/documents/doc-del-77");
                    assertThat(doc.action()).isEqualTo(DocumentAction.DELETE);
                })
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("cmis://-default-/documents/doc-new-88");
                    assertThat(doc.action()).isEqualTo(DocumentAction.UPSERT);
                    assertThat(doc.metadata().get("name")).containsExactly("NewContract.pdf");
                })
                .verifyComplete();
    }

    @Test
    void testGetSchema() {
        CmisRepositoryConnector connector = new CmisRepositoryConnector();
        ConnectorSchema schema = connector.getSchema("/");
        assertThat(schema.fields()).isNotEmpty();
        assertThat(schema.fields().stream().map(ConnectorSchema.SchemaField::name))
                .contains("cmis.objectId", "cmis.name", "cmis.baseTypeId", "cmis.lastModificationDate");
    }
}
