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

import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.core.text.TextExtractionResult;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.opencrawling.vector.config.PrecomputedEmbeddingModel;

@Component
@Primary
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "pgvector", matchIfMissing = true)
public class VectorOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(VectorOutputConnector.class);
    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final TextExtractionService textExtractionService;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final boolean flowableCollocatedSchema;
    private final String vectorTableName;

    @Autowired
    public VectorOutputConnector(
            VectorStore vectorStore,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel,
            @Autowired(required = false) TextExtractionService textExtractionService,
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            @Value("${spring.opencrawling.output.pgvector.flowable-collocated-schema:false}") boolean flowableCollocatedSchema,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String vectorTableName) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.textSplitter = TokenTextSplitter.builder().build();
        this.textExtractionService = textExtractionService != null ? textExtractionService : TextExtractionService.noop();
        this.jdbcTemplate = jdbcTemplate;
        this.flowableCollocatedSchema = flowableCollocatedSchema;
        this.vectorTableName = (vectorTableName != null && !vectorTableName.isBlank()) ? vectorTableName : "vector_store";
    }

    public VectorOutputConnector(
            VectorStore vectorStore,
            EmbeddingModel embeddingModel,
            TextExtractionService textExtractionService) {
        this(vectorStore, embeddingModel, textExtractionService, null, false, "vector_store");
    }

    public VectorOutputConnector(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this(vectorStore, embeddingModel, null, null, false, "vector_store");
    }

    public VectorOutputConnector(VectorStore vectorStore) {
        this(vectorStore, null, null, null, false, "vector_store");
    }

    public VectorOutputConnector(
            VectorStore vectorStore,
            EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate,
            boolean flowableCollocatedSchema) {
        this(vectorStore, embeddingModel, null, jdbcTemplate, flowableCollocatedSchema, "vector_store");
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

                // Extract raw text using TextExtractionService (process-isolated PipesForkParser)
                TextExtractionResult result = textExtractionService.extractText(contentBytes, document.metadata());
                String text = result.text();

                if (!result.success()) {
                    log.warn("Text extraction failed for document {}: {}", document.id(), result.errorMessage());
                }

                if (result.trimmed()) {
                    log.info("Document {} text was trimmed during extraction.", document.id());
                }

                if (text.isBlank()) {
                    boolean isBlob = document.metadata() != null &&
                        (Boolean.parseBoolean(document.metadata().getOrDefault("is_blob", List.of("false")).get(0))
                         || "image".equals(document.metadata().getOrDefault("media_type", List.of("")).get(0))
                         || document.metadata().getOrDefault("mimeType", List.of("")).stream().anyMatch(m -> m.startsWith("image/")));

                    if (isBlob) {
                        String title = document.metadata().getOrDefault("title", document.metadata().getOrDefault("name", List.of())).stream().findFirst().orElse("");
                        String filename = document.metadata().getOrDefault("filename", List.of()).stream().findFirst().orElse("");
                        String mime = document.metadata().getOrDefault("mimeType", List.of()).stream().findFirst().orElse("image/binary");
                        StringBuilder desc = new StringBuilder();
                        desc.append("# Binary Item: ").append(document.id());
                        if (!filename.isBlank()) desc.append(" (").append(filename).append(")");
                        desc.append("\n\nType: ").append(mime);
                        if (!title.isBlank()) desc.append("\nTitle: ").append(title);
                        desc.append("\n\nAttributes:\n");
                        document.metadata().forEach((k, vals) -> {
                            if (!k.startsWith("tk:") && !k.equals("documentId") && !k.equals("uri") && !k.equals("acl") && !k.equals("security") && !k.equals("is_blob") && !k.equals("has_blob")) {
                                desc.append("- **").append(k).append("**: ").append(String.join("; ", vals)).append("\n");
                            }
                        });
                        text = desc.toString();
                        log.info("Generated descriptive metadata text for BLOB item {} (MIME: {}) for direct embedding.", document.id(), mime);
                    } else {
                        log.warn("Document {} extracted text is empty, skipping vector store.", document.id());
                        return;
                    }
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

                // Map standard OIS security fields for role-based and zero-trust pre-filtering
                List<String> allowedRead = new ArrayList<>();
                List<String> deniedRead = new ArrayList<>();
                boolean inheritanceEnabled = false;

                if (document.security() != null) {
                    metadata.put("security", document.security());
                    inheritanceEnabled = document.security().inheritanceEnabled();
                    if (document.security().permissions() != null) {
                        for (PermissionRule rule : document.security().permissions()) {
                            if (rule != null && rule.identity() != null && !rule.identity().isBlank()) {
                                String id = rule.identity().trim();
                                if ("read".equalsIgnoreCase(rule.access()) || "write".equalsIgnoreCase(rule.access()) || "admin".equalsIgnoreCase(rule.access())) {
                                    if (!allowedRead.contains(id)) allowedRead.add(id);
                                } else if ("deny".equalsIgnoreCase(rule.access())) {
                                    if (!deniedRead.contains(id)) deniedRead.add(id);
                                }
                            }
                        }
                    }
                }

                if (document.acl() != null && !document.acl().isBlank() && !"read".equalsIgnoreCase(document.acl().trim())) {
                    for (String token : document.acl().split(",")) {
                        String trimmed = token.trim();
                        if (!trimmed.isEmpty() && !allowedRead.contains(trimmed)) {
                            allowedRead.add(trimmed);
                        }
                    }
                }

                metadata.put("security_inheritance", inheritanceEnabled);
                metadata.put("security_allowed_read", allowedRead);
                metadata.put("security_denied_read", deniedRead);

                // Construct Spring AI Document
                Document aiDoc = new Document(document.id(), text, metadata);
                
                // Chunk the document using TokenTextSplitter
                List<Document> chunks = textSplitter.apply(List.of(aiDoc));
                log.info("Split document into {} chunks for vector store.", chunks.size());
                
                Map<String, float[]> precomputedVectors = new HashMap<>();
                if (embeddingModel != null) {
                    for (Document chunk : chunks) {
                        try {
                            float[] vector = embeddingModel.embed(chunk.getText());
                            chunk.getMetadata().put("embedding", vector);
                            precomputedVectors.put(chunk.getText(), vector);
                        } catch (Exception e) {
                            log.warn("Failed to compute embedding for chunk in document {}: {}", document.id(), e.getMessage());
                        }
                    }
                }

                try {
                    if (!precomputedVectors.isEmpty()) {
                        PrecomputedEmbeddingModel.setPrecomputedVectors(precomputedVectors);
                    }
                    // Persist the embedded chunks to the configured Vector Store
                    vectorStore.add(chunks);
                    log.info("Successfully added document {} to Vector Store.", document.id());

                    if (flowableCollocatedSchema && jdbcTemplate != null) {
                        updateFlowableCollocatedColumns(chunks, document);
                    }
                } finally {
                    PrecomputedEmbeddingModel.clear();
                }
                
            } catch (Exception e) {
                log.error("Error processing document {}: {}", document.id(), e.getMessage());
                throw new RuntimeException("Failed to process document: " + document.id(), e);
            }
        });
    }

    private void updateFlowableCollocatedColumns(List<Document> chunks, RepositoryDocument document) {
        String scopeId = null;
        if (document.metadata() != null) {
            if (document.metadata().containsKey("flowable_scope_id") && !document.metadata().get("flowable_scope_id").isEmpty()) {
                scopeId = document.metadata().get("flowable_scope_id").get(0);
            } else if (document.metadata().containsKey("flowable_proc_inst_id") && !document.metadata().get("flowable_proc_inst_id").isEmpty()) {
                scopeId = document.metadata().get("flowable_proc_inst_id").get(0);
            }
        }

        String scopeType = null;
        if (document.metadata() != null && document.metadata().containsKey("flowable_scope_type") && !document.metadata().get("flowable_scope_type").isEmpty()) {
            scopeType = document.metadata().get("flowable_scope_type").get(0);
        }

        if (scopeId != null && scopeType != null) {
            List<Object[]> batchArgs = new ArrayList<>();
            for (Document chunk : chunks) {
                batchArgs.add(new Object[]{scopeId, scopeType, chunk.getId()});
            }
            try {
                jdbcTemplate.batchUpdate("UPDATE " + vectorTableName + " SET scope_id_ = ?, scope_type_ = ? WHERE id = ?::uuid", batchArgs);
            } catch (Exception e) {
                try {
                    jdbcTemplate.batchUpdate("UPDATE " + vectorTableName + " SET scope_id_ = ?, scope_type_ = ? WHERE id = ?", batchArgs);
                } catch (Exception ex) {
                    log.warn("Failed to populate dedicated Flowable columns on {}: {}", vectorTableName, ex.getMessage());
                }
            }
            log.info("Updated dedicated Flowable columns (scope_id: {}, scope_type: {}) for {} chunks in {}", scopeId, scopeType, chunks.size(), vectorTableName);
        }
    }

    public boolean isFlowableCollocatedSchema() {
        return flowableCollocatedSchema;
    }

    public String getVectorTableName() {
        return vectorTableName;
    }
}
