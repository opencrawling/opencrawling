/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.hyland.cic.client;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.hyland.cic.config.HylandCicProperties;
import org.opencrawling.hyland.cic.model.HylandCicDocument;
import org.opencrawling.hyland.cic.model.HylandCicFolder;
import org.opencrawling.hyland.cic.model.HylandCicPagedResult;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HylandCicClientTest {

    private MockWebServer server;
    private HylandCicProperties properties;
    private HylandCicClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        properties = new HylandCicProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setIdpTokenUrl(server.url("/connect/token").toString());
        properties.setClientId("test-client");
        properties.setClientSecret("test-secret");
        properties.setBearerToken("mock-token-xyz");
        properties.setTenantId("test-tenant");
        properties.setConnectionTimeoutSeconds(5);
        properties.setMaxRetries(2);

        client = new HylandCicClient(properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
        client.close();
    }

    @Test
    void checkHealthReturnsTrueOn200() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"UP\"}"));

        boolean healthy = client.checkHealth();
        assertThat(healthy).isTrue();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/content/health");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer mock-token-xyz");
        assertThat(req.getHeader("X-Tenant-Id")).isEqualTo("test-tenant");
    }

    @Test
    void checkHealthReturnsFalseOn500() {
        server.enqueue(new MockResponse().setResponseCode(500));
        boolean healthy = client.checkHealth();
        assertThat(healthy).isFalse();
    }

    @Test
    void getValidTokenExchangesCredentialsWhenNoDirectBearer() throws Exception {
        properties.setBearerToken(""); // clear direct token
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"exchanged-jwt-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"));

        String token = client.getValidToken();
        assertThat(token).isEqualTo("exchanged-jwt-token");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/connect/token");
        assertThat(req.getBody().readUtf8()).contains("grant_type=client_credentials", "client_id=test-client", "client_secret=test-secret");
    }

    @Test
    void findFolderReturnsFolderWhenFound() throws Exception {
        String json = """
                {
                  "id": "folder-123",
                  "name": "Finance",
                  "path": "/Corporate/Finance",
                  "parentId": "folder-root"
                }
                """;
        server.enqueue(new MockResponse().setResponseCode(200).setBody(json));

        Optional<HylandCicFolder> folderOpt = client.findFolder("folder-123");
        assertThat(folderOpt).isPresent();
        HylandCicFolder folder = folderOpt.get();
        assertThat(folder.id()).isEqualTo("folder-123");
        assertThat(folder.name()).isEqualTo("Finance");
        assertThat(folder.path()).isEqualTo("/Corporate/Finance");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/content/folders/folder-123");
    }

    @Test
    void findFolderByPathEncodesQueryParam() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"f1\",\"name\":\"Doc\",\"path\":\"/a/b\"}"));

        Optional<HylandCicFolder> folder = client.findFolder("/a/b");
        assertThat(folder).isPresent();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/content/folders?path=%2Fa%2Fb");
    }

    @Test
    void findFolderReturnsEmptyOn404() {
        server.enqueue(new MockResponse().setResponseCode(404));
        Optional<HylandCicFolder> folder = client.findFolder("missing-folder");
        assertThat(folder).isEmpty();
    }

    @Test
    void getFolderItemsReturnsPagedResult() throws Exception {
        String json = """
                {
                  "items": [
                    {
                      "id": "doc-1",
                      "name": "Report.pdf",
                      "type": "document",
                      "contentType": "Report",
                      "fileSize": 1024,
                      "mimeType": "application/pdf"
                    },
                    {
                      "id": "subfolder-2",
                      "name": "2026",
                      "type": "folder",
                      "path": "/Corporate/Finance/2026"
                    }
                  ],
                  "totalCount": 2,
                  "hasMore": false,
                  "nextOffset": null
                }
                """;
        server.enqueue(new MockResponse().setResponseCode(200).setBody(json));

        HylandCicPagedResult result = client.getFolderItems("folder-123", 0, 50);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).isDocument()).isTrue();
        assertThat(result.items().get(1).isFolder()).isTrue();
        assertThat(result.hasMore()).isFalse();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/content/folders/folder-123/items?offset=0&limit=50");
    }

    @Test
    void queryExecutesSearch() throws Exception {
        String json = """
                {
                  "items": [
                    {
                      "id": "doc-q1",
                      "name": "Audit.docx",
                      "type": "document",
                      "contentType": "Audit"
                    }
                  ],
                  "totalCount": 1,
                  "hasMore": false
                }
                """;
        server.enqueue(new MockResponse().setResponseCode(200).setBody(json));

        HylandCicPagedResult result = client.query("contentType eq 'Audit'", 0, 100);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo("doc-q1");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/content/query");
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getBody().readUtf8()).contains("contentType eq 'Audit'");
    }

    @Test
    void findDocumentReturnsDocument() throws Exception {
        String json = """
                {
                  "id": "doc-99",
                  "name": "Agreement.pdf",
                  "contentType": "LegalDoc",
                  "fileSize": 2048,
                  "mimeType": "application/pdf",
                  "folderId": "f-legal",
                  "version": "1.0",
                  "customFields": {
                    "Department": "Legal",
                    "Signee": "John Doe"
                  }
                }
                """;
        server.enqueue(new MockResponse().setResponseCode(200).setBody(json));

        Optional<HylandCicDocument> docOpt = client.findDocument("doc-99");
        assertThat(docOpt).isPresent();
        HylandCicDocument doc = docOpt.get();
        assertThat(doc.id()).isEqualTo("doc-99");
        assertThat(doc.name()).isEqualTo("Agreement.pdf");
        assertThat(doc.customFields()).containsEntry("Department", "Legal");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/content/documents/doc-99");
    }

    @Test
    void downloadContentStreamsBinary() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("BINARY_PAYLOAD_DATA"));

        try (InputStream stream = client.downloadContent("doc-99")) {
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).isEqualTo("BINARY_PAYLOAD_DATA");
        }

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/content/documents/doc-99/content");
    }

    @Test
    void createDocumentExecutesMultipartUpload() throws Exception {
        String json = """
                {
                  "id": "new-doc-1",
                  "name": "Uploaded.pdf",
                  "contentType": "ArchivedDocument"
                }
                """;
        server.enqueue(new MockResponse().setResponseCode(201).setBody(json));

        HylandCicDocument doc = client.createDocument(
                "f-target",
                "Uploaded.pdf",
                "ArchivedDocument",
                "TEST_DATA".getBytes(StandardCharsets.UTF_8),
                "application/pdf",
                Map.of("Status", "Archived")
        );

        assertThat(doc.id()).isEqualTo("new-doc-1");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/content/documents");
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getHeader("Content-Type")).startsWith("multipart/form-data; boundary=");
        assertThat(req.getBody().readUtf8()).contains("Uploaded.pdf", "ArchivedDocument", "TEST_DATA");
    }

    @Test
    void deleteDocumentSendsDeleteRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        boolean deleted = client.deleteDocument("doc-to-delete");
        assertThat(deleted).isTrue();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/content/documents/doc-to-delete");
        assertThat(req.getMethod()).isEqualTo("DELETE");
    }

    @Test
    void retriesOnHttp429() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "1"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"UP\"}"));

        boolean healthy = client.checkHealth();
        assertThat(healthy).isTrue();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }
}
