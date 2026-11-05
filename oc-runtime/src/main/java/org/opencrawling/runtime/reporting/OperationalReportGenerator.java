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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.opencrawling.core.event.JobExecutionCompletedEvent;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.core.reporting.JobReportSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Generates operational JSON summaries and compliance CSV manifests for
 * immediate Admin UI rendering, API access, and spreadsheet-based audits.
 */
@Component
public class OperationalReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(OperationalReportGenerator.class);

    private final JobReportProperties properties;
    private final ObjectMapper objectMapper;

    public OperationalReportGenerator(JobReportProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @EventListener
    public void onJobExecutionCompleted(JobExecutionCompletedEvent event) {
        if (!properties.isEnabled()) return;

        try {
            if (properties.getFormats().isJson()) {
                generateJsonSummary(event);
            }
            if (properties.getFormats().isCsv()) {
                generateCsvManifest(event);
            }
        } catch (Exception e) {
            log.error("Failed to generate operational reports for job {} [exec: {}]: {}",
                    event.jobId(), event.executionId(), e.getMessage(), e);
        }
    }

    public File generateJsonSummary(JobExecutionCompletedEvent event) throws Exception {
        File dir = properties.getStorage().resolveLocalDir();
        File jsonFile = new File(dir, String.format("summary_%s.json", event.executionId()));

        long durationMs = Duration.between(event.startTime(), event.endTime()).toMillis();
        String overallStatus = event.failedDocuments() == 0 ? "SUCCESS"
                : (event.successfulDocuments() > 0 ? "PARTIAL" : "FAILED");

        LocalDate date = LocalDate.ofInstant(event.startTime(), ZoneOffset.UTC);
        String relativeParquet = String.format("mode=%s/year=%d/month=%02d/day=%02d/job_%s_exec_%s.parquet",
                event.pipelineMode().name(), date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                event.jobId(), event.executionId());

        JobReportSummary summary = new JobReportSummary(
                event.jobId(),
                event.executionId(),
                event.jobName(),
                event.pipelineMode(),
                event.repositoryConnector(),
                event.outputConnector(),
                overallStatus,
                event.startTime(),
                event.endTime(),
                durationMs,
                event.totalDocuments(),
                event.successfulDocuments(),
                event.failedDocuments(),
                event.skippedDocuments(),
                // RAG
                (Long) event.summaryMetrics().get("totalChunks"),
                (Long) event.summaryMetrics().get("totalTokens"),
                (Double) event.summaryMetrics().get("estimatedEmbeddingCostUsd"),
                (String) event.summaryMetrics().get("embeddingModel"),
                // Migration
                (Long) event.summaryMetrics().get("totalBinaryBytes"),
                (Long) event.summaryMetrics().get("totalReclaimedBytes"),
                (Long) event.summaryMetrics().get("safePrunedCount"),
                (Long) event.summaryMetrics().get("verifiedChecksumCount"),
                // Paths
                relativeParquet,
                String.format("manifest_%s.csv", event.executionId()),
                String.format("summary_%s.json", event.executionId()),
                event.provenance()
        );

        objectMapper.writeValue(jsonFile, summary);
        log.info("Generated operational JSON summary at: {}", jsonFile.getAbsolutePath());
        return jsonFile;
    }

    public File generateCsvManifest(JobExecutionCompletedEvent event) throws Exception {
        File dir = properties.getStorage().resolveLocalDir();
        File csvFile = new File(dir, String.format("manifest_%s.csv", event.executionId()));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile, StandardCharsets.UTF_8))) {
            // CSV Header
            writer.write("job_id,execution_id,pipeline_mode,document_id,source_uri,connector_source,connector_target," +
                    "status,duration_ms,timestamp_utc,error_code,error_message," +
                    "rag_chunk_count,rag_token_count,rag_embedding_model," +
                    "mig_binary_size_bytes,mig_sha256_checksum,mig_target_key,mig_safe_pruned,mig_reclaimed_bytes\n");

            for (DocumentLedgerEntry doc : event.documentEntries()) {
                StringBuilder sb = new StringBuilder();
                appendCsv(sb, doc.jobId());
                appendCsv(sb, doc.executionId());
                appendCsv(sb, doc.pipelineMode().name());
                appendCsv(sb, doc.documentId());
                appendCsv(sb, doc.sourceUri());
                appendCsv(sb, doc.connectorSource());
                appendCsv(sb, doc.connectorTarget());
                appendCsv(sb, doc.status());
                appendCsv(sb, String.valueOf(doc.durationMs()));
                appendCsv(sb, doc.timestampUtc() != null ? doc.timestampUtc().toString() : "");
                appendCsv(sb, doc.errorCode());
                appendCsv(sb, doc.errorMessage());

                // RAG
                appendCsv(sb, doc.ragChunkCount() != null ? String.valueOf(doc.ragChunkCount()) : "");
                appendCsv(sb, doc.ragTokenCount() != null ? String.valueOf(doc.ragTokenCount()) : "");
                appendCsv(sb, doc.ragEmbeddingModel());

                // Migration
                appendCsv(sb, doc.migBinarySizeBytes() != null ? String.valueOf(doc.migBinarySizeBytes()) : "");
                appendCsv(sb, doc.migSha256Checksum());
                appendCsv(sb, doc.migTargetKey());
                appendCsv(sb, doc.migSafePruned() != null ? String.valueOf(doc.migSafePruned()) : "");
                appendCsvLast(sb, doc.migReclaimedBytes() != null ? String.valueOf(doc.migReclaimedBytes()) : "");

                writer.write(sb.toString());
            }
        }

        log.info("Generated compliance CSV manifest ({} lines) at: {}", event.documentEntries().size(), csvFile.getAbsolutePath());
        return csvFile;
    }

    private void appendCsv(StringBuilder sb, String val) {
        sb.append(escapeCsv(val)).append(",");
    }

    private void appendCsvLast(StringBuilder sb, String val) {
        sb.append(escapeCsv(val)).append("\n");
    }

    public static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
