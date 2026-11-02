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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.openarch.output.client.OpenArchClient;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class OpenArchMcpSearchBackendTest {

    private OpenArchClient mockClient;
    private EmbeddingModel mockEmbeddingModel;
    private OpenArchMcpSearchBackend backend;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        mockClient = mock(OpenArchClient.class);
        mockEmbeddingModel = mock(EmbeddingModel.class);
        when(mockEmbeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        backend = new OpenArchMcpSearchBackend(mockClient, mockEmbeddingModel);
    }

    @Test
    public void testBackendType() {
        assertEquals("openarch", backend.backendType());
    }

    @Test
    public void testSecureVectorSearch() throws Exception {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode embeddings = mapper.createArrayNode();

        ObjectNode emb1 = mapper.createObjectNode();
        emb1.put("id", "emb-1");
        emb1.put("docId", "doc-100");
        emb1.put("text", "Federal acquisition regulations policy documentation");
        emb1.put("score", 0.95);
        emb1.put("acl", "analysts");
        embeddings.add(emb1);

        response.set("embeddings", embeddings);

        when(mockClient.queryEmbedding(anyString(), anyString(), anyList(), anyString(), isNull(), anyInt(), anyLong()))
                .thenReturn(response);

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch(
                "acquisition regulations",
                "analyst_user",
                List.of("analysts"),
                5,
                0.7,
                1024
        );

        assertNotNull(results);
        assertEquals(1, results.size());
        McpVectorServer.DocumentSearchResult res = results.get(0);
        assertEquals("emb-1", res.id());
        assertEquals("openarch://default/doc-100", res.uri());
        assertEquals("Federal acquisition regulations policy documentation", res.content());
        assertEquals("analysts", res.acl());
        assertEquals(0.95, res.score(), 0.001);
    }

    @Test
    public void testSecureVectorSearchUnauthorizedExcludesHits() throws Exception {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode embeddings = mapper.createArrayNode();

        ObjectNode emb1 = mapper.createObjectNode();
        emb1.put("id", "emb-1");
        emb1.put("docId", "doc-100");
        emb1.put("text", "Top secret acquisitions");
        emb1.put("score", 0.95);
        emb1.put("acl", "legal");
        embeddings.add(emb1);

        response.set("embeddings", embeddings);

        when(mockClient.queryEmbedding(anyString(), anyString(), anyList(), anyString(), isNull(), anyInt(), anyLong()))
                .thenReturn(response);

        List<McpVectorServer.DocumentSearchResult> results = backend.secureVectorSearch(
                "acquisitions",
                "outsider_user",
                List.of("engineering"),
                5,
                0.7,
                1024
        );

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    public void testSecureVectorSearchWithSysAcl() throws Exception {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode embeddings = mapper.createArrayNode();

        ObjectNode emb1 = mapper.createObjectNode();
        emb1.put("id", "emb-1");
        emb1.put("docId", "doc-100");
        emb1.put("text", "Enterprise architecture design");
        emb1.put("score", 0.90);

        ArrayNode aces = mapper.createArrayNode();
        ObjectNode ace = mapper.createObjectNode();
        ace.put("permission", "Read");
        ace.put("granted", true);
        ace.putObject("group").put("groupname", "architects");
        aces.add(ace);
        emb1.set("sys_acl", aces);

        embeddings.add(emb1);
        response.set("embeddings", embeddings);

        when(mockClient.queryEmbedding(anyString(), anyString(), anyList(), anyString(), isNull(), anyInt(), anyLong()))
                .thenReturn(response);

        // Allowed identity matches
        List<McpVectorServer.DocumentSearchResult> allowedResults = backend.secureVectorSearch(
                "architecture",
                "dev1",
                List.of("architects"),
                5,
                0.7,
                1024
        );
        assertEquals(1, allowedResults.size());
        assertEquals("group:architects", allowedResults.get(0).acl());

        // Denied identity doesn't match
        List<McpVectorServer.DocumentSearchResult> deniedResults = backend.secureVectorSearch(
                "architecture",
                "dev1",
                List.of("developers"),
                5,
                0.7,
                1024
        );
        assertTrue(deniedResults.isEmpty());
    }

    @Test
    public void testGetDocumentContent() throws Exception {
        ObjectNode docNode = mapper.createObjectNode();
        docNode.put("id", "doc-100");
        docNode.put("sys_name", "Contract_Agreement.pdf");
        docNode.put("sys_title", "Master Services Agreement 2026");
        docNode.put("sys_description", "Standard commercial enterprise contract terms.");
        docNode.put("acl", "legal");

        when(mockClient.getDocument(eq("default"), eq("doc-100"))).thenReturn(docNode);

        McpVectorServer.DocumentDetailsResult details = backend.getDocumentContent(
                "openarch://default/doc-100",
                "analyst_user",
                List.of("legal"),
                1024
        );

        assertNotNull(details);
        assertEquals("doc-100", details.id());
        assertEquals("openarch://default/doc-100", details.uri());
        assertEquals("legal", details.acl());
        assertTrue(details.content().contains("Master Services Agreement 2026"));
        assertTrue(details.content().contains("Standard commercial enterprise contract terms."));
    }

    @Test
    public void testGetDocumentContentAccessDeniedReturnsNull() throws Exception {
        ObjectNode docNode = mapper.createObjectNode();
        docNode.put("id", "doc-secret");
        docNode.put("sys_title", "Confidential");
        docNode.put("acl", "executives");

        when(mockClient.getDocument(eq("default"), eq("doc-secret"))).thenReturn(docNode);

        McpVectorServer.DocumentDetailsResult details = backend.getDocumentContent(
                "openarch://default/doc-secret",
                "intern_user",
                List.of("interns"),
                1024
        );

        assertNull(details);
    }

    @Test
    public void testListAccessibleSources() throws Exception {
        ObjectNode queryResult = mapper.createObjectNode();
        ArrayNode docs = mapper.createArrayNode();

        ObjectNode d1 = mapper.createObjectNode();
        d1.put("id", "doc-1");
        d1.put("sys_name", "Policy.docx");
        d1.put("acl", "public");
        docs.add(d1);

        ObjectNode d2 = mapper.createObjectNode();
        d2.put("id", "doc-2");
        d2.put("sys_name", "Secret_Salaries.xlsx");
        d2.put("acl", "finance");
        docs.add(d2);

        queryResult.set("documents", docs);

        when(mockClient.query(eq("default"), anyString(), anyInt(), anyLong(), isNull())).thenReturn(queryResult);

        // Public user should see only doc-1
        List<Map<String, Object>> sources = backend.listAccessibleSources("user1", List.of(), 1024);
        assertNotNull(sources);
        assertEquals(1, sources.size());
        assertEquals("doc-1", sources.get(0).get("id"));
        assertEquals("Policy.docx", sources.get(0).get("name"));
        assertEquals("openarch://default/doc-1", sources.get(0).get("uri"));
        assertEquals("public", sources.get(0).get("acl"));

        // Finance user should see both doc-1 and doc-2
        List<Map<String, Object>> financeSources = backend.listAccessibleSources("cfo", List.of("finance"), 1024);
        assertNotNull(financeSources);
        assertEquals(2, financeSources.size());
    }
}
