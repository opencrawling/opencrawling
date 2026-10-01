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
package org.opencrawling.doxis.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.doxis.output.client.DoxisClient;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Archives OpenCrawling documents into a Doxis AI.dp dataset: one row per document holding the original binary
 * (as an inline document cell, which Doxis then OCRs and indexes), the OIS descriptors and the OIS security model.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "doxis")
public class DoxisOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(DoxisOutputConnector.class);

    private final DoxisClient client;
    private final DoxisOutputProperties properties;
    private final DoxisDatasetManager datasetManager;
    private final DoxisRowMapper rowMapper;

    /**
     * No-arg constructor for {@link java.util.ServiceLoader} discovery; such an instance is not connected.
     */
    public DoxisOutputConnector() {
        this.client = null;
        this.properties = DoxisOutputProperties.defaults();
        this.datasetManager = null;
        this.rowMapper = new DoxisRowMapper(properties, new ObjectMapper());
    }

    /**
     * Builds a self-contained connector from per-job connector configuration.
     */
    public DoxisOutputConnector(DoxisOutputProperties properties) {
        this(new DoxisClient(properties.baseUrl(), properties.apiKey(),
                        Duration.ofSeconds(properties.timeoutSeconds()), properties.maxRetries()),
                properties, null, new DoxisRowMapper(properties, new ObjectMapper()));
    }

    @Autowired
    public DoxisOutputConnector(DoxisClient client, DoxisOutputProperties properties,
                                DoxisDatasetManager datasetManager, DoxisRowMapper rowMapper) {
        this.client = client;
        this.properties = properties;
        this.datasetManager = datasetManager != null ? datasetManager : new DoxisDatasetManager(client, properties);
        this.rowMapper = rowMapper;
    }

    @Override
    public String getName() {
        return "DoxisOutputConnector";
    }

    @Override
    public void connect() throws Exception {
        if (datasetManager != null) {
            datasetManager.datasetId();
        }
    }

    @Override
    public void disconnect() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            if (client == null) {
                throw new IllegalStateException("DoxisOutputConnector is not configured (no DoxisClient)");
            }
            try {
                String datasetId = datasetManager.datasetId();
                if (document.action() == DocumentAction.DELETE) {
                    delete(datasetId, document.id());
                } else {
                    upsert(datasetId, document);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while archiving document into Doxis: " + document.id(), e);
            } catch (Exception e) {
                log.error("Error archiving document {} into Doxis: {}", document.id(), e.getMessage(), e);
                throw new RuntimeException("Failed to archive document into Doxis: " + document.id(), e);
            }
        });
    }

    private void upsert(String datasetId, RepositoryDocument document) throws IOException, InterruptedException {
        byte[] content = readContent(document);
        List<String> existing = client.findRowIds(datasetId, DoxisConstants.COL_EXTERNAL_ID, document.id());

        if (!existing.isEmpty() && properties.conflictResolution() == DoxisOutputProperties.ConflictResolution.UPDATE_METADATA) {
            Map<String, Object> cells = rowMapper.documentMetadataCells(document, content);
            for (String rowId : existing) {
                client.patchRow(datasetId, rowId, cells);
            }
            log.info("Updated metadata of {} existing Doxis row(s) for document {}.", existing.size(), document.id());
            return;
        }

        // Insert first, then remove the previous rows, so a failed upload never loses the archived copy.
        List<String> created = client.insertRows(datasetId, List.of(rowMapper.documentRow(document, content)));
        for (String rowId : existing) {
            if (!created.contains(rowId)) {
                client.deleteRow(datasetId, rowId);
            }
        }
        log.info("Archived document {} into Doxis dataset '{}' as row {} ({} previous row(s) replaced).",
                document.id(), datasetId, created, existing.size());
    }

    private void delete(String datasetId, String documentId) throws IOException, InterruptedException {
        List<String> rowIds = client.findRowIds(datasetId, DoxisConstants.COL_DOCUMENT_ID, documentId);
        for (String rowId : rowIds) {
            client.deleteRow(datasetId, rowId);
        }
        log.info("Processed DELETE tombstone for document {}: removed {} Doxis row(s).", documentId, rowIds.size());
    }

    private byte[] readContent(RepositoryDocument document) throws IOException {
        if (document.contentStream() == null) {
            return null;
        }
        try (InputStream is = document.contentStream()) {
            return is.readAllBytes();
        }
    }
}
