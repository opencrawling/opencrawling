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
package org.opencrawling.milvus;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeast;

class MilvusOutputConnectorTest {

    private MilvusOutputConnector connector;

    @Mock
    private MilvusClientV2 milvusClient;

    @Mock
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        connector = new MilvusOutputConnector(milvusClient, embeddingModel);
    }

    @Test
    void testGetName() {
        assertThat(connector.getName()).isEqualTo("MilvusOutputConnector");
    }

    @Test
    void testSendDocument() throws Exception {
        // Setup mock input stream
        String contentText = "This is a test content that is long enough to be indexed in the vector store.";
        ByteArrayInputStream bais = new ByteArrayInputStream(contentText.getBytes(StandardCharsets.UTF_8));

        // Setup mock metadata
        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("mimeType", List.of("text/plain"));
        metadata.put("title", List.of("Test Title"));

        // Setup mock security
        SecurityConfig securityConfig = new SecurityConfig(true, List.of(
                new PermissionRule("user1", "user", "User One", "read"),
                new PermissionRule("group1", "group", "Group One", "read"),
                new PermissionRule("baduser", "user", "Bad User", "deny")
        ));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-123",
                "file://tmp/doc-123.txt",
                bais,
                metadata,
                "acl-123",
                securityConfig,
                Instant.now()
        );

        // Mock embedding model call
        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class)))
                .thenReturn(new float[]{0.1f, 0.2f});

        // Trigger send
        connector.send(doc).block();

        // Capture insert request
        ArgumentCaptor<InsertReq> captor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClient, times(1)).insert(captor.capture());

        InsertReq capturedReq = captor.getValue();
        assertThat(capturedReq.getCollectionName()).isEqualTo("enterprise_kb");
        assertThat(capturedReq.getData()).isNotEmpty();

        Object dataRow = capturedReq.getData().get(0);
        assertThat(dataRow.toString()).contains("doc-123");
        assertThat(dataRow.toString()).contains(MilvusConstants.FIELD_SECURITY_ALLOWED_READ);
        assertThat(dataRow.toString()).contains(MilvusConstants.FIELD_SECURITY_DENIED_READ);
        assertThat(dataRow.toString()).contains(MilvusConstants.FIELD_SECURITY_INHERITANCE);
    }

    /** #333: a missing model, a failed embedding call or an empty vector fails the document, and nothing is written. */
    @Test
    void aDocumentWithoutItsRealVectorsIsNotStored() throws Exception {
        assertThatThrownBy(() -> new MilvusOutputConnector(milvusClient, (EmbeddingModel) null).send(failFastDocument("doc-333-a", "Text that needs its vector.")).block())
                .hasStackTraceContaining("No embedding model is configured");

        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class))).thenReturn(new float[0]);
        assertThatThrownBy(() -> connector.send(failFastDocument("doc-333-b", "Text that needs its vector.")).block())
                .hasStackTraceContaining("returned no vector");

        doThrow(new RuntimeException("model down")).when(embeddingModel).embed(any(org.springframework.ai.document.Document.class));
        doThrow(new RuntimeException("model down")).when(embeddingModel).embed(anyString());
        assertThatThrownBy(() -> connector.send(failFastDocument("doc-333-c", "Text that needs its vector.")).block())
                .hasStackTraceContaining("model down");

        verify(milvusClient, never()).insert(any(InsertReq.class));
    }

    private static org.opencrawling.core.document.RepositoryDocument failFastDocument(String id, String text) {
        return new org.opencrawling.core.document.RepositoryDocument(id, "file://tmp/" + id + ".txt",
                new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.util.Map.of("mimeType", java.util.List.of("text/plain")), "",
                org.opencrawling.core.security.SecurityConfig.createPublic(), java.time.Instant.now());
    }
}
