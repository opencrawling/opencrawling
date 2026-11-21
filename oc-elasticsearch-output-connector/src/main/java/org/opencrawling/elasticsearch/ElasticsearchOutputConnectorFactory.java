/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.elasticsearch.config.ElasticsearchClientFactory;
import org.opencrawling.elasticsearch.config.ElasticsearchIndexInitializer;
import org.opencrawling.elasticsearch.config.ElasticsearchOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An Elasticsearch output for one run, from its saved configuration (#319). Creates its index if it does not exist
 * (unless {@code elasticsearchCreateIndex} is {@code false}). A RAG run sends its documents to it directly unless the
 * runtime's pipeline writer stores to the same nodes and index.
 */
public class ElasticsearchOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchOutputConnectorFactory.class);
    private static final int INIT_ATTEMPTS = 3;
    private static final long INIT_DELAY_MILLIS = 1_000;

    @Override
    public String className() {
        return ElasticsearchOutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        ElasticsearchOutputProperties properties = properties(config);
        ElasticsearchClient client = ElasticsearchClientFactory.create(properties);
        try {
            if (properties.createIndex()) {
                new ElasticsearchIndexInitializer(client, properties).ensureIndex(INIT_ATTEMPTS, INIT_DELAY_MILLIS);
            }
        } catch (RuntimeException e) {
            closeQuietly(client);
            throw e;
        }
        log.info("Resolving dynamic Elasticsearch output connector for index '{}' at {}", properties.indexName(),
                properties.hostUris());
        return new ElasticsearchOutputConnector(client, properties, context.service(EmbeddingModel.class),
                context.service(TextExtractionService.class), !pipelineWrites(context, properties));
    }

    /** The settings a saved Elasticsearch output connector's configuration describes. */
    static ElasticsearchOutputProperties properties(Map<String, String> config) {
        return new ElasticsearchOutputProperties(
                OutputContext.text(config, "http://localhost:9200", "elasticsearchUris"),
                OutputContext.text(config, "elastic", "elasticsearchUsername"),
                OutputContext.text(config, "", "elasticsearchPassword"),
                OutputContext.text(config, "", "elasticsearchApiKey"),
                OutputContext.text(config, "enterprise_kb", "elasticsearchIndexName"),
                OutputContext.integer(config, 1024, "elasticsearchDimensions"),
                ElasticsearchOutputProperties.Similarity.valueOf(
                        constant(OutputContext.text(config, "COSINE", "elasticsearchSimilarity"))),
                ElasticsearchOutputProperties.IndexType.valueOf(
                        constant(OutputContext.text(config, "HNSW", "elasticsearchIndexType"))),
                OutputContext.integer(config, 500, "elasticsearchBulkBatchSize"),
                OutputContext.flag(config, true, "elasticsearchCreateIndex"),
                OutputContext.integer(config, 8, "elasticsearchCompatibleWith"),
                new ElasticsearchOutputProperties.Ssl(
                        OutputContext.text(config, "", "elasticsearchTrustStorePath"),
                        OutputContext.text(config, "", "elasticsearchTrustStorePassword"),
                        OutputContext.text(config, "PKCS12", "elasticsearchTrustStoreType"),
                        OutputContext.text(config, "", "elasticsearchCaFingerprint")));
    }

    /** Whether the runtime's pipeline writer stores to these nodes and this index (its output is the same cluster). */
    public static boolean pipelineWrites(OutputContext context, ElasticsearchOutputProperties run) {
        ElasticsearchOutputConnector pipeline = context.pipelineOutput(ElasticsearchOutputConnector.class);
        return pipeline != null && Objects.equals(pipeline.getProperties().hostUris(), run.hostUris())
                && Objects.equals(pipeline.getProperties().indexName(), run.indexName());
    }

    private static String constant(String value) {
        return value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static void closeQuietly(ElasticsearchClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.debug("Could not close Elasticsearch client: {}", e.getMessage());
        }
    }
}
