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
package org.opencrawling.openarch.output.parquet;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Generates Apache Parquet blobs conforming to Hyland OpenArch's native {@code embedding.avsc} schema
 * for zero-copy bulk ingestion into OpenArch's vector index.
 */
public class OpenArchParquetEmbeddingsWriter {

    private static final Logger log = LoggerFactory.getLogger(OpenArchParquetEmbeddingsWriter.class);

    private final Schema schema;

    public OpenArchParquetEmbeddingsWriter() {
        this.schema = loadSchema();
    }

    public OpenArchParquetEmbeddingsWriter(Schema schema) {
        this.schema = Objects.requireNonNull(schema, "Schema must not be null");
    }

    public Schema getSchema() {
        return schema;
    }

    public static Schema loadSchema() {
        try (InputStream in = OpenArchParquetEmbeddingsWriter.class.getResourceAsStream("/schemas/embedding.avsc")) {
            if (in == null) {
                throw new IllegalStateException("embedding.avsc schema resource not found in /schemas/embedding.avsc");
            }
            return new Schema.Parser().parse(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load OpenArch embedding.avsc Avro schema", e);
        }
    }

    /**
     * DTO representing an embedding chunk to serialize into Parquet.
     */
    public record EmbeddingChunk(
            String id,
            String type,
            List<Double> vector,
            String text,
            String blobref,
            Integer page,
            Integer paragraph
    ) {
        public static EmbeddingChunk of(String id, String type, float[] vector, String text, String blobref) {
            List<Double> doubleList = new ArrayList<>(vector.length);
            for (float f : vector) {
                doubleList.add((double) f);
            }
            return new EmbeddingChunk(id, type, doubleList, text, blobref, null, null);
        }

        public static EmbeddingChunk of(String id, String type, List<Double> vector, String text, String blobref) {
            return new EmbeddingChunk(id, type, vector, text, blobref, null, null);
        }
    }

    /**
     * Maps an EmbeddingChunk to an Avro GenericRecord matching embedding.avsc.
     */
    public GenericRecord createAvroRecord(EmbeddingChunk chunk) {
        GenericRecord record = new GenericData.Record(schema);
        
        String chunkId = (chunk.id() != null && !chunk.id().isBlank())
                ? chunk.id()
                : UUID.randomUUID().toString();
        record.put("id", chunkId);
        record.put("type", chunk.type() != null ? chunk.type() : "default");
        record.put("vector", chunk.vector() != null ? chunk.vector() : Collections.emptyList());
        record.put("text", chunk.text() != null ? chunk.text() : "");
        record.put("blobref", chunk.blobref());

        // Location record
        Schema locationSchema = schema.getField("location").schema();
        GenericRecord locationRecord = new GenericData.Record(locationSchema);

        if (chunk.page() != null || chunk.paragraph() != null) {
            Schema textUnion = locationSchema.getField("text").schema();
            Schema textLocationSchema = textUnion.getTypes().stream()
                    .filter(s -> s.getType() == Schema.Type.RECORD)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("TextLocation schema record not found in union"));
            
            GenericRecord textLoc = new GenericData.Record(textLocationSchema);
            textLoc.put("page", chunk.page() != null ? chunk.page() : 0);
            textLoc.put("paragraph", chunk.paragraph() != null ? chunk.paragraph() : 0);
            locationRecord.put("text", textLoc);
        } else {
            locationRecord.put("text", null);
        }

        locationRecord.put("position", null);
        locationRecord.put("timestamp", null);
        locationRecord.put("spreadsheet", null);
        record.put("location", locationRecord);

        return record;
    }

    /**
     * Writes a batch of embedding chunks into a columnar Parquet file conforming to OpenArch schema.
     */
    public void writeParquet(List<EmbeddingChunk> chunks, File destinationFile) throws IOException {
        Objects.requireNonNull(destinationFile, "Destination file must not be null");
        Path hadoopPath = new Path(destinationFile.toURI());

        log.debug("Writing {} embedding chunks to Parquet file: {}", chunks.size(), destinationFile.getAbsolutePath());

        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(hadoopPath)
                .withSchema(schema)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .withRowGroupSize(1024 * 1024)
                .withPageSize(64 * 1024)
                .build()) {

            for (EmbeddingChunk chunk : chunks) {
                GenericRecord record = createAvroRecord(chunk);
                writer.write(record);
            }
        }
        log.info("Successfully generated OpenArch Parquet embeddings file ({} chunks, {} bytes): {}",
                chunks.size(), destinationFile.length(), destinationFile.getName());
    }
}
