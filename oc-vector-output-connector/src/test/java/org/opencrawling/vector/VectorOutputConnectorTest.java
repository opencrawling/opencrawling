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
package org.opencrawling.vector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.document.RepositoryDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VectorOutputConnectorTest {

    private VectorStore vectorStore;
    private EmbeddingModel embeddingModel;
    private VectorOutputConnector connector;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        embeddingModel = mock(EmbeddingModel.class);
        connector = new VectorOutputConnector(vectorStore, embeddingModel);
    }

    @Test
    void testSendComputesEmbeddingAndAddsToStore() {
        byte[] content = "Hello Spring AI vector store ingestion path test.".getBytes();
        RepositoryDocument repoDoc = mock(RepositoryDocument.class);
        when(repoDoc.id()).thenReturn("doc-1");
        when(repoDoc.contentStream()).thenReturn(new ByteArrayInputStream(content));
        when(repoDoc.metadata()).thenReturn(Map.of("mimeType", List.of("text/plain")));
        when(repoDoc.uri()).thenReturn("file://doc-1");
        when(repoDoc.acl()).thenReturn("read");
        when(repoDoc.lastModified()).thenReturn(Instant.now());

        float[] mockEmbedding = new float[]{0.12f, 0.34f, 0.56f};
        when(embeddingModel.embed(anyString())).thenReturn(mockEmbedding);

        connector.send(repoDoc).block();

        verify(embeddingModel, atLeastOnce()).embed(anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        List<Document> addedChunks = captor.getValue();
        assertFalse(addedChunks.isEmpty());
        assertNotNull(addedChunks.get(0).getMetadata().get("embedding"));
        assertArrayEquals(mockEmbedding, (float[]) addedChunks.get(0).getMetadata().get("embedding"));
    }
}
