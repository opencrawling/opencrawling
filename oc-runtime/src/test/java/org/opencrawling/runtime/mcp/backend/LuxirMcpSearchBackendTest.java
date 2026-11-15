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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.luxir.LuxirConstants;
import org.opencrawling.luxir.client.LuxirClient;
import org.opencrawling.luxir.config.LuxirOutputProperties;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.IOException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LuxirMcpSearchBackendTest {

    private LuxirClient luxirClient;
    private LuxirOutputProperties properties;
    private EmbeddingModel embeddingModel;
    private LuxirMcpSearchBackend backend;

    @BeforeEach
    void setUp() {
        luxirClient = mock(LuxirClient.class);
        properties = new LuxirOutputProperties(
                "http://localhost:9400", "opencrawling", "embedding_v", 1024,
                "cosine", true, 0, 30
        );
        embeddingModel = mock(EmbeddingModel.class);
        backend = new LuxirMcpSearchBackend(luxirClient, properties, embeddingModel, null);
    }

    @Test
    void testBackendType() {
        assertThat(backend.backendType()).isEqualTo("luxir");
    }

    @Test
    void testSecureVectorSearch_success() throws IOException, InterruptedException {
        when(embeddingModel.embed("luxir query")).thenReturn(new float[]{0.1f, 0.2f});

        Map<String, Object> doc1 = Map.of(
                LuxirConstants.FIELD_ID, "chunk-1",
                LuxirConstants.FIELD_URI, "file:///luxir.txt",
                LuxirConstants.FIELD_TEXT, "Luxir Content",
                LuxirConstants.FIELD_ACL, List.of("engineering"),
                "_score", 0.95
        );

        Map<String, Object> doc2 = Map.of(
                LuxirConstants.FIELD_ID, "chunk-2",
                LuxirConstants.FIELD_URI, "file:///finance.txt",
                LuxirConstants.FIELD_TEXT, "Finance Content",
                LuxirConstants.FIELD_ACL, List.of("finance"),
                "_score", 0.90
        );

        when(luxirClient.search(eq("opencrawling"), any()))
                .thenReturn(new LuxirClient.LuxirSearchResult(2, List.of(doc1, doc2)));

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch(
                "luxir query", "user@enterprise.com", List.of("engineering"), 5, 0.0, null
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("chunk-1");
    }

    @Test
    void testGetDocumentContent_success() throws IOException, InterruptedException {
        Map<String, Object> doc = Map.of(
                LuxirConstants.FIELD_ID, "chunk-1",
                LuxirConstants.FIELD_URI, "file:///doc.txt",
                LuxirConstants.FIELD_TEXT, "Doc Content",
                LuxirConstants.FIELD_ACL, "public"
        );

        when(luxirClient.search(eq("opencrawling"), any()))
                .thenReturn(new LuxirClient.LuxirSearchResult(1, List.of(doc)));

        McpVectorServer.DocumentDetailsResult result = backend.getDocumentContent("file:///doc.txt", "user", List.of(), null);
        assertThat(result.id()).isEqualTo("chunk-1");
        assertThat(result.uri()).isEqualTo("file:///doc.txt");
    }

    @Test
    void testGetDocumentContent_notFoundThrows() throws IOException, InterruptedException {
        when(luxirClient.search(eq("opencrawling"), any()))
                .thenReturn(new LuxirClient.LuxirSearchResult(0, Collections.emptyList()));

        assertThatThrownBy(() -> backend.getDocumentContent("file:///missing.txt", "user", List.of(), null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testListAccessibleSources() throws IOException, InterruptedException {
        Map<String, Object> doc = Map.of(
                LuxirConstants.FIELD_ID, "chunk-1",
                LuxirConstants.FIELD_URI, "file:///doc.txt",
                LuxirConstants.FIELD_ACL, "public"
        );

        when(luxirClient.search(eq("opencrawling"), any()))
                .thenReturn(new LuxirClient.LuxirSearchResult(1, List.of(doc)));

        List<Map<String, Object>> sources = backend.listAccessibleSources("user", List.of(), null);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("id")).isEqualTo("chunk-1");
    }
}
