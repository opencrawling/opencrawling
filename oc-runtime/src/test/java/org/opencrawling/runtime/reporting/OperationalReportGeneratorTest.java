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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.event.JobExecutionCompletedEvent;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.core.reporting.JobReportSummary;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OperationalReportGeneratorTest {

    @TempDir
    File tempDir;

    private JobReportProperties properties;
    private OperationalReportGenerator generator;

    @BeforeEach
    void setUp() {
        properties = new JobReportProperties();
        properties.getStorage().setLocalDir(tempDir.getAbsolutePath());
        generator = new OperationalReportGenerator(properties);
    }

    @Test
    void testJsonSummaryAndCsvManifestGeneration() throws Exception {
        String jobId = "202";
        String execId = "exec-test-002";
        Instant now = Instant.now();

        DocumentLedgerEntry doc1 = DocumentLedgerEntry.ragSuccess(
                jobId, execId, "doc-a", "file:///data/report,2026.pdf",
                "FileSystemRepositoryConnector", "PGVectorOutputConnector",
                30L, 2, 250, "all-minilm", 384, "vector_store_384"
        );

        DocumentLedgerEntry doc2 = DocumentLedgerEntry.migrationSuccess(
                jobId, execId, "doc-b", "s3://source-bucket/file\"quote\".pdf",
                "AlfrescoRepositoryConnector", "OzoneOutputConnector",
                95L, 2048000L, "abcdef1234567890", "ozone-target", "keys/doc-b.pdf",
                true, 200, true, 2048000L
        );

        JobExecutionCompletedEvent event = new JobExecutionCompletedEvent(
                jobId, execId, "Migration & RAG Hybrid", PipelineMode.MIGRATION,
                "AlfrescoRepositoryConnector", "OzoneOutputConnector",
                now, now.plusSeconds(5),
                2, 2, 0, 0,
                Map.of(
                        "totalBinaryBytes", 2048000L,
                        "totalReclaimedBytes", 2048000L,
                        "safePrunedCount", 1L,
                        "verifiedChecksumCount", 1L
                ),
                List.of(doc1, doc2)
        );

        // 1. JSON Summary Verification
        File jsonFile = generator.generateJsonSummary(event);
        assertTrue(jsonFile.exists(), "JSON summary file must exist");
        assertTrue(jsonFile.length() > 0);

        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        JobReportSummary summary = om.readValue(jsonFile, JobReportSummary.class);
        assertEquals(jobId, summary.jobId());
        assertEquals(execId, summary.executionId());
        assertEquals("SUCCESS", summary.status());
        assertEquals(2, summary.totalDocuments());
        assertEquals(2048000L, summary.totalBinaryBytes());

        // 2. CSV Manifest Verification
        File csvFile = generator.generateCsvManifest(event);
        assertTrue(csvFile.exists(), "CSV manifest file must exist");
        List<String> lines = Files.readAllLines(csvFile.toPath());
        assertEquals(3, lines.size(), "1 header line + 2 document lines");

        assertTrue(lines.get(0).startsWith("job_id,execution_id,pipeline_mode"));
        // Check CSV escaping on doc1 (contains comma) and doc2 (contains quote)
        assertTrue(lines.get(1).contains("\"file:///data/report,2026.pdf\""));
        assertTrue(lines.get(2).contains("\"s3://source-bucket/file\"\"quote\"\".pdf\""));
    }
}
