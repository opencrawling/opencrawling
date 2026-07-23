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
package org.opencrawling.opensearch3;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.apache.tika.Tika;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
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
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "opensearch3")
public class OpenSearch3OutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(OpenSearch3OutputConnector.class);

    private final TokenTextSplitter textSplitter;
    private final Tika tika;

    @Value("${spring.opencrawling.output.opensearch3.uris:http://localhost:9200}")
    private String uris = "http://localhost:9200";

    @Value("${spring.opencrawling.output.opensearch3.username:admin}")
    private String username = "admin";

    @Value("${spring.opencrawling.output.opensearch3.password:admin}")
    private String password = "admin";

    @Value("${spring.opencrawling.output.opensearch3.index-name:enterprise_kb}")
    private String indexName = "enterprise_kb";

    @Value("${spring.opencrawling.output.opensearch3.dimensions:1024}")
    private int dimensions = 1024;

    private OpenSearchClient client;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public OpenSearch3OutputConnector(
            @Autowired(required = false) OpenSearchClient client,
            @Autowired(required = false) @org.springframework.beans.factory.annotation.Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.textSplitter = TokenTextSplitter.builder().build();
        this.tika = new Tika();
    }

    private synchronized OpenSearchClient getOrInitClient() {
        if (client == null) {
            log.info("Initializing lazy OpenSearchClient (3.x) in connector. URI: {}", uris);
            try {
                final HttpHost host = HttpHost.create(uris);
                final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(new AuthScope(host), new UsernamePasswordCredentials(username, password.toCharArray()));

                final OpenSearchTransport transport = ApacheHttpClient5TransportBuilder
                        .builder(host)
                        .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider))
                        .setMapper(new JacksonJsonpMapper())
                        .build();

                client = new OpenSearchClient(transport);
            } catch (Exception e) {
                log.error("Failed to initialize OpenSearchClient", e);
                throw new RuntimeException("OpenSearch client initialization error", e);
            }
        }
        return client;
    }

    @Override
    public String getName() {
        return "OpenSearch3OutputConnector";
    }

    @Override
    public void connect() throws Exception {
        getOrInitClient();
    }

    @Override
    public void disconnect() throws Exception {
        client = null;
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            try (InputStream is = document.contentStream()) {
                byte[] contentBytes = is.readAllBytes();

                if (contentBytes.length == 0) {
                    log.warn("Document {} content is empty, skipping OpenSearch ingestion.", document.id());
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
                    log.warn("Document {} extracted text is empty, skipping OpenSearch ingestion.", document.id());
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
                metadata.put(OpenSearch3Constants.FIELD_URI, document.uri());
                metadata.put(OpenSearch3Constants.FIELD_ACL, document.acl());
                metadata.put(OpenSearch3Constants.FIELD_LAST_MODIFIED, document.lastModified().toString());

                // Construct Spring AI Document for chunking
                Document aiDoc = new Document(document.id(), text, metadata);
                List<Document> chunks = textSplitter.apply(List.of(aiDoc));
                log.info("Split document into {} chunks for OpenSearch.", chunks.size());

                List<Map<String, Object>> rows = new ArrayList<>();
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

                    Map<String, Object> row = new HashMap<>();
                    row.put(OpenSearch3Constants.FIELD_ID, chunkId);
                    row.put(OpenSearch3Constants.FIELD_TEXT, chunk.getText());
                    row.put(OpenSearch3Constants.FIELD_URI, document.uri());
                    row.put(OpenSearch3Constants.FIELD_ACL, document.acl());
                    row.put(OpenSearch3Constants.FIELD_LAST_MODIFIED, document.lastModified().toString());
                    row.put(OpenSearch3Constants.FIELD_SECURITY_INHERITANCE, inheritanceEnabled);
                    row.put(OpenSearch3Constants.FIELD_SECURITY_ALLOWED_READ, allowedRead);
                    row.put(OpenSearch3Constants.FIELD_SECURITY_DENIED_READ, deniedRead);
                    row.put(OpenSearch3Constants.FIELD_EMBEDDINGS, embedding);

                    // Add other dynamic metadata properties to row
                    metadata.forEach((key, val) -> {
                        if (!OpenSearch3Constants.FIELD_URI.equals(key) &&
                            !OpenSearch3Constants.FIELD_ACL.equals(key) &&
                            !OpenSearch3Constants.FIELD_LAST_MODIFIED.equals(key)) {
                            row.put(key, val);
                        }
                    });

                    rows.add(row);
                }

                // Insert to OpenSearch
                if (!rows.isEmpty()) {
                    BulkRequest.Builder br = new BulkRequest.Builder();
                    for (Map<String, Object> row : rows) {
                        String id = (String) row.get(OpenSearch3Constants.FIELD_ID);
                        br.operations(op -> op
                            .index(idx -> idx
                                .index(indexName)
                                .id(id)
                                .document(row)
                            )
                        );
                    }
                    getOrInitClient().bulk(br.build());
                    log.info("Successfully added {} chunks for document {} to OpenSearch index '{}' via Bulk API.", 
                            rows.size(), document.id(), indexName);
                }

            } catch (Exception e) {
                log.error("Error processing document {} for OpenSearch: {}", document.id(), e.getMessage());
                throw new RuntimeException("Failed to process document for OpenSearch: " + document.id(), e);
            }
        });
    }
}
