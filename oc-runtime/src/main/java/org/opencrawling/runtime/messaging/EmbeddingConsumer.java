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

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.opencrawling.runtime.config.KafkaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name = "opencrawling.consumer.embedding.enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddingConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConsumer.class);
    
    private final EmbeddingModel defaultModel;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final java.util.concurrent.ConcurrentHashMap<String, EmbeddingModel> clientCache = new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    public EmbeddingConsumer(EmbeddingModel embeddingModel, KafkaTemplate<String, Object> kafkaTemplate) {
        this.defaultModel = embeddingModel;
        this.kafkaTemplate = kafkaTemplate;
    }

    private EmbeddingModel getEmbeddingModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return defaultModel;
        }
        return clientCache.computeIfAbsent(modelName, name -> {
            log.info("Creating dynamic OllamaEmbeddingModel client for model: {} on url: {}", name, ollamaBaseUrl);
            var ollamaApi = org.springframework.ai.ollama.api.OllamaApi.builder()
                .baseUrl(ollamaBaseUrl)
                .build();
            return org.springframework.ai.ollama.OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .options(org.springframework.ai.ollama.api.OllamaEmbeddingOptions.builder()
                    .model(name)
                    .build())
                .build();
        });
    }

    @KafkaListener(topics = KafkaConfig.CHUNKS_TOPIC_NAME)
    public void consume(DocumentChunkMessage message) {
        log.info("Received chunk for embedding: {} (Model: {})", message.chunkId(), message.embeddingModel());
        try {
            // Resolve dynamic or default embedding model
            EmbeddingModel model = getEmbeddingModel(message.embeddingModel());
            
            // Generate embedding for chunk text
            float[] embedding = model.embed(message.text());
            
            DocumentEmbeddedMessage embeddedMessage = new DocumentEmbeddedMessage(
                message.documentId(),
                message.chunkId(),
                message.text(),
                message.metadata(),
                embedding
            );
            
            // Publish to embedded topic
            kafkaTemplate.send(KafkaConfig.EMBEDDED_TOPIC_NAME, message.chunkId(), embeddedMessage).get();
            log.info("Successfully generated embedding and published chunk: {}", message.chunkId());
        } catch (Exception e) {
            log.error("Failed to generate embedding for chunk: {}", message.chunkId(), e);
        }
    }
}
