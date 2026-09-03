/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.solr.config;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

@Configuration
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "solr")
@EnableConfigurationProperties(SolrOutputProperties.class)
public class SolrClientConfig {

    private static final Logger log = LoggerFactory.getLogger(SolrClientConfig.class);

    @Bean(destroyMethod = "close")
    public SolrClient solrClient(SolrOutputProperties properties) {
        if ("cloud".equalsIgnoreCase(properties.mode())) {
            log.info("Initializing CloudSolrClient pointing to ZooKeeper host: {}", properties.zkHost());
            List<String> zkHosts = List.of(properties.zkHost().split(","));
            return new CloudSolrClient.Builder(zkHosts, Optional.empty()).build();
        } else {
            log.info("Initializing HttpJdkSolrClient pointing to Solr URL: {}", properties.url());
            String baseUrl = properties.url();
            return new HttpJdkSolrClient.Builder(baseUrl).build();
        }
    }
}
