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
package org.opencrawling.solr;

import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.solr.config.SolrOutputProperties;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeast;

class SolrOutputConnectorTest {

    private SolrClient solrClient;
    private SolrOutputProperties properties;
    private EmbeddingModel embeddingModel;
    private SolrOutputConnector connector;

    @BeforeEach
    void setUp() {
        solrClient = Mockito.mock(SolrClient.class);
        properties = new SolrOutputProperties(
                "standalone",
                "http://localhost:8983/solr",
                "localhost:2181",
                "enterprise_kb",
                1024,
                "cosine",
                "FLOAT32",
                "none",
                16,
                100,
                100,
                1000,
                "solr",
                "password"
        );
        embeddingModel = Mockito.mock(EmbeddingModel.class);
        Mockito.when(embeddingModel.embed(any(org.springframework.ai.document.Document.class)))
                .thenReturn(new float[1024]);

        connector = new SolrOutputConnector(solrClient, properties, embeddingModel);
    }

    @Test
    void testGetName() {
        assertEquals("SolrOutputConnector", connector.getName());
    }

    @Test
    void testSendDocument() throws Exception {
        RepositoryDocument doc = new RepositoryDocument(
                "doc-1",
                "file:///tmp/test.txt",
                new ByteArrayInputStream("Sample text content for Solr indexing.".getBytes()),
                Map.of("mimeType", List.of("text/plain")),
                "public-acl",
                Instant.now()
        );

        connector.send(doc).block();

        verify(solrClient).add(eq("enterprise_kb"), any(List.class), eq(1000));
    }

    /** #333: a missing model, a failed embedding call or an empty vector fails the document, and nothing is written. */
    @Test
    void aDocumentWithoutItsRealVectorsIsNotStored() throws Exception {
        assertThatThrownBy(() -> new SolrOutputConnector(solrClient, properties, (EmbeddingModel) null).send(failFastDocument("doc-333-a", "Text that needs its vector.")).block())
                .hasStackTraceContaining("No embedding model is configured");

        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class))).thenReturn(new float[0]);
        assertThatThrownBy(() -> connector.send(failFastDocument("doc-333-b", "Text that needs its vector.")).block())
                .hasStackTraceContaining("returned no vector");

        doThrow(new RuntimeException("model down")).when(embeddingModel).embed(any(org.springframework.ai.document.Document.class));
        doThrow(new RuntimeException("model down")).when(embeddingModel).embed(anyString());
        assertThatThrownBy(() -> connector.send(failFastDocument("doc-333-c", "Text that needs its vector.")).block())
                .hasStackTraceContaining("model down");

        verify(solrClient, never()).add(anyString(), any(List.class), anyInt());
    }

    private static org.opencrawling.core.document.RepositoryDocument failFastDocument(String id, String text) {
        return new org.opencrawling.core.document.RepositoryDocument(id, "file://tmp/" + id + ".txt",
                new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.util.Map.of("mimeType", java.util.List.of("text/plain")), "",
                org.opencrawling.core.security.SecurityConfig.createPublic(), java.time.Instant.now());
    }
}
