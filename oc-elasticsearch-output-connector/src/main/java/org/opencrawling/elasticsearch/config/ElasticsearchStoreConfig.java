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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Elasticsearch client when {@code spring.opencrawling.output.type=elasticsearch}.
 */
@Configuration
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "elasticsearch")
@EnableConfigurationProperties(ElasticsearchOutputProperties.class)
public class ElasticsearchStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchStoreConfig.class);

    private static final int INIT_ATTEMPTS = 10;
    private static final long INIT_DELAY_MILLIS = 3_000;

    @Bean(destroyMethod = "close")
    public ElasticsearchClient openCrawlingElasticsearchClient(ElasticsearchOutputProperties properties) {
        log.info("Initializing ElasticsearchClient connected to: {}", properties.hostUris());
        ElasticsearchClient client = ElasticsearchClientFactory.create(properties);
        if (properties.createIndex()) {
            new ElasticsearchIndexInitializer(client, properties).ensureIndex(INIT_ATTEMPTS, INIT_DELAY_MILLIS);
        }
        return client;
    }
}
