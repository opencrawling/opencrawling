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

import org.junit.jupiter.api.Test;
import org.opencrawling.vespa.config.VespaOutputProperties;

import java.util.List;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.opencrawling.runtime.mcp.McpVectorServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VespaMcpSearchBackendTest {

    private static final VespaOutputProperties UNREACHABLE = new VespaOutputProperties(
            "http://127.0.0.1:59999", "opencrawling", "opencrawling_chunk", 1024, 2, false, null, null, null);

    private final VespaMcpSearchBackend backend = new VespaMcpSearchBackend(UNREACHABLE, null, null);

    @Test
    void testBackendType() {
        assertThat(backend.backendType()).isEqualTo("vespa");
    }

    @Test
    void testSecureVectorSearchUnreachableThrows() {
        assertThatThrownBy(() -> backend.secureVectorSearch("query", "user", List.of(), 5, 0.0, null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testGetDocumentContentUnreachableThrows() {
        assertThatThrownBy(() -> backend.getDocumentContent("file:///doc.txt", "user", List.of(), null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testListAccessibleSourcesUnreachableThrows() {
        assertThatThrownBy(() -> backend.listAccessibleSources("user", List.of(), null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void getDocumentContentJoinsTheDocumentsChunksInOrder() throws Exception {
        // Vespa schemas are strict: the chunk's position travels inside metadata_json.
        String body = """
                {"root": {"fields": {"totalCount": 2}, "children": [
                  {"relevance": 1.0, "fields": {"chunk_id": "c1", "uri": "file:///doc.txt", "text": "second",
                    "acl": "public", "security_allowed_read": ["public"],
                    "metadata_json": "{\\"documentId\\":\\"file:///doc.txt\\",\\"chunk_index\\":1,\\"total_chunks\\":2}"}},
                  {"relevance": 1.0, "fields": {"chunk_id": "c0", "uri": "file:///doc.txt", "text": "first",
                    "acl": "public", "security_allowed_read": ["public"],
                    "metadata_json": "{\\"documentId\\":\\"file:///doc.txt\\",\\"chunk_index\\":0,\\"total_chunks\\":2}"}}
                ]}}
                """;
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer vespa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vespa.createContext("/search/", exchange -> {
            query.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        vespa.start();
        try {
            VespaMcpSearchBackend local = new VespaMcpSearchBackend(new VespaOutputProperties(
                    "http://127.0.0.1:" + vespa.getAddress().getPort(), "opencrawling", "opencrawling_chunk", 1024, 2,
                    false, null, null, null), null, null);

            McpVectorServer.DocumentDetailsResult result = local.getDocumentContent("file:///doc.txt", "user", List.of(), null);

            assertThat(result.content()).isEqualTo("first\nsecond");
            assertThat(result.id()).isEqualTo("file:///doc.txt");
            assertThat(result.totalChunks()).isEqualTo(2);
            assertThat(result.complete()).isTrue();
            assertThat(query.get()).contains("\"hits\":" + (GatedMcpSearchBackend.DEFAULT_DOCUMENT_MAX_CHUNKS + 1));
        } finally {
            vespa.stop(0);
        }
    }
}
