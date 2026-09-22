/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.luxir;

import org.apache.tika.Tika;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.luxir.client.LuxirClient;
import org.opencrawling.luxir.config.LuxirOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "luxir")
public class LuxirOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(LuxirOutputConnector.class);
    private static final String NUL_CHAR = Character.toString(0);

    private final LuxirClient luxirClient;
    private final LuxirOutputProperties properties;
    private final EmbeddingModel embeddingModel;
    private final TokenTextSplitter textSplitter;
    private final Tika tika;

    public LuxirOutputConnector() {
        this(null, new LuxirOutputProperties(null, null, null, LuxirConstants.DEFAULT_DIMENSIONS, null, true, 0, LuxirConstants.DEFAULT_TIMEOUT_SECONDS), null);
    }

    @Autowired
    public LuxirOutputConnector(
            LuxirClient luxirClient,
            LuxirOutputProperties properties,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        this.luxirClient = luxirClient;
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.textSplitter = TokenTextSplitter.builder().build();
        this.tika = new Tika();
    }

    @Override
    public String getName() {
        return "LuxirOutputConnector";
    }

    @Override
    public void connect() throws Exception {
        // Managed by LuxirClient bean lifecycle
    }

    @Override
    public void disconnect() throws Exception {
        if (luxirClient != null) {
            luxirClient.close();
        }
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            try (InputStream is = document.contentStream()) {
                byte[] contentBytes = is.readAllBytes();
                if (contentBytes.length == 0) {
                    log.warn("Document {} content is empty, skipping Luxir ingestion.", document.id());
                    return;
                }

                String text = extractText(contentBytes, document);
                if (text.isBlank()) {
                    log.warn("Document {} extracted text is empty, skipping Luxir ingestion.", document.id());
                    return;
                }
                log.info("Extracted {} characters from document: {}", text.length(), document.id());

                Map<String, Object> metadata = cleanedMetadata(document);
                Document aiDoc = new Document(document.id(), text, metadata);
                List<Document> chunks = textSplitter.apply(List.of(aiDoc));
                log.info("Split document into {} chunks for Luxir.", chunks.size());

                List<Map<String, Object>> luxirDocs = new ArrayList<>();
                for (Document chunk : chunks) {
                    String chunkId = document.id() + "_" + (chunk.getId() != null ? chunk.getId() : UUID.randomUUID().toString());
                    float[] embedding = computeEmbedding(chunk);

                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put(LuxirConstants.FIELD_ID, chunkId);
                    doc.put(LuxirConstants.FIELD_DOC_ID, document.id());

                    // Title extraction
                    String title = document.id();
                    if (metadata.containsKey("title")) {
                        Object titleObj = metadata.get("title");
                        if (titleObj instanceof List<?> list && !list.isEmpty()) {
                            title = String.valueOf(list.getFirst());
                        } else if (titleObj != null) {
                            title = String.valueOf(titleObj);
                        }
                    }
                    doc.put(LuxirConstants.FIELD_TITLE, title);
                    doc.put(LuxirConstants.FIELD_TEXT, chunk.getText());
                    doc.put(LuxirConstants.FIELD_URI, document.uri());

                    // Multi-valued ACL tokens
                    List<String> acls = new ArrayList<>();
                    if (document.acl() != null && !document.acl().isBlank()) {
                        acls.addAll(Arrays.stream(document.acl().split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList());
                    }
                    doc.put(LuxirConstants.FIELD_ACL, acls);

                    if (document.lastModified() != null) {
                        doc.put(LuxirConstants.FIELD_LAST_MODIFIED, document.lastModified().toString());
                    }

                    // Convert float[] to List<Float>
                    List<Float> vectorList = new ArrayList<>(embedding.length);
                    for (float f : embedding) {
                        vectorList.add(f);
                    }
                    doc.put(properties.vectorField(), vectorList);

                    // Add other metadata attributes
                    metadata.forEach((key, val) -> {
                        if (!"title".equals(key) && !LuxirConstants.FIELD_ID.equals(key) && !LuxirConstants.FIELD_TEXT.equals(key)) {
                            String targetKey = key.endsWith("_s") || key.endsWith("_t") || key.endsWith("_ss") ? key : key + "_s";
                            doc.put(targetKey, val);
                        }
                    });

                    luxirDocs.add(doc);
                }

                if (!luxirDocs.isEmpty()) {
                    luxirClient.updateDocuments(properties.collection(), luxirDocs, properties.autoCommit());
                    log.info("Successfully indexed {} chunks for document {} in Luxir collection '{}'.",
                            luxirDocs.size(), document.id(), properties.collection());
                }
            } catch (Exception e) {
                log.error("Error processing document {} for Luxir: {}", document.id(), e.getMessage(), e);
                throw new RuntimeException("Failed to process document for Luxir: " + document.id(), e);
            }
        });
    }

    private String extractText(byte[] contentBytes, RepositoryDocument document) {
        String text = "";
        try {
            text = tika.parseToString(new ByteArrayInputStream(contentBytes));
        } catch (Exception e) {
            log.warn("Tika failed to parse document {}: {}. Falling back to text check.", document.id(), e.getMessage());
        }

        if (text.isBlank()) {
            String mimeType = String.valueOf(document.metadata().getOrDefault("mimeType", List.of("text/plain")));
            if (mimeType.contains("text") || mimeType.contains("json") || mimeType.contains("xml") || mimeType.contains("csv")) {
                text = new String(contentBytes, StandardCharsets.UTF_8);
            }
        }

        return text.replace(NUL_CHAR, "");
    }

    private Map<String, Object> cleanedMetadata(RepositoryDocument document) {
        Map<String, Object> metadata = new HashMap<>();
        document.metadata().forEach((key, values) -> {
            if (values == null) {
                return;
            }
            List<String> cleaned = new ArrayList<>();
            for (String value : values) {
                if (value != null) {
                    cleaned.add(value.replace(NUL_CHAR, ""));
                }
            }
            metadata.put(key, cleaned);
        });
        return metadata;
    }

    private float[] computeEmbedding(Document chunk) {
        if (embeddingModel == null) {
            float[] fallback = new float[properties.dimensions()];
            fallback[0] = 1.0f;
            return fallback;
        }
        try {
            return embeddingModel.embed(chunk);
        } catch (Exception e) {
            log.debug("Failed embedding from document metadata, trying to embed text directly.", e);
            return embeddingModel.embed(chunk.getText());
        }
    }
}
