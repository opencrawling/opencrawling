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
package org.opencrawling.iceberg.output.schema;

import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.types.Types;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IcebergTableManager {

    private static final Logger log = LoggerFactory.getLogger(IcebergTableManager.class);

    public static final Schema CANONICAL_SCHEMA = new Schema(
            Types.NestedField.required(1, "id", Types.StringType.get(), "Unique record UUID"),
            Types.NestedField.required(2, "document_id", Types.StringType.get(), "Canonical document ID"),
            Types.NestedField.optional(3, "source_type", Types.StringType.get(), "Repository connector source type"),
            Types.NestedField.optional(4, "document_uri", Types.StringType.get(), "Original repository URI or path"),
            Types.NestedField.optional(5, "title", Types.StringType.get(), "Document title or filename"),
            Types.NestedField.optional(6, "mime_type", Types.StringType.get(), "MIME content type"),
            Types.NestedField.optional(7, "content_length", Types.LongType.get(), "Binary length in bytes"),
            Types.NestedField.optional(8, "sha256_checksum", Types.StringType.get(), "SHA-256 binary checksum"),
            Types.NestedField.optional(9, "claim_check_uri", Types.StringType.get(), "Claim-Check object storage URI"),
            Types.NestedField.optional(10, "extracted_text", Types.StringType.get(), "Extracted plain text"),
            Types.NestedField.optional(11, "extracted_entities",
                    Types.MapType.ofOptional(12, 13, Types.StringType.get(), Types.StringType.get()), "Extracted entity attributes"),
            Types.NestedField.optional(14, "custom_metadata",
                    Types.MapType.ofOptional(15, 16, Types.StringType.get(), Types.StringType.get()), "Repository metadata key-values"),
            Types.NestedField.optional(17, "security_owner", Types.StringType.get(), "Primary security owner"),
            Types.NestedField.optional(18, "security_inheritance", Types.BooleanType.get(), "Whether security inheritance is enabled"),
            Types.NestedField.optional(19, "allowed_principals",
                    Types.ListType.ofOptional(20, Types.StringType.get()), "Allowed users and groups (Zero-Trust ACL)"),
            Types.NestedField.optional(21, "denied_principals",
                    Types.ListType.ofOptional(22, Types.StringType.get()), "Explicitly denied users and groups"),
            Types.NestedField.required(23, "action", Types.StringType.get(), "OIS document action: UPSERT or DELETE"),
            Types.NestedField.required(24, "crawled_at", Types.TimestampType.withoutZone(), "Crawl timestamp UTC")
    );

    private final Catalog catalog;
    private final IcebergOutputProperties properties;
    private final Map<TableIdentifier, Table> tableCache = new ConcurrentHashMap<>();

    public IcebergTableManager(Catalog catalog, IcebergOutputProperties properties) {
        this.catalog = catalog;
        this.properties = properties;
    }

    public Table resolveAndEnsureTable(RepositoryDocument document) {
        TableIdentifier tableId = resolveTableIdentifier(document);
        return tableCache.computeIfAbsent(tableId, this::getOrCreateTable);
    }

    public TableIdentifier resolveTableIdentifier(RepositoryDocument document) {
        String namespaceStr = properties.getNamespace() != null ? properties.getNamespace() : "opencrawling_ecm";
        Namespace namespace = Namespace.of(namespaceStr);

        if (properties.getTableStrategy() == IcebergOutputProperties.TableStrategy.PER_DOCUMENT_TYPE && document != null) {
            String docType = extractDocumentType(document);
            if (docType != null && !docType.isBlank()) {
                String sanitizedTable = sanitizeIdentifier(docType);
                return TableIdentifier.of(namespace, sanitizedTable);
            }
        }

        String defaultTable = properties.getTableName() != null ? properties.getTableName() : "enterprise_documents";
        return TableIdentifier.of(namespace, sanitizeIdentifier(defaultTable));
    }

    private Table getOrCreateTable(TableIdentifier tableId) {
        if (catalog.tableExists(tableId)) {
            log.info("Iceberg table '{}' already exists.", tableId);
            return catalog.loadTable(tableId);
        }

        if (!properties.isAutoCreateTable()) {
            throw new IllegalStateException("Iceberg table '" + tableId + "' does not exist and auto-create-table is false.");
        }

        ensureNamespaceExists(tableId.namespace());

        PartitionSpec spec = buildPartitionSpec(CANONICAL_SCHEMA);
        log.info("Creating new Iceberg table '{}' with schema version and partition spec: {}", tableId, spec);
        return catalog.createTable(tableId, CANONICAL_SCHEMA, spec);
    }

    private void ensureNamespaceExists(Namespace namespace) {
        if (catalog instanceof SupportsNamespaces supportsNamespaces) {
            try {
                if (!supportsNamespaces.namespaceExists(namespace)) {
                    log.info("Creating Iceberg namespace '{}'", namespace);
                    supportsNamespaces.createNamespace(namespace);
                }
            } catch (Exception e) {
                log.debug("Namespace creation attempt returned: {}", e.getMessage());
            }
        }
    }

    private PartitionSpec buildPartitionSpec(Schema schema) {
        IcebergOutputProperties.PartitionStrategy strategy = properties.getPartitionBy();
        if (strategy == null) {
            strategy = IcebergOutputProperties.PartitionStrategy.YEAR_MONTH;
        }

        PartitionSpec.Builder builder = PartitionSpec.builderFor(schema);
        switch (strategy) {
            case YEAR_MONTH -> {
                builder.month("crawled_at");
            }
            case SOURCE_AND_DATE -> {
                builder.identity("source_type");
                builder.month("crawled_at");
            }
            case NONE -> {
                return PartitionSpec.unpartitioned();
            }
        }
        return builder.build();
    }

    public void evolveSchemaIfNeeded(Table table, Map<String, String> entities) {
        if (!properties.isAutoSchemaEvolution() || entities == null || entities.isEmpty()) {
            return;
        }

        Schema currentSchema = table.schema();
        UpdateSchema updateSchema = table.updateSchema();
        boolean hasNewColumns = false;

        for (String entityKey : entities.keySet()) {
            String colName = sanitizeIdentifier(entityKey);
            if (currentSchema.findField(colName) == null) {
                log.info("Evolving Iceberg table '{}' with new dynamic entity column: '{}'", table.name(), colName);
                updateSchema.addColumn(colName, Types.StringType.get(), "Dynamically extracted entity: " + entityKey);
                hasNewColumns = true;
            }
        }

        if (hasNewColumns) {
            updateSchema.commit();
            table.refresh();
            tableCache.put(TableIdentifier.parse(table.name()), table);
        }
    }

    private String extractDocumentType(RepositoryDocument document) {
        if (document.metadata() == null) {
            return null;
        }
        for (String candidateKey : List.of("document_type", "doc_type", "type", "cmis:objectTypeId", "documentType")) {
            List<String> values = document.metadata().get(candidateKey);
            if (values != null && !values.isEmpty() && values.get(0) != null) {
                return values.get(0);
            }
        }
        return null;
    }

    public static String sanitizeIdentifier(String name) {
        if (name == null || name.isBlank()) {
            return "unspecified";
        }
        String sanitized = name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT);
        if (Character.isDigit(sanitized.charAt(0))) {
            sanitized = "col_" + sanitized;
        }
        return sanitized;
    }
}
