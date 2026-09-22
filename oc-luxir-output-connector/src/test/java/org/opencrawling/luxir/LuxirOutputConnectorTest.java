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
package org.opencrawling.luxir;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.luxir.client.LuxirClient;
import org.opencrawling.luxir.config.LuxirOutputProperties;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

class LuxirOutputConnectorTest {

    private LuxirClient luxirClient;
    private LuxirOutputProperties properties;
    private EmbeddingModel embeddingModel;
    private LuxirOutputConnector connector;

    @BeforeEach
    void setUp() {
        luxirClient = Mockito.mock(LuxirClient.class);
        properties = new LuxirOutputProperties(
                "http://localhost:9400",
                "opencrawling",
                "embedding_v",
                384,
                "cosine",
                true,
                0,
                30
        );
        embeddingModel = Mockito.mock(EmbeddingModel.class);
        Mockito.when(embeddingModel.embed(any(org.springframework.ai.document.Document.class)))
                .thenReturn(new float[384]);

        connector = new LuxirOutputConnector(luxirClient, properties, embeddingModel);
    }

    @Test
    void testGetName() {
        assertEquals("LuxirOutputConnector", connector.getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSendDocument() throws Exception {
        RepositoryDocument doc = new RepositoryDocument(
                "doc-luxir-1",
                "file:///tmp/test.txt",
                new ByteArrayInputStream("Sample text content for Luxir indexing.".getBytes()),
                Map.of("mimeType", List.of("text/plain"), "title", List.of("Sample Title")),
                "ROLE_ENGINEERING,user:alice",
                Instant.now()
        );

        connector.send(doc).block();

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(luxirClient).updateDocuments(eq("opencrawling"), captor.capture(), eq(true));

        List<Map<String, Object>> capturedDocs = captor.getValue();
        assertEquals(1, capturedDocs.size());

        Map<String, Object> indexedDoc = capturedDocs.getFirst();
        assertEquals("doc-luxir-1", indexedDoc.get("doc_id_s"));
        assertEquals("Sample Title", indexedDoc.get("title_t"));
        assertEquals("Sample text content for Luxir indexing.", indexedDoc.get("text_t"));

        List<String> acls = (List<String>) indexedDoc.get("acl_ss");
        assertEquals(2, acls.size());
        assertTrue(acls.contains("ROLE_ENGINEERING"));
        assertTrue(acls.contains("user:alice"));

        List<Float> embedding = (List<Float>) indexedDoc.get("embedding_v");
        assertEquals(384, embedding.size());
    }
}
