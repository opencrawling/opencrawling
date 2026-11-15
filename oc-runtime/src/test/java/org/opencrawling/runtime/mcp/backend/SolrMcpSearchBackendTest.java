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
package org.opencrawling.runtime.mcp.backend;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.opencrawling.solr.SolrConstants;
import org.opencrawling.solr.config.SolrOutputProperties;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.IOException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SolrMcpSearchBackendTest {

    private SolrClient solrClient;
    private SolrOutputProperties properties;
    private EmbeddingModel embeddingModel;
    private SolrMcpSearchBackend backend;

    @BeforeEach
    void setUp() {
        solrClient = mock(SolrClient.class);
        properties = new SolrOutputProperties(
                "standalone", "http://localhost:8983/solr", "", "enterprise_kb",
                1024, "cosine", "FLOAT32", "none", 16, 100, 100, 1000, null, null
        );
        embeddingModel = mock(EmbeddingModel.class);
        backend = new SolrMcpSearchBackend(solrClient, properties, embeddingModel, null);
    }

    @Test
    void testBackendType() {
        assertThat(backend.backendType()).isEqualTo("solr");
    }

    @Test
    void testSecureVectorSearch_success() throws SolrServerException, IOException {
        when(embeddingModel.embed("solr query")).thenReturn(new float[]{0.1f, 0.2f});

        SolrDocument doc1 = new SolrDocument();
        doc1.addField(SolrConstants.FIELD_ID, "chunk-1");
        doc1.addField(SolrConstants.FIELD_URI, "file:///solr.txt");
        doc1.addField(SolrConstants.FIELD_TEXT, "Solr Content");
        doc1.addField(SolrConstants.FIELD_ACL, "engineering");
        doc1.addField("score", 0.95);

        SolrDocument doc2 = new SolrDocument();
        doc2.addField(SolrConstants.FIELD_ID, "chunk-2");
        doc2.addField(SolrConstants.FIELD_URI, "file:///finance.txt");
        doc2.addField(SolrConstants.FIELD_TEXT, "Finance Content");
        doc2.addField(SolrConstants.FIELD_ACL, "finance");
        doc2.addField("score", 0.90);

        SolrDocumentList docs = new SolrDocumentList();
        docs.add(doc1);
        docs.add(doc2);

        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(docs);
        when(solrClient.query(eq("enterprise_kb"), any(SolrParams.class))).thenReturn(response);

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch(
                "solr query", "user@enterprise.com", List.of("engineering"), 5, 0.0, null
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("chunk-1");
        assertThat(results.get(0).acl()).isEqualTo("engineering");
    }

    @Test
    void testGetDocumentContent_success() throws SolrServerException, IOException {
        SolrDocument doc = new SolrDocument();
        doc.addField(SolrConstants.FIELD_ID, "chunk-1");
        doc.addField(SolrConstants.FIELD_URI, "file:///doc.txt");
        doc.addField(SolrConstants.FIELD_TEXT, "Doc Content");
        doc.addField(SolrConstants.FIELD_ACL, "public");

        SolrDocumentList docs = new SolrDocumentList();
        docs.add(doc);

        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(docs);
        when(solrClient.query(eq("enterprise_kb"), any(SolrParams.class))).thenReturn(response);

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///doc.txt", "user", List.of(), null);
        assertThat(result.id()).isEqualTo("chunk-1");
        assertThat(result.uri()).isEqualTo("file:///doc.txt");
    }

    @Test
    void testGetDocumentContent_notFoundThrows() throws SolrServerException, IOException {
        SolrDocumentList docs = new SolrDocumentList();
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(docs);
        when(solrClient.query(eq("enterprise_kb"), any(SolrParams.class))).thenReturn(response);

        assertThatThrownBy(() -> backend.getDocumentContent("file:///missing.txt", "user", List.of(), null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testListAccessibleSources() throws SolrServerException, IOException {
        SolrDocument doc = new SolrDocument();
        doc.addField(SolrConstants.FIELD_ID, "chunk-1");
        doc.addField(SolrConstants.FIELD_URI, "file:///doc.txt");
        doc.addField(SolrConstants.FIELD_ACL, "public");

        SolrDocumentList docs = new SolrDocumentList();
        docs.add(doc);

        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(docs);
        when(solrClient.query(eq("enterprise_kb"), any(SolrParams.class))).thenReturn(response);

        List<Map<String, Object>> sources = backend.listAccessibleSources("user", List.of(), null);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("id")).isEqualTo("chunk-1");
    }
}
