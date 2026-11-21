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
package org.opencrawling.seatunnel;

import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.messaging.PipelineTopics;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.seatunnel.client.SeaTunnelRestClient;
import org.opencrawling.seatunnel.config.SeaTunnelOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

/**
 * A SeaTunnel output for one run, from its saved configuration (#319).
 *
 * <p>Reads the keys the admin UI saves ({@code seaTunnelRestUrl}, ...); the all-lower-case spelling
 * ({@code seatunnelRestUrl}) that per-job SeaTunnel outputs read before is still accepted.
 */
public class SeaTunnelOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(SeaTunnelOutputConnectorFactory.class);

    @Override
    public String className() {
        return SeaTunnelOutputConnector.class.getName();
    }

    @Override
    @SuppressWarnings("unchecked")
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        SeaTunnelOutputProperties properties = properties(config);
        SeaTunnelRestClient restClient = new SeaTunnelRestClient(properties.restUrl(), properties.timeoutSeconds());
        log.info("Resolving dynamic SeaTunnel output connector for endpoint '{}'", properties.restUrl());
        return new SeaTunnelOutputConnector(restClient, properties, context.service(EmbeddingModel.class),
                (KafkaTemplate<String, Object>) context.service(KafkaTemplate.class),
                context.service(TextExtractionService.class));
    }

    static SeaTunnelOutputProperties properties(Map<String, String> config) {
        return new SeaTunnelOutputProperties(
                OutputContext.text(config, "http://localhost:8080", "seaTunnelRestUrl", "seatunnelRestUrl"),
                OutputContext.text(config, "opencrawling_ingestion_pipeline", "seaTunnelJobName", "seatunnelJobName"),
                OutputContext.text(config, "STREAMING", "seaTunnelJobMode", "seatunnelJobMode"),
                OutputContext.integer(config, 5000, "seaTunnelCheckpointIntervalMs", "seatunnelCheckpointIntervalMs"),
                OutputContext.integer(config, 4, "seaTunnelParallelism", "seatunnelParallelism"),
                OutputContext.text(config, "localhost:9092", "seaTunnelKafkaBootstrapServers",
                        "seatunnelKafkaBootstrapServers"),
                OutputContext.text(config, PipelineTopics.EMBEDDED, "seaTunnelKafkaTopic", "seatunnelKafkaTopic"),
                OutputContext.text(config, "opencrawling-seatunnel-group", "seaTunnelKafkaGroupId",
                        "seatunnelKafkaGroupId"),
                OutputContext.text(config, "console", "seaTunnelTargetSinks", "seatunnelTargetSinks"),
                OutputContext.integer(config, 1024, "seaTunnelDimensions", "seatunnelDimensions"),
                OutputContext.flag(config, true, "seaTunnelAutoSubmitJob", "seatunnelAutoSubmitJob"),
                OutputContext.integer(config, 30, "seaTunnelTimeoutSeconds", "seatunnelTimeoutSeconds"));
    }
}
