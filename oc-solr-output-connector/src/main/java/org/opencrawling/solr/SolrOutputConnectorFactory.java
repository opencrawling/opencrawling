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
package org.opencrawling.solr;

import org.apache.solr.client.solrj.SolrClient;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.solr.config.SolrClientConfig;
import org.opencrawling.solr.config.SolrCollectionInitializer;
import org.opencrawling.solr.config.SolrOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Map;
import java.util.Objects;

/**
 * An Apache Solr output for one run, from its saved configuration (#319). Adds its vector field type and fields to
 * the collection when they are missing. A RAG run sends its documents to it directly unless the runtime's pipeline
 * writer stores to the same Solr (URL, or ZooKeeper hosts in cloud mode) and collection.
 */
public class SolrOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(SolrOutputConnectorFactory.class);

    @Override
    public String className() {
        return SolrOutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        SolrOutputProperties properties = properties(config);
        SolrClient client = SolrClientConfig.client(properties);
        new SolrCollectionInitializer(client, properties).initializeSchema();
        log.info("Resolving dynamic Solr output connector for collection '{}'", properties.collection());
        return new SolrOutputConnector(client, properties, context.service(EmbeddingModel.class),
                context.service(TextExtractionService.class), !pipelineWrites(context, properties));
    }

    /** The settings a saved Solr output connector's configuration describes. */
    static SolrOutputProperties properties(Map<String, String> config) {
        return new SolrOutputProperties(
                OutputContext.text(config, "standalone", "solrMode"),
                OutputContext.text(config, "http://localhost:8983/solr", "solrUrl"),
                OutputContext.text(config, "localhost:2181", "solrZkHost"),
                OutputContext.text(config, "enterprise_kb", "solrCollection"),
                OutputContext.integer(config, 1024, "solrDimensions"),
                OutputContext.text(config, "cosine", "solrSimilarity"),
                OutputContext.text(config, "FLOAT32", "solrVectorEncoding"),
                OutputContext.text(config, "none", "solrQuantization"),
                OutputContext.integer(config, 16, "solrHnswMaxConnections"),
                OutputContext.integer(config, 100, "solrHnswBeamWidth"),
                OutputContext.integer(config, 100, "solrEfSearch"),
                OutputContext.integer(config, 1000, "solrCommitWithinMs"),
                OutputContext.text(config, null, "solrUsername"),
                OutputContext.text(config, null, "solrPassword"));
    }

    /** Whether the runtime's pipeline writer stores to this Solr and collection. */
    public static boolean pipelineWrites(OutputContext context, SolrOutputProperties run) {
        SolrOutputConnector pipeline = context.pipelineOutput(SolrOutputConnector.class);
        if (pipeline == null || pipeline.getProperties() == null) {
            return false;
        }
        SolrOutputProperties own = pipeline.getProperties();
        boolean cloud = "cloud".equalsIgnoreCase(run.mode());
        return cloud == "cloud".equalsIgnoreCase(own.mode())
                && Objects.equals(cloud ? own.zkHost() : trimSlash(own.url()), cloud ? run.zkHost() : trimSlash(run.url()))
                && Objects.equals(own.collection(), run.collection());
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
