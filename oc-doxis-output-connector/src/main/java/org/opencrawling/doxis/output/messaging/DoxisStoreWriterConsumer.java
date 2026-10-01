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
package org.opencrawling.doxis.output.messaging;

import jakarta.annotation.PostConstruct;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.doxis.output.DoxisConstants;
import org.opencrawling.doxis.output.DoxisDatasetManager;
import org.opencrawling.doxis.output.DoxisRowMapper;
import org.opencrawling.doxis.output.client.DoxisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Decoupled writer: stores each embedded chunk from {@code opencrawling-embedded} as a chunk row
 * (keyed by chunk id, grouped by document id) and applies DELETE tombstones to every row of the document.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "doxis")
@ConditionalOnExpression("'${opencrawling.consumer.writer.enabled:false}' == 'true'")
public class DoxisStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DoxisStoreWriterConsumer.class);

    private final DoxisClient doxisClient;
    private final DoxisDatasetManager datasetManager;
    private final DoxisRowMapper rowMapper;

    public DoxisStoreWriterConsumer(DoxisClient doxisClient, DoxisDatasetManager datasetManager, DoxisRowMapper rowMapper) {
        this.doxisClient = doxisClient;
        this.datasetManager = datasetManager;
        this.rowMapper = rowMapper;
    }

    @PostConstruct
    public void init() {
        log.info("DoxisStoreWriterConsumer initialized against {}.", doxisClient.getBaseUrl());
    }

    @KafkaListener(topics = "opencrawling-embedded")
    public void consume(DocumentEmbeddedMessage message) {
        log.info("Received embedded chunk for Doxis storage: {}", message.chunkId());
        try {
            String datasetId = datasetManager.datasetId();

            if (message.action() == DocumentAction.DELETE) {
                List<String> rowIds = doxisClient.findRowIds(datasetId, DoxisConstants.COL_DOCUMENT_ID, message.documentId());
                for (String rowId : rowIds) {
                    doxisClient.deleteRow(datasetId, rowId);
                }
                log.info("Processed DELETE tombstone for document {}: removed {} Doxis row(s).", message.documentId(), rowIds.size());
                return;
            }

            List<String> existing = doxisClient.findRowIds(datasetId, DoxisConstants.COL_EXTERNAL_ID, message.chunkId());
            List<String> created = doxisClient.insertRows(datasetId, List.of(rowMapper.chunkRow(message)));
            for (String rowId : existing) {
                if (!created.contains(rowId)) {
                    doxisClient.deleteRow(datasetId, rowId);
                }
            }
            log.info("Successfully saved chunk {} to Doxis dataset '{}'.", message.chunkId(), datasetId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while storing chunk {} in Doxis.", message.chunkId());
        } catch (Exception e) {
            log.error("Failed to store embedded chunk in Doxis: {}", message.chunkId(), e);
        }
    }
}
