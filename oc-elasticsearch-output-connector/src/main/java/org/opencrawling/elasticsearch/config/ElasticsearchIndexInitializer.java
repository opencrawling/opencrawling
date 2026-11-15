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
package org.opencrawling.elasticsearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.opencrawling.elasticsearch.ElasticsearchConstants.*;

/**
 * Creates the chunk index with a {@code dense_vector} mapping when it does not exist yet.
 */
public class ElasticsearchIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchIndexInitializer.class);

    private final ElasticsearchClient client;
    private final ElasticsearchOutputProperties properties;

    public ElasticsearchIndexInitializer(ElasticsearchClient client, ElasticsearchOutputProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * Ensures the index exists, retrying while the cluster comes up.
     *
     * @param maxAttempts  how many times to try
     * @param delayMillis  the pause between attempts
     */
    public void ensureIndex(int maxAttempts, long delayMillis) {
        for (int attempt = 1; ; attempt++) {
            try {
                ensureIndex();
                return;
            } catch (Exception e) {
                if (attempt >= maxAttempts) {
                    throw new IllegalStateException("Failed to initialize Elasticsearch index '"
                            + properties.indexName() + "' after " + maxAttempts + " attempts", e);
                }
                log.warn("Attempt {}/{} to initialize Elasticsearch index '{}' failed, retrying in {} ms: {}",
                        attempt, maxAttempts, properties.indexName(), delayMillis, e.getMessage());
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while initializing the Elasticsearch index", ie);
                }
            }
        }
    }

    /** Creates the index once; a concurrent creation by another stage counts as success. */
    public void ensureIndex() throws IOException {
        String indexName = properties.indexName();
        if (client.indices().exists(e -> e.index(indexName)).value()) {
            log.info("Elasticsearch index '{}' already exists.", indexName);
            return;
        }
        log.info("Creating Elasticsearch index '{}' (dense_vector dims={}, similarity={}, index type={}).",
                indexName, properties.dimensions(), properties.similarity(), properties.indexType());
        try {
            client.indices().create(c -> c.index(indexName).mappings(mapping(properties)));
            log.info("Created Elasticsearch index '{}'.", indexName);
        } catch (ElasticsearchException e) {
            if ("resource_already_exists_exception".equals(e.error().type())) {
                log.info("Elasticsearch index '{}' was created concurrently.", indexName);
                return;
            }
            throw e;
        }
    }

    /** The mapping of the chunk index. */
    public static TypeMapping mapping(ElasticsearchOutputProperties properties) {
        return TypeMapping.of(m -> m
                // Free-form metadata is mapped dynamically; never guess dates from strings, which would
                // reject later documents whose value for the same field is not a date.
                .dateDetection(false)
                .properties(FIELD_ID, p -> p.keyword(k -> k))
                .properties(FIELD_DOCUMENT_ID, p -> p.keyword(k -> k))
                .properties(FIELD_TEXT, p -> p.text(t -> t))
                .properties(FIELD_URI, p -> p.keyword(k -> k))
                .properties(FIELD_ACL, p -> p.keyword(k -> k))
                .properties(FIELD_LAST_MODIFIED, p -> p.date(d -> d.ignoreMalformed(true)))
                .properties(FIELD_SECURITY_INHERITANCE, p -> p.boolean_(b -> b))
                .properties(FIELD_SECURITY_ALLOWED_READ, p -> p.keyword(k -> k))
                .properties(FIELD_SECURITY_DENIED_READ, p -> p.keyword(k -> k))
                .properties(FIELD_SECURITY_READ_KEYS, p -> p.keyword(k -> k))
                .properties(FIELD_EMBEDDINGS, p -> p.denseVector(v -> v
                        .dims(properties.dimensions())
                        .index(true)
                        .similarity(properties.similarity().toElasticsearch())
                        .indexOptions(o -> o.type(properties.indexType().toElasticsearch())))));
    }
}
