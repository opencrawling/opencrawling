/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.iceberg.output.writer;

import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.iceberg.output.schema.IcebergTableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class IcebergRecordWriter {

    private static final Logger log = LoggerFactory.getLogger(IcebergRecordWriter.class);

    public static Record convertToRecord(Table table, RepositoryDocument document, byte[] contentBytes) {
        Schema schema = table.schema();
        Record record = GenericRecord.create(schema);

        String id = UUID.randomUUID().toString();
        String documentId = document.id();
        String uri = document.uri() != null ? document.uri() : "";

        record.setField("id", id);
        record.setField("document_id", documentId);
        record.setField("document_uri", uri);

        Map<String, List<String>> metadata = document.metadata() != null ? document.metadata() : Map.of();

        // Source type
        String sourceType = getFirstMetadataValue(metadata, "source_type", "repository_type", "source");
        record.setField("source_type", sourceType != null ? sourceType : "generic");

        // Title
        String title = getFirstMetadataValue(metadata, "title", "name", "filename");
        record.setField("title", title != null ? title : documentId);

        // MIME type
        String mimeType = getFirstMetadataValue(metadata, "mimeType", "content_type", "media_type", "mime_type");
        record.setField("mime_type", mimeType != null ? mimeType : "application/octet-stream");

        // Length and Checksum
        long length = contentBytes != null ? contentBytes.length : 0L;
        String checksum = "";
        if (contentBytes != null && contentBytes.length > 0) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(contentBytes);
                checksum = HexFormat.of().formatHex(hash);
            } catch (Exception ignored) {
            }
        }
        if (checksum.isBlank()) {
            checksum = getFirstMetadataValue(metadata, "sha256", "sha256_checksum", "hash");
        }
        record.setField("content_length", length);
        record.setField("sha256_checksum", checksum != null ? checksum : "");

        // Claim check URI
        String claimCheckUri = getFirstMetadataValue(metadata, "claim_check_uri", "claimCheckUri");
        if (claimCheckUri == null && uri.startsWith("s3://") || uri.startsWith("o3://") || uri.startsWith("file://")) {
            claimCheckUri = uri;
        }
        record.setField("claim_check_uri", claimCheckUri != null ? claimCheckUri : "");

        // Extracted text
        String extractedText = getFirstMetadataValue(metadata, "extracted_text", "text");
        record.setField("extracted_text", extractedText != null ? extractedText : "");

        // Extracted entities and custom metadata
        Map<String, String> extractedEntities = extractEntitiesMap(metadata);
        Map<String, String> customMetadata = extractCustomMetadata(metadata);
        record.setField("extracted_entities", extractedEntities);
        record.setField("custom_metadata", customMetadata);

        // Security / Zero-Trust ACLs
        String owner = getFirstMetadataValue(metadata, "owner", "author", "creator");
        record.setField("security_owner", owner != null ? owner : "");

        org.opencrawling.core.security.EffectiveAcl effectiveAcl =
                org.opencrawling.core.security.EffectiveAcl.resolve(document.security(), document.acl());

        record.setField("security_inheritance", effectiveAcl.inheritanceEnabled());
        record.setField("allowed_principals", new ArrayList<>(effectiveAcl.allowedRead()));
        record.setField("denied_principals", new ArrayList<>(effectiveAcl.deniedRead()));

        // Action & Timestamp
        String action = document.action() != null ? document.action().name() : "UPSERT";
        record.setField("action", action);

        Instant modified = document.lastModified() != null ? document.lastModified() : Instant.now();
        record.setField("crawled_at", LocalDateTime.ofInstant(modified, ZoneOffset.UTC));

        // Dynamically evolved columns
        for (Map.Entry<String, String> entity : extractedEntities.entrySet()) {
            String colName = IcebergTableManager.sanitizeIdentifier(entity.getKey());
            if (schema.findField(colName) != null) {
                record.setField(colName, entity.getValue());
            }
        }

        return record;
    }

    public static DataFile writeRecords(Table table, List<Record> records) throws IOException {
        if (records == null || records.isEmpty()) {
            return null;
        }

        PartitionKey partitionKey = resolvePartitionKey(table, records.get(0));
        return writeSinglePartitionFile(table, records, partitionKey);
    }

    /** Writes records grouped by Iceberg partition key so partition stats and manifest pruning stay accurate. */
    public static List<DataFile> writePartitionedRecords(Table table, List<Record> records) throws IOException {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        if (table.spec() == null || !table.spec().isPartitioned() || records.size() == 1) {
            PartitionKey singleKey = resolvePartitionKey(table, records.get(0));
            return List.of(writeSinglePartitionFile(table, records, singleKey));
        }

        Map<PartitionKey, List<Record>> byPartition = new LinkedHashMap<>();
        InternalRecordWrapper wrapper = new InternalRecordWrapper(table.schema().asStruct());
        for (Record rec : records) {
            PartitionKey key = new PartitionKey(table.spec(), table.schema());
            key.partition(wrapper.wrap(rec));
            byPartition.computeIfAbsent(key.copy(), ignored -> new ArrayList<>()).add(rec);
        }

        List<DataFile> files = new ArrayList<>(byPartition.size());
        for (Map.Entry<PartitionKey, List<Record>> entry : byPartition.entrySet()) {
            files.add(writeSinglePartitionFile(table, entry.getValue(), entry.getKey()));
        }
        return files;
    }

    private static PartitionKey resolvePartitionKey(Table table, Record sample) {
        if (table.spec() == null || !table.spec().isPartitioned() || sample == null) {
            return null;
        }
        PartitionKey partitionKey = new PartitionKey(table.spec(), table.schema());
        partitionKey.partition(new InternalRecordWrapper(table.schema().asStruct()).wrap(sample));
        return partitionKey;
    }

    private static DataFile writeSinglePartitionFile(Table table, List<Record> records, PartitionKey partitionKey)
            throws IOException {
        String partitionPath = (partitionKey != null && table.spec().isPartitioned()) ? partitionKey.toPath() : "";
        String dataDir = (partitionPath != null && !partitionPath.isBlank())
                ? table.location() + "/data/" + partitionPath
                : table.location() + "/data";
        String dataFilePath = dataDir + "/" + UUID.randomUUID() + ".parquet";
        OutputFile outputFile = table.io().newOutputFile(dataFilePath);

        FileAppender<Record> appender = Parquet.write(outputFile)
                .forTable(table)
                .schema(table.schema())
                .metricsConfig(MetricsConfig.forTable(table))
                .createWriterFunc(messageType -> GenericParquetWriter.create(table.schema(), messageType))
                .build();
        try {
            appender.addAll(records);
        } finally {
            appender.close();
        }

        DataFiles.Builder builder = DataFiles.builder(table.spec())
                .withPath(dataFilePath)
                .withFileSizeInBytes(appender.length())
                .withRecordCount(appender.metrics().recordCount())
                .withMetrics(appender.metrics())
                .withFormat(FileFormat.PARQUET);
        if (partitionKey != null && table.spec().isPartitioned()) {
            builder.withPartition(partitionKey);
        }
        return builder.build();
    }

    public static void commitDataFile(Table table, DataFile dataFile) {
        if (dataFile != null) {
            commitDataFiles(table, List.of(dataFile));
        }
    }

    public static void commitDataFiles(Table table, List<DataFile> dataFiles) {
        if (dataFiles == null || dataFiles.isEmpty()) {
            return;
        }
        AppendFiles append = table.newAppend();
        for (DataFile dataFile : dataFiles) {
            if (dataFile != null) {
                log.info("Committing DataFile '{}' (records: {}, size: {} bytes) to Iceberg table '{}'",
                        dataFile.path(), dataFile.recordCount(), dataFile.fileSizeInBytes(), table.name());
                append.appendFile(dataFile);
            }
        }
        append.commit();
        log.info("Snapshot committed successfully to table '{}'", table.name());
    }

    public static Map<String, String> extractEntitiesMap(Map<String, List<String>> metadata) {
        Map<String, String> entities = new HashMap<>();
        if (metadata != null) {
            metadata.forEach((key, values) -> {
                if (values != null && !values.isEmpty() && values.get(0) != null) {
                    if (key.startsWith("entity.") || key.startsWith("extracted.") || isCommonEntityName(key)) {
                        String cleanKey = key.replaceFirst("^(entity\\.|extracted\\.)", "");
                        entities.put(cleanKey, String.join(", ", values));
                    }
                }
            });
        }
        return entities;
    }

    public static Map<String, String> extractCustomMetadata(Map<String, List<String>> metadata) {
        Map<String, String> custom = new HashMap<>();
        if (metadata != null) {
            metadata.forEach((key, values) -> {
                if (values != null && !values.isEmpty() && values.get(0) != null) {
                    if (!key.startsWith("tk:") && !key.equals("documentId") && !key.equals("uri")
                            && !key.equals("acl") && !key.equals("security") && !key.equals("extracted_text")) {
                        custom.put(key, String.join(", ", values));
                    }
                }
            });
        }
        return custom;
    }

    private static boolean isCommonEntityName(String key) {
        String lower = key.toLowerCase();
        return lower.contains("invoice") || lower.contains("vendor") || lower.contains("amount")
                || lower.contains("currency") || lower.contains("policy") || lower.contains("claim")
                || lower.contains("contract") || lower.contains("due_date") || lower.contains("iban")
                || lower.contains("vat");
    }

    private static String getFirstMetadataValue(Map<String, List<String>> metadata, String... candidateKeys) {
        if (metadata == null) {
            return null;
        }
        for (String key : candidateKeys) {
            List<String> vals = metadata.get(key);
            if (vals != null && !vals.isEmpty() && vals.get(0) != null) {
                return vals.get(0);
            }
        }
        return null;
    }
}
