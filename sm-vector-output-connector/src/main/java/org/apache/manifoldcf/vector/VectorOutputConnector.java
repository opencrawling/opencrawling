package org.apache.manifoldcf.vector;

import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.apache.manifoldcf.core.connector.OutputConnector;
import org.apache.manifoldcf.core.document.RepositoryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Component
public class VectorOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(VectorOutputConnector.class);
    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final Tika tika;

    public VectorOutputConnector(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.textSplitter = TokenTextSplitter.builder().build();
        this.tika = new Tika();
    }

    @Override
    public String getName() {
        return "VectorStoreOutputConnector";
    }

    @Override
    public void connect() throws Exception {}

    @Override
    public void disconnect() throws Exception {}

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            try (InputStream is = document.contentStream()) {
                byte[] contentBytes = is.readAllBytes();
                
                if (contentBytes.length == 0) {
                    log.warn("Document {} content is empty, skipping vector store.", document.id());
                    return;
                }

                // Extract raw text using Apache Tika (now from bytes to avoid stream issues)
                String text = tika.parseToString(new java.io.ByteArrayInputStream(contentBytes));
                
                // Fallback for plain text if Tika fails but we have bytes
                if (text.isBlank() && contentBytes.length > 0) {
                    text = new String(contentBytes, java.nio.charset.StandardCharsets.UTF_8);
                }

                if (text.isBlank()) {
                    log.warn("Document {} extracted text is empty, skipping vector store.", document.id());
                    return;
                }

                log.info("Extracted {} characters from document: {}", text.length(), document.id());

                // Map repository metadata to Vector Document metadata
                Map<String, Object> metadata = new HashMap<>(document.metadata());
                metadata.put("uri", document.uri());
                metadata.put("acl", document.acl());
                metadata.put("lastModified", document.lastModified().toString());
                
                // Construct Spring AI Document
                Document aiDoc = new Document(document.id(), text, metadata);
                
                // Chunk the document using TokenTextSplitter
                List<Document> chunks = textSplitter.apply(List.of(aiDoc));
                log.info("Split document into {} chunks for vector store.", chunks.size());
                
                // Persist the embedded chunks to the configured Vector Store
                vectorStore.add(chunks);
                log.info("Successfully added document {} to Vector Store.", document.id());
                
            } catch (Exception e) {
                log.error("Error processing document {}: {}", document.id(), e.getMessage());
                throw new RuntimeException("Failed to process document: " + document.id(), e);
            }
        });
    }
}
