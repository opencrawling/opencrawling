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
package org.opencrawling.runtime;

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
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.iceberg.IcebergRepositoryConnector;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "spring.kafka.consumer.group-id=test-group-${random.uuid}"
})
@ActiveProfiles("test")
class IcebergConnectorIT {

    private static final Logger log = LoggerFactory.getLogger(IcebergConnectorIT.class);

    @Autowired
    private JobOrchestrator jobOrchestrator;

    @Autowired
    private OutputConnector outputConnector;

    @Autowired
    private List<VectorStore> vectorStores;

    @Autowired
    private org.opencrawling.core.claimcheck.LocalFileClaimCheckStore localFileClaimCheckStore;

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
    void testEndToEndIcebergCrawlAndVectorSearch() throws Exception {
        // Initialize the Iceberg Repository Connector
        IcebergRepositoryConnector icebergConnector = new IcebergRepositoryConnector(
            "in-memory",
            null,
            "inmemory://warehouse",
            "id"
        );
        icebergConnector.setCatalog(catalog);
        icebergConnector.connect();

        log.info("Running JobOrchestrator for Iceberg table 'test_db.test_table'...");
        jobOrchestrator.runJob(icebergConnector, outputConnector, "test_db.test_table");

        // Verify that the record has been ingested into the vector store
        log.info("Performing similarity search for 'Product A' with retries across vector stores...");
        List<Document> results = List.of();
        java.io.File claimFile = new java.io.File(localFileClaimCheckStore.getClaimsDir().toFile(), "101_Product_A");
        String expectedUri = claimFile.toURI().toString();

        for (int i = 0; i < 15; i++) {
            for (VectorStore store : vectorStores) {
                try {
                    results = store.similaritySearch(
                            SearchRequest.builder()
                                    .query("Product A")
                                    .filterExpression(new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                                            .eq("uri", expectedUri).build())
                                    .topK(5)
                                    .similarityThreshold(0.0)
                                    .build()
                    );
                    if (!results.isEmpty()) {
                        log.info("Found Iceberg document in Vector Store [{}] after {} seconds.", store.toString(), i);
                        break;
                    }
                } catch (Exception e) {
                    // Ignore dimension mismatch or table query errors
                }
            }
            if (!results.isEmpty()) {
                break;
            }
            Thread.sleep(1000);
        }

        log.info("Found {} results in Vector Store.", results.size());
        results.forEach(doc -> log.info("Iceberg Match: Content: {}", doc.getText()));

        // Assertions
        assertThat(results).withFailMessage("Expected at least one result in Vector Store search").isNotEmpty();
        assertThat(results.get(0).getText()).contains("Product A");
        assertThat(results.get(0).getMetadata()).containsKey("uri");
        assertThat(results.get(0).getMetadata().get("uri")).isEqualTo(expectedUri);

        icebergConnector.disconnect();
    }
}
