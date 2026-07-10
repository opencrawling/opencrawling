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
    
    private final EmbeddingModel embeddingModel;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public EmbeddingConsumer(EmbeddingModel embeddingModel, KafkaTemplate<String, Object> kafkaTemplate) {
        this.embeddingModel = embeddingModel;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = KafkaConfig.CHUNKS_TOPIC_NAME)
    public void consume(DocumentChunkMessage message) {
        log.info("Received chunk for embedding: {}", message.chunkId());
        try {
            // Generate embedding for chunk text
            float[] embedding = embeddingModel.embed(message.text());
            
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
