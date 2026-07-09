package org.opencrawling.runtime.messaging;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.opencrawling.runtime.config.KafkaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class VectorStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreWriterConsumer.class);
    
    private final PgVectorStore vectorStore;

    public VectorStoreWriterConsumer(PgVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @KafkaListener(topics = KafkaConfig.EMBEDDED_TOPIC_NAME)
    public void consume(DocumentEmbeddedMessage message) {
        log.info("Received embedded chunk for storage: {}", message.chunkId());
        try {
            Map<String, Object> metadata = new HashMap<>(message.metadata());
            // Put the precomputed embedding into the metadata so PrecomputedEmbeddingModel can extract it
            metadata.put("embedding", message.embedding());
            
            Document doc = new Document(message.chunkId(), message.text(), metadata);
            
            // Add to PgVectorStore. This will invoke PrecomputedEmbeddingModel, 
            // which extracts the embedding from metadata and saves it directly.
            vectorStore.add(List.of(doc));
            
            log.info("Successfully saved chunk {} to Vector Store.", message.chunkId());
        } catch (Exception e) {
            log.error("Failed to store embedded chunk: {}", message.chunkId(), e);
        }
    }
}
