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
package org.opencrawling.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.text.TextExtractionResult;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.elasticsearch.config.ElasticsearchClientFactory;
import org.opencrawling.elasticsearch.config.ElasticsearchOutputProperties;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes documents to Elasticsearch on the direct (non-Kafka) path: extract text, chunk, embed and bulk-index.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "elasticsearch")
public class ElasticsearchOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchOutputConnector.class);

    private final ElasticsearchOutputProperties properties;
    private final EmbeddingModel embeddingModel;
    private final TextExtractionService textExtractionService;
    private final ElasticsearchDocumentMapper mapper = new ElasticsearchDocumentMapper();
    private final TokenTextSplitter textSplitter = TokenTextSplitter.builder().build();

    private ElasticsearchClient client;
    private boolean ownsClient;

    @Autowired
    public ElasticsearchOutputConnector(
            @Autowired(required = false) ElasticsearchClient client,
            @Autowired(required = false) ElasticsearchOutputProperties properties,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel,
            @Autowired(required = false) TextExtractionService textExtractionService) {
        this.client = client;
        this.properties = properties != null ? properties : ElasticsearchOutputProperties.defaults();
        this.embeddingModel = embeddingModel;
        this.textExtractionService = textExtractionService != null ? textExtractionService : TextExtractionService.noop();
    }

    public ElasticsearchOutputConnector(ElasticsearchClient client, ElasticsearchOutputProperties properties,
                                        EmbeddingModel embeddingModel) {
        this(client, properties, embeddingModel, null);
    }

    private synchronized ElasticsearchClient getOrInitClient() {
        if (client == null) {
            log.info("Initializing lazy ElasticsearchClient in connector. URIs: {}", properties.uris());
            client = ElasticsearchClientFactory.create(properties);
            ownsClient = true;
        }
        return client;
    }

    @Override
    public String getName() {
        return "ElasticsearchOutputConnector";
    }

    @Override
    public void connect() throws Exception {
        getOrInitClient();
    }

    @Override
    public synchronized void disconnect() throws Exception {
        if (ownsClient && client != null) {
            client.close();
        }
        if (ownsClient) {
            client = null;
            ownsClient = false;
        }
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        if (document.action() == DocumentAction.DELETE) {
            return Mono.fromRunnable(() -> {
                try {
                    purgeDocument(getOrInitClient(), document.id());
                    log.info("Deleted chunks of document {} from Elasticsearch index '{}'.", document.id(), properties.indexName());
                } catch (IOException e) {
                    throw new RuntimeException("Failed to delete document from Elasticsearch: " + document.id(), e);
                }
            });
        }
        return Mono.fromRunnable(() -> {
            try (InputStream is = document.contentStream()) {
                byte[] contentBytes = is.readAllBytes();
                if (contentBytes.length == 0) {
                    log.warn("Document {} content is empty, skipping Elasticsearch ingestion.", document.id());
                    return;
                }

                TextExtractionResult extraction = textExtractionService.extractText(contentBytes, document.metadata());
                String text = extraction.text();
                if (!extraction.success()) {
                    log.warn("Text extraction failed for document {}: {}", document.id(), extraction.errorMessage());
                }
                if (extraction.trimmed()) {
                    log.info("Document {} text was trimmed during extraction.", document.id());
                }
                if (text == null || text.isBlank()) {
                    log.warn("Document {} extracted text is empty, skipping Elasticsearch ingestion.", document.id());
                    return;
                }

                Map<String, Object> metadata = cleanMetadata(document.metadata());
                List<Document> chunks = textSplitter.apply(List.of(new Document(document.id(), text, metadata)));
                log.info("Split document {} into {} chunks for Elasticsearch.", document.id(), chunks.size());

                EffectiveAcl effectiveAcl = EffectiveAcl.resolve(document.security(), document.acl());
                String lastModified = document.lastModified() != null ? document.lastModified().toString() : null;

                List<Map<String, Object>> sources = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    Document chunk = chunks.get(i);
                    sources.add(mapper.toSource(new ElasticsearchDocumentMapper.Chunk(
                            chunkId(document.id(), i),
                            document.id(),
                            chunk.getText(),
                            document.uri(),
                            document.acl(),
                            lastModified,
                            effectiveAcl,
                            embed(chunk),
                            metadata)));
                }

                ElasticsearchClient es = getOrInitClient();
                // Re-crawls can produce fewer chunks than before; drop the old ones so none linger.
                purgeDocument(es, document.id());
                bulkIndex(es, sources);
                log.info("Indexed {} chunks for document {} into Elasticsearch index '{}'.",
                        sources.size(), document.id(), properties.indexName());
            } catch (Exception e) {
                log.error("Error processing document {} for Elasticsearch: {}", document.id(), e.getMessage());
                throw new RuntimeException("Failed to process document for Elasticsearch: " + document.id(), e);
            }
        });
    }

    /** Deterministic chunk ids, so re-indexing a document overwrites rather than duplicates. */
    static String chunkId(String documentId, int index) {
        return documentId + "_" + index;
    }

    private float[] embed(Document chunk) {
        if (embeddingModel == null) {
            // Placeholder vector when no model is wired (tests, connector checks).
            float[] placeholder = new float[properties.dimensions()];
            placeholder[0] = 1.0f;
            return placeholder;
        }
        try {
            return embeddingModel.embed(chunk);
        } catch (Exception e) {
            log.debug("Embedding the chunk document failed, embedding its text directly.", e);
            return embeddingModel.embed(chunk.getText());
        }
    }

    private void purgeDocument(ElasticsearchClient es, String documentId) throws IOException {
        es.deleteByQuery(d -> d
                .index(properties.indexName())
                .conflicts(Conflicts.Proceed)
                .refresh(true)
                .query(q -> q.term(t -> t
                        .field(ElasticsearchConstants.FIELD_DOCUMENT_ID)
                        .value(FieldValue.of(documentId)))));
    }

    private void bulkIndex(ElasticsearchClient es, List<Map<String, Object>> sources) throws IOException {
        int batchSize = properties.bulkBatchSize();
        for (int from = 0; from < sources.size(); from += batchSize) {
            List<Map<String, Object>> batch = sources.subList(from, Math.min(from + batchSize, sources.size()));
            BulkRequest.Builder request = new BulkRequest.Builder();
            for (Map<String, Object> source : batch) {
                String id = (String) source.get(ElasticsearchConstants.FIELD_ID);
                request.operations(op -> op.index(idx -> idx
                        .index(properties.indexName())
                        .id(id)
                        .document(source)));
            }
            BulkResponse response = es.bulk(request.build());
            if (response != null && response.errors()) {
                throw new IOException("Elasticsearch bulk request had failures: " + firstError(response));
            }
        }
    }

    static String firstError(BulkResponse response) {
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                return "id=" + item.id() + " type=" + item.error().type() + " reason=" + item.error().reason();
            }
        }
        return "unknown";
    }

    private static Map<String, Object> cleanMetadata(Map<String, List<String>> raw) {
        Map<String, Object> metadata = new HashMap<>();
        if (raw == null) {
            return metadata;
        }
        raw.forEach((key, values) -> {
            if (values != null) {
                List<String> cleaned = new ArrayList<>(values.size());
                for (String value : values) {
                    if (value != null) {
                        cleaned.add(value.replace("\u0000", ""));
                    }
                }
                metadata.put(key, cleaned);
            }
        });
        return metadata;
    }
}
