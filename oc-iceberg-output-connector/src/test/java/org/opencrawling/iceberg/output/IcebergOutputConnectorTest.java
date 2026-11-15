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
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class IcebergOutputConnectorTest {

    private InMemoryCatalog catalog;
    private IcebergOutputProperties properties;
    private IcebergOutputConnector connector;

    @BeforeEach
    public void setUp() throws Exception {
        catalog = new InMemoryCatalog();
        Map<String, String> catalogProps = new HashMap<>();
        catalogProps.put(CatalogProperties.WAREHOUSE_LOCATION, "inmemory://test-warehouse");
        catalog.initialize("in_memory_test", catalogProps);

        properties = new IcebergOutputProperties();
        properties.setCatalogType("in-memory");
        properties.setNamespace("enterprise_ecm");
        properties.setTableName("contracts");
        properties.setAutoCreateTable(true);
        properties.setAutoSchemaEvolution(true);
        properties.setPartitionBy(IcebergOutputProperties.PartitionStrategy.NONE);

        connector = new IcebergOutputConnector(properties, catalog, null);
        connector.connect();
    }

    @AfterEach
    public void tearDown() throws Exception {
        connector.disconnect();
    }

    @Test
    public void testConnectorMetadata() {
        assertThat(connector.getName()).isEqualTo("IcebergOutputConnector");
        assertThat(connector.getCatalog()).isNotNull();
        assertThat(connector.getTableManager()).isNotNull();
    }

    @Test
    public void testSendUpsertAndSchemaEvolution() {
        byte[] content = "Vendor Agreement and Service Contract Details".getBytes(StandardCharsets.UTF_8);

        SecurityConfig security = new SecurityConfig(
                true,
                List.of(new PermissionRule("legal_team", "group", "Legal Team", "read"))
        );

        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("title", List.of("Master Services Agreement 2026"));
        metadata.put("source_type", List.of("alfresco"));
        metadata.put("entity.contract_number", List.of("CTR-2026-X1"));
        metadata.put("entity.total_value", List.of("150000.00"));
        metadata.put("custom_status", List.of("ACTIVE"));

        RepositoryDocument document = new RepositoryDocument(
                "contract-x1",
                "https://alfresco.company.com/d/ctr-x1.pdf",
                new ByteArrayInputStream(content),
                metadata,
                null,
                security,
                Instant.now(),
                DocumentAction.UPSERT
        );

        // Send document
        connector.send(document).block();

        // Verify table was created and snapshot committed
        TableIdentifier tableId = TableIdentifier.of("enterprise_ecm", "contracts");
        assertThat(catalog.tableExists(tableId)).isTrue();

        Table table = catalog.loadTable(tableId);
        assertThat(table.currentSnapshot()).isNotNull();
        assertThat(table.currentSnapshot().addedDataFiles(table.io())).hasSize(1);

        // Verify schema evolved with dynamic entity columns
        assertThat(table.schema().findField("contract_number")).isNotNull();
        assertThat(table.schema().findField("total_value")).isNotNull();
    }

    @Test
    public void testSendTombstoneDelete() {
        RepositoryDocument tombstone = RepositoryDocument.createTombstone(
                "deleted-doc-123",
                "https://sharepoint.com/deleted.pdf"
        );

        connector.send(tombstone).block();

        TableIdentifier tableId = TableIdentifier.of("enterprise_ecm", "contracts");
        assertThat(catalog.tableExists(tableId)).isTrue();

        Table table = catalog.loadTable(tableId);
        assertThat(table.currentSnapshot()).isNotNull();
    }
}
