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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "opencrawling.consumer.ingestion.enabled", havingValue = "true", matchIfMissing = true)
public class IngestionConsumer {

    private static final Logger log = LoggerFactory.getLogger(IngestionConsumer.class);
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TokenTextSplitter textSplitter;
    private final Tika tika;

    public IngestionConsumer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.textSplitter = TokenTextSplitter.builder().build();
        this.tika = new Tika();
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("IngestionConsumer initialized successfully!");
    }

    @KafkaListener(topics = KafkaConfig.TOPIC_NAME)
    public void consume(IngestionMessage message) {
        log.info("Received document message from Kafka: {}", message.documentId());
        try {
            URI fileUri = new URI(message.uri());
            
            // Resolve local filesystem stream (Claim Check Pattern)
            try (InputStream contentStream = Files.newInputStream(Paths.get(fileUri))) {
                byte[] contentBytes = contentStream.readAllBytes();
                
                if (contentBytes.length == 0) {
                    log.warn("Document {} content is empty, skipping chunking.", message.documentId());
                    return;
                }

                // Extract raw text using Apache Tika
                String text = tika.parseToString(new java.io.ByteArrayInputStream(contentBytes));
                
                // Fallback for plain text if Tika fails but we have bytes
                if (text.isBlank() && contentBytes.length > 0) {
                    text = new String(contentBytes, java.nio.charset.StandardCharsets.UTF_8);
                }

                if (text.isBlank()) {
                    log.warn("Document {} extracted text is empty, skipping.", message.documentId());
                    return;
                }

                log.info("Extracted {} characters from document: {}", text.length(), message.documentId());

                // Map repository metadata to Vector Document metadata
                Map<String, Object> metadata = new HashMap<>(message.metadata());
                metadata.put("uri", message.uri());
                metadata.put("acl", message.acl());
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
                        message.embeddingModel()
                    );
                    kafkaTemplate.send(KafkaConfig.CHUNKS_TOPIC_NAME, chunkId, chunkMsg).get();
                }
                log.info("Successfully published all chunks for document: {}", message.documentId());
            }
        } catch (java.nio.file.NoSuchFileException e) {
            log.warn("Ingestion file no longer exists (possibly a temporary test file): {}", message.uri());
        } catch (Exception e) {
            log.error("Failed to process Kafka ingestion message: {}", message.documentId(), e);
        }
    }
}
