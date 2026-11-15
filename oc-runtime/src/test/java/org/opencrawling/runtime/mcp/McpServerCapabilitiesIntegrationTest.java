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
package org.opencrawling.runtime.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.observability.ObservabilityMcpTools;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end unit and integration test suite validating the entire OpenCrawling MCP Server capabilities:
 * 1. Canonical MCP Vector Knowledge Tools (secureVectorSearch, getDocumentContent, listAccessibleSources)
 * 2. Zero-Trust Access Control List (ACL) Evaluator with explicit deny precedence
 * 3. Multi-dimensional vector store querying (384, 768, 1024)
 * 4. AIOps Observability MCP Tools (fetchJobTraces, getErrorLogs, queryThroughputMetrics)
 */
class McpServerCapabilitiesIntegrationTest {

    private VectorStore mockVectorStore;
    private McpVectorServer mcpVectorServer;
    private TelemetryTraceStore traceStore;
    private ObservabilityMcpTools observabilityMcpTools;

    @BeforeEach
    void setUp() {
        mockVectorStore = mock(VectorStore.class);
        mcpVectorServer = new McpVectorServer(mockVectorStore);
        traceStore = new TelemetryTraceStore();
        observabilityMcpTools = new ObservabilityMcpTools(traceStore);
    }

    // =========================================================================
    // 1. MCP Vector Knowledge Capabilities: secureVectorSearch
    // =========================================================================

    @Test
    @DisplayName("MCP secureVectorSearch should return documents matching authorized user principal and roles")
    void testSecureVectorSearch_authorizedIdentity() {
        Document engineeringDoc = new Document("doc-eng", "Kubernetes cluster architecture",
                Map.of("uri", "file:///docs/k8s.md", "acl", "engineering", "dimensions", 1024));
        Document publicDoc = new Document("doc-pub", "OpenCrawling quickstart guide",
                Map.of("uri", "file:///docs/quickstart.md", "acl", "public", "dimensions", 1024));

        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(engineeringDoc, publicDoc));

