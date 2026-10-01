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

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.doxis.output.client.DoxisClient;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Resolves the target Doxis dataset once and makes sure it carries every column the connector writes.
 * Resolution order: the configured {@code dataset-id}, then a dataset whose name matches {@code dataset-name},
 * then (when {@code auto-create-dataset} is on) a newly created dataset.
 */
public class DoxisDatasetManager {

    private static final Logger log = LoggerFactory.getLogger(DoxisDatasetManager.class);

    private final DoxisClient client;
    private final DoxisOutputProperties properties;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile String datasetId;

    public DoxisDatasetManager(DoxisClient client, DoxisOutputProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public String datasetId() throws IOException, InterruptedException {
        String resolved = datasetId;
        if (resolved != null) {
            return resolved;
        }
        lock.lock();
        try {
            if (datasetId == null) {
                datasetId = resolve();
            }
            return datasetId;
        } finally {
            lock.unlock();
        }
    }

    private String resolve() throws IOException, InterruptedException {
        String configuredId = properties.datasetId();
        if (configuredId != null && !configuredId.isBlank()) {
            ensureColumns(client.getDataset(configuredId));
            log.info("Using configured Doxis dataset '{}'.", configuredId);
            return configuredId;
        }

        for (JsonNode dataset : client.listDatasets()) {
            if (properties.datasetName().equals(dataset.path("name").asText())) {
                String id = dataset.path("id").asText();
                ensureColumns(client.getDataset(id));
                log.info("Resolved Doxis dataset '{}' by name to id '{}'.", properties.datasetName(), id);
                return id;
            }
        }

        if (!properties.autoCreateDataset()) {
            throw new IOException("Doxis dataset '" + properties.datasetName()
                    + "' does not exist and auto-create-dataset is disabled");
        }
        String id = client.createDataset(properties.datasetName(), DoxisConstants.COLUMNS);
        log.info("Created Doxis dataset '{}' with id '{}'.", properties.datasetName(), id);
        return id;
    }

    private void ensureColumns(JsonNode dataset) throws IOException, InterruptedException {
        String id = dataset.path("id").asText();
        Set<String> existing = new HashSet<>();
        dataset.path("columns").forEach(column -> existing.add(column.path("slug").asText()));
        for (Map.Entry<String, String> column : DoxisConstants.COLUMNS.entrySet()) {
            if (!existing.contains(column.getKey())) {
                log.info("Adding missing column '{}' ({}) to Doxis dataset '{}'.", column.getKey(), column.getValue(), id);
                client.addColumn(id, column.getKey(), column.getValue());
            }
        }
    }
}
