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

import jakarta.annotation.PostConstruct;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.data.Record;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.iceberg.output.catalog.IcebergCatalogFactory;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.opencrawling.iceberg.output.schema.IcebergTableManager;
import org.opencrawling.iceberg.output.writer.IcebergRecordWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "iceberg")
@EnableConfigurationProperties(IcebergOutputProperties.class)
public class IcebergOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(IcebergOutputConnector.class);

    private final IcebergOutputProperties properties;
    private Catalog catalog;
    private IcebergTableManager tableManager;
    private final ClaimCheckStore claimCheckStore;

    public IcebergOutputConnector() {
        this(new IcebergOutputProperties(), null, null);
    }

    public IcebergOutputConnector(IcebergOutputProperties properties) {
        this(properties, null, null);
    }

    @Autowired
    public IcebergOutputConnector(
            IcebergOutputProperties properties,
            @Autowired(required = false) Catalog catalog,
            @Autowired(required = false) @Qualifier("claimCheckStore") ClaimCheckStore claimCheckStore) {
        this.properties = properties != null ? properties : new IcebergOutputProperties();
        this.catalog = catalog;
        this.claimCheckStore = claimCheckStore;
    }

    @PostConstruct
    public void init() {
        try {
            connect();
        } catch (Exception e) {
            log.debug("IcebergOutputConnector deferred initialization: {}", e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "IcebergOutputConnector";
    }

    @Override
    public synchronized void connect() throws Exception {
        if (this.catalog == null) {
            this.catalog = IcebergCatalogFactory.createCatalog(this.properties);
        }
        if (this.tableManager == null) {
            this.tableManager = new IcebergTableManager(this.catalog, this.properties);
        }
        log.info("IcebergOutputConnector successfully connected to catalog '{}'", properties.getCatalogName());
    }

    @Override
    public synchronized void disconnect() throws Exception {
        log.info("Disconnecting IcebergOutputConnector.");
        if (catalog instanceof AutoCloseable closeable) {
            closeable.close();
        }
        this.catalog = null;
        this.tableManager = null;
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            try {
                if (catalog == null || tableManager == null) {
                    connect();
                }

                byte[] contentBytes = resolveContentBytes(document);

                Table table = tableManager.resolveAndEnsureTable(document);

                Map<String, String> entities = IcebergRecordWriter.extractEntitiesMap(document.metadata());
                tableManager.evolveSchemaIfNeeded(table, entities);

                Record record = IcebergRecordWriter.convertToRecord(table, document, contentBytes);

                DataFile dataFile = IcebergRecordWriter.writeRecords(table, List.of(record));
                IcebergRecordWriter.commitDataFile(table, dataFile);

                log.info("Successfully ingested document '{}' (action: {}) into Iceberg table '{}'",
                        document.id(), document.action(), table.name());
            } catch (Exception e) {
                log.error("Failed to ingest document '{}' into Iceberg: {}", document.id(), e.getMessage(), e);
                throw new RuntimeException("Failed to ingest document into Iceberg: " + document.id(), e);
            }
        });
    }

    public synchronized void commitBatch(RepositoryDocument prototype, List<RepositoryDocument> batch) throws Exception {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        if (catalog == null || tableManager == null) {
            connect();
        }

        Table table = tableManager.resolveAndEnsureTable(prototype);

        List<Record> records = new java.util.ArrayList<>(batch.size());
        for (RepositoryDocument doc : batch) {
            byte[] bytes = resolveContentBytes(doc);
            Map<String, String> entities = IcebergRecordWriter.extractEntitiesMap(doc.metadata());
            tableManager.evolveSchemaIfNeeded(table, entities);
            records.add(IcebergRecordWriter.convertToRecord(table, doc, bytes));
        }

        List<DataFile> dataFiles = IcebergRecordWriter.writePartitionedRecords(table, records);
        IcebergRecordWriter.commitDataFiles(table, dataFiles);
        log.info("Committed batch of {} documents to Iceberg table '{}'", batch.size(), table.name());
    }

    private byte[] resolveContentBytes(RepositoryDocument document) {
        try {
            if (document.contentStream() != null) {
                return document.contentStream().readAllBytes();
            }
            if (claimCheckStore != null && document.uri() != null) {
                try {
                    URI uri = URI.create(document.uri());
                    try (InputStream is = claimCheckStore.get(uri)) {
                        if (is != null) {
                            return is.readAllBytes();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("Could not read content bytes for document {}: {}", document.id(), e.getMessage());
        }
        return new byte[0];
    }

    public Catalog getCatalog() {
        return catalog;
    }

    public void setCatalog(Catalog catalog) {
        this.catalog = catalog;
        if (this.properties != null) {
            this.tableManager = new IcebergTableManager(catalog, this.properties);
        }
    }

    public IcebergTableManager getTableManager() {
        return tableManager;
    }

    public IcebergOutputProperties getProperties() {
        return properties;
    }
}
