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
package org.opencrawling.runtime;

import org.opencrawling.core.messaging.DocumentChunkMessage;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.runtime.config.KafkaConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class TestEmbeddingConsumer {

    private static final Logger log = LoggerFactory.getLogger(TestEmbeddingConsumer.class);
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final int dimensions;

    public TestEmbeddingConsumer(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${spring.ai.vectorstore.pgvector.dimensions:1536}") int dimensions) {
        this.kafkaTemplate = kafkaTemplate;
        this.dimensions = dimensions;
        log.info("Initialized TestEmbeddingConsumer with simulated dimensions: {}", dimensions);
    }

    @KafkaListener(topics = KafkaConfig.CHUNKS_TOPIC_NAME)
    public void consume(DocumentChunkMessage message) {
        log.info("[Test] Consumed chunk: {} for embedding simulation.", message.chunkId());
        
        // Generate a mock embedding vector matching the default dimensions (non-zero magnitude)
        float[] dummyEmbedding = new float[dimensions];
        dummyEmbedding[0] = 1.0f;
        
        DocumentEmbeddedMessage embeddedMsg = new DocumentEmbeddedMessage(
            message.documentId(),
            message.chunkId(),
            message.text(),
            message.metadata(),
            dummyEmbedding
        );
        
        try {
            kafkaTemplate.send(KafkaConfig.EMBEDDED_TOPIC_NAME, message.chunkId(), embeddedMsg).get();
            log.info("[Test] Published simulated embedded chunk: {} with dimensions: {}", message.chunkId(), dimensions);
        } catch (Exception e) {
            log.error("[Test] Failed to publish simulated embedded chunk", e);
        }
    }
}
