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
package org.opencrawling.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.RepositoryDocument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class IcebergRepositoryConnectorTest {

    private InMemoryCatalog catalog;
    private Schema schema;
    private TableIdentifier tableId;
    private Table table;

    @BeforeEach
    public void setUp() throws Exception {
        catalog = new InMemoryCatalog();
        Map<String, String> properties = new HashMap<>();
        properties.put("catalog-impl", "org.apache.iceberg.inmemory.InMemoryCatalog");
        properties.put("io-impl", "org.apache.iceberg.inmemory.InMemoryFileIO");
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, "inmemory://warehouse");
        catalog.initialize("in_memory", properties);
        catalog.createNamespace(Namespace.of("test_db"));

        schema = new Schema(
            Types.NestedField.required(1, "id", Types.IntegerType.get()),
            Types.NestedField.required(2, "name", Types.StringType.get()),
            Types.NestedField.required(3, "amount", Types.DoubleType.get()),
            Types.NestedField.optional(4, "last_modified", Types.StringType.get())
        );

        tableId = TableIdentifier.of("test_db", "test_table");
        table = catalog.createTable(tableId, schema, PartitionSpec.unpartitioned());

        // Write sample data to in-memory Parquet file and commit
        List<Record> records = new ArrayList<>();
        Record r1 = GenericRecord.create(schema);
        r1.setField("id", 101);
        r1.setField("name", "Product A");
        r1.setField("amount", 250.75);
        r1.setField("last_modified", "2026-07-16T12:00:00Z");
        records.add(r1);

        Record r2 = GenericRecord.create(schema);
        r2.setField("id", 102);
        r2.setField("name", "Product B");
        r2.setField("amount", 89.99);
        r2.setField("last_modified", "2026-07-16T13:00:00Z");
        records.add(r2);

        String dataFilePath = "inmemory://warehouse/test_db/test_table/data.parquet";
        OutputFile file = table.io().newOutputFile(dataFilePath);
        FileAppender<Record> appender = Parquet.write(file)
            .schema(schema)
            .createWriterFunc(messageType -> GenericParquetWriter.create(schema, messageType))
            .build();
        try {
            appender.addAll(records);
        } finally {
            appender.close();
        }

        DataFile dataFile = DataFiles.builder(PartitionSpec.unpartitioned())
            .withPath(dataFilePath)
            .withFileSizeInBytes(1024)
            .withRecordCount(records.size())
            .withFormat(FileFormat.PARQUET)
            .build();

        table.newAppend().appendFile(dataFile).commit();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (catalog != null) {
            catalog.close();
        }
    }

    @Test
    public void testScanIcebergTable() throws Exception {
        IcebergRepositoryConnector connector = new IcebergRepositoryConnector(
            "in-memory",
            null,
            "inmemory://warehouse",
            "id"
        );
        connector.setCatalog(catalog);

        connector.connect();

        List<RepositoryDocument> documents = connector.scan("test_db.test_table")
            .collectList()
            .block();

        assertThat(documents).isNotNull();
        assertThat(documents).hasSize(2);

        ObjectMapper mapper = new ObjectMapper();

        // Validate Document 101
        RepositoryDocument doc1 = documents.stream()
            .filter(d -> d.id().equals("101"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Document 101 not found"));

        assertThat(doc1.uri()).isEqualTo("iceberg://in_memory.test_db.test_table/101");
        assertThat(doc1.metadata().get("name")).containsExactly("Product A");
        assertThat(doc1.metadata().get("amount")).containsExactly("250.75");
        
        JsonNode json1 = mapper.readTree(doc1.contentStream());
        assertThat(json1.get("id").asInt()).isEqualTo(101);
        assertThat(json1.get("name").asText()).isEqualTo("Product A");
        assertThat(json1.get("amount").asDouble()).isEqualTo(250.75);

        // Validate Document 102
        RepositoryDocument doc2 = documents.stream()
            .filter(d -> d.id().equals("102"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Document 102 not found"));

        assertThat(doc2.uri()).isEqualTo("iceberg://in_memory.test_db.test_table/102");
        assertThat(doc2.metadata().get("name")).containsExactly("Product B");
        assertThat(doc2.metadata().get("amount")).containsExactly("89.99");

        JsonNode json2 = mapper.readTree(doc2.contentStream());
        assertThat(json2.get("id").asInt()).isEqualTo(102);
        assertThat(json2.get("name").asText()).isEqualTo("Product B");
        assertThat(json2.get("amount").asDouble()).isEqualTo(89.99);

        connector.disconnect();
    }
}
