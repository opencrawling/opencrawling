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
package org.opencrawling.vector;

import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

@Component
@Primary
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "pgvector", matchIfMissing = true)
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
                String text = "";
                try {
                    text = tika.parseToString(new java.io.ByteArrayInputStream(contentBytes));
                } catch (Exception e) {
                    log.warn("Tika failed to parse document {}: {}. Falling back to plain text check.", document.id(), e.getMessage());
                }
                
                // Fallback for plain text if Tika fails but we have bytes
                if (text.isBlank() && contentBytes.length > 0) {
                    String mimeType = String.valueOf(document.metadata().getOrDefault("mimeType", List.of("text/plain")));
                    if (mimeType.contains("text") || mimeType.contains("json") || mimeType.contains("xml") || mimeType.contains("csv")) {
                        text = new String(contentBytes, java.nio.charset.StandardCharsets.UTF_8);
                    }
                }

                // Remove null characters to prevent PostgreSQL "invalid byte sequence for encoding UTF8: 0x00" error
                text = text.replace("\u0000", "");

                if (text.isBlank()) {
                    log.warn("Document {} extracted text is empty, skipping vector store.", document.id());
                    return;
                }

                log.info("Extracted {} characters from document: {}", text.length(), document.id());

                // Map repository metadata to Vector Document metadata
                Map<String, Object> metadata = new HashMap<>();
                document.metadata().forEach((key, val) -> {
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
