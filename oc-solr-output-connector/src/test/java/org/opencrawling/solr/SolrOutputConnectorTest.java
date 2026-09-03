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
}
