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
import java.util.List;

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
                "http://localhost:8080/alfresco/api",
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
        // Mock responses:
        // 1. Root children lookup: returns one subfolder ("subfolder-1") and one file ("file-1.txt")
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
                      }
                    }
                  }
                ]
              }
            }
            """);

        // 2. Subfolder children lookup: returns no entries (empty list)
        HttpResponse<String> subfolderChildrenResponse = mock(HttpResponse.class);
        when(subfolderChildrenResponse.statusCode()).thenReturn(200);
        when(subfolderChildrenResponse.body()).thenReturn("""
            {
              "list": {
                "pagination": {
                  "count": 0,
                  "hasMoreItems": false,
                  "totalItems": 0,
                  "skipCount": 0,
                  "maxItems": 10
                },
                "entries": []
              }
            }
            """);

        // 3. Content download response
        HttpResponse<InputStream> contentResponse = mock(HttpResponse.class);
        when(contentResponse.statusCode()).thenReturn(200);
        when(contentResponse.body()).thenReturn(new ByteArrayInputStream("Hello World!".getBytes()));

        // Stub HttpClient calls based on the target URL pattern
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
                    assertThat(doc.uri()).isEqualTo("http://localhost:8080/alfresco/api/nodes/file-1/content");
                    assertThat(doc.acl()).isEqualTo("public");
                    assertThat(doc.metadata().get("name")).containsExactly("file-1.txt");
                    assertThat(doc.metadata().get("cm:title")).containsExactly("File 1 Title");
                    assertThat(doc.metadata().get("cm:description")).containsExactly("File 1 Description");
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
    void testScanSkipsDataDictionaryFolder() throws Exception {
        HttpResponse<String> rootResponse = mock(HttpResponse.class);
        when(rootResponse.statusCode()).thenReturn(200);
        when(rootResponse.body()).thenReturn("""
            {
              "list": {
                "pagination": { "count": 2, "hasMoreItems": false, "totalItems": 2, "skipCount": 0, "maxItems": 10 },
                "entries": [
                  {
                    "entry": {
                      "id": "dict-id",
                      "name": "Data Dictionary",
                      "isFolder": true,
                      "isFile": false,
                      "nodeType": "cm:folder"
                    }
                  },
                  {
                    "entry": {
                      "id": "repo-doc-1",
                      "name": "spec.txt",
                      "isFolder": false,
                      "isFile": true,
                      "nodeType": "cm:content",
                      "content": { "mimeType": "text/plain", "sizeInBytes": 10 }
                    }
                  }
                ]
              }
            }
            """);

        HttpResponse<InputStream> contentResponse = mock(HttpResponse.class);
        when(contentResponse.statusCode()).thenReturn(200);
        when(contentResponse.body()).thenReturn(new ByteArrayInputStream("Repo content".getBytes()));

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<?> handler = invocation.getArgument(1);
                    if (handler == HttpResponse.BodyHandlers.ofInputStream()) {
                        return contentResponse;
                    }
                    return rootResponse;
                });

        connector.setHttpClient(mockHttpClient);

        Flux<RepositoryDocument> scanFlux = connector.scan("-root-");

        StepVerifier.create(scanFlux)
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo("repo-doc-1");
                    assertThat(doc.metadata().get("name")).containsExactly("spec.txt");
                })
                .verifyComplete();
    }
}
