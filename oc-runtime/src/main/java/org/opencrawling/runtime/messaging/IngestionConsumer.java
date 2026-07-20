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

import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.messaging.DocumentChunkMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.claimcheck.ClaimCheckProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;

@Component
@ConditionalOnProperty(name = "opencrawling.consumer.ingestion.enabled", havingValue = "true", matchIfMissing = true)
public class IngestionConsumer {

    private static final Logger log = LoggerFactory.getLogger(IngestionConsumer.class);
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TokenTextSplitter textSplitter;
    private final Tika tika;
    private final ClaimCheckStore claimCheckStore;
    private final ClaimCheckProperties claimCheckProperties;

    public IngestionConsumer(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Qualifier("claimCheckStore") ClaimCheckStore claimCheckStore,
            ClaimCheckProperties claimCheckProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.claimCheckStore = claimCheckStore;
        this.claimCheckProperties = claimCheckProperties;
        this.textSplitter = TokenTextSplitter.builder().build();
        this.tika = new Tika();
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("IngestionConsumer initialized successfully with ClaimCheckStore: {}!", claimCheckStore.getClass().getSimpleName());
    }

    @KafkaListener(topics = KafkaConfig.TOPIC_NAME)
    public void consume(IngestionMessage message) {
        log.info("Received document message from Kafka: {}", message.documentId());
        try {
            URI fileUri = URI.create(message.uri());
            
            // Resolve stream via ClaimCheckStore (supports local filesystem, Apache Ozone, S3, etc.)
            try (InputStream contentStream = claimCheckStore.get(fileUri)) {
                byte[] contentBytes = contentStream.readAllBytes();
                
                if (contentBytes.length == 0) {
                    log.warn("Document {} content is empty, skipping chunking.", message.documentId());
                    return;
                }

                // Extract raw text using Apache Tika
                String text = "";
                try {
                    text = tika.parseToString(new java.io.ByteArrayInputStream(contentBytes));
                } catch (Exception e) {
                    log.warn("Tika failed to parse document {}: {}. Falling back to plain text check.", message.documentId(), e.getMessage());
                }
                
                // Fallback for plain text if Tika fails but we have bytes
                if (text.isBlank() && contentBytes.length > 0) {
                    String mimeType = String.valueOf(message.metadata().getOrDefault("mimeType", List.of("text/plain")));
                    if (mimeType.contains("text") || mimeType.contains("json") || mimeType.contains("xml") || mimeType.contains("csv")) {
                        text = new String(contentBytes, java.nio.charset.StandardCharsets.UTF_8);
                    }
                }

                // Remove null characters to prevent PostgreSQL "invalid byte sequence for encoding UTF8: 0x00" error
                text = text.replace("\u0000", "");

                if (text.isBlank()) {
                    log.warn("Document {} extracted text is empty, skipping.", message.documentId());
                    return;
                }

                log.info("Extracted {} characters from document: {}", text.length(), message.documentId());

                // Map repository metadata to Vector Document metadata
                Map<String, Object> metadata = new HashMap<>();
                message.metadata().forEach((key, val) -> {
                    if (val != null) {
                        List<String> cleanedList = new ArrayList<>();
                        for (String s : val) {
                            if (s != null) {
                                cleanedList.add(s.replace("\u0000", ""));
                            }
                        }
                        metadata.put(key, cleanedList);
                    }
                });
                metadata.put("uri", message.uri());
                metadata.put("acl", message.acl());
                metadata.put("security", message.security());
                metadata.put("lastModified", message.lastModified());
                
                // Construct Spring AI Document
                Document aiDoc = new Document(message.documentId(), text, metadata);
                
                // Chunk the document using TokenTextSplitter
                List<Document> chunks = textSplitter.apply(List.of(aiDoc));
                log.info("Split document {} into {} chunks. Publishing to Kafka topic: {}", 
                    message.documentId(), chunks.size(), KafkaConfig.CHUNKS_TOPIC_NAME);
                
                for (Document chunk : chunks) {
                    String chunkId = chunk.getId();
                    if (chunkId == null || chunkId.equals(message.documentId())) {
                        chunkId = UUID.randomUUID().toString();
                    }
                    DocumentChunkMessage chunkMsg = new DocumentChunkMessage(
                        message.documentId(),
                        chunkId,
                        chunk.getText(),
                        chunk.getMetadata(),
                        message.transformationConnector(),
                        message.transformationEngine(),
                        message.transformationConfig()
                    );
                    kafkaTemplate.send(KafkaConfig.CHUNKS_TOPIC_NAME, chunkId, chunkMsg).get();
                }
                log.info("Successfully published all chunks for document: {}", message.documentId());
            }

            if (claimCheckProperties.isCleanupOnConsume()) {
                try {
                    claimCheckStore.delete(fileUri);
                } catch (Exception e) {
                    log.debug("Cleanup of claim check object {} failed or skipped: {}", fileUri, e.getMessage());
                }
            }
        } catch (java.nio.file.NoSuchFileException e) {
            log.warn("Ingestion file no longer exists (possibly a temporary test file): {}", message.uri());
        } catch (Exception e) {
            log.error("Failed to process Kafka ingestion message: {}", message.documentId(), e);
        }
    }
}
