/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.openarch.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.openarch.output.client.OpenArchClient;
import org.opencrawling.openarch.output.config.OpenArchOutputProperties;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class OpenArchOutputConnectorTest {

    private OpenArchClient mockClient;
    private OpenArchOutputProperties properties;
    private OpenArchOutputConnector connector;
    private ObjectMapper mapper = new ObjectMapper();

    private org.springframework.ai.embedding.EmbeddingModel embeddingModel;

    @BeforeEach
    public void setup() throws Exception {
        mockClient = mock(OpenArchClient.class);
        properties = new OpenArchOutputProperties();
        properties.setEndpointUrl("http://localhost:8080");
        properties.setRepositoryId("default");
        properties.setRootFolderId("00000000-0000-0000-0000-000000000000");

        ObjectNode uploadRes = mapper.createObjectNode();
        uploadRes.put("id", "blob-uuid-1234");
        when(mockClient.uploadBlob(any(InputStream.class), anyString(), anyString())).thenReturn(uploadRes);

        ObjectNode docRes = mapper.createObjectNode();
        docRes.put("id", "doc-uuid-5678");
        when(mockClient.createOrUpdateDocument(anyString(), anyString(), anyMap(), anyBoolean())).thenReturn(docRes);

        when(mockClient.ping(anyString())).thenReturn(true);

        embeddingModel = mock(org.springframework.ai.embedding.EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        connector = new OpenArchOutputConnector(properties, mockClient, embeddingModel, null);
    }

    @Test
    public void testConnectorLifecycle() throws Exception {
        assertEquals("OpenArch_Output", connector.getName());
        connector.connect();
        verify(mockClient).ping("default");
        connector.disconnect();
    }

    @Test
    public void testSendDocument() {
        SecurityConfig securityConfig = new SecurityConfig(true, List.of(
                new PermissionRule("user:john", "user", "John", "READ"),
                new PermissionRule("group:engineers", "group", "Engineers", "WRITE")
        ));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-test-1",
                "file:///tmp/doc-test-1",
                new ByteArrayInputStream("Test file content for OpenArch".getBytes()),
                Map.of(
                        "mimeType", List.of("text/plain"),
                        "title", List.of("Test Title"),
                        "department", List.of("R&D")
                ),
                "READ:user:john",
                securityConfig,
                Instant.now()
        );

        StepVerifier.create(connector.send(doc))
                .verifyComplete();

        try {
            verify(mockClient).uploadBlob(any(InputStream.class), eq("doc-test-1"), eq("text/plain"));
            verify(mockClient).uploadBlob(any(InputStream.class), eq("embeddings-doc-uuid-5678.parquet"), eq("application/vnd.apache.parquet"));
            verify(mockClient).createOrUpdateDocument(eq("default"), eq("00000000-0000-0000-0000-000000000000"), anyMap(), eq(true));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** #333: the document is marked SysHasEmbeddings, so it is written only with its vectors. */
    @Test
    public void aDocumentWithoutItsRealVectorsIsNotStored() throws Exception {
        OpenArchOutputConnector noModel = new OpenArchOutputConnector(mockClient, properties);
        StepVerifier.create(noModel.send(document("doc-333-a"))).expectErrorMatches(e ->
                e.getMessage().contains("No embedding model is configured")).verify();

        when(embeddingModel.embed(anyString())).thenReturn(new float[0]);
        StepVerifier.create(connector.send(document("doc-333-b"))).expectErrorMatches(e ->
                e.getMessage().contains("returned no vector")).verify();

        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("model down"));
        StepVerifier.create(connector.send(document("doc-333-c"))).expectErrorMatches(e ->
                e.getMessage().contains("model down")).verify();

        verify(mockClient, org.mockito.Mockito.never()).uploadBlob(any(InputStream.class), anyString(), anyString());
        verify(mockClient, org.mockito.Mockito.never()).createOrUpdateDocument(anyString(), anyString(), anyMap(), anyBoolean());
    }

    /** #333: a failed Parquet vector upload fails the document instead of being logged and dropped. */
    @Test
    public void aFailedVectorUploadFailsTheDocument() throws Exception {
        when(mockClient.uploadBlob(any(InputStream.class), eq("embeddings-doc-uuid-5678.parquet"), anyString()))
                .thenThrow(new java.io.IOException("upload refused"));
        StepVerifier.create(connector.send(document("doc-333-d"))).expectErrorMatches(e ->
                e.getMessage().contains("upload refused")).verify();
    }

    private static RepositoryDocument document(String id) {
        return new RepositoryDocument(id, "file:///tmp/" + id, new ByteArrayInputStream("Content for OpenArch".getBytes()),
                Map.of("mimeType", List.of("text/plain")), "", SecurityConfig.createPublic(), Instant.now());
    }
}
