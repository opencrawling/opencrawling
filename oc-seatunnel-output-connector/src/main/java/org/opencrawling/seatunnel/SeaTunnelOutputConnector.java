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
package org.opencrawling.seatunnel;

import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.core.text.PipesForkTextExtractor;
import org.opencrawling.core.text.TextExtractionResult;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.seatunnel.client.SeaTunnelJobConfigBuilder;
import org.opencrawling.seatunnel.client.SeaTunnelRestClient;
import org.opencrawling.seatunnel.config.SeaTunnelOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "seatunnel")
public class SeaTunnelOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(SeaTunnelOutputConnector.class);
    private static final String NUL_CHAR = Character.toString(0);

    private final SeaTunnelRestClient restClient;
    private final SeaTunnelOutputProperties properties;
    private final SeaTunnelJobConfigBuilder jobConfigBuilder;
    private final EmbeddingModel embeddingModel;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TokenTextSplitter textSplitter;
    private final TextExtractionService textExtractionService;

    public SeaTunnelOutputConnector() {
        this(null, new SeaTunnelOutputProperties(null, null, null, 5000, 4, null, null, null, null, 1024, false, 30), null, null, null);
    }

    public SeaTunnelOutputConnector(
            SeaTunnelRestClient restClient,
            SeaTunnelOutputProperties properties,
            EmbeddingModel embeddingModel,
            KafkaTemplate<String, Object> kafkaTemplate) {
        this(restClient, properties, embeddingModel, kafkaTemplate, null);
    }

    @Autowired
    public SeaTunnelOutputConnector(
            @Autowired(required = false) SeaTunnelRestClient restClient,
            SeaTunnelOutputProperties properties,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel,
            @Autowired(required = false) KafkaTemplate<String, Object> kafkaTemplate,
            @Autowired(required = false) TextExtractionService textExtractionService) {
        this.properties = properties != null ? properties : new SeaTunnelOutputProperties(null, null, null, 5000, 4, null, null, null, null, 1024, false, 30);
        this.restClient = restClient != null ? restClient : new SeaTunnelRestClient(this.properties.restUrl(), this.properties.timeoutSeconds());
        this.jobConfigBuilder = new SeaTunnelJobConfigBuilder(this.properties);
        this.embeddingModel = embeddingModel;
        this.kafkaTemplate = kafkaTemplate;
        this.textSplitter = TokenTextSplitter.builder().build();
        this.textExtractionService = textExtractionService != null ? textExtractionService : new PipesForkTextExtractor();
    }

    @Override
    public String getName() {
        return "SeaTunnelOutputConnector";
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting to Apache SeaTunnel Zeta cluster at {}...", properties.restUrl());
        boolean reachable = restClient.isReachable();
        if (reachable) {
            log.info("Successfully connected to SeaTunnel Zeta cluster at {}", properties.restUrl());
            if (properties.autoSubmitJob()) {
                try {
                    String hoconConfig = jobConfigBuilder.buildHoconConfig();
                    log.info("Auto-submitting SeaTunnel streaming fan-out job '{}'...", properties.jobName());
                    restClient.submitJob(properties.jobName(), hoconConfig);
                } catch (Exception e) {
                    log.warn("Notice: Auto-submitting SeaTunnel job encountered: {}. The pipeline may already be active.", e.getMessage());
                }
            }
        } else {
            log.warn("SeaTunnel cluster at {} did not respond to health check. Proceeding with deferred connection.", properties.restUrl());
        }
    }

    @Override
    public void disconnect() throws Exception {
        if (restClient != null) {
            restClient.close();
        }
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            try {
                if (document.action() == DocumentAction.DELETE) {
                    log.info("Received DELETE tombstone for SeaTunnel fan-out: document {}", document.id());
                    Map<String, Object> meta = cleanedMetadata(document);
                    meta.put(SeaTunnelConstants.FIELD_ACTION, DocumentAction.DELETE.name());
                    meta.put("rowKind", SeaTunnelConstants.ROW_KIND_DELETE);
                    if (document.uri() != null) {
                        meta.put("uri", document.uri());
                    }

                    DocumentEmbeddedMessage tombstoneMsg = new DocumentEmbeddedMessage(
                            document.id(),
                            document.id(),
                            "",
                            meta,
                            new float[0],
                            DocumentAction.DELETE
                    );

                    publishToKafkaOrBuffer(tombstoneMsg);
                    return;
                }

                byte[] contentBytes;
                try (InputStream is = document.contentStream()) {
                    contentBytes = is != null ? is.readAllBytes() : new byte[0];
                }

                if (contentBytes.length == 0) {
                    log.warn("Document {} content is empty, skipping SeaTunnel ingestion.", document.id());
                    return;
                }

                TextExtractionResult result = textExtractionService.extractText(contentBytes, document.metadata());
                String text = result.text();
                if (!result.success()) {
                    log.warn("Text extraction failed for document {}: {}", document.id(), result.errorMessage());
                }

                if (text.isBlank()) {
                    log.warn("Document {} extracted text is empty, skipping SeaTunnel ingestion.", document.id());
                    return;
                }

                Map<String, Object> metadata = cleanedMetadata(document);
                Document aiDoc = new Document(document.id(), text, metadata);
                List<Document> chunks = textSplitter.apply(List.of(aiDoc));
                log.info("Split document {} into {} chunks for SeaTunnel fan-out.", document.id(), chunks.size());

                for (Document chunk : chunks) {
                    String chunkId = document.id() + "_" + (chunk.getId() != null ? chunk.getId() : UUID.randomUUID().toString());
                    float[] embedding = computeEmbedding(chunk);

                    Map<String, Object> chunkMetadata = new LinkedHashMap<>(metadata);
                    chunkMetadata.put(SeaTunnelConstants.FIELD_DOC_ID, document.id());
                    chunkMetadata.put(SeaTunnelConstants.FIELD_URI, document.uri());
                    chunkMetadata.put(SeaTunnelConstants.FIELD_ACTION, DocumentAction.UPSERT.name());
                    chunkMetadata.put("rowKind", SeaTunnelConstants.ROW_KIND_INSERT);

                    if (document.acl() != null && !document.acl().isBlank()) {
                        chunkMetadata.put(SeaTunnelConstants.FIELD_ACL, document.acl());
                    }
                    if (document.lastModified() != null) {
                        chunkMetadata.put(SeaTunnelConstants.FIELD_LAST_MODIFIED, document.lastModified().toString());
                    }

                    DocumentEmbeddedMessage embeddedMessage = new DocumentEmbeddedMessage(
                            document.id(),
                            chunkId,
                            chunk.getText(),
                            chunkMetadata,
                            embedding,
                            DocumentAction.UPSERT
                    );

                    publishToKafkaOrBuffer(embeddedMessage);
                }
                log.info("Successfully dispatched {} chunks for document {} to SeaTunnel stream topic '{}'.",
                        chunks.size(), document.id(), properties.kafkaTopic());
            } catch (Exception e) {
                log.error("Error processing document {} for SeaTunnel: {}", document.id(), e.getMessage(), e);
                throw new RuntimeException("Failed to process document for SeaTunnel: " + document.id(), e);
            }
        });
    }

    private void publishToKafkaOrBuffer(DocumentEmbeddedMessage message) {
        if (kafkaTemplate != null) {
            kafkaTemplate.send(properties.kafkaTopic(), message.chunkId(), message);
        } else {
            log.debug("KafkaTemplate not injected, buffered message {} for SeaTunnel topic {}", message.chunkId(), properties.kafkaTopic());
        }
    }

    private Map<String, Object> cleanedMetadata(RepositoryDocument document) {
        Map<String, Object> metadata = new HashMap<>();
        if (document.metadata() != null) {
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
        }
        if (document.security() != null) {
            metadata.put("security", document.security());
        }
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

    public SeaTunnelRestClient getRestClient() {
        return restClient;
    }

    public SeaTunnelOutputProperties getProperties() {
        return properties;
    }
}
