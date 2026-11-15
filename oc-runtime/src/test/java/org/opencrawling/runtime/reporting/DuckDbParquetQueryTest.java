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

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DuckDbParquetQueryTest {

    @TempDir
    File tempDir;

    private JobReportProperties properties;
    private ParquetJobReportGenerator generator;
    private JobReportService reportService;
    private JobLedgerAccumulator accumulator;

    @BeforeEach
    void setUp() {
        properties = new JobReportProperties();
        properties.getStorage().setLocalDir(tempDir.getAbsolutePath());
        properties.getFormats().getParquet().setEnabled(true);
        generator = new ParquetJobReportGenerator(properties);
        accumulator = new JobLedgerAccumulator(null);
        reportService = new JobReportService(properties, accumulator);
    }

    @Test
    void testDuckDbParquetAnalyticalQueries() throws Exception {
        String jobId = "303";
        String execId = "exec-test-003";
        Instant now = Instant.now();

        DocumentLedgerEntry d1 = DocumentLedgerEntry.ragSuccess(
                jobId, execId, "doc-101", "file:///doc1.txt",
                "FileSystemRepositoryConnector", "PGVectorOutputConnector",
                20L, 5, 500, "mxbai-embed-large", 1024, "vector_store"
        );
        DocumentLedgerEntry d2 = DocumentLedgerEntry.ragSuccess(
                jobId, execId, "doc-102", "file:///doc2.txt",
                "FileSystemRepositoryConnector", "PGVectorOutputConnector",
                25L, 3, 300, "mxbai-embed-large", 1024, "vector_store"
        );
        DocumentLedgerEntry d3 = DocumentLedgerEntry.migrationSuccess(
                jobId, execId, "doc-103", "file:///doc3.bin",
                "FileSystemRepositoryConnector", "OzoneOutputConnector",
                40L, 5242880L, "sha256hash123", "bucket1", "key1", true, 200, true, 5242880L
        );

        JobExecutionCompletedEvent event = new JobExecutionCompletedEvent(
                jobId, execId, "Multi-pipeline Job", PipelineMode.RAG,
                "FileSystemRepositoryConnector", "PGVectorOutputConnector",
                now, now.plusSeconds(2),
                3, 3, 0, 0,
                Map.of(),
                List.of(d1, d2, d3)
        );

        File parquetFile = generator.generateParquetReport(event);
        assertTrue(parquetFile.exists());

        // Test Query 1: Total records count
        String sqlCount = String.format("SELECT count(*) AS total_count FROM read_parquet('%s')", parquetFile.getAbsolutePath().replace("\\", "/"));
        JobReportService.QueryResult resultCount = reportService.executeDuckDbQuery(sqlCount);
        assertEquals(1, resultCount.columns().size());
        assertEquals("total_count", resultCount.columns().get(0));
        assertEquals(1, resultCount.rows().size());
        assertEquals(3L, ((Number) resultCount.rows().get(0).get(0)).longValue());

        // Test Query 2: Aggregated token count for RAG records
        String sqlTokens = String.format(
                "SELECT sum(rag_token_count) AS total_tokens, sum(rag_chunk_count) AS total_chunks " +
                "FROM read_parquet('%s') WHERE pipeline_mode = 'RAG'",
                parquetFile.getAbsolutePath().replace("\\", "/"));

        JobReportService.QueryResult resultTokens = reportService.executeDuckDbQuery(sqlTokens);
        assertEquals(2, resultTokens.columns().size());
        assertEquals(800L, ((Number) resultTokens.rows().get(0).get(0)).longValue());
        assertEquals(8L, ((Number) resultTokens.rows().get(0).get(1)).longValue());

        // Test Query 3: Migration binary bytes verification
        String sqlMigration = String.format(
                "SELECT sum(mig_binary_size_bytes) AS total_migrated_bytes " +
                "FROM read_parquet('%s') WHERE mig_safe_pruned = true",
                parquetFile.getAbsolutePath().replace("\\", "/"));

        JobReportService.QueryResult resultMigration = reportService.executeDuckDbQuery(sqlMigration);
        assertEquals(5242880L, ((Number) resultMigration.rows().get(0).get(0)).longValue());
    }
}
