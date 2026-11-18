/*
 * Copyright © ${year} the original author or authors (michael@michaelcizmar.com)
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
package org.opencrawling.vespa;

import org.opencrawling.core.embedding.RequiredEmbeddings;
import ai.vespa.feed.client.FeedClient;
import ai.vespa.feed.client.OperationParameters;
import ai.vespa.feed.client.Result;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.core.text.TextExtractionResult;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.vespa.config.VespaOutputProperties;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.opencrawling.core.document.DocumentAction;

@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "vespa")
public class VespaOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(VespaOutputConnector.class);

    // Built from the code point rather than a source-level escape to strip NUL characters, which downstream stores reject.
    private static final String NUL_CHAR = Character.toString(0);

    private final FeedClient client;
    private final VespaOutputProperties properties;
    /**
     * Whether this connector was built for one job whose Vespa destination (endpoint and namespace) is not the one the runtime's
     * pipeline writer stores to: its documents are then sent to it directly. Fixed at construction.
     */
    private final boolean outsidePipeline;
    private final VespaDocumentMapper mapper;
    private final EmbeddingModel embeddingModel;
    private final TokenTextSplitter textSplitter;
    private final TextExtractionService textExtractionService;
    private final VespaChunkRemover chunkRemover;

    @Autowired
    public VespaOutputConnector(
            FeedClient client,
            VespaOutputProperties properties,
            VespaDocumentMapper mapper,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel,
            @Autowired(required = false) TextExtractionService textExtractionService) {
        this(client, properties, mapper, embeddingModel, textExtractionService, false);
    }

    /**
     * A connector built for one job ({@code ConnectorFactory}).
     *
     * @param outsidePipeline whether the runtime's pipeline writer does not store to this connector's destination
     *                        (endpoint and namespace), so a RAG run sends its documents here directly
     */
    public VespaOutputConnector(
            FeedClient client,
            VespaOutputProperties properties,
            VespaDocumentMapper mapper,
            EmbeddingModel embeddingModel,
            TextExtractionService textExtractionService,
            boolean outsidePipeline) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
        this.embeddingModel = embeddingModel;
        this.textSplitter = TokenTextSplitter.builder().build();
        this.textExtractionService = textExtractionService != null ? textExtractionService : TextExtractionService.noop();
        this.outsidePipeline = outsidePipeline;
        this.chunkRemover = new VespaChunkRemover(client, this.properties);
    }

    public VespaOutputConnector(
            FeedClient client,
            VespaOutputProperties properties,
            VespaDocumentMapper mapper,
            EmbeddingModel embeddingModel) {
        this(client, properties, mapper, embeddingModel, null);
    }

    @Override
    public String getName() {
        return "VespaOutputConnector";
    }

    /**
     * In RAG runs the Kafka pipeline's writer stores this output's chunks, so a run must not also send them here,
     * unless this connector was built for one job whose Vespa destination (endpoint and namespace) the writer does not write.
     */
    @Override
    public boolean writtenByPipeline(PipelineMode mode) {
        return mode == PipelineMode.RAG && !outsidePipeline;
    }

    /** The settings this connector writes with. */
    public VespaOutputProperties getProperties() {
        return properties;
    }

    @Override
    public void connect() throws Exception {
        // Connection is managed by the FeedClient bean lifecycle.
    }

    @Override
    public void disconnect() throws Exception {
        client.close();
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            if (document.action() == DocumentAction.DELETE) {
                // A tombstone has no content; it removes what earlier runs stored for the document.
                try {
                    int removed = chunkRemover.removeDocument(document.id(), document.uri());
                    log.info("Deleted document {} from Vespa ({} chunks).", document.id(), removed);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("Failed to delete document " + document.id() + " from Vespa", e);
                }
                return;
            }
            try (InputStream is = document.contentStream()) {
                byte[] contentBytes = is.readAllBytes();
                if (contentBytes.length == 0) {
                    log.warn("Document {} content is empty, skipping Vespa ingestion.", document.id());
                    return;
                }

                String text = extractText(contentBytes, document);
                if (text.isBlank()) {
                    log.warn("Document {} extracted text is empty, skipping Vespa ingestion.", document.id());
                    return;
                }
                log.info("Extracted {} characters from document: {}", text.length(), document.id());

                Map<String, Object> metadata = cleanedMetadata(document);
                // Kept in metadata_json: the source document id keys relationship-based authorization on read.
                metadata.put("documentId", document.id());
                Document aiDoc = new Document(document.id(), text, metadata);
                List<Document> chunks = textSplitter.apply(List.of(aiDoc));
                log.info("Split document into {} chunks for Vespa.", chunks.size());

                // Every chunk is embedded before anything is fed, so a failed chunk stores nothing of the document (#333).
                List<VespaDocumentMapper.VespaDocument> vespaDocuments = new ArrayList<>(chunks.size());
                for (Document chunk : chunks) {
                    String chunkId = document.id() + "_" + (chunk.getId() != null ? chunk.getId() : UUID.randomUUID().toString());
                    float[] embedding = computeEmbedding(chunk, document.id());
                    // Route to a dimension-specific document type dynamically based on the actual embedding
                    // produced, so a model change on the embedding side needs no redeploy or restart here.
                    String documentType = VespaDocumentMapper.resolveDocumentType(embedding.length, properties.documentType());
                    VespaDocumentMapper.VespaDocument vespaDocument = mapper.toDocument(
                            properties.namespace(), documentType, chunkId, chunk.getText(),
                            document.uri(), document.acl(), document.lastModified().toString(),
                            document.security(), embedding, metadata);
                    vespaDocuments.add(vespaDocument);
                }

                List<CompletableFuture<Result>> feeds = new ArrayList<>(vespaDocuments.size());
                for (VespaDocumentMapper.VespaDocument vespaDocument : vespaDocuments) {
                    feeds.add(VespaFeeds.put(client, vespaDocument));
                }

                FeedClient.await(feeds);
                log.info("Successfully fed {} chunks for document {} to Vespa.", feeds.size(), document.id());
            } catch (Exception e) {
                log.error("Error processing document {} for Vespa: {}", document.id(), e.getMessage());
                throw new RuntimeException("Failed to process document for Vespa: " + document.id(), e);
            }
        });
    }

    private String extractText(byte[] contentBytes, RepositoryDocument document) {
        TextExtractionResult result = textExtractionService.extractText(contentBytes, document.metadata());
        if (!result.success()) {
            log.warn("Text extraction failed for document {}: {}", document.id(), result.errorMessage());
        }
        return result.text();
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

    /** The chunk's real vector; a missing model, a failed call or an empty vector fails the document (#333). */
    private float[] computeEmbedding(Document chunk, String documentId) {
        RequiredEmbeddings.requireModel(embeddingModel, documentId);
        return RequiredEmbeddings.embed(text -> {
            try {
                return embeddingModel.embed(chunk);
            } catch (Exception e) {
                log.debug("Failed embedding from document metadata, trying to embed text directly.", e);
                return embeddingModel.embed(text);
            }
        }, chunk.getText(), documentId);
    }
}
