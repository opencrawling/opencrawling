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
package org.opencrawling.embedding.messaging;

import org.opencrawling.core.messaging.DocumentChunkMessage;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.embedding.EmbeddingModelFactory;
import org.opencrawling.embedding.config.KafkaConfig;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class EmbeddingConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConsumer.class);

    private final EmbeddingModelFactory modelFactory;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public EmbeddingConsumer(EmbeddingModelFactory modelFactory, KafkaTemplate<String, Object> kafkaTemplate) {
        this.modelFactory = modelFactory;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = KafkaConfig.CHUNKS_TOPIC_NAME)
    public void consume(DocumentChunkMessage message) {
        log.info("Received chunk for embedding: {} (Engine: {}, Connector: {})", 
            message.chunkId(), message.transformationEngine(), message.transformationConnector());
        try {
            // Resolve the dynamic embedding model client from factory
            EmbeddingModel model = modelFactory.getModel(
                message.transformationEngine(), 
                message.transformationConfig()
            );

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
