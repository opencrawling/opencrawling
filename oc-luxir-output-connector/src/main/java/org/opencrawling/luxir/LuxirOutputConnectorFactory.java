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
package org.opencrawling.luxir;

import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.luxir.client.LuxirClient;
import org.opencrawling.luxir.config.LuxirCollectionInitializer;
import org.opencrawling.luxir.config.LuxirOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * A Luxir output for one run, from its saved configuration (#319). Provisions its collection's schema when missing.
 * A RAG run sends its documents to it directly unless the runtime's pipeline writer stores to the same endpoint and
 * collection.
 */
public class LuxirOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(LuxirOutputConnectorFactory.class);

    @Override
    public String className() {
        return LuxirOutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        LuxirOutputProperties properties = properties(config);
        LuxirClient client = new LuxirClient(properties.endpoint(), Duration.ofSeconds(properties.timeoutSeconds()));
        new LuxirCollectionInitializer(client, properties).initializeSchema();
        log.info("Resolving dynamic Luxir output connector for collection '{}' at '{}'", properties.collection(),
                properties.endpoint());
        return new LuxirOutputConnector(client, properties, context.service(EmbeddingModel.class),
                context.service(TextExtractionService.class), !pipelineWrites(context, properties));
    }

    /** The settings a saved Luxir output connector's configuration describes. */
    static LuxirOutputProperties properties(Map<String, String> config) {
        return new LuxirOutputProperties(
                OutputContext.text(config, LuxirConstants.DEFAULT_ENDPOINT, "luxirEndpoint"),
                OutputContext.text(config, LuxirConstants.DEFAULT_COLLECTION, "luxirCollection"),
                OutputContext.text(config, LuxirConstants.DEFAULT_VECTOR_FIELD, "luxirVectorField"),
                OutputContext.integer(config, LuxirConstants.DEFAULT_DIMENSIONS, "luxirDimensions"),
                OutputContext.text(config, LuxirConstants.DEFAULT_SIMILARITY, "luxirSimilarity"),
                OutputContext.flag(config, true, "luxirAutoCommit"),
                OutputContext.integer(config, 0, "luxirCommitWithinMs"),
                OutputContext.integer(config, LuxirConstants.DEFAULT_TIMEOUT_SECONDS, "luxirTimeoutSeconds"));
    }

    /** Whether the runtime's pipeline writer stores to this endpoint and collection. */
    public static boolean pipelineWrites(OutputContext context, LuxirOutputProperties run) {
        LuxirOutputConnector pipeline = context.pipelineOutput(LuxirOutputConnector.class);
        return pipeline != null && pipeline.getProperties() != null
                && Objects.equals(trimSlash(pipeline.getProperties().endpoint()), trimSlash(run.endpoint()))
                && Objects.equals(pipeline.getProperties().collection(), run.collection());
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
