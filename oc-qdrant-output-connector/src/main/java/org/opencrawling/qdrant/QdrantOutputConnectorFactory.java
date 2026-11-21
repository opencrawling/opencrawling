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
package org.opencrawling.qdrant;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.qdrant.config.QdrantCollectionInitializer;
import org.opencrawling.qdrant.config.QdrantOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Map;
import java.util.Objects;

/** A Qdrant output for one run, from its saved configuration (#319). */
public class QdrantOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(QdrantOutputConnectorFactory.class);

    @Override
    public String className() {
        return QdrantOutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        String host = config.getOrDefault("qdrantHost", "localhost");
        int grpcPort = OutputContext.integer(config, 6334, "qdrantPort");
        boolean useTls = Boolean.parseBoolean(config.getOrDefault("useTls", "false"));
        String apiKey = config.getOrDefault("qdrantApiKey", "");
        String collectionName = config.getOrDefault("qdrantCollection", "enterprise_kb");
        int dimensions = OutputContext.integer(config, 1024, "qdrantDimensions");

        QdrantGrpcClient.Builder grpcBuilder = QdrantGrpcClient.newBuilder(host, grpcPort, useTls);
        if (apiKey != null && !apiKey.isBlank()) {
            grpcBuilder.withApiKey(apiKey);
        }
        QdrantClient qdrantClient = new QdrantClient(grpcBuilder.build());

        QdrantOutputProperties props = new QdrantOutputProperties(host, grpcPort, apiKey, collectionName, dimensions,
                QdrantOutputProperties.Distance.COSINE, QdrantOutputProperties.Quantization.NONE, useTls, 500);
        new QdrantCollectionInitializer(qdrantClient, props).initializeCollection();

        log.info("Resolving dynamic Qdrant output connector for collection '{}'", collectionName);
        // Unless the pipeline's writer stores to this destination, the run's documents go to it directly.
        return new QdrantOutputConnector(qdrantClient, props, new QdrantPointMapper(),
                context.service(EmbeddingModel.class), context.service(TextExtractionService.class),
                !pipelineWrites(context, props));
    }

    /**
     * Whether the runtime's pipeline writer stores to this Qdrant destination: its output is Qdrant, with the same
     * host, port and collection. Only then may a RAG run leave the run's documents to the pipeline; otherwise they are
     * sent to the run's Qdrant directly, or they would never reach it.
     */
    public static boolean pipelineWrites(OutputContext context, QdrantOutputProperties run) {
        QdrantOutputConnector pipeline = context.pipelineOutput(QdrantOutputConnector.class);
        return pipeline != null && sameDestination(pipeline.getProperties(), run);
    }

    public static boolean sameDestination(QdrantOutputProperties a, QdrantOutputProperties b) {
        return a != null && b != null && Objects.equals(a.host(), b.host()) && a.port() == b.port()
                && Objects.equals(a.collectionName(), b.collectionName());
    }
}
