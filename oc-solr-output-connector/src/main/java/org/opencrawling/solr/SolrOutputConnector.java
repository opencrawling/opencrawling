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
package org.opencrawling.solr;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.apache.tika.Tika;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.solr.config.SolrOutputProperties;
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
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "solr")
public class SolrOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(SolrOutputConnector.class);
    private static final String NUL_CHAR = Character.toString(0);

    private final SolrClient solrClient;
    private final SolrOutputProperties properties;
    private final EmbeddingModel embeddingModel;
    private final TokenTextSplitter textSplitter;
    private final Tika tika;

    @Autowired
    public SolrOutputConnector(
            SolrClient solrClient,
            SolrOutputProperties properties,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        this.solrClient = solrClient;
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.textSplitter = TokenTextSplitter.builder().build();
        this.tika = new Tika();
    }

    @Override
    public String getName() {
        return "SolrOutputConnector";
    }

    @Override
    public void connect() throws Exception {
        // Managed by SolrClient bean lifecycle
    }

    @Override
    public void disconnect() throws Exception {
        if (solrClient != null) {
            solrClient.close();
        }
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            try (InputStream is = document.contentStream()) {
                byte[] contentBytes = is.readAllBytes();
                if (contentBytes.length == 0) {
                    log.warn("Document {} content is empty, skipping Solr ingestion.", document.id());
                    return;
                }

                String text = extractText(contentBytes, document);
                if (text.isBlank()) {
                    log.warn("Document {} extracted text is empty, skipping Solr ingestion.", document.id());
                    return;
                }
                log.info("Extracted {} characters from document: {}", text.length(), document.id());

                Map<String, Object> metadata = cleanedMetadata(document);
                Document aiDoc = new Document(document.id(), text, metadata);
                List<Document> chunks = textSplitter.apply(List.of(aiDoc));
                log.info("Split document into {} chunks for Solr.", chunks.size());

                List<SolrInputDocument> solrDocs = new ArrayList<>();
                for (Document chunk : chunks) {
                    String chunkId = document.id() + "_" + (chunk.getId() != null ? chunk.getId() : UUID.randomUUID().toString());
                    float[] embedding = computeEmbedding(chunk);

                    SolrInputDocument doc = new SolrInputDocument();
                    doc.addField(SolrConstants.FIELD_ID, chunkId);
                    doc.addField(SolrConstants.FIELD_TEXT, chunk.getText());
                    doc.addField(SolrConstants.FIELD_URI, document.uri());
                    doc.addField(SolrConstants.FIELD_ACL, document.acl());
                    doc.addField(SolrConstants.FIELD_LAST_MODIFIED, document.lastModified() != null ? document.lastModified().toString() : "");

                    // Convert float[] embedding to List<Float> for Solr SolrInputDocument DenseVectorField
                    List<Float> vectorList = new ArrayList<>(embedding.length);
                    for (float f : embedding) {
                        vectorList.add(f);
                    }
                    doc.addField(SolrConstants.FIELD_EMBEDDINGS, vectorList);

                    // Add metadata
                    metadata.forEach((key, val) -> {
                        if (!SolrConstants.FIELD_ID.equals(key) && !SolrConstants.FIELD_TEXT.equals(key)) {
                            doc.addField(key, val);
                        }
                    });

                    solrDocs.add(doc);
                }

                if (!solrDocs.isEmpty()) {
                    solrClient.add(properties.collection(), solrDocs, properties.commitWithinMs());
                    log.info("Successfully indexed {} chunks for document {} in Solr collection '{}'.",
                            solrDocs.size(), document.id(), properties.collection());
                }
            } catch (Exception e) {
                log.error("Error processing document {} for Solr: {}", document.id(), e.getMessage(), e);
                throw new RuntimeException("Failed to process document for Solr: " + document.id(), e);
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
