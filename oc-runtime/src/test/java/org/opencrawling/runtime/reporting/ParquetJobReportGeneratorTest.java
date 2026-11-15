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

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
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

class ParquetJobReportGeneratorTest {

    @TempDir
    File tempDir;

    private JobReportProperties properties;
    private ParquetJobReportGenerator generator;

    @BeforeEach
    void setUp() {
        properties = new JobReportProperties();
        properties.getStorage().setLocalDir(tempDir.getAbsolutePath());
        properties.getFormats().getParquet().setEnabled(true);
        properties.getFormats().setAvro(true);
        generator = new ParquetJobReportGenerator(properties);
    }

    @Test
    void testAvroSchemaResolution() {
        Schema schema = generator.getAvroSchema();
        assertNotNull(schema, "Schema should be resolved from classpath");
        assertEquals("DocumentExecutionRecord", schema.getName());
        assertEquals("org.opencrawling.reporting.avro", schema.getNamespace());
        assertNotNull(schema.getField("job_id"));
        assertNotNull(schema.getField("rag_token_count"));
        assertNotNull(schema.getField("mig_sha256_checksum"));
    }

    @Test
    void testParquetAndAvroReportGeneration() throws Exception {
        String jobId = "101";
        String execId = "exec-test-001";
        Instant now = Instant.now();

        DocumentLedgerEntry ragDoc = DocumentLedgerEntry.ragSuccess(
                jobId, execId, "doc-1", "file:///test/doc1.pdf",
                "FileSystemRepositoryConnector", "PGVectorOutputConnector",
                45L, 3, 420, "mxbai-embed-large", 1024, "vector_store_1024"
        );

        DocumentLedgerEntry migDoc = DocumentLedgerEntry.migrationSuccess(
                jobId, execId, "doc-2", "cmis://repo/doc2.docx",
                "CmisRepositoryConnector", "OzoneOutputConnector",
                82L, 1048576L, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "enterprise-archive", "2026/10/doc2.docx", true, 200, true, 1048576L
        );

        DocumentLedgerEntry failedDoc = DocumentLedgerEntry.failure(
                jobId, execId, PipelineMode.RAG, "doc-3", "file:///test/corrupt.bin",
                "FileSystemRepositoryConnector", "PGVectorOutputConnector",
                12L, "TIKA_EXTRACTION_ERROR", "Unsupported file format"
        );

        JobExecutionCompletedEvent event = new JobExecutionCompletedEvent(
                jobId, execId, "Test Ingestion Job", PipelineMode.RAG,
                "FileSystemRepositoryConnector", "PGVectorOutputConnector",
                now, now.plusSeconds(10),
                3, 2, 1, 0,
                Map.of("totalTokens", 420L, "totalChunks", 3L),
                List.of(ragDoc, migDoc, failedDoc)
        );

        File parquetFile = generator.generateParquetReport(event);
        assertNotNull(parquetFile);
        assertTrue(parquetFile.exists(), "Parquet file must exist on disk");
        assertTrue(parquetFile.length() > 0, "Parquet file should not be empty");

        // Verify that Parquet file can be read back using AvroParquetReader
        Path hadoopPath = new Path(parquetFile.toURI());
        int recordCount = 0;
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(hadoopPath).build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                recordCount++;
                assertNotNull(record.get("job_id"));
                assertNotNull(record.get("document_id"));
                assertNotNull(record.get("status"));
            }
        }
        assertEquals(3, recordCount, "Parquet file should contain all 3 records");

        // Verify companion .avro file was also written
        File avroFile = new File(parquetFile.getParentFile(), String.format("job_%s_exec_%s.avro", jobId, execId));
        assertTrue(avroFile.exists(), "Companion .avro file must be generated when avro format is enabled");
        assertTrue(avroFile.length() > 0);
    }
}
