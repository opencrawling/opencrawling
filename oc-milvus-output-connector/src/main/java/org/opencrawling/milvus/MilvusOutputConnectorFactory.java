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
package org.opencrawling.milvus;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.milvus.config.MilvusStoreConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Map;
import java.util.Objects;

/**
 * A Milvus output for one run, from its saved configuration (#319). Creates its collection if it does not exist.
 * A RAG run sends its documents to it directly unless the runtime's pipeline writer stores to the same server and
 * collection.
 */
public class MilvusOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(MilvusOutputConnectorFactory.class);

    @Override
    public String className() {
        return MilvusOutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        String uri = OutputContext.text(config, "http://localhost:19530", "milvusUri");
        String token = OutputContext.text(config, "root:Milvus", "milvusToken");
        String collection = OutputContext.text(config, "enterprise_kb", "milvusCollection");
        String vectorField = OutputContext.text(config, "embeddings", "milvusVectorField");
        int dimensions = OutputContext.integer(config, 1024, "milvusDimensions");
        String metricType = OutputContext.text(config, "COSINE", "milvusMetricType");
        String indexType = OutputContext.text(config, "HNSW", "milvusIndexType");

        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder().uri(uri).token(token).build());
        MilvusStoreConfig.initializeCollection(client, collection, vectorField, dimensions, indexType, metricType);
        boolean pipelineWrites = pipelineWrites(context, uri, collection);
        log.info("Resolving dynamic Milvus output connector for collection '{}' at '{}'", collection, uri);
        return new MilvusOutputConnector(client, uri, collection, vectorField, dimensions,
                context.service(EmbeddingModel.class), context.service(TextExtractionService.class), !pipelineWrites);
    }

    /** Whether the runtime's pipeline writer stores to this server and collection (its output is the same Milvus). */
    public static boolean pipelineWrites(OutputContext context, String uri, String collection) {
        MilvusOutputConnector pipeline = context.pipelineOutput(MilvusOutputConnector.class);
        return pipeline != null && Objects.equals(trimSlash(pipeline.uri()), trimSlash(uri))
                && Objects.equals(pipeline.collectionName(), collection);
    }

    private static String trimSlash(String uri) {
        return uri != null && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }
}
