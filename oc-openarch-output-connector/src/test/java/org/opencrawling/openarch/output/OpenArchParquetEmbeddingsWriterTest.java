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
package org.opencrawling.openarch.output;

import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.openarch.output.parquet.OpenArchParquetEmbeddingsWriter;
import org.opencrawling.openarch.output.parquet.OpenArchParquetEmbeddingsWriter.EmbeddingChunk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class OpenArchParquetEmbeddingsWriterTest {

    @Test
    public void testWriteAndReadParquetEmbeddings(@TempDir java.nio.file.Path tempDir) throws IOException {
        OpenArchParquetEmbeddingsWriter writer = new OpenArchParquetEmbeddingsWriter();
        assertNotNull(writer.getSchema(), "Avro schema should be loaded successfully");

        List<EmbeddingChunk> chunks = new ArrayList<>();
        chunks.add(new EmbeddingChunk(
                "chunk-1",
                "mxbai-embed-large",
                List.of(0.12, -0.34, 0.56, 0.78),
                "First chunk of enterprise documentation.",
                "doc-parent-1",
                1,
                2
        ));
        chunks.add(new EmbeddingChunk(
                "chunk-2",
                "mxbai-embed-large",
                List.of(0.99, -0.88, 0.77, 0.66),
                "Second chunk covering security access control lists.",
                "doc-parent-1",
                2,
                1
        ));

        File destinationFile = tempDir.resolve("test-embeddings.parquet").toFile();
        writer.writeParquet(chunks, destinationFile);

        assertTrue(destinationFile.exists());
        assertTrue(destinationFile.length() > 0);

        // Read back with AvroParquetReader using LocalInputFile exactly like OpenArch ParquetEmbeddingsParser
        List<GenericRecord> readRecords = new ArrayList<>();
        LocalInputFile inputFile = new LocalInputFile(destinationFile.toPath());
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(inputFile).build()) {
            for (GenericRecord rec = reader.read(); rec != null; rec = reader.read()) {
                readRecords.add(rec);
            }
        }

        assertEquals(2, readRecords.size());

        GenericRecord rec1 = readRecords.get(0);
        assertEquals("chunk-1", rec1.get("id").toString());
        assertEquals("mxbai-embed-large", rec1.get("type").toString());
        assertEquals("First chunk of enterprise documentation.", rec1.get("text").toString());
        assertEquals("doc-parent-1", rec1.get("blobref").toString());

        @SuppressWarnings("unchecked")
        List<Double> vector1 = (List<Double>) rec1.get("vector");
        assertEquals(4, vector1.size());
        assertEquals(0.12, vector1.get(0), 0.001);

        GenericRecord location = (GenericRecord) rec1.get("location");
        assertNotNull(location);
        GenericRecord textLoc = (GenericRecord) location.get("text");
        assertNotNull(textLoc);
        assertEquals(1, textLoc.get("page"));
        assertEquals(2, textLoc.get("paragraph"));
    }
}
