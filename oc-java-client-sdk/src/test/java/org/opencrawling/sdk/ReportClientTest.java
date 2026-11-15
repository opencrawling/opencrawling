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
package org.opencrawling.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.sdk.models.DocumentLedgerEntry;
import org.opencrawling.sdk.models.JobReportSummary;
import org.opencrawling.sdk.models.PaginatedLedgerResponse;
import org.opencrawling.sdk.models.QueryResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportClientTest {

    private static HttpServer server;
    private static int port;
    private static OpenCrawlingClient client;

    @BeforeAll
    static void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/api/reports", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            byte[] responseBytes = new byte[0];
            int status = 200;

            if ("GET".equals(method)) {
                if ("/api/reports".equals(path)) {
                    String json = """
                        [
                          {
                            "executionId": "exec-123",
                            "jobId": "1",
                            "jobName": "Doc Crawler",
                            "pipelineMode": "RAG",
                            "status": "COMPLETED",
                            "startTime": "2026-10-16T10:00:00Z",
                            "endTime": "2026-10-16T10:05:00Z",
                            "durationMs": 300000,
                            "totalDocuments": 100,
                            "successfulDocuments": 100,
                            "failedDocuments": 0,
                            "skippedDocuments": 0,
                            "totalChunks": 250,
                            "totalTokens": 15000,
                            "estimatedEmbeddingCostUsd": 0.003
                          }
                        ]
                        """;
                    responseBytes = json.getBytes(StandardCharsets.UTF_8);
                } else if ("/api/reports/1/exec-123".equals(path)) {
                    String json = """
                        {
                          "executionId": "exec-123",
                          "jobId": "1",
                          "jobName": "Doc Crawler",
                          "pipelineMode": "RAG",
                          "status": "COMPLETED",
                          "startTime": "2026-10-16T10:00:00Z",
                          "endTime": "2026-10-16T10:05:00Z",
                          "durationMs": 300000,
                          "totalDocuments": 100,
                          "successfulDocuments": 100,
                          "failedDocuments": 0,
                          "skippedDocuments": 0,
                          "totalChunks": 250,
                          "totalTokens": 15000,
                          "estimatedEmbeddingCostUsd": 0.003
                        }
                        """;
                    responseBytes = json.getBytes(StandardCharsets.UTF_8);
                } else if ("/api/reports/1/nonexistent".equals(path)) {
                    status = 404;
                    responseBytes = "{\"error\": \"Report not found\"}".getBytes(StandardCharsets.UTF_8);
                } else if ("/api/reports/1/exec-123/documents".equals(path)) {
                    String json = """
                        {
                          "items": [
                            {
                              "documentId": "doc-1",
                              "sourceUri": "https://example.com/1",
                              "status": "SUCCESS",
                              "ragChunkCount": 3,
                              "ragTokenCount": 150,
                              "durationMs": 42
                            }
                          ],
                          "page": 0,
                          "size": 10,
                          "total": 1
                        }
                        """;
                    responseBytes = json.getBytes(StandardCharsets.UTF_8);
                } else if ("/api/reports/1/exec-123/export".equals(path)) {
                    responseBytes = "PAR1BINARYPARQUETDATA".getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 404;
                    responseBytes = "{\"error\": \"Not found\"}".getBytes(StandardCharsets.UTF_8);
                }
            } else if ("POST".equals(method)) {
                if ("/api/reports/query".equals(path)) {
                    String json = """
                        {
                          "columns": ["doc_count", "avg_duration_ms"],
                          "rows": [
                            [100, 45.5]
                          ]
                        }
                        """;
                    responseBytes = json.getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 404;
                    responseBytes = "{\"error\": \"Not found\"}".getBytes(StandardCharsets.UTF_8);
                }
            } else {
                status = 405;
                responseBytes = "Method not allowed".getBytes(StandardCharsets.UTF_8);
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });

        server.start();
        client = OpenCrawlingClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testListReports() {
        List<JobReportSummary> list = client.reports().list();
        assertThat(list).isNotEmpty();
        assertThat(list.get(0).executionId()).isEqualTo("exec-123");
        assertThat(list.get(0).jobId()).isEqualTo("1");
        assertThat(list.get(0).estimatedEmbeddingCostUsd()).isEqualTo(0.003);
    }

    @Test
    void testGetReport() {
        Optional<JobReportSummary> summary = client.reports().get("1", "exec-123");
        assertThat(summary).isPresent();
        assertThat(summary.get().executionId()).isEqualTo("exec-123");
        assertThat(summary.get().totalChunks()).isEqualTo(250);

        Optional<JobReportSummary> nonExistent = client.reports().get("1", "nonexistent");
        assertThat(nonExistent).isEmpty();
    }

    @Test
    void testGetDocumentLedger() {
        PaginatedLedgerResponse ledger = client.reports().getDocumentLedger("1", "exec-123", "SUCCESS", null, 0, 10);
        assertThat(ledger).isNotNull();
        assertThat(ledger.items()).hasSize(1);
        DocumentLedgerEntry entry = ledger.items().get(0);
        assertThat(entry.documentId()).isEqualTo("doc-1");
        assertThat(entry.sourceUri()).isEqualTo("https://example.com/1");
        assertThat(entry.status()).isEqualTo("SUCCESS");
        assertThat(entry.ragChunkCount()).isEqualTo(3);
    }

    @Test
    void testDownloadBytes() {
        byte[] bytes = client.reports().download("1", "exec-123", "parquet");
        assertThat(bytes).isNotNull();
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("PAR1BINARYPARQUETDATA");
    }

    @Test
    void testDownloadToFile(@TempDir Path tempDir) {
        Path dest = tempDir.resolve("export.parquet");
        client.reports().downloadToFile("1", "exec-123", "parquet", dest);
        assertThat(Files.exists(dest)).isTrue();
        assertThat(dest).hasContent("PAR1BINARYPARQUETDATA");
    }

    @Test
    void testQuery() {
        QueryResult result = client.reports().query("SELECT count(*) AS doc_count, avg(duration_ms) AS avg_duration_ms FROM read_parquet('...')");
        assertThat(result).isNotNull();
        assertThat(result.columns()).containsExactly("doc_count", "avg_duration_ms");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).get(0)).isEqualTo(100);
        assertThat(result.rows().get(0).get(1)).isEqualTo(45.5);
    }

    @Test
    void testQueryEmptySqlThrowsException() {
        assertThatThrownBy(() -> client.reports().query("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
