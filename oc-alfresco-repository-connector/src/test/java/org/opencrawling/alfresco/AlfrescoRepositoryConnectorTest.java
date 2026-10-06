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
package org.opencrawling.alfresco;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.RepositoryDocument;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class AlfrescoRepositoryConnectorTest {

    private AlfrescoRepositoryConnector connector;
    private HttpClient mockHttpClient;

    @BeforeEach
    void setUp() {
        connector = new AlfrescoRepositoryConnector(
                "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1",
                "admin",
                "admin",
                10
        );
        mockHttpClient = mock(HttpClient.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testConnectSuccess() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"entry\": {\"id\": \"-root-\"}}");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        connector.setHttpClient(mockHttpClient);
        connector.connect();

        assertThat(connector.getName()).isEqualTo("AlfrescoConnector");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testConnectFailure() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(401);
        when(mockResponse.body()).thenReturn("Unauthorized");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        connector.setHttpClient(mockHttpClient);

        assertThatThrownBy(() -> connector.connect())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to connect to Alfresco Content Services");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testScanFoldersAndFiles() throws Exception {
        HttpResponse<String> childrenResponse = mock(HttpResponse.class);
        when(childrenResponse.statusCode()).thenReturn(200);
        when(childrenResponse.body()).thenReturn("""
            {
              "list": {
                "pagination": {
                  "count": 2,
                  "hasMoreItems": false,
                  "totalItems": 2,
                  "skipCount": 0,
                  "maxItems": 10
                },
                "entries": [
                  {
                    "entry": {
                      "id": "subfolder-1",
                      "name": "Subfolder 1",
                      "isFolder": true,
                      "isFile": false,
                      "nodeType": "cm:folder"
                    }
                  },
                  {
                    "entry": {
                      "id": "file-1",
                      "name": "file-1.txt",
                      "isFolder": false,
                      "isFile": true,
                      "nodeType": "cm:content",
                      "modifiedAt": "2026-07-13T16:11:16.000Z",
                      "content": {
                        "mimeType": "text/plain",
                        "sizeInBytes": 12
                      },
                      "properties": {
                        "cm:title": "File 1 Title",
                        "cm:description": "File 1 Description"
                      },
                      "aspectNames": ["cm:titled"]
                    }
                  }
                ]
              }
            }
            """);

        HttpResponse<String> subfolderChildrenResponse = mock(HttpResponse.class);
        when(subfolderChildrenResponse.statusCode()).thenReturn(200);
        when(subfolderChildrenResponse.body()).thenReturn("""
            {
              "list": {
                "pagination": { "count": 0, "hasMoreItems": false, "totalItems": 0, "skipCount": 0, "maxItems": 10 },
                "entries": []
              }
            }
            """);

        HttpResponse<InputStream> contentResponse = mock(HttpResponse.class);
        when(contentResponse.statusCode()).thenReturn(200);
        when(contentResponse.body()).thenReturn(new ByteArrayInputStream("Hello World!".getBytes()));

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    HttpResponse.BodyHandler<?> handler = invocation.getArgument(1);

                    String uriStr = req.uri().toString();

                    if (handler == HttpResponse.BodyHandlers.ofInputStream()) {
                        return contentResponse;
                    }

                    if (uriStr.contains("subfolder-1/children")) {
                        return subfolderChildrenResponse;
                    } else {
                        return childrenResponse;
                    }
                });

        connector.setHttpClient(mockHttpClient);

        Flux<RepositoryDocument> scanFlux = connector.scan("-root-");

        StepVerifier.create(scanFlux)
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("file-1");
                    assertThat(doc.uri()).isEqualTo("http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1/nodes/file-1/content");
                    assertThat(doc.acl()).isEqualTo("public");
                    assertThat(doc.metadata().get("name")).containsExactly("file-1.txt");
                    assertThat(doc.metadata().get("cm:title")).containsExactly("File 1 Title");
                    assertThat(doc.metadata().get("cm:description")).containsExactly("File 1 Description");
                    assertThat(doc.metadata().get("alfresco_aspects")).containsExactly("cm:titled");
                    try (InputStream is = doc.contentStream()) {
                        byte[] bytes = is.readAllBytes();
                        assertThat(new String(bytes)).isEqualTo("Hello World!");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testIncludeSubfoldersFalseDoesNotRecurse() throws Exception {
        AlfrescoRepositoryConnector flatConnector = new AlfrescoRepositoryConnector(
                "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1",
                "admin", "admin", 10,
                AlfrescoCrawlMode.FOLDER,
                "/", "", "",
                false, // includeSubfolders = false
                Set.of(),
                "TYPE:'cm:content'", "afts",
                true, true, 52428800L, Set.of(), 30
        );

        HttpResponse<String> rootResponse = mock(HttpResponse.class);
        when(rootResponse.statusCode()).thenReturn(200);
        when(rootResponse.body()).thenReturn("""
            {
              "list": {
                "pagination": { "count": 2, "hasMoreItems": false, "totalItems": 2, "skipCount": 0, "maxItems": 10 },
                "entries": [
                  {
                    "entry": {
                      "id": "folder-a",
                      "name": "Folder A",
                      "isFolder": true,
                      "isFile": false,
                      "nodeType": "cm:folder"
                    }
                  },
                  {
                    "entry": {
                      "id": "file-root",
                      "name": "root-file.txt",
                      "isFolder": false,
                      "isFile": true,
                      "nodeType": "cm:content",
                      "content": { "mimeType": "text/plain", "sizeInBytes": 5 }
                    }
                  }
                ]
              }
            }
            """);

        HttpResponse<InputStream> contentResponse = mock(HttpResponse.class);
        when(contentResponse.statusCode()).thenReturn(200);
        when(contentResponse.body()).thenReturn(new ByteArrayInputStream("data".getBytes()));

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<?> handler = invocation.getArgument(1);
                    if (handler == HttpResponse.BodyHandlers.ofInputStream()) {
                        return contentResponse;
                    }
                    return rootResponse;
                });

        flatConnector.setHttpClient(mockHttpClient);

        Flux<RepositoryDocument> scanFlux = flatConnector.scan("/");

        StepVerifier.create(scanFlux)
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("file-root");
                    assertThat(doc.metadata().get("name")).containsExactly("root-file.txt");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAftsSearchApiQueryScan() throws Exception {
        AlfrescoRepositoryConnector queryConnector = new AlfrescoRepositoryConnector(
                "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1",
                "admin", "admin", 10,
                AlfrescoCrawlMode.QUERY,
                "/", "", "", true, Set.of(),
                "TYPE:'cm:content' AND @cm:title:'Invoice*'", "afts",
                true, true, 52428800L, Set.of(), 30
        );

        HttpResponse<String> searchResponse = mock(HttpResponse.class);
        when(searchResponse.statusCode()).thenReturn(200);
        when(searchResponse.body()).thenReturn("""
            {
              "list": {
                "pagination": { "count": 1, "hasMoreItems": false, "totalItems": 1, "skipCount": 0, "maxItems": 10 },
                "entries": [
                  {
                    "entry": {
                      "id": "invoice-101",
                      "name": "invoice_101.pdf",
                      "isFolder": false,
                      "isFile": true,
                      "nodeType": "cm:content",
                      "content": { "mimeType": "application/pdf", "sizeInBytes": 1024 },
                      "properties": { "cm:title": "Invoice 101" },
                      "aspectNames": ["cm:titled"],
                      "permissions": {
                        "isInheritanceEnabled": false,
                        "locallySet": [
                          { "authorityId": "accountant", "name": "Consumer", "accessStatus": "ALLOWED" },
                          { "authorityId": "GROUP_FINANCE", "name": "Editor", "accessStatus": "ALLOWED" }
                        ]
                      }
                    }
                  }
                ]
              }
            }
            """);

        HttpResponse<InputStream> contentResponse = mock(HttpResponse.class);
        when(contentResponse.statusCode()).thenReturn(200);
        when(contentResponse.body()).thenReturn(new ByteArrayInputStream("PDF-BYTE-STREAM".getBytes()));

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    HttpResponse.BodyHandler<?> handler = invocation.getArgument(1);
                    if (handler == HttpResponse.BodyHandlers.ofInputStream()) {
                        return contentResponse;
                    }
                    assertThat(req.uri().toString()).contains("/public/search/versions/1/search");
                    assertThat(req.method()).isEqualTo("POST");
                    return searchResponse;
                });

        queryConnector.setHttpClient(mockHttpClient);

        Flux<RepositoryDocument> scanFlux = queryConnector.scan("TYPE:'cm:content'");

        StepVerifier.create(scanFlux)
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("invoice-101");
                    assertThat(doc.metadata().get("name")).containsExactly("invoice_101.pdf");
                    assertThat(doc.metadata().get("alfresco_identity_users")).containsExactly("accountant");
                    assertThat(doc.metadata().get("alfresco_identity_groups")).containsExactly("GROUP_FINANCE");
                    assertThat(doc.acl()).contains("accountant", "GROUP_FINANCE");
                    assertThat(doc.security().permissions()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMetadataOnlyWhenIncludeContentStreamIsFalse() throws Exception {
        AlfrescoRepositoryConnector metaOnlyConnector = new AlfrescoRepositoryConnector(
                "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1",
                "admin", "admin", 10,
                AlfrescoCrawlMode.FOLDER,
                "/", "", "", true, Set.of(),
                "TYPE:'cm:content'", "afts",
                true,
                false, // includeContentStream = false
                52428800L, Set.of(), 30
        );

        HttpResponse<String> rootResponse = mock(HttpResponse.class);
        when(rootResponse.statusCode()).thenReturn(200);
        when(rootResponse.body()).thenReturn("""
            {
              "list": {
                "pagination": { "count": 1, "hasMoreItems": false, "totalItems": 1, "skipCount": 0, "maxItems": 10 },
                "entries": [
                  {
                    "entry": {
                      "id": "doc-meta",
                      "name": "metadata-only.docx",
                      "isFolder": false,
                      "isFile": true,
                      "nodeType": "cm:content",
                      "content": { "mimeType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "sizeInBytes": 50000000 }
                    }
                  }
                ]
              }
            }
            """);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(rootResponse);

        metaOnlyConnector.setHttpClient(mockHttpClient);

        Flux<RepositoryDocument> scanFlux = metaOnlyConnector.scan("-root-");

        StepVerifier.create(scanFlux)
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("doc-meta");
                    assertThat(doc.contentStream()).isNull();
                    assertThat(doc.metadata().get("sizeInBytes")).containsExactly("50000000");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMaxContentSizeBytesThresholdExceeded() throws Exception {
        AlfrescoRepositoryConnector sizeLimitedConnector = new AlfrescoRepositoryConnector(
                "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1",
                "admin", "admin", 10,
                AlfrescoCrawlMode.FOLDER,
                "/", "", "", true, Set.of(),
                "TYPE:'cm:content'", "afts",
                true, true,
                100L, // maxContentSizeBytes = 100 bytes
                Set.of(), 30
        );

        HttpResponse<String> rootResponse = mock(HttpResponse.class);
        when(rootResponse.statusCode()).thenReturn(200);
        when(rootResponse.body()).thenReturn("""
            {
              "list": {
                "pagination": { "count": 1, "hasMoreItems": false, "totalItems": 1, "skipCount": 0, "maxItems": 10 },
                "entries": [
                  {
                    "entry": {
                      "id": "large-video",
                      "name": "video.mp4",
                      "isFolder": false,
                      "isFile": true,
                      "nodeType": "cm:content",
                      "content": { "mimeType": "video/mp4", "sizeInBytes": 9999999 }
                    }
                  }
                ]
              }
            }
            """);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(rootResponse);

        sizeLimitedConnector.setHttpClient(mockHttpClient);

        Flux<RepositoryDocument> scanFlux = sizeLimitedConnector.scan("-root-");

        StepVerifier.create(scanFlux)
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("large-video");
                    assertThat(doc.contentStream()).isNull(); // Skipped because size exceeds 100 bytes
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMimeTypeFilterOnlyIncludesMatchingTypes() throws Exception {
        AlfrescoRepositoryConnector pdfOnlyConnector = new AlfrescoRepositoryConnector(
                "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1",
                "admin", "admin", 10,
                AlfrescoCrawlMode.FOLDER,
                "/", "", "", true, Set.of(),
                "TYPE:'cm:content'", "afts",
                true, true, 52428800L,
                Set.of("application/pdf"), // Only PDF
                30
        );

        HttpResponse<String> rootResponse = mock(HttpResponse.class);
        when(rootResponse.statusCode()).thenReturn(200);
        when(rootResponse.body()).thenReturn("""
            {
              "list": {
                "pagination": { "count": 2, "hasMoreItems": false, "totalItems": 2, "skipCount": 0, "maxItems": 10 },
                "entries": [
                  {
                    "entry": {
                      "id": "ignored-txt",
                      "name": "notes.txt",
                      "isFolder": false,
                      "isFile": true,
                      "nodeType": "cm:content",
                      "content": { "mimeType": "text/plain", "sizeInBytes": 100 }
                    }
                  },
                  {
                    "entry": {
                      "id": "kept-pdf",
                      "name": "manual.pdf",
                      "isFolder": false,
                      "isFile": true,
                      "nodeType": "cm:content",
                      "content": { "mimeType": "application/pdf", "sizeInBytes": 200 }
                    }
                  }
                ]
              }
            }
            """);

        HttpResponse<InputStream> contentResponse = mock(HttpResponse.class);
        when(contentResponse.statusCode()).thenReturn(200);
        when(contentResponse.body()).thenReturn(new ByteArrayInputStream("PDF-CONTENT".getBytes()));

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<?> handler = invocation.getArgument(1);
                    if (handler == HttpResponse.BodyHandlers.ofInputStream()) {
                        return contentResponse;
                    }
                    return rootResponse;
                });

        pdfOnlyConnector.setHttpClient(mockHttpClient);

        Flux<RepositoryDocument> scanFlux = pdfOnlyConnector.scan("-root-");

        StepVerifier.create(scanFlux)
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("kept-pdf");
                    assertThat(doc.metadata().get("name")).containsExactly("manual.pdf");
                })
                .verifyComplete();
    }
}
