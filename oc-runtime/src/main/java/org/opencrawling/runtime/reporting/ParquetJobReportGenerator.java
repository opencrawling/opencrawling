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
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.opencrawling.core.event.JobExecutionCompletedEvent;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Asynchronous report generator that produces schema-enforced Apache Avro and compressed
 * columnar Apache Parquet files using Java 25 Virtual Threads upon job completion.
 */
@Component
public class ParquetJobReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ParquetJobReportGenerator.class);

    private final JobReportProperties properties;
    private Schema avroSchema;

    public ParquetJobReportGenerator(JobReportProperties properties) {
        this.properties = properties;
        initAvroSchema();
    }

    private synchronized void initAvroSchema() {
        if (this.avroSchema != null) return;
        try {
            ClassPathResource res = new ClassPathResource("avro/job_execution_report.avsc");
            try (InputStream in = res.getInputStream()) {
                this.avroSchema = new Schema.Parser().parse(in);
                log.info("Loaded canonical Avro schema '{}' for job execution audit reporting.", this.avroSchema.getFullName());
            }
        } catch (Exception e) {
            log.error("Failed to load Avro schema avro/job_execution_report.avsc: {}", e.getMessage(), e);
        }
    }

    public Schema getAvroSchema() {
        if (this.avroSchema == null) {
            initAvroSchema();
        }
        return this.avroSchema;
    }

    @EventListener
    public void onJobExecutionCompleted(JobExecutionCompletedEvent event) {
        if (!properties.isEnabled() || !properties.getFormats().getParquet().isEnabled()) {
            log.debug("Parquet reporting is disabled, skipping generation for job {}", event.jobId());
            return;
        }

        try {
            generateParquetReport(event);
        } catch (Exception e) {
            log.error("Failed to generate Parquet report for job {} [exec: {}]: {}",
                    event.jobId(), event.executionId(), e.getMessage(), e);
        }
    }

    public File generateParquetReport(JobExecutionCompletedEvent event) throws Exception {
        Schema schema = getAvroSchema();
        if (schema == null) {
            throw new IllegalStateException("Avro schema not initialized");
        }

        LocalDate date = LocalDate.ofInstant(event.startTime(), ZoneOffset.UTC);
        String partition = String.format("mode=%s/year=%d/month=%02d/day=%02d",
                event.pipelineMode().name(), date.getYear(), date.getMonthValue(), date.getDayOfMonth());

        File targetDir = new File(properties.getStorage().resolveLocalDir(), partition);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        File parquetFile = new File(targetDir, String.format("job_%s_exec_%s.parquet", event.jobId(), event.executionId()));
        File crcFile = new File(targetDir, "." + parquetFile.getName() + ".crc");
        if (crcFile.exists()) {
            crcFile.delete();
        }
        Path hadoopPath = new Path(parquetFile.toURI());

        CompressionCodecName codec = resolveCodec(properties.getFormats().getParquet().getCompression());

        log.info("Generating columnar Parquet report (Codec: {}) at: {}", codec, parquetFile.getAbsolutePath());

        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(hadoopPath)
                .withSchema(schema)
                .withWriteMode(org.apache.parquet.hadoop.ParquetFileWriter.Mode.OVERWRITE)
                .withCompressionCodec(codec)
                .withRowGroupSize(properties.getFormats().getParquet().getRowGroupSizeBytes())
                .withPageSize(properties.getFormats().getParquet().getPageSizeBytes())
                .build()) {

            for (DocumentLedgerEntry doc : event.documentEntries()) {
                GenericRecord record = mapToAvroRecord(doc, event.provenance(), schema);
                writer.write(record);
            }
        } catch (Exception writeEx) {
            if (parquetFile.exists() && parquetFile.length() == 0) {
                parquetFile.delete();
            }
            throw writeEx;
        }

        log.info("Successfully generated Parquet report ({} records, size: {} bytes) for job {} [exec: {}]",
                event.documentEntries().size(), parquetFile.length(), event.jobId(), event.executionId());

        if (properties.getFormats().isAvro()) {
            File avroFile = new File(targetDir, String.format("job_%s_exec_%s.avro", event.jobId(), event.executionId()));
            try (DataFileWriter<GenericRecord> avroWriter = new DataFileWriter<>(new GenericDatumWriter<>(schema))) {
                avroWriter.create(schema, avroFile);
                for (DocumentLedgerEntry doc : event.documentEntries()) {
                    avroWriter.append(mapToAvroRecord(doc, event.provenance(), schema));
                }
            }
            log.info("Successfully generated companion binary Avro file: {}", avroFile.getAbsolutePath());
        }

        return parquetFile;
    }

    public GenericRecord mapToAvroRecord(DocumentLedgerEntry doc, Schema schema) {
        return mapToAvroRecord(doc, null, schema);
    }

    /** One ledger row: the document's own fields plus its execution's provenance, repeated on every row. */
    public GenericRecord mapToAvroRecord(DocumentLedgerEntry doc, ExecutionProvenance provenance, Schema schema) {
        GenericRecord r = new GenericData.Record(schema);
        r.put("job_id", doc.jobId());
        r.put("execution_id", doc.executionId());
        r.put("pipeline_mode", doc.pipelineMode().name());
        r.put("document_id", doc.documentId());
        r.put("source_uri", doc.sourceUri());
        r.put("connector_source", doc.connectorSource());
        r.put("connector_target", doc.connectorTarget());
        r.put("status", doc.status());
        r.put("duration_ms", doc.durationMs());
        r.put("timestamp_utc", doc.timestampUtc() != null ? doc.timestampUtc().toString() : "");
        r.put("error_code", doc.errorCode());
        r.put("error_message", doc.errorMessage());

        // RAG Fields
        r.put("rag_chunk_count", doc.ragChunkCount());
        r.put("rag_token_count", doc.ragTokenCount());
        r.put("rag_embedding_model", doc.ragEmbeddingModel());
        r.put("rag_embedding_dimensions", doc.ragEmbeddingDimensions());
        r.put("rag_vector_table", doc.ragVectorTable());

        // Migration Fields
        r.put("mig_binary_size_bytes", doc.migBinarySizeBytes());
        r.put("mig_sha256_checksum", doc.migSha256Checksum());
        r.put("mig_target_bucket", doc.migTargetBucket());
        r.put("mig_target_key", doc.migTargetKey());
        r.put("mig_sidecar_written", doc.migSidecarWritten());
        r.put("mig_callback_http_status", doc.migCallbackHttpStatus());
        r.put("mig_safe_pruned", doc.migSafePruned());
        r.put("mig_reclaimed_bytes", doc.migReclaimedBytes());

        // Provenance: who started the execution
        if (provenance != null) {
            r.put("trigger_source", provenance.triggerSource());
            r.put("triggered_by", provenance.triggeredBy());
            r.put("on_behalf_of", provenance.onBehalfOf());
            r.put("caller_groups", provenance.callerGroups().isEmpty() ? null : provenance.callerGroups());
            r.put("caller_tenant", provenance.callerTenant());
            r.put("caller_verified", provenance.callerVerified());
            r.put("correlation_id", provenance.correlationId());
        }

        return r;
    }

    private CompressionCodecName resolveCodec(String compression) {
        if (compression == null) return CompressionCodecName.ZSTD;
        return switch (compression.toUpperCase()) {
            case "SNAPPY" -> CompressionCodecName.SNAPPY;
            case "GZIP" -> CompressionCodecName.GZIP;
            case "UNCOMPRESSED" -> CompressionCodecName.UNCOMPRESSED;
            default -> CompressionCodecName.ZSTD;
        };
    }
}
