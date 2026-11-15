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
package org.opencrawling.openarch.output.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.openarch.output.client.OpenArchClient;
import org.opencrawling.openarch.output.config.OpenArchOutputProperties;
import org.opencrawling.openarch.output.parquet.OpenArchParquetEmbeddingsWriter;
import org.opencrawling.openarch.output.parquet.OpenArchParquetEmbeddingsWriter.EmbeddingChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "openarch")
@ConditionalOnExpression("'${opencrawling.consumer.writer.enabled:false}' == 'true'")
public class OpenArchStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(OpenArchStoreWriterConsumer.class);

    private final OpenArchClient client;
    private final OpenArchOutputProperties properties;
    private final OpenArchParquetEmbeddingsWriter parquetWriter;
    private final List<EmbeddingChunk> chunkBuffer = Collections.synchronizedList(new ArrayList<>());

    @Autowired
    public OpenArchStoreWriterConsumer(OpenArchClient client, OpenArchOutputProperties properties) {
        this.client = client;
        this.properties = properties != null ? properties : new OpenArchOutputProperties();
        this.parquetWriter = new OpenArchParquetEmbeddingsWriter();
    }

    @KafkaListener(topics = "opencrawling-embedded")
    public void consume(DocumentEmbeddedMessage message) {
        log.info("Received embedded chunk for OpenArch storage: {} (Dimensions: {})",
                message.chunkId(), message.embedding() != null ? message.embedding().length : 0);

        if (message.action() == DocumentAction.DELETE) {
            log.info("Received DELETE tombstone for OpenArch chunk: {}", message.chunkId());
            return;
        }

        String embeddingsType = properties.getEmbeddingsType();
        EmbeddingChunk chunk = EmbeddingChunk.of(
                message.chunkId(),
                embeddingsType,
                message.embedding(),
                message.text(),
                message.documentId()
        );

        chunkBuffer.add(chunk);

        if (chunkBuffer.size() >= properties.getParquetBatchThreshold()) {
            flushBatch();
        }
    }

    public synchronized void flushBatch() {
        if (chunkBuffer.isEmpty()) {
            return;
        }
        List<EmbeddingChunk> batchToFlush;
        synchronized (chunkBuffer) {
            batchToFlush = new ArrayList<>(chunkBuffer);
            chunkBuffer.clear();
        }

        log.info("Flushing batch of {} vector chunks to OpenArch via Parquet vector bridge...", batchToFlush.size());
        try {
            File tempParquet = File.createTempFile("openarch-batch-embeddings-", ".parquet");
            try {
                parquetWriter.writeParquet(batchToFlush, tempParquet);
                try (InputStream parquetIn = new FileInputStream(tempParquet)) {
                    JsonNode uploadRes = client.uploadBlob(parquetIn, "embeddings-batch-" + System.currentTimeMillis() + ".parquet", "application/vnd.apache.parquet");
                    log.info("Uploaded Parquet batch to OpenArch (Upload ID: {})", uploadRes != null ? uploadRes.get("id") : "ok");
                }
            } finally {
                if (tempParquet.exists()) {
                    tempParquet.delete();
                }
            }
        } catch (Exception e) {
            log.error("Failed to flush Parquet embeddings batch to OpenArch: {}", e.getMessage(), e);
        }
    }
}