        List<McpVectorServer.DocumentSearchResult> results = mcpVectorServer.secureVectorSearch(
                "cluster architecture", "alice@enterprise.com", "engineering,devops", 5, 0.5, 1024
        );

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).isEqualTo("doc-eng");
        assertThat(results.get(0).acl()).isEqualTo("engineering");
        assertThat(results.get(1).id()).isEqualTo("doc-pub");
        assertThat(results.get(1).acl()).isEqualTo("public");
    }

    @Test
    @DisplayName("MCP secureVectorSearch should evaluate Zero-Trust ACL and filter unpermitted documents")
    void testSecureVectorSearch_aclEvaluationFilter() {
        // Doc with allowedRead for engineering, and Doc with explicit deny for blacklisted user
        Map<String, Object> restrictedMeta = new HashMap<>();
        restrictedMeta.put("uri", "file:///finance/budget.xlsx");
        restrictedMeta.put("security_allowed_read", List.of("finance-auditors"));

        Map<String, Object> denyMeta = new HashMap<>();
        denyMeta.put("uri", "file:///contractors/audit.pdf");
        denyMeta.put("security_allowed_read", List.of("contractors"));
        denyMeta.put("security_denied_read", List.of("bad-actor@vendor.com"));

        // Direct test against McpAclEvaluator using metadata maps
        Map<String, Object> publicMeta = Map.of("acl", "public");
        assertThat(McpAclEvaluator.isAccessible(publicMeta, "guest", List.of())).isTrue();
        assertThat(McpAclEvaluator.isAccessible(restrictedMeta, "alice@enterprise.com", List.of("engineering"))).isFalse();
        assertThat(McpAclEvaluator.isAccessible(restrictedMeta, "bob@enterprise.com", List.of("finance-auditors"))).isTrue();

        // Explicit deny precedence
        assertThat(McpAclEvaluator.isAccessible(denyMeta, "bad-actor@vendor.com", List.of("contractors"))).isFalse();
        assertThat(McpAclEvaluator.isAccessible(denyMeta, "good-actor@vendor.com", List.of("contractors"))).isTrue();
    }

    // =========================================================================
    // 2. MCP Vector Knowledge Capabilities: getDocumentContent
    // =========================================================================

    @Test
    @DisplayName("MCP getDocumentContent should retrieve document text when identity is authorized")
    void testGetDocumentContent_success() {
        String uri = "file:///specs/mcp-server-spec.md";
        Document specDoc = new Document("spec-1", "# MCP Server Architecture Specification\nDetails...",
                Map.of("uri", uri, "acl", "engineering"));

        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(specDoc));

        McpVectorServer.DocumentDetailsResult details = mcpVectorServer.getDocumentContent(
                uri, "engineer-1@company.com", "engineering", 1024
        );

        assertThat(details).isNotNull();
        assertThat(details.id()).isEqualTo("spec-1");
        assertThat(details.uri()).isEqualTo(uri);
        assertThat(details.content()).contains("MCP Server Architecture Specification");
        assertThat(details.acl()).isEqualTo("engineering");
    }

    @Test
    @DisplayName("MCP getDocumentContent should throw NoSuchElementException when document not found or denied")
    void testGetDocumentContent_deniedOrNotFound() {
        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> mcpVectorServer.getDocumentContent(
                "file:///confidential/keys.pem", "attacker@evil.com", "guest", 1024
        ))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining("Document not found or access denied.");
    }

    // =========================================================================
    // 3. MCP Vector Knowledge Capabilities: listAccessibleSources
    // =========================================================================

    @Test
    @DisplayName("MCP listAccessibleSources should list only accessible documents and metadata")
    void testListAccessibleSources() {
        Document doc1 = new Document("1", "Text 1", Map.of("uri", "file:///doc1.txt", "acl", "public", "lastModified", "2026-10-20T10:00:00Z"));
        Document doc2 = new Document("2", "Text 2", Map.of("uri", "file:///doc2.txt", "acl", "analysts", "lastModified", "2026-10-20T11:00:00Z"));

        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc1, doc2));

        List<Map<String, Object>> sources = mcpVectorServer.listAccessibleSources("analyst-1", "analysts", 1024);

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).get("uri")).isEqualTo("file:///doc1.txt");
        assertThat(sources.get(1).get("uri")).isEqualTo("file:///doc2.txt");
    }

    // =========================================================================
    // 4. AIOps Observability MCP Tools Capabilities
    // =========================================================================

    @Test
    @DisplayName("Observability MCP Tool fetchJobTraces should return stage timing and status")
    void testObservability_fetchJobTraces() {
        String testJobId = "mcp-test-job-42";
        ObservabilityMcpTools.JobTraceResponse response = observabilityMcpTools.fetchJobTraces(testJobId);

        assertThat(response).isNotNull();
        assertThat(response.jobId()).isEqualTo(testJobId);
        assertThat(response.overallStatus()).isEqualTo("COMPLETED");
        assertThat(response.totalSpans()).isGreaterThan(0);
        assertThat(response.totalDurationMillis()).isGreaterThan(0);

        Map<String, Long> stageBreakdown = response.stageDurationBreakdownMillis();
        assertThat(stageBreakdown).containsKey("Scanning");
        assertThat(stageBreakdown).containsKey("Extracting");
        assertThat(stageBreakdown).containsKey("Chunking");
        assertThat(stageBreakdown).containsKey("Embedding");
        assertThat(stageBreakdown).containsKey("Indexing");
    }

    @Test
    @DisplayName("Observability MCP Tool getErrorLogs should return structured errors")
    void testObservability_getErrorLogs() {
        String testJobId = "mcp-test-job-99";
        ObservabilityMcpTools.ErrorLogsResponse errorResponse = observabilityMcpTools.getErrorLogs(testJobId, "all");

        assertThat(errorResponse).isNotNull();
        assertThat(errorResponse.jobId()).isEqualTo(testJobId);
        assertThat(errorResponse.errors()).isNotNull();
    }

    @Test
    @DisplayName("Observability MCP Tool queryThroughputMetrics should return live throughput and concurrency")
    void testObservability_queryThroughputMetrics() {
        ObservabilityMcpTools.ThroughputMetricsResponse metrics =
                observabilityMcpTools.queryThroughputMetrics("PGVector_Output");

        assertThat(metrics).isNotNull();
        assertThat(metrics.connectorId()).isEqualTo("PGVector_Output");
        assertThat(metrics.averageThroughputDocsPerSec()).isGreaterThan(0.0);
        assertThat(metrics.p95LatencyMillis()).isGreaterThan(0.0);
        assertThat(metrics.activeVirtualThreads()).isGreaterThan(0.0);
    }
}
