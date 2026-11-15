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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.event.JobExecutionCompletedEvent;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.reporting.JobReportSummary;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who started an execution travels from the ledger to the completed-run event, the JSON summary that
 * {@code /api/reports} serves, and every row of the Parquet and Avro ledger.
 */
class ExecutionProvenanceReportingTest {

    @TempDir
    File tempDir;

    private JobReportProperties properties;
    private JobLedgerAccumulator accumulator;
    private JobReportService reportService;

    private static final ExecutionProvenance DELEGATED = ExecutionProvenance.verified("alice", "ingestion-agent",
            List.of("finance", "legal"), "acme", ExecutionProvenance.SOURCE_API, "req-42");

    @BeforeEach
    void setUp() {
        properties = new JobReportProperties();
        properties.getStorage().setLocalDir(tempDir.getAbsolutePath());
        properties.getFormats().getParquet().setEnabled(true);
        accumulator = new JobLedgerAccumulator(event -> { });
        reportService = new JobReportService(properties, accumulator);
    }

    private JobExecutionCompletedEvent run(String execId, ExecutionProvenance provenance) {
        accumulator.startExecution("7", execId, "Provenance job", PipelineMode.RAG, "Repo", "Out", provenance);
        accumulator.recordDocument(DocumentLedgerEntry.ragSuccess("7", execId, "doc-1", "file:///a.txt", "Repo", "Out",
                10L, 1, 100, "mxbai-embed-large", 1024, "vector_store"));
        accumulator.recordDocument(DocumentLedgerEntry.ragSuccess("7", execId, "doc-2", "file:///b.txt", "Repo", "Out",
                10L, 1, 100, "mxbai-embed-large", 1024, "vector_store"));
        return accumulator.completeExecution(execId, true, Map.of());
    }

    @Test
    void theLedgerCarriesTheProvenanceToTheCompletedEvent() {
        JobExecutionCompletedEvent event = run("exec-prov-1", DELEGATED);

        assertThat(event.provenance()).isEqualTo(DELEGATED);
    }

    @Test
    void anExecutionStartedWithoutProvenanceIsUnknownNotMissing() {
        accumulator.startExecution("7", "exec-prov-2", "Legacy start", PipelineMode.RAG, "Repo", "Out");

        JobExecutionCompletedEvent event = accumulator.completeExecution("exec-prov-2", true, Map.of());

        assertThat(event.provenance()).isEqualTo(ExecutionProvenance.unknown());
    }

    @Test
    void theSummaryServedByTheReportsApiNamesWhoStartedTheRun() throws Exception {
        JobExecutionCompletedEvent event = run("exec-prov-3", DELEGATED);

        // From memory, before any file is written
        assertThat(reportService.getSummary("exec-prov-3")).get()
                .extracting(JobReportSummary::provenance).isEqualTo(DELEGATED);

        // From the JSON summary on disk, as after a restart
        new OperationalReportGenerator(properties).generateJsonSummary(event);
        JobReportService afterRestart = new JobReportService(properties, null);
        assertThat(afterRestart.getSummary("exec-prov-3")).get()
                .extracting(JobReportSummary::provenance).isEqualTo(DELEGATED);
        assertThat(afterRestart.getAllSummaries()).extracting(JobReportSummary::provenance).containsExactly(DELEGATED);
    }

    @Test
    void summariesWrittenBeforeProvenanceStillLoad() throws Exception {
        String legacy = """
                {"jobId":"7","executionId":"exec-old-1","jobName":"Old","pipelineMode":"RAG",
                 "repositoryConnector":"Repo","outputConnector":"Out","status":"SUCCESS",
                 "startTime":"2026-10-01T10:00:00Z","endTime":"2026-10-01T10:00:05Z","durationMs":5000,
                 "totalDocuments":1,"successfulDocuments":1,"failedDocuments":0,"skippedDocuments":0,
                 "reportPathParquet":"x.parquet","reportPathCsv":"x.csv","reportPathJson":"x.json"}
                """;
        Files.writeString(new File(tempDir, "summary_exec-old-1.json").toPath(), legacy);

        JobReportSummary summary = new JobReportService(properties, null).getSummary("exec-old-1").orElseThrow();

        assertThat(summary.jobName()).isEqualTo("Old");
        assertThat(summary.provenance()).isNull();
    }

    @Test
    void everyParquetRowCarriesTheProvenanceAndCanBeQueried() throws Exception {
        JobExecutionCompletedEvent event = run("exec-prov-4", DELEGATED);
        File parquet = new ParquetJobReportGenerator(properties).generateParquetReport(event);
        String path = parquet.getAbsolutePath().replace("\\", "/");

        JobReportService.QueryResult byAgent = reportService.executeDuckDbQuery(String.format(
                "SELECT trigger_source, triggered_by, on_behalf_of, caller_tenant, caller_verified, correlation_id, "
                        + "len(caller_groups) AS group_count, list_contains(caller_groups, 'legal') AS has_legal "
                        + "FROM read_parquet('%s') WHERE triggered_by = 'ingestion-agent'", path));

        assertThat(byAgent.rows()).hasSize(2);
        assertThat(byAgent.rows().get(0)).containsExactly("api", "ingestion-agent", "alice", "acme", true, "req-42", 2L, true);
    }

    @Test
    void anAnonymousRunLeavesTheIdentityColumnsEmpty() throws Exception {
        ParquetJobReportGenerator generator = new ParquetJobReportGenerator(properties);
        Schema schema = generator.getAvroSchema();
        DocumentLedgerEntry doc = DocumentLedgerEntry.ragSuccess("7", "exec-prov-5", "doc-1", "file:///a.txt",
                "Repo", "Out", 10L, 1, 100, "mxbai-embed-large", 1024, "vector_store");

        GenericRecord anonymous = generator.mapToAvroRecord(doc, ExecutionProvenance.anonymous("ois", null), schema);
        GenericRecord legacy = generator.mapToAvroRecord(doc, schema);

        assertThat(anonymous.get("trigger_source")).isEqualTo("ois");
        assertThat(anonymous.get("triggered_by")).isNull();
        assertThat(anonymous.get("caller_groups")).isNull();
        assertThat(anonymous.get("caller_verified")).isEqualTo(false);
        assertThat(legacy.get("trigger_source")).isNull();
        assertThat(legacy.get("caller_verified")).isNull();
    }
}
