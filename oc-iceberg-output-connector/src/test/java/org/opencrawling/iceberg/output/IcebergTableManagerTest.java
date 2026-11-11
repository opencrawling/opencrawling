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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.opencrawling.iceberg.output.schema.IcebergTableManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class IcebergTableManagerTest {

    private InMemoryCatalog catalog;
    private IcebergOutputProperties properties;
    private IcebergTableManager tableManager;

    @BeforeEach
    public void setUp() {
        catalog = new InMemoryCatalog();
        Map<String, String> catalogProps = new HashMap<>();
        catalogProps.put(CatalogProperties.WAREHOUSE_LOCATION, "inmemory://test-warehouse");
        catalog.initialize("test_catalog", catalogProps);

        properties = new IcebergOutputProperties();
        properties.setNamespace("test_ecm");
        properties.setTableName("docs");
        properties.setAutoCreateTable(true);
        properties.setAutoSchemaEvolution(true);
        properties.setPartitionBy(IcebergOutputProperties.PartitionStrategy.NONE);

        tableManager = new IcebergTableManager(catalog, properties);
    }

    @Test
    public void testResolveTableIdentifierUnified() {
        RepositoryDocument doc = new RepositoryDocument("doc-1", "uri-1", null, Map.of(), null, Instant.now());
        TableIdentifier id = tableManager.resolveTableIdentifier(doc);

        assertThat(id.namespace().toString()).isEqualTo("test_ecm");
        assertThat(id.name()).isEqualTo("docs");
    }

    @Test
    public void testResolveTableIdentifierPerDocumentType() {
        properties.setTableStrategy(IcebergOutputProperties.TableStrategy.PER_DOCUMENT_TYPE);

        RepositoryDocument doc = new RepositoryDocument(
                "doc-1", "uri-1", null,
                Map.of("document_type", List.of("Supplier Invoice")),
                null, Instant.now()
        );
        TableIdentifier id = tableManager.resolveTableIdentifier(doc);

        assertThat(id.namespace().toString()).isEqualTo("test_ecm");
        assertThat(id.name()).isEqualTo("supplier_invoice");
    }

    @Test
    public void testResolveAndEnsureTableCreatesNewTable() {
        RepositoryDocument doc = new RepositoryDocument("doc-1", "uri-1", null, Map.of(), null, Instant.now());
        Table table = tableManager.resolveAndEnsureTable(doc);

        assertThat(table).isNotNull();
        assertThat(table.schema().findField("document_id")).isNotNull();
        assertThat(table.schema().findField("allowed_principals")).isNotNull();
        assertThat(table.schema().findField("denied_principals")).isNotNull();
        assertThat(table.schema().findField("action")).isNotNull();
    }

    @Test
    public void testEvolveSchemaIfNeeded() {
        RepositoryDocument doc = new RepositoryDocument("doc-1", "uri-1", null, Map.of(), null, Instant.now());
        Table table = tableManager.resolveAndEnsureTable(doc);

        assertThat(table.schema().findField("tax_code")).isNull();

        tableManager.evolveSchemaIfNeeded(table, Map.of("tax_code", "IT12345678901", "payment_method", "SEPA_TRANSFER"));

        table.refresh();
        assertThat(table.schema().findField("tax_code")).isNotNull();
        assertThat(table.schema().findField("payment_method")).isNotNull();
    }
}
