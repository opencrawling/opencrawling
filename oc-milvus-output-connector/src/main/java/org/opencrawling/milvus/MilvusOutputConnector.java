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
package org.opencrawling.milvus;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import org.apache.tika.Tika;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.*;

@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "milvus")
public class MilvusOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(MilvusOutputConnector.class);

    private final TokenTextSplitter textSplitter;
    private final Tika tika;
    private final Gson gson;

    @Value("${spring.opencrawling.output.milvus.uri:http://localhost:19530}")
    private String uri = "http://localhost:19530";

    @Value("${spring.opencrawling.output.milvus.token:root:Milvus}")
    private String token = "root:Milvus";

    @Value("${spring.opencrawling.output.milvus.collection-name:enterprise_kb}")
    private String collectionName = "enterprise_kb";

    @Value("${spring.opencrawling.output.milvus.vector-field-name:embeddings}")
    private String vectorFieldName = "embeddings";

    @Value("${spring.opencrawling.output.milvus.dimensions:1024}")
    private int dimensions = 1024;

    private MilvusClientV2 client;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public MilvusOutputConnector(
            @Autowired(required = false) MilvusClientV2 client,
            @Autowired(required = false) @org.springframework.beans.factory.annotation.Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.textSplitter = TokenTextSplitter.builder().build();
        this.tika = new Tika();
        this.gson = new Gson();
    }

    private synchronized MilvusClientV2 getOrInitClient() {
        if (client == null) {
            log.info("Initializing lazy MilvusClientV2 in connector. URI: {}", uri);
            ConnectConfig config = ConnectConfig.builder()
                    .uri(uri)
                    .token(token)
                    .build();
            client = new MilvusClientV2(config);
        }
        return client;
    }

    @Override
    public String getName() {
        return "MilvusOutputConnector";
    }

    @Override
    public void connect() throws Exception {
        getOrInitClient();
    }

    @Override
    public void disconnect() throws Exception {
        // MilvusClientV2 doesn't have a close/disconnect in standard v2 API, but we release reference
        client = null;
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            try (InputStream is = document.contentStream()) {
                byte[] contentBytes = is.readAllBytes();

                if (contentBytes.length == 0) {
                    log.warn("Document {} content is empty, skipping Milvus ingestion.", document.id());
                    return;
                }

                // Parse document text using Tika
                String text = "";
                try {
                    text = tika.parseToString(new java.io.ByteArrayInputStream(contentBytes));
                } catch (Exception e) {
                    log.warn("Tika failed to parse document {}: {}. Falling back to plain text check.", document.id(), e.getMessage());
                }

                // Fallback for plain text
                if (text.isBlank() && contentBytes.length > 0) {
                    String mimeType = String.valueOf(document.metadata().getOrDefault("mimeType", List.of("text/plain")));
                    if (mimeType.contains("text") || mimeType.contains("json") || mimeType.contains("xml") || mimeType.contains("csv")) {
                        text = new String(contentBytes, java.nio.charset.StandardCharsets.UTF_8);
                    }
                }

                // Remove null characters
                text = text.replace("\u0000", "");

                if (text.isBlank()) {
                    log.warn("Document {} extracted text is empty, skipping Milvus ingestion.", document.id());
                    return;
                }

                log.info("Extracted {} characters from document: {}", text.length(), document.id());

                // Build metadata map
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
                metadata.put(MilvusConstants.FIELD_URI, document.uri());
                metadata.put(MilvusConstants.FIELD_ACL, document.acl());
                metadata.put(MilvusConstants.FIELD_LAST_MODIFIED, document.lastModified().toString());

                // Construct Spring AI Document for chunking
                Document aiDoc = new Document(document.id(), text, metadata);
                List<Document> chunks = textSplitter.apply(List.of(aiDoc));
                log.info("Split document into {} chunks for Milvus.", chunks.size());

                List<JsonObject> rows = new ArrayList<>();
                for (Document chunk : chunks) {
                    String chunkId = document.id() + "_" + (chunk.getId() != null ? chunk.getId() : UUID.randomUUID().toString());

                    // Get or compute embedding
                    float[] embedding = null;
                    if (embeddingModel != null) {
                        try {
                            embedding = embeddingModel.embed(chunk);
                        } catch (Exception e) {
                            log.debug("Failed embedding from document metadata, trying to embed text directly.", e);
                            embedding = embeddingModel.embed(chunk.getText());
                        }
                    } else {
                        // Fallback/Simulated vector if no model is present (e.g. tests)
                        embedding = new float[dimensions];
                        embedding[0] = 1.0f;
                    }

                    // Map ACL lists
                    List<String> allowedRead = new ArrayList<>();
                    List<String> deniedRead = new ArrayList<>();
                    boolean inheritanceEnabled = true;

                    if (document.security() != null) {
                        inheritanceEnabled = document.security().inheritanceEnabled();
                        for (PermissionRule rule : document.security().permissions()) {
                            if ("read".equalsIgnoreCase(rule.access()) || "write".equalsIgnoreCase(rule.access())) {
                                allowedRead.add(rule.identity());
                            } else if ("deny".equalsIgnoreCase(rule.access())) {
                                deniedRead.add(rule.identity());
                            }
                        }
                    }

                    JsonObject row = new JsonObject();
                    row.addProperty(MilvusConstants.FIELD_ID, chunkId);
                    row.addProperty(MilvusConstants.FIELD_TEXT, chunk.getText());
                    row.addProperty(MilvusConstants.FIELD_URI, document.uri());
                    row.addProperty(MilvusConstants.FIELD_ACL, document.acl());
                    row.addProperty(MilvusConstants.FIELD_LAST_MODIFIED, document.lastModified().toString());
                    row.addProperty(MilvusConstants.FIELD_SECURITY_INHERITANCE, inheritanceEnabled);
                    row.add(MilvusConstants.FIELD_SECURITY_ALLOWED_READ, gson.toJsonTree(allowedRead));
                    row.add(MilvusConstants.FIELD_SECURITY_DENIED_READ, gson.toJsonTree(deniedRead));
                    row.add(vectorFieldName, gson.toJsonTree(embedding));

                    // Add other dynamic metadata properties to row
                    metadata.forEach((key, val) -> {
                        if (!MilvusConstants.FIELD_URI.equals(key) &&
                            !MilvusConstants.FIELD_ACL.equals(key) &&
                            !MilvusConstants.FIELD_LAST_MODIFIED.equals(key)) {
                            row.add(key, gson.toJsonTree(val));
                        }
                    });

                    rows.add(row);
                }

                // Insert to Milvus
                if (!rows.isEmpty()) {
                    InsertReq insertReq = InsertReq.builder()
                            .collectionName(collectionName)
                            .data(rows)
                            .build();
                    getOrInitClient().insert(insertReq);
                    log.info("Successfully added {} chunks for document {} to Milvus collection '{}'.", 
                            rows.size(), document.id(), collectionName);
                }

            } catch (Exception e) {
                log.error("Error processing document {} for Milvus: {}", document.id(), e.getMessage());
                throw new RuntimeException("Failed to process document for Milvus: " + document.id(), e);
            }
        });
    }
}
