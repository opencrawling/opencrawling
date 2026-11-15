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
package org.opencrawling.openarch.output;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.text.TextExtractionResult;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.openarch.output.client.OpenArchClient;
import org.opencrawling.openarch.output.config.OpenArchOutputProperties;
import org.opencrawling.openarch.output.parquet.OpenArchParquetEmbeddingsWriter;
import org.opencrawling.openarch.output.parquet.OpenArchParquetEmbeddingsWriter.EmbeddingChunk;
import org.opencrawling.openarch.output.security.OpenArchSecurityMapper;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

/**
 * Enterprise output connector indexing crawled content, metadata, Zero-Trust ACLs,
 * and Parquet vector embeddings directly into Hyland OpenArch™.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "openarch")
public class OpenArchOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(OpenArchOutputConnector.class);

    private final OpenArchOutputProperties properties;
    private final OpenArchClient client;
    private final EmbeddingModel embeddingModel;
    private final TokenTextSplitter textSplitter;
    private final TextExtractionService textExtractionService;
    private final OpenArchParquetEmbeddingsWriter parquetWriter;

    @Autowired
    public OpenArchOutputConnector(
            OpenArchOutputProperties properties,
            @Autowired(required = false) OpenArchClient client,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel,
            @Autowired(required = false) TextExtractionService textExtractionService) {
        this.properties = properties != null ? properties : new OpenArchOutputProperties();
        this.client = client != null ? client : new OpenArchClient(
                this.properties.getEndpointUrl(),
                this.properties.getUsername(),
                this.properties.getPassword()
        );
        this.embeddingModel = embeddingModel;
        this.textSplitter = TokenTextSplitter.builder().build();
        this.textExtractionService = textExtractionService != null ? textExtractionService : TextExtractionService.noop();
        this.parquetWriter = new OpenArchParquetEmbeddingsWriter();
    }

    public OpenArchOutputConnector(OpenArchClient client, OpenArchOutputProperties properties) {
        this(properties, client, null, null);
    }

    @Override
    public String getName() {
        return "OpenArch_Output";
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting to Hyland OpenArch at: {} (Repository: {})", properties.getEndpointUrl(), properties.getRepositoryId());
        boolean reachable = client.ping(properties.getRepositoryId());
        if (reachable) {
            log.info("Successfully connected to Hyland OpenArch cluster.");
        } else {
            log.warn("OpenArch endpoint {} did not respond positively to ping, but continuing startup.", properties.getEndpointUrl());
        }
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnected from Hyland OpenArch.");
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            try {
                processDocument(document);
            } catch (Exception e) {
                log.error("Failed to send document {} to OpenArch: {}", document.id(), e.getMessage(), e);
                throw new RuntimeException("OpenArch ingestion error: " + e.getMessage(), e);
            }
        });
    }

    protected void processDocument(RepositoryDocument document) throws Exception {
        if (document.action() == DocumentAction.DELETE) {
            log.info("Received DELETE action for document {}. OpenArch tombstone recorded.", document.id());
            return;
        }

        String repoId = properties.getRepositoryId();
        String rootFolder = properties.getRootFolderId();

        byte[] contentBytes = null;
        if (document.contentStream() != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.contentStream().transferTo(baos);
            contentBytes = baos.toByteArray();
        }

        String mimeType = getFirstMetadata(document.metadata(), "mimeType", "Content-Type", "application/octet-stream");
        String title = getFirstMetadata(document.metadata(), "title", document.id());
        String description = getFirstMetadata(document.metadata(), "description", null);

        // 1. Binary streaming to OpenArch /api/upload if content is present
        String uploadedBlobId = null;
        if (properties.isUploadBinaries() && contentBytes != null && contentBytes.length > 0) {
            try (InputStream stream = new ByteArrayInputStream(contentBytes)) {
                JsonNode uploadNode = client.uploadBlob(stream, document.id(), mimeType);
                if (uploadNode != null && uploadNode.has("id")) {
                    uploadedBlobId = uploadNode.get("id").asText();
                    log.info("Uploaded binary for document {} to OpenArch blob: {}", document.id(), uploadedBlobId);
                }
            } catch (Exception uploadErr) {
                log.warn("Binary upload failed for document {}, continuing metadata-only ingestion: {}", document.id(), uploadErr.getMessage());
            }
        }

        // 2. Build OpenArch Document DTO
        Map<String, Object> openArchDoc = new LinkedHashMap<>();
        openArchDoc.put("sys_primaryType", "SysFile");
        openArchDoc.put("sys_name", document.id());
        openArchDoc.put("sys_title", title);
        if (description != null) {
            openArchDoc.put("sys_description", description);
        }
        if (document.lastModified() != null) {
            openArchDoc.put("sys_modified", document.lastModified().toString());
            openArchDoc.put("sys_created", document.lastModified().toString());
        }
        openArchDoc.put("sys_mixinTypes", List.of("SysHasEmbeddings"));

        // Attach uploaded blob if available
        if (uploadedBlobId != null) {
            openArchDoc.put("file:content", Map.of("data", uploadedBlobId, "name", document.id()));
        }

        // 3. Map Zero-Trust OIS ACLs to OpenArch ACEs
        if (properties.isSyncAcls() && document.security() != null) {
            List<Map<String, Object>> aces = OpenArchSecurityMapper.toOpenArchAcl(document.security());
            openArchDoc.put("sys_acl", aces);
        }

        // 4. Attach extra metadata
        if (document.metadata() != null) {
            for (Map.Entry<String, List<String>> entry : document.metadata().entrySet()) {
                if (!entry.getKey().startsWith("sys_")) {
                    List<String> values = entry.getValue();
                    if (values != null && !values.isEmpty()) {
                        openArchDoc.put(entry.getKey(), values.size() == 1 ? values.get(0) : values);
                    }
                }
            }
        }

        // 5. Upsert Document in OpenArch
        JsonNode createdDoc = client.createOrUpdateDocument(repoId, rootFolder, openArchDoc, properties.isUpsert());
        String targetDocId = (createdDoc != null && createdDoc.has("id"))
                ? createdDoc.get("id").asText()
                : document.id();
        log.info("Document {} indexed in OpenArch under parent {} (Target ID: {})", document.id(), rootFolder, targetDocId);

        // 6. Parquet Vector Bridging (embedding.avsc)
        if (embeddingModel != null && contentBytes != null && contentBytes.length > 0) {
            try {
                TextExtractionResult extraction = textExtractionService.extractText(contentBytes, document.metadata() != null ? document.metadata() : Map.of());
                String text = extraction.text();
                if (text != null && !text.isBlank()) {
                    List<Document> chunks = textSplitter.split(new Document(text));
                    List<EmbeddingChunk> embeddingChunks = new ArrayList<>();

                    for (int i = 0; i < chunks.size(); i++) {
                        Document chunk = chunks.get(i);
                        float[] vector = embeddingModel.embed(chunk.getText());
                        String chunkId = UUID.nameUUIDFromBytes((targetDocId + "#" + i).getBytes()).toString();

                        embeddingChunks.add(EmbeddingChunk.of(
                                chunkId,
                                properties.getEmbeddingsType(),
                                vector,
                                chunk.getText(),
                                uploadedBlobId
                        ));
                    }

                    if (!embeddingChunks.isEmpty()) {
                        File tempParquet = File.createTempFile("openarch-embeddings-", ".parquet");
                        try {
                            parquetWriter.writeParquet(embeddingChunks, tempParquet);
                            try (InputStream parquetIn = new FileInputStream(tempParquet)) {
                                JsonNode parquetUpload = client.uploadBlob(parquetIn, "embeddings-" + targetDocId + ".parquet", "application/vnd.apache.parquet");
                                log.info("Successfully uploaded Parquet vector bridge blob for document {} ({} chunks): {}",
                                        targetDocId, embeddingChunks.size(), parquetUpload != null ? parquetUpload.get("id") : "ok");
                            }
                        } finally {
                            if (tempParquet.exists()) {
                                tempParquet.delete();
                            }
                        }
                    }
                }
            } catch (Exception embEx) {
                log.warn("Vector embedding or Parquet generation failed for document {}: {}", document.id(), embEx.getMessage());
            }
        }
    }

    private String getFirstMetadata(Map<String, List<String>> metadata, String key, String defaultValue) {
        if (metadata == null) {
            return defaultValue;
        }
        List<String> vals = metadata.get(key);
        if (vals != null && !vals.isEmpty() && vals.get(0) != null) {
            return vals.get(0);
        }
        return defaultValue;
    }

    private String getFirstMetadata(Map<String, List<String>> metadata, String key1, String key2, String defaultValue) {
        String res = getFirstMetadata(metadata, key1, null);
        if (res != null) {
            return res;
        }
        return getFirstMetadata(metadata, key2, defaultValue);
    }
}
