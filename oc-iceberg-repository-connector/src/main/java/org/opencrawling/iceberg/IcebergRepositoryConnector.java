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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.StructuredTaskScope;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetReaders;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.hive.HiveCatalog;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.rest.RESTCatalog;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;

@Component
public class IcebergRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(IcebergRepositoryConnector.class);

    private final String catalogType;
    private final String catalogUri;
    private final String warehouse;
    private final String idColumn;
    private final ObjectMapper objectMapper;

    private Catalog catalog;

    public IcebergRepositoryConnector() {
        this("in-memory", null, "tmp/iceberg-warehouse", null);
    }

    public IcebergRepositoryConnector(
            String catalogType,
            String catalogUri,
            String warehouse,
            String idColumn) {
        this.catalogType = catalogType != null ? catalogType.toLowerCase() : "in-memory";
        this.catalogUri = catalogUri;
        this.warehouse = warehouse != null ? warehouse : "tmp/iceberg-warehouse";
        this.idColumn = idColumn;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "IcebergConnector";
    }

    @Override
    public void connect() throws Exception {
        if (this.catalog != null) {
            log.info("Iceberg Catalog is already initialized/injected.");
            return;
        }
        log.info("Connecting to Iceberg Catalog of type: {}, URI: {}, warehouse: {}", catalogType, catalogUri, warehouse);
        Configuration conf = new Configuration();
        Map<String, String> properties = new HashMap<>();
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, warehouse);
        if (catalogUri != null && !catalogUri.isBlank()) {
            properties.put(CatalogProperties.URI, catalogUri);
        }

        switch (catalogType) {
            case "rest" -> {
                RESTCatalog restCatalog = new RESTCatalog();
                restCatalog.setConf(conf);
                restCatalog.initialize("rest_catalog", properties);
                this.catalog = restCatalog;
            }
            case "hive" -> {
                HiveCatalog hiveCatalog = new HiveCatalog();
                hiveCatalog.setConf(conf);
                hiveCatalog.initialize("hive_catalog", properties);
                this.catalog = hiveCatalog;
            }
            case "hadoop" -> {
                this.catalog = new HadoopCatalog(conf, warehouse);
            }
            case "in-memory" -> {
                InMemoryCatalog inMemoryCatalog = new InMemoryCatalog();
                inMemoryCatalog.initialize("in_memory_catalog", properties);
                this.catalog = inMemoryCatalog;
            }
            case "glue" -> {
                try {
                    Class<?> glueCatalogClass = Class.forName("org.apache.iceberg.aws.glue.GlueCatalog");
                    Catalog glueCatalog = (Catalog) glueCatalogClass.getDeclaredConstructor().newInstance();
                    try {
                        glueCatalogClass.getMethod("setConf", Configuration.class).invoke(glueCatalog, conf);
                    } catch (NoSuchMethodException e) {
                        // ignore if not present
                    }
                    glueCatalog.initialize("glue_catalog", properties);
                    this.catalog = glueCatalog;
                } catch (Exception e) {
                    throw new RuntimeException("AWS Glue Catalog dependencies are not available on the classpath. Please add 'iceberg-aws' to runtime dependencies.", e);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported Iceberg catalog type: " + catalogType);
        }
        log.info("Successfully connected to Iceberg Catalog");
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnecting from Iceberg Catalog.");
        if (catalog instanceof AutoCloseable closeable) {
            closeable.close();
        }
        this.catalog = null;
    }

    public void setCatalog(Catalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        try {
            if (catalog == null) {
                connect();
            }
            TableIdentifier tableId = TableIdentifier.parse(basePath);
            Table table = catalog.loadTable(tableId);
            List<ConnectorSchema.SchemaField> fields = table.schema().columns().stream()
                    .map(col -> new ConnectorSchema.SchemaField(
                            col.name(),
                            col.type().toString(),
                            col.doc()
                    ))
                    .toList();
            return new ConnectorSchema(fields);
        } catch (Exception e) {
            log.error("Failed to retrieve schema for Iceberg table: {}", basePath, e);
            throw new RuntimeException("Failed to retrieve schema for Iceberg table: " + basePath, e);
        }
    }

    @Override
    @SuppressWarnings("preview")
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            Thread.ofVirtual().start(() -> {
                try {
                    if (catalog == null) {
                        connect();
                    }
                    TableIdentifier tableId = TableIdentifier.parse(basePath);
                    Table table = catalog.loadTable(tableId);

                    TableScan scan = table.newScan();
                    CloseableIterable<FileScanTask> tasks = scan.planFiles();

                    log.info("Starting concurrent Iceberg table scan for: {} using Virtual Threads", basePath);

                    try (var scope = StructuredTaskScope.open()) {
                        for (FileScanTask task : tasks) {
                            scope.fork(org.opencrawling.observability.concurrency.ObservabilityTask.observed(() -> {
                                try (CloseableIterable<Record> reader = Parquet.read(table.io().newInputFile(task.file().path().toString()))
                                        .project(table.schema())
                                        .createReaderFunc(fileSchema -> GenericParquetReaders.buildReader(table.schema(), fileSchema))
                                        .build()) {
                                    for (Record record : reader) {
                                        RepositoryDocument doc = createDocument(record, table);
                                        sink.next(doc);
                                    }
                                } catch (Exception e) {
                                    log.error("Error reading Iceberg records in virtual thread for task: {}", task, e);
                                }
                                return null;
                            }));
                        }
                        scope.join();
                    }
                    log.info("Iceberg table scan complete for: {}", basePath);
                    sink.complete();
                } catch (Exception e) {
                    log.error("Failed scanning Iceberg table: {}", basePath, e);
                    sink.error(e);
                }
            });
        });
    }

    private RepositoryDocument createDocument(Record record, Table table) throws Exception {
        Map<String, Object> recordMap = recordToMap(record, table.schema());
        byte[] jsonBytes = objectMapper.writeValueAsBytes(recordMap);
        InputStream contentStream = new ByteArrayInputStream(jsonBytes);

        String docId = null;
        if (idColumn != null && recordMap.containsKey(idColumn)) {
            Object val = recordMap.get(idColumn);
            if (val != null) {
                docId = val.toString();
            }
        }
        if (docId == null) {
            java.util.Set<String> idFields = table.schema().identifierFieldNames();
            if (idFields != null && !idFields.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String fieldName : idFields) {
                    Object val = recordMap.get(fieldName);
                    if (val != null) {
                        if (!sb.isEmpty()) sb.append("_");
                        sb.append(val.toString());
                    }
                }
                if (!sb.isEmpty()) {
                    docId = sb.toString();
                }
            }
        }
        if (docId == null) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(jsonBytes);
                StringBuilder hexString = new StringBuilder();
                for (byte b : hash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                docId = hexString.toString();
            } catch (Exception e) {
                docId = UUID.randomUUID().toString();
            }
        }

        String uri = "iceberg://" + table.name() + "/" + docId;

        Map<String, List<String>> docMetadata = new HashMap<>();
        recordMap.forEach((k, v) -> {
            if (v != null) {
                docMetadata.put(k, List.of(v.toString()));
            }
        });

        // Set content details in metadata
        docMetadata.put("mimeType", List.of("application/json"));
        docMetadata.put("sizeInBytes", List.of(String.valueOf(jsonBytes.length)));

        Instant lastModified = Instant.now();
        for (String tsCol : List.of("last_modified", "updated_at", "timestamp", "date")) {
            if (recordMap.containsKey(tsCol)) {
                Object val = recordMap.get(tsCol);
                if (val instanceof Instant instant) {
                    lastModified = instant;
                    break;
                } else if (val instanceof Long l) {
                    lastModified = Instant.ofEpochMilli(l);
                    break;
                } else if (val != null) {
                    try {
                        lastModified = Instant.parse(val.toString());
                        break;
                    } catch (Exception ignored) {}
                }
            }
        }

        return new RepositoryDocument(
            docId,
            uri,
            contentStream,
            docMetadata,
            "public",
            lastModified
        );
    }

    private Map<String, Object> recordToMap(Record record, org.apache.iceberg.Schema schema) {
        Map<String, Object> map = new HashMap<>();
        List<org.apache.iceberg.types.Types.NestedField> fields = schema.columns();
        for (int i = 0; i < fields.size(); i++) {
            org.apache.iceberg.types.Types.NestedField field = fields.get(i);
            map.put(field.name(), record.getField(field.name()));
        }
        return map;
    }
}
