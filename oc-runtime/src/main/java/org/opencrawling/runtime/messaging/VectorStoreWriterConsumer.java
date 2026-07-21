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
package org.opencrawling.runtime.messaging;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.runtime.observability.TelemetryTraceStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "opencrawling.consumer.writer.enabled", havingValue = "true", matchIfMissing = true)
public class VectorStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreWriterConsumer.class);
    
    private final PgVectorStore vectorStore;
    private final PgVectorStore vectorStore384;
    private final PgVectorStore vectorStore768;
    private final PgVectorStore vectorStore1024;
    private final TelemetryTraceStore traceStore;

    public VectorStoreWriterConsumer(
            PgVectorStore vectorStore,
            PgVectorStore vectorStore384,
            PgVectorStore vectorStore768,
            PgVectorStore vectorStore1024,
            @org.springframework.beans.factory.annotation.Autowired(required = false) TelemetryTraceStore traceStore) {
        this.vectorStore = vectorStore;
        this.vectorStore384 = vectorStore384;
        this.vectorStore768 = vectorStore768;
        this.vectorStore1024 = vectorStore1024;
        this.traceStore = traceStore;
    }

    @KafkaListener(topics = KafkaConfig.EMBEDDED_TOPIC_NAME)
    public void consume(DocumentEmbeddedMessage message) {
        log.info("Received embedded chunk for storage: {} (Dimensions: {})", message.chunkId(), 
            message.embedding() != null ? message.embedding().length : 0);
        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        String jobId = message.documentId();

        try {
            Map<String, Object> metadata = new HashMap<>(message.metadata());
            // Put the precomputed embedding into the metadata so PrecomputedEmbeddingModel can extract it
            metadata.put("embedding", message.embedding());
            
            Document doc = new Document(message.chunkId(), message.text(), metadata);
            
            // Route to correct vector store dynamically based on embedding vector dimensions
            PgVectorStore targetStore = vectorStore;
            if (message.embedding() != null) {
                int len = message.embedding().length;
                targetStore = switch (len) {
                    case 384 -> vectorStore384;
                    case 768 -> vectorStore768;
                    case 1024 -> vectorStore1024;
                    default -> vectorStore;
                };
            }
            
            targetStore.add(List.of(doc));
            long duration = System.currentTimeMillis() - startTime;

            if (traceStore != null) {
                traceStore.recordSpan(new TelemetryTraceStore.SpanRecord(
                        UUID.randomUUID().toString(), traceId, jobId, "Indexing", "VectorStoreWriterConsumer",
                        startTime, duration, "SUCCESS", null, Map.of("db", "pgvector", "chunkId", message.chunkId())
                ));
            }

            log.info("Successfully saved chunk {} to Vector Store in {} ms.", message.chunkId(), duration);
        } catch (Exception e) {
            log.error("Failed to store embedded chunk: {}", message.chunkId(), e);
            if (traceStore != null) {
                traceStore.recordError(jobId, "ERROR", "VectorStoreWriterConsumer", "Failed to store embedded chunk: " + message.chunkId() + " - " + e.getMessage(), e.toString());
            }
        }
    }
}
