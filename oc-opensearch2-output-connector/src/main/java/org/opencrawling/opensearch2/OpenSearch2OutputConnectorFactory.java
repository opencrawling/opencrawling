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
package org.opencrawling.opensearch2;

import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.opensearch2.config.OpenSearch2StoreConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Map;
import java.util.Objects;

/**
 * An OpenSearch 2.x output for one run, from its saved configuration (#319). Creates its k-NN index if it does not
 * exist. A RAG run sends its documents to it directly unless the runtime's pipeline writer stores to the same server
 * and index.
 */
public class OpenSearch2OutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(OpenSearch2OutputConnectorFactory.class);

    @Override
    public String className() {
        return OpenSearch2OutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        String uris = OutputContext.text(config, "http://localhost:9200", "opensearch2Uris");
        String username = OutputContext.text(config, "admin", "opensearch2Username");
        String password = OutputContext.text(config, "admin", "opensearch2Password");
        String index = OutputContext.text(config, "enterprise_kb", "opensearch2IndexName");
        int dimensions = OutputContext.integer(config, 1024, "opensearch2Dimensions");

        OpenSearchClient client = OpenSearch2StoreConfig.client(uris, username, password);
        OpenSearch2StoreConfig.initializeIndex(client, index, dimensions);
        boolean pipelineWrites = pipelineWrites(context, uris, index);
        log.info("Resolving dynamic OpenSearch 2.x output connector for index '{}' at '{}'", index, uris);
        return new OpenSearch2OutputConnector(client, uris, index, dimensions, context.service(EmbeddingModel.class),
                context.service(TextExtractionService.class), !pipelineWrites);
    }

    /** Whether the runtime's pipeline writer stores to this server and index (its output is the same OpenSearch). */
    public static boolean pipelineWrites(OutputContext context, String uris, String index) {
        OpenSearch2OutputConnector pipeline = context.pipelineOutput(OpenSearch2OutputConnector.class);
        return pipeline != null && Objects.equals(trimSlash(pipeline.uris()), trimSlash(uris))
                && Objects.equals(pipeline.indexName(), index);
    }

    private static String trimSlash(String uri) {
        return uri != null && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }
}
