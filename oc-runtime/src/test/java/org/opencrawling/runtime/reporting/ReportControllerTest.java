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
package org.opencrawling.runtime.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.event.JobExecutionCompletedEvent;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.core.reporting.JobReportSummary;
import org.opencrawling.runtime.api.ReportController;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportControllerTest {

    @TempDir
    File tempDir;

    private JobReportProperties properties;
    private JobLedgerAccumulator accumulator;
    private JobReportService reportService;
    private ReportController controller;

    @BeforeEach
    void setUp() {
        properties = new JobReportProperties();
        properties.getStorage().setLocalDir(tempDir.getAbsolutePath());
        accumulator = new JobLedgerAccumulator(event -> {});
        reportService = new JobReportService(properties, accumulator);
        controller = new ReportController(reportService);
    }

    @Test
    void testControllerEndpoints() throws Exception {
        String jobId = "404";
        String execId = "exec-test-004";

        accumulator.startExecution(jobId, execId, "Test Job", PipelineMode.RAG, "FileSystem", "PGVector");
        accumulator.recordDocument(DocumentLedgerEntry.ragSuccess(
                jobId, execId, "doc-1", "file:///a.txt", "FileSystem", "PGVector",
                15L, 2, 100, "mxbai", 1024, "vector_store"
        ));
        accumulator.recordDocument(DocumentLedgerEntry.failure(
                jobId, execId, PipelineMode.RAG, "doc-2", "file:///b.txt", "FileSystem", "PGVector",
                10L, "IO_ERROR", "File not found"
        ));
        JobExecutionCompletedEvent event = accumulator.completeExecution(execId, true, Map.of("totalTokens", 100L));

        // Generate files
        OperationalReportGenerator opGen = new OperationalReportGenerator(properties);
        opGen.generateJsonSummary(event);
        opGen.generateCsvManifest(event);

        // 1. GET /api/reports
        ResponseEntity<List<JobReportSummary>> allRes = controller.getAllReports();
        assertTrue(allRes.getStatusCode().is2xxSuccessful());
        assertNotNull(allRes.getBody());
        assertEquals(1, allRes.getBody().size());

        // 2. GET /api/reports/{jobId}/{executionId}
        ResponseEntity<JobReportSummary> singleRes = controller.getReport(jobId, execId);
        assertTrue(singleRes.getStatusCode().is2xxSuccessful());
        assertNotNull(singleRes.getBody());
        assertEquals(execId, singleRes.getBody().executionId());

        // 3. GET /api/reports/{jobId}/{executionId}/documents
        ResponseEntity<JobReportService.PaginatedLedgerResponse> docRes = controller.getDocumentLedger(
                jobId, execId, "ALL", null, 0, 10);
        assertTrue(docRes.getStatusCode().is2xxSuccessful());
        assertNotNull(docRes.getBody());
        assertEquals(2, docRes.getBody().total());
        assertEquals(2, docRes.getBody().items().size());

        // Filter by FAILED
        ResponseEntity<JobReportService.PaginatedLedgerResponse> failedRes = controller.getDocumentLedger(
                jobId, execId, "FAILED", null, 0, 10);
        assertEquals(1, failedRes.getBody().total());
        assertEquals("doc-2", failedRes.getBody().items().get(0).documentId());

        // 4. GET /api/reports/{jobId}/{executionId}/export?format=csv
        ResponseEntity<Resource> exportRes = controller.exportReport(jobId, execId, "csv");
        assertTrue(exportRes.getStatusCode().is2xxSuccessful());
        assertNotNull(exportRes.getBody());
        assertTrue(exportRes.getBody().contentLength() > 0);

        // Generate Parquet report
        ParquetJobReportGenerator parquetGen = new ParquetJobReportGenerator(properties);
        File parquetFile = parquetGen.generateParquetReport(event);
        assertNotNull(parquetFile);
        assertTrue(parquetFile.exists());

        // 5. POST /api/reports/query with scalar SQL
        ResponseEntity<?> queryRes = controller.executeQuery(new ReportController.QueryRequest("SELECT 42 AS answer"));
        assertTrue(queryRes.getStatusCode().is2xxSuccessful());
        assertNotNull(queryRes.getBody());
        JobReportService.QueryResult qr = (JobReportService.QueryResult) queryRes.getBody();
        assertEquals("answer", qr.columns().get(0));
        assertEquals(42, qr.rows().get(0).get(0));

        // 6. POST /api/reports/query with read_parquet(...)
        JobReportSummary summary = singleRes.getBody();
        assertNotNull(summary);
        assertNotNull(summary.reportPathParquet());
        String parquetQuery = "SELECT status, count(*) AS doc_count FROM read_parquet('" + summary.reportPathParquet() + "') GROUP BY status ORDER BY status";
        ResponseEntity<?> parquetQueryRes = controller.executeQuery(new ReportController.QueryRequest(parquetQuery));
        assertTrue(parquetQueryRes.getStatusCode().is2xxSuccessful(), "DuckDB query over Parquet must succeed");
        JobReportService.QueryResult pqr = (JobReportService.QueryResult) parquetQueryRes.getBody();
        assertNotNull(pqr);
        assertTrue(pqr.columns().contains("status"));
        assertTrue(pqr.columns().contains("doc_count"));
        assertEquals(2, pqr.rows().size());

        // 7. POST /api/reports/query with trailing semicolon must also succeed
        String parquetQueryWithTrailingSemicolon = "SELECT status, count(*) AS doc_count, avg(duration_ms) AS avg_duration_ms FROM read_parquet('" + summary.reportPathParquet() + "') GROUP BY status;";
        ResponseEntity<?> semicolonQueryRes = controller.executeQuery(new ReportController.QueryRequest(parquetQueryWithTrailingSemicolon));
        assertTrue(semicolonQueryRes.getStatusCode().is2xxSuccessful(), "Single-statement DuckDB query with trailing semicolon must succeed");

        // 8. POST /api/reports/query with multi-statement internal semicolon must be rejected
        ResponseEntity<?> multiStmtRes = controller.executeQuery(new ReportController.QueryRequest("SELECT 1; SELECT 2;"));
        assertEquals(400, multiStmtRes.getStatusCode().value(), "Multi-statement query must be rejected");
        assertTrue(String.valueOf(multiStmtRes.getBody()).contains("Multi-statement queries containing semicolons are forbidden"));
    }

    @Test
    void testDiskFallbackWhenAccumulatorEmpty() throws Exception {
        String jobId = "505";
        String execId = "exec-test-005";

        accumulator.startExecution(jobId, execId, "Test Job", PipelineMode.RAG, "FileSystem", "PGVector");
        accumulator.recordDocument(DocumentLedgerEntry.ragSuccess(
                jobId, execId, "doc-1", "file:///a.txt", "FileSystem", "PGVector",
                15L, 2, 100, "mxbai", 1024, "vector_store"
        ));
        accumulator.recordDocument(DocumentLedgerEntry.ragSuccess(
                jobId, execId, "doc-2", "file:///b.txt", "FileSystem", "PGVector",
                20L, 3, 150, "mxbai", 1024, "vector_store"
        ));
        JobExecutionCompletedEvent event = accumulator.completeExecution(execId, true, Map.of("totalTokens", 250L));

        // Generate files on disk
        OperationalReportGenerator opGen = new OperationalReportGenerator(properties);
        opGen.generateJsonSummary(event);
        opGen.generateCsvManifest(event);

        // Simulate server restart: fresh accumulator with no in-memory state
        JobLedgerAccumulator emptyAccumulator = new JobLedgerAccumulator(ev -> {});
        JobReportService freshService = new JobReportService(properties, emptyAccumulator);
        ReportController freshController = new ReportController(freshService);

        // Must load 2 documents from disk manifest
        ResponseEntity<JobReportService.PaginatedLedgerResponse> res = freshController.getDocumentLedger(
                jobId, execId, "ALL", null, 0, 10);
        assertTrue(res.getStatusCode().is2xxSuccessful());
        assertNotNull(res.getBody());
        assertEquals(2, res.getBody().total(), "Must load 2 entries from disk");
        assertEquals(2, res.getBody().items().size());
        assertEquals("doc-1", res.getBody().items().get(0).documentId());
        assertEquals("doc-2", res.getBody().items().get(1).documentId());
    }
}
