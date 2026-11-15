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
package org.opencrawling.iceberg.output;

import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.iceberg.output.schema.IcebergTableManager;
import org.opencrawling.iceberg.output.writer.IcebergRecordWriter;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class IcebergRecordWriterTest {

    private InMemoryCatalog catalog;
    private Table table;

    @BeforeEach
    public void setUp() {
        catalog = new InMemoryCatalog();
        Map<String, String> catalogProps = new HashMap<>();
        catalogProps.put(CatalogProperties.WAREHOUSE_LOCATION, "inmemory://test-warehouse");
        catalog.initialize("test_catalog", catalogProps);
        catalog.createNamespace(org.apache.iceberg.catalog.Namespace.of("test_db"));

        table = catalog.createTable(
                TableIdentifier.of("test_db", "test_records"),
                IcebergTableManager.CANONICAL_SCHEMA,
                PartitionSpec.unpartitioned()
        );
    }

    @Test
    public void testConvertToRecordWithSecurityAndEntities() {
        byte[] payload = "Hello Apache Iceberg Lakehouse!".getBytes(StandardCharsets.UTF_8);

        SecurityConfig security = new SecurityConfig(
                true,
                List.of(
                        new PermissionRule("alice", "user", "Alice", "read"),
                        new PermissionRule("role_finance", "group", "Finance Role", "read"),
                        new PermissionRule("malicious_user", "user", "Malicious User", "deny")
                )
        );

        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("title", List.of("Invoice #2026-999"));
        metadata.put("source_type", List.of("sharepoint"));
        metadata.put("mimeType", List.of("application/pdf"));
        metadata.put("entity.invoice_number", List.of("INV-2026-999"));
        metadata.put("entity.vendor_name", List.of("Acme Corp"));
        metadata.put("entity.amount", List.of("4500.00"));
        metadata.put("custom_department", List.of("Procurement"));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-inv-999",
                "https://sharepoint.com/finance/inv-999.pdf",
                new ByteArrayInputStream(payload),
                metadata,
                null,
                security,
                Instant.parse("2026-11-11T10:00:00Z"),
                DocumentAction.UPSERT
        );

        Record record = IcebergRecordWriter.convertToRecord(table, doc, payload);

        assertThat(record.getField("document_id")).isEqualTo("doc-inv-999");
        assertThat(record.getField("source_type")).isEqualTo("sharepoint");
        assertThat(record.getField("title")).isEqualTo("Invoice #2026-999");
        assertThat(record.getField("content_length")).isEqualTo((long) payload.length);
        assertThat((String) record.getField("sha256_checksum")).isNotEmpty();

        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) record.getField("allowed_principals");
        assertThat(allowed).containsExactlyInAnyOrder("user:alice", "group:role_finance");

        @SuppressWarnings("unchecked")
        List<String> denied = (List<String>) record.getField("denied_principals");
        assertThat(denied).containsExactly("user:malicious_user");

        @SuppressWarnings("unchecked")
        Map<String, String> entities = (Map<String, String>) record.getField("extracted_entities");
        assertThat(entities).containsEntry("invoice_number", "INV-2026-999");
        assertThat(entities).containsEntry("vendor_name", "Acme Corp");
        assertThat(entities).containsEntry("amount", "4500.00");
    }

    @Test
    public void testWriteRecordsAndCommit() throws Exception {
        byte[] payload = "Test bytes".getBytes(StandardCharsets.UTF_8);
        RepositoryDocument doc = new RepositoryDocument(
                "doc-test-1",
                "uri-test-1",
                new ByteArrayInputStream(payload),
                Map.of("title", List.of("Test Title")),
                null,
                Instant.now()
        );

        Record record = IcebergRecordWriter.convertToRecord(table, doc, payload);
        DataFile dataFile = IcebergRecordWriter.writeRecords(table, List.of(record));

        assertThat(dataFile).isNotNull();
        assertThat(dataFile.recordCount()).isEqualTo(1L);

        IcebergRecordWriter.commitDataFile(table, dataFile);

        table.refresh();
        assertThat(table.currentSnapshot()).isNotNull();
        assertThat(table.currentSnapshot().addedDataFiles(table.io())).hasSize(1);
    }
}
